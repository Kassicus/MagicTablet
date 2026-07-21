package com.magictablet.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLogicTest {
    @Test fun initialGame_buildsPlayers() {
        val g = initialGame(4, 40)
        assertEquals(4, g.players.size)
        assertEquals(40, g.startingLife)
        assertEquals(listOf(1, 2, 3, 4), g.players.map { it.id })
        assertEquals(listOf(1, 2, 3, 4), g.players.map { it.seat })
        assertEquals(listOf("P1", "P2", "P3", "P4"), g.players.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), g.players.map { it.colorIndex })
        assertTrue(g.players.all { it.life == 40 && it.poison == 0 })
        assertEquals(mapOf("energy" to 0, "experience" to 0), g.players.first().counters)
    }

    @Test fun seatSplit_isBottomHeavy() {
        assertEquals(1 to 1, seatSplit(2))
        assertEquals(1 to 2, seatSplit(3))
        assertEquals(2 to 2, seatSplit(4))
        assertEquals(2 to 3, seatSplit(5))
        assertEquals(3 to 3, seatSplit(6))
    }

    @Test fun adjustLife_canGoNegative() {
        val g = initialGame(2, 1).adjustLife(1, -3)
        assertEquals(-2, g.players.first { it.id == 1 }.life)
    }

    @Test fun counts_clampAtZero() {
        var g = initialGame(2, 40)
        g = g.adjustPoison(1, -5)
        g = g.adjustCounter(1, "energy", -2)
        g = g.adjustCommanderDamage(1, 2, -4)
        val p = g.players.first { it.id == 1 }
        assertEquals(0, p.poison)
        assertEquals(0, p.counters["energy"])
        assertEquals(0, p.commanderDamage[2] ?: 0)
    }

    @Test fun commanderDamage_isPerOpponent() {
        val g = initialGame(4, 40)
            .adjustCommanderDamage(1, 2, 21)
            .adjustCommanderDamage(1, 3, 5)
        val p = g.players.first { it.id == 1 }
        assertEquals(21, p.commanderDamage[2])
        assertEquals(5, p.commanderDamage[3])
        assertTrue(p.isLost())
        assertEquals("cmdr", p.lossReason())
    }

    @Test fun lossReason_precedence() {
        assertNull(initialGame(2, 40).players.first().lossReason())
        assertEquals("0 life", initialGame(2, 40).adjustLife(1, -40).players.first { it.id == 1 }.lossReason())
        assertEquals("poison", initialGame(2, 40).adjustPoison(1, 10).players.first { it.id == 1 }.lossReason())
        assertFalse(initialGame(2, 40).players.first().isLost())
    }

    @Test fun lossReason_zeroLifeTakesPrecedence() {
        val p = PlayerState(id = 1, seat = 1, name = "P1", colorIndex = 0, life = 0, poison = 10, commanderDamage = mapOf(2 to 21))
        assertTrue(p.isLost())
        assertEquals("0 life", p.lossReason())
    }

    @Test fun lossReason_poisonBeforeCommander() {
        val p = PlayerState(id = 1, seat = 1, name = "P1", colorIndex = 0, life = 20, poison = 10, commanderDamage = mapOf(2 to 21))
        assertTrue(p.isLost())
        assertEquals("poison", p.lossReason())
    }

    @Test fun lossReason_commanderDamage() {
        val p = PlayerState(id = 1, seat = 1, name = "P1", colorIndex = 0, life = 20, poison = 0, commanderDamage = mapOf(2 to 21))
        assertTrue(p.isLost())
        assertEquals("cmdr", p.lossReason())
    }

    @Test fun notLost_withEmptyCommanderDamage() {
        val p = PlayerState(id = 1, seat = 1, name = "P1", colorIndex = 0, life = 20)
        assertFalse(p.isLost())
        assertNull(p.lossReason())
    }
}
