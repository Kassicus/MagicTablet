package com.magictablet.game.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import com.magictablet.game.PlayerState
import com.magictablet.game.RecentDelta
import com.magictablet.game.seatSplit

@Composable
fun SeatLayout(
    players: List<PlayerState>,
    recentDeltas: Map<Int, RecentDelta>,
    onAdjustLife: (playerId: Int, delta: Int) -> Unit,
    onClearDelta: (playerId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (topCount, _) = seatSplit(players.size)
    val topPlayers = players.take(topCount)
    val bottomPlayers = players.drop(topCount)

    Column(modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            topPlayers.forEach { p ->
                PlayerPanel(
                    player = p,
                    recentDelta = recentDeltas[p.id],
                    onAdjustLife = onAdjustLife,
                    onClearDelta = onClearDelta,
                    modifier = Modifier.weight(1f).rotate(180f),
                )
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            bottomPlayers.forEach { p ->
                PlayerPanel(
                    player = p,
                    recentDelta = recentDeltas[p.id],
                    onAdjustLife = onAdjustLife,
                    onClearDelta = onClearDelta,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
