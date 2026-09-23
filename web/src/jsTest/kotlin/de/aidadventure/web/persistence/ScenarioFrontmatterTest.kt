package de.aidadventure.web.persistence

import de.aidadventure.web.model.WorldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScenarioFrontmatterTest {
    @Test fun `parses a nonblank initial location`() {
        assertEquals(WorldState("Küche"), ScenarioFrontmatter.parse("---\nworld-state:\n  location: Küche\n---\n# Haus").initialWorldState)
    }
    @Test fun `parses initial situation setting and nested starting location`() {
        val parsed = ScenarioFrontmatter.parse("""---
initial-situation:
  world-state:
    location: Bar
  setting: >
    The harbor-master greets the player.
---
# Hafen
""")
        assertEquals(WorldState("Bar"), parsed.initialWorldState)
        assertEquals("The harbor-master greets the player.\n", parsed.initialSituationSetting)
    }
    @Test fun `rejects malformed initial situation fields`() {
        listOf(
            "initial-situation: []",
            "initial-situation:\n  world-state:\n    location: ''",
            "initial-situation:\n  setting: ''",
            "initial-situation:\n  setting: true",
            "initial-situation:\n  world-state: []",
        ).forEach { body ->
            assertFailsWith<IllegalArgumentException> { ScenarioFrontmatter.parse("---\n$body\n---\n# Haus") }
        }
    }
    @Test fun `omitted world state is empty`() {
        assertEquals(WorldState(), ScenarioFrontmatter.parse("# Haus").initialWorldState)
    }
    @Test fun `parses the canonical location catalog`() {
        assertEquals(
            mapOf("Kai" to "", "Leuchtturm" to ""),
            ScenarioFrontmatter.parse("---\nworld-state:\n  location: Kai\nlocations:\n  Kai:\n  Leuchtturm: ''\n---\n# Hafen").locations,
        )
    }
    @Test fun `rejects a catalog without its initial location or duplicate locations`() {
        assertFailsWith<IllegalArgumentException> {
            ScenarioFrontmatter.parse("---\nworld-state:\n  location: Kai\nlocations:\n  Leuchtturm: ''\n---\n# Hafen")
        }
        assertFailsWith<IllegalArgumentException> {
            ScenarioFrontmatter.parse("---\nlocations:\n  Kai: ''\n  kai: ''\n---\n# Hafen")
        }
    }
    @Test fun `rejects blank or additional location fields`() {
        assertFailsWith<IllegalArgumentException> { ScenarioFrontmatter.parse("---\nworld-state:\n  location: ''\n---\n# Haus") }
        assertFailsWith<IllegalArgumentException> { ScenarioFrontmatter.parse("---\nworld-state:\n  location: Küche\n  facts: {}\n---\n# Haus") }
    }
}
