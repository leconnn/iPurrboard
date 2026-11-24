package dev.leconnn.ioskeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Long-press symbols include Gboard's. */
class AccentsTest {
    @Test fun equalsOffersInfinity() {
        val items = Accents.forOutput("=")!!.items
        assertTrue(items.toString(), "∞" in items && "≠" in items && "≈" in items)
    }

    @Test fun gboardOnlyKeysHaveTheirSymbols() {
        assertEquals(listOf("<", "‹", "≤", "«"), Accents.forOutput("<")!!.items)
        assertTrue("±" in Accents.forOutput("+")!!.items)
        assertTrue("₹" in Accents.forOutput("$")!!.items)
    }
}
