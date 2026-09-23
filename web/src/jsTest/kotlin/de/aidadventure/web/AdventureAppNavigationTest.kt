package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.PrefillAccepted
import de.aidadventure.web.api.PrefillRequest
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnCompletion
import de.aidadventure.web.api.TurnRequest
import de.aidadventure.web.api.WarmupRequest
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.model.WorldState
import de.aidadventure.web.persistence.ScenarioRepository
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
    fun `completed game master response reveals runtime fragment only from its info control`() = GlobalScope.promise<Unit> {
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

            val fragmentInformationButton = assertNotNull(container.querySelector("button[aria-label='Fragment information']"))
            assertEquals(1, container.querySelectorAll("button[aria-label='Fragment information']").length)
            assertEquals("false", fragmentInformationButton.getAttribute("aria-expanded"))
            assertNull(fragmentInformationButton.getAttribute("aria-describedby"))
            assertNull(container.querySelector("[role='tooltip']"))
            assertEquals(false, container.textContent?.contains("secret fragment"))

            container.click("button[aria-label='Fragment information']")
            nextBrowserTick()

            assertEquals(true, container.textContent?.contains("secret fragment"))
            val tooltip = assertNotNull(container.querySelector("[role='tooltip']"))
            assertEquals("true", fragmentInformationButton.getAttribute("aria-expanded"))
            val tooltipId = assertNotNull(tooltip.getAttribute("id"))
            assertEquals(tooltipId, fragmentInformationButton.getAttribute("aria-describedby"))

            document.body.asDynamic().dispatchEvent(js("new MouseEvent('click', { bubbles: true })"))
            nextBrowserTick()

            assertNull(container.querySelector("[role='tooltip']"))
            assertEquals("false", fragmentInformationButton.getAttribute("aria-expanded"))
            assertNull(fragmentInformationButton.getAttribute("aria-describedby"))
        } finally {
            container.remove()
        }
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
