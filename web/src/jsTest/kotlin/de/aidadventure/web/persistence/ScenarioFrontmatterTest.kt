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
    fun parsesInitialWorldStateFromDirectFlowMappings() {
        val parsed = ScenarioFrontmatter.parse(
            "---\nworld-state: { entities: { mira: { kind: npc, name: Mira, summary: Waiting } }, facts: { weather: fog } }\n---\n# Harbor",
        )

        assertEquals(
            WorldState(
                entities = mapOf("mira" to WorldEntity("npc", "Mira", "Waiting")),
                facts = mapOf("weather" to "fog"),
            ),
            parsed.initialWorldState,
        )
    }

    @Test
    fun parsesBlockScalarEntitySummaryContainingColon() {
        val parsed = ScenarioFrontmatter.parse(
            """
            ---
            world-state:
              entities:
                mira:
                  kind: npc
                  name: Mira
                  summary: |
                    2026: arrival
            ---
            # Harbor
            """.trimIndent(),
        )

        assertEquals("2026: arrival\n", parsed.initialWorldState.entities.getValue("mira").summary)
    }

    @Test
    fun parsesQuotedNumericEntityAndFactKeys() {
        val parsed = ScenarioFrontmatter.parse(
            """
            ---
            world-state:
              entities:
                "1":
                  kind: npc
                  name: Mira
                  summary: Waiting at the quay
              facts:
                "2": "true"
            ---
            # Harbor
            """.trimIndent(),
        )

        assertEquals(setOf("1"), parsed.initialWorldState.entities.keys)
        assertEquals(mapOf("2" to "true"), parsed.initialWorldState.facts)
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
    fun rejectsNumericEntityIdInFlowMapping() = assertInvalidWorldState(
        """
        world-state:
          entities: { 1: { kind: npc, name: Mira, summary: Here } }
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
    fun rejectsNumericFactKeyInFlowMapping() = assertInvalidWorldState(
        """
        world-state:
          facts: { 1: x }
        """.trimIndent(),
    )

    @Test
    fun rejectsWorldStateAliasWithNumericSourceKeys() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ScenarioFrontmatter.parse(
                """
                ---
                state: &state
                  entities:
                    1:
                      kind: npc
                      name: Mira
                      summary: Waiting at the quay
                  facts:
                    2: "true"
                world-state: *state
                ---
                # Harbor
                """.trimIndent(),
            )
        }

        assertEquals("Invalid scenario frontmatter.", exception.message)
    }

    @Test
    fun rejectsAnchoredFlowEntitiesMapping() = assertInvalidWorldState(
        """
        world-state: { entities: &entities { 1: { kind: npc, name: Mira, summary: Waiting at the quay } } }
        """.trimIndent(),
    )

    @Test
    fun rejectsTaggedFlowEntitiesMapping() = assertInvalidWorldState(
        """
        world-state: { entities: !!map { 1: { kind: npc, name: Mira, summary: Waiting at the quay } } }
        """.trimIndent(),
    )

    @Test
    fun rejectsMergedEntityMapping() = assertInvalidWorldState(
        """
        world-state:
          entities:
            <<: &defaults
              1:
                kind: npc
                name: Mira
                summary: Waiting at the quay
        """.trimIndent(),
    )

    @Test
    fun rejectsAliasedEntityMapping() = assertInvalidWorldState(
        """
        defaults: &defaults
          kind: npc
          name: Mira
          summary: Waiting at the quay
        world-state:
          entities:
            mira: *defaults
        """.trimIndent(),
    )

    @Test
    fun rejectsStandaloneAnchorContinuationInsideWorldState() = assertInvalidWorldState(
        """
        world-state:
          &state
          entities:
            mira:
              kind: npc
              name: Mira
              summary: Waiting at the quay
        """.trimIndent(),
    )

    @Test
    fun rejectsStandaloneAliasContinuationInsideEntities() = assertInvalidWorldState(
        """
        defaults: &defaults
          mira:
            kind: npc
            name: Mira
            summary: Waiting at the quay
        world-state:
          entities:
            *defaults
        """.trimIndent(),
    )

    @Test
    fun rejectsStandaloneTagContinuationInsideFacts() = assertInvalidWorldState(
        """
        world-state:
          facts:
            !!map
            weather: fog
        """.trimIndent(),
    )

    @Test
    fun rejectsStandaloneAnchorContinuationInsideEntityMapping() = assertInvalidWorldState(
        """
        world-state:
          entities:
            mira:
              &mira
              kind: npc
              name: Mira
              summary: Waiting at the quay
        """.trimIndent(),
    )

    @Test
    fun ignoresNumericRootFrontmatterKey() {
        val parsed = ScenarioFrontmatter.parse("---\n1: whatever\n---\n# Harbor")

        assertEquals(WorldState(), parsed.initialWorldState)
        assertEquals("# Harbor", parsed.promptMarkdown)
    }

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
