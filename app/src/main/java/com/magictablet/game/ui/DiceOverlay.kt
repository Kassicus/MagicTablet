package com.magictablet.game.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magictablet.game.flipCoin
import com.magictablet.game.rollDie

private enum class RollKind(val label: String) { Coin("Coin"), D6("d6"), D20("d20") }

@Composable
fun DiceOverlay(onDismiss: () -> Unit) {
    var lastKind by remember { mutableStateOf<RollKind?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    fun roll(kind: RollKind) {
        lastKind = kind
        result = when (kind) {
            RollKind.Coin -> "Coin: ${flipCoin()}"
            RollKind.D6 -> "d6: ${rollDie(6)}"
            RollKind.D20 -> "d20: ${rollDie(20)}"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dice & coin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RollKind.entries.forEach { kind ->
                        OutlinedButton(onClick = { roll(kind) }) { Text(kind.label) }
                    }
                }
                Text(
                    text = result ?: "Pick one to roll",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally),
                )
            }
        },
        confirmButton = {
            Button(onClick = { lastKind?.let { roll(it) } }, enabled = lastKind != null) {
                Text("Roll again")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
