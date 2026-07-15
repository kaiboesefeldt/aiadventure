package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.api.TurnRequest
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.ModelProvider
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.persistence.SessionRepository
import kotlin.test.Test
import react.create
import react.dom.client.createRoot
import react.dom.flushSync
import web.dom.document

class DetailViewTest {
    @Test
    fun `selected llama cpp session visibly explains how to resume`() {
        val container = document.createElement("div")
        val session = GameSession(
            id = "saved",
            scenarioId = "scenario",
            title = "Saved run",
            modelId = "local-model",
            messages = emptyList(),
            createdAt = 1,
            updatedAt = 1,
            provider = ModelProvider.LLAMA_CPP,
            llamaCppPort = 8081,
        )

        flushSync {
            createRoot(container).render(
                DetailView.create {
                    scenario = Scenario("scenario", "scenario.md", "# Scenario", 1)
                    models = emptyList()
                    modelError = null
                    api = unusedApi()
                    llamaCppConnection = LlamaCppConnectionState.initial(8081, "local-model")
                    llamaCppSession = session
                    llamaCppPending = false
                    sessions = { emptyList() }
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

        check(container.textContent?.contains("Resume saved session: Saved run") == true)
        check(container.textContent?.contains("Check llama.cpp to resume") == true)
    }

    @Test
    fun `opening detail view does not update state during render`() {
        val container = document.createElement("div")
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
                    api = unusedApi()
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

    private fun unusedApi() = object : AdventureApi {
        override suspend fun models(): List<String> = emptyList()
        override suspend fun llamaCppModels(port: Int): List<String> = emptyList()
        override suspend fun play(request: TurnRequest, onToken: (String) -> Unit): TurnMetrics = error("unused")
    }
}
