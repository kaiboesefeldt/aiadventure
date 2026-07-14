# Markdown Transcript Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Render both player and game-master transcript messages as safe GitHub-Flavored Markdown.

**Architecture:** The browser module adds ESM-only react-markdown with remark-gfm. A Kotlin/JS wrapper and MarkdownMessage component create React elements directly with skipHtml = true, so untrusted user and LLM text never becomes active HTML. PlayView replaces its plain-text paragraph with the component, and CSS styles generated semantic elements.

**Tech Stack:** Kotlin/JS, React 19 Kotlin wrappers, react-markdown 10.1.0, remark-gfm 4.0.1, Kotlin browser tests, Gradle.

## Global Constraints

- Render player and LLM messages through the same Markdown component.
- Keep raw HTML inert with skipHtml = true; do not add rehype-raw, dangerouslySetInnerHTML, or a custom HTML parser.
- Preserve browser-only persistence; do not modify the API, server, prompts, or stored message schema.
- Re-render accumulated text on every stream update; incomplete Markdown may be temporarily unformatted.

---

### Task 1: Render safe GFM in every transcript message

**Files:**

- Modify: web/build.gradle.kts:28-39
- Create: web/src/jsMain/kotlin/de/aidadventure/web/MarkdownMessage.kt
- Modify: web/src/jsMain/kotlin/de/aidadventure/web/Main.kt:378-393
- Modify: web/src/jsMain/resources/app.css:57-62
- Create: web/src/jsTest/kotlin/de/aidadventure/web/MarkdownMessageTest.kt

**Interfaces:**

- Consumes: content: String from every player and game-master transcript message.
- Produces: internal val MarkdownMessage: FC<MarkdownMessageProps>, rendering safe GFM React elements.

- [ ] **Step 1: Write failing browser-DOM tests**

Create web/src/jsTest/kotlin/de/aidadventure/web/MarkdownMessageTest.kt:

~~~kotlin
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
~~~

- [ ] **Step 2: Run the tests to verify they fail**

Run: ./gradlew :web:browserTest --tests de.aidadventure.web.MarkdownMessageTest

Expected: test compilation fails because MarkdownMessage does not yet exist.

- [ ] **Step 3: Add dependencies, the wrapper, integration, and styles**

In main.dependencies in web/build.gradle.kts, add:

~~~kotlin
implementation(npm("react-markdown", "10.1.0"))
implementation(npm("remark-gfm", "4.0.1"))
~~~

Create web/src/jsMain/kotlin/de/aidadventure/web/MarkdownMessage.kt:

~~~kotlin
package de.aidadventure.web

import react.FC
import react.Props

@JsModule("react-markdown")
@JsName("default")
external val ReactMarkdown: FC<ReactMarkdownProps>

@JsModule("remark-gfm")
@JsName("default")
external val remarkGfm: dynamic

external interface ReactMarkdownProps : Props {
    var children: String
    var remarkPlugins: Array<dynamic>
    var skipHtml: Boolean
}

external interface MarkdownMessageProps : Props {
    var content: String
}

internal val MarkdownMessage = FC<MarkdownMessageProps> { props ->
    ReactMarkdown {
        children = props.content
        remarkPlugins = arrayOf(remarkGfm)
        skipHtml = true
    }
}
~~~

In web/src/jsMain/kotlin/de/aidadventure/web/Main.kt, replace:

~~~kotlin
ReactHTML.p {
    className = ClassName("message-content")
    +message.content
}
~~~

with:

~~~kotlin
ReactHTML.div {
    className = ClassName("message-content")
    MarkdownMessage {
        content = message.content
    }
}
~~~

Replace the current .message-content rules in web/src/jsMain/resources/app.css with:

~~~css
.message-content { line-height: 1.5; overflow-wrap: anywhere; }
.message-content > :first-child { margin-top: 0; }
.message-content > :last-child { margin-bottom: 0; }
.message-content p, .message-content ul, .message-content ol, .message-content blockquote, .message-content pre, .message-content table { margin: 0.7rem 0; }
.message-content ul, .message-content ol { padding-left: 1.4rem; }
.message-content blockquote { margin-left: 0; padding-left: 0.8rem; border-left: 3px solid var(--rule); color: var(--muted-ink); }
.message-content :not(pre) > code { padding: 0.1rem 0.3rem; border-radius: 4px; background: var(--accent-wash); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.message-content pre { overflow-x: auto; padding: 0.75rem; border-radius: 6px; background: #1f2928; color: #f6f2e8; }
.message-content pre code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.message-content table { display: block; max-width: 100%; overflow-x: auto; border-collapse: collapse; }
.message-content th, .message-content td { padding: 0.35rem 0.55rem; border: 1px solid var(--rule); text-align: left; }
.message-content a { color: var(--accent-hover); }
.message--game-master .message-content { line-height: 1.65; }
~~~

- [ ] **Step 4: Run focused and full verification**

Run: ./gradlew :web:browserTest --tests de.aidadventure.web.MarkdownMessageTest

Expected: Gradle exits with code 0; strong and table are present, and untrusted img markup did not create an image element.

Run: ./gradlew check

Expected: Gradle exits with code 0; server and browser checks pass.

- [ ] **Step 5: Perform the browser smoke test**

Start the application with ./gradlew :server:run, open http://localhost:8080, and submit a player action containing bold Markdown, a list, and a Markdown link. Confirm the player message renders each element. Ask the model for a table and fenced code block; confirm both render while tokens stream and after the done event. Finally submit <img src=x onerror=alert(1)> and confirm no image appears.

- [ ] **Step 6: Commit the implementation**

~~~bash
git add web/build.gradle.kts web/src/jsMain/kotlin/de/aidadventure/web/MarkdownMessage.kt web/src/jsMain/kotlin/de/aidadventure/web/Main.kt web/src/jsMain/resources/app.css web/src/jsTest/kotlin/de/aidadventure/web/MarkdownMessageTest.kt
git commit -m "feat(web): render transcript as Markdown"
~~~
