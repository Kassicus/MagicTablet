package com.magictablet.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameViewModelTest {
    @Test fun defaultGame_isFourByForty() {
        val vm = GameViewModel()
        assertEquals(4, vm.state.value.players.size)
        assertEquals(40, vm.state.value.startingLife)
    }

    @Test fun adjustLife_accumulatesRecentDelta() {
        val vm = GameViewModel()
        vm.adjustLife(1, -1)
        vm.adjustLife(1, -1)
        vm.adjustLife(1, -1)
        assertEquals(-3, vm.recentDeltas.value[1]?.amount)
        assertEquals(37, vm.state.value.players.first { it.id == 1 }.life)
    }

    @Test fun clearRecentDelta_removesEntry() {
        val vm = GameViewModel()
        vm.adjustLife(2, 5)
        vm.clearRecentDelta(2)
        assertNull(vm.recentDeltas.value[2])
    }

    @Test fun newGame_resetsState() {
        val vm = GameViewModel()
        vm.adjustLife(1, -10)
        vm.newGame(2, 20)
        assertEquals(2, vm.state.value.players.size)
        assertEquals(20, vm.state.value.players.first { it.id == 1 }.life)
        assertEquals(emptyMap<Int, RecentDelta>(), vm.recentDeltas.value)
    }
}
