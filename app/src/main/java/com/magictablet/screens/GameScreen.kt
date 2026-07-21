package com.magictablet.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameViewModel
import com.magictablet.game.ui.DiceOverlay
import com.magictablet.game.ui.NewGameSheet
import com.magictablet.game.ui.SeatLayout
import com.magictablet.game.ui.TimerChip
import com.magictablet.kiosk.findActivity
import com.magictablet.kiosk.releaseKiosk

@Composable
fun GameScreen(viewModel: GameViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentDeltas by viewModel.recentDeltas.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var panelOpen by remember { mutableStateOf(false) }
    var showNewGame by remember { mutableStateOf(false) }
    var showDice by remember { mutableStateOf(false) }
    var showExitKiosk by remember { mutableStateOf(false) }

    LaunchedEffectTick(timer.running, viewModel)

    Box(Modifier.fillMaxSize()) {
        SeatLayout(
            players = state.players,
            recentDeltas = recentDeltas,
            activePlayerId = state.activePlayerId,
            monarchPlayerId = state.monarchPlayerId,
            onAdjustLife = viewModel::adjustLife,
            onClearDelta = viewModel::clearRecentDelta,
            onAdjustPoison = viewModel::adjustPoison,
            onAdjustCommanderDamage = viewModel::adjustCommanderDamage,
            onAdjustCounter = viewModel::adjustCounter,
            onToggleMonarch = viewModel::toggleMonarch,
            modifier = Modifier.fillMaxSize(),
        )

        // Center: timer chip (when active) + the Pass-turn button.
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (timer.running || timer.elapsedSeconds > 0) {
                TimerChip(elapsedSeconds = timer.elapsedSeconds, running = timer.running)
            }
            Button(onClick = { viewModel.advanceTurn() }) { Text("Pass turn") }
        }

        // Left-edge handle that opens the side panel.
        Surface(
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp)
                .size(width = 28.dp, height = 120.dp)
                .clickable { panelOpen = true },
            tonalElevation = 4.dp,
            shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("☰") }
        }

        // Scrim (tap to close) behind the sliding panel.
        if (panelOpen) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { panelOpen = false },
            )
        }

        // Left slide-out panel.
        AnimatedVisibility(
            visible = panelOpen,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) {
            Surface(
                modifier = Modifier.fillMaxHeight().width(280.dp),
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Table", style = MaterialTheme.typography.titleMedium)
                    PanelButton("Random first player") { viewModel.randomFirstPlayer(); panelOpen = false }
                    PanelButton("Dice & coin") { showDice = true; panelOpen = false }
                    PanelButton(if (timer.running) "Pause timer" else "Start timer") {
                        if (timer.running) viewModel.pauseTimer() else viewModel.startTimer()
                        panelOpen = false
                    }
                    PanelButton("Reset timer") { viewModel.resetTimer(); panelOpen = false }
                    PanelButton("New game") { showNewGame = true; panelOpen = false }
                    Spacer(Modifier.weight(1f))
                    PanelButton("Exit kiosk mode") { showExitKiosk = true; panelOpen = false }
                }
            }
        }
    }

    if (showNewGame) {
        NewGameSheet(
            currentCount = state.players.size,
            currentLife = state.startingLife,
            onStart = { count, life -> viewModel.newGame(count, life); showNewGame = false },
            onDismiss = { showNewGame = false },
        )
    }
    if (showDice) {
        DiceOverlay(onDismiss = { showDice = false })
    }
    if (showExitKiosk) {
        AlertDialog(
            onDismissRequest = { showExitKiosk = false },
            title = { Text("Exit kiosk mode?") },
            text = { Text("This unlocks the tablet and removes MTG Table as device owner, so players can leave the app. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitKiosk = false
                    val message = releaseKiosk(context.findActivity())
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }) { Text("Exit kiosk") }
            },
            dismissButton = {
                TextButton(onClick = { showExitKiosk = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun LaunchedEffectTick(running: Boolean, viewModel: GameViewModel) {
    androidx.compose.runtime.LaunchedEffect(running) {
        while (running) {
            kotlinx.coroutines.delay(1000)
            viewModel.tickTimer()
        }
    }
}
