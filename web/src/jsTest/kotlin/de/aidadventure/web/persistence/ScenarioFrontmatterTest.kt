package de.aidadventure.web.persistence

import de.aidadventure.web.model.WorldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScenarioFrontmatterTest {
    @Test fun `parses a nonblank initial location`() {
        assertEquals(WorldState("Küche"), ScenarioFrontmatter.parse("---\nworld-state:\n  location: Küche\n---\n# Haus").initialWorldState)
    }
    @Test fun `omitted world state is empty`() {
        assertEquals(WorldState(), ScenarioFrontmatter.parse("# Haus").initialWorldState)
    }
    @Test fun `rejects blank or additional location fields`() {
        assertFailsWith<IllegalArgumentException> { ScenarioFrontmatter.parse("---\nworld-state:\n  location: ''\n---\n# Haus") }
        assertFailsWith<IllegalArgumentException> { ScenarioFrontmatter.parse("---\nworld-state:\n  location: Küche\n  facts: {}\n---\n# Haus") }
    }
}
