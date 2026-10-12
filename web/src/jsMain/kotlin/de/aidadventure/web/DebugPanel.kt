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
    var headerPopover by useState("")
    var collapsedEvents by useState(emptySet<String>())
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
            ReactHTML.div {
                className = ClassName("debug-header-tools")
                listOf("flags" to "⚑", "frontmatter" to "{}", "prompt" to "¶").forEach { (tool, icon) ->
                    val popoverId = "debug-header-popover-$tool"
                    val label = when (tool) { "flags" -> "Current flags"; "frontmatter" -> "Frontmatter"; else -> "System prompt" }
                    ReactHTML.button {
                        className = ClassName("debug-icon-button")
                        title = label
                        ariaLabel = label
                        ariaControls = popoverId.unsafeCast<web.dom.ElementId>()
                        ariaExpanded = headerPopover == tool
                        onClick = { headerPopover = if (headerPopover == tool) "" else tool }
                        +icon
                    }
                }
            }
            ReactHTML.button {
                className = ClassName("debug-close-button")
                ariaLabel = "Close debug panel"
                onClick = { props.onClose() }
                +"×"
            }
        }
        listOf("flags", "frontmatter", "prompt").forEach { tool ->
            ReactHTML.div {
                id = "debug-header-popover-$tool".unsafeCast<web.dom.ElementId>()
                className = ClassName("debug-popover debug-header-popover")
                asDynamic().hidden = headerPopover != tool
                if (headerPopover == tool) {
                    if (tool == "flags") {
                        ReactHTML.h3 { +"Current flags" }
                        renderFlagValues(props.session.flags)
                    } else if (tool == "frontmatter") {
                        ReactHTML.h3 { +"Frontmatter" }
                        val yaml = frontmatterYaml(props.scenario.markdown)
                        if (yaml == null) ReactHTML.p { +"No frontmatter" } else renderYamlNode(yaml)
                    } else {
                        ReactHTML.h3 { +"System prompt" }
                        ReactHTML.pre { className = ClassName("debug-prompt"); +ScenarioFrontmatter.parse(props.scenario.markdown).promptMarkdown }
                    }
                }
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
            val turnParity = ordered.map { it.turnId }.distinct().withIndex().associate { it.value to (it.index % 2) }
            val backgroundOutcomes = ordered.filter { it.kind == "background_rerank" && it.stage.startsWith("prior_") }
                .associateBy { it.fields["operationId"] }
            val scheduledOperationIds = ordered.filter { it.kind == "background_rerank" && it.stage == "scheduled" }
                .mapNotNull { it.fields["operationId"] }.toSet()
            val displayedMessages = DisplayedChatMessages()
            ordered.filterNot {
                it.kind == "background_rerank" && it.stage.startsWith("prior_") && it.fields["operationId"] in scheduledOperationIds
            }.forEach { storedEvent ->
                val completedEvent = if (storedEvent.kind == "background_rerank" && storedEvent.stage == "scheduled") {
                    backgroundOutcomes[storedEvent.fields["operationId"]]?.let { outcome ->
                        storedEvent.copy(stage = outcome.stage, fields = storedEvent.fields + outcome.fields, metrics = outcome.metrics ?: storedEvent.metrics)
                    } ?: storedEvent
                } else storedEvent
                val event = completedEvent.copy(fields = completedEvent.fields.mapValues { (key, value) ->
                    if (!isChatSnapshotField(key)) value else displayedMessages.onlyNewMessages(value)
                })
                // Outputs are already visible in their own section. Count their next model
                // occurrence so a subsequent prompt does not display them again as context.
                completedEvent.fields.filterKeys { completedEvent.isVisibleOutputField(it) }.forEach { (key, output) ->
                    val promptKey = if (key == "output") "prompt" else key.removeSuffix("_output") + "_prompt"
                    displayedMessages.recordOutput(output, completedEvent.fields[promptKey])
                }
                val messageId = event.transcriptMessageId
                val index = if (messageId == null) -1 else visibleMessages.indexOfFirst { it.messageId == messageId && it.role == event.transcriptRole }
                val turnIndex = if (index >= 0) index else ordered.asSequence().filter { it.turnId == event.turnId }
                    .mapNotNull { sameTurn ->
                        val sameTurnId = sameTurn.transcriptMessageId ?: return@mapNotNull null
                        visibleMessages.indexOfFirst { it.messageId == sameTurnId && it.role == sameTurn.transcriptRole }.takeIf { it >= 0 }
                    }.firstOrNull()
                if (event.hasDisplayInformation()) renderDebugEvent(
                    event, turnIndex, turnParity[event.turnId] ?: 0, props.session.flags,
                    event.id !in collapsedEvents,
                    { collapsedEvents = if (event.id in collapsedEvents) collapsedEvents - event.id else collapsedEvents + event.id },
                    props.onShowTranscript,
                )
            }
        }
    }
}

private fun ChildrenBuilder.renderDebugEvent(event: DebugEvent, transcriptIndex: Int?, turnParity: Int, currentFlags: Map<String, Boolean>, expanded: Boolean, onToggle: () -> Unit, onShowTranscript: (Int) -> Unit) {
    ReactHTML.article {
        key = event.id
        id = "debug-event-${event.id}".unsafeCast<web.dom.ElementId>()
        className = ClassName("debug-event debug-event-turn-$turnParity")
        if (transcriptIndex != null) {
            asDynamic().tabIndex = 0
            asDynamic().onDoubleClick = { onShowTranscript(transcriptIndex) }
            asDynamic().onKeyDown = { keyEvent: dynamic ->
                if (keyEvent.target == keyEvent.currentTarget && (keyEvent.key == "Enter" || keyEvent.key == " ")) {
                    keyEvent.preventDefault()
                    onShowTranscript(transcriptIndex)
                }
            }
        }
        val prompts = event.fields.filter { (key, value) -> (key == "prompt" || key.endsWith("_prompt")) && value.hasChatContent() }
        val outputs = event.fields.filter { (key, value) -> event.isVisibleOutputField(key) && value.isNotBlank() }
        val histories = event.fields.filter { (key, value) -> (key == "history" || key.endsWith("_history")) && value.hasChatContent() }
        val deltas = flagDeltas(event)
        ReactHTML.div {
            className = ClassName("debug-event-topline")
            ReactHTML.h4 {
                ReactHTML.button {
                    className = ClassName("debug-event-toggle")
                    ariaExpanded = expanded
                    ariaControls = "debug-event-body-${event.id}".unsafeCast<web.dom.ElementId>()
                    onClick = { onToggle() }
                    onDoubleClick = { it.stopPropagation() }
                    +humanLabel(event.kind)
                }
            }
            ReactHTML.span { className = ClassName("debug-stage"); +humanLabel(event.stage) }
            ReactHTML.div {
                className = ClassName("debug-event-tools")
                onDoubleClick = { it.stopPropagation() }
                ReactHTML.details {
                    ReactHTML.summary { className = ClassName("debug-icon-button"); title = "All status variables"; ariaLabel = "All status variables"; +"⚑" }
                    ReactHTML.div { className = ClassName("debug-popover debug-event-popover"); renderFlagValues(currentFlags) }
                }
                if (event.metrics.hasInformation() || event.fields.any { (key, value) -> isTechnicalDetailField(key) && value.isNotBlank() }) ReactHTML.details {
                    ReactHTML.summary { className = ClassName("debug-icon-button"); title = "Event details"; ariaLabel = "Event details"; +"ⓘ" }
                    ReactHTML.div {
                        className = ClassName("debug-popover debug-event-popover")
                        ReactHTML.dl {
                            className = ClassName("debug-fields")
                            renderDiagnosticMetadata(event)
                            event.metrics?.let { renderDiagnosticMetrics(it) }
                        }
                    }
                }
            }
        }
        ReactHTML.div {
            id = "debug-event-body-${event.id}".unsafeCast<web.dom.ElementId>()
            className = ClassName("debug-event-body")
            asDynamic().hidden = !expanded
            val domainFields = event.fields.filter { (key, value) ->
                isDiagnosticMetadataField(key) && !isTechnicalDetailField(key) && key != "emittedSignals" && value.isNotBlank()
            }
            if (domainFields.isNotEmpty()) ReactHTML.dl {
                className = ClassName("debug-fields debug-domain-fields")
                domainFields.forEach { (key, value) -> field(humanLabel(key), value) }
            }
            prompts.forEach { (key, prompt) -> ReactHTML.section {
                ReactHTML.h5 { +(if (key == "prompt") "Prompt" else "${humanLabel(key.removeSuffix("_prompt"))} prompt") }
                renderChatHistory(prompt)
            } }
            outputs.forEach { (key, output) -> ReactHTML.section {
                ReactHTML.h5 { +(if (key == "output") "LLM output" else "${humanLabel(key.removeSuffix("_output"))} output") }
                ReactHTML.div { className = ClassName("debug-markdown"); MarkdownMessage { content = output } }
            } }
            histories.forEach { (key, value) -> ReactHTML.section {
                ReactHTML.h5 { +(if (key == "history") "Chat history" else humanLabel(key.removeSuffix("_history"))) }
                renderChatHistory(value)
            } }
            event.fields["emittedSignals"]?.takeIf(String::isNotBlank)?.let { signals -> ReactHTML.div {
                className = ClassName("debug-signal-name")
                +"Signal: $signals"
            } }
            if (deltas.isNotEmpty()) ReactHTML.div {
                className = ClassName("debug-flag-delta")
                deltas.forEach { (name, value) -> ReactHTML.span { +"$name: $value" } }
            }
        }
    }
}

private fun DebugEvent.isVisibleOutputField(key: String): Boolean =
    (key == "output" || key.endsWith("_output")) && key != "warmup_output" &&
        !(key == "output" && (kind == "warmup" || stage == "warmup"))

private fun flagDeltas(event: DebugEvent): Map<String, String> = buildMap {
    event.fields.filter { (key, value) -> key.startsWith("flag:") && value.isNotBlank() }.forEach { (key, value) -> put(key.removePrefix("flag:"), value) }
}

private fun ChildrenBuilder.renderFlagValues(flags: Map<String, Boolean>) {
    if (flags.isEmpty()) ReactHTML.p { +"No flags" } else ReactHTML.dl {
        className = ClassName("debug-fields")
        flags.toList().sortedBy { it.first }.forEach { (name, value) -> field(name, value.toString()) }
    }
}

private fun ChildrenBuilder.renderDiagnosticMetadata(event: DebugEvent) {
    event.fields.filter { (key, value) -> isTechnicalDetailField(key) && value.isNotBlank() }.forEach { (key, value) ->
        field(humanLabel(key), if (key == "source") humanLabel(value) else value)
    }
}

private fun DiagnosticMetrics?.hasInformation(): Boolean = this != null && this != DiagnosticMetrics()

// A replayed prompt can become empty after chat deduplication, including an open model cue.
private fun String.hasChatContent(): Boolean =
    isNotBlank() && (parseChatHistory(this)?.any { it.content.isNotBlank() } ?: true)

private fun DebugEvent.hasDisplayInformation(): Boolean {
    // The signal event already reports changed flags. Repeated assignments are not new state.
    if (kind == "flags" && stage == "applied") return flagDeltas(this).isNotEmpty()
    if (metrics.hasInformation()) return true
    if (fields.any { (key, value) ->
            when {
                isChatSnapshotField(key) -> value.hasChatContent()
                key == "output" || key.endsWith("_output") -> isVisibleOutputField(key) && value.isNotBlank()
                else -> (isDiagnosticMetadataField(key) || key.startsWith("flag:")) && value.isNotBlank()
            }
        }) return true
    // Lifecycle stages themselves carry information even when there is no payload.
    return kind in setOf("turn_status", "world_state", "warmup", "background_rerank") ||
        stage.endsWith("failed") || stage.endsWith("cancelled") || stage == "incomplete"
}

private fun isTechnicalDetailField(key: String): Boolean {
    if (!isDiagnosticMetadataField(key)) return false
    val normalized = key.lowercase().filter(Char::isLetter)
    return normalized.endsWith("id") || normalized.endsWith("tokens") ||
        normalized.endsWith("durationms") || normalized.endsWith("tokenspersecond") ||
        normalized in setOf("source", "operation", "epoch", "active", "pending") ||
        normalized.startsWith("activecovers") || normalized.startsWith("pendingcovers") ||
        normalized.endsWith("recapchars")
}

private fun isDiagnosticMetadataField(key: String): Boolean =
    key != "prompt" && !key.endsWith("_prompt") && key != "output" && !key.endsWith("_output") &&
        key != "history" && !key.endsWith("_history") && !key.startsWith("flag:") &&
        key.lowercase().filter { it.isLetter() } !in setOf(
            "playeraction", "gamemasteroutput", "generated", "generatedtext", "response", "responsetext",
        )

private data class ChatHistoryMessage(val role: String, val content: String)

private fun parseChatHistory(history: String): List<ChatHistoryMessage>? {
    val startToken = "<|turn>"
    val endToken = "<turn|>"
    val messages = mutableListOf<ChatHistoryMessage>()
    var cursor = 0
    while (cursor < history.length) {
        while (cursor < history.length && history[cursor].isWhitespace()) cursor++
        if (cursor == history.length) break
        if (!history.startsWith(startToken, cursor)) return null
        val roleStart = cursor + startToken.length
        val separator = history.indexOf('\n', roleStart)
        if (separator < 0) {
            if (history.substring(roleStart).trim() in setOf("model", "assistant")) break
            return null
        }
        val end = history.indexOf(endToken, separator + 1)
        val role = history.substring(roleStart, separator).trim()
        if (role.isEmpty()) return null
        if (end < 0) {
            // llama.cpp templates end with an empty, open assistant generation cue.
            if (role in setOf("model", "assistant") && history.substring(separator + 1).isBlank()) break
            return null
        }
        val content = history.substring(separator + 1, end).trim()
        messages += ChatHistoryMessage(role, content)
        cursor = end + endToken.length
    }
    return messages
}

private fun List<ChatHistoryMessage>.formatChatHistory(): String = joinToString("") { "<|turn>${it.role}\n${it.content}<turn|>" }

private fun isChatSnapshotField(key: String): Boolean =
    key == "prompt" || key.endsWith("_prompt") || key == "history" || key.endsWith("_history")

private fun ChatHistoryMessage.identity(): ChatHistoryMessage =
    if (role == "assistant") copy(role = "model") else this

/** Maximum occurrences in any snapshot: identical actions appended later remain visible. */
private class DisplayedChatMessages {
    private val seen = mutableMapOf<ChatHistoryMessage, Int>()

    fun onlyNewMessages(snapshot: String): String {
        val messages = parseChatHistory(snapshot) ?: return snapshot
        val occurrences = mutableMapOf<ChatHistoryMessage, Int>()
        val newMessages = messages.filter { message ->
            val identity = message.identity()
            val occurrence = (occurrences[identity] ?: 0) + 1
            occurrences[identity] = occurrence
            occurrence > (seen[identity] ?: 0)
        }
        occurrences.forEach { (message, count) -> seen[message] = maxOf(seen[message] ?: 0, count) }
        return newMessages.formatChatHistory()
    }

    fun recordOutput(output: String, prompt: String?) {
        if (output.isBlank()) return
        val message = ChatHistoryMessage("model", output.trim())
        val priorOccurrences = prompt?.let(::parseChatHistory)?.count { it.identity() == message } ?: 0
        seen[message] = maxOf(seen[message] ?: 0, priorOccurrences + 1)
    }
}

private fun ChildrenBuilder.renderChatHistory(history: String) {
    if (history.isBlank()) return
    val messages = parseChatHistory(history)
    if (messages == null) ReactHTML.pre { className = ClassName("debug-prompt"); +history }
    else messages.forEach { message ->
        ReactHTML.div {
            className = ClassName("debug-chat-turn")
            ReactHTML.strong { +humanLabel(message.role) }
            ReactHTML.div { className = ClassName("debug-markdown"); MarkdownMessage { content = message.content } }
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
