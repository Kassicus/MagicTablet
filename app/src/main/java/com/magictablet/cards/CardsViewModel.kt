package com.magictablet.cards

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SyncUiState {
    data object Idle : SyncUiState
    data class Running(val progress: SyncProgress) : SyncUiState
    data class Done(val cards: Int, val rulings: Int) : SyncUiState
    data class Error(val message: String) : SyncUiState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CardsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = CardDb(app)
    private val cardSync = CardSync(app)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _selected = MutableStateFlow<CardDetail?>(null)
    val selected: StateFlow<CardDetail?> = _selected.asStateFlow()

    private val _recent = MutableStateFlow<List<CardSummary>>(emptyList())
    val recent: StateFlow<List<CardSummary>> = _recent.asStateFlow()

    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    private val _refresh = MutableStateFlow(0)

    val results: StateFlow<List<CardSummary>> =
        combine(
            _query.debounce(250).map { it.trim() }.distinctUntilChanged(),
            _ready,
            _refresh,
        ) { q, ready, _ -> q to ready }
            .flatMapLatest { (q, ready) ->
                if (!ready || q.isEmpty()) flowOf(emptyList())
                else flow { emit(db.search(q)) }.flowOn(Dispatchers.IO)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            db.prepare()
            _ready.value = true
        }
    }

    fun onQueryChange(text: String) { _query.value = text }

    fun openCard(oracleId: String) {
        viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) { db.card(oracleId) } ?: return@launch
            _selected.value = detail
            val summary = CardSummary(detail.oracleId, detail.name, detail.manaCost, detail.typeLine)
            _recent.value = (listOf(summary) + _recent.value.filterNot { it.oracleId == summary.oracleId }).take(15)
        }
    }

    fun closeDetail() { _selected.value = null }

    suspend fun getDetail(oracleId: String): CardDetail? =
        withContext(Dispatchers.IO) { db.card(oracleId) }

    suspend fun searchCards(query: String): List<CardSummary> =
        withContext(Dispatchers.IO) { db.search(query) }

    fun startSync() {
        if (_syncState.value is SyncUiState.Running) return
        _syncState.value = SyncUiState.Running(SyncProgress.Connecting)
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = cardSync.sync { p -> _syncState.value = SyncUiState.Running(p) }) {
                is SyncResult.Success -> {
                    db.reopen()
                    _refresh.value += 1
                    _syncState.value = SyncUiState.Done(result.cards, result.rulings)
                }
                is SyncResult.Error -> _syncState.value = SyncUiState.Error(result.message)
            }
        }
    }

    fun dismissSync() { _syncState.value = SyncUiState.Idle }
}
