package de.aidadventure.web

import de.aidadventure.web.api.AdventureApi
import de.aidadventure.web.api.AdventureApiClient
import de.aidadventure.web.api.TurnMetrics
import de.aidadventure.web.model.GameMessage
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.model.WorldState
import de.aidadventure.web.persistence.AdventureDatabase
import de.aidadventure.web.persistence.ImportResult
import de.aidadventure.web.persistence.ScenarioImport
import de.aidadventure.web.persistence.ScenarioFrontmatter
import de.aidadventure.web.persistence.ScenarioRepository
import de.aidadventure.web.persistence.SessionRepository
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import react.useEffectWithCleanup
import react.useState
import web.cssom.ClassName
import web.dom.document
import web.events.Event
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
                    val time = now()
                    val session = newGameSession(current.scenario, "session-$time-${kotlin.random.Random.nextInt()}", time)
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
    var action by useState(""); var pending by useState(props.session.introductionPending); var inputReady by useState(false); var turnPhase by useState(TurnPhase.IDLE); var turnAction by useState<String?>(null); var queuedAction by useState<String?>(null); var promptBase by useState(props.session); var error by useState<String?>(null); var metrics by useState<TurnMetrics?>(null); var streamed by useState<GameSession?>(null); var openFragmentIndex by useState<Int?>(null); var worldStateOpen by useState(false)
    val liveId = props.session.id
    val (prefill) = useState(PrefillSubmission(props.api))
    val displayed = sessionForDisplay(props.session, streamed)
    fun introduce() {
        prefill.turnStarted()
        pending = true
        error = null
        scope.launch {
            try {
                runCatching { WarmupSubmission(props.api).submit(props.session, props.scenario) }
                val completed = TurnSubmission(props.sessions, props.api).submitIntroduction(
                    props.session, props.scenario,
                    { flushStreamUpdate { streamed = it } },
                    { flushStreamUpdate { streamed = it } },
                )
                metrics = completed.metrics
                streamed = completed.session
                props.onSessionChanged(completed.session)
            } catch (failure: Throwable) {
                streamed = null
                error = clientMessage(failure)
            } finally {
                prefill.turnFinished()
                pending = false
            }
        }
    }
    useEffect(dependencies = emptyArray()) {
        if (props.session.introductionPending) introduce()
        else scope.launch { runCatching { WarmupSubmission(props.api).submit(props.session, props.scenario) } }
    }
    useEffectWithCleanup(dependencies = arrayOf(openFragmentIndex, worldStateOpen)) {
        (if (worldStateOpen) worldStateTooltipContainerId() else openFragmentIndex?.let(::fragmentTooltipContainerId))?.let { containerId ->
            val outsideClick: (Event) -> Unit = { event ->
                val container = document.getElementById(containerId.unsafeCast<web.dom.ElementId>())
                if (container == null || !container.asDynamic().contains(event.target)) {
                    if (worldStateOpen) worldStateOpen = false else openFragmentIndex = null
                }
            }
            document.asDynamic().addEventListener("click", outsideClick)
            onCleanup { document.asDynamic().removeEventListener("click", outsideClick) }
        }
    }
    fun submit() {
        val text = action.trim()
        if (text.isBlank() || props.session.introductionPending) return
        action = ""
        error = null
        when (turnPhase) {
            TurnPhase.STREAMING -> {
                queuedAction = listOfNotNull(queuedAction, text).joinToString("\n")
                props.api.stopActiveTurn()
            }
            TurnPhase.READY -> {
                turnAction = listOfNotNull(turnAction, text).joinToString("\n")
                props.api.stopActiveTurn()
                prefill.supersede(promptBase, props.scenario, liveId, turnAction.orEmpty())
            }
            TurnPhase.PREPARING -> turnAction = listOfNotNull(turnAction, text).joinToString("\n")
            TurnPhase.IDLE -> {
                turnAction = text
                queuedAction = null
                promptBase = props.session
                pending = true
                inputReady = false
                turnPhase = TurnPhase.PREPARING
                prefill.turnStarted()
                scope.launch {
                    var base = props.session
                    try {
                        while (true) {
                            val submittedAction = turnAction ?: break
                            val completed = try {
                                TurnSubmission(props.sessions, props.api).submit(
                                    base, props.scenario, submittedAction,
                                    { flushStreamUpdate { streamed = it } },
                                    { session -> flushStreamUpdate { streamed = session } },
                                    { inputReady = true },
                                    { turnPhase = TurnPhase.STREAMING },
                                    liveId,
                                    onGenerationReady = {
                                        turnPhase = TurnPhase.READY
                                        if (turnAction != submittedAction) {
                                            prefill.supersede(base, props.scenario, liveId, turnAction.orEmpty())
                                            props.api.stopActiveTurn()
                                        }
                                    },
                                )
                            } catch (stopped: de.aidadventure.web.api.TurnStreamStoppedException) {
                                turnPhase = TurnPhase.PREPARING
                                streamed = base
                                delay(175)
                                prefill.turnStarted()
                                continue
                            }
                            base = completed.session
                            promptBase = base
                            metrics = completed.metrics
                            streamed = completed.session
                            props.onSessionChanged(completed.session)
                            val next = queuedAction
                            queuedAction = null
                            if (next == null) break
                            turnAction = next
                            turnPhase = TurnPhase.PREPARING
                            inputReady = false
                            prefill.turnStarted()
                        }
                    } catch (failure: Throwable) {
                        error = clientMessage(failure)
                    } finally {
                        turnAction = null
                        queuedAction = null
                        turnPhase = TurnPhase.IDLE
                        prefill.turnFinished()
                        pending = false
                        inputReady = false
                    }
                }
            }
        }
    }
    ReactHTML.section { className = ClassName("view-card")
        ReactHTML.button { className = ClassName("secondary-button"); disabled = pending; onClick = { props.onBack() }; +"Back to sessions" }
        ReactHTML.h2 { className = ClassName("view-title"); +displayed.title }
        ReactHTML.div { className = ClassName("transcript"); displayed.messages.forEachIndexed { index, message ->
            if (isVisibleTranscriptMessage(message)) ReactHTML.article {
                className = ClassName("message")
                val fragment = props.session.messages.getOrNull(index)
                    ?.takeIf { it == message }
                    ?.let { runtimeFragmentForGameMaster(props.session.messages, index) }
                fragment?.let { content ->
                    val tooltipId = fragmentTooltipId(index)
                    val isTooltipOpen = openFragmentIndex == index
                    ReactHTML.div {
                        id = fragmentTooltipContainerId(index).unsafeCast<web.dom.ElementId>()
                        className = ClassName("fragment-tooltip-container")
                        ReactHTML.button {
                            className = ClassName("fragment-info-button")
                            ariaLabel = "Fragment information"
                            asDynamic()["aria-expanded"] = isTooltipOpen.toString()
                            if (isTooltipOpen) asDynamic()["aria-describedby"] = tooltipId
                            onClick = { event ->
                                event.stopPropagation()
                                openFragmentIndex = if (openFragmentIndex == index) null else index
                            }
                            +"Info"
                        }
                        if (isTooltipOpen) ReactHTML.div {
                            id = tooltipId.unsafeCast<web.dom.ElementId>()
                            className = ClassName("fragment-tooltip")
                            asDynamic().role = "tooltip"
                            +content
                        }
                    }
                }
                MarkdownMessage { content = message.content }
            }
        } }
        ReactHTML.div {
            id = worldStateTooltipContainerId().unsafeCast<web.dom.ElementId>()
            className = ClassName("fragment-tooltip-container")
            ReactHTML.button {
                className = ClassName("fragment-info-button")
                ariaLabel = "World state information"
                asDynamic()["aria-expanded"] = worldStateOpen.toString()
                if (worldStateOpen) asDynamic()["aria-describedby"] = worldStateTooltipId()
                onClick = { event -> event.stopPropagation(); worldStateOpen = !worldStateOpen }
                +"World state"
            }
            if (worldStateOpen) ReactHTML.div {
                id = worldStateTooltipId().unsafeCast<web.dom.ElementId>()
                className = ClassName("fragment-tooltip")
                asDynamic().role = "tooltip"
                +worldStateJson(displayed.worldState)
            }
        }
        metrics?.let { renderMetrics(it) }; error?.let { ReactHTML.p { className = ClassName("status-error"); +it } }
        if (props.session.introductionPending && !pending && error != null) ReactHTML.button {
            className = ClassName("primary-button")
            onClick = { introduce() }
            +"Retry introduction"
        }
        ReactHTML.textarea { id = "player-action".unsafeCast<web.dom.ElementId>(); className = ClassName("action-input"); value = action; disabled = props.session.introductionPending; onChange = { action = it.target.unsafeCast<HTMLTextAreaElement>().value; if (turnPhase == TurnPhase.IDLE) prefill.changed(props.session, props.scenario, liveId, action) }; onKeyDown = { event -> if (shouldSubmitOnEnter(event.key, event.shiftKey, event.nativeEvent.asDynamic().isComposing.unsafeCast<Boolean>(), false, action)) { event.preventDefault(); submit() } } }
        ReactHTML.button { className = ClassName("primary-button"); disabled = props.session.introductionPending || action.isBlank(); onClick = { submit() }; +(if (pending && props.session.introductionPending) "Introducing adventure…" else if (pending && inputReady) "Updating world…" else if (pending) "Sending…" else "Send") }
    }
}

private enum class TurnPhase { IDLE, PREPARING, READY, STREAMING }

private fun ChildrenBuilder.renderMetrics(metrics: TurnMetrics) { listOfNotNull(metrics.totalDurationMs?.let { "duration: ${it}ms" }, metrics.promptTokens?.let { "prompt tokens: $it" }, metrics.cacheTokens?.let { "cache tokens: $it" }).takeIf { it.isNotEmpty() }?.let { ReactHTML.p { className = ClassName("metadata"); +it.joinToString(" · ") } } }
private fun clientMessage(cause: Throwable) = cause.message ?: "Something went wrong. Please try again."
internal fun flushStreamUpdate(update: () -> Unit) = flushSync(update)
internal fun sessionForDisplay(persisted: GameSession, streamed: GameSession?) = streamed ?: persisted
internal fun newGameSession(scenario: Scenario, id: String, time: Long): GameSession = GameSession(
    id = id,
    scenarioId = scenario.id,
    title = "${scenario.name} adventure",
    messages = emptyList(),
    createdAt = time,
    updatedAt = time,
    worldState = ScenarioFrontmatter.parse(scenario.markdown).initialWorldState,
    introductionPending = true,
)
internal fun isVisibleTranscriptMessage(message: GameMessage) = !message.hidden && message.role != "runtime-fragment"
internal fun runtimeFragmentForGameMaster(messages: List<GameMessage>, index: Int): String? =
    messages.getOrNull(index)?.takeIf { it.role == "game-master" }
        ?.let { messages.getOrNull(index - 2)?.takeIf { it.role == "runtime-fragment" }?.content
            ?.takeIf { messages.getOrNull(index - 1)?.role == "player" } }
private fun fragmentTooltipContainerId(index: Int) = "fragment-tooltip-container-$index"
private fun fragmentTooltipId(index: Int) = "fragment-tooltip-$index"
private fun worldStateTooltipContainerId() = "world-state-tooltip-container"
private fun worldStateTooltipId() = "world-state-tooltip"
internal fun worldStateJson(worldState: WorldState): String = Json.encodeToString(worldState)
internal fun shouldSubmitOnEnter(key: String, shiftKey: Boolean, isComposing: Boolean, pending: Boolean, action: String) = key == "Enter" && !shiftKey && !isComposing && !pending && action.isNotBlank()
internal fun now(): Long = (js("Date.now()") as Double).toLong()
