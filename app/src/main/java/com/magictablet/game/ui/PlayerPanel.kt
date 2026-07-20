package com.magictablet.game.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magictablet.game.PlayerState
import com.magictablet.game.RecentDelta
import com.magictablet.game.isLost
import com.magictablet.game.lossReason
import com.magictablet.ui.theme.SeatColors

@Composable
fun PlayerPanel(
    player: PlayerState,
    opponents: List<PlayerState>,
    recentDelta: RecentDelta?,
    onAdjustLife: (playerId: Int, delta: Int) -> Unit,
    onClearDelta: (playerId: Int) -> Unit,
    onAdjustPoison: (playerId: Int, delta: Int) -> Unit,
    onAdjustCommanderDamage: (playerId: Int, fromOpponentId: Int, delta: Int) -> Unit,
    onAdjustCounter: (playerId: Int, counter: String, delta: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = SeatColors[player.colorIndex % SeatColors.size]
    var expanded by remember { mutableStateOf(false) }
    val lost = player.isLost()

    LaunchedEffect(recentDelta?.token) {
        if (recentDelta != null) {
            kotlinx.coroutines.delay(1000)
            onClearDelta(player.id)
        }
    }

    Box(
        modifier = modifier
            .padding(4.dp)
            .border(2.dp, accent, RoundedCornerShape(12.dp)),
    ) {
        if (expanded) {
            PlayerDrawer(
                player = player,
                opponents = opponents,
                onAdjustPoison = { onAdjustPoison(player.id, it) },
                onAdjustCommanderDamage = { oppId, d -> onAdjustCommanderDamage(player.id, oppId, d) },
                onAdjustCounter = { c, d -> onAdjustCounter(player.id, c, d) },
                onCollapse = { expanded = false },
            )
        } else {
            // Tap zones stay active even when lost (life can recover).
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().holdRepeatClick { onAdjustLife(player.id, -1) })
                Box(Modifier.weight(1f).fillMaxHeight().holdRepeatClick { onAdjustLife(player.id, 1) })
            }

            // Life number, dimmed when lost.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = player.life.toString(),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.alpha(if (lost) 0.4f else 1f),
                )
            }

            // Recent delta.
            Box(Modifier.fillMaxSize().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
                AnimatedVisibility(
                    visible = recentDelta != null && recentDelta.amount != 0,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    val amount = recentDelta?.amount ?: 0
                    Text(
                        text = if (amount > 0) "+$amount" else "$amount",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                }
            }

            // Loss overlay: skull + reason.
            if (lost) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.padding(top = 90.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("☠", fontSize = 28.sp, color = accent)
                        Text(player.lossReason() ?: "", fontSize = 16.sp, color = accent)
                    }
                }
            }

            // Collapsed badges.
            val hasCmd = player.commanderDamage.values.any { it > 0 }
            if (player.poison > 0 || hasCmd) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (player.poison > 0) Text("☠${player.poison}", fontSize = 14.sp, color = accent)
                    if (hasCmd) Text("CMD", fontSize = 14.sp, color = accent)
                }
            }

            TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Text("▴ counters", fontSize = 13.sp, color = accent)
            }
        }
    }
}
