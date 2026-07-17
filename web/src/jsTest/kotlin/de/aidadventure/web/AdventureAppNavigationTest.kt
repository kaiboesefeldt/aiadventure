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

            container.click("button.action-row")
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
