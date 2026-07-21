# UI Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Game screen's center `⚙` dropdown with a left-edge slide-out panel + a center Pass-turn button, move the Comprehensive Rules reader into its own tab, and rework the monarch UX (hidden until claimed; grayed crowns for non-monarchs; claim via the counters popup).

**Architecture:** Pure UI/navigation restructuring — **no ViewModel/model/persistence changes, no new deps.** Every action already exists (`advanceTurn`, `toggleMonarch`, `randomFirstPlayer`, `newGame`, timer intents). `App` lifts the shared `CrViewModel` to host the new Rules tab and drive the Stack's CR deep-link (which now switches tabs). The CR reader Dialog becomes a full-screen tab; the old `CrReader` is deleted once both its hosts (Game menu, Stack modal) stop using it.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), existing lifecycle/coroutines.

## Global Constraints

From the spec (`docs/superpowers/specs/2026-07-20-ui-refresh-design.md`):

- **No new dependencies. No ViewModel/`GameState`/persistence changes.** UI + navigation only.
- Decisions: panel on the **LEFT** edge (handle → animated slide-in over a scrim); crowns **tappable-to-steal** (`toggleMonarch`) **plus** a counters-popup "Become the monarch" option; the Stack CR hint **jumps to the Rules tab** (`crViewModel.openAt(n)` + `screen = Screen.Rules`).
- Task order matters: Task 1 (Game) removes GameScreen's `CrReader` use; Task 2 removes the Stack's and **then deletes `rules/CrReader.kt`**. Do not delete `CrReader.kt` before Task 2.
- **Environment preamble** (every Gradle/adb command; pass `dangerouslyDisableSandbox: true`):

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
  ```
  Device serial: `TK12110626B081745`.

> **Testing:** No new pure logic → no new unit tests (the actions reuse already-tested intents). Every task confirms the unit suite stays green (`:app:testDebugUnitTest`) and verifies its UI on-device.

---

### Task 1: Game screen — left side panel + center Pass-turn button

**Files:**
- Modify (replace): `app/src/main/java/com/magictablet/screens/GameScreen.kt`

**Interfaces:**
- `GameScreen(viewModel: GameViewModel = viewModel())` — the `crViewModel` param is removed. `App` already calls `GameScreen(viewModel = gameViewModel)`, which still compiles. GameScreen no longer references `CrReader`/`CrViewModel`.

- [ ] **Step 1: Replace `app/src/main/java/com/magictablet/screens/GameScreen.kt`**

```kotlin
package com.magictablet.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameViewModel
import com.magictablet.game.ui.DiceOverlay
import com.magictablet.game.ui.NewGameSheet
import com.magictablet.game.ui.SeatLayout
import com.magictablet.game.ui.TimerChip
import com.magictablet.kiosk.findActivity
import com.magictablet.kiosk.releaseKiosk

@Composable
fun GameScreen(viewModel: GameViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentDeltas by viewModel.recentDeltas.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var panelOpen by remember { mutableStateOf(false) }
    var showNewGame by remember { mutableStateOf(false) }
    var showDice by remember { mutableStateOf(false) }
    var showExitKiosk by remember { mutableStateOf(false) }

    LaunchedEffectTick(timer.running, viewModel)

    Box(Modifier.fillMaxSize()) {
        SeatLayout(
            players = state.players,
            recentDeltas = recentDeltas,
            activePlayerId = state.activePlayerId,
            monarchPlayerId = state.monarchPlayerId,
            onAdjustLife = viewModel::adjustLife,
            onClearDelta = viewModel::clearRecentDelta,
            onAdjustPoison = viewModel::adjustPoison,
            onAdjustCommanderDamage = viewModel::adjustCommanderDamage,
            onAdjustCounter = viewModel::adjustCounter,
            onToggleMonarch = viewModel::toggleMonarch,
            modifier = Modifier.fillMaxSize(),
        )

        // Center: timer chip (when active) + the Pass-turn button.
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (timer.running || timer.elapsedSeconds > 0) {
                TimerChip(elapsedSeconds = timer.elapsedSeconds, running = timer.running)
            }
            Button(onClick = { viewModel.advanceTurn() }) { Text("Pass turn") }
        }

        // Left-edge handle that opens the side panel.
        Surface(
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp)
                .size(width = 28.dp, height = 120.dp)
                .clickable { panelOpen = true },
            tonalElevation = 4.dp,
            shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("☰") }
        }

        // Scrim (tap to close) behind the sliding panel.
        if (panelOpen) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { panelOpen = false },
            )
        }

        // Left slide-out panel.
        AnimatedVisibility(
            visible = panelOpen,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) {
            Surface(
                modifier = Modifier.fillMaxHeight().width(280.dp),
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Table", style = MaterialTheme.typography.titleMedium)
                    PanelButton("Random first player") { viewModel.randomFirstPlayer(); panelOpen = false }
                    PanelButton("Dice & coin") { showDice = true; panelOpen = false }
                    PanelButton(if (timer.running) "Pause timer" else "Start timer") {
                        if (timer.running) viewModel.pauseTimer() else viewModel.startTimer()
                        panelOpen = false
                    }
                    PanelButton("Reset timer") { viewModel.resetTimer(); panelOpen = false }
                    PanelButton("New game") { showNewGame = true; panelOpen = false }
                    Spacer(Modifier.weight(1f))
                    PanelButton("Exit kiosk mode") { showExitKiosk = true; panelOpen = false }
                }
            }
        }
    }

    if (showNewGame) {
        NewGameSheet(
            currentCount = state.players.size,
            currentLife = state.startingLife,
            onStart = { count, life -> viewModel.newGame(count, life); showNewGame = false },
            onDismiss = { showNewGame = false },
        )
    }
    if (showDice) {
        DiceOverlay(onDismiss = { showDice = false })
    }
    if (showExitKiosk) {
        AlertDialog(
            onDismissRequest = { showExitKiosk = false },
            title = { Text("Exit kiosk mode?") },
            text = { Text("This unlocks the tablet and removes MTG Table as device owner, so players can leave the app. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitKiosk = false
                    val message = releaseKiosk(context.findActivity())
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }) { Text("Exit kiosk") }
            },
            dismissButton = {
                TextButton(onClick = { showExitKiosk = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun LaunchedEffectTick(running: Boolean, viewModel: GameViewModel) {
    androidx.compose.runtime.LaunchedEffect(running) {
        while (running) {
            kotlinx.coroutines.delay(1000)
            viewModel.tickTimer()
        }
    }
}
```

- [ ] **Step 2: Build, install, verify on device**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug
"$ADB" -s TK12110626B081745 shell am start -n com.magictablet/.MainActivity
```
Expected: unit suite BUILD SUCCESSFUL. On the tablet, Game tab: the center shows a **Pass turn** button (tapping it moves the active-seat highlight around the table); a small **☰ handle** sits on the left edge → tapping it **slides a panel in from the left** over a dim scrim; the panel lists Random first player / Dice & coin / Start-Pause timer / Reset timer / New game, with **Exit kiosk mode pinned at the bottom**; tapping the scrim closes it; New game / Dice / Exit-kiosk launch their dialogs and the panel closes. Screenshot + report. (CR is not on this screen anymore — that's Task 2.)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "UI refresh Task 1: Game screen left side-panel + center Pass-turn button"
```

---

### Task 2: Rules tab + navigation + Stack CR deep-link (delete CrReader)

**Files:**
- Modify: `app/src/main/java/com/magictablet/Screen.kt`
- Create: `app/src/main/java/com/magictablet/screens/RulesScreen.kt`
- Modify: `app/src/main/java/com/magictablet/screens/StackScreen.kt`
- Modify: `app/src/main/java/com/magictablet/App.kt`
- Delete: `app/src/main/java/com/magictablet/rules/CrReader.kt`

**Interfaces:**
- `RulesScreen(viewModel: CrViewModel = viewModel())` (new tab). `StackScreen(..., onOpenRule: (String) -> Unit = {})` (was `crViewModel`). `App` shares one `crViewModel` between them.

- [ ] **Step 1: Add the Rules tab to `Screen.kt`** — replace the enum body:

```kotlin
enum class Screen(val label: String) {
    Game("Game"),
    Cards("Cards"),
    Stack("Stack"),
    Rules("Rules"),
}
```

- [ ] **Step 2: Create `app/src/main/java/com/magictablet/screens/RulesScreen.kt`** (the CR reader content lifted into a full-screen tab — no Dialog, no Close button):

```kotlin
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
```

- [ ] **Step 3: Rework `StackScreen.kt`** (drop the CR modal; add `onOpenRule`) — four edits:

Edit 3a — remove the two CR imports. Delete these lines:
```kotlin
import com.magictablet.rules.CrReader
import com.magictablet.rules.CrViewModel
```

Edit 3b — change the signature + drop the `showRules` state. Replace:
```kotlin
@Composable
fun StackScreen(
    gameViewModel: GameViewModel = viewModel(),
    cardsViewModel: CardsViewModel = viewModel(),
    crViewModel: CrViewModel = viewModel(),
) {
    val state by gameViewModel.state.collectAsStateWithLifecycle()
    val lastResolved by gameViewModel.lastResolved.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
```
with:
```kotlin
@Composable
fun StackScreen(
    gameViewModel: GameViewModel = viewModel(),
    cardsViewModel: CardsViewModel = viewModel(),
    onOpenRule: (String) -> Unit = {},
) {
    val state by gameViewModel.state.collectAsStateWithLifecycle()
    val lastResolved by gameViewModel.lastResolved.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
```

Edit 3c — forward the deep-link to `onOpenRule` and drop the hosted reader. Replace:
```kotlin
    lastResolved?.let { resolved ->
        GuidancePanel(resolved, cardsViewModel,
            onOpenRule = { number -> crViewModel.openAt(number); showRules = true },
            onClose = { gameViewModel.clearLastResolved() })
    }

    if (showRules) {
        CrReader(viewModel = crViewModel, onClose = { showRules = false })
    }
}
```
with:
```kotlin
    lastResolved?.let { resolved ->
        GuidancePanel(resolved, cardsViewModel,
            onOpenRule = onOpenRule,
            onClose = { gameViewModel.clearLastResolved() })
    }
}
```

- [ ] **Step 4: Wire `App.kt`** (share `crViewModel`; add the Rules tab + the deep-link)

Add two imports (with the existing `com.magictablet.*` imports):
```kotlin
import com.magictablet.rules.CrViewModel
import com.magictablet.screens.RulesScreen
```

Add the shared `crViewModel` — replace:
```kotlin
    val gameViewModel: GameViewModel = viewModel(
        factory = remember { GameViewModelFactory(GameStore(File(context.filesDir, "game.json"))) },
    )
    var screen by remember { mutableStateOf(Screen.Game) }
```
with:
```kotlin
    val gameViewModel: GameViewModel = viewModel(
        factory = remember { GameViewModelFactory(GameStore(File(context.filesDir, "game.json"))) },
    )
    val crViewModel: CrViewModel = viewModel()
    var screen by remember { mutableStateOf(Screen.Game) }
```

Update the `when (screen)` — replace:
```kotlin
                when (screen) {
                    Screen.Game -> GameScreen(viewModel = gameViewModel)
                    Screen.Cards -> CardsScreen()
                    Screen.Stack -> StackScreen(gameViewModel = gameViewModel)
                }
```
with:
```kotlin
                when (screen) {
                    Screen.Game -> GameScreen(viewModel = gameViewModel)
                    Screen.Cards -> CardsScreen()
                    Screen.Stack -> StackScreen(
                        gameViewModel = gameViewModel,
                        onOpenRule = { number -> crViewModel.openAt(number); screen = Screen.Rules },
                    )
                    Screen.Rules -> RulesScreen(viewModel = crViewModel)
                }
```

- [ ] **Step 5: Delete the old reader**

```bash
git rm app/src/main/java/com/magictablet/rules/CrReader.kt
```

- [ ] **Step 6: Build, install, verify on device**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug
"$ADB" -s TK12110626B081745 shell am start -n com.magictablet/.MainActivity
```
Expected: unit suite BUILD SUCCESSFUL (and the delete caused no unresolved-reference errors). On the tablet: a **Rules** tab appears (Game / Cards / Stack / Rules); opening it shows the Comprehensive Rules reader (browse/jump/glossary/update, or the "Load rules" prompt if not loaded). On the **Stack** tab, resolve an item so the guidance dialog appears, tap a **"… — CR NNN"** hint → the app switches to the **Rules** tab at that rule; tap back to **Stack** → the stack is intact. Report.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "UI refresh Task 2: Rules tab + Stack CR deep-link to it; remove CrReader dialog"
```

---

### Task 3: Monarch rework (hidden by default; grayed crowns; claim via counters popup)

**Files:**
- Modify: `app/src/main/java/com/magictablet/game/ui/SeatLayout.kt`
- Modify: `app/src/main/java/com/magictablet/game/ui/PlayerPanel.kt`
- Modify: `app/src/main/java/com/magictablet/game/ui/PlayerDrawer.kt`

- [ ] **Step 1: `SeatLayout.kt` — thread `monarchExists` into each panel**

Replace:
```kotlin
            isActive = p.id == activePlayerId,
            anyActive = activePlayerId != null,
            isMonarch = p.id == monarchPlayerId,
```
with:
```kotlin
            isActive = p.id == activePlayerId,
            anyActive = activePlayerId != null,
            isMonarch = p.id == monarchPlayerId,
            monarchExists = monarchPlayerId != null,
```

- [ ] **Step 2: `PlayerPanel.kt` — new param, crown only when a monarch exists, pass into the drawer**

Add the parameter — replace:
```kotlin
    isActive: Boolean,
    anyActive: Boolean,
    isMonarch: Boolean,
```
with:
```kotlin
    isActive: Boolean,
    anyActive: Boolean,
    isMonarch: Boolean,
    monarchExists: Boolean,
```

Render the crown only when a monarch exists — replace:
```kotlin
            Box(
                Modifier.align(Alignment.TopEnd).size(44.dp)
                    .clickable { onToggleMonarch(player.id) },
                contentAlignment = Alignment.Center,
            ) {
                Text("👑", fontSize = 20.sp, modifier = Modifier.alpha(if (isMonarch) 1f else 0.3f))
            }
```
with:
```kotlin
            if (monarchExists) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(44.dp)
                        .clickable { onToggleMonarch(player.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("👑", fontSize = 20.sp, modifier = Modifier.alpha(if (isMonarch) 1f else 0.3f))
                }
            }
```

Pass the monarch flag + action into the drawer — replace:
```kotlin
            PlayerDrawer(
                player = player,
                opponents = opponents,
                onAdjustPoison = { onAdjustPoison(player.id, it) },
                onAdjustCommanderDamage = { oppId, d -> onAdjustCommanderDamage(player.id, oppId, d) },
                onAdjustCounter = { c, d -> onAdjustCounter(player.id, c, d) },
                onCollapse = { expanded = false },
            )
```
with:
```kotlin
            PlayerDrawer(
                player = player,
                opponents = opponents,
                isMonarch = isMonarch,
                onAdjustPoison = { onAdjustPoison(player.id, it) },
                onAdjustCommanderDamage = { oppId, d -> onAdjustCommanderDamage(player.id, oppId, d) },
                onAdjustCounter = { c, d -> onAdjustCounter(player.id, c, d) },
                onBecomeMonarch = { onToggleMonarch(player.id) },
                onCollapse = { expanded = false },
            )
```

- [ ] **Step 3: `PlayerDrawer.kt` — new params + the Become/Renounce button**

Add the two parameters — replace:
```kotlin
fun PlayerDrawer(
    player: PlayerState,
    opponents: List<PlayerState>,
    onAdjustPoison: (delta: Int) -> Unit,
    onAdjustCommanderDamage: (fromOpponentId: Int, delta: Int) -> Unit,
    onAdjustCounter: (counter: String, delta: Int) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
```
with:
```kotlin
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
```

Add the monarch button just above the Collapse button — replace:
```kotlin
            TextButton(onClick = onCollapse, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Collapse")
            }
```
with:
```kotlin
            OutlinedButton(onClick = onBecomeMonarch, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (isMonarch) "Renounce monarch 👑" else "Become the monarch 👑")
            }
            TextButton(onClick = onCollapse, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Collapse")
            }
```

- [ ] **Step 4: Build, install, verify on device**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug
"$ADB" -s TK12110626B081745 shell am start -n com.magictablet/.MainActivity
```
Expected: unit suite BUILD SUCCESSFUL. On the tablet, Game tab: a fresh game shows **no crowns**. Open a player's **▴ counters** drawer → **"Become the monarch 👑"** → close: now **every** seat shows a crown — **solid** on that player, **grayed** on the others. Tap another player's **grayed crown** → the monarchy moves to them (solid there, grayed elsewhere). Open the monarch's drawer → **"Renounce monarch 👑"** (or tap their solid crown) → **all crowns disappear**. Force-stop + relaunch with a monarch set → the monarch (and crowns) restore. Screenshot + report.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "UI refresh Task 3: monarch hidden by default, grayed crowns, claim via counters popup"
```

---

## Definition of Done

- [ ] Game screen: center is a **Pass turn** button; a left-edge handle opens a slide-out panel with Random first player / Dice / timer / New game and **Exit kiosk at the bottom**; the old `⚙` dropdown is gone.
- [ ] A **Rules** tab hosts the Comprehensive Rules reader; a Stack CR hint jumps to it at the rule; the `CrReader` dialog is deleted.
- [ ] Monarch: no crowns by default; a counters-popup **"Become the monarch"** claims it; once set, solid crown on the monarch + grayed crowns on the rest; grayed crowns are tappable to steal; renounce hides them. Survives a reboot.
- [ ] Unit suite green; each screen verified on-device.

## Self-Review notes

- **Spec coverage:** side panel + pass button (T1); Rules tab + move CR + Stack deep-link + delete CrReader (T2); monarch hide/claim/steal (T3). All six spec items mapped.
- **Type consistency:** `GameScreen(viewModel)` (no crViewModel); `StackScreen(gameViewModel, cardsViewModel, onOpenRule)`; `RulesScreen(viewModel)`; `PlayerPanel(..., isMonarch, monarchExists)`; `PlayerDrawer(..., isMonarch, onBecomeMonarch, ...)`; `App` shares one `crViewModel`. Consistent across tasks.
- **No new pure logic:** actions reuse `advanceTurn`/`toggleMonarch`/`randomFirstPlayer`/`newGame`/timer intents — no VM/model change, no new unit tests; the gate is the on-device check + the suite staying green.
- **Ordering:** Task 1 removes GameScreen's `CrReader` use; Task 2 removes the Stack's and only THEN deletes `CrReader.kt`. Deleting it earlier would break the build.
- **Known risks:** the left handle overlaps a sliver of the left −life zone (narrow; trimmable); the scrim uses `indication = null` to avoid a full-screen ripple; `RulesScreen(viewModel())` default and `App`'s `crViewModel` resolve to the same Activity-scoped instance (App passes it explicitly).
