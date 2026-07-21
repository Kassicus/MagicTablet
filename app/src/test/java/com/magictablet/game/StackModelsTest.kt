package com.magictablet.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StackModelsTest {
    private fun game() = initialGame(4, 40) // players 1..4
    private fun item(id: Long, controller: Int = 1, kind: StackKind = StackKind.Spell) =
        StackItem(id, controller, null, kind, "Item $id")

    @Test fun push_addsToTop_setsPriorityToActive() {
        val g = game().copy(activePlayerId = 2).pushStackItem(item(1))
        assertEquals(listOf(1L), g.stack.map { it.id })
        assertEquals(2, g.priorityPlayerId)   // active player
        assertEquals(0, g.consecutivePasses)
    }

    @Test fun push_priorityFallsBackToFirstSeat_whenNoActive() {
        assertEquals(1, game().pushStackItem(item(1)).priorityPlayerId)
    }

    @Test fun resolveTop_popsTop_resets() {
        val g = game().pushStackItem(item(1)).pushStackItem(item(2)).resolveTop()
        assertEquals(listOf(1L), g.stack.map { it.id })  // top (2) removed
        assertEquals(0, g.consecutivePasses)
        assertNull(game().pushStackItem(item(1)).resolveTop().priorityPlayerId) // empty -> null
    }

    @Test fun removeStackItem_removesById() {
        val g = game().pushStackItem(item(1)).pushStackItem(item(2)).removeStackItem(1)
        assertEquals(listOf(2L), g.stack.map { it.id })
    }

    @Test fun clearStack_empties() {
        val g = game().pushStackItem(item(1)).clearStack()
        assertEquals(emptyList<StackItem>(), g.stack)
        assertNull(g.priorityPlayerId)
    }

    @Test fun passPriority_advancesToNextSeat() {
        val g = game().copy(activePlayerId = 1).pushStackItem(item(1)).passPriority()
        assertEquals(2, g.priorityPlayerId)
        assertEquals(1, g.consecutivePasses)
    }

    @Test fun passPriority_allPassed_resolvesTop() {
        var g = game().copy(activePlayerId = 1).pushStackItem(item(1))
        repeat(4) { g = g.passPriority() }   // 4 players all pass
        assertEquals(emptyList<StackItem>(), g.stack) // top resolved
        assertEquals(0, g.consecutivePasses)
    }
}
