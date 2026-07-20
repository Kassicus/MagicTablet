package com.magictablet.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameViewModel
import com.magictablet.game.ui.SeatLayout

@Composable
fun GameScreen(viewModel: GameViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentDeltas by viewModel.recentDeltas.collectAsStateWithLifecycle()

    SeatLayout(
        players = state.players,
        recentDeltas = recentDeltas,
        onAdjustLife = viewModel::adjustLife,
        onClearDelta = viewModel::clearRecentDelta,
        modifier = Modifier.fillMaxSize(),
    )
}
