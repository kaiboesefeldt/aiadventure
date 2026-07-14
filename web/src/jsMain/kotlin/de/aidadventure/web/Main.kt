package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.AdventureApiClient
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.ModelProvider
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
import react.dom.flushSync
import react.dom.html.ReactHTML
import react.useState
import web.cssom.ClassName
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
    data class Detail(val scenario: Scenario, val llamaCppSession: GameSession? = null) : View
    data class Play(val scenario: Scenario, val session: GameSession) : View
}

private val AdventureApp = FC<AdventureAppProps> { props ->
    var view by useState<View>(View.Library)
    var scenarios by useState(props.initialScenarios)
    var importError by useState<String?>(null)
    var models by useState<List<String>>(emptyList())
    var modelLoadError by useState<String?>(null)
    var llamaCppConnection by useState(LlamaCppConnectionState.initial())

    fun refreshScenarios() {
        scope.launch { scenarios = props.scenarioRepository.list() }
    }

    fun openScenario(scenario: Scenario) {
        view = View.Detail(scenario)
        models = emptyList()
        modelLoadError = null
        llamaCppConnection = LlamaCppConnectionState.initial()
        scope.launch {
            runCatching { props.api.models() }
                .onSuccess { models = it }
                .onFailure { modelLoadError = clientMessage(it) }
        }
    }

    ReactHTML.main {
        className = ClassName("app-shell")
        ReactHTML.h1 {
            className = ClassName("app-title")
            +"AI Adventure"
        }
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

            is View.Detail -> +DetailView.create {
                scenario = current.scenario
                this.models = models
                modelError = modelLoadError
                api = props.api
                llamaCppConnection = llamaCppConnection
                llamaCppSession = current.llamaCppSession
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
                onLlamaCppConnectionChanged = { connection -> llamaCppConnection = connection }
                onLlamaCppStart = { model, port, onComplete ->
                    scope.launch {
                        runCatching {
                            val time = now()
                            val session = current.llamaCppSession?.copy(
                                modelId = model,
                                updatedAt = time,
                                provider = ModelProvider.LLAMA_CPP,
                                llamaCppPort = port,
                            ) ?: GameSession(
                                id = "session-$time-${kotlin.random.Random.nextInt()}",
                                scenarioId = current.scenario.id,
                                title = "${current.scenario.name} adventure",
                                modelId = model,
                                messages = emptyList(),
                                createdAt = time,
                                updatedAt = time,
                                provider = ModelProvider.LLAMA_CPP,
                                llamaCppPort = port,
                            )
                            props.sessionRepository.save(session)
                            session
                        }.onSuccess { session ->
                            view = View.Play(current.scenario, session)
                            onComplete(null)
                        }.onFailure(onComplete)
                    }
                }
                onResume = { session ->
                    if (session.provider == ModelProvider.LLAMA_CPP) {
                        llamaCppConnection = LlamaCppConnectionState.initial(session.llamaCppPort, session.modelId)
                        view = View.Detail(current.scenario, session)
                    } else {
                        view = View.Play(current.scenario, session)
                    }
                }
                onReloadModels = { openScenario(current.scenario) }
            }

            is View.Play -> +PlayView.create {
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
        className = ClassName("view-card")
        ReactHTML.h2 {
            className = ClassName("view-title")
            +"Your scenarios"
        }
        ReactHTML.input {
            type = "file".unsafeCast<web.html.InputType>()
            accept = ".md,.markdown,text/markdown"
            onChange = { event ->
                event.target.unsafeCast<HTMLInputElement>().files?.item(0)?.let(onImport)
            }
        }
        importError?.let {
            ReactHTML.p {
                className = ClassName("status-error")
                +it
            }
        }
        ReactHTML.button {
            className = ClassName("secondary-button")
            onClick = { onRefresh() }
            +"Refresh library"
        }
        if (scenarios.isEmpty()) {
            ReactHTML.p {
                className = ClassName("empty-state")
                +"Import a Markdown scenario to begin."
            }
        }
        ReactHTML.div {
            className = ClassName("stack")
            scenarios.forEach { scenario ->
                ReactHTML.button {
                    className = ClassName("action-row")
                    onClick = { onOpen(scenario) }
                    ReactHTML.span {
                        className = ClassName("action-row-title")
                        +scenario.name
                    }
                    ReactHTML.span {
                        className = ClassName("action-row-meta")
                        +"Imported scenario"
                    }
                }
            }
        }
    }
}

private external interface DetailViewProps : Props {
    var scenario: Scenario
    var models: List<String>
    var modelError: String?
    var api: AdventureApi
    var llamaCppConnection: LlamaCppConnectionState
    var llamaCppSession: GameSession?
    var sessions: suspend () -> List<GameSession>
    var onBack: () -> Unit
    var onCreate: (String) -> Unit
    var onLlamaCppConnectionChanged: (LlamaCppConnectionState) -> Unit
    var onLlamaCppStart: (String, Int, (Throwable?) -> Unit) -> Unit
    var onResume: (GameSession) -> Unit
    var onReloadModels: () -> Unit
}

private val DetailView = FC<DetailViewProps> { props ->
    var loadedSessions by useState<List<GameSession>>(emptyList())
    var sessionsLoaded by useState(false)
    var sessionError by useState<String?>(null)
    var llamaCppPending by useState(false)
    fun loadSessions() = scope.launch {
        sessionsLoaded = true
        runCatching { props.sessions() }.onSuccess { loadedSessions = it }.onFailure { sessionError = clientMessage(it) }
    }
    if (!sessionsLoaded && sessionError == null) loadSessions()

    ReactHTML.section {
        className = ClassName("view-card")
        ReactHTML.div {
            className = ClassName("view-header")
            ReactHTML.button {
                className = ClassName("secondary-button")
                onClick = { props.onBack() }
                +"Back to library"
            }
            ReactHTML.h2 {
                className = ClassName("view-title")
                +props.scenario.name
            }
        }
        ReactHTML.h3 {
            className = ClassName("section-title")
            +"Choose a model for a new session"
        }
        props.modelError?.let {
            ReactHTML.p {
                className = ClassName("status-error")
                +it
            }
        }
        ReactHTML.button {
            className = ClassName("secondary-button")
            onClick = { props.onReloadModels() }
            +"Reload models"
        }
        ReactHTML.div {
            className = ClassName("stack")
            props.models.forEach { model ->
                ReactHTML.button {
                    className = ClassName("action-row primary-button")
                    onClick = { props.onCreate(model) }
                    ReactHTML.span {
                        className = ClassName("action-row-title")
                        +"Start with $model"
                    }
                }
            }
        }
        ReactHTML.h3 {
            className = ClassName("section-title")
            +(if (props.llamaCppSession == null) "Or connect to llama.cpp" else "Reconnect to llama.cpp")
        }
        ReactHTML.div {
            className = ClassName("stack")
            ReactHTML.label {
                className = ClassName("action-label")
                htmlFor = "llama-cpp-port".unsafeCast<web.dom.ElementId>()
                +"llama.cpp port"
            }
            ReactHTML.input {
                id = "llama-cpp-port".unsafeCast<web.dom.ElementId>()
                type = "number".unsafeCast<web.html.InputType>()
                value = props.llamaCppConnection.portText
                disabled = !props.llamaCppConnection.portEditable || llamaCppPending
                onChange = { event ->
                    props.onLlamaCppConnectionChanged(
                        props.llamaCppConnection.withPortText(event.target.unsafeCast<HTMLInputElement>().value),
                    )
                }
            }
            props.llamaCppConnection.error?.let {
                ReactHTML.p {
                    className = ClassName("status-error")
                    +it
                }
            }
            ReactHTML.button {
                className = ClassName("primary-button")
                disabled = llamaCppPending
                onClick = {
                    val port = props.llamaCppConnection.portText.toIntOrNull()
                    if (port == null || port !in 1..65535) {
                        props.onLlamaCppConnectionChanged(
                            props.llamaCppConnection.failed("Enter a valid llama.cpp port."),
                        )
                    } else if (props.llamaCppConnection.startOnClick) {
                        props.onLlamaCppStart(props.llamaCppConnection.modelId, port) { failure ->
                            if (failure != null) {
                                props.onLlamaCppConnectionChanged(
                                    props.llamaCppConnection.failed(clientMessage(failure)),
                                )
                            }
                        }
                    } else {
                        llamaCppPending = true
                        props.onLlamaCppConnectionChanged(props.llamaCppConnection.withoutError())
                        scope.launch {
                            runCatching {
                                props.api.llamaCppModels(port).firstOrNull()
                                    ?: throw IllegalStateException("No llama.cpp models are available on this port.")
                            }.onSuccess { model ->
                                props.onLlamaCppConnectionChanged(props.llamaCppConnection.connected(model))
                            }.onFailure { failure ->
                                props.onLlamaCppConnectionChanged(props.llamaCppConnection.failed(clientMessage(failure)))
                            }
                            llamaCppPending = false
                        }
                    }
                }
                +(when {
                    llamaCppPending -> "Checking llama.cpp…"
                    props.llamaCppConnection.startOnClick -> "Start with ${props.llamaCppConnection.modelId}"
                    else -> "Check llama.cpp"
                })
            }
        }
        ReactHTML.h3 {
            className = ClassName("section-title")
            +"Saved sessions"
        }
        sessionError?.let {
            ReactHTML.p {
                className = ClassName("status-error")
                +it
            }
        }
        ReactHTML.div {
            className = ClassName("stack")
            loadedSessions.forEach { session ->
                ReactHTML.button {
                    className = ClassName("action-row")
                    onClick = { props.onResume(session) }
                    ReactHTML.span {
                        className = ClassName("action-row-title")
                        +session.title
                    }
                    ReactHTML.span {
                        className = ClassName("action-row-meta")
                        +"${session.modelId} · ${session.messages.size} messages"
                    }
                }
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
    var streamedSession by useState<GameSession?>(null)
    val displayedSession = sessionForDisplay(props.session, streamedSession)

    fun submitAction() {
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
                        onStarted = { session ->
                            flushStreamUpdate { streamedSession = session }
                        },
                        onStreamed = { session ->
                            flushStreamUpdate { streamedSession = session }
                        },
                    )
                    metrics = completed.metrics
                    flushStreamUpdate { streamedSession = completed.session }
                    props.onSessionChanged(completed.session)
                } catch (cause: Throwable) {
                    error = clientMessage(cause)
                } finally {
                    pending = false
                }
            }
        }
    }

    ReactHTML.section {
        className = ClassName("view-card")
        ReactHTML.div {
            className = ClassName("view-header")
            ReactHTML.button {
                className = ClassName("secondary-button")
                onClick = { props.onBack() }
                +"Back to sessions"
            }
            ReactHTML.h2 {
                className = ClassName("view-title")
                +displayedSession.title
            }
        }
        ReactHTML.p {
            className = ClassName("metadata")
            +"Model: ${displayedSession.modelId}"
        }
        ReactHTML.div {
            className = ClassName("transcript")
            displayedSession.messages.forEach { message ->
                val player = message.role in setOf("player", "user")
                ReactHTML.article {
                    className = ClassName(if (player) "message message--player" else "message message--game-master")
                    ReactHTML.span {
                        className = ClassName("message-speaker")
                        +(if (player) "You" else "Game master")
                    }
                    ReactHTML.div {
                        className = ClassName("message-content")
                        MarkdownMessage {
                            content = message.content
                        }
                    }
                }
            }
        }
        metrics?.let { renderMetrics(it) }
        error?.let {
            ReactHTML.p {
                className = ClassName("status-error")
                +it
            }
        }
        ReactHTML.div {
            className = ClassName("action-form")
            ReactHTML.label {
                className = ClassName("action-label")
                htmlFor = "player-action".unsafeCast<web.dom.ElementId>()
                +"Your action"
            }
            ReactHTML.p {
                className = ClassName("input-hint")
                +"Enter sends · Shift+Enter adds a new line"
            }
            ReactHTML.textarea {
                id = "player-action".unsafeCast<web.dom.ElementId>()
                className = ClassName("action-input")
                value = action
                disabled = pending
                placeholder = "What do you do?"
                onChange = { event -> action = event.target.unsafeCast<HTMLTextAreaElement>().value }
                onKeyDown = { event ->
                    val composing = event.nativeEvent.asDynamic().isComposing.unsafeCast<Boolean>()
                    if (shouldSubmitOnEnter(event.key, event.shiftKey, composing, pending, action)) {
                        event.preventDefault()
                        submitAction()
                    }
                }
            }
            ReactHTML.button {
                className = ClassName("primary-button")
                disabled = pending || action.isBlank()
                onClick = { submitAction() }
                +(if (pending) "Sending…" else "Send")
            }
        }
    }
}

private fun ChildrenBuilder.renderMetrics(metrics: TurnMetrics) {
    val parts = listOfNotNull(
        metrics.totalDurationMs?.let { "duration: ${it}ms" },
        metrics.promptTokens?.let { "prompt tokens: $it" },
        metrics.cacheTokens?.let { "cache tokens: $it" },
    )
    if (parts.isNotEmpty()) {
        ReactHTML.p {
            className = ClassName("metadata")
            +parts.joinToString(" · ")
        }
    }
}

private fun clientMessage(cause: Throwable): String = cause.message ?: "Something went wrong. Please try again."

internal fun flushStreamUpdate(update: () -> Unit) {
    flushSync(update)
}

internal fun sessionForDisplay(persisted: GameSession, streamed: GameSession?): GameSession = streamed ?: persisted

internal fun shouldSubmitOnEnter(
    key: String,
    shiftKey: Boolean,
    isComposing: Boolean,
    pending: Boolean,
    action: String,
): Boolean = key == "Enter" && !shiftKey && !isComposing && !pending && action.isNotBlank()

internal data class LlamaCppConnectionState(
    val portText: String,
    val modelId: String = "",
    val checked: Boolean = false,
    val error: String? = null,
) {
    val portEditable: Boolean get() = !checked
    val startOnClick: Boolean get() = checked && modelId.isNotBlank()

    fun withPortText(portText: String): LlamaCppConnectionState = copy(
        portText = portText,
        modelId = "",
        checked = false,
        error = null,
    )

    fun withoutError(): LlamaCppConnectionState = copy(error = null)

    fun connected(modelId: String): LlamaCppConnectionState = copy(
        modelId = modelId,
        checked = true,
        error = null,
    )

    fun failed(error: String): LlamaCppConnectionState = copy(
        checked = false,
        error = error,
    )

    companion object {
        fun initial(port: Int? = null, modelId: String? = null): LlamaCppConnectionState = LlamaCppConnectionState(
            portText = (port ?: AdventureApiClient.DEFAULT_LLAMA_CPP_PORT).toString(),
            modelId = modelId.orEmpty(),
        )
    }
}

internal fun now(): Long = (js("Date.now()") as Double).toLong()
