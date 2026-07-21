package com.magictablet.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.cards.CardDetail
import com.magictablet.cards.CardSummary
import com.magictablet.cards.CardsViewModel
import com.magictablet.game.GameViewModel
import com.magictablet.game.PlayerState
import com.magictablet.game.RESOLUTION_PROCEDURE
import com.magictablet.game.StackItem
import com.magictablet.game.StackKind
import com.magictablet.game.stackHints
import com.magictablet.ui.theme.SeatColors

@Composable
fun StackScreen(
    gameViewModel: GameViewModel = viewModel(),
    cardsViewModel: CardsViewModel = viewModel(),
    onOpenRule: (String) -> Unit = {},
) {
    val state by gameViewModel.state.collectAsStateWithLifecycle()
    val lastResolved by gameViewModel.lastResolved.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    val players = state.players
    fun playerOf(id: Int): PlayerState? = players.firstOrNull { it.id == id }
    fun colorOf(p: PlayerState?) = p?.let { SeatColors[it.colorIndex % SeatColors.size] }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Stack", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.stack.isNotEmpty()) TextButton(onClick = { gameViewModel.clearStack() }) { Text("Clear") }
                Button(onClick = { showAdd = true }) { Text("Add") }
            }
        }

        val priorityId = state.priorityPlayerId
        if (priorityId != null) {
            val p = playerOf(priorityId)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Priority: ${p?.name ?: "P$priorityId"}", color = colorOf(p) ?: MaterialTheme.colorScheme.onBackground)
                TextButton(onClick = { gameViewModel.passPriority() }) { Text("Pass") }
                TextButton(onClick = { gameViewModel.resolveTop() }) { Text("Resolve top") }
            }
        }

        if (state.stack.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Stack is empty — add a spell or ability.") }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.stack.reversed(), key = { it.id }) { item ->
                    StackItemRow(item, colorOf(playerOf(item.controllerId)), cardsViewModel) { gameViewModel.removeStackItem(item.id) }
                }
            }
        }
    }

    if (showAdd) {
        AddStackSheet(players, cardsViewModel,
            onAdd = { c, k, oid, label, targets -> gameViewModel.addToStack(c, k, oid, label, targets); showAdd = false },
            onDismiss = { showAdd = false })
    }

    lastResolved?.let { resolved ->
        GuidancePanel(resolved, cardsViewModel,
            onOpenRule = onOpenRule,
            onClose = { gameViewModel.clearLastResolved() })
    }
}

@Composable
private fun StackItemRow(item: StackItem, accent: androidx.compose.ui.graphics.Color?, cardsViewModel: CardsViewModel, onRemove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var detail by remember(item.id) { mutableStateOf<CardDetail?>(null) }
    LaunchedEffect(expanded, item.cardOracleId) {
        if (expanded && item.cardOracleId != null && detail == null) detail = cardsViewModel.getDetail(item.cardOracleId)
    }
    val dot = accent ?: MaterialTheme.colorScheme.outline
    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("●", color = dot)
                    Text(item.kind.name, style = MaterialTheme.typography.labelSmall, color = dot)
                    Text(item.label, style = MaterialTheme.typography.bodyLarge)
                }
                Row {
                    if (item.cardOracleId != null) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Text") }
                    TextButton(onClick = onRemove) { Text("×") }
                }
            }
            if (item.targets.isNotBlank()) Text("Targets: ${item.targets}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                val d = detail
                if (d == null) Text("Loading…", style = MaterialTheme.typography.bodySmall)
                else {
                    if (d.oracleText.isNotEmpty()) Text(d.oracleText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    if (d.rulings.isNotEmpty()) {
                        Text("Rulings", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                        d.rulings.take(8).forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddStackSheet(
    players: List<PlayerState>,
    cardsViewModel: CardsViewModel,
    onAdd: (Int, StackKind, String?, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var controllerId by remember { mutableStateOf(players.firstOrNull()?.id) }
    var kind by remember { mutableStateOf(StackKind.Spell) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CardSummary>>(emptyList()) }
    var linked by remember { mutableStateOf<CardSummary?>(null) }
    var freeText by remember { mutableStateOf("") }
    var targets by remember { mutableStateOf("") }

    LaunchedEffect(query, linked) {
        if (query.isBlank() || linked != null) {
            results = emptyList()
        } else {
            kotlinx.coroutines.delay(250)
            results = cardsViewModel.searchCards(query)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add to stack", style = MaterialTheme.typography.titleMedium)

            Text("Controller")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                players.forEach { p -> FilterChip(selected = controllerId == p.id, onClick = { controllerId = p.id }, label = { Text(p.name) }) }
            }
            Text("Kind")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StackKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.name) }) }
            }

            if (linked == null) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search a card (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                results.take(6).forEach { c ->
                    Text(c.name, modifier = Modifier.fillMaxWidth().clickable { linked = c; query = "" }.padding(vertical = 6.dp))
                }
                OutlinedTextField(value = freeText, onValueChange = { freeText = it }, label = { Text("…or type the ability") }, modifier = Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Card: ${linked!!.name}", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { linked = null }) { Text("Change") }
                }
            }

            OutlinedTextField(value = targets, onValueChange = { targets = it }, label = { Text("Targets (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                val cid = controllerId
                val label = linked?.name ?: freeText.trim()
                Button(enabled = cid != null && label.isNotEmpty(), onClick = { onAdd(cid!!, kind, linked?.oracleId, label, targets.trim()) }) { Text("Add") }
            }
        }
    }
}

@Composable
private fun GuidancePanel(item: StackItem, cardsViewModel: CardsViewModel, onOpenRule: (String) -> Unit, onClose: () -> Unit) {
    var detail by remember(item.id) { mutableStateOf<CardDetail?>(null) }
    LaunchedEffect(item.id) { if (item.cardOracleId != null) detail = cardsViewModel.getDetail(item.cardOracleId) }
    val hints = stackHints(item, detail?.oracleText, detail?.keywords ?: emptyList())

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Resolving: ${item.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                detail?.let { d ->
                    if (d.oracleText.isNotEmpty()) {
                        Text("Oracle text", style = MaterialTheme.typography.labelLarge)
                        Text(d.oracleText, style = MaterialTheme.typography.bodySmall)
                    }
                    if (d.rulings.isNotEmpty()) {
                        Text("Rulings", style = MaterialTheme.typography.labelLarge)
                        d.rulings.take(6).forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Text("Procedure", style = MaterialTheme.typography.labelLarge)
                Text(RESOLUTION_PROCEDURE, style = MaterialTheme.typography.bodySmall)
                Text("Rules hints", style = MaterialTheme.typography.labelLarge)
                hints.forEach { h ->
                    Text(
                        "${h.label} — CR ${h.ruleNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable { onOpenRule(h.ruleNumber) }.padding(vertical = 2.dp),
                    )
                }
            }
        },
    )
}
