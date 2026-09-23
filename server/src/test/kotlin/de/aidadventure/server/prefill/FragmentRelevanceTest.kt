package de.aidadventure.server.prefill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FragmentRelevanceTest {
    @Test
    fun `parses only exact Y or N relevance decisions`() {
        assertEquals<Boolean?>(true, parseFragmentDecision("Y"))
        assertEquals<Boolean?>(false, parseFragmentDecision("N"))
        assertNull(parseFragmentDecision(" N\n"))
        assertNull(parseFragmentDecision("y"))
        assertNull(parseFragmentDecision("YES"))
        assertNull(parseFragmentDecision("5"))
        assertNull(parseFragmentDecision("N."))
    }
}
