package de.aidadventure.web.persistence

import de.aidadventure.web.model.WorldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContains

class ScenarioFrontmatterTest {
    @Test fun `runtime fragment flag assignments support inherited named effects and assignment only direct branches`() {
        val parsed = ScenarioFrontmatter.parse("""---
flags: {visited: false, reward: false}
runtime-fragments:
  condition:
    - visited: false
      then:
        set-flag: {visited: true}
  tower:
    set-flag: {reward: true}
    room:
      set-flag: {reward: false}
---
# Tower
""")
        assertEquals(2, parsed.runtimeFragments.size)
        val direct = parsed.runtimeFragments.single { it.direct }
        val named = parsed.runtimeFragments.single { !it.direct }
        assertEquals("", direct.matchText)
        assertEquals("tower\nroom", named.matchText)
        assertEquals(listOf(FlagAssignment("visited", true)), direct.setFlags)
        assertEquals(listOf(FlagAssignment("reward", true), FlagAssignment("reward", false)), named.setFlags)
    }

    @Test fun `runtime fragment assignments reject empty unknown and nonboolean flag values`() {
        for (assignment in listOf("{}", "{unknown: true}", "{visited: yes}")) {
            val error = assertFailsWith<IllegalArgumentException> {
                ScenarioFrontmatter.parse("---\nflags: {visited: false}\nruntime-fragments:\n  tower:\n    set-flag: $assignment\n---\n# Tower")
            }
            assertContains(error.message.orEmpty(), "runtime-fragments.tower.set-flag")
        }
    }

    @Test fun `declared boolean flag named location remains distinct from current location predicates`() {
        val parsed = ScenarioFrontmatter.parse("""---
flags: {location: false}
signals:
  visit:
    condition: {location: true}
    triggers: [{emit-text: Arrival}]
runtime-fragments:
  condition: {location: false}
  emit-text: Flag gated
  tower:
    condition: {location: Leuchtturm}
    emit-text: Place gated
  branch:
    condition:
      - location: true
        then: {flag-path: Flag branch}
      - else: {other-path: Fallback}
---
# Harbor
""")
        assertEquals(listOf(FlagCondition("location", true)), parsed.signalChecks.single().conditions)
        assertEquals(listOf(FlagCondition("location", false)), parsed.runtimeFragments.single { it.direct }.conditions)
        assertEquals(listOf(FlagCondition("location", false), FlagCondition(location = "Leuchtturm")),
            parsed.runtimeFragments.single { it.matchText == "tower" }.conditions)
        assertEquals(listOf(FlagCondition("location", false), FlagCondition("location", true)),
            parsed.runtimeFragments.single { it.matchText == "branch\nflag-path" }.conditions)
        assertEquals(listOf(listOf(FlagCondition("location", true))),
            parsed.runtimeFragments.single { it.matchText == "branch\nother-path" }.excludedConditions)
    }

    @Test fun `location conditions are accepted throughout direct named inherited signal and else predicates`() {
        val parsed = ScenarioFrontmatter.parse("""---
locations: {Leuchtturm: Tower, Kai: Harbor}
flags: {ready: true}
signals:
  visit:
    condition: {location: Leuchtturm, ready: true}
    triggers: [{emit-text: Arrival}]
runtime-fragments:
  condition: {location: Kai}
  emit-text: Harbor hint
  tower:
    condition: {location: Leuchtturm}
    interior: Tower hint
  condition-tree:
    condition:
      - location: Leuchtturm
        ready: true
        then: {tower-path: Matched}
      - else: {fallback-path: Fallback}
---
# Harbor
""")
        assertEquals(2, parsed.signalChecks.single().conditions.size)
        assertEquals(1, parsed.runtimeFragments.single { it.direct }.conditions.size)
        assertEquals(2, parsed.runtimeFragments.single { it.matchText == "tower\ninterior" }.conditions.size)
        assertEquals(3, parsed.runtimeFragments.single { it.matchText == "condition-tree\ntower-path" }.conditions.size)
        assertEquals(2, parsed.runtimeFragments.single { it.matchText == "condition-tree\nfallback-path" }.excludedConditions.single().size)
    }

    @Test fun `location predicates validate nonblank strings and optional catalog`() {
        fun scenario(catalog: String, location: String) =
            "---\nflags: {}\n${catalog}runtime-fragments:\n  condition: {location: $location}\n  emit-text: Hint\n---\n# Game"
        assertEquals(1, ScenarioFrontmatter.parse(scenario("", "Leuchtturm")).runtimeFragments.size)
        assertEquals(1, ScenarioFrontmatter.parse(scenario("locations: {Leuchtturm: Tower}\n", "Leuchtturm")).runtimeFragments.size)
        for (value in listOf("' '", "true", "42", "[]", "null")) {
            assertFailsWith<IllegalArgumentException> { ScenarioFrontmatter.parse(scenario("", value)) }
        }
        assertFailsWith<IllegalArgumentException> { ScenarioFrontmatter.parse(scenario("locations: {Kai: Harbor}\n", "Leuchtturm")) }
    }

    @Test fun `root condition supports direct effects AND nested retrieval and final else`() {
        val parsed = ScenarioFrontmatter.parse("""---
flags: {ready: true, visited: false}
signals:
  enter:
    condition: {ready: true, visited: false}
    triggers:
      - set-flag: {visited: true}
runtime-fragments:
  condition:
    - ready: true
      visited: false
      then:
        emit-text: Direct guidance
        signal: enter
        nearby tower: Retrieved guidance
        condition:
          - visited: false
            then: {emit-text: Nested guidance}
    - ready: true
      then: {emit-text: Another match}
    - else: {emit-text: Fallback}
---
# Harbor
""")
        assertEquals(listOf("Direct guidance", "Nested guidance", "Another match", "Fallback"), parsed.runtimeFragments.filter { it.direct }.map { it.fragment })
        val retrieved = parsed.runtimeFragments.single { !it.direct }
        assertEquals("nearby tower", retrieved.matchText)
        assertEquals("Retrieved guidance", retrieved.fragment)
        assertEquals(listOf(FlagCondition("ready", true), FlagCondition("visited", false)), retrieved.conditions)
        assertEquals(listOf("enter"), parsed.runtimeFragments.first().emittedSignals)
        assertEquals(listOf(listOf(FlagCondition("ready", true), FlagCondition("visited", false)), listOf(FlagCondition("ready", true))), parsed.runtimeFragments.last().excludedConditions)
        assertEquals(2, parsed.signalChecks.single().conditions.size)
    }

    @Test fun `direct and named references retain only the signal declaration conditions`() {
        val parsed = ScenarioFrontmatter.parse("""---
flags: {ready: false, enabled: true}
signals:
  visit:
    condition: {enabled: true}
    triggers:
      - emit-text: Signal guidance
runtime-fragments:
  condition:
    - ready: true
      then: {signal: visit}
    - else:
        tower:
          signal: visit
          emit-text: Retrieved guidance
---
# Harbor
""")
        assertEquals(listOf(
            SignalCheckDefinition("visit", null, listOf(FlagCondition("enabled", true)), listOf("Signal guidance")),
        ), parsed.signalChecks)
    }

    @Test fun `named final else requires a nonempty fragment mapping`() {
        for (fallback in listOf("scalar", "7", "[]", "{}")) {
            val error = assertFailsWith<IllegalArgumentException> {
                ScenarioFrontmatter.parse("---\nflags: {ready: true}\nruntime-fragments:\n  tower:\n    condition:\n      - ready: true\n        then: {known: guidance}\n      - else: $fallback\n---\n# Harbor")
            }
            assertContains(error.message.orEmpty(), "runtime-fragments.tower.condition[1].else")
        }
    }

    @Test fun `rejects invalid root conditional branches with useful paths`() {
        for (branches in listOf(
            "[{else: {emit-text: fallback}}, {ready: true, then: {emit-text: x}}]",
            "[{else: {emit-text: a}}, {else: {emit-text: b}}]",
            "[{ready: true, then: {}}]",
            "[{unknown: true, then: {emit-text: x}}]",
            "[{ready: yes, then: {emit-text: x}}]",
            "[{then: {emit-text: x}}]",
            "[{ready: true, else: {emit-text: x}}]",
            "[{ready: true, then: {signal: unknown}}]",
        )) {
            val error = assertFailsWith<IllegalArgumentException> {
                ScenarioFrontmatter.parse("---\nflags: {ready: true}\nruntime-fragments: {condition: $branches}\n---\n# Harbor")
            }
            assertContains(error.message.orEmpty(), "runtime-fragments.condition")
        }
    }

    @Test fun `rejects malformed emitted signal text with trigger path`() {
        for (value in listOf("''", "true", "{}")) {
            val error = assertFailsWith<IllegalArgumentException> {
                ScenarioFrontmatter.parse("---\nsignals:\n  visit:\n    triggers:\n      - emit-text: $value\n---\n# Harbor")
            }
            assertContains(error.message.orEmpty(), "signals.visit.triggers[0].emit-text")
        }
    }

    @Test fun `accepts text only signals without flags`() {
        val parsed = ScenarioFrontmatter.parse("---\nsignals:\n  visit:\n    triggers:\n      - emit-text: Remember the tower.\n---\n# Harbor")
        assertEquals(emptyList(), parsed.signals["visit"])
        assertEquals(listOf("Remember the tower."), parsed.signalChecks.single().emittedTexts)
    }

    @Test fun `signal without event check retains conditions without a fallback predicate`() {
        val parsed = ScenarioFrontmatter.parse("---\nflags: {visited: false}\nsignals:\n  visit:\n    condition: {visited: false}\n    triggers:\n      - set-flag: {visited: true}\n---\n# Harbor")
        assertEquals(listOf(SignalCheckDefinition("visit", null, listOf(FlagCondition("visited", false)))), parsed.signalChecks)
    }

    @Test fun `absent null empty and blank event checks disable automatic predicates`() {
        for (field in listOf("", "    event-check: null\n", "    event-check:\n", "    event-check: ''\n", "    event-check: '  '\n")) {
            val parsed = ScenarioFrontmatter.parse("---\nsignals:\n  visit:\n${field}    triggers: [{emit-text: Arrival}]\n---\n# Harbor")
            assertEquals(listOf(SignalCheckDefinition("visit", emittedTexts = listOf("Arrival"))), parsed.signalChecks)
        }
    }

    @Test fun `rejects malformed event checks and signal conditions with field paths`() {
        listOf(
            "event-check: true" to "signals.visit.event-check",
            "event-check: {}" to "signals.visit.event-check",
            "description: Old predicate" to "signals.visit",
            "condition: {missing: true}" to "signals.visit.condition.missing",
            "condition: {visited: unknown}" to "signals.visit.condition.visited",
        ).forEach { (field, path) ->
            val error = assertFailsWith<IllegalArgumentException> {
                ScenarioFrontmatter.parse("---\nflags: {visited: false}\nsignals:\n  visit:\n    $field\n    triggers:\n      - set-flag: {visited: true}\n---\n# Harbor")
            }
            assertContains(error.message.orEmpty(), path)
        }
    }

    @Test fun `accepts explicit event check independently of fragment conditions`() {
        val parsed = ScenarioFrontmatter.parse("""---
flags: {visited: false, ready: true}
signals:
  visit:
    event-check: The player actually enters the tower.
    condition: {visited: false}
    triggers:
      - set-flag: {visited: true}
runtime-fragments:
  unrelated guidance:
    condition: {ready: true}
    signal: visit
    emit-text: Describe the weather.
---
# Harbor
""")
        assertEquals(listOf(FlagAssignment("visited", true)), parsed.signals["visit"])
        assertEquals(listOf(SignalCheckDefinition("visit", "The player actually enters the tower.", listOf(FlagCondition("visited", false)))), parsed.signalChecks)
    }

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
        assertEquals(listOf(SignalCheckDefinition("visit")), parsed.signalChecks)
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
    @Test fun `parses location entry triggers while preserving legacy descriptions`() {
        val parsed = ScenarioFrontmatter.parse("""---
world-state: {location: Kai}
flags: {arrived: false}
locations:
  Kai:
    description: Harbor at dawn.
    triggers:
      - set-flag: {arrived: true}
      - emit-text: The harbor bells ring.
      - signals: [met-keeper]
  Leuchtturm: Old lighthouse.
signals:
  met-keeper:
    triggers:
      - set-flag: {arrived: false}
---
# Hafen
""")
        assertEquals(mapOf("Kai" to "Harbor at dawn.", "Leuchtturm" to "Old lighthouse."), parsed.locations)
        assertEquals(listOf(
            LocationTriggerAction(setFlags = listOf(FlagAssignment("arrived", true))),
            LocationTriggerAction(emitText = "The harbor bells ring."),
            LocationTriggerAction(signals = listOf("met-keeper")),
        ), parsed.locationTriggers.getValue("Kai"))
    }
    @Test fun `rejects a catalog without its initial location or duplicate locations`() {
        assertFailsWith<IllegalArgumentException> {
            ScenarioFrontmatter.parse("---\nworld-state:\n  location: Kai\nlocations:\n  Leuchtturm: ''\n---\n# Hafen")
        }
        assertFailsWith<IllegalArgumentException> {
            ScenarioFrontmatter.parse("---\nlocations:\n  Kai: ''\n  kai: ''\n---\n# Hafen")
        }
    }
    @Test fun `location signals must reference declared signals`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ScenarioFrontmatter.parse("""---
locations:
  Kai:
    description: Harbor.
    triggers:
      - signals: [unknown]
---
# Hafen
""")
        }
        assertContains(error.message.orEmpty(), "locations.Kai.triggers[0].signals[0]")
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
