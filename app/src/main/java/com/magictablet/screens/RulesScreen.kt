package com.magictablet.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.rules.CrSyncProgress
import com.magictablet.rules.CrSyncUiState
import com.magictablet.rules.CrView
import com.magictablet.rules.CrViewModel

@Composable
fun RulesScreen(viewModel: CrViewModel = viewModel()) {
    val hasRules by viewModel.hasRules.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val url by viewModel.url.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val glossary by viewModel.glossary.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Comprehensive Rules", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        when {
            syncState !is CrSyncUiState.Idle ->
                UpdatePanel(syncState, url, viewModel::setUrl, viewModel::startUpdate, viewModel::dismissSync)
            !hasRules ->
                LoadPrompt(url, viewModel::setUrl, viewModel::startUpdate)
            else ->
                BrowsePanel(view, glossary, viewModel::drillTo, viewModel::up, viewModel::jumpTo, viewModel::lookupGlossary, viewModel::clearGlossary, viewModel::startUpdate)
        }
    }
}

@Composable
private fun LoadPrompt(url: String, onUrl: (String) -> Unit, onUpdate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Comprehensive Rules aren't loaded yet. Connect to WiFi and update.")
        UrlField(url, onUrl)
        Button(onClick = onUpdate) { Text("Update rules") }
    }
}

@Composable
private fun UpdatePanel(state: CrSyncUiState, url: String, onUrl: (String) -> Unit, onUpdate: () -> Unit, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is CrSyncUiState.Running -> when (val p = state.progress) {
                is CrSyncProgress.Connecting -> Text("Connecting…")
                is CrSyncProgress.Downloading -> {
                    Text("Downloading rules…")
                    if (p.total > 0) LinearProgressIndicator(progress = { (p.bytes.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is CrSyncProgress.Parsing -> { Text("Parsing… ${p.rules} rules"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                is CrSyncProgress.Finalizing -> Text("Finalizing…")
            }
            is CrSyncUiState.Done -> {
                Text("Loaded ${state.rules} rules, ${state.terms} glossary terms.")
                TextButton(onClick = onDone) { Text("Done") }
            }
            is CrSyncUiState.Error -> {
                Text("Update failed: ${state.message}")
                UrlField(url, onUrl)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onUpdate) { Text("Retry") }
                    TextButton(onClick = onDone) { Text("Close") }
                }
            }
            is CrSyncUiState.Idle -> {}
        }
    }
}

@Composable
private fun BrowsePanel(
    view: CrView,
    glossary: Pair<String, String?>?,
    onDrill: (String) -> Unit,
    onUp: () -> Unit,
    onJump: (String) -> Unit,
    onGlossary: (String) -> Unit,
    onClearGlossary: () -> Unit,
    onUpdate: () -> Unit,
) {
    var jump by remember { mutableStateOf("") }
    var term by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onUp) { Text("‹ Up") }
            OutlinedTextField(value = jump, onValueChange = { jump = it }, placeholder = { Text("Jump to rule #") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (jump.isNotBlank()) onJump(jump) }) { Text("Go") }
            TextButton(onClick = onUpdate) { Text("Update") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = term, onValueChange = { term = it }, placeholder = { Text("Glossary term") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (term.isNotBlank()) onGlossary(term) }) { Text("Define") }
        }
        if (glossary != null) {
            val (t, def) = glossary
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(t, style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = onClearGlossary) { Text("Dismiss") }
                    }
                    Text(def ?: "No glossary entry for '$t'", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val current = view.current
        if (current != null) {
            Text(current.number, style = MaterialTheme.typography.titleMedium)
            Text(current.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(view.children, key = { it.number }) { r ->
                Column(Modifier.fillMaxWidth().clickable { onDrill(r.number) }.padding(vertical = 8.dp)) {
                    Text(r.number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(r.text, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
            }
        }
    }
}

@Composable
private fun UrlField(url: String, onUrl: (String) -> Unit) {
    OutlinedTextField(value = url, onValueChange = onUrl, label = { Text("Comprehensive Rules .txt URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
