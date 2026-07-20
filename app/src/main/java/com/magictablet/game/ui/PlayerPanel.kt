package com.magictablet.game.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    recentDelta: RecentDelta?,
    onAdjustLife: (playerId: Int, delta: Int) -> Unit,
    onClearDelta: (playerId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = SeatColors[player.colorIndex % SeatColors.size]

    // Clear the accumulated delta 1s after the last change (restarts on each new change via token key).
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
        // Tap zones (bottom layer): left = -1, right = +1.
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .holdRepeatClick { onAdjustLife(player.id, -1) },
            )
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .holdRepeatClick { onAdjustLife(player.id, 1) },
            )
        }

        // Center overlay (not hittable → taps fall through to the zones).
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = player.life.toString(),
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // Recent delta, fades out when cleared.
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
    }
}
