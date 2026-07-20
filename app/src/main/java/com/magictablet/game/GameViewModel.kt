package com.magictablet.game

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class GameViewModel : ViewModel() {
    private val _state = MutableStateFlow(initialGame(DEFAULT_PLAYER_COUNT, DEFAULT_STARTING_LIFE))
    val state: StateFlow<GameState> = _state.asStateFlow()

    private val _recentDeltas = MutableStateFlow<Map<Int, RecentDelta>>(emptyMap())
    val recentDeltas: StateFlow<Map<Int, RecentDelta>> = _recentDeltas.asStateFlow()

    private val _timer = MutableStateFlow(TimerState())
    val timer: StateFlow<TimerState> = _timer.asStateFlow()

    fun newGame(playerCount: Int, startingLife: Int) {
        _state.value = initialGame(playerCount, startingLife)
        _recentDeltas.value = emptyMap()
        _timer.value = TimerState()
    }

    fun adjustLife(playerId: Int, delta: Int) {
        _state.update { it.adjustLife(playerId, delta) }
        _recentDeltas.update { current ->
            val existing = current[playerId]
            current + (playerId to RecentDelta(
                amount = (existing?.amount ?: 0) + delta,
                token = (existing?.token ?: 0L) + 1L,
            ))
        }
    }

    fun clearRecentDelta(playerId: Int) {
        _recentDeltas.update { it - playerId }
    }

    fun advanceTurn() = _state.update { it.advanceTurn() }

    fun randomFirstPlayer() {
        val players = _state.value.players
        if (players.isEmpty()) return
        val id = players.random().id
        _state.update { it.setActivePlayer(id) }
    }

    fun toggleMonarch(playerId: Int) = _state.update { it.toggleMonarch(playerId) }

    fun startTimer() = _timer.update { it.copy(running = true) }
    fun pauseTimer() = _timer.update { it.copy(running = false) }
    fun resetTimer() { _timer.value = TimerState() }
    fun tickTimer() = _timer.update { if (it.running) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it }

    fun adjustPoison(playerId: Int, delta: Int) = _state.update { it.adjustPoison(playerId, delta) }

    fun adjustCommanderDamage(playerId: Int, fromOpponentId: Int, delta: Int) =
        _state.update { it.adjustCommanderDamage(playerId, fromOpponentId, delta) }

    fun adjustCounter(playerId: Int, counter: String, delta: Int) =
        _state.update { it.adjustCounter(playerId, counter, delta) }

    companion object {
        const val DEFAULT_PLAYER_COUNT = 4
        const val DEFAULT_STARTING_LIFE = 40
    }
}
