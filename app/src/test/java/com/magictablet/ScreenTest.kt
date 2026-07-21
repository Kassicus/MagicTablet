package com.magictablet

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTest {
    @Test
    fun screensAreGameCardsStackRulesInOrder() {
        assertEquals(listOf("Game", "Cards", "Stack", "Rules"), Screen.entries.map { it.label })
    }
}
