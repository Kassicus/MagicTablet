package com.magictablet.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class GameViewModel(private val persistence: GamePersistence = NoPersistence) : ViewModel() {
    private val _state = MutableStateFlow(initialGame(DEFAULT_PLAYER_COUNT, DEFAULT_STARTING_LIFE))
    val state: StateFlow<GameState> = _state.asStateFlow()

    private val _recentDeltas = MutableStateFlow<Map<Int, RecentDelta>>(emptyMap())
    val recentDeltas: StateFlow<Map<Int, RecentDelta>> = _recentDeltas.asStateFlow()

    private val _timer = MutableStateFlow(TimerState())
    val timer: StateFlow<TimerState> = _timer.asStateFlow()

    private val _lastResolved = MutableStateFlow<StackItem?>(null)
    val lastResolved: StateFlow<StackItem?> = _lastResolved.asStateFlow()
    private var nextStackId = 1L

    init {
        persistence.load()?.let { snapshot ->
            _state.value = snapshot.state
            nextStackId = snapshot.nextStackId
        }
        // Only launch the autosave collector when there is real persistence. The default
        // NoPersistence path is a no-op to save anyway, and skipping the launch keeps
        // GameViewModel() constructible in plain-JVM unit tests where viewModelScope's Main
        // dispatcher isn't initialized (launchIn would otherwise throw from the constructor).
        if (persistence !== NoPersistence) {
            _state.drop(1)
                .debounce(AUTOSAVE_DEBOUNCE_MS)
                .onEach { snapshotState ->
                    // Capture id counter on the collector thread (same thread as the intents that
                    // mutate it), then do the JSON encode + file write off the main thread.
                    val snapshot = GameSnapshot(snapshotState, nextStackId)
                    withContext(Dispatchers.IO) { persistence.save(snapshot) }
                }
                .launchIn(viewModelScope)
        }
    }

    fun newGame(playerCount: Int, startingLife: Int) {
        _state.value = initialGame(playerCount, startingLife)
        _recentDeltas.value = emptyMap()
        _timer.value = TimerState()
        _lastResolved.value = null
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

    fun addToStack(controllerId: Int, kind: StackKind, cardOracleId: String?, label: String, targets: String) {
        _state.update { it.pushStackItem(StackItem(nextStackId++, controllerId, cardOracleId, kind, label, targets)) }
    }

    fun resolveTop() = resolvingUpdate { it.resolveTop() }
    fun passPriority() = resolvingUpdate { it.passPriority() }
    fun removeStackItem(id: Long) = _state.update { it.removeStackItem(id) }
    fun clearStack() { _state.update { it.clearStack() }; _lastResolved.value = null }
    fun clearLastResolved() { _lastResolved.value = null }

    /** Apply [op]; if it shrank the stack (a resolution), capture the removed top into lastResolved. */
    private fun resolvingUpdate(op: (GameState) -> GameState) {
        val before = _state.value
        val top = before.stack.lastOrNull()
        val after = op(before)
        _state.value = after
        if (after.stack.size < before.stack.size && top != null) _lastResolved.value = top
    }

    companion object {
        const val DEFAULT_PLAYER_COUNT = 4
        const val DEFAULT_STARTING_LIFE = 40
        const val AUTOSAVE_DEBOUNCE_MS = 500L
    }
}

class GameViewModelFactory(private val persistence: GamePersistence) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = GameViewModel(persistence) as T
}
