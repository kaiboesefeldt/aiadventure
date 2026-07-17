package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.AdventureApiClient
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.model.GameMessage
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
import react.ChildrenBuilder
import react.FC
import react.Props
import react.create
import react.dom.client.createRoot
import react.dom.flushSync
import react.dom.html.ReactHTML
import react.useEffect
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
        val koin = startKoin { modules(module {
            single<ScenarioRepository> { database.scenarios }
            single<SessionRepository> { database.sessions }
            single<AdventureApi> { AdventureApiClient() }
        }) }.koin
        val root = document.getElementById("root".unsafeCast<web.dom.ElementId>()) ?: error("Missing #root element")
        val initialScenarios = database.scenarios.list()
        createRoot(root).render(AdventureApp.create {
            scenarioRepository = koin.get(); sessionRepository = koin.get(); api = koin.get()
            this.initialScenarios = initialScenarios
        })
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

internal val AdventureApp = FC<AdventureAppProps> { props ->
    var view by useState<View>(View.Library)
    var scenarios by useState(props.initialScenarios)
    var importError by useState<String?>(null)
    var deletingScenarioId by useState<String?>(null)
    fun refresh() { scope.launch { scenarios = props.scenarioRepository.list() } }
    fun deleteScenario(scenario: Scenario) {
        deletingScenarioId = scenario.id
        importError = null
        scope.launch {
            runCatching {
                props.scenarioRepository.delete(scenario.id)
                props.scenarioRepository.list()
            }.onSuccess { scenarios = it }
                .onFailure { importError = clientMessage(it) }
            deletingScenarioId = null
        }
    }
    ReactHTML.main {
        className = ClassName("app-shell")
        ReactHTML.h1 { className = ClassName("app-title"); +"AI Adventure" }
        when (val current = view) {
            View.Library -> libraryView(scenarios, importError, { file -> scope.launch {
                runCatching { file.asDynamic().text().unsafeCast<kotlin.js.Promise<String>>().await() }
                    .onSuccess { markdown -> when (val result = ScenarioImport.fromFile(file.name, markdown, scenarios, now())) {
                        is ImportResult.Created -> { props.scenarioRepository.save(result.scenario); importError = null; refresh() }
                        is ImportResult.Updated -> importError = "This scenario is already in your library."
                        is ImportResult.Rejected -> importError = result.message
                    } }.onFailure { importError = clientMessage(it) }
            } }, { view = View.Detail(it) }, ::refresh, ::deleteScenario, deletingScenarioId)
            is View.Detail -> +DetailView.create {
                scenario = current.scenario; api = props.api
                sessions = { props.sessionRepository.list().filter { it.scenarioId == current.scenario.id } }
                onBack = { view = View.Library }
                onCreate = { scope.launch {
                    val time = now(); val session = GameSession("session-$time-${kotlin.random.Random.nextInt()}", current.scenario.id,
                        "${current.scenario.name} adventure", "configured-llama-cpp", emptyList(), time, time)
                    props.sessionRepository.save(session); view = View.Play(current.scenario, session)
                } }
                onResume = { session -> view = View.Play(current.scenario, session) }
                onDeleteSession = { session -> props.sessionRepository.delete(session.id) }
            }
            is View.Play -> +PlayView.create {
                scenario = current.scenario; session = current.session; api = props.api; sessions = props.sessionRepository
                onBack = { view = View.Detail(current.scenario) }
                onSessionChanged = { view = View.Play(current.scenario, it) }
            }
        }
    }
}

private fun ChildrenBuilder.libraryView(scenarios: List<Scenario>, error: String?, onImport: (File) -> Unit, onOpen: (Scenario) -> Unit, onRefresh: () -> Unit, onDelete: (Scenario) -> Unit, deletingScenarioId: String?) {
    ReactHTML.section {
        className = ClassName("view-card"); ReactHTML.h2 { className = ClassName("view-title"); +"Your scenarios" }
        ReactHTML.input { type = "file".unsafeCast<web.html.InputType>(); accept = ".md,.markdown,text/markdown"; onChange = { it.target.unsafeCast<HTMLInputElement>().files?.item(0)?.let(onImport) } }
        error?.let { ReactHTML.p { className = ClassName("status-error"); +it } }
        ReactHTML.button { className = ClassName("secondary-button"); disabled = deletingScenarioId != null; onClick = { onRefresh() }; +"Refresh library" }
        ReactHTML.div { className = ClassName("stack"); scenarios.forEach { scenario -> ReactHTML.div {
            className = ClassName("action-row")
            ReactHTML.button { className = ClassName("action-row-open"); disabled = deletingScenarioId != null; onClick = { onOpen(scenario) }
                ReactHTML.span { className = ClassName("action-row-title"); +scenario.name }
            }
            ReactHTML.button { className = ClassName("delete-button"); disabled = deletingScenarioId != null; onClick = { onDelete(scenario) }; +"Delete scenario" }
        } } }
    }
}

private external interface DetailViewProps : Props {
    var scenario: Scenario; var api: AdventureApi; var sessions: suspend () -> List<GameSession>
    var onBack: () -> Unit; var onCreate: () -> Unit; var onResume: (GameSession) -> Unit; var onDeleteSession: suspend (GameSession) -> Unit
}

private val DetailView = FC<DetailViewProps> { props ->
    var loadedSessions by useState<List<GameSession>>(emptyList())
    var deletingSessionId by useState<String?>(null)
    var error by useState<String?>(null)
    useEffect(dependencies = emptyArray()) { scope.launch { runCatching { props.sessions() }.onSuccess { loadedSessions = it }.onFailure { error = clientMessage(it) } } }
    fun deleteSession(session: GameSession) {
        deletingSessionId = session.id
        error = null
        scope.launch {
            runCatching {
                props.onDeleteSession(session)
                props.sessions()
            }.onSuccess { loadedSessions = it }
                .onFailure { error = clientMessage(it) }
            deletingSessionId = null
        }
    }
    val busy = deletingSessionId != null
    ReactHTML.section { className = ClassName("view-card")
        ReactHTML.button { className = ClassName("secondary-button"); disabled = busy; onClick = { props.onBack() }; +"Back to library" }
        ReactHTML.h2 { className = ClassName("view-title"); +props.scenario.name }
        ReactHTML.button { className = ClassName("primary-button"); disabled = busy; onClick = { props.onCreate() }; +"New session" }
        error?.let { ReactHTML.p { className = ClassName("status-error"); +it } }
        ReactHTML.h3 { className = ClassName("section-title"); +"Saved sessions" }
        ReactHTML.div { className = ClassName("stack"); loadedSessions.forEach { session -> ReactHTML.div { className = ClassName("action-row")
            ReactHTML.button { className = ClassName("action-row-open"); disabled = busy; onClick = { props.onResume(session) }; +session.title }
            ReactHTML.button { className = ClassName("delete-button"); disabled = busy; onClick = { deleteSession(session) }; +"Delete session" }
        } } }
    }
}

private external interface PlayViewProps : Props { var scenario: Scenario; var session: GameSession; var api: AdventureApi; var sessions: SessionRepository; var onBack: () -> Unit; var onSessionChanged: (GameSession) -> Unit }
private val PlayView = FC<PlayViewProps> { props ->
    var action by useState(""); var pending by useState(false); var error by useState<String?>(null); var metrics by useState<TurnMetrics?>(null); var streamed by useState<GameSession?>(null)
    val liveId = props.session.id
    val (prefill) = useState(PrefillSubmission(props.api))
    val displayed = sessionForDisplay(props.session, streamed)
    useEffect(dependencies = emptyArray()) {
        scope.launch { runCatching { WarmupSubmission(props.api).submit(props.session, props.scenario) } }
    }
    fun submit() { val text = action.trim(); if (text.isBlank() || pending) return; prefill.turnStarted(); pending = true; action = ""; error = null; scope.launch { try {
        val completed = TurnSubmission(props.sessions, props.api).submit(props.session, props.scenario, text, { flushStreamUpdate { streamed = it } }, { flushStreamUpdate { streamed = it } }, liveId)
        metrics = completed.metrics; streamed = completed.session; props.onSessionChanged(completed.session)
    } catch (failure: Throwable) { error = clientMessage(failure) } finally { prefill.turnFinished(); pending = false } } }
    ReactHTML.section { className = ClassName("view-card")
        ReactHTML.button { className = ClassName("secondary-button"); disabled = pending; onClick = { props.onBack() }; +"Back to sessions" }
        ReactHTML.h2 { className = ClassName("view-title"); +displayed.title }
        ReactHTML.div { className = ClassName("transcript"); displayed.messages.forEach { message -> ReactHTML.article { className = ClassName("message"); MarkdownMessage { content = message.content } } } }
        metrics?.let { renderMetrics(it) }; error?.let { ReactHTML.p { className = ClassName("status-error"); +it } }
        ReactHTML.textarea { id = "player-action".unsafeCast<web.dom.ElementId>(); className = ClassName("action-input"); value = action; disabled = pending; onChange = { action = it.target.unsafeCast<HTMLTextAreaElement>().value; prefill.changed(props.session, props.scenario, liveId, action) }; onKeyDown = { event -> if (shouldSubmitOnEnter(event.key, event.shiftKey, event.nativeEvent.asDynamic().isComposing.unsafeCast<Boolean>(), pending, action)) { event.preventDefault(); submit() } } }
        ReactHTML.button { className = ClassName("primary-button"); disabled = pending || action.isBlank(); onClick = { submit() }; +(if (pending) "Sending…" else "Send") }
    }
}

private fun ChildrenBuilder.renderMetrics(metrics: TurnMetrics) { listOfNotNull(metrics.totalDurationMs?.let { "duration: ${it}ms" }, metrics.promptTokens?.let { "prompt tokens: $it" }, metrics.cacheTokens?.let { "cache tokens: $it" }).takeIf { it.isNotEmpty() }?.let { ReactHTML.p { className = ClassName("metadata"); +it.joinToString(" · ") } } }
private fun clientMessage(cause: Throwable) = cause.message ?: "Something went wrong. Please try again."
internal fun flushStreamUpdate(update: () -> Unit) = flushSync(update)
internal fun sessionForDisplay(persisted: GameSession, streamed: GameSession?) = streamed ?: persisted
internal fun shouldSubmitOnEnter(key: String, shiftKey: Boolean, isComposing: Boolean, pending: Boolean, action: String) = key == "Enter" && !shiftKey && !isComposing && !pending && action.isNotBlank()
internal fun now(): Long = (js("Date.now()") as Double).toLong()
