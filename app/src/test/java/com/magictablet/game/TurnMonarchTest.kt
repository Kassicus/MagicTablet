package com.magictablet.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnMonarchTest {
    @Test fun initialGame_hasNoActiveOrMonarch() {
        val g = initialGame(4, 40)
        assertNull(g.activePlayerId)
        assertNull(g.monarchPlayerId)
    }

    @Test fun advanceTurn_fromNull_goesToFirstSeat() {
        assertEquals(1, initialGame(4, 40).advanceTurn().activePlayerId)
    }

    @Test fun advanceTurn_wrapsAndOrders() {
        var g = initialGame(3, 40)
        g = g.advanceTurn(); assertEquals(1, g.activePlayerId)
        g = g.advanceTurn(); assertEquals(2, g.activePlayerId)
        g = g.advanceTurn(); assertEquals(3, g.activePlayerId)
        g = g.advanceTurn(); assertEquals(1, g.activePlayerId) // wrap
    }

    @Test fun setActivePlayer_setsId() {
        assertEquals(3, initialGame(4, 40).setActivePlayer(3).activePlayerId)
    }

    @Test fun toggleMonarch_claimThenPass() {
        val claimed = initialGame(4, 40).toggleMonarch(2)
        assertEquals(2, claimed.monarchPlayerId)
        assertNull(claimed.toggleMonarch(2).monarchPlayerId) // pass/clear
        assertEquals(3, claimed.toggleMonarch(3).monarchPlayerId) // move crown
    }
}
