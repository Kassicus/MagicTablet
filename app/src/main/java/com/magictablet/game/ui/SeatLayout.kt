package com.magictablet.game.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import com.magictablet.game.PlayerState
import com.magictablet.game.RecentDelta
import com.magictablet.game.seatSplit

@Composable
fun SeatLayout(
    players: List<PlayerState>,
    recentDeltas: Map<Int, RecentDelta>,
    activePlayerId: Int?,
    monarchPlayerId: Int?,
    onAdjustLife: (playerId: Int, delta: Int) -> Unit,
    onClearDelta: (playerId: Int) -> Unit,
    onAdjustPoison: (playerId: Int, delta: Int) -> Unit,
    onAdjustCommanderDamage: (playerId: Int, fromOpponentId: Int, delta: Int) -> Unit,
    onAdjustCounter: (playerId: Int, counter: String, delta: Int) -> Unit,
    onToggleMonarch: (playerId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (topCount, _) = seatSplit(players.size)
    val topPlayers = players.take(topCount)
    val bottomPlayers = players.drop(topCount)

    @Composable
    fun panel(p: PlayerState, mod: Modifier) {
        PlayerPanel(
            player = p,
            opponents = players.filter { it.id != p.id },
            recentDelta = recentDeltas[p.id],
            isActive = p.id == activePlayerId,
            anyActive = activePlayerId != null,
            isMonarch = p.id == monarchPlayerId,
            onAdjustLife = onAdjustLife,
            onClearDelta = onClearDelta,
            onAdjustPoison = onAdjustPoison,
            onAdjustCommanderDamage = onAdjustCommanderDamage,
            onAdjustCounter = onAdjustCounter,
            onToggleMonarch = onToggleMonarch,
            modifier = mod,
        )
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            topPlayers.forEach { p -> key(p.id) { panel(p, Modifier.weight(1f).rotate(180f)) } }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            bottomPlayers.forEach { p -> key(p.id) { panel(p, Modifier.weight(1f)) } }
        }
    }
}
