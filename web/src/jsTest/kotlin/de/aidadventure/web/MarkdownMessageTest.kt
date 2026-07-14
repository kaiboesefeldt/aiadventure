package de.aidadventure.web

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import react.create
import react.dom.client.createRoot
import react.dom.flushSync
import web.dom.document

class MarkdownMessageTest {
    @Test
    fun markdownRenderingCreatesGfmElements() {
        val container = renderMarkdown("**bold**\n\n| one | two |\n| --- | --- |\n| 1 | 2 |")

        assertNotNull(container.querySelector("strong"))
        assertNotNull(container.querySelector("table"))
    }

    @Test
    fun markdownRenderingDoesNotCreateRawHtmlElements() {
        val container = renderMarkdown("<img src=x onerror=alert(1)>")

        assertNull(container.querySelector("img"))
    }

    private fun renderMarkdown(content: String) = document.createElement("div").also { container ->
        flushSync {
            createRoot(container).render(
                MarkdownMessage.create {
                    this.content = content
                },
            )
        }
    }
}
