package com.magictablet.screens

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameViewModel
import com.magictablet.game.ui.DiceOverlay
import com.magictablet.game.ui.NewGameSheet
import com.magictablet.game.ui.SeatLayout
import com.magictablet.game.ui.TimerChip
import com.magictablet.rules.CrReader
import com.magictablet.rules.CrViewModel

@Composable
fun GameScreen(
    viewModel: GameViewModel = viewModel(),
    crViewModel: CrViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentDeltas by viewModel.recentDeltas.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showNewGame by remember { mutableStateOf(false) }
    var showDice by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }

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

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (timer.running || timer.elapsedSeconds > 0) {
                TimerChip(elapsedSeconds = timer.elapsedSeconds, running = timer.running)
            }
            Box {
                FilledTonalButton(onClick = { menuOpen = true }) { Text("⚙") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Advance turn") }, onClick = { menuOpen = false; viewModel.advanceTurn() })
                    DropdownMenuItem(text = { Text("Random first player") }, onClick = { menuOpen = false; viewModel.randomFirstPlayer() })
                    DropdownMenuItem(text = { Text("Dice & coin") }, onClick = { menuOpen = false; showDice = true })
                    DropdownMenuItem(
                        text = { Text(if (timer.running) "Pause timer" else "Start timer") },
                        onClick = { menuOpen = false; if (timer.running) viewModel.pauseTimer() else viewModel.startTimer() },
                    )
                    DropdownMenuItem(text = { Text("Reset timer") }, onClick = { menuOpen = false; viewModel.resetTimer() })
                    DropdownMenuItem(text = { Text("Comprehensive Rules") }, onClick = { menuOpen = false; showRules = true })
                    DropdownMenuItem(text = { Text("New game") }, onClick = { menuOpen = false; showNewGame = true })
                    DropdownMenuItem(text = { Text("Relinquish device owner") }, onClick = { menuOpen = false; relinquishDeviceOwner(context) })
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
    if (showRules) {
        CrReader(viewModel = crViewModel, onClose = { showRules = false })
    }
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

private fun relinquishDeviceOwner(context: Context) {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val pkg = context.packageName
    val message = if (dpm.isDeviceOwnerApp(pkg)) {
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(pkg)
        "Device owner relinquished"
    } else {
        "Not device owner"
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
