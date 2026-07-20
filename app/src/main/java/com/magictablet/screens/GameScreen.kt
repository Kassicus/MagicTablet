package com.magictablet.screens

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameViewModel
import com.magictablet.game.ui.NewGameSheet
import com.magictablet.game.ui.SeatLayout

@Composable
fun GameScreen(viewModel: GameViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentDeltas by viewModel.recentDeltas.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showNewGame by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        SeatLayout(
            players = state.players,
            recentDeltas = recentDeltas,
            onAdjustLife = viewModel::adjustLife,
            onClearDelta = viewModel::clearRecentDelta,
            onAdjustPoison = viewModel::adjustPoison,
            onAdjustCommanderDamage = viewModel::adjustCommanderDamage,
            onAdjustCounter = viewModel::adjustCounter,
            modifier = Modifier.fillMaxSize(),
        )

        Box(Modifier.align(Alignment.Center)) {
            FilledTonalButton(onClick = { menuOpen = true }) { Text("⚙") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("New game") },
                    onClick = { menuOpen = false; showNewGame = true },
                )
                DropdownMenuItem(
                    text = { Text("Relinquish device owner") },
                    onClick = { menuOpen = false; relinquishDeviceOwner(context) },
                )
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
