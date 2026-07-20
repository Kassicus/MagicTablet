package com.magictablet

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTest {
    @Test
    fun screensAreGameCardsStackInOrder() {
        assertEquals(listOf("Game", "Cards", "Stack"), Screen.entries.map { it.label })
    }
}
