package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.PrefillAccepted
import de.aidadventure.web.api.PrefillRequest
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnRequest
import de.aidadventure.web.api.WarmupRequest
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.persistence.ScenarioRepository
import de.aidadventure.web.persistence.SessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.await
import react.create
import react.dom.client.createRoot
import react.dom.flushSync
import web.dom.document
import web.html.HTMLElement

class PrefillSubmissionTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `resuming a session renders PlayView and submits one complete warmup`() = GlobalScope.promise<Unit> {
        val api = RecordingAdventureApi()
        val scenario = Scenario("scenario-7", "harbor.md", "# Harbor\nStorm signals", 1)
        val savedSession = GameSession(
            id = "persisted-session-7",
            scenarioId = scenario.id,
            title = "Harbor watch",
            modelId = "saved-model",
            messages = listOf(
                GameMessage("player", "Watch the pier", 1),
                GameMessage("game-master", "A bell rings", 2),
            ),
            createdAt = 1,
            updatedAt = 2,
            llamaCppPort = 9091,
        )
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)

        try {
            flushSync {
                root.render(AdventureApp.create {
                    scenarioRepository = staticScenarioRepository(scenario)
                    sessionRepository = staticSessionRepository(savedSession)
                    this.api = api
                    initialScenarios = listOf(scenario)
                })
            }

            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            assertNotNull(container.buttonNamed(savedSession.title))
            container.clickButtonNamed(savedSession.title)
            nextBrowserTick()
            nextBrowserTick()

            assertNotNull(container.querySelector("#player-action"))
            assertEquals(
                listOf(
                    WarmupRequest(
                        sessionId = "persisted-session-7",
                        scenarioMarkdown = "# Harbor\nStorm signals",
                        history = listOf(
                            de.aidadventure.web.api.ChatEntry("player", "Watch the pier"),
                            de.aidadventure.web.api.ChatEntry("game-master", "A bell rings"),
                        ),
                    ),
                ),
                api.warmupRequests,
            )
        } finally {
            root.unmount()
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `resume warmup reaches API once with the stored session ID`() = GlobalScope.promise<Unit> {
        val api = RecordingAdventureApi()

        WarmupSubmission(api).submit(session(), scenario())
        nextTick()

        assertEquals(listOf("stored-1"), api.warmedSessionIds)
        assertEquals(1, api.warmupRequests.size)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `debounce sends only newest complete input`() = GlobalScope.promise<Unit> {
        val scheduler = ManualPrefillScheduler()
        val api = RecordingAdventureApi()
        val prefill = PrefillSubmission(api, scheduler)

        prefill.changed(session(), scenario(), "live-1", "open")
        prefill.changed(session(), scenario(), "live-1", "open door")
        scheduler.runAll()
        nextTick()

        assertEquals(listOf("open door"), api.prefilledActions)
        assertEquals(listOf(2L), api.prefillRevisions)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `turn start cancels queued prefill`() = GlobalScope.promise<Unit> {
        val scheduler = ManualPrefillScheduler()
        val api = RecordingAdventureApi()
        val prefill = PrefillSubmission(api, scheduler)

        prefill.changed(session(), scenario(), "live-1", "open")
        prefill.turnStarted()
        scheduler.runAll()

        assertTrue(api.prefilledActions.isEmpty())
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `a completed turn allows prefill for the next action`() = GlobalScope.promise<Unit> {
        val scheduler = ManualPrefillScheduler()
        val api = RecordingAdventureApi()
        val prefill = PrefillSubmission(api, scheduler)

        prefill.turnStarted()
        prefill.turnFinished()
        prefill.changed(session(), scenario(), "live-1", "open the door")
        scheduler.runAll()
        nextTick()

        assertEquals(listOf("open the door"), api.prefilledActions)
    }

    @Test
    fun `clearing input cancels queued prefill`() {
        val scheduler = ManualPrefillScheduler()
        val api = RecordingAdventureApi()
        val prefill = PrefillSubmission(api, scheduler)

        prefill.changed(session(), scenario(), "live-1", "open")
        prefill.changed(session(), scenario(), "live-1", "")
        scheduler.runAll()

        assertTrue(api.prefilledActions.isEmpty())
    }

    private fun session() = GameSession(
        id = "stored-1",
        scenarioId = "scenario-1",
        title = "Test adventure",
        modelId = "test-model",
        messages = emptyList(),
        createdAt = 1,
        updatedAt = 1,
        llamaCppPort = 8081,
    )

    private fun scenario() = Scenario("scenario-1", "test.md", "# Test", 1)

    private suspend fun nextTick() = kotlin.js.Promise<Unit> { resolve, _ ->
        kotlinx.browser.window.setTimeout({ resolve(Unit) }, 0)
    }.await()

    private fun staticScenarioRepository(scenario: Scenario) = object : ScenarioRepository {
        override suspend fun list() = listOf(scenario)
        override suspend fun save(scenario: Scenario) = Unit
        override suspend fun find(id: String) = null
        override suspend fun delete(id: String) = Unit
    }

    private fun staticSessionRepository(session: GameSession) = object : SessionRepository {
        override suspend fun list() = listOf(session)
        override suspend fun save(session: GameSession) = Unit
        override suspend fun find(id: String) = null
        override suspend fun delete(id: String) = Unit
    }

    private fun HTMLElement.click(selector: String) {
        querySelector(selector)
            ?.asDynamic()
            ?.dispatchEvent(js("new MouseEvent('click', { bubbles: true })"))
    }

    private fun HTMLElement.clickButtonNamed(label: String) {
        buttonNamed(label)?.asDynamic()?.dispatchEvent(js("new MouseEvent('click', { bubbles: true })"))
    }

    private fun HTMLElement.buttonNamed(label: String): web.dom.Element? {
        val buttons = querySelectorAll("button")
        for (index in 0 until buttons.length) {
            val button = buttons.item(index) ?: continue
            if (button.textContent == label) return button
        }
        return null
    }

    private suspend fun nextBrowserTick() {
        kotlin.js.Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 0) }.await()
    }
}

private class ManualPrefillScheduler : PrefillScheduler {
    private var task: (() -> Unit)? = null

    override fun schedule(delayMs: Int, task: () -> Unit): Any {
        require(delayMs == 150)
        this.task = task
        return Any()
    }

    override fun cancel(handle: Any) {
        task = null
    }

    fun runAll() {
        task?.also { task = null }?.invoke()
    }
}

private class RecordingAdventureApi : AdventureApi {
    val prefilledActions = mutableListOf<String>()
    val prefillRevisions = mutableListOf<Long>()
    val warmupRequests = mutableListOf<WarmupRequest>()
    val warmedSessionIds get() = warmupRequests.map { it.sessionId }

    override suspend fun prefill(request: PrefillRequest): PrefillAccepted {
        prefilledActions += request.playerAction
        prefillRevisions += request.revision
        return PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
    }

    override suspend fun warmup(request: WarmupRequest): PrefillAccepted {
        warmupRequests += request
        return PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
    }

    override suspend fun play(request: TurnRequest, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnMetrics = error("unused")
}
