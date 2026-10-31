package de.aidadventure.web

import de.aidadventure.web.model.DebugEvent
import de.aidadventure.web.model.GameSession
import de.aidadventure.web.model.Scenario
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
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

class DebugPanelTest {
    @Test fun `partial text effect does not hide legacy flag changes or positive checks`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        try {
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
                session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
                events = listOf(
                    DebugEvent("text", "session", "turn", 0, 1, "text_emitted", "emitted", fields = mapOf("text" to "Secret", "sourceType" to "signal", "sourceName" to "arrive", "operationId" to "one")),
                    DebugEvent("legacy-flag", "session", "turn", 1, 1, "signal", "emitted", fields = mapOf("flag:door" to "true", "operationId" to "one")),
                    DebugEvent("positive-check", "session", "turn", 2, 1, "signal_check", "completed", fields = mapOf("signal" to "arrive", "result" to "triggered", "operationId" to "one")),
                    DebugEvent("negative-background", "session", "turn", 3, 1, "background_rerank", "prior_completed", fields = mapOf("effectsReported" to "true", "signal_missing_result" to "not_triggered", "signal_invalid_result" to "invalid")),
                )
                onClose = {}; onClear = {}; onShowTranscript = {}
            }) }
            assertNotNull(container.querySelector("#debug-event-legacy-flag"))
            assertNotNull(container.querySelector("#debug-event-positive-check"))
            assertEquals(true, container.querySelector("#debug-event-negative-background .debug-event-body")?.textContent?.contains("not_triggered"))
            assertEquals(true, container.querySelector("#debug-event-negative-background .debug-event-body")?.textContent?.contains("invalid"))
        } finally { flushSync { root.unmount() }; container.remove() }
    }
    @Test fun `effects show full readable content sources and repeated writes as separate collapsible cards`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        try {
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
                session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
                events = listOf(
                    DebugEvent("text", "session", "turn", 0, 1, "text_emitted", "emitted", fields = mapOf("text" to "**The entire secret**", "sourceType" to "signal", "sourceName" to "arrive")),
                    DebugEvent("flag", "session", "turn", 1, 1, "flag_set", "applied", fields = mapOf("flag" to "door", "previousValue" to "true", "value" to "true", "signal" to "arrive")),
                    DebugEvent("signal", "session", "turn", 2, 1, "signal_detected", "detected", fields = mapOf("signal" to "arrive", "detection" to "model")),
                    DebugEvent("bulk", "session", "turn", 3, 1, "signal", "emitted", fields = mapOf("flag:door" to "true")),
                    DebugEvent("fragment-bulk", "session", "turn", 4, 1, "fragment", "selected", fields = mapOf("fragment" to "Combined text", "effectsReported" to "true")),
                )
                onClose = {}; onClear = {}; onShowTranscript = {}
            }) }
            assertEquals(3, container.querySelectorAll(".debug-event").length)
            assertEquals(0, container.querySelectorAll(".debug-stage").length)
            assertEquals("The entire secret", container.querySelector("#debug-event-text strong")?.textContent)
            assertEquals(true, container.querySelector("#debug-event-text .debug-event-body")?.textContent?.contains("Signal: arrive"))
            assertEquals(true, container.querySelector("#debug-event-flag .debug-event-body")?.textContent?.contains("door: true → true"))
            assertEquals(true, container.querySelector("#debug-event-signal .debug-event-body")?.textContent?.contains("Model"))
            val toggle = assertNotNull(container.querySelector("#debug-event-text .debug-event-toggle"))
            assertEquals("Text emitted", toggle.textContent)
            flushSync { toggle.asDynamic().click() }
            assertEquals(true, container.querySelector("#debug-event-text .debug-event-body")?.asDynamic()?.hidden as Boolean)
        } finally { flushSync { root.unmount() }; container.remove() }
    }

    @Test
    fun `each card title toggles its body without triggering transcript navigation`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        var navigation = -1
        try {
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
                session = GameSession("session", "scenario", "Harbor", messages = listOf(
                    de.aidadventure.web.model.GameMessage("player", "Look", 1, messageId = "player-1")
                ), createdAt = 1, updatedAt = 1)
                events = listOf(
                    DebugEvent("content", "session", "turn", 0, 1, "fragment", "selected", fields = mapOf("fragment" to "A locked gate"), transcriptRole = "player", transcriptMessageId = "player-1"),
                    DebugEvent("status", "session", "turn", 1, 2, "turn_status", "completed", fields = mapOf("result" to "accepted")),
                )
                onClose = {}
                onClear = {}
                onShowTranscript = { navigation = it }
            }) }
            for (id in listOf("content", "status")) {
                val toggle = assertNotNull(container.querySelector("#debug-event-$id .debug-event-toggle"))
                val bodyId = assertNotNull(toggle.getAttribute("aria-controls"))
                val body = assertNotNull(document.getElementById(bodyId.unsafeCast<web.dom.ElementId>()))
                assertEquals("BUTTON", toggle.tagName)
                assertEquals("true", toggle.getAttribute("aria-expanded"))
                assertEquals(false, body.asDynamic().hidden as Boolean)
                flushSync { toggle.asDynamic().click() }
                assertEquals("false", toggle.getAttribute("aria-expanded"))
                assertEquals(true, body.asDynamic().hidden as Boolean)
                toggle.asDynamic().dispatchEvent(js("new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })"))
                assertEquals(-1, navigation, "Title keyboard activation must not navigate")
                flushSync { toggle.asDynamic().click() }
                assertEquals("true", toggle.getAttribute("aria-expanded"))
                assertEquals(false, body.asDynamic().hidden as Boolean)
                toggle.asDynamic().dispatchEvent(js("new MouseEvent('dblclick', { bubbles: true })"))
                assertEquals(-1, navigation, "Title double click must not navigate")
            }
            container.querySelector("#debug-event-content .debug-event-body")?.asDynamic()?.dispatchEvent(js("new MouseEvent('dblclick', { bubbles: true })"))
            assertEquals(0, navigation, "Card content keeps its transcript cross-link")
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @Test
    fun `empty and repeated diagnostics are hidden while payloads and real flag changes remain`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val history = "<|turn>user\nLook<turn|>"
        fun event(id: String, kind: String, stage: String, fields: Map<String, String> = emptyMap()) =
            DebugEvent(id, "session", "turn", 0, 1, kind, stage, fields = fields)
        try {
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
                session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
                events = listOf(
                    event("initial", "llm_call", "completed", mapOf("history" to history)),
                    event("duplicate", "llm_call", "completed", mapOf("history" to history, "output" to " ")),
                    event("cue", "llm_call", "completed", mapOf("prompt" to "<|turn>model\n")),
                    event("empty-fragment", "fragment", "selected", mapOf("fragment" to "", "emittedSignals" to "")),
                    event("static-signals", "turn_state", "initial", mapOf("signals" to "unlock")),
                    event("emitted-signals", "signal", "emitted", mapOf("emittedSignals" to "unlock")),
                    event("flags", "flags", "applied", mapOf("emittedSignals" to "unlock", "appliedAssignments" to "door=true")),
                    event("blank-delta", "flags", "applied", mapOf("flag:door" to " ")),
                    event("empty-metrics", "llm_call", "completed").copy(metrics = de.aidadventure.web.model.DiagnosticMetrics()),
                    event("zero-metrics", "llama_completion", "completed").copy(metrics = de.aidadventure.web.model.DiagnosticMetrics(cacheTokens = 0)),
                    event("delta", "flags", "applied", mapOf("flag:door" to "true")),
                    event("completed", "turn_status", "completed", mapOf("result" to "accepted")),
                    event("cancelled", "world_state", "cancelled", mapOf("reason" to "Newer turn")),
                    event("failed", "warmup", "failed", mapOf("code" to "GatewayUnavailable")),
                )
                onClose = {}
                onClear = {}
            }) }
            for (id in listOf("duplicate", "cue", "empty-fragment", "flags", "blank-delta", "empty-metrics", "static-signals", "emitted-signals")) {
                assertEquals(null, container.querySelector("#debug-event-$id"), "$id has no additional information")
            }
            for (id in listOf("initial", "delta", "completed", "cancelled", "failed", "zero-metrics")) {
                assertNotNull(container.querySelector("#debug-event-$id"), "$id remains meaningful")
            }
            assertEquals(true, container.querySelector("#debug-event-delta .debug-flag-delta")?.textContent?.contains("door: true"))
            assertEquals(1, container.querySelectorAll(".debug-event section").length)
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @Test
    fun `stage only cards disappear while payload errors and completion metrics remain`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        fun event(id: String, kind: String, stage: String, fields: Map<String, String> = emptyMap()) =
            DebugEvent(id, "session", "turn", 0, 1, kind, stage, fields = fields)
        try {
            val metrics = de.aidadventure.web.model.DiagnosticMetrics(cacheTokens = 15, evaluatedTokens = 6)
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
                session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
                events = listOf(
                    event("status", "turn_status", "completed"),
                    event("warmup", "warmup", "completed"),
                    event("background", "background_rerank", "scheduled"),
                    event("world-state", "world_state", "cancelled"),
                    event("failure", "generation", "failed"),
                    event("incomplete", "generation", "incomplete"),
                    event("aggregate", "turn_metrics", "completed").copy(metrics = metrics),
                    event("completion", "llama_completion", "completed").copy(metrics = metrics),
                    event("error", "turn_status", "failed", mapOf("code" to "GatewayUnavailable")),
                )
                onClose = {}
                onClear = {}
            }) }
            for (id in listOf("status", "warmup", "background", "world-state", "failure", "incomplete", "aggregate")) {
                assertEquals(null, container.querySelector("#debug-event-$id"), "$id has no additional information")
            }
            val completion = assertNotNull(container.querySelector("#debug-event-completion"))
            assertEquals(true, completion.textContent?.contains("15"), "Completion cache metrics remain accessible")
            assertEquals(true, completion.textContent?.contains("6"), "Completion evaluation metrics remain accessible")
            assertEquals(true, container.querySelector("#debug-event-error")?.textContent?.contains("GatewayUnavailable"))
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @Test
    fun `technical only warmups and empty compression disappear while useful bodies and completion metrics remain`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        fun event(id: String, kind: String, stage: String, fields: Map<String, String> = emptyMap()) =
            DebugEvent(id, "session", "turn", 0, 1, kind, stage, fields = fields)
        val metrics = de.aidadventure.web.model.DiagnosticMetrics(cacheTokens = 15, evaluatedTokens = 6)
        val prompt = "<|turn>user\nExplore the harbor<turn|><|turn>model\n"
        try {
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
                session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
                events = listOf(
                    event("warmup-ids", "warmup", "completed", mapOf("operationId" to "op-1", "source" to "prior_turn")),
                    event("warmup-metrics", "warmup", "completed").copy(metrics = metrics),
                    event("warmup-prompt", "llm_call", "warmup", mapOf("prompt" to prompt, "output" to "Suppressed warmup output")).copy(metrics = metrics),
                    event("warmup-replay", "llm_call", "warmup", mapOf("prompt" to prompt)).copy(metrics = metrics),
                    event("compression-empty", "context_compression", "completed", mapOf("epoch" to "0", "active" to "false", "pending" to "false")),
                    event("compression-zero", "context_compression", "completed", mapOf("activeRecapChars" to "0", "activeCoversThroughTurn" to "0")),
                    event("compression-real", "context_compression", "completed", mapOf(
                        "epoch" to "2", "active" to "true", "pending" to "true",
                        "activeCoversThroughTurn" to "4", "activeCoversThroughEntry" to "8", "activeRecapChars" to "320",
                        "pendingCoversThroughTurn" to "6", "pendingCoversThroughEntry" to "12", "pendingRecapChars" to "480",
                    )),
                    event("compression-recap", "context_compression", "completed", mapOf("recap" to "The captain has left the harbor.")),
                    event("warmup-error", "warmup", "failed", mapOf("failureCategory" to "NoIdleLlamaCppSlotException")),
                    event("compression-error", "context_compression", "failed", mapOf("reason" to "Recap exceeds the context limit")),
                    event("completion", "llama_completion", "completed").copy(metrics = metrics),
                )
                onClose = {}
                onClear = {}
            }) }
            for (id in listOf("warmup-ids", "warmup-metrics", "warmup-replay", "compression-empty", "compression-zero")) {
                assertEquals(null, container.querySelector("#debug-event-$id"), "$id has no visible body information")
            }
            for ((id, content) in mapOf(
                "warmup-prompt" to "Explore the harbor", "compression-recap" to "The captain has left the harbor.",
                "warmup-error" to "NoIdleLlamaCppSlotException", "compression-error" to "Recap exceeds the context limit",
            )) {
                val body = assertNotNull(container.querySelector("#debug-event-$id .debug-event-body"))
                assertEquals(true, body.textContent?.contains(content), "$id preserves useful content")
            }
            assertEquals(false, container.textContent?.contains("Suppressed warmup output"))
            val compression = assertNotNull(container.querySelector("#debug-event-compression-real .debug-event-body"))
            val values = compression.querySelectorAll(".debug-domain-fields dd")
            assertEquals(listOf("true", "true", "4", "8", "320", "6", "12", "480"),
                List(values.length) { index -> values.item(index)?.textContent },
                "All real compression checkpoint values remain inline")
            val completionDetails = assertNotNull(container.querySelector("#debug-event-completion summary[title='Event details']"))
            completionDetails.asDynamic().click()
            assertEquals(true, container.querySelector("#debug-event-completion details[open]")?.textContent?.contains("15"))
            assertEquals(true, container.querySelector("#debug-event-completion details[open]")?.textContent?.contains("6"))
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @Test
    fun `domain state and fragment decisions are inline while technical details stay in popovers`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        try {
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
                session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
                events = listOf(DebugEvent("domain", "session", "turn", 0, 1, "turn_state", "initial", fields = mapOf(
                    "location" to "Harbor", "narrativeFocus" to "Find the captain", "signals" to "unlock",
                    "fragment" to "A locked gate", "matchText" to "gate", "result" to "accepted", "flag:door" to "false",
                    "failureCategory" to "GatewayUnavailable", "operationId" to "op-1", "warmup_cache_tokens" to "42",
                )))
                onClose = {}
                onClear = {}
            }) }
            val body = assertNotNull(container.querySelector(".debug-event-body"))
            for (value in listOf("Harbor", "Find the captain", "A locked gate", "gate", "accepted", "door: false", "GatewayUnavailable")) {
                assertEquals(true, body.textContent?.contains(value), "$value must be readable without a popover")
            }
            assertEquals(false, container.textContent?.contains("unlock"), "Static signal names must be absent from cards and popovers")
            assertEquals(false, body.textContent?.contains("op-1"))
            assertEquals(false, body.textContent?.contains("42"))
            assertEquals(true, container.querySelector(".debug-event-tools details:last-child .debug-event-popover")?.textContent?.contains("op-1"))
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }


    @Test
    fun `header tools and event details are available in compact popovers`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        try {
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "---\ntitle: Harbor\n---\n# Prompt", 1)
                session = GameSession("session", "scenario", "Harbor", flags = mapOf("door" to true), messages = emptyList(), createdAt = 1, updatedAt = 1)
                events = listOf(DebugEvent("event", "session", "turn", 0, 1, "generation", "completed", fields = mapOf("output" to "**Done**", "operationId" to "op-1"), metrics = de.aidadventure.web.model.DiagnosticMetrics(promptTokens = 12)))
                onClose = {}
                onClear = {}
            }) }
            val headerButtons = container.querySelectorAll(".debug-panel-header button[title]")
            assertEquals(3, headerButtons.length)
            repeat(headerButtons.length) { index ->
                val button = assertNotNull(headerButtons.item(index))
                val controlledId = assertNotNull(button.getAttribute("aria-controls"))
                val controlledPopover = assertNotNull(document.getElementById(controlledId.unsafeCast<web.dom.ElementId>()))
                assertEquals("false", button.getAttribute("aria-expanded"))
                assertEquals(true, controlledPopover.asDynamic().hidden as Boolean)
            }
            val flagsButton = assertNotNull(container.querySelector("button[title='Current flags']"))
            flushSync { flagsButton.asDynamic().click() }
            assertEquals("true", flagsButton.getAttribute("aria-expanded"))
            assertEquals(false, assertNotNull(document.getElementById("debug-header-popover-flags".unsafeCast<web.dom.ElementId>())).asDynamic().hidden as Boolean)
            flushSync { flagsButton.asDynamic().click() }
            assertEquals("false", flagsButton.getAttribute("aria-expanded"))
            assertEquals(2, container.querySelectorAll(".debug-event .debug-event-tools summary[title]").length)
            assertEquals(true, container.querySelector(".debug-markdown strong")?.textContent == "Done")
            assertEquals(false, container.querySelector(".debug-event-tools details[open]")?.textContent?.contains("Prompt tokens") == true)
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @Test
    fun `signal flag changes and structured chat history remain without signal listings`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        try {
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
                session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
                events = listOf(DebugEvent("signal", "session", "turn", 0, 1, "signal", "emitted", fields = mapOf("emittedSignals" to "unlock", "flag:doorOpen" to "true", "history" to "<|turn>user\nGo north<turn|><|turn>assistant\nYou arrive.<turn|>")))
                onClose = {}
                onClear = {}
            }) }
            assertEquals(true, container.querySelector(".debug-flag-delta")?.textContent?.contains("doorOpen") == true)
            assertEquals(false, container.textContent?.contains("unlock"), "Emitted signal names must be absent from cards and popovers")
            assertEquals("Flag changes", container.querySelector("#debug-event-signal h4")?.textContent)
            assertEquals(2, container.querySelectorAll(".debug-chat-turn").length)
            assertEquals(false, container.textContent?.contains("<|turn>") == true)
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @Test
    fun `history snapshots show only messages appended since the previous snapshot`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        try {
            val initial = "<|turn>system\nPrompt<turn|><|turn>model\nOpening<turn|>"
            val later = "$initial<|turn>user\nGo north<turn|><|turn>model\nYou arrive.<turn|>"
            val revised = "$initial<|turn>user\nGo north<turn|><|turn>model\nThe gate is locked.<turn|>"
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
                session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
                events = listOf(
                    DebugEvent("initial", "session", "turn-1", 0, 1, "generation", "completed", fields = mapOf("history" to initial)),
                    DebugEvent("next", "session", "turn-2", 1, 2, "generation", "completed", fields = mapOf("history" to later)),
                    DebugEvent("repeat", "session", "turn-2", 1, 3, "generation", "completed", fields = mapOf("history" to later)),
                    DebugEvent("revised", "session", "turn-2", 1, 4, "generation", "completed", fields = mapOf("history" to revised)),
                )
                onClose = {}
                onClear = {}
            }) }
            val turns = container.querySelectorAll(".debug-chat-turn")
            assertEquals(5, turns.length)
            assertEquals(true, turns.item(0)?.textContent?.contains("System"))
            assertEquals(true, turns.item(1)?.textContent?.contains("Opening"))
            assertEquals(true, turns.item(2)?.textContent?.contains("Go north"))
            assertEquals(true, turns.item(3)?.textContent?.contains("You arrive"))
            assertEquals(2, container.querySelectorAll("#debug-event-next .debug-chat-turn").length)
            assertEquals(0, container.querySelectorAll("#debug-event-repeat .debug-chat-turn").length)
            assertEquals(0, container.querySelectorAll("#debug-event-repeat .debug-prompt").length)
            assertEquals(1, container.querySelectorAll("#debug-event-revised .debug-chat-turn").length)
            assertEquals(true, container.querySelector("#debug-event-revised")?.textContent?.contains("The gate is locked"))
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @Test
    fun `actual prompt fields show only new occurrences across calls and changed world state`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        fun message(role: String, content: String) = "<|turn>$role\n$content<turn|>\n"
        fun prompt(vararg messages: String) = messages.joinToString("") + "<|turn>model\n"
        fun event(id: String, fields: Map<String, String>) = DebugEvent(id, "session", id, 0, 1, "llm_call", "completed", fields = fields)
        val system = message("system", "# Harbor rules")
        val initial = message("user", "INITIAL SITUATION: At the harbor")
        val state = message("user", "AUTHORITATIVE CURRENT LOCATION: Kai")
        val opening = message("model", "The fog rolls in.")
        val action = message("user", "Look around")
        try {
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
                session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
                events = listOf(
                    event("warmup", mapOf("prompt" to prompt(system, state, initial))),
                    event("introduction", mapOf("prompt" to prompt(system, state, initial))).copy(kind = "llama_completion"),
                    event("combined", mapOf("prompt" to prompt(system, state, initial, opening, message("user", "Update world state")), "output" to "At the harbor")),
                    event("analysis", mapOf("world_state_prompt" to prompt(system, state, initial, opening, message("user", "Update world state")))),
                    event("action", mapOf("prompt" to prompt(system, state, initial, opening, action), "output" to "You see a boat.")),
                    event("changed", mapOf("rerank_prompt" to prompt(system, message("user", "AUTHORITATIVE CURRENT LOCATION: Steg"), initial, opening, action, message("assistant", "You see a boat."), action))),
                    event("duplicate", mapOf("prompt" to prompt(system, state, initial, opening, action).removeSuffix("\n"))),
                    event("cue", mapOf("prompt" to "<|turn>assistant")),
                    event("plain", mapOf("prompt" to "Unstructured diagnostic prompt: signals: unlock", "output" to "Signal unlock triggered.")),
                )
                onClose = {}
                onClear = {}
            }) }
            assertEquals(3, container.querySelectorAll("#debug-event-warmup .debug-chat-turn").length)
            assertEquals(0, container.querySelectorAll("#debug-event-introduction .debug-chat-turn").length)
            assertEquals(2, container.querySelectorAll("#debug-event-combined .debug-chat-turn").length)
            assertEquals(true, container.querySelector("#debug-event-combined")?.textContent?.contains("The fog rolls in"))
            assertEquals(0, container.querySelectorAll("#debug-event-analysis .debug-chat-turn").length)
            assertEquals(1, container.querySelectorAll("#debug-event-action .debug-chat-turn").length)
            assertEquals("UserLook around", container.querySelector("#debug-event-action .debug-chat-turn")?.textContent)
            assertEquals(2, container.querySelectorAll("#debug-event-changed .debug-chat-turn").length)
            assertEquals(true, container.querySelector("#debug-event-changed")?.textContent?.contains("Steg"))
            assertEquals(true, container.querySelector("#debug-event-changed")?.textContent?.contains("Look around"))
            assertEquals(false, container.querySelector("#debug-event-changed")?.textContent?.contains("You see a boat"))
            assertEquals(0, container.querySelectorAll("#debug-event-duplicate .debug-chat-turn").length)
            assertEquals(0, container.querySelectorAll("#debug-event-duplicate .debug-prompt").length)
            assertEquals(0, container.querySelectorAll("#debug-event-cue .debug-chat-turn, #debug-event-cue .debug-prompt").length)
            assertEquals(false, container.textContent?.contains("<|turn>"))
            assertEquals("Unstructured diagnostic prompt: signals: unlock", container.querySelector("#debug-event-plain .debug-prompt")?.textContent)
            assertEquals("Signal unlock triggered.", container.querySelector("#debug-event-plain .debug-markdown")?.textContent)
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @Test
    fun `repository order survives a reset turn counter after reload`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        try {
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
                session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
                events = listOf(
                    DebugEvent("old", "session", "before-reload", 0, 10, "turn_status", "old turn", fields = mapOf("result" to "accepted"), turnOrder = 12),
                    DebugEvent("new", "session", "after-reload", 0, 20, "turn_status", "new turn", fields = mapOf("result" to "accepted"), turnOrder = 0),
                )
                onClose = {}
                onClear = {}
            }) }
            val cards = container.querySelectorAll(".debug-event")
            assertEquals(2, cards.length)
            assertEquals(true, cards.item(0)?.textContent?.contains("Old turn"))
            assertEquals(true, cards.item(1)?.textContent?.contains("New turn"))
            assertEquals(true, cards.item(0)?.getAttribute("class")?.contains("debug-event-turn-0"))
            assertEquals(true, cards.item(1)?.getAttribute("class")?.contains("debug-event-turn-1"))
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @Test
    fun `frontmatter hides signal declarations and fragment emissions while keeping flags and fragment text`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        try {
            // Signal declarations accept both mappings and lists of named signals.
            for (declarations in listOf(
                "  unlock:\n    triggers:\n      - set-flag: {door: true}",
                "  - unlock:\n      triggers:\n        - set-flag: {door: true}",
            )) {
                flushSync { root.render(DebugPanel.create {
                    scenario = Scenario("scenario", "scenario.md", "---\nflags: {door: false}\nsignals:\n$declarations\nruntime-fragments:\n  gate:\n    signal: unlock\n    emit-text: A locked gate\ncustom:\n  signal: Weather observation\n---\n# Harbor", 1)
                    session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
                    events = emptyList()
                    onClose = {}
                    onClear = {}
                }) }
                val button = assertNotNull(container.querySelector("button[title='Frontmatter']"))
                if (button.getAttribute("aria-expanded") != "true") flushSync { button.asDynamic().click() }
                val text = assertNotNull(container.querySelector("#debug-header-popover-frontmatter")).textContent.orEmpty()
                assertEquals(false, text.contains("unlock"), text)
                assertEquals(false, text.contains("signals"), text)
                assertEquals(false, text.contains("triggers"), text)
                for (value in listOf("flags", "door", "false", "runtime-fragments", "gate", "emit-text", "A locked gate", "Weather observation")) {
                    assertEquals(true, text.contains(value), "$value must remain in frontmatter: $text")
                }
            }
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @Test
    fun `frontmatter tree handles a cyclic YAML alias`() {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        try {
            flushSync { root.render(DebugPanel.create {
                scenario = Scenario("scenario", "scenario.md", "---\ncustom: &loop\n  child: *loop\n---\n# Harbor", 1)
                session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
                events = emptyList()
                onClose = {}
                onClear = {}
            }) }
            flushSync { container.querySelector("button[title='Frontmatter']")?.asDynamic()?.click() }
            assertEquals(true, container.textContent?.contains("Circular reference"))
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `new events follow the bottom only while the reader is at the bottom`() = GlobalScope.promise<Unit> {
        val container = document.createElement("div")
        document.body.appendChild(container)
        val root = createRoot(container)
        val scenario = Scenario("scenario", "scenario.md", "# Harbor", 1)
        val session = GameSession("session", "scenario", "Harbor", messages = emptyList(), createdAt = 1, updatedAt = 1)
        val first = DebugEvent("first", "session", "turn", 0, 1, "turn_status", "completed", fields = mapOf("result" to "accepted"))
        val second = first.copy(id = "second", sequence = 1)
        val third = first.copy(id = "third", sequence = 2)
        fun render(events: List<DebugEvent>) = flushSync {
            root.render(DebugPanel.create {
                this.scenario = scenario
                this.session = session
                this.events = events
                onClose = {}
                onClear = {}
            })
        }
        try {
            render(listOf(first))
            val scroller = assertNotNull(container.querySelector("#debug-event-list"))
            js("Object.defineProperty(scroller, 'scrollHeight', { configurable: true, value: 1000 })")
            js("Object.defineProperty(scroller, 'clientHeight', { configurable: true, value: 100 })")
            js("Object.defineProperty(scroller, 'scrollTop', { configurable: true, writable: true, value: 0 })")
            scroller.asDynamic().scrollTop = 250
            scroller.asDynamic().dispatchEvent(js("new Event('scroll', { bubbles: true })"))
            nextTick()
            render(listOf(first, second))
            nextTick()
            assertEquals(250, scroller.asDynamic().scrollTop)

            scroller.asDynamic().scrollTop = 900
            scroller.asDynamic().dispatchEvent(js("new Event('scroll', { bubbles: true })"))
            nextTick()
            render(listOf(first, second, third))
            nextTick()
            assertEquals(1000, scroller.asDynamic().scrollTop)
        } finally {
            flushSync { root.unmount() }
            container.remove()
        }
    }

    private suspend fun nextTick() {
        Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 0) }.await()
    }
}
