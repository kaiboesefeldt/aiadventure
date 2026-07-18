package de.aidadventure.web.persistence

import de.aidadventure.web.model.Scenario
import de.aidadventure.web.model.GameSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScenarioImportTest {
    @Test
    fun `invalid fragments reject import and valid raw template remains`() {
        assertEquals(
            ImportResult.Rejected("Invalid scenario frontmatter."),
            ScenarioImport.fromFile("x.md", "---\nruntime-fragments: []\n---\n# X", emptyList(), 1),
        )

        val created = assertIs<ImportResult.Created>(
            ScenarioImport.fromFile("x.md", "---\nruntime-fragments: {}\n---\n# X", emptyList(), 1),
        )

        assertTrue(created.scenario.markdown.startsWith("---"))
    }

    @Test
    fun `same filename and markdown updates existing scenario`() {
        val old = Scenario("id-1", "harbor.md", "# Harbor", 1)

        val result = ScenarioImport.fromFile("harbor.md", "# Harbor", listOf(old), now = 2)

        assertEquals(ImportResult.Updated("id-1"), result)
    }

    @Test
    fun `blank markdown is rejected`() {
        assertEquals(
            ImportResult.Rejected("Scenario file is empty."),
            ScenarioImport.fromFile("x.md", "  ", emptyList(), now = 1),
        )
    }

    @Test
    fun `non Markdown file is rejected`() {
        assertEquals(
            ImportResult.Rejected("Scenario files must use the .md or .markdown extension."),
            ScenarioImport.fromFile("harbor.txt", "# Harbor", emptyList(), now = 1),
        )
    }

    @Test
    fun `new Markdown file creates a deterministic scenario id`() {
        val first = assertIs<ImportResult.Created>(
            ScenarioImport.fromFile("harbor.markdown", "# Harbor", emptyList(), now = 1),
        )
        val second = assertIs<ImportResult.Created>(
            ScenarioImport.fromFile("harbor.markdown", "# Harbor", emptyList(), now = 2),
        )

        assertEquals(first.scenario.id, second.scenario.id)
        assertEquals("# Harbor", first.scenario.markdown)
    }

    @Test
    fun `scenario deletion selects sessions with the matching scenario id`() {
        val sessions = listOf(
            GameSession("session-1", "harbor", "Harbor", "model", emptyList(), 1, 1),
            GameSession("session-2", "forest", "Forest", "model", emptyList(), 1, 1),
            GameSession("session-3", "harbor", "Return", "model", emptyList(), 1, 1),
        )

        assertEquals(
            listOf("session-1", "session-3"),
            sessionIdsForScenarioDeletion("harbor", sessions),
        )
    }
}
