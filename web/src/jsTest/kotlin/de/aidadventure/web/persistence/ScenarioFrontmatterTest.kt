package de.aidadventure.web.persistence

import de.aidadventure.web.model.WorldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContains

class ScenarioFrontmatterTest {
    @Test fun `parses flags signals and inherited fragment emissions`() {
        val parsed = ScenarioFrontmatter.parse("""---
flags:
  visited: false
  has-map: true
signals:
  visit:
    triggers:
      - set-flag:
          visited: true
runtime-fragments:
  lighthouse:
    signal: visit
    emit-text: A tower stands ahead.
    condition:
      - has-map: true
        then:
          drawing:
            emit-text: A cross marks the coast.
          chart: A chart lies nearby.
---
# Harbor
""")

        assertEquals(mapOf("visited" to false, "has-map" to true), parsed.initialFlags)
        assertEquals(mapOf("visit" to listOf(FlagAssignment("visited", true))), parsed.signals)
        assertEquals(
            listOf(
                RuntimeFragmentDefinition("lighthouse\ndrawing", "A tower stands ahead.\nA cross marks the coast.", listOf(FlagCondition("has-map", true)), listOf("visit")),
                RuntimeFragmentDefinition("lighthouse\nchart", "A tower stands ahead.\nA chart lies nearby.", listOf(FlagCondition("has-map", true)), listOf("visit")),
            ),
            parsed.runtimeFragments,
        )
    }

    @Test fun `accepts sequential signal declarations and emits signals in path order`() {
        val parsed = ScenarioFrontmatter.parse("""---
flags: {ready: false}
signals:
  - outer:
      triggers:
        - set-flag: {ready: true}
  - inner:
      triggers:
        - set-flag: {ready: false}
runtime-fragments:
  first:
    signal: outer
    second:
      signal: inner
      emit-text: Chosen.
---
# Harbor""")

        assertEquals(listOf("outer", "inner"), parsed.runtimeFragments.single().emittedSignals)
        assertEquals(listOf(FlagAssignment("ready", false)), parsed.signals.getValue("inner"))
    }

    @Test fun `rejects undeclared names and bad schema at their YAML paths`() {
        val invalid = listOf(
            "flags:\n  visited: yes" to "flags.visited",
            "signals:\n  visit:\n    triggers:\n      - set-flag: {visited: true}" to "signals.visit.triggers[0].set-flag.visited",
            "flags: {visited: false}\nruntime-fragments:\n  lighthouse:\n    signal: visit\n    emit-text: Ready." to "runtime-fragments.lighthouse.signal",
            "flags: {other: false}\nruntime-fragments:\n  lighthouse:\n    condition:\n      - visited: true\n        then:\n          chart: Found." to "runtime-fragments.lighthouse.condition[0].visited",
            "runtime-fragments:\n  lighthouse:\n    emit-text: ''" to "runtime-fragments.lighthouse.emit-text",
            "flags: {visited: false}\nruntime-fragments:\n  lighthouse:\n    condition: {visited: false}" to "runtime-fragments.lighthouse",
            "flags: {visited: false}\nruntime-fragments:\n  lighthouse:\n    condition:\n      - visited: true\n        then: {chart: Seen}\n      - visited: false\n        then: {chart: Unseen}" to "runtime-fragments.lighthouse.chart",
            "signals:\n  visit:\n    triggers: []" to "signals.visit.triggers",
        )
        invalid.forEach { (body, path) ->
            val error = assertFailsWith<IllegalArgumentException> { ScenarioFrontmatter.parse("---\n$body\n---\n# Harbor") }
            assertContains(error.message.orEmpty(), path)
        }
    }
    @Test fun `reserved-looking nested keys remain valid legacy fragment labels without signal declarations`() {
        val parsed = ScenarioFrontmatter.parse("""---
runtime-fragments:
  lighthouse:
    signal: A warning bell rings.
    condition: The door is locked.
    emit-text: The stairs are wet.
---
# Harbor""")

        assertEquals(
            listOf(
                RuntimeFragmentDefinition("lighthouse\nsignal", "A warning bell rings."),
                RuntimeFragmentDefinition("lighthouse\ncondition", "The door is locked."),
                RuntimeFragmentDefinition("lighthouse\nemit-text", "The stairs are wet."),
            ),
            parsed.runtimeFragments,
        )
    }
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
    @Test fun `parses runtime fragment strings and mappings`() {
        val parsed = ScenarioFrontmatter.parse("""---
runtime-fragments:
  lighthouse: Mira remembers the storm.
  harbor:
    lighthouse: The harbor is quiet.
---
# Hafen
""")

        assertEquals(
            listOf(
                RuntimeFragmentDefinition("lighthouse", "Mira remembers the storm."),
                RuntimeFragmentDefinition("harbor\nlighthouse", "The harbor is quiet."),
            ),
            parsed.runtimeFragments,
        )
    }

    @Test fun `rejects blank strings and unsupported runtime fragment values`() {
        listOf(
            "runtime-fragments:\n  lighthouse: ''",
            "runtime-fragments:\n  lighthouse: 42",
        ).forEach { frontmatter ->
            assertFailsWith<IllegalArgumentException> {
                ScenarioFrontmatter.parse("---\n$frontmatter\n---\n# Hafen")
            }
        }
    }
}
