package de.aidadventure.web.persistence

import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExampleScenarioSmokeTest {
    @Test fun `real simple example declares independent guarded event checks`() = MainScope().promise {
        val parsed = loadExample("signal-test-game.md")
        assertEquals(mapOf("war-im-leuchtturm" to false, "hat-hinweis" to false,
            "hafenmeisterin-misstrauisch" to false), parsed.initialFlags)
        val expected = mapOf(
            "betritt-leuchtturm" to FlagAssignment("war-im-leuchtturm", true),
            "entdeckt-hinweis" to FlagAssignment("hat-hinweis", true),
            "verunsichert-hafenmeisterin" to FlagAssignment("hafenmeisterin-misstrauisch", true),
            "beruhigt-hafenmeisterin" to FlagAssignment("hafenmeisterin-misstrauisch", false),
        )
        assertEquals(expected.mapValues { listOf(it.value) }, parsed.signals)
        assertEquals(expected.keys, parsed.signalChecks.map { it.name }.toSet())
        assertEquals(expected.size, parsed.signalChecks.size)
        parsed.signalChecks.forEach { check ->
            val assignment = expected.getValue(check.name)
            assertTrue(check.description.isNotBlank() && check.description != check.name)
            assertEquals(listOf(FlagCondition(assignment.flag, !assignment.value)), check.conditions)
        }
        assertTrue(parsed.runtimeFragments.isNotEmpty())
        assertTrue(parsed.runtimeFragments.all { it.emittedSignals.isEmpty() })
    }

    @Test fun `real complex example retains conditional guidance and legacy signals`() = MainScope().promise {
        val parsed = loadExample("test-game.md")
        assertTrue(parsed.initialSituationSetting.isNotBlank())
        assertTrue(parsed.locations.isNotEmpty())
        assertTrue(parsed.runtimeFragments.any { it.conditions.isNotEmpty() })
        assertTrue(parsed.runtimeFragments.any { it.emittedSignals.isNotEmpty() })
        assertTrue(parsed.signalChecks.isNotEmpty())
        parsed.signals.values.flatten().forEach { assertTrue(it.flag in parsed.initialFlags) }
    }

    private suspend fun loadExample(name: String): ParsedScenario {
        val response = window.fetch("/examples/$name").await()
        assertEquals(200, response.status.toInt(), "Real example must be served: $name")
        return ScenarioFrontmatter.parse(response.text().await())
    }
}
