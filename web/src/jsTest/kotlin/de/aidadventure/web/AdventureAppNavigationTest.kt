package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnRequest
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.persistence.ScenarioRepository
import de.aidadventure.web.persistence.SessionRepository
import kotlin.test.Test
import kotlin.js.Promise
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import react.create
import react.dom.client.createRoot
import react.dom.flushSync
import web.dom.document

class AdventureAppNavigationTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `opening a scenario does not update parent state while rendering detail props`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val scenario = Scenario("scenario", "scenario.md", "# Scenario", 1)
        try {
            flushSync {
                createRoot(container).render(
                    AdventureApp.create {
                        scenarioRepository = object : ScenarioRepository {
                            override suspend fun list() = listOf(scenario)
                            override suspend fun save(scenario: Scenario) = Unit
                            override suspend fun find(id: String) = null
                        }
                        sessionRepository = object : SessionRepository {
                            override suspend fun list() = emptyList<de.aidadventure.web.model.GameSession>()
                            override suspend fun save(session: de.aidadventure.web.model.GameSession) = Unit
                            override suspend fun find(id: String) = null
                        }
                        this.api = object : AdventureApi {
                            override suspend fun models(): List<String> = emptyList()
                            override suspend fun llamaCppModels(port: Int): List<String> = emptyList()
                            override suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics = error("unused")
                        }
                        initialScenarios = listOf(scenario)
                    },
                )
            }
            container.querySelector("button.action-row")
                ?.asDynamic()
                ?.dispatchEvent(js("new MouseEvent('click', { bubbles: true })"))
            Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 0) }.await()

            check(container.querySelector(".section-title") != null)
        } finally {
            container.remove()
        }
    }
}
