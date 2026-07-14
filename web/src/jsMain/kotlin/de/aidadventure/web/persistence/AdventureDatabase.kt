package de.aidadventure.web.persistence

import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JSON
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface ScenarioRepository {
    suspend fun list(): List<Scenario>
    suspend fun save(scenario: Scenario)
    suspend fun find(id: String): Scenario?
}

interface SessionRepository {
    suspend fun list(): List<GameSession>
    suspend fun save(session: GameSession)
    suspend fun find(id: String): GameSession?
}

sealed class PersistenceError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unavailable(cause: Throwable? = null) : PersistenceError("Browser IndexedDB is unavailable.", cause)
    class DatabaseFailure(val operation: String, cause: Throwable? = null) :
        PersistenceError("IndexedDB failed during $operation.", cause)
}

class AdventureDatabase private constructor(private val database: dynamic) {
    private val scenarioStore = RepositoryStore<Scenario>(database, SCENARIOS, Scenario.serializer())
    private val sessionStore = RepositoryStore<GameSession>(database, SESSIONS, GameSession.serializer())

    val scenarios: ScenarioRepository = object : ScenarioRepository {
        override suspend fun list(): List<Scenario> = scenarioStore.list()
        override suspend fun save(scenario: Scenario) = scenarioStore.save(scenario)
        override suspend fun find(id: String): Scenario? = scenarioStore.find(id)
    }

    val sessions: SessionRepository = object : SessionRepository {
        override suspend fun list(): List<GameSession> = sessionStore.list()
        override suspend fun save(session: GameSession) = sessionStore.save(session)
        override suspend fun find(id: String): GameSession? = sessionStore.find(id)
    }

    companion object {
        private const val NAME = "ai-adventure"
        private const val VERSION = 1
        private const val SCENARIOS = "scenarios"
        private const val SESSIONS = "sessions"

        suspend fun open(): AdventureDatabase {
            val indexedDb = js("globalThis.indexedDB")
                ?: throw PersistenceError.Unavailable()
            val request = try {
                indexedDb.open(NAME, VERSION)
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
            return awaitRequest(request, "opening database") { AdventureDatabase(it) }
        }
    }
}

private class RepositoryStore<T>(
    private val database: dynamic,
    private val storeName: String,
    private val serializer: kotlinx.serialization.KSerializer<T>,
) {
    suspend fun list(): List<T> {
        val transaction = transaction("readonly")
        val values = awaitRequest(transaction.objectStore(storeName).getAll(), "listing $storeName") { result ->
            result.unsafeCast<Array<dynamic>>().map(::decode)
        }
        awaitTransaction(transaction, "listing $storeName")
        return values
    }

    suspend fun save(value: T) {
        val transaction = transaction("readwrite")
        awaitRequest(transaction.objectStore(storeName).put(encode(value)), "saving $storeName") { Unit }
        awaitTransaction(transaction, "saving $storeName")
    }

    suspend fun find(id: String): T? {
        val transaction = transaction("readonly")
        val value = awaitRequest(transaction.objectStore(storeName).get(id), "reading $storeName") { result ->
            if (result == null || js("result === undefined") as Boolean) null else decode(result)
        }
        awaitTransaction(transaction, "reading $storeName")
        return value
    }

    private fun transaction(mode: String): dynamic = try {
        database.transaction(storeName, mode)
    } catch (cause: Throwable) {
        throw PersistenceError.DatabaseFailure("opening $storeName transaction", cause)
    }

    private fun encode(value: T): dynamic = JSON.parse(Json.encodeToString(serializer, value))
    private fun decode(value: dynamic): T = Json.decodeFromString(serializer, JSON.stringify(value))
}

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

private suspend fun awaitTransaction(transaction: dynamic, operation: String) {
    suspendCoroutine<Unit> { continuation ->
        transaction.oncomplete = { _: dynamic -> continuation.resume(Unit); null }
        transaction.onerror = { _: dynamic ->
            continuation.resumeWithException(PersistenceError.DatabaseFailure(operation, transaction.error as? Throwable))
            null
        }
        transaction.onabort = { _: dynamic ->
            continuation.resumeWithException(PersistenceError.DatabaseFailure(operation, transaction.error as? Throwable))
            null
        }
    }
}
