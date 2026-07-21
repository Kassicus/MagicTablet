# M4b — Stack Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A manual, card-aware LIFO stack: add spells/abilities (card-linked or free-typed), inline oracle text + rulings for linked items, a pass-around priority tracker, and three-tier resolution guidance that deep-links into the M4a Comprehensive Rules reader. Never computes interaction outcomes (DESIGN.md §2).

**Architecture:** The stack is shared game state on `GameState`/`GameViewModel` (screens share the Activity-scoped VM). Pure ops (`StackModels.kt`) + pure guidance (`StackGuidance.kt`) are unit-tested. The Stack screen reuses the shared `CardsViewModel` (card search + detail) and `CrViewModel` (CR deep-links, hosting `CrReader` here).

**Tech Stack:** Kotlin, Compose + Material 3, existing lifecycle/coroutines. No new deps.

## Global Constraints

From the spec (`docs/superpowers/specs/2026-07-20-m4b-stack-assistant-design.md`):

- Package `com.magictablet.game` (stack logic) + `com.magictablet.screens` (StackScreen). Reuses `com.magictablet.cards` + `com.magictablet.rules`. **No new deps.**
- **NOT a rules engine** (§2): only text, the static procedure, pattern hints, and CR references — never computed outcomes.
- `GameState` gains `stack: List<StackItem> = emptyList()`, `priorityPlayerId: Int? = null`, `consecutivePasses: Int = 0`; `newGame` resets them (via `initialGame`) + `lastResolved`.
- Pure ops (verbatim, §5 of spec): `pushStackItem`, `resolveTop`, `removeStackItem`, `clearStack`, `passPriority` (pass N=player-count times → top resolves). `priorityStart()` = `activePlayerId ?: players.firstOrNull()?.id`.
- `stackHints(item, oracleText, keywords)` + `RESOLUTION_PROCEDURE` (§6) — dedup by rule number.
- The stack shares the same `GameViewModel` instance as the Game screen (Activity-scoped `viewModel()`); the Stack screen also uses shared `CardsViewModel` + `CrViewModel`.
- **Environment preamble:**

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
  ```

- **Sandbox:** Gradle + adb need `dangerouslyDisableSandbox: true`. Device `TK12110626B081745`. (The Task-2 manual verification can use card search offline; a CR deep-link needs the CR loaded — load it via ⚙ → Comprehensive Rules → Update first if needed.)

> **Testing:** Task 1 = offline JVM unit tests (TDD). Task 2 = the Stack screen UI + on-device manual flow.

---

### Task 1: Stack + priority + guidance logic (TDD)

**Files:**
- Modify: `app/src/main/java/com/magictablet/game/GameModels.kt` (GameState fields)
- Create: `app/src/main/java/com/magictablet/game/StackModels.kt`
- Create: `app/src/main/java/com/magictablet/game/StackGuidance.kt`
- Modify: `app/src/main/java/com/magictablet/game/GameViewModel.kt` (intents + lastResolved)
- Modify: `app/src/main/java/com/magictablet/cards/CardsViewModel.kt` (getDetail + searchCards)
- Test: `app/src/test/java/com/magictablet/game/StackModelsTest.kt`
- Test: `app/src/test/java/com/magictablet/game/StackGuidanceTest.kt`
- Modify (add tests): `app/src/test/java/com/magictablet/game/GameViewModelTest.kt`

**Interfaces:**
- `StackItem`, `StackKind`, `GameState.pushStackItem/resolveTop/removeStackItem/clearStack/passPriority`, `RuleHint`, `stackHints`, `RESOLUTION_PROCEDURE`; `GameViewModel.addToStack/resolveTop/removeStackItem/clearStack/passPriority/clearLastResolved` + `lastResolved`; `CardsViewModel.getDetail/searchCards`.

- [ ] **Step 1: Write the failing tests** — `app/src/test/java/com/magictablet/game/StackModelsTest.kt`

```kotlin
package com.magictablet.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StackModelsTest {
    private fun game() = initialGame(4, 40) // players 1..4
    private fun item(id: Long, controller: Int = 1, kind: StackKind = StackKind.Spell) =
        StackItem(id, controller, null, kind, "Item $id")

    @Test fun push_addsToTop_setsPriorityToActive() {
        val g = game().copy(activePlayerId = 2).pushStackItem(item(1))
        assertEquals(listOf(1L), g.stack.map { it.id })
        assertEquals(2, g.priorityPlayerId)   // active player
        assertEquals(0, g.consecutivePasses)
    }

    @Test fun push_priorityFallsBackToFirstSeat_whenNoActive() {
        assertEquals(1, game().pushStackItem(item(1)).priorityPlayerId)
    }

    @Test fun resolveTop_popsTop_resets() {
        val g = game().pushStackItem(item(1)).pushStackItem(item(2)).resolveTop()
        assertEquals(listOf(1L), g.stack.map { it.id })  // top (2) removed
        assertEquals(0, g.consecutivePasses)
        assertNull(game().pushStackItem(item(1)).resolveTop().priorityPlayerId) // empty -> null
    }

    @Test fun removeStackItem_removesById() {
        val g = game().pushStackItem(item(1)).pushStackItem(item(2)).removeStackItem(1)
        assertEquals(listOf(2L), g.stack.map { it.id })
    }

    @Test fun clearStack_empties() {
        val g = game().pushStackItem(item(1)).clearStack()
        assertEquals(emptyList<StackItem>(), g.stack)
        assertNull(g.priorityPlayerId)
    }

    @Test fun passPriority_advancesToNextSeat() {
        val g = game().copy(activePlayerId = 1).pushStackItem(item(1)).passPriority()
        assertEquals(2, g.priorityPlayerId)
        assertEquals(1, g.consecutivePasses)
    }

    @Test fun passPriority_allPassed_resolvesTop() {
        var g = game().copy(activePlayerId = 1).pushStackItem(item(1))
        repeat(4) { g = g.passPriority() }   // 4 players all pass
        assertEquals(emptyList<StackItem>(), g.stack) // top resolved
        assertEquals(0, g.consecutivePasses)
    }
}
```

Also `app/src/test/java/com/magictablet/game/StackGuidanceTest.kt`:

```kotlin
package com.magictablet.game

import org.junit.Assert.assertTrue
import org.junit.Test

class StackGuidanceTest {
    private fun item(kind: StackKind, label: String = "x") = StackItem(1, 1, null, kind, label)
    private fun rules(hints: List<RuleHint>) = hints.map { it.ruleNumber }.toSet()

    @Test fun spell_maps_601_608() {
        assertTrue(rules(stackHints(item(StackKind.Spell), "Deal 3 damage.", emptyList())).containsAll(setOf("601", "608")))
    }

    @Test fun triggered_maps_603() {
        assertTrue("603" in rules(stackHints(item(StackKind.Triggered), null, emptyList())))
    }

    @Test fun whenever_flags_triggered() {
        assertTrue("603" in rules(stackHints(item(StackKind.Spell), "Whenever a creature dies, draw a card.", emptyList())))
    }

    @Test fun colon_flags_activated() {
        assertTrue("602" in rules(stackHints(item(StackKind.Spell), "{T}: Add {G}.", emptyList())))
    }

    @Test fun target_flags_115() {
        assertTrue("115" in rules(stackHints(item(StackKind.Spell), "Destroy target creature.", emptyList())))
    }

    @Test fun keywords_flag_702() {
        assertTrue("702" in rules(stackHints(item(StackKind.Spell), "A creature.", listOf("Flying"))))
    }

    @Test fun hints_dedupByRuleNumber() {
        val hints = stackHints(item(StackKind.Spell), "Deal damage.", emptyList())
        assertTrue(hints.size == hints.map { it.ruleNumber }.distinct().size)
    }

    @Test fun procedure_nonEmpty() {
        assertTrue(RESOLUTION_PROCEDURE.isNotBlank())
    }
}
```

- [ ] **Step 2: Run the tests, verify they fail**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.game.StackModelsTest" --tests "com.magictablet.game.StackGuidanceTest"
```
Expected: FAIL — unresolved `StackItem`/`pushStackItem`/`stackHints`/etc.

- [ ] **Step 3: Update the `GameState` data class in `GameModels.kt`** — replace the existing `GameState` declaration with:

```kotlin
data class GameState(
    val startingLife: Int,
    val players: List<PlayerState>,
    val activePlayerId: Int? = null,
    val monarchPlayerId: Int? = null,
    val stack: List<StackItem> = emptyList(),
    val priorityPlayerId: Int? = null,
    val consecutivePasses: Int = 0,
)
```

- [ ] **Step 4: Create `app/src/main/java/com/magictablet/game/StackModels.kt`**

```kotlin
package com.magictablet.game

enum class StackKind { Spell, Activated, Triggered }

data class StackItem(
    val id: Long,
    val controllerId: Int,
    val cardOracleId: String?,
    val kind: StackKind,
    val label: String,
    val targets: String = "",
)

private fun GameState.priorityStart(): Int? = activePlayerId ?: players.firstOrNull()?.id

fun GameState.pushStackItem(item: StackItem): GameState =
    copy(stack = stack + item, priorityPlayerId = priorityStart(), consecutivePasses = 0)

fun GameState.resolveTop(): GameState {
    if (stack.isEmpty()) return this
    val rest = stack.dropLast(1)
    return copy(
        stack = rest,
        priorityPlayerId = if (rest.isEmpty()) null else priorityStart(),
        consecutivePasses = 0,
    )
}

fun GameState.removeStackItem(id: Long): GameState {
    val rest = stack.filterNot { it.id == id }
    return copy(stack = rest, priorityPlayerId = if (rest.isEmpty()) null else priorityPlayerId)
}

fun GameState.clearStack(): GameState =
    copy(stack = emptyList(), priorityPlayerId = null, consecutivePasses = 0)

fun GameState.passPriority(): GameState {
    val current = priorityPlayerId ?: return this
    val next = consecutivePasses + 1
    if (next >= players.size) return resolveTop()
    val idx = players.indexOfFirst { it.id == current }
    val nextId = if (idx < 0) players.first().id else players[(idx + 1) % players.size].id
    return copy(priorityPlayerId = nextId, consecutivePasses = next)
}
```

- [ ] **Step 5: Create `app/src/main/java/com/magictablet/game/StackGuidance.kt`**

```kotlin
package com.magictablet.game

data class RuleHint(val label: String, val ruleNumber: String)

const val RESOLUTION_PROCEDURE =
    "Resolve one object at a time, from the top of the stack down. After each resolution the active " +
        "player gets priority. Abilities that trigger during resolution go on top of the stack (in turn " +
        "order) before anyone gets priority. A spell or ability whose targets have all become illegal " +
        "doesn't resolve — it and its effects are removed. (CR 608, 603)"

private val TRIGGER_WORDS = Regex("(?i)\\b(when|whenever|at)\\b")
private val TARGET_WORD = Regex("(?i)\\btarget")

fun stackHints(item: StackItem, oracleText: String?, keywords: List<String>): List<RuleHint> {
    val text = oracleText ?: item.label
    val hints = ArrayList<RuleHint>()
    when (item.kind) {
        StackKind.Spell -> {
            hints.add(RuleHint("Casting spells", "601"))
            hints.add(RuleHint("Resolving a spell", "608"))
        }
        StackKind.Activated -> hints.add(RuleHint("Activated abilities", "602"))
        StackKind.Triggered -> hints.add(RuleHint("Triggered abilities", "603"))
    }
    if (item.kind != StackKind.Triggered && TRIGGER_WORDS.containsMatchIn(text)) {
        hints.add(RuleHint("‘When/Whenever/At’ = a triggered ability", "603"))
    }
    if (item.kind != StackKind.Activated && text.contains(':')) {
        hints.add(RuleHint("‘[cost]: [effect]’ = an activated ability", "602"))
    }
    if (TARGET_WORD.containsMatchIn(text)) {
        hints.add(RuleHint("Has a target — if all targets become illegal, it doesn't resolve", "115"))
    }
    if (keywords.isNotEmpty()) {
        hints.add(RuleHint("Keyword ability: ${keywords.joinToString(", ")}", "702"))
    }
    hints.add(RuleHint("Resolving spells & abilities", "608"))
    return hints.distinctBy { it.ruleNumber }
}
```

- [ ] **Step 6: Add stack intents to `GameViewModel.kt`** (three inserts + a `newGame` tweak)

Insert A — after the line `val timer: StateFlow<TimerState> = _timer.asStateFlow()`, add:
```kotlin

    private val _lastResolved = MutableStateFlow<StackItem?>(null)
    val lastResolved: StateFlow<StackItem?> = _lastResolved.asStateFlow()
    private var nextStackId = 1L
```

Insert B — inside `newGame`, immediately after `_timer.value = TimerState()`, add:
```kotlin
        _lastResolved.value = null
```

Insert C — immediately before the `companion object` line, add:
```kotlin
    fun addToStack(controllerId: Int, kind: StackKind, cardOracleId: String?, label: String, targets: String) {
        _state.update { it.pushStackItem(StackItem(nextStackId++, controllerId, cardOracleId, kind, label, targets)) }
    }

    fun resolveTop() = resolvingUpdate { it.resolveTop() }
    fun passPriority() = resolvingUpdate { it.passPriority() }
    fun removeStackItem(id: Long) = _state.update { it.removeStackItem(id) }
    fun clearStack() { _state.update { it.clearStack() }; _lastResolved.value = null }
    fun clearLastResolved() { _lastResolved.value = null }

    /** Apply [op]; if it shrank the stack (a resolution), capture the removed top into lastResolved. */
    private fun resolvingUpdate(op: (GameState) -> GameState) {
        val before = _state.value
        val top = before.stack.lastOrNull()
        val after = op(before)
        _state.value = after
        if (after.stack.size < before.stack.size && top != null) _lastResolved.value = top
    }

```

- [ ] **Step 7: Add `getDetail` + `searchCards` to `CardsViewModel.kt`** — insert these two methods into the class (e.g. after `fun closeDetail()`):

```kotlin
    suspend fun getDetail(oracleId: String): CardDetail? =
        withContext(Dispatchers.IO) { db.card(oracleId) }

    suspend fun searchCards(query: String): List<CardSummary> =
        withContext(Dispatchers.IO) { db.search(query) }
```

(`withContext` and `Dispatchers` are already imported in `CardsViewModel` from M3.)

- [ ] **Step 8: Add VM tests** — append to `GameViewModelTest.kt` (inside the class):

```kotlin
    @Test fun addToStack_thenResolveTop_setsLastResolved() {
        val vm = GameViewModel()
        vm.addToStack(1, StackKind.Spell, null, "Bolt", "")
        assertEquals(1, vm.state.value.stack.size)
        vm.resolveTop()
        assertEquals(0, vm.state.value.stack.size)
        assertEquals("Bolt", vm.lastResolved.value?.label)
    }

    @Test fun passAround_resolvesTop_andSetsLastResolved() {
        val vm = GameViewModel()  // default 4 players
        vm.addToStack(1, StackKind.Triggered, null, "Trigger", "")
        repeat(4) { vm.passPriority() }
        assertEquals(0, vm.state.value.stack.size)
        assertEquals("Trigger", vm.lastResolved.value?.label)
    }

    @Test fun newGame_clearsStackAndLastResolved() {
        val vm = GameViewModel()
        vm.addToStack(1, StackKind.Spell, null, "Bolt", "")
        vm.resolveTop()
        vm.newGame(4, 40)
        assertEquals(emptyList<StackItem>(), vm.state.value.stack)
        assertNull(vm.lastResolved.value)
    }
```

Ensure `assertNull`/`assertEquals` are imported (they are, from M1b's VM tests).

- [ ] **Step 9: Run all game tests, verify green**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.game.*"
```
Expected: `BUILD SUCCESSFUL`, all pass (Stack + Guidance + VM + prior game tests).

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "M4b Task 1: stack + priority ops + resolution guidance logic (TDD)"
```

---

### Task 2: Stack screen (add / list / priority / resolve / guidance + CR deep-links)

**Files:**
- Modify (replace): `app/src/main/java/com/magictablet/screens/StackScreen.kt`

**Interfaces:**
- Consumes `GameViewModel` (stack state + intents + `lastResolved`), `CardsViewModel` (`searchCards`/`getDetail`), `CrViewModel` (`openAt`) + `CrReader`, `stackHints`/`RESOLUTION_PROCEDURE`, `SeatColors`. `App.kt`'s `StackScreen()` call keeps working (default args).

- [ ] **Step 1: Replace `app/src/main/java/com/magictablet/screens/StackScreen.kt`**

```kotlin
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
import com.magictablet.rules.CrReader
import com.magictablet.rules.CrViewModel
import com.magictablet.ui.theme.SeatColors

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
            onOpenRule = { number -> crViewModel.openAt(number); showRules = true },
            onClose = { gameViewModel.clearLastResolved() })
    }

    if (showRules) {
        CrReader(viewModel = crViewModel, onClose = { showRules = false })
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
```

- [ ] **Step 2: Build, install, verify on device**

Run:
```bash
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
"$ADB" shell am start -n com.magictablet/.MainActivity
```
On the tablet: **Stack** tab → **Add** → pick a controller + kind **Spell**, search **Lightning Bolt** → link it → Add. Add a second item: kind **Triggered**, free-type "Whenever a creature dies, draw a card" → Add. The list shows both (newest on top) with controller color + kind. Tap **Text** on Lightning Bolt → its oracle text ("…3 damage…") + rulings show. The **Priority: P{n}** row shows; tap **Pass** four times (4-player default) → the top resolves and a **Resolving:** guidance dialog appears with the procedure + rules hints. Tap a hint (e.g. "CR 603") → the **Comprehensive Rules** reader opens at that rule (load the CR first via ⚙ → Comprehensive Rules → Update if it isn't loaded). Also try **Resolve top** directly. Game/Cards tabs unchanged. Report what you saw.

- [ ] **Step 3: Confirm the unit suite still passes**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "M4b Task 2: Stack screen (add/LIFO/priority/resolve + tier-2/3 guidance + CR deep-links)"
```

---

## Definition of Done (M4b)

- [ ] Stack screen: Add (controller + kind + card-link/free-text + targets) puts an item on top (LIFO); linked items expand to oracle text + rulings.
- [ ] Priority row shows whose priority; Pass advances around the table; all-passed resolves the top; Resolve top works.
- [ ] Resolving shows the guidance panel (tier-2 procedure + tier-3 hints); tapping a hint opens the CR reader at that rule.
- [ ] The app never computes an interaction outcome.
- [ ] `StackModelsTest` + `StackGuidanceTest` (+ the VM tests) pass; the on-device Stack flow is verified.

## Self-Review notes

- **Spec coverage:** logic (stack ops + priority + guidance + VM/Cards additions) with unit tests (T1); the full Stack screen — add/list/inline oracle+rulings/priority/resolve/guidance/CR deep-links (T2). Every DoD item maps to a task.
- **Type consistency:** `StackItem`/`StackKind`, `pushStackItem/resolveTop/removeStackItem/clearStack/passPriority`, `RuleHint`/`stackHints`/`RESOLUTION_PROCEDURE`, `GameViewModel` intents + `lastResolved`, `CardsViewModel.getDetail/searchCards`, and the StackScreen composables are consistent across the two tasks.
- **Shared VMs:** all `viewModel()` (Activity-scoped), so the stack shares the Game screen's `GameViewModel` and the Cards/CR screens' VMs. `StackScreen()` keeps the `App.kt` call site (default args).
- **Not a rules engine:** the guidance is static procedure + pattern hints + CR references; no outcome computation.
- **Known risks:** `GameViewModel` grows (kept the logic in pure `StackModels`); the CR deep-link needs the CR loaded (opens the reader's load prompt otherwise); per-item oracle/ruling loads happen on expand/resolve (cached).
