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

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CardsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = CardDb(app)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _selected = MutableStateFlow<CardDetail?>(null)
    val selected: StateFlow<CardDetail?> = _selected.asStateFlow()

    private val _recent = MutableStateFlow<List<CardSummary>>(emptyList())
    val recent: StateFlow<List<CardSummary>> = _recent.asStateFlow()

    val results: StateFlow<List<CardSummary>> =
        combine(
            _query.debounce(250).map { it.trim() }.distinctUntilChanged(),
            _ready,
        ) { q, ready -> q to ready }
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

    fun onQueryChange(text: String) {
        _query.value = text
    }

    fun openCard(oracleId: String) {
        viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) { db.card(oracleId) } ?: return@launch
            _selected.value = detail
            val summary = CardSummary(detail.oracleId, detail.name, detail.manaCost, detail.typeLine)
            _recent.value = (listOf(summary) + _recent.value.filterNot { it.oracleId == summary.oracleId }).take(15)
        }
    }

    fun closeDetail() {
        _selected.value = null
    }
}
