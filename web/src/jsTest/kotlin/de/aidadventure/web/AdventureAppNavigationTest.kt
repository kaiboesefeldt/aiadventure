package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.PrefillAccepted
import de.aidadventure.web.api.PrefillRequest
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnRequest
import de.aidadventure.web.api.WarmupRequest
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
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
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `starting a new session renders the play view`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val scenario = Scenario("scenario", "scenario.md", "# Scenario", 1)
        try {
            flushSync {
                createRoot(container).render(AdventureApp.create {
                    scenarioRepository = scenarioRepository(scenario)
                    sessionRepository = sessionRepository()
                    api = object : AdventureApi {
                        override suspend fun models(port: Int) = listOf("local-model")
                        override suspend fun prefill(request: PrefillRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
                        override suspend fun warmup(request: WarmupRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
                        override suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics = error("unused")
                    }
                    initialScenarios = listOf(scenario)
                })
            }

            container.clickButtonNamed(scenario.name)
            nextBrowserTick()
            assertNotNull(container.buttonNamed("Find models"))
            container.clickButtonNamed("Find models")
            nextBrowserTick()
            assertNotNull(container.buttonNamed("Start with local-model"))
            container.clickButtonNamed("Start with local-model")
            nextBrowserTick()
            nextBrowserTick()

            assertNotNull(container.querySelector("#player-action"))
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
        override suspend fun models(port: Int) = listOf("local-model")
        override suspend fun prefill(request: PrefillRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
        override suspend fun warmup(request: WarmupRequest) = PrefillAccepted(de.aidadventure.web.api.PrefillDisposition.STARTED)
        override suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics = error("unused")
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
