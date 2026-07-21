package com.magictablet.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun advanceTurn_viaVm() {
        val vm = GameViewModel()
        vm.advanceTurn()
        assertEquals(1, vm.state.value.activePlayerId)
    }

    @Test fun randomFirstPlayer_landsOnValidSeat() {
        val vm = GameViewModel()
        vm.randomFirstPlayer()
        val id = vm.state.value.activePlayerId
        assertTrue(id != null && vm.state.value.players.any { it.id == id })
    }

    @Test fun toggleMonarch_viaVm() {
        val vm = GameViewModel()
        vm.toggleMonarch(3)
        assertEquals(3, vm.state.value.monarchPlayerId)
        vm.toggleMonarch(3)
        assertNull(vm.state.value.monarchPlayerId)
    }

    @Test fun timer_startTickPauseReset() {
        val vm = GameViewModel()
        assertEquals(TimerState(), vm.timer.value)
        vm.startTimer(); vm.tickTimer(); vm.tickTimer()
        assertEquals(true, vm.timer.value.running)
        assertEquals(2L, vm.timer.value.elapsedSeconds)
        vm.pauseTimer(); vm.tickTimer() // no increment while paused
        assertEquals(false, vm.timer.value.running)
        assertEquals(2L, vm.timer.value.elapsedSeconds)
        vm.resetTimer()
        assertEquals(TimerState(), vm.timer.value)
    }

    @Test fun newGame_clearsActiveMonarchTimer() {
        val vm = GameViewModel()
        vm.advanceTurn(); vm.toggleMonarch(2); vm.startTimer(); vm.tickTimer()
        vm.newGame(4, 40)
        assertNull(vm.state.value.activePlayerId)
        assertNull(vm.state.value.monarchPlayerId)
        assertEquals(TimerState(), vm.timer.value)
    }

    @Test fun addToStack_thenResolveTop_setsLastResolved() {
        val vm = GameViewModel()
        vm.addToStack(1, StackKind.Spell, null, "Bolt", "")
        assertEquals(1, vm.state.value.stack.size)
        vm.resolveTop()
        assertEquals(0, vm.state.value.stack.size)
        assertEquals("Bolt", vm.lastResolved.value?.label)
    }

    @Test fun passAround_resolvesTop_andSetsLastResolved() {
        val vm = GameViewModel()  // default 4 players
        vm.addToStack(1, StackKind.Triggered, null, "Trigger", "")
        repeat(4) { vm.passPriority() }
        assertEquals(0, vm.state.value.stack.size)
        assertEquals("Trigger", vm.lastResolved.value?.label)
    }

    @Test fun newGame_clearsStackAndLastResolved() {
        val vm = GameViewModel()
        vm.addToStack(1, StackKind.Spell, null, "Bolt", "")
        vm.resolveTop()
        vm.newGame(4, 40)
        assertEquals(emptyList<StackItem>(), vm.state.value.stack)
        assertNull(vm.lastResolved.value)
    }

    @Test fun adjustLife_recentDeltaTokenIsMonotonic() {
        val vm = GameViewModel()
        vm.adjustLife(1, -1)
        val first = vm.recentDeltas.value[1]?.token ?: 0L
        vm.adjustLife(1, -1)
        val second = vm.recentDeltas.value[1]?.token ?: 0L
        assertTrue(second > first)
    }
}
