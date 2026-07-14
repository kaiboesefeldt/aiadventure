package de.aidadventure.web.persistence

import de.aidadventure.web.model.Scenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ScenarioImportTest {
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
}
