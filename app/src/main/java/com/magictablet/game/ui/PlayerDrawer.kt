package com.magictablet.game.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.magictablet.game.COMMANDER_DAMAGE_LOSS
import com.magictablet.game.POISON_LOSS
import com.magictablet.game.PlayerState
import com.magictablet.ui.theme.SeatColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerDrawer(
    player: PlayerState,
    opponents: List<PlayerState>,
    isMonarch: Boolean,
    onAdjustPoison: (delta: Int) -> Unit,
    onAdjustCommanderDamage: (fromOpponentId: Int, delta: Int) -> Unit,
    onAdjustCounter: (counter: String, delta: Int) -> Unit,
    onBecomeMonarch: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CounterRow("Poison", player.poison, warn = player.poison >= POISON_LOSS) { onAdjustPoison(it) }

            Text("Cmdr dmg (21 = loss)", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                opponents.forEach { opp ->
                    val dmg = player.commanderDamage[opp.id] ?: 0
                    OpponentChip(
                        name = opp.name,
                        color = SeatColors[opp.colorIndex % SeatColors.size],
                        damage = dmg,
                        warn = dmg >= COMMANDER_DAMAGE_LOSS,
                        onMinus = { onAdjustCommanderDamage(opp.id, -1) },
                        onPlus = { onAdjustCommanderDamage(opp.id, 1) },
                    )
                }
            }

            CounterRow("Energy", player.counters["energy"] ?: 0) { onAdjustCounter("energy", it) }
            CounterRow("Exp", player.counters["experience"] ?: 0) { onAdjustCounter("experience", it) }

            OutlinedButton(onClick = onBecomeMonarch, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (isMonarch) "Renounce monarch 👑" else "Become the monarch 👑")
            }
            TextButton(onClick = onCollapse, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Collapse")
            }
        }
    }
}

@Composable
private fun CounterRow(label: String, value: Int, warn: Boolean = false, onAdjust: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(value.toString(), fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal)
        OutlinedButton(onClick = { onAdjust(-1) }) { Text("-") }
        OutlinedButton(onClick = { onAdjust(1) }) { Text("+") }
    }
}

@Composable
private fun OpponentChip(
    name: String,
    color: Color,
    damage: Int,
    warn: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Surface(tonalElevation = 1.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(name, color = color, fontWeight = FontWeight.SemiBold)
            Text(damage.toString(), fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal)
            TextButton(onClick = onMinus, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("-") }
            TextButton(onClick = onPlus, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("+") }
        }
    }
}
