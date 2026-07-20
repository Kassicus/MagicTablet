package com.magictablet.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.cards.CardDetail
import com.magictablet.cards.CardSummary
import com.magictablet.cards.CardsViewModel

@Composable
fun CardsScreen(viewModel: CardsViewModel = viewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()

    BackHandler(enabled = selected != null) { viewModel.closeDetail() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text("Search cards") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { viewModel.onQueryChange("") }) { Text("Clear") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        val detail = selected
        when {
            detail != null -> CardDetailView(detail, onBack = viewModel::closeDetail)
            !ready -> Hint("Preparing card database…")
            query.isBlank() -> RecentList(recent, viewModel::openCard)
            else -> ResultsList(results, viewModel::openCard)
        }
    }
}

@Composable
private fun ResultsList(items: List<CardSummary>, onOpen: (String) -> Unit) {
    if (items.isEmpty()) { Hint("No matches"); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.oracleId }) { CardRow(it, onOpen) }
    }
}

@Composable
private fun RecentList(items: List<CardSummary>, onOpen: (String) -> Unit) {
    if (items.isEmpty()) { Hint("Search for a card"); return }
    Column(Modifier.fillMaxSize()) {
        Text("Recently viewed", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn { items(items, key = { it.oracleId }) { CardRow(it, onOpen) } }
    }
}

@Composable
private fun CardRow(card: CardSummary, onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onOpen(card.oracleId) }.padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(card.name, style = MaterialTheme.typography.bodyLarge)
            if (card.manaCost.isNotEmpty()) Text(card.manaCost, style = MaterialTheme.typography.bodyMedium)
        }
        if (card.typeLine.isNotEmpty()) {
            Text(card.typeLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CardDetailView(detail: CardDetail, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text(detail.name, style = MaterialTheme.typography.headlineSmall)
        if (detail.manaCost.isNotEmpty()) Text(detail.manaCost, style = MaterialTheme.typography.bodyLarge)
        if (detail.typeLine.isNotEmpty()) Text(detail.typeLine, style = MaterialTheme.typography.bodyMedium)
        if (detail.oracleText.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(detail.oracleText, style = MaterialTheme.typography.bodyMedium)
        }
        if (detail.keywords.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Keywords: ${detail.keywords.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        }
        if (detail.rulings.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Rulings", style = MaterialTheme.typography.titleMedium)
            detail.rulings.forEach { r ->
                Spacer(Modifier.height(6.dp))
                Text(r.publishedAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(r.text, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}
