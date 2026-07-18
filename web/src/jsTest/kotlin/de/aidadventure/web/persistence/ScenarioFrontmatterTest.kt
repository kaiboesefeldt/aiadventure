package de.aidadventure.web.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.xhr.XMLHttpRequest

class ScenarioFrontmatterTest {
    @Test
    fun bundledLighthouseExampleContainsFortyRuntimeFragments() {
        val markdown = loadResource("examples/test-game.md")
        val parsed = ScenarioFrontmatter.parse(markdown)

        assertEquals(40, parsed.runtimeFragments.size)
        assertFalse(parsed.promptMarkdown.startsWith("---"))
        assertTrue(parsed.promptMarkdown.startsWith("# Test Spiel: Der Leuchtturm von Nebelhafen"))
    }

    @Test
    fun parsesFragmentsAndStripsFrontmatter() {
        val parsed = ScenarioFrontmatter.parse(
            "---\nruntime-fragments:\n  lighthouse:\n    fragment: Mira remembers it\n---\n# Harbor",
        )

        assertEquals("# Harbor", parsed.promptMarkdown)
        assertEquals(
            listOf(RuntimeFragmentDefinition("lighthouse", "Mira remembers it")),
            parsed.runtimeFragments,
        )
    }

    @Test
    fun templateWithoutFrontmatterKeepsFullMarkdownAndHasNoFragments() {
        val parsed = ScenarioFrontmatter.parse("# Harbor")

        assertEquals("# Harbor", parsed.promptMarkdown)
        assertEquals(emptyList(), parsed.runtimeFragments)
    }

    @Test
    fun frontmatterWithoutRuntimeFragmentsIsStrippedAndHasNoFragments() {
        val parsed = ScenarioFrontmatter.parse("---\ntitle: Harbor\n---\n# Harbor")

        assertEquals("# Harbor", parsed.promptMarkdown)
        assertEquals(emptyList(), parsed.runtimeFragments)
    }

    private fun loadResource(path: String): String = XMLHttpRequest().apply {
        open("GET", "/$path", false)
        send()
    }.responseText
}
