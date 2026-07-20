package com.magictablet.cards

import org.junit.Assert.assertEquals
import org.junit.Test

class CardQueryTest {
    @Test fun singleWord_getsPrefix() {
        assertEquals("lightning*", buildMatchQuery("Lightning"))
    }

    @Test fun multiWord_eachPrefixedAndLowercased() {
        assertEquals("lightning* bol*", buildMatchQuery("Lightning bol"))
    }

    @Test fun punctuation_splitsAndStrips() {
        assertEquals("gaea* s* cradle*", buildMatchQuery("Gaea's, Cradle"))
    }

    @Test fun digitsKept() {
        assertEquals("bolt* 3*", buildMatchQuery("Bolt 3"))
    }

    @Test fun blank_isEmpty() {
        assertEquals("", buildMatchQuery(""))
        assertEquals("", buildMatchQuery("   "))
        assertEquals("", buildMatchQuery(",,, --- "))
    }
}
