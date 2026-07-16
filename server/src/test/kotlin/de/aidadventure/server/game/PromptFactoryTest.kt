package de.aidadventure.server.game

import de.aidadventure.server.model.ChatEntry
import de.aidadventure.server.model.TurnRequest
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromptFactoryTest {
    @Test
    fun `build keeps immutable prefix before history and action`() {
        val prompt = PromptFactory().build(
            TurnRequest("live-1", "test-model", 8081, "# Harbor", listOf(ChatEntry("player", "look")), "open door"),
        )

        assertEquals(listOf("system", "system", "user", "user"), prompt.map { it.type().name.lowercase() })
        assertEquals("# Harbor", (prompt[1] as SystemMessage).text())
        assertEquals("look", (prompt[2] as UserMessage).singleText())
        assertEquals("open door", (prompt[3] as UserMessage).singleText())
    }

    @Test
    fun `build rejects unknown history roles`() {
        assertFailsWith<IllegalArgumentException> {
            PromptFactory().build(
                TurnRequest("live-1", "test-model", 8081, "# Harbor", listOf(ChatEntry("narrator", "look")), "open door"),
            )
        }
    }
}
