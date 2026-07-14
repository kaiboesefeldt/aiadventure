package de.aidadventure.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionSubmissionTest {
    @Test
    fun `plain Enter submits a non-empty action`() {
        assertTrue(shouldSubmitOnEnter("Enter", false, false, false, "look"))
    }

    @Test
    fun `Shift Enter keeps a newline`() {
        assertFalse(shouldSubmitOnEnter("Enter", true, false, false, "look"))
    }

    @Test
    fun `Enter does not submit a blank action`() {
        assertFalse(shouldSubmitOnEnter("Enter", false, false, false, "  \n"))
    }

    @Test
    fun `Enter does not submit while a turn is pending or composing`() {
        assertFalse(shouldSubmitOnEnter("Enter", false, false, true, "look"))
        assertFalse(shouldSubmitOnEnter("Enter", false, true, false, "look"))
    }
}
