package com.magictablet.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CrSyncUiState {
    data object Idle : CrSyncUiState
    data class Running(val progress: CrSyncProgress) : CrSyncUiState
    data class Done(val rules: Int, val terms: Int) : CrSyncUiState
    data class Error(val message: String) : CrSyncUiState
}

/** current == null means the root (the 1..9 sections in [children]). */
data class CrView(val current: CrRule?, val children: List<CrRule>)

class CrViewModel(app: Application) : AndroidViewModel(app) {
    private val db = CrDb(app)
    private val crSync = CrSync(app)
    private val prefs = app.getSharedPreferences("magictablet", Application.MODE_PRIVATE)

    private val _url = MutableStateFlow(prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL)
    val url: StateFlow<String> = _url.asStateFlow()

    private val _hasRules = MutableStateFlow(false)
    val hasRules: StateFlow<Boolean> = _hasRules.asStateFlow()

    private val _view = MutableStateFlow(CrView(null, emptyList()))
    val view: StateFlow<CrView> = _view.asStateFlow()

    private val _syncState = MutableStateFlow<CrSyncUiState>(CrSyncUiState.Idle)
    val syncState: StateFlow<CrSyncUiState> = _syncState.asStateFlow()

    private val backStack = ArrayDeque<String?>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            db.prepare()
            _hasRules.value = db.hasRules()
            refreshView(null)
        }
    }

    /** Read the DB for [number] (null = root) and publish. Call on IO. */
    private fun refreshView(number: String?) {
        val current = number?.let { db.rule(it) }
        val kids = if (number == null) db.roots() else db.children(number)
        _view.value = CrView(current, kids)
    }

    fun open() = go(null, clearBack = true)
    fun openAt(number: String) = go(number, clearBack = true)
    fun jumpTo(number: String) = go(number.trim(), clearBack = true)

    fun drillTo(number: String) {
        backStack.addLast(_view.value.current?.number)
        go(number, clearBack = false)
    }

    fun up() {
        viewModelScope.launch(Dispatchers.IO) {
            val prev = if (backStack.isNotEmpty()) backStack.removeLast()
            else _view.value.current?.let { db.rule(it.number)?.parent }
            refreshView(prev)
        }
    }

    private fun go(number: String?, clearBack: Boolean) {
        if (clearBack) backStack.clear()
        viewModelScope.launch(Dispatchers.IO) { refreshView(number) }
    }

    fun setUrl(newUrl: String) {
        _url.value = newUrl
        prefs.edit().putString(KEY_URL, newUrl).apply()
    }

    fun startUpdate() {
        if (_syncState.value is CrSyncUiState.Running) return
        _syncState.value = CrSyncUiState.Running(CrSyncProgress.Connecting)
        viewModelScope.launch(Dispatchers.IO) {
            when (val r = crSync.sync(_url.value) { p -> _syncState.value = CrSyncUiState.Running(p) }) {
                is CrSyncResult.Success -> {
                    db.reopen()
                    _hasRules.value = db.hasRules()
                    backStack.clear()
                    refreshView(null)
                    _syncState.value = CrSyncUiState.Done(r.rules, r.terms)
                }
                is CrSyncResult.Error -> _syncState.value = CrSyncUiState.Error(r.message)
            }
        }
    }

    fun dismissSync() { _syncState.value = CrSyncUiState.Idle }

    companion object {
        private const val KEY_URL = "cr_url"
        const val DEFAULT_URL = "https://media.wizards.com/2026/downloads/MagicCompRules%2020260227.txt"
    }
}
