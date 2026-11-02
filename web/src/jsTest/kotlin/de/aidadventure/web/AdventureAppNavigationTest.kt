package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.PrefillAccepted
import de.aidadventure.web.api.PrefillRequest
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnCompletion
import de.aidadventure.web.api.TurnRequest
import de.aidadventure.web.api.WarmupRequest
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.DebugEvent
import de.aidadventure.web.model.DiagnosticMetrics
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.model.StreamDiagnostic
import de.aidadventure.web.model.WorldState
import de.aidadventure.web.persistence.ScenarioRepository
import de.aidadventure.web.persistence.DebugEventRepository
import de.aidadventure.web.persistence.SessionRepository
import kotlin.js.Promise
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import react.create
import react.dom.client.createRoot
import react.dom.flushSync
import web.dom.document
import web.html.HTMLElement

class AdventureAppNavigationTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test fun `action queued after input readiness waits for final flags without aborting checks`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "Harbor", "---\nflags: {visited: false}\n---\n# Harbor", 1)
        val session = GameSession("session", scenario.id, "Saved game", messages = listOf(GameMessage("game-master", "Welcome", 1)), createdAt = 1, updatedAt = 1)
        val firstReady = CompletableDeferred<Unit>()
        val finishChecks = CompletableDeferred<Unit>()
        val nextRequest = CompletableDeferred<TurnRequest>()
        val saved = mutableListOf<GameSession>()
        var stops = 0
        var turns = 0
        val sessions = object : SessionRepository {
            override suspend fun list() = listOf(session)
            override suspend fun save(session: GameSession) { saved += session }
            override suspend fun find(id: String) = session
            override suspend fun delete(id: String) = Unit
        }
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
            override suspend fun warmup(request: WarmupRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
            override fun stopActiveTurn() { stops++ }
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
                turns++
                onToken("You enter.")
                onInputReady()
                if (turns == 1) {
                    firstReady.complete(Unit)
                    finishChecks.await()
                } else nextRequest.complete(request)
                return TurnCompletion(TurnMetrics(null, null, null), WorldState(), flags = mapOf("visited" to true))
            }
        }
        suspend fun enterAction(value: String) {
            val input = container.querySelector("#player-action").asDynamic()
            js("Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(input, value)")
            input.dispatchEvent(js("new Event('input', { bubbles: true })"))
            nextBrowserTick()
            container.click(".action-form button.primary-button")
            nextBrowserTick()
        }
        try {
            flushSync { root.render(AdventureApp.create {
                scenarioRepository = scenarioRepository(scenario)
                sessionRepository = sessions
                this.api = api
                initialScenarios = listOf(scenario)
            }) }
            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed(session.title)
            nextBrowserTick()
            enterAction("enter")
            withTimeout(2000) { firstReady.await() }
            nextBrowserTick()
            enterAction("look around")
            assertEquals(0, stops, "Background event checks must keep their active stream")
            assertEquals(false, nextRequest.isCompleted)
            finishChecks.complete(Unit)
            val following = withTimeout(2000) { nextRequest.await() }
            assertEquals("look around", following.playerAction)
            assertEquals(true, following.flags["visited"])
            assertEquals(true, saved.any { it.flags["visited"] == true })
        } finally {
            finishChecks.complete(Unit)
            nextBrowserTick()
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `opening debug panel recovers a persisted scheduled outcome after reload`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val session = GameSession("session", scenario.id, "Harbor", messages = listOf(
            GameMessage("game-master", "Saved welcome", 2, messageId = "saved-output"),
        ), createdAt = 1, updatedAt = 2)
        val operationId = "operation-panel-recovery"
        val scheduled = DebugEvent("scheduled", "session", "old-turn", 2, 2, "background_rerank", "scheduled",
            fields = mapOf("operationId" to operationId), transcriptRole = "game-master",
            transcriptIndex = 0, transcriptMessageId = "saved-output")
        val events = recordingDebugEvents(scheduled)
        val fetched = CompletableDeferred<Unit>()
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
            override suspend fun warmup(request: WarmupRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("unused")
            override suspend fun awaitBackgroundDiagnostic(sessionId: String, operationId: String): StreamDiagnostic? {
                assertEquals("session", sessionId)
                assertEquals("operation-panel-recovery", operationId)
                fetched.complete(Unit)
                return StreamDiagnostic("background_rerank", "prior_completed", mapOf("operationId" to operationId, "source" to "prior_turn"), "player")
            }
        }
        try {
            flushSync { root.render(AdventureApp.create {
                scenarioRepository = scenarioRepository(scenario)
                sessionRepository = fixedSessions(session)
                debugEventRepository = events
                this.api = api
                initialScenarios = listOf(scenario)
            }) }
            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed(session.title)
            nextBrowserTick()
            assertEquals(false, fetched.isCompleted)
            container.click("button[aria-label='Open debug panel']")
            withTimeout(1000) { fetched.await() }
            nextBrowserTick()
            val outcome = events.saved.single { it.stage == "prior_completed" }
            assertEquals(scheduled.turnId, outcome.turnId)
            assertEquals("saved-output", outcome.transcriptMessageId)
        } finally { flushSync { root.unmount() }; container.remove() }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `resumed introduction navigates to its prior turn diagnostic after a failed retry`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val session = GameSession("session", scenario.id, "Harbor", messages = listOf(
            GameMessage("player", "hidden introduction", 1, hidden = true, messageId = "hidden-intro"),
            GameMessage("game-master", "Saved welcome", 2, messageId = "saved-intro-output"),
        ), createdAt = 1, updatedAt = 2)
        val events = recordingDebugEvents(
            DebugEvent("stale-intro", "session", "failed-intro", 0, 1, "turn_status", "started", transcriptRole = "game-master", transcriptIndex = 0, transcriptMessageId = "discarded-intro-output"),
            DebugEvent("prior-intro", "session", "next-turn", 0, 3, "background_rerank", "prior_completed", fields = mapOf("source" to "prior_turn", "result" to "accepted"), transcriptRole = "game-master", transcriptIndex = 0, transcriptMessageId = "saved-intro-output"),
        )
        val elementPrototype = js("Element.prototype")
        val oldBounds = elementPrototype.getBoundingClientRect
        elementPrototype.getBoundingClientRect = js("(function (original) { return function () { if (/^(debug-event-|transcript-message-)/.test(this.id)) window.__lastScrolledId = this.id; return original.call(this); }; })")(oldBounds)
        try {
            flushSync { root.render(AdventureApp.create {
                scenarioRepository = scenarioRepository(scenario)
                sessionRepository = fixedSessions(session)
                debugEventRepository = events
                api = unusedApi()
                initialScenarios = listOf(scenario)
            }) }
            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed(session.title)
            nextBrowserTick()
            assertNull(container.querySelector("#transcript-message-1"))
            container.click("button[aria-label='Open debug panel']")
            nextBrowserTick()
            container.doubleClick("#transcript-message-0")
            nextBrowserTick()
            nextBrowserTick()
            assertEquals("debug-event-prior-intro", window.asDynamic().__lastScrolledId)
            assertNull(container.querySelector("#debug-event-stale-intro")?.getAttribute("tabindex"))
            container.doubleClick("#debug-event-prior-intro")
            nextBrowserTick()
            assertEquals("transcript-message-0", window.asDynamic().__lastScrolledId)
            assertNotNull(container.querySelector("[role='dialog'][aria-label='Debug panel']"))
        } finally {
            elementPrototype.getBoundingClientRect = oldBounds
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `resumed retry does not link a failed turn event to the replacement player`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val session = GameSession("session", scenario.id, "Harbor", messages = listOf(
            GameMessage("player", "retry action", 3, messageId = "retry-player"),
            GameMessage("game-master", "retry response", 4, messageId = "retry-output"),
        ), createdAt = 1, updatedAt = 4)
        val events = recordingDebugEvents(
            DebugEvent("failed", "session", "failed-turn", 0, 2, "turn_state", "initial", fields = mapOf("location" to "Harbor"), transcriptRole = "player", transcriptIndex = 0, transcriptMessageId = "failed-player"),
            DebugEvent("retry", "session", "retry-turn", 0, 3, "turn_state", "initial", fields = mapOf("location" to "Harbor"), transcriptRole = "player", transcriptIndex = 0, transcriptMessageId = "retry-player"),
        )
        val elementPrototype = js("Element.prototype")
        val oldBounds = elementPrototype.getBoundingClientRect
        elementPrototype.getBoundingClientRect = js("(function (original) { return function () { if (/^(debug-event-|transcript-message-)/.test(this.id)) window.__lastScrolledId = this.id; return original.call(this); }; })")(oldBounds)
        try {
            flushSync { root.render(AdventureApp.create {
                scenarioRepository = scenarioRepository(scenario)
                sessionRepository = fixedSessions(session)
                debugEventRepository = events
                api = unusedApi()
                initialScenarios = listOf(scenario)
            }) }
            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed(session.title)
            nextBrowserTick()
            container.click("button[aria-label='Open debug panel']")
            nextBrowserTick()
            container.doubleClick("#transcript-message-0")
            nextBrowserTick()
            nextBrowserTick()
            assertEquals("debug-event-retry", window.asDynamic().__lastScrolledId)
            assertNotNull(container.querySelector("#debug-event-retry"))
            assertNull(container.querySelector("#debug-event-failed")?.getAttribute("tabindex"))
        } finally {
            elementPrototype.getBoundingClientRect = oldBounds
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `resumed transcript and diagnostic events navigate in both directions`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val session = GameSession("session", scenario.id, "Harbor", messages = listOf(
            GameMessage("player", "hidden introduction", 1, hidden = true, messageId = "intro-input"),
            GameMessage("game-master", "Welcome", 2, messageId = "intro-output"),
            GameMessage("player", "Visit pier", 3, messageId = "player-action"),
            GameMessage("game-master", "At the pier", 4, messageId = "player-output"),
        ), createdAt = 1, updatedAt = 4)
        val events = recordingDebugEvents(
            DebugEvent("intro-event", "session", "intro", 0, 2, "turn_status", "completed", fields = mapOf("output" to "Welcome"), transcriptRole = "game-master", transcriptIndex = 0, transcriptMessageId = "intro-output"),
            DebugEvent("player-event", "session", "turn", 0, 3, "turn_state", "initial", fields = mapOf("location" to "Harbor"), transcriptRole = "player", transcriptIndex = 1, transcriptMessageId = "player-action"),
        )
        val elementPrototype = js("Element.prototype")
        val oldBounds = elementPrototype.getBoundingClientRect
        elementPrototype.getBoundingClientRect = js("(function (original) { return function () { if (/^(debug-event-|transcript-message-)/.test(this.id)) window.__lastScrolledId = this.id; return original.call(this); }; })")(oldBounds)
        try {
            flushSync { root.render(AdventureApp.create {
                scenarioRepository = scenarioRepository(scenario)
                sessionRepository = fixedSessions(session)
                debugEventRepository = events
                api = unusedApi()
                initialScenarios = listOf(scenario)
            }) }
            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed(session.title)
            nextBrowserTick()
            assertNull(container.querySelector("#transcript-message-3"))
            assertNotNull(container.querySelector("#transcript-message-1"))
            container.click("button[aria-label='Open debug panel']")
            nextBrowserTick()
            container.doubleClick("#transcript-message-1")
            nextBrowserTick()
            assertNotNull(container.querySelector("#debug-event-player-event"))
            nextBrowserTick()
            assertEquals("debug-event-player-event", window.asDynamic().__lastScrolledId)
            assertNotNull(container.querySelector("[role='dialog'][aria-label='Debug panel']"))
            container.doubleClick("#debug-event-player-event")
            nextBrowserTick()
            assertEquals("transcript-message-1", window.asDynamic().__lastScrolledId)
            assertNotNull(container.querySelector("[role='dialog'][aria-label='Debug panel']"))
            assertNull(container.querySelector(".debug-navigation-button"))
        } finally {
            elementPrototype.getBoundingClientRect = oldBounds
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @Test
    fun `new session persists the initial situation location`() {
        val scenario = Scenario(
            "harbor", "harbor.md", """
            ---
            initial-situation:
              world-state:
                location: Harbor
              setting: The keeper greets the player.
            ---
            # Harbor
            """.trimIndent(), 1,
        )

        val session = newGameSession(scenario, "session-1", 2)

        assertEquals(
            WorldState("Harbor"),
            session.worldState,
        )
    }

    @Test
    fun `creating a new session does not change an existing session world state`() {
        val scenario = Scenario(
            "harbor", "harbor.md", """
            ---
            world-state:
              location: Harbor
            ---
            # Harbor
            """.trimIndent(), 1,
        )
        val existing = GameSession(
            "existing", scenario.id, "Existing adventure", messages = emptyList(), createdAt = 1, updatedAt = 1,
            worldState = WorldState("Pier"),
        )

        newGameSession(scenario, "new", 2)

        assertEquals(WorldState("Pier"), existing.worldState)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `new session introduces the adventure once and resumes its saved introduction`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Scenario", 1)
        val saved = mutableListOf<GameSession>()
        val requests = mutableListOf<TurnRequest>()
        try {
            flushSync {
                root.render(AdventureApp.create {
                    scenarioRepository = scenarioRepository(scenario)
                    sessionRepository = object : SessionRepository {
                        override suspend fun list() = saved.asReversed().distinctBy { it.id }
                        override suspend fun save(session: GameSession) { saved += session }
                        override suspend fun find(id: String) = saved.lastOrNull { it.id == id }
                        override suspend fun delete(id: String) = Unit
                    }
                    api = object : AdventureApi {
                        override suspend fun prefill(request: PrefillRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
                        override suspend fun warmup(request: WarmupRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
                        override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
                            requests += request
                            onToken("You stand at the harbor. What do you do?")
                            return TurnCompletion(TurnMetrics(null, null, null), WorldState("Harbor"))
                        }
                    }
                    initialScenarios = listOf(scenario)
                })
            }

            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            assertNull(container.buttonNamed("Find models"))
            assertNotNull(container.buttonNamed("New session"))
            container.clickButtonNamed("New session")
            nextBrowserTick()
            nextBrowserTick()

            assertNotNull(container.querySelector("#player-action"))
            assertEquals(1, requests.size)
            assertEquals(true, container.textContent?.contains("You stand at the harbor."))
            assertEquals("game-master", saved.last().messages.last().role)
            assertEquals(saved.last().id, requests.single().sessionId)
            assertEquals(false, saved.last().introductionPending)
            assertEquals(false, container.textContent?.contains(requests.single().playerAction))

            container.clickButtonNamed("Back to sessions")
            nextBrowserTick()
            container.clickButtonNamed(saved.last().title)
            nextBrowserTick()
            nextBrowserTick()
            assertEquals(1, requests.size)
            assertEquals(true, container.textContent?.contains("You stand at the harbor."))
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `deleting a scenario removes the selected scenario from the library`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Scenario", 1)
        val storedScenarios = mutableListOf(scenario)
        val deletedScenarioIds = mutableListOf<String>()
        val scenarios = object : ScenarioRepository {
            override suspend fun list() = storedScenarios.toList()
            override suspend fun save(scenario: Scenario) = Unit
            override suspend fun find(id: String) = storedScenarios.find { it.id == id }
            override suspend fun delete(id: String) {
                deletedScenarioIds += id
                storedScenarios.removeAll { it.id == id }
            }
        }
        try {
            flushSync {
                root.render(AdventureApp.create {
                    scenarioRepository = scenarios
                    sessionRepository = sessionRepository()
                    api = unusedApi()
                    initialScenarios = storedScenarios
                })
            }

            container.clickButtonNamed("Delete scenario")
            nextBrowserTick()
            nextBrowserTick()

            assertEquals(listOf("scenario"), deletedScenarioIds)
            assertNull(container.buttonNamed(scenario.name))
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `deleting a session removes only the selected session from the detail page`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Scenario", 1)
        val first = GameSession("first", scenario.id, "First session", "local-model", emptyList(), 1, 1)
        val second = GameSession("second", scenario.id, "Second session", "local-model", emptyList(), 1, 1)
        val storedSessions = mutableListOf(first, second)
        val deletedSessionIds = mutableListOf<String>()
        val sessions = object : SessionRepository {
            override suspend fun list() = storedSessions.toList()
            override suspend fun save(session: GameSession) = Unit
            override suspend fun find(id: String) = storedSessions.find { it.id == id }
            override suspend fun delete(id: String) {
                deletedSessionIds += id
                storedSessions.removeAll { it.id == id }
            }
        }
        try {
            flushSync {
                root.render(AdventureApp.create {
                    scenarioRepository = scenarioRepository(scenario)
                    sessionRepository = sessions
                    api = unusedApi()
                    initialScenarios = listOf(scenario)
                })
            }

            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            nextBrowserTick()
            container.clickButtonNamed("Delete session")
            nextBrowserTick()
            nextBrowserTick()

            assertEquals(listOf("first"), deletedSessionIds)
            assertNull(container.buttonNamed("First session"))
            assertNotNull(container.buttonNamed("Second session"))
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `play transcript does not render persisted runtime fragments`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Scenario", 1)
        val session = GameSession(
            "session", scenario.id, "Harbor", "local-model",
            listOf(
                de.aidadventure.web.model.GameMessage("runtime-fragment", "secret fragment", 1),
                de.aidadventure.web.model.GameMessage("player", "look", 2),
            ),
            1, 2,
        )
        val sessions = object : SessionRepository {
            override suspend fun list() = listOf(session)
            override suspend fun save(session: GameSession) = Unit
            override suspend fun find(id: String) = session.takeIf { it.id == id }
            override suspend fun delete(id: String) = Unit
        }
        try {
            flushSync {
                root.render(AdventureApp.create {
                    scenarioRepository = scenarioRepository(scenario)
                    sessionRepository = sessions
                    api = unusedApi()
                    initialScenarios = listOf(scenario)
                })
            }

            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed(session.title)
            nextBrowserTick()

            assertEquals(false, container.textContent?.contains("secret fragment"))
            assertEquals(true, container.textContent?.contains("look"))
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `play view puts diagnostics behind a closable panel without inline controls`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Scenario", 1)
        val session = GameSession(
            "session", scenario.id, "Harbor", "local-model",
            listOf(
                de.aidadventure.web.model.GameMessage("runtime-fragment", "secret fragment", 1),
                de.aidadventure.web.model.GameMessage("player", "look", 2),
                de.aidadventure.web.model.GameMessage("game-master", "The lighthouse answers.", 3),
                de.aidadventure.web.model.GameMessage("game-master", "Still streaming.", 4),
            ),
            1, 4,
        )
        val sessions = object : SessionRepository {
            override suspend fun list() = listOf(session)
            override suspend fun save(session: GameSession) = Unit
            override suspend fun find(id: String) = session.takeIf { it.id == id }
            override suspend fun delete(id: String) = Unit
        }
        try {
            flushSync {
                root.render(AdventureApp.create {
                    scenarioRepository = scenarioRepository(scenario)
                    sessionRepository = sessions
                    api = unusedApi()
                    initialScenarios = listOf(scenario)
                })
            }

            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed(session.title)
            nextBrowserTick()

            assertNull(container.querySelector("button[aria-label='Fragment information']"))
            assertNull(container.querySelector("button[aria-label='World state information']"))
            assertNull(container.querySelector(".metadata"))
            assertEquals(false, container.textContent?.contains("secret fragment"))
            val opener = assertNotNull(container.querySelector("button[aria-label='Open debug panel']"))
            container.click("button[aria-label='Open debug panel']")
            nextBrowserTick()
            assertNotNull(container.querySelector("[role='dialog'][aria-label='Debug panel']"))
            assertEquals(container.querySelector("button[aria-label='Close debug panel']"), document.activeElement)
            container.click("button[aria-label='Close debug panel']")
            nextBrowserTick()
            assertNull(container.querySelector("[role='dialog'][aria-label='Debug panel']"))
            assertEquals(opener, document.activeElement)

            container.click("button[aria-label='Open debug panel']")
            nextBrowserTick()
            assertNotNull(container.querySelector("[role='dialog'][aria-label='Debug panel']"))
            document.asDynamic().dispatchEvent(js("new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })"))
            nextBrowserTick()
            assertNull(container.querySelector("[role='dialog'][aria-label='Debug panel']"))
            assertEquals(opener, document.activeElement)
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `debug panel renders global information and labeled turn diagnostics`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "---\nflags:\n  doorOpen: false\nsignals:\n  unlock:\n    triggers:\n      - set-flag:\n          doorOpen: true\n---\n# Lighthouse\nPrompt body", 1)
        val session = GameSession("session", scenario.id, "Harbor", "local-model", emptyList(), 1, 1, flags = mapOf("doorOpen" to true))
        val events = recordingDebugEvents(
            DebugEvent("earlier", "session", "turn", 1, 1, "background_rerank", "prior_completed", fields = mapOf("source" to "prior_turn", "result" to "accepted", "fragment" to "Hidden fragment", "matchText" to "lighthouse", "playerAction" to "player secret action", "gameMasterOutput" to "game master secret output"), turnOrder = 1),
            DebugEvent("later", "session", "turn", 2, 2, "llama_completion", "completed", metrics = DiagnosticMetrics(promptTokens = 21, contextTokens = 4096, predictedTokens = 12, cacheTokens = 15, evaluatedTokens = 6, promptDurationMs = 100.0, predictedDurationMs = 200.0, promptTokensPerSecond = 210.0, predictedTokensPerSecond = 60.0, totalDurationMs = 300), turnOrder = 1),
            DebugEvent("compression", "session", "turn", 3, 3, "context_compression", "completed", fields = mapOf("epoch" to "2", "activeCoversThroughTurn" to "4"), turnOrder = 1),
            DebugEvent("signal", "session", "turn", 4, 4, "signal", "emitted", fields = mapOf("emittedSignals" to "unlock", "flag:doorOpen" to "true"), turnOrder = 1),
            DebugEvent("applied", "session", "turn", 5, 5, "flags", "applied", fields = mapOf("emittedSignals" to "unlock", "appliedAssignments" to "doorOpen=true"), turnOrder = 1),
        )
        try {
            flushSync { root.render(AdventureApp.create {
                scenarioRepository = scenarioRepository(scenario)
                sessionRepository = fixedSessions(session)
                debugEventRepository = events
                api = unusedApi()
                initialScenarios = listOf(scenario)
            }) }
            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed(session.title)
            nextBrowserTick()
            container.click("button[aria-label='Open debug panel']")
            nextBrowserTick()
            val panel = assertNotNull(container.querySelector("[role='dialog'][aria-label='Debug panel']"))
            val text = panel.textContent.orEmpty()
            assertEquals(false, text.contains("Current flags"), text)
            assertEquals(false, text.contains("Frontmatter"), text)
            assertEquals(false, text.contains("System prompt"), text)
            assertEquals(false, panel.querySelector(".debug-event details[open]")?.textContent?.contains("Prompt tokens") == true)
            panel.unsafeCast<HTMLElement>().click("button[title='Current flags']")
            nextBrowserTick()
            assertEquals(true, panel.textContent?.contains("doorOpen") == true)
            assertEquals(true, panel.textContent?.contains("true") == true)
            panel.unsafeCast<HTMLElement>().click("button[title='Frontmatter']")
            nextBrowserTick()
            assertEquals(false, panel.textContent?.contains("unlock") == true)
            assertEquals(false, panel.textContent?.contains("set-flag") == true)
            assertEquals(true, panel.querySelector("#debug-header-popover-frontmatter")?.textContent?.contains("doorOpen") == true)
            panel.unsafeCast<HTMLElement>().click("button[title='System prompt']")
            nextBrowserTick()
            assertEquals(true, panel.textContent?.contains("Prompt body") == true)
            assertEquals(true, panel.querySelector("#debug-event-signal .debug-flag-delta")?.textContent?.contains("doorOpen: true"))
            assertNull(panel.querySelector("#debug-event-applied"))
            assertEquals(true, text.contains("Background rerank"), text)
            assertEquals(true, text.contains("Prior turn"), text)
            assertEquals(true, text.contains("Context compression"), text)
            panel.querySelectorAll(".debug-event summary[title='Event details']").let { details ->
                repeat(details.length) { index -> details.item(index)?.asDynamic()?.click() }
            }
            val withDetails = panel.textContent.orEmpty()
            assertEquals(false, withDetails.contains("Applied assignments"), withDetails)
            assertEquals(true, withDetails.contains("Prompt tokens"), withDetails)
            assertEquals(true, withDetails.contains("Context tokens"), withDetails)
            assertEquals(true, withDetails.contains("Predicted tokens per second"), withDetails)
            assertEquals(true, withDetails.contains("300 ms"), withDetails)
            assertEquals(false, text.contains("player secret action"))
            assertEquals(false, text.contains("game master secret output"))
            val cards = panel.querySelectorAll(".debug-event")
            assertEquals(4, cards.length)
            assertEquals(true, cards.item(0)?.textContent?.contains("Background rerank"))
        } finally { flushSync { root.unmount() }; container.remove() }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `clearing debug log removes only active session events`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val session = GameSession("session", scenario.id, "Harbor", "local-model", emptyList(), 1, 1)
        val events = recordingDebugEvents(
            DebugEvent("active", "session", "turn", 0, 1, "turn_status", "completed", fields = mapOf("result" to "accepted")),
            DebugEvent("other", "other", "turn", 0, 1, "turn_status", "completed"),
        )
        try {
            flushSync { root.render(AdventureApp.create {
                scenarioRepository = scenarioRepository(scenario)
                sessionRepository = fixedSessions(session)
                debugEventRepository = events
                api = unusedApi()
                initialScenarios = listOf(scenario)
            }) }
            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed(session.title)
            nextBrowserTick()
            container.click("button[aria-label='Open debug panel']")
            nextBrowserTick()
            assertEquals(1, container.querySelectorAll(".debug-event").length)
            container.clickButtonNamed("Clear debug log")
            nextBrowserTick()
            assertEquals(0, container.querySelectorAll(".debug-event").length)
            assertEquals(listOf("other"), events.saved.map { it.id })
        } finally { flushSync { root.unmount() }; container.remove() }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `open debug panel refreshes diagnostics added during a turn`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val session = GameSession("session", scenario.id, "Harbor", "local-model", emptyList(), 1, 1)
        val events = recordingDebugEvents()
        try {
            flushSync { root.render(AdventureApp.create {
                scenarioRepository = scenarioRepository(scenario)
                sessionRepository = fixedSessions(session)
                debugEventRepository = events
                api = unusedApi()
                initialScenarios = listOf(scenario)
            }) }
            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed(session.title)
            nextBrowserTick()
            container.click("button[aria-label='Open debug panel']")
            nextBrowserTick()
            assertEquals(0, container.querySelectorAll(".debug-event").length)
            events.append(DebugEvent("new", "session", "turn", 0, 1, "turn_status", "completed", fields = mapOf("result" to "accepted")))
            Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 650) }.await()
            assertEquals(1, container.querySelectorAll(".debug-event").length)
        } finally { flushSync { root.unmount() }; container.remove() }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `introduction streams with input locked and can be retried after failure`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val failFirst = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finishRetry = kotlinx.coroutines.CompletableDeferred<Unit>()
        var stored: GameSession? = null
        val requests = mutableListOf<TurnRequest>()
        val warmups = mutableListOf<WarmupRequest>()
        try {
            flushSync {
                root.render(AdventureApp.create {
                    scenarioRepository = scenarioRepository(scenario)
                    sessionRepository = object : SessionRepository {
                        override suspend fun list() = listOfNotNull(stored)
                        override suspend fun save(session: GameSession) { stored = session }
                        override suspend fun find(id: String) = stored?.takeIf { it.id == id }
                        override suspend fun delete(id: String) = Unit
                    }
                    api = object : AdventureApi {
                        override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unexpected prefill")
                        override suspend fun warmup(request: WarmupRequest): PrefillAccepted {
                            warmups += request
                            return PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
                        }
                        override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
                            assertEquals(request.sessionId, warmups.last().sessionId)
                            requests += request
                            onToken(if (requests.size == 1) "Incomplete opening" else "You arrive at the harbor.")
                            onInputReady()
                            if (requests.size == 1) {
                                failFirst.await()
                                error("model unavailable")
                            }
                            finishRetry.await()
                            return TurnCompletion(TurnMetrics(null, null, null), WorldState("Harbor"))
                        }
                    }
                    initialScenarios = listOf(scenario)
                })
            }
            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed("New session")
            nextBrowserTick()
            nextBrowserTick()
            assertEquals(1, requests.size)
            assertEquals(true, container.textContent?.contains("Incomplete opening"))
            assertEquals(true, container.querySelector("#player-action")?.asDynamic()?.disabled)
            assertEquals(true, stored?.introductionPending)
            assertEquals(emptyList(), stored?.messages)

            failFirst.complete(Unit)
            nextBrowserTick()
            nextBrowserTick()
            assertEquals(false, container.textContent?.contains("Incomplete opening"))
            assertNotNull(container.buttonNamed("Retry introduction"))
            assertEquals(true, container.querySelector("#player-action")?.asDynamic()?.disabled)
            container.clickButtonNamed("Retry introduction")
            nextBrowserTick()
            assertEquals(2, requests.size)
            assertEquals(requests[0], requests[1])
            assertEquals(true, container.textContent?.contains("You arrive at the harbor."))
            assertEquals(true, container.querySelector("#player-action")?.asDynamic()?.disabled)
            finishRetry.complete(Unit)
            nextBrowserTick()
            nextBrowserTick()
            assertEquals(false, stored?.introductionPending)
            assertEquals(false, container.querySelector("#player-action")?.asDynamic()?.disabled)
            assertNull(container.buttonNamed("Retry introduction"))
            assertEquals(1, stored?.messages?.count { it.role == "game-master" })
        } finally {
            failFirst.complete(Unit)
            finishRetry.complete(Unit)
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `resuming an unfinished new session automatically generates its introduction`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        var stored = newGameSession(scenario, "pending-session", 1)
        val requests = mutableListOf<TurnRequest>()
        try {
            flushSync {
                root.render(AdventureApp.create {
                    scenarioRepository = scenarioRepository(scenario)
                    sessionRepository = object : SessionRepository {
                        override suspend fun list() = listOf(stored)
                        override suspend fun save(session: GameSession) { stored = session }
                        override suspend fun find(id: String) = stored.takeIf { it.id == id }
                        override suspend fun delete(id: String) = Unit
                    }
                    api = object : AdventureApi {
                        override suspend fun prefill(request: PrefillRequest): PrefillAccepted = error("unexpected prefill")
                        override suspend fun warmup(request: WarmupRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
                        override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
                            requests += request
                            onToken("Welcome to the harbor.")
                            return TurnCompletion(TurnMetrics(null, null, null), WorldState("Harbor"))
                        }
                    }
                    initialScenarios = listOf(scenario)
                })
            }
            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            nextBrowserTick()
            container.clickButtonNamed(stored.title)
            nextBrowserTick()
            nextBrowserTick()
            assertEquals("pending-session", requests.single().sessionId)
            assertEquals(false, stored.introductionPending)
            assertEquals(true, container.textContent?.contains("Welcome to the harbor."))
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    // Missing follow updates, ignoring user scrolls, or losing follow state breaks these transitions.
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `streaming follows bottom pauses above it and resumes with bottom or End`() = GlobalScope.promise<Unit> {
        val stylesheet = document.createElement("link")
        stylesheet.asDynamic().rel = "stylesheet"
        stylesheet.asDynamic().href = "/app.css"
        val loaded = Promise<Unit> { resolve, reject ->
            stylesheet.asDynamic().onload = { resolve(Unit) }
            stylesheet.asDynamic().onerror = { reject(IllegalStateException("Cannot load app.css")) }
        }
        document.head.appendChild(stylesheet)
        loaded.await()
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("follow", "follow.md", "# Harbor", 1)
        val session = GameSession("follow-session", scenario.id, "Harbor", messages = List(20) {
            GameMessage("game-master", "Old narrative paragraph. ".repeat(30), it.toLong(), messageId = "old-$it")
        }, createdAt = 1, updatedAt = 20)
        val events = recordingDebugEvents(DebugEvent("follow-old", session.id, "old-turn", 0, 1,
            "turn_status", "completed", fields = mapOf("result" to "accepted"),
            transcriptRole = "game-master", transcriptMessageId = "old-0"))
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var token: (String) -> Unit = {}
        val api = object : AdventureApi {
            override suspend fun prefill(request: PrefillRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
            override suspend fun warmup(request: WarmupRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
            override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion {
                token = onToken
                started.complete(Unit)
                finished.await()
                return TurnCompletion(TurnMetrics(null, null, null), WorldState())
            }
        }
        try {
            flushSync { root.render(AdventureApp.create {
                scenarioRepository = scenarioRepository(scenario)
                sessionRepository = fixedSessions(session)
                debugEventRepository = events
                this.api = api
                initialScenarios = listOf(scenario)
            }) }
            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed(session.title)
            nextBrowserTick()
            val transcript = assertNotNull(container.querySelector(".transcript")).asDynamic()
            val input = container.querySelector("#player-action").asDynamic()
            fun scrollTo(offset: dynamic) {
                transcript.scrollTop = offset
                transcript.dispatchEvent(js("new Event('scroll', { bubbles: false })"))
            }
            fun assertBottom() {
                assertEquals(true, transcript.scrollHeight - transcript.clientHeight - transcript.scrollTop <= 1,
                    "Latest streamed text must stay visible")
            }
            scrollTo(transcript.scrollHeight)
            js("Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(input, 'Look around')")
            input.dispatchEvent(js("new Event('input', { bubbles: true })"))
            nextBrowserTick()
            container.click(".action-form button.primary-button")
            withTimeout(5_000) { started.await() }
            suspend fun appendText() {
                token("\n\nNew streamed narrative. ".repeat(40))
                nextBrowserTick()
            }
            appendText()
            assertBottom()
            appendText()
            assertBottom()

            scrollTo(200)
            val pausedOffset = transcript.scrollTop
            appendText()
            assertEquals(pausedOffset, transcript.scrollTop, "Reading earlier text must pause following")
            scrollTo(transcript.scrollHeight)
            appendText()
            assertBottom()

            scrollTo(200)
            input.focus()
            val end = js("new KeyboardEvent('keydown', { key: 'End', bubbles: true, cancelable: true })")
            input.dispatchEvent(end)
            assertEquals(false, end.defaultPrevented, "Plain End in composer retains native editing")
            assertEquals(200, transcript.scrollTop)
            val ctrlEnd = js("new KeyboardEvent('keydown', { key: 'End', ctrlKey: true, bubbles: true, cancelable: true })")
            input.dispatchEvent(ctrlEnd)
            assertEquals(true, ctrlEnd.defaultPrevented, "Ctrl+End in composer must jump the dialog")
            assertBottom()
            appendText()
            assertBottom()

            scrollTo(200)
            for (modifier in listOf("shiftKey", "altKey", "metaKey")) {
                val options = js("({ key: 'End', bubbles: true, cancelable: true })")
                options[modifier] = true
                val modifiedEnd = js("new KeyboardEvent('keydown', options)")
                input.dispatchEvent(modifiedEnd)
                assertEquals(false, modifiedEnd.defaultPrevented, "Modified End retains native editing")
                assertEquals(200, transcript.scrollTop)
            }

            val composingEnd = js("new KeyboardEvent('keydown', { key: 'End', isComposing: true, bubbles: true, cancelable: true })")
            input.dispatchEvent(composingEnd)
            assertEquals(false, composingEnd.defaultPrevented, "IME composition retains native keys")
            assertEquals(200, transcript.scrollTop)

            input.blur()
            val bodyEnd = js("new KeyboardEvent('keydown', { key: 'End', bubbles: true, cancelable: true })")
            document.body.dispatchEvent(bodyEnd)
            assertEquals(true, bodyEnd.defaultPrevented, "End with body focus must jump the dialog")
            appendText()
            assertBottom()

            // Diagnostics may change layout and navigate back while generation continues.
            scrollTo(transcript.scrollHeight)
            container.click("button[aria-label='Open debug panel']")
            nextBrowserTick()
            nextBrowserTick()
            appendText()
            assertBottom()
            assertNotNull(container.querySelector("#debug-event-follow-old")).asDynamic().dispatchEvent(
                js("new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })"))
            nextBrowserTick()
            val linkedOffset = transcript.scrollTop
            assertEquals(true, linkedOffset < transcript.scrollHeight - transcript.clientHeight, "Diagnostic link must reveal old narrative")
            appendText()
            assertEquals(linkedOffset, transcript.scrollTop, "A diagnostic back-link must pause following")
            val debugEvent = assertNotNull(container.querySelector("#debug-event-follow-old")).asDynamic()
            debugEvent.focus()
            val debugEnd = js("new KeyboardEvent('keydown', { key: 'End', bubbles: true, cancelable: true })")
            debugEvent.dispatchEvent(debugEnd)
            assertEquals(true, debugEnd.defaultPrevented, "End from diagnostics must jump the dialog")
            appendText()
            assertBottom()
        } finally {
            finished.complete(Unit)
            nextBrowserTick()
            flushSync { root.unmount() }
            container.remove()
            stylesheet.remove()
            val endAfterUnmount = js("new KeyboardEvent('keydown', { key: 'End', bubbles: true, cancelable: true })")
            document.body.dispatchEvent(endAfterUnmount)
            assertEquals(false, endAfterUnmount.defaultPrevented, "Leaving play removes the End listener")
        }
    }

    // Removing pane height constraints or scrolling the page during cross-links breaks this contract.
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `long game and diagnostics scroll independently with controls visible`() = GlobalScope.promise<Unit> {
        val stylesheet = document.createElement("link")
        stylesheet.asDynamic().rel = "stylesheet"
        stylesheet.asDynamic().href = "/app.css"
        val loaded = Promise<Unit> { resolve, reject ->
            stylesheet.asDynamic().onload = { resolve(Unit) }
            stylesheet.asDynamic().onerror = { reject(IllegalStateException("Cannot load app.css")) }
        }
        document.head.appendChild(stylesheet)
        loaded.await()
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("layout", "layout.md", "# Harbor", 1)
        val session = GameSession("layout-session", scenario.id, "Harbor", messages = List(60) {
            GameMessage("game-master", "Long narrative paragraph. ".repeat(30), it.toLong(), messageId = "message-$it")
        }, createdAt = 1, updatedAt = 60)
        val events = recordingDebugEvents(*Array(60) {
            DebugEvent("layout-$it", session.id, "turn-$it", it, it.toLong(), "turn_status", "completed", fields = mapOf("result" to "accepted"),
                transcriptRole = "game-master", transcriptMessageId = "message-$it")
        })
        try {
            flushSync { root.render(AdventureApp.create {
                scenarioRepository = scenarioRepository(scenario)
                sessionRepository = fixedSessions(session)
                debugEventRepository = events
                api = unusedApi()
                initialScenarios = listOf(scenario)
            }) }
            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            container.clickButtonNamed(session.title)
            nextBrowserTick()
            assertEquals(false, container.textContent?.contains("AI Adventure"), "Global heading must not consume window space")
            val transcript = assertNotNull(container.querySelector(".transcript")).asDynamic()
            fun visible(element: dynamic) {
                val bounds = element.getBoundingClientRect()
                assertEquals(true, bounds.top >= 0 && bounds.bottom <= window.innerHeight + 1,
                    "Controls must fit viewport: " + bounds.top + ".." + bounds.bottom)
            }
            assertEquals(true, transcript.scrollHeight > transcript.clientHeight, "Transcript must have its own overflow")
            visible(container.querySelector("#player-action"))
            visible(container.querySelector(".action-form > button"))
            container.click("button[aria-label='Open debug panel']")
            nextBrowserTick()
            nextBrowserTick()
            val panelBounds = assertNotNull(container.querySelector(".debug-panel")).asDynamic().getBoundingClientRect()
            assertEquals(0.0, panelBounds.top as Double, "Diagnostics use the full window height")
            assertEquals(window.innerHeight.toDouble(), panelBounds.bottom as Double)
            val debug = assertNotNull(container.querySelector("#debug-event-list")).asDynamic()
            assertEquals(true, debug.scrollHeight > debug.clientHeight)
            visible(container.querySelector(".debug-panel-header"))
            visible(container.querySelector("#player-action"))
            transcript.scrollTop = 300
            debug.scrollTop = 0
            val gameOffset = transcript.scrollTop
            val pageOffset = window.scrollY
            container.doubleClick("#transcript-message-55")
            nextBrowserTick()
            nextBrowserTick()
            assertEquals(true, debug.scrollTop > 0, "Transcript cross-link must reveal a distant diagnostic")
            assertEquals(gameOffset, transcript.scrollTop, "Source pane must stay put")
            assertEquals(pageOffset, window.scrollY, "Cross-link must not scroll page")
            transcript.scrollTop = 0
            val debugOffset = debug.scrollTop
            container.querySelector("#debug-event-layout-55")?.asDynamic()?.dispatchEvent(
                js("new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })"))
            nextBrowserTick()
            assertEquals(true, transcript.scrollTop > 0, "Keyboard diagnostic cross-link must reveal transcript")
            assertEquals(debugOffset, debug.scrollTop)
            assertEquals(pageOffset, window.scrollY)
            visible(container.querySelector("#player-action"))
            // A real narrow browsing context evaluates the responsive media query.
            val frame = document.createElement("iframe")
            frame.asDynamic().style = "width:390px;height:844px;border:0;position:fixed;top:0;left:0"
            document.body.appendChild(frame)
            try {
                val narrow = frame.asDynamic().contentDocument
                narrow.open()
                narrow.write("<link rel='stylesheet' href='/app.css'>" + container.innerHTML)
                narrow.close()
                Promise<Unit> { resolve, reject ->
                    val link = narrow.querySelector("link")
                    if (link.sheet != null) resolve(Unit) else {
                        link.onload = { resolve(Unit) }
                        link.onerror = { reject(IllegalStateException("Cannot load narrow app.css")) }
                    }
                }.await()
                val game = narrow.querySelector(".play-layout > .view-card")
                val panel = narrow.querySelector(".debug-panel")
                assertEquals(844.0, panel.getBoundingClientRect().height as Double, "Narrow diagnostics use the full window height")
                assertEquals(true, panel.getBoundingClientRect().top >= game.getBoundingClientRect().bottom,
                    "Narrow panes should stack")
                for (selector in listOf(".transcript", "#debug-event-list")) {
                    val scroller = narrow.querySelector(selector)
                    assertEquals(true, scroller.clientHeight > 0 && scroller.scrollHeight > scroller.clientHeight,
                        "$selector must remain reachable and independently scrollable on narrow screens")
                }
                val composer = narrow.querySelector(".action-form").getBoundingClientRect()
                val gameBounds = game.getBoundingClientRect()
                assertEquals(true, composer.top >= gameBounds.top && composer.bottom <= gameBounds.bottom,
                    "Narrow game keeps composer inside its pane")
                assertEquals(true, narrow.documentElement.scrollWidth <= 390, "No horizontal page overflow")
                // Just above the stacking breakpoint, both columns must still fit.
                frame.asDynamic().style.width = "720px"
                val intermediateGame = game.getBoundingClientRect()
                val intermediateDebug = panel.getBoundingClientRect()
                assertEquals(true, intermediateDebug.left >= intermediateGame.right,
                    "Wider panes should be side by side")
                assertEquals(true, narrow.documentElement.scrollWidth <= 720, "Intermediate width must not overflow")
                frame.asDynamic().style.width = "1000px"
                frame.asDynamic().style.height = "307px"
                val shortTranscript = narrow.querySelector(".transcript")
                assertEquals(true, shortTranscript.clientHeight >= 96,
                    "Short wide viewport must retain readable transcript space")
                val shortComposer = narrow.querySelector(".action-form").getBoundingClientRect()
                val shortGame = game.getBoundingClientRect()
                assertEquals(true, shortComposer.top >= shortGame.top && shortComposer.bottom <= shortGame.bottom,
                    "Short viewport composer must remain inside a reachable pane")
                assertEquals(true, narrow.querySelector(".action-form > button").clientHeight >= 40,
                    "Send control must remain reachable")
                assertEquals(307.0, panel.getBoundingClientRect().height as Double, "Short diagnostics use the full window height")
                val shortDebug = narrow.querySelector("#debug-event-list")
                assertEquals(true, shortDebug.clientHeight >= 96,
                    "Short viewport diagnostics must remain readable")
                // Closing diagnostics must preserve the same readable game pane.
                narrow.querySelector(".play-layout").classList.remove("play-layout--debug-open")
                panel.remove()
                assertEquals(true, shortTranscript.clientHeight >= 96,
                    "Short viewport game remains readable with diagnostics closed")
                frame.asDynamic().style.height = "407px"
                assertEquals(true, narrow.querySelector(".app-shell").getBoundingClientRect().bottom <= 407,
                    "Compact chrome should fit a 407px viewport without page growth")
                narrow.querySelector(".view-title").textContent = "Long session title ".repeat(20)
                assertEquals(true, shortTranscript.clientHeight >= 96,
                    "A long session title must not displace the short-window transcript")
                val title = narrow.querySelector(".view-title")
                assertEquals(true, title.scrollHeight > title.clientHeight,
                    "Wrapped title remains reachable through its own overflow")


            } finally { frame.remove() }
        } finally {
            flushSync { root.unmount() }
            container.remove()
            stylesheet.remove()
            window.scrollTo(0.0, 0.0)
        }
    }

    private fun scenarioRepository(scenario: Scenario) = object : ScenarioRepository {
        override suspend fun list() = listOf(scenario)
        override suspend fun save(scenario: Scenario) = Unit
        override suspend fun find(id: String) = null
        override suspend fun delete(id: String) = Unit
    }

    private fun sessionRepository() = object : SessionRepository {
        override suspend fun list() = emptyList<GameSession>()
        override suspend fun save(session: GameSession) = Unit
        override suspend fun find(id: String) = null
        override suspend fun delete(id: String) = Unit
    }

    private fun fixedSessions(session: GameSession) = object : SessionRepository {
        override suspend fun list() = listOf(session)
        override suspend fun save(session: GameSession) = Unit
        override suspend fun find(id: String) = session.takeIf { it.id == id }
        override suspend fun delete(id: String) = Unit
    }

    private fun recordingDebugEvents(vararg events: DebugEvent) = object : DebugEventRepository {
        val saved = events.toMutableList()
        override suspend fun listForSession(sessionId: String) = saved.filter { it.sessionId == sessionId }
        override suspend fun append(event: DebugEvent) { saved += event }
        override suspend fun appendBackgroundOutcome(event: DebugEvent, operationId: String, effects: List<DebugEvent>): Boolean {
            if (saved.none { it.sessionId == event.sessionId && it.turnId == event.turnId &&
                    it.stage == "scheduled" && it.fields["operationId"] == operationId }) return false
            if (saved.any { it.sessionId == event.sessionId && it.stage.startsWith("prior_") &&
                    it.fields["operationId"] == operationId }) return false
            append(event)
            effects.forEach { effect -> if (saved.none { it.id == effect.id }) append(effect) }
            return true
        }
        override suspend fun clearSession(sessionId: String) { saved.removeAll { it.sessionId == sessionId } }
        override suspend fun deleteForSessions(sessionIds: Set<String>) { saved.removeAll { it.sessionId in sessionIds } }
    }

    private fun unusedApi() = object : AdventureApi {
        override suspend fun prefill(request: PrefillRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
        override suspend fun warmup(request: WarmupRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
        override suspend fun play(request: TurnRequest, onInputReady: () -> Unit, onFragment: (String) -> Unit, onToken: (String) -> Unit): TurnCompletion = error("unused")
    }

    private fun HTMLElement.click(selector: String) {
        querySelector(selector)
            ?.asDynamic()
            ?.dispatchEvent(js("new MouseEvent('click', { bubbles: true })"))
    }

    private fun HTMLElement.doubleClick(selector: String) {
        querySelector(selector)
            ?.asDynamic()
            ?.dispatchEvent(js("new MouseEvent('dblclick', { bubbles: true })"))
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
        Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 0) }.await()
    }
}
