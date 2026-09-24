package de.aidadventure.web

import de.aidadventure.web.model.WorldState
import de.aidadventure.web.persistence.decodePersistedGameSession
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldStatePopoverTest {
    @Test fun `reads an unresolved situation as narrative focus`() =
        assertEquals(WorldState(narrativeFocus = "Der Leuchtturm"), decodePersistedGameSession("""{"id":"s","scenarioId":"x","title":"x","messages":[],"createdAt":1,"updatedAt":1,"worldState":{"unresolvedSituation":"Der Leuchtturm"}}""").worldState)

    @Test fun `reads an old active thread as narrative focus`() =
        assertEquals(WorldState(narrativeFocus = "Der Leuchtturm"), decodePersistedGameSession("""{"id":"s","scenarioId":"x","title":"x","messages":[],"createdAt":1,"updatedAt":1,"worldState":{"activeThread":"Der Leuchtturm"}}""").worldState)

    @Test fun `prefers unresolved situation over an old active thread`() =
        assertEquals(WorldState(narrativeFocus = "Der aktuelle Fokus"), decodePersistedGameSession("""{"id":"s","scenarioId":"x","title":"x","messages":[],"createdAt":1,"updatedAt":1,"worldState":{"unresolvedSituation":"Der aktuelle Fokus","activeThread":"Älter"}}""").worldState)

    @Test fun `prefers an existing narrative focus over legacy values`() =
        assertEquals(WorldState(narrativeFocus = "Der aktuelle Fokus"), decodePersistedGameSession("""{"id":"s","scenarioId":"x","title":"x","messages":[],"createdAt":1,"updatedAt":1,"worldState":{"narrativeFocus":"Der aktuelle Fokus","unresolvedSituation":"Alt","activeThread":"Älter"}}""").worldState)
}
