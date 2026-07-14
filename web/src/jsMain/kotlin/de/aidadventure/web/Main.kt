package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.AdventureApiClient
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.persistence.AdventureDatabase
import de.aidadventure.web.persistence.ImportResult
import de.aidadventure.web.persistence.ScenarioImport
import de.aidadventure.web.persistence.ScenarioRepository
import de.aidadventure.web.persistence.SessionRepository
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.dsl.module
import react.FC
import react.ChildrenBuilder
import react.Props
import react.create
import react.dom.client.createRoot
import react.dom.html.ReactHTML
import react.useState
import web.dom.document
import web.file.File
import web.html.HTMLInputElement
import web.html.HTMLTextAreaElement

private val scope = MainScope()

fun main() {
    scope.launch {
        val database = AdventureDatabase.open()
        val koin = startKoin {
            modules(module {
                single<ScenarioRepository> { database.scenarios }
                single<SessionRepository> { database.sessions }
                single<AdventureApi> { AdventureApiClient() }
            })
        }.koin
        val root = document.getElementById("root".unsafeCast<web.dom.ElementId>()) ?: error("Missing #root element")
        val initialScenarios = database.scenarios.list()
        createRoot(root).render(
            AdventureApp.create {
                scenarioRepository = koin.get()
                sessionRepository = koin.get()
                api = koin.get()
                this.initialScenarios = initialScenarios
            },
        )
    }
}

external interface AdventureAppProps : Props {
    var scenarioRepository: ScenarioRepository
    var sessionRepository: SessionRepository
    var api: AdventureApi
    var initialScenarios: List<Scenario>
}

private sealed interface View {
    data object Library : View
    data class Detail(val scenario: Scenario) : View
    data class Play(val scenario: Scenario, val session: GameSession) : View
}

private val AdventureApp = FC<AdventureAppProps> { props ->
    var view by useState<View>(View.Library)
    var scenarios by useState(props.initialScenarios)
    var importError by useState<String?>(null)
    var models by useState<List<String>>(emptyList())
    var modelLoadError by useState<String?>(null)

    fun refreshScenarios() {
        scope.launch { scenarios = props.scenarioRepository.list() }
    }

    fun openScenario(scenario: Scenario) {
        view = View.Detail(scenario)
        models = emptyList()
        modelLoadError = null
        scope.launch {
            runCatching { props.api.models() }
                .onSuccess { models = it }
                .onFailure { modelLoadError = clientMessage(it) }
        }
    }

    ReactHTML.main {
        ReactHTML.h1 { +"AI Adventure" }
        when (val current = view) {
            View.Library -> libraryView(
                scenarios = scenarios,
                importError = importError,
                onImport = { file ->
                    scope.launch {
                        runCatching {
                            file.asDynamic().text().unsafeCast<kotlin.js.Promise<String>>().await()
                        }.onSuccess { markdown ->
                            when (val result = ScenarioImport.fromFile(file.name, markdown, scenarios, now())) {
                                is ImportResult.Created -> {
                                    props.scenarioRepository.save(result.scenario)
                                    importError = null
                                    refreshScenarios()
                                }
                                is ImportResult.Updated -> importError = "This scenario is already in your library."
                                is ImportResult.Rejected -> importError = result.message
                            }
                        }.onFailure { importError = clientMessage(it) }
                    }
                },
                onOpen = ::openScenario,
                onRefresh = ::refreshScenarios,
            )

            is View.Detail -> DetailView.create {
                scenario = current.scenario
                this.models = models
                modelError = modelLoadError
                sessions = { props.sessionRepository.list().filter { it.scenarioId == current.scenario.id } }
                onBack = { view = View.Library }
                onCreate = { model ->
                    scope.launch {
                        val time = now()
                        val session = GameSession(
                            id = "session-$time-${kotlin.random.Random.nextInt()}",
                            scenarioId = current.scenario.id,
                            title = "${current.scenario.name} adventure",
                            modelId = model,
                            messages = emptyList(),
                            createdAt = time,
                            updatedAt = time,
                        )
                        props.sessionRepository.save(session)
                        view = View.Play(current.scenario, session)
                    }
                }
                onResume = { session -> view = View.Play(current.scenario, session) }
                onReloadModels = { openScenario(current.scenario) }
            }

            is View.Play -> PlayView.create {
                scenario = current.scenario
                session = current.session
                api = props.api
                sessions = props.sessionRepository
                onBack = { view = View.Detail(current.scenario) }
                onSessionChanged = { session -> view = View.Play(current.scenario, session) }
            }
        }
    }
}

private fun ChildrenBuilder.libraryView(
    scenarios: List<Scenario>,
    importError: String?,
    onImport: (File) -> Unit,
    onOpen: (Scenario) -> Unit,
    onRefresh: () -> Unit,
) {
    ReactHTML.section {
        ReactHTML.h2 { +"Your scenarios" }
        ReactHTML.input {
            type = "file".unsafeCast<web.html.InputType>()
            accept = ".md,.markdown,text/markdown"
            onChange = { event ->
                event.target.unsafeCast<HTMLInputElement>().files?.item(0)?.let(onImport)
            }
        }
        importError?.let { ReactHTML.p { +it } }
        ReactHTML.button { onClick = { onRefresh() }; +"Refresh library" }
        if (scenarios.isEmpty()) ReactHTML.p { +"Import a Markdown scenario to begin." }
        ReactHTML.ul {
            scenarios.forEach { scenario ->
                ReactHTML.li {
                    ReactHTML.button { onClick = { onOpen(scenario) }; +scenario.name }
                }
            }
        }
    }
}

private external interface DetailViewProps : Props {
    var scenario: Scenario
    var models: List<String>
    var modelError: String?
    var sessions: suspend () -> List<GameSession>
    var onBack: () -> Unit
    var onCreate: (String) -> Unit
    var onResume: (GameSession) -> Unit
    var onReloadModels: () -> Unit
}

private val DetailView = FC<DetailViewProps> { props ->
    var loadedSessions by useState<List<GameSession>>(emptyList())
    var sessionsLoaded by useState(false)
    var sessionError by useState<String?>(null)
    fun loadSessions() = scope.launch {
        sessionsLoaded = true
        runCatching { props.sessions() }.onSuccess { loadedSessions = it }.onFailure { sessionError = clientMessage(it) }
    }
    if (!sessionsLoaded && sessionError == null) loadSessions()

    ReactHTML.section {
        ReactHTML.button { onClick = { props.onBack() }; +"Back to library" }
        ReactHTML.h2 { +props.scenario.name }
        ReactHTML.h3 { +"Choose a model for a new session" }
        props.modelError?.let { ReactHTML.p { +it } }
        ReactHTML.button { onClick = { props.onReloadModels() }; +"Reload models" }
        ReactHTML.ul {
            props.models.forEach { model ->
                ReactHTML.li { ReactHTML.button { onClick = { props.onCreate(model) }; +"Start with $model" } }
            }
        }
        ReactHTML.h3 { +"Saved sessions" }
        sessionError?.let { ReactHTML.p { +it } }
        ReactHTML.ul {
            loadedSessions.forEach { session ->
                ReactHTML.li { ReactHTML.button { onClick = { props.onResume(session) }; +session.title } }
            }
        }
    }
}

private external interface PlayViewProps : Props {
    var scenario: Scenario
    var session: GameSession
    var api: AdventureApi
    var sessions: SessionRepository
    var onBack: () -> Unit
    var onSessionChanged: (GameSession) -> Unit
}

private val PlayView = FC<PlayViewProps> { props ->
    var action by useState("")
    var pending by useState(false)
    var error by useState<String?>(null)
    var metrics by useState<TurnMetrics?>(null)

    ReactHTML.section {
        ReactHTML.button { onClick = { props.onBack() }; +"Back to sessions" }
        ReactHTML.h2 { +props.session.title }
        ReactHTML.p { +"Model: ${props.session.modelId}" }
        ReactHTML.div {
            props.session.messages.forEach { message ->
                ReactHTML.p { +"${message.role}: ${message.content}" }
            }
        }
        metrics?.let { renderMetrics(it) }
        error?.let { ReactHTML.p { +it } }
        ReactHTML.textarea {
            value = action
            disabled = pending
            onChange = { event -> action = event.target.unsafeCast<HTMLTextAreaElement>().value }
        }
        ReactHTML.button {
            disabled = pending || action.isBlank()
            onClick = {
                val playerAction = action.trim()
                if (playerAction.isNotBlank() && !pending) {
                    pending = true
                    error = null
                    action = ""
                    scope.launch {
                        try {
                            val completed = TurnSubmission(props.sessions, props.api).submit(
                                session = props.session,
                                scenario = props.scenario,
                                playerAction = playerAction,
                                onStarted = props.onSessionChanged,
                            )
                            metrics = completed.metrics
                            props.onSessionChanged(completed.session)
                        } catch (cause: Throwable) {
                            error = clientMessage(cause)
                        } finally {
                            pending = false
                        }
                    }
                }
            }
            +(if (pending) "Sending…" else "Send")
        }
    }
}

private fun ChildrenBuilder.renderMetrics(metrics: TurnMetrics) {
    val parts = listOfNotNull(
        metrics.totalDurationMs?.let { "duration: ${it}ms" },
        metrics.promptTokens?.let { "prompt tokens: $it" },
        metrics.cacheTokens?.let { "cache tokens: $it" },
    )
    if (parts.isNotEmpty()) ReactHTML.p { +parts.joinToString(" · ") }
}

private fun clientMessage(cause: Throwable): String = cause.message ?: "Something went wrong. Please try again."

internal fun now(): Long = (js("Date.now()") as Double).toLong()
