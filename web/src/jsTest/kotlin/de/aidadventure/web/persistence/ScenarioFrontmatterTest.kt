package de.aidadventure.web.persistence

import de.aidadventure.web.model.WorldEntity
import de.aidadventure.web.model.WorldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun parsesInitialWorldStateWithRevisionZero() {
        val parsed = ScenarioFrontmatter.parse(
            """---
            world-state:
              entities:
                mira: { kind: npc, name: Mira, summary: Waiting at the quay }
              facts:
                mira-promised-a-meeting: "true"
            ---
            # Harbor
            """.trimIndent(),
        )

        assertEquals(0, parsed.initialWorldState.revision)
        assertEquals(WorldEntity("npc", "Mira", "Waiting at the quay"), parsed.initialWorldState.entities["mira"])
        assertEquals(mapOf("mira-promised-a-meeting" to "true"), parsed.initialWorldState.facts)
    }

    @Test
    fun omittedWorldStateUsesEmptyRevisionZeroState() {
        assertEquals(WorldState(), ScenarioFrontmatter.parse("# Harbor").initialWorldState)
    }

    @Test
    fun rejectsMalformedWorldState() {
        listOf(
            "world-state: { revision: 1 }",
            "world-state: { unknown: value }",
            "world-state: { entities: { mira: { kind: npc, name: Mira } } }",
            "world-state: { entities: { '': { kind: npc, name: Mira, summary: Waiting } } }",
            "world-state: { facts: { '': true } }",
            "world-state: { facts: { tide: 2 } }",
            "world-state: { entities: [] }",
        ).forEach { worldState ->
            assertFailsWith<IllegalArgumentException> {
                ScenarioFrontmatter.parse("---\n$worldState\n---\n# Harbor")
            }
        }
    }

    private fun loadResource(path: String): String = XMLHttpRequest().apply {
        open("GET", "/$path", false)
        send()
    }.responseText
}
