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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magictablet.game.PlayerState
import com.magictablet.game.RecentDelta
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
            // Tap zones (bottom layer).
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().holdRepeatClick { onAdjustLife(player.id, -1) })
                Box(Modifier.weight(1f).fillMaxHeight().holdRepeatClick { onAdjustLife(player.id, 1) })
            }

            // Life number.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = player.life.toString(),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            // Recent delta (top-center), fades on clear.
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

            // Collapsed badges (top-start): poison / commander damage.
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

            // Chevron to open the drawer (bottom-center); consumes its own tap area.
            TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Text("▴ counters", fontSize = 13.sp, color = accent)
            }
        }
    }
}
