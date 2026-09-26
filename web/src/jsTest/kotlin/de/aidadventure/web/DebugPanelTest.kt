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
                    DebugEvent("old", "session", "before-reload", 0, 10, "turn_status", "old turn", turnOrder = 12),
                    DebugEvent("new", "session", "after-reload", 0, 20, "turn_status", "new turn", turnOrder = 0),
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
        val first = DebugEvent("first", "session", "turn", 0, 1, "turn_status", "completed")
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
