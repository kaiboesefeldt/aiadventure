package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnRequest
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.persistence.SessionRepository
import kotlin.test.Test
import react.create
import react.dom.client.createRoot
import react.dom.flushSync
import web.dom.document

class DetailViewTest {
    @Test
    fun `opening detail view does not update state during render`() {
        val container = document.createElement("div")
        val api = object : AdventureApi {
            override suspend fun models(): List<String> = emptyList()
            override suspend fun llamaCppModels(port: Int): List<String> = emptyList()
            override suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics = error("unused")
        }
        val sessions = object : SessionRepository {
            override suspend fun list() = emptyList<de.aidadventure.web.model.GameSession>()
            override suspend fun save(session: de.aidadventure.web.model.GameSession) = Unit
            override suspend fun find(id: String) = null
        }

        flushSync {
            createRoot(container).render(
                DetailView.create {
                    scenario = Scenario("scenario", "scenario.md", "# Scenario", 1)
                    models = emptyList()
                    modelError = null
                    this.api = api
                    llamaCppConnection = LlamaCppConnectionState.initial()
                    llamaCppSession = null
                    llamaCppPending = false
                    this.sessions = { sessions.list() }
                    onBack = {}
                    onCreate = {}
                    onLlamaCppConnectionChanged = {}
                    onLlamaCppStart = { _, _, _ -> }
                    onResume = {}
                    onLlamaCppPendingChanged = {}
                    onReloadModels = {}
                },
            )
        }

        check(container.querySelector("h2") != null)
    }
}
