package de.aidadventure.web

import de.aidadventure.web.model.DebugEvent
import de.aidadventure.web.model.DiagnosticMetrics
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import de.aidadventure.web.persistence.ScenarioFrontmatter
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML
import react.useEffect
import react.useEffectWithCleanup
import react.useState
import web.cssom.ClassName
import web.dom.document
import kotlin.js.JsModule
import kotlin.js.JsNonModule

internal external interface DebugPanelProps : Props {
    var scenario: Scenario
    var session: GameSession
    var events: List<DebugEvent>
    var onClose: () -> Unit
    var onClear: () -> Unit
    var onShowTranscript: (Int) -> Unit
}

internal val DebugPanel = FC<DebugPanelProps> { props ->
    var readerAtBottom by useState(true)
    val ordered = props.events
    useEffect(dependencies = arrayOf(ordered.size)) {
        if (ordered.isEmpty()) readerAtBottom = true
        if (readerAtBottom) {
            val scroller = document.getElementById("debug-event-list".unsafeCast<web.dom.ElementId>())
            if (scroller != null) scroller.asDynamic().scrollTop = scroller.asDynamic().scrollHeight
        }
    }
    useEffectWithCleanup(dependencies = emptyArray()) {
        document.querySelector("button[aria-label='Close debug panel']")?.asDynamic()?.focus()
        val onKeyDown: (dynamic) -> Unit = { event ->
            if (event.key == "Escape") props.onClose()
        }
        document.asDynamic().addEventListener("keydown", onKeyDown)
        onCleanup { document.asDynamic().removeEventListener("keydown", onKeyDown) }
    }
    ReactHTML.aside {
        className = ClassName("debug-panel")
        asDynamic().role = "dialog"
        ariaLabel = "Debug panel"
        ReactHTML.header {
            className = ClassName("debug-panel-header")
            ReactHTML.h2 { +"Debug panel" }
            ReactHTML.button {
                className = ClassName("debug-close-button")
                ariaLabel = "Close debug panel"
                onClick = { props.onClose() }
                +"×"
            }
        }
        ReactHTML.div {
            className = ClassName("debug-global")
            ReactHTML.h3 { +"Current flags" }
            if (props.session.flags.isEmpty()) ReactHTML.p { +"No flags" }
            else ReactHTML.dl {
                className = ClassName("debug-fields")
                props.session.flags.toList().sortedBy { it.first }.forEach { (name, value) ->
                    ReactHTML.dt { +name }
                    ReactHTML.dd { +value.toString() }
                }
            }
            ReactHTML.details {
                ReactHTML.summary { +"Frontmatter" }
                val yaml = frontmatterYaml(props.scenario.markdown)
                if (yaml == null) ReactHTML.p { +"No frontmatter" }
                else renderYamlNode(yaml)
            }
            ReactHTML.details {
                ReactHTML.summary { +"System prompt" }
                ReactHTML.pre { className = ClassName("debug-prompt"); +ScenarioFrontmatter.parse(props.scenario.markdown).promptMarkdown }
            }
        }
        ReactHTML.div {
            className = ClassName("debug-log-heading")
            ReactHTML.h3 { +"Turn diagnostics" }
            ReactHTML.button {
                className = ClassName("secondary-button")
                onClick = { props.onClear() }
                +"Clear debug log"
            }
        }
        ReactHTML.div {
            id = "debug-event-list".unsafeCast<web.dom.ElementId>()
            className = ClassName("debug-event-list")
            asDynamic().onScroll = { event: dynamic ->
                val target = event.target
                readerAtBottom = target.scrollHeight - target.scrollTop - target.clientHeight <= 24
            }
            if (ordered.isEmpty()) ReactHTML.p { className = ClassName("empty-state"); +"No turn diagnostics yet." }
            val visibleMessages = props.session.messages.filter(::isVisibleTranscriptMessage)
            ordered.forEach { event ->
                val messageId = event.transcriptMessageId
                val index = if (messageId == null) -1 else visibleMessages.indexOfFirst { it.messageId == messageId && it.role == event.transcriptRole }
                renderDebugEvent(event, index.takeIf { it >= 0 }, props.onShowTranscript)
            }
        }
    }
}

private fun ChildrenBuilder.renderDebugEvent(event: DebugEvent, transcriptIndex: Int?, onShowTranscript: (Int) -> Unit) {
    ReactHTML.article {
        key = event.id
        id = "debug-event-${event.id}".unsafeCast<web.dom.ElementId>()
        className = ClassName("debug-event")
        ReactHTML.div {
            className = ClassName("debug-event-topline")
            ReactHTML.h4 { +humanLabel(event.kind) }
            ReactHTML.span { className = ClassName("debug-stage"); +humanLabel(event.stage) }
        }
        if (transcriptIndex != null) ReactHTML.button {
            className = ClassName("secondary-button debug-navigation-button")
            ariaLabel = "Show transcript message"
            onClick = { onShowTranscript(transcriptIndex) }
            +"Show transcript message"
        }
        ReactHTML.details {
            ReactHTML.summary { +"Details" }
            val safeFields = event.fields.filterKeys { key ->
                key.lowercase().filter { it.isLetter() } !in setOf(
                    "playeraction", "gamemasteroutput", "generated", "generatedtext", "response", "responsetext", "prompt", "history",
                )
            }
            if (safeFields.isNotEmpty() || event.metrics != null) ReactHTML.dl {
                className = ClassName("debug-fields")
                safeFields.forEach { (key, value) -> field(humanLabel(key), if (key == "source") humanLabel(value) else value) }
                event.metrics?.let { renderDiagnosticMetrics(it) }
            }
        }
    }
}

private fun ChildrenBuilder.renderDiagnosticMetrics(metrics: DiagnosticMetrics) {
    metrics.promptTokens?.let { field("Prompt tokens", it.toString()) }
    metrics.contextTokens?.let { field("Context tokens", it.toString()) }
    metrics.predictedTokens?.let { field("Predicted tokens", it.toString()) }
    metrics.cacheTokens?.let { field("Cache tokens", it.toString()) }
    metrics.evaluatedTokens?.let { field("Evaluated tokens", it.toString()) }
    metrics.promptDurationMs?.let { field("Prompt duration", "$it ms") }
    metrics.predictedDurationMs?.let { field("Predicted duration", "$it ms") }
    metrics.promptTokensPerSecond?.let { field("Prompt tokens per second", "$it tokens/s") }
    metrics.predictedTokensPerSecond?.let { field("Predicted tokens per second", "$it tokens/s") }
    metrics.totalDurationMs?.let { field("Total duration", "$it ms") }
}

private fun ChildrenBuilder.field(label: String, value: String) {
    ReactHTML.dt { +label }
    ReactHTML.dd { +value }
}

private fun humanLabel(value: String): String = value
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .replace('_', ' ')
    .lowercase()
    .replaceFirstChar { it.uppercase() }

private fun frontmatterYaml(markdown: String): dynamic {
    val normalized = markdown.replace("\r\n", "\n")
    if (!normalized.startsWith("---\n")) return null
    val end = normalized.indexOf("\n---", 4)
    return if (end < 0) null else DebugYaml.load(normalized.substring(4, end))
}

private fun ChildrenBuilder.renderYamlNode(value: dynamic, ancestors: dynamic = js("new WeakSet()")) {
    val container = value != null && js("typeof value === 'object'") as Boolean
    if (container) {
        if (ancestors.has(value) as Boolean) {
            ReactHTML.span { +"Circular reference" }
            return
        }
        ancestors.add(value)
    }
    when {
        value == null -> ReactHTML.span { +"null" }
        js("Array.isArray(value)") as Boolean -> ReactHTML.ol {
            value.unsafeCast<Array<dynamic>>().forEach { item -> ReactHTML.li { renderYamlNode(item, ancestors) } }
        }
        js("typeof value === 'object'") as Boolean -> ReactHTML.ul {
            js("Object.entries(value)").unsafeCast<Array<Array<dynamic>>>().forEach { entry ->
                ReactHTML.li {
                    ReactHTML.strong { +entry[0].toString() }
                    +": "
                    renderYamlNode(entry[1], ancestors)
                }
            }
        }
        else -> ReactHTML.span { +value.toString() }
    }
    if (container) ancestors.delete(value)
}

@JsModule("js-yaml")
@JsNonModule
private external object DebugYaml {
    fun load(input: String): dynamic
}
