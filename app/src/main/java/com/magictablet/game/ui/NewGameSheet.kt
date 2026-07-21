package com.magictablet.game.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.magictablet.game.GAME_FORMATS

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewGameSheet(
    currentCount: Int,
    currentLife: Int,
    onStart: (playerCount: Int, startingLife: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var count by remember { mutableStateOf(currentCount) }
    var lifeText by remember { mutableStateOf(currentLife.toString()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New game")

            Text("Format")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GAME_FORMATS.forEach { format ->
                    FilterChip(
                        selected = count == format.playerCount && lifeText == format.startingLife.toString(),
                        onClick = { count = format.playerCount; lifeText = format.startingLife.toString() },
                        label = { Text(format.name) },
                    )
                }
            }

            Text("Players")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (2..6).forEach { n ->
                    FilterChip(selected = count == n, onClick = { count = n }, label = { Text("$n") })
                }
            }

            Text("Starting life")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(40, 25, 20).forEach { preset ->
                    FilterChip(
                        selected = lifeText == preset.toString(),
                        onClick = { lifeText = preset.toString() },
                        label = { Text("$preset") },
                    )
                }
            }
            OutlinedTextField(
                value = lifeText,
                onValueChange = { new -> lifeText = new.filter { it.isDigit() }.take(3) },
                label = { Text("Custom life") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = {
                    val life = (lifeText.toIntOrNull() ?: 40).coerceIn(1, 999)
                    onStart(count, life)
                }) { Text("Start") }
            }
        }
    }
}
