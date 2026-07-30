package de.aidadventure.web.persistence

import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JSON
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

interface ScenarioRepository {
    suspend fun list(): List<Scenario>
    suspend fun save(scenario: Scenario)
    suspend fun find(id: String): Scenario?
    suspend fun delete(id: String)
}

interface SessionRepository {
    suspend fun list(): List<GameSession>
    suspend fun save(session: GameSession)
    suspend fun find(id: String): GameSession?
    suspend fun delete(id: String)
}

sealed class PersistenceError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unavailable(cause: Throwable? = null) : PersistenceError("Browser IndexedDB is unavailable.", cause)
    class DatabaseFailure(val operation: String, cause: Throwable? = null) :
        PersistenceError("IndexedDB failed during $operation.", cause)
}

class AdventureDatabase private constructor(private val database: dynamic) {
    private val scenarioStore = RepositoryStore<Scenario>(database, SCENARIOS, Scenario.serializer())
    private val sessionStore = RepositoryStore(database, SESSIONS, GameSession.serializer(), ::decodePersistedGameSession)

    val scenarios: ScenarioRepository = object : ScenarioRepository {
        override suspend fun list(): List<Scenario> = scenarioStore.list()
        override suspend fun save(scenario: Scenario) = scenarioStore.save(scenario)
        override suspend fun find(id: String): Scenario? = scenarioStore.find(id)
        override suspend fun delete(id: String) = deleteScenarioAndSessions(id)
    }

    val sessions: SessionRepository = object : SessionRepository {
        override suspend fun list(): List<GameSession> = sessionStore.list()
        override suspend fun save(session: GameSession) = sessionStore.save(session)
        override suspend fun find(id: String): GameSession? = sessionStore.find(id)
        override suspend fun delete(id: String) = sessionStore.delete(id)
    }

    private suspend fun deleteScenarioAndSessions(scenarioId: String) {
        val transaction = try {
            database.transaction(arrayOf(SCENARIOS, SESSIONS), "readwrite")
        } catch (cause: Throwable) {
            throw PersistenceError.DatabaseFailure("opening scenario deletion transaction", cause)
        }
        val observer = TransactionObserver(transaction, "deleting scenario")
        try {
            transaction.objectStore(SCENARIOS).delete(scenarioId)
            val sessions = transaction.objectStore(SESSIONS)
            awaitRequest(sessions.index("scenarioId").getAll(scenarioId), "finding scenario sessions") { result ->
                result.unsafeCast<Array<dynamic>>().forEach { session -> sessions.delete(session.id) }
                Unit
            }
            observer.awaitCompletion()
        } catch (cause: Throwable) {
            throw when (cause) {
                is PersistenceError -> cause
                else -> PersistenceError.DatabaseFailure("deleting scenario", cause)
            }
        }
    }

    fun close() {
        database.close()
    }

    companion object {
        private const val NAME = "ai-adventure"
        private const val VERSION = 2
        private const val SCENARIOS = "scenarios"
        private const val SESSIONS = "sessions"

        suspend fun open(name: String = NAME): AdventureDatabase {
            val indexedDb = js("globalThis.indexedDB")
                ?: throw PersistenceError.Unavailable()
            val request = try {
                indexedDb.open(name, VERSION)
            } catch (cause: Throwable) {
                throw PersistenceError.Unavailable(cause)
            }
            request.onupgradeneeded = { _: dynamic ->
                val db = request.result
                if (!db.objectStoreNames.contains(SCENARIOS)) {
                    db.createObjectStore(SCENARIOS, js("({ keyPath: 'id' })"))
                }
                if (!db.objectStoreNames.contains(SESSIONS)) {
                    val sessionStore = db.createObjectStore(SESSIONS, js("({ keyPath: 'id' })"))
                    sessionStore.createIndex("scenarioId", "scenarioId", js("({ unique: false })"))
                }
                null
            }
            return awaitRequest(request, "opening database") {
                it.onversionchange = { _: dynamic ->
                    it.close()
                    null
                }
                AdventureDatabase(it)
            }
        }
    }
}

private class RepositoryStore<T>(
    private val database: dynamic,
    private val storeName: String,
    private val serializer: kotlinx.serialization.KSerializer<T>,
    private val decoder: ((String) -> T)? = null,
) {
    suspend fun list(): List<T> {
        val transaction = transaction("readonly")
        return awaitRequestAndTransaction(transaction, "listing $storeName", {
            transaction.objectStore(storeName).getAll()
        }) { result ->
            result.unsafeCast<Array<dynamic>>().map(::decode)
        }
    }

    suspend fun save(value: T) {
        val encoded = encode(value)
        val transaction = transaction("readwrite")
        awaitRequestAndTransaction(transaction, "saving $storeName", {
            transaction.objectStore(storeName).put(encoded)
        }) { Unit }
    }

    suspend fun find(id: String): T? {
        val transaction = transaction("readonly")
        return awaitRequestAndTransaction(transaction, "reading $storeName", {
            transaction.objectStore(storeName).get(id)
        }) { result ->
            if (result == null || js("result === undefined") as Boolean) null else decode(result)
        }
    }

    suspend fun delete(id: String) {
        val transaction = transaction("readwrite")
        awaitRequestAndTransaction(transaction, "deleting $storeName", {
            transaction.objectStore(storeName).delete(id)
        }) { Unit }
    }

    private fun transaction(mode: String): dynamic = try {
        database.transaction(storeName, mode)
    } catch (cause: Throwable) {
        throw PersistenceError.DatabaseFailure("opening $storeName transaction", cause)
    }

    private fun encode(value: T): dynamic = JSON.parse(Json.encodeToString(serializer, value))
    private fun decode(value: dynamic): T = decoder?.invoke(JSON.stringify(value)) ?: Json.decodeFromString(serializer, JSON.stringify(value))
}

internal fun decodePersistedGameSession(source: String): GameSession {
    val root = Json.parseToJsonElement(source).jsonObject
    val worldState = root["worldState"]?.jsonObject
    val migratedRoot = worldState?.let { state ->
        val legacyFocus = state["unresolvedSituation"] ?: state["activeThread"]
        val withoutLegacyKeys = state - "unresolvedSituation" - "activeThread"
        val migratedState = if ("narrativeFocus" in state || legacyFocus == null) withoutLegacyKeys else withoutLegacyKeys + ("narrativeFocus" to legacyFocus)
        JsonObject(root + ("worldState" to JsonObject(migratedState)))
    } ?: root
    return Json.decodeFromJsonElement(GameSession.serializer(), migratedRoot)
}

internal fun sessionIdsForScenarioDeletion(scenarioId: String, sessions: List<GameSession>): List<String> =
    sessions.filter { it.scenarioId == scenarioId }.map { it.id }

private suspend fun <T> awaitRequest(request: dynamic, operation: String, transform: (dynamic) -> T): T =
    suspendCoroutine { continuation ->
        request.onsuccess = { _: dynamic ->
            try {
                continuation.resume(transform(request.result))
            } catch (cause: Throwable) {
                continuation.resumeWithException(PersistenceError.DatabaseFailure(operation, cause))
            }
            null
        }
        request.onerror = { _: dynamic ->
            continuation.resumeWithException(PersistenceError.DatabaseFailure(operation, request.error as? Throwable))
            null
        }
    }

internal suspend fun <T> awaitRequestAndTransaction(
    transaction: dynamic,
    operation: String,
    request: () -> dynamic,
    transform: (dynamic) -> T,
): T {
    val transactionObserver = TransactionObserver(transaction, operation)
    val requestResult = try {
        Result.success(awaitRequest(request(), operation, transform))
    } catch (cause: Throwable) {
        Result.failure(cause)
    }

    try {
        transactionObserver.awaitCompletion()
    } catch (transactionFailure: Throwable) {
        requestResult.exceptionOrNull()?.let { throw it }
        throw transactionFailure
    }
    return requestResult.getOrThrow()
}

private class TransactionObserver(transaction: dynamic, operation: String) {
    private var completion: Result<Unit>? = null
    private var continuation: Continuation<Unit>? = null

    init {
        transaction.oncomplete = { _: dynamic -> finish(Result.success(Unit)); null }
        transaction.onerror = { _: dynamic ->
            finish(Result.failure(PersistenceError.DatabaseFailure(operation, transaction.error as? Throwable)))
            null
        }
        transaction.onabort = { _: dynamic ->
            finish(Result.failure(PersistenceError.DatabaseFailure(operation, transaction.error as? Throwable)))
            null
        }
    }

    suspend fun awaitCompletion() {
        suspendCoroutine<Unit> { waiter ->
            completion?.let { waiter.resumeWith(it) } ?: run { continuation = waiter }
        }
    }

    private fun finish(result: Result<Unit>) {
        completion = result
        continuation?.let { waiter ->
            continuation = null
            waiter.resumeWith(result)
        }
    }
}
