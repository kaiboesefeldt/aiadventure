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
import de.aidadventure.web.model.WorldState
import de.aidadventure.web.persistence.ScenarioRepository
import de.aidadventure.web.persistence.DebugEventRepository
import de.aidadventure.web.persistence.SessionRepository
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import react.create
import react.dom.client.createRoot
import react.dom.flushSync
import web.dom.document
import web.html.HTMLElement

class AdventureAppNavigationTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `resumed introduction navigates to its prior turn diagnostic after a failed retry`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val session = GameSession("session", scenario.id, "Harbor", messages = listOf(
            GameMessage("player", "hidden introduction", 1, hidden = true, messageId = "hidden-intro"),
            GameMessage("game-master", "Saved welcome", 2, messageId = "saved-intro-output"),
        ), createdAt = 1, updatedAt = 2)
        val events = recordingDebugEvents(
            DebugEvent("stale-intro", "session", "failed-intro", 0, 1, "turn_status", "started", transcriptRole = "game-master", transcriptIndex = 0, transcriptMessageId = "discarded-intro-output"),
            DebugEvent("prior-intro", "session", "next-turn", 0, 3, "background_rerank", "prior_completed", fields = mapOf("source" to "prior_turn"), transcriptRole = "game-master", transcriptIndex = 0, transcriptMessageId = "saved-intro-output"),
        )
        val elementPrototype = js("Element.prototype")
        val oldScrollIntoView = elementPrototype.scrollIntoView
        elementPrototype.scrollIntoView = js("function () { window.__lastScrolledId = this.id; }")
        try {
            flushSync { createRoot(container).render(AdventureApp.create {
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
            container.click("#transcript-message-0 button[aria-label='Show diagnostics']")
            nextBrowserTick()
            nextBrowserTick()
            assertEquals("debug-event-prior-intro", window.asDynamic().__lastScrolledId)
            assertNull(container.querySelector("#debug-event-stale-intro button[aria-label='Show transcript message']"))
            container.click("#debug-event-prior-intro button[aria-label='Show transcript message']")
            nextBrowserTick()
            assertEquals("transcript-message-0", window.asDynamic().__lastScrolledId)
        } finally {
            elementPrototype.scrollIntoView = oldScrollIntoView
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `resumed retry does not link a failed turn event to the replacement player`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val session = GameSession("session", scenario.id, "Harbor", messages = listOf(
            GameMessage("player", "retry action", 3, messageId = "retry-player"),
            GameMessage("game-master", "retry response", 4, messageId = "retry-output"),
        ), createdAt = 1, updatedAt = 4)
        val events = recordingDebugEvents(
            DebugEvent("failed", "session", "failed-turn", 0, 2, "turn_state", "initial", transcriptRole = "player", transcriptIndex = 0, transcriptMessageId = "failed-player"),
            DebugEvent("retry", "session", "retry-turn", 0, 3, "turn_state", "initial", transcriptRole = "player", transcriptIndex = 0, transcriptMessageId = "retry-player"),
        )
        val elementPrototype = js("Element.prototype")
        val oldScrollIntoView = elementPrototype.scrollIntoView
        elementPrototype.scrollIntoView = js("function () { window.__lastScrolledId = this.id; }")
        try {
            flushSync { createRoot(container).render(AdventureApp.create {
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
            container.click("#transcript-message-0 button[aria-label='Show diagnostics']")
            nextBrowserTick()
            nextBrowserTick()
            assertEquals("debug-event-retry", window.asDynamic().__lastScrolledId)
            assertNotNull(container.querySelector("#debug-event-retry"))
            assertNull(container.querySelector("#debug-event-failed button[aria-label='Show transcript message']"))
            assertNotNull(container.querySelector("#debug-event-retry button[aria-label='Show transcript message']"))
        } finally {
            elementPrototype.scrollIntoView = oldScrollIntoView
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `resumed transcript and diagnostic events navigate in both directions`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val session = GameSession("session", scenario.id, "Harbor", messages = listOf(
            GameMessage("player", "hidden introduction", 1, hidden = true, messageId = "intro-input"),
            GameMessage("game-master", "Welcome", 2, messageId = "intro-output"),
            GameMessage("player", "Visit pier", 3, messageId = "player-action"),
            GameMessage("game-master", "At the pier", 4, messageId = "player-output"),
        ), createdAt = 1, updatedAt = 4)
        val events = recordingDebugEvents(
            DebugEvent("intro-event", "session", "intro", 0, 2, "turn_status", "completed", transcriptRole = "game-master", transcriptIndex = 0, transcriptMessageId = "intro-output"),
            DebugEvent("player-event", "session", "turn", 0, 3, "turn_state", "initial", transcriptRole = "player", transcriptIndex = 1, transcriptMessageId = "player-action"),
        )
        val elementPrototype = js("Element.prototype")
        val oldScrollIntoView = elementPrototype.scrollIntoView
        elementPrototype.scrollIntoView = js("function () { window.__lastScrolledId = this.id; }")
        try {
            flushSync { createRoot(container).render(AdventureApp.create {
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
            container.click("#transcript-message-1 button[aria-label='Show diagnostics']")
            nextBrowserTick()
            assertNotNull(container.querySelector("#debug-event-player-event"))
            nextBrowserTick()
            assertEquals("debug-event-player-event", window.asDynamic().__lastScrolledId)
            assertNotNull(container.querySelector("[role='dialog'][aria-label='Debug panel']"))
            container.click("#debug-event-player-event button[aria-label='Show transcript message']")
            nextBrowserTick()
            assertEquals("transcript-message-1", window.asDynamic().__lastScrolledId)
            assertNull(container.querySelector("[role='dialog'][aria-label='Debug panel']"))
            assertNotNull(container.querySelector("#transcript-message-0 button[aria-label='Show diagnostics']"))
        } finally {
            elementPrototype.scrollIntoView = oldScrollIntoView
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
        val scenario = Scenario("scenario", "scenario.md", "# Scenario", 1)
        val saved = mutableListOf<GameSession>()
        val requests = mutableListOf<TurnRequest>()
        try {
            flushSync {
                createRoot(container).render(AdventureApp.create {
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
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `deleting a scenario removes the selected scenario from the library`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
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
                createRoot(container).render(AdventureApp.create {
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
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `deleting a session removes only the selected session from the detail page`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
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
                createRoot(container).render(AdventureApp.create {
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
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `play transcript does not render persisted runtime fragments`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
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
                createRoot(container).render(AdventureApp.create {
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
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `play view puts diagnostics behind a closable panel without inline controls`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
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
                createRoot(container).render(AdventureApp.create {
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
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `debug panel renders global information and labeled turn diagnostics`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val scenario = Scenario("scenario", "scenario.md", "---\nflags:\n  doorOpen: false\nsignals:\n  unlock:\n    triggers:\n      - set-flag:\n          doorOpen: true\n---\n# Lighthouse\nPrompt body", 1)
        val session = GameSession("session", scenario.id, "Harbor", "local-model", emptyList(), 1, 1, flags = mapOf("doorOpen" to true))
        val events = recordingDebugEvents(
            DebugEvent("earlier", "session", "turn", 1, 1, "background_rerank", "prior_completed", fields = mapOf("source" to "prior_turn", "result" to "accepted", "fragment" to "Hidden fragment", "matchText" to "lighthouse", "playerAction" to "player secret action", "gameMasterOutput" to "game master secret output"), turnOrder = 1),
            DebugEvent("later", "session", "turn", 2, 2, "turn_metrics", "completed", metrics = DiagnosticMetrics(promptTokens = 21, contextTokens = 4096, predictedTokens = 12, cacheTokens = 15, evaluatedTokens = 6, promptDurationMs = 100.0, predictedDurationMs = 200.0, promptTokensPerSecond = 210.0, predictedTokensPerSecond = 60.0, totalDurationMs = 300), turnOrder = 1),
            DebugEvent("compression", "session", "turn", 3, 3, "context_compression", "completed", fields = mapOf("epoch" to "2", "activeCoversThroughTurn" to "4"), turnOrder = 1),
            DebugEvent("signal", "session", "turn", 4, 4, "flags", "applied", fields = mapOf("emittedSignals" to "unlock", "appliedAssignments" to "doorOpen=true"), turnOrder = 1),
        )
        try {
            flushSync { createRoot(container).render(AdventureApp.create {
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
            assertEquals(true, text.contains("Current flags"), text)
            assertEquals(true, text.contains("doorOpen"), text)
            assertEquals(true, text.contains("true"), text)
            assertEquals(true, text.contains("Frontmatter"), text)
            assertEquals(true, text.contains("set-flag"), text)
            assertEquals(true, text.contains("System prompt"), text)
            assertEquals(true, text.contains("Prompt body"), text)
            assertEquals(true, text.contains("Background rerank"), text)
            assertEquals(true, text.contains("Prior turn"), text)
            assertEquals(true, text.contains("Context compression"), text)
            assertEquals(true, text.contains("Applied assignments"), text)
            assertEquals(true, text.contains("Prompt tokens"), text)
            assertEquals(true, text.contains("Context tokens"), text)
            assertEquals(true, text.contains("Predicted tokens per second"), text)
            assertEquals(true, text.contains("300 ms"), text)
            assertEquals(false, text.contains("player secret action"))
            assertEquals(false, text.contains("game master secret output"))
            val cards = panel.querySelectorAll(".debug-event")
            assertEquals(4, cards.length)
            assertEquals(true, cards.item(0)?.textContent?.contains("Background rerank"))
        } finally { container.remove() }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `clearing debug log removes only active session events`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val session = GameSession("session", scenario.id, "Harbor", "local-model", emptyList(), 1, 1)
        val events = recordingDebugEvents(
            DebugEvent("active", "session", "turn", 0, 1, "turn_status", "completed"),
            DebugEvent("other", "other", "turn", 0, 1, "turn_status", "completed"),
        )
        try {
            flushSync { createRoot(container).render(AdventureApp.create {
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
        } finally { container.remove() }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `open debug panel refreshes diagnostics added during a turn`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val session = GameSession("session", scenario.id, "Harbor", "local-model", emptyList(), 1, 1)
        val events = recordingDebugEvents()
        try {
            flushSync { createRoot(container).render(AdventureApp.create {
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
            events.append(DebugEvent("new", "session", "turn", 0, 1, "turn_status", "completed"))
            Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 650) }.await()
            assertEquals(1, container.querySelectorAll(".debug-event").length)
        } finally { container.remove() }
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
