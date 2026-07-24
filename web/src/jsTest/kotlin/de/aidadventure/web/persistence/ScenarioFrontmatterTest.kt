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
    fun parsesInitialWorldState() {
        val parsed = ScenarioFrontmatter.parse(
            """
            ---
            world-state:
              entities:
                mira:
                  kind: npc
                  name: Mira
                  summary: Waiting at the quay
              facts:
                mira-promised-a-meeting: "true"
            ---
            # Harbor
            """.trimIndent(),
        )

        assertEquals(
            WorldState(
                entities = mapOf("mira" to WorldEntity("npc", "Mira", "Waiting at the quay")),
                facts = mapOf("mira-promised-a-meeting" to "true"),
            ),
            parsed.initialWorldState,
        )
    }

    @Test
    fun noWorldStateUsesEmptyRevisionZeroState() {
        val parsed = ScenarioFrontmatter.parse("---\ntitle: Harbor\n---\n# Harbor")

        assertEquals(WorldState(), parsed.initialWorldState)
        assertEquals(0, parsed.initialWorldState.revision)
    }

    @Test
    fun rejectsRevisionInWorldState() = assertInvalidWorldState(
        """
        world-state:
          revision: 1
        """.trimIndent(),
    )

    @Test
    fun rejectsUnknownWorldStateKey() = assertInvalidWorldState(
        """
        world-state:
          weather: rain
        """.trimIndent(),
    )

    @Test
    fun rejectsEntityWithoutSummary() = assertInvalidWorldState(
        """
        world-state:
          entities:
            mira:
              kind: npc
              name: Mira
        """.trimIndent(),
    )

    @Test
    fun rejectsBlankEntityId() = assertInvalidWorldState(
        """
        world-state:
          entities:
            "":
              kind: npc
              name: Mira
              summary: Waiting at the quay
        """.trimIndent(),
    )

    @Test
    fun rejectsNumericEntityId() = assertInvalidWorldState(
        """
        world-state:
          entities:
            1:
              kind: npc
              name: Mira
              summary: Waiting at the quay
        """.trimIndent(),
    )

    @Test
    fun rejectsBlankFactKey() = assertInvalidWorldState(
        """
        world-state:
          facts:
            "": true
        """.trimIndent(),
    )

    @Test
    fun rejectsNumericFactKey() = assertInvalidWorldState(
        """
        world-state:
          facts:
            1: "true"
        """.trimIndent(),
    )

    @Test
    fun rejectsNumericFactValue() = assertInvalidWorldState(
        """
        world-state:
          facts:
            mira-promised-a-meeting: 1
        """.trimIndent(),
    )

    @Test
    fun rejectsListForEntities() = assertInvalidWorldState(
        """
        world-state:
          entities:
            - mira
        """.trimIndent(),
    )

    private fun assertInvalidWorldState(worldState: String) {
        val exception = assertFailsWith<IllegalArgumentException> {
            ScenarioFrontmatter.parse("---\n$worldState\n---\n# Harbor")
        }

        assertEquals("Invalid scenario frontmatter.", exception.message)
    }

    private fun loadResource(path: String): String = XMLHttpRequest().apply {
        open("GET", "/$path", false)
        send()
    }.responseText
}
