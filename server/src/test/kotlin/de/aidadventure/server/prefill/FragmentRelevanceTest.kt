package de.aidadventure.server.prefill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FragmentRelevanceTest {
    @Test
    fun `parses only a trimmed single digit relevance score`() {
        assertEquals(5, parseFragmentRelevance("5"))
        assertEquals(9, parseFragmentRelevance(" 9\n"))
        assertNull(parseFragmentRelevance("5."))
        assertNull(parseFragmentRelevance("10"))
        assertNull(parseFragmentRelevance("five"))
    }
}
