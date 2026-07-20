# M1b — Table Utilities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the ⚙-hub table utilities — advance turn / random first player with an active-seat highlight, a tappable monarch marker, a Coin/d6/d20 dice overlay, and a simple overall game timer.

**Architecture:** Extends M1a's in-memory `GameViewModel`. New turn/monarch fields on `GameState` with pure, unit-tested update functions; dice/coin as pure helpers with injectable `Random`; timer as transient VM state with a UI-driven 1 s tick (so the VM stays coroutine/dispatcher-free and testable). UI additions: per-seat active-border + crown marker, a dice `Dialog`, and a timer readout chip, all wired through the existing `GameScreen → SeatLayout → PlayerPanel` graph.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose (BOM 2024.12.01) + Material 3, AndroidX Lifecycle 2.8.7, JUnit 4. **No new dependencies.**

## Global Constraints

Copied from the spec (`docs/superpowers/specs/2026-07-20-m1b-table-utilities-design.md`) and verified machine state:

- Builds on M1a (merged to `main`). Packages: `com.magictablet.game` (logic/VM), `com.magictablet.game.ui` (composables), `com.magictablet.screens` (GameScreen).
- **No new Gradle dependencies. No Room / persistence** (that is M6).
- `GameState` gains `activePlayerId: Int? = null` and `monarchPlayerId: Int? = null`; `initialGame` leaves them null; `newGame` resets them (via `initialGame`) AND resets the timer.
- Pure logic is deterministic and unit-tested; randomness uses `kotlin.random.Random` injected as a defaulted parameter (`Random.Default`) so tests can seed it.
- `advanceTurn`: null active → first seat; else next player in `players` order, wrapping. `toggleMonarch(id)`: claim, or clear if already that seat. Monarch is exclusive.
- Timer: VM holds `TimerState(running, elapsedSeconds)`; the VM has **no coroutine** — a `LaunchedEffect` in `GameScreen` calls `tickTimer()` once/second while running. `tickTimer` increments only when running.
- Dice: `rollDie(sides)` ∈ `1..sides`; `flipCoin()` ∈ {"Heads","Tails"}.
- The monarch crown is a `clickable` control; the M1a fix (`awaitFirstDown()` default `requireUnconsumed = true` in `HoldRepeat.kt`) already prevents life-zone click-through — do NOT reintroduce `requireUnconsumed = false`.
- Orientation/theme/seat palette unchanged from M0/M1a.
- **Environment preamble** (prefix build/adb commands; shell state does not persist between commands):

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
  ```

- **Sandbox:** Gradle + adb/USB commands need the Bash param `dangerouslyDisableSandbox: true`. Install via `./gradlew :app:installDebug` — never Android Studio Run. Device `TK12110626B081745`.

> **Testing note:** Task 1 is pure logic + VM state with full JVM unit tests (TDD). Tasks 2–4 are Compose UI; their gate is build + on-device verification (concrete steps given).

---

### Task 1: Turn/monarch state, dice helpers, timer state, VM intents (TDD)

**Files:**
- Modify: `app/src/main/java/com/magictablet/game/GameModels.kt`
- Create: `app/src/main/java/com/magictablet/game/Randomizers.kt`
- Modify: `app/src/main/java/com/magictablet/game/GameViewModel.kt`
- Test: `app/src/test/java/com/magictablet/game/TurnMonarchTest.kt`
- Test: `app/src/test/java/com/magictablet/game/RandomizersTest.kt`
- Modify (add tests): `app/src/test/java/com/magictablet/game/GameViewModelTest.kt`

**Interfaces:**
- `GameState` gains `activePlayerId: Int?`, `monarchPlayerId: Int?`; pure `GameState.advanceTurn()`, `setActivePlayer(id)`, `toggleMonarch(id)`; `TimerState(running, elapsedSeconds)`; `rollDie(sides, random)`, `flipCoin(random)`.
- `GameViewModel` gains `timer: StateFlow<TimerState>` and `advanceTurn()`, `randomFirstPlayer()`, `toggleMonarch(id)`, `startTimer()`, `pauseTimer()`, `resetTimer()`, `tickTimer()`; `newGame` also resets active/monarch/timer.

- [ ] **Step 1: Write the failing tests** — `app/src/test/java/com/magictablet/game/TurnMonarchTest.kt`

```kotlin
package com.magictablet.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnMonarchTest {
    @Test fun initialGame_hasNoActiveOrMonarch() {
        val g = initialGame(4, 40)
        assertNull(g.activePlayerId)
        assertNull(g.monarchPlayerId)
    }

    @Test fun advanceTurn_fromNull_goesToFirstSeat() {
        assertEquals(1, initialGame(4, 40).advanceTurn().activePlayerId)
    }

    @Test fun advanceTurn_wrapsAndOrders() {
        var g = initialGame(3, 40)
        g = g.advanceTurn(); assertEquals(1, g.activePlayerId)
        g = g.advanceTurn(); assertEquals(2, g.activePlayerId)
        g = g.advanceTurn(); assertEquals(3, g.activePlayerId)
        g = g.advanceTurn(); assertEquals(1, g.activePlayerId) // wrap
    }

    @Test fun setActivePlayer_setsId() {
        assertEquals(3, initialGame(4, 40).setActivePlayer(3).activePlayerId)
    }

    @Test fun toggleMonarch_claimThenPass() {
        val claimed = initialGame(4, 40).toggleMonarch(2)
        assertEquals(2, claimed.monarchPlayerId)
        assertNull(claimed.toggleMonarch(2).monarchPlayerId) // pass/clear
        assertEquals(3, claimed.toggleMonarch(3).monarchPlayerId) // move crown
    }
}
```

Also `app/src/test/java/com/magictablet/game/RandomizersTest.kt`:

```kotlin
package com.magictablet.game

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RandomizersTest {
    @Test fun rollDie_inRange() {
        val r = Random(42)
        repeat(200) {
            val v6 = rollDie(6, r); assertTrue(v6 in 1..6)
            val v20 = rollDie(20, r); assertTrue(v20 in 1..20)
        }
    }

    @Test fun flipCoin_isHeadsOrTails() {
        val r = Random(1)
        repeat(50) { assertTrue(flipCoin(r) in setOf("Heads", "Tails")) }
    }
}
```

- [ ] **Step 2: Run the tests, verify they fail**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.game.TurnMonarchTest" --tests "com.magictablet.game.RandomizersTest"
```
Expected: FAIL — unresolved `advanceTurn`/`toggleMonarch`/`rollDie`/`flipCoin`, and `activePlayerId` not a member.

- [ ] **Step 3: Modify `GameModels.kt`** — add the two fields to `GameState` and append the pure functions + `TimerState`.

Change the `GameState` data class to:
```kotlin
data class GameState(
    val startingLife: Int,
    val players: List<PlayerState>,
    val activePlayerId: Int? = null,
    val monarchPlayerId: Int? = null,
)
```

Append to the end of `GameModels.kt`:
```kotlin
data class TimerState(
    val running: Boolean = false,
    val elapsedSeconds: Long = 0,
)

fun GameState.advanceTurn(): GameState {
    if (players.isEmpty()) return this
    val currentIndex = players.indexOfFirst { it.id == activePlayerId }
    val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % players.size
    return copy(activePlayerId = players[nextIndex].id)
}

fun GameState.setActivePlayer(id: Int): GameState = copy(activePlayerId = id)

fun GameState.toggleMonarch(id: Int): GameState =
    copy(monarchPlayerId = if (monarchPlayerId == id) null else id)
```

(`initialGame` needs no change — the new fields default to null.)

- [ ] **Step 4: Create `app/src/main/java/com/magictablet/game/Randomizers.kt`**

```kotlin
package com.magictablet.game

import kotlin.random.Random

/** Uniform die roll in 1..sides. */
fun rollDie(sides: Int, random: Random = Random.Default): Int = random.nextInt(sides) + 1

/** "Heads" or "Tails". */
fun flipCoin(random: Random = Random.Default): String = if (random.nextBoolean()) "Heads" else "Tails"
```

- [ ] **Step 5: Run TurnMonarchTest + RandomizersTest, verify they pass**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.game.TurnMonarchTest" --tests "com.magictablet.game.RandomizersTest"
```
Expected: `BUILD SUCCESSFUL`, both pass.

- [ ] **Step 6: Modify `GameViewModel.kt`** — add timer state + the new intents; reset timer/active/monarch on new game.

Add imports if missing: `import kotlinx.coroutines.flow.update` (already present from M1a).

Inside the class, after the `_recentDeltas` declarations, add:
```kotlin
    private val _timer = MutableStateFlow(TimerState())
    val timer: StateFlow<TimerState> = _timer.asStateFlow()
```

Change `newGame` to also reset the timer:
```kotlin
    fun newGame(playerCount: Int, startingLife: Int) {
        _state.value = initialGame(playerCount, startingLife)
        _recentDeltas.value = emptyMap()
        _timer.value = TimerState()
    }
```
(`initialGame` already leaves `activePlayerId`/`monarchPlayerId` null, so New game clears them.)

Add these functions to the class (e.g. after `clearRecentDelta`):
```kotlin
    fun advanceTurn() = _state.update { it.advanceTurn() }

    fun randomFirstPlayer() {
        val players = _state.value.players
        if (players.isEmpty()) return
        val id = players.random().id
        _state.update { it.setActivePlayer(id) }
    }

    fun toggleMonarch(playerId: Int) = _state.update { it.toggleMonarch(playerId) }

    fun startTimer() = _timer.update { it.copy(running = true) }
    fun pauseTimer() = _timer.update { it.copy(running = false) }
    fun resetTimer() { _timer.value = TimerState() }
    fun tickTimer() = _timer.update { if (it.running) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it }
```

- [ ] **Step 7: Add VM tests** — append to `app/src/test/java/com/magictablet/game/GameViewModelTest.kt` (inside the existing class):

```kotlin
    @Test fun advanceTurn_viaVm() {
        val vm = GameViewModel()
        vm.advanceTurn()
        assertEquals(1, vm.state.value.activePlayerId)
    }

    @Test fun randomFirstPlayer_landsOnValidSeat() {
        val vm = GameViewModel()
        vm.randomFirstPlayer()
        val id = vm.state.value.activePlayerId
        assertTrue(id != null && vm.state.value.players.any { it.id == id })
    }

    @Test fun toggleMonarch_viaVm() {
        val vm = GameViewModel()
        vm.toggleMonarch(3)
        assertEquals(3, vm.state.value.monarchPlayerId)
        vm.toggleMonarch(3)
        assertNull(vm.state.value.monarchPlayerId)
    }

    @Test fun timer_startTickPauseReset() {
        val vm = GameViewModel()
        assertEquals(TimerState(), vm.timer.value)
        vm.startTimer(); vm.tickTimer(); vm.tickTimer()
        assertEquals(true, vm.timer.value.running)
        assertEquals(2L, vm.timer.value.elapsedSeconds)
        vm.pauseTimer(); vm.tickTimer() // no increment while paused
        assertEquals(false, vm.timer.value.running)
        assertEquals(2L, vm.timer.value.elapsedSeconds)
        vm.resetTimer()
        assertEquals(TimerState(), vm.timer.value)
    }

    @Test fun newGame_clearsActiveMonarchTimer() {
        val vm = GameViewModel()
        vm.advanceTurn(); vm.toggleMonarch(2); vm.startTimer(); vm.tickTimer()
        vm.newGame(4, 40)
        assertNull(vm.state.value.activePlayerId)
        assertNull(vm.state.value.monarchPlayerId)
        assertEquals(TimerState(), vm.timer.value)
    }
```

Ensure these imports exist at the top of the file: `import org.junit.Assert.assertTrue`, `import org.junit.Assert.assertNull` (add if missing).

- [ ] **Step 8: Run all game tests, verify green**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.game.*"
```
Expected: `BUILD SUCCESSFUL`, all pass.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "M1b Task 1: turn/monarch state, dice helpers, timer state + VM intents (TDD)"
```

---

### Task 2: Per-seat markers + advance-turn / random-first-player / monarch wiring

**Files:**
- Modify (replace): `app/src/main/java/com/magictablet/game/ui/PlayerPanel.kt`
- Modify (replace): `app/src/main/java/com/magictablet/game/ui/SeatLayout.kt`
- Modify (replace): `app/src/main/java/com/magictablet/screens/GameScreen.kt`

**Interfaces:**
- `PlayerPanel` gains `isActive: Boolean`, `isMonarch: Boolean`, `onToggleMonarch: (playerId: Int) -> Unit`.
- `SeatLayout` gains `activePlayerId: Int?`, `monarchPlayerId: Int?`, `onToggleMonarch`.
- `GameScreen` hub menu gains **Advance turn** + **Random first player**; wires marker state/callbacks.

- [ ] **Step 1: Replace `app/src/main/java/com/magictablet/game/ui/PlayerPanel.kt`** (M1a version + active border + crown marker)

```kotlin
package com.magictablet.game.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magictablet.game.PlayerState
import com.magictablet.game.RecentDelta
import com.magictablet.game.isLost
import com.magictablet.game.lossReason
import com.magictablet.ui.theme.SeatColors

@Composable
fun PlayerPanel(
    player: PlayerState,
    opponents: List<PlayerState>,
    recentDelta: RecentDelta?,
    isActive: Boolean,
    isMonarch: Boolean,
    onAdjustLife: (playerId: Int, delta: Int) -> Unit,
    onClearDelta: (playerId: Int) -> Unit,
    onAdjustPoison: (playerId: Int, delta: Int) -> Unit,
    onAdjustCommanderDamage: (playerId: Int, fromOpponentId: Int, delta: Int) -> Unit,
    onAdjustCounter: (playerId: Int, counter: String, delta: Int) -> Unit,
    onToggleMonarch: (playerId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = SeatColors[player.colorIndex % SeatColors.size]
    var expanded by remember { mutableStateOf(false) }
    val lost = player.isLost()

    LaunchedEffect(recentDelta?.token) {
        if (recentDelta != null) {
            kotlinx.coroutines.delay(1000)
            onClearDelta(player.id)
        }
    }

    Box(
        modifier = modifier
            .padding(4.dp)
            .border(
                width = if (isActive) 4.dp else 2.dp,
                color = if (isActive) accent else accent.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            ),
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
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().holdRepeatClick { onAdjustLife(player.id, -1) })
                Box(Modifier.weight(1f).fillMaxHeight().holdRepeatClick { onAdjustLife(player.id, 1) })
            }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = player.life.toString(),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.alpha(if (lost) 0.4f else 1f),
                )
            }

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

            if (lost) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.padding(top = 90.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("☠", fontSize = 28.sp, color = accent)
                        Text(player.lossReason() ?: "", fontSize = 16.sp, color = accent)
                    }
                }
            }

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

            // Monarch marker (top-end). Clickable — safe from life-zone click-through (HoldRepeat uses
            // default requireUnconsumed = true). Bright when monarch, faint otherwise.
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .clickable { onToggleMonarch(player.id) },
            ) {
                Text("👑", fontSize = 20.sp, modifier = Modifier.alpha(if (isMonarch) 1f else 0.3f))
            }

            TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Text("▴ counters", fontSize = 13.sp, color = accent)
            }
        }
    }
}
```

- [ ] **Step 2: Replace `app/src/main/java/com/magictablet/game/ui/SeatLayout.kt`** (thread marker state/callback)

```kotlin
package com.magictablet.game.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import com.magictablet.game.PlayerState
import com.magictablet.game.RecentDelta
import com.magictablet.game.seatSplit

@Composable
fun SeatLayout(
    players: List<PlayerState>,
    recentDeltas: Map<Int, RecentDelta>,
    activePlayerId: Int?,
    monarchPlayerId: Int?,
    onAdjustLife: (playerId: Int, delta: Int) -> Unit,
    onClearDelta: (playerId: Int) -> Unit,
    onAdjustPoison: (playerId: Int, delta: Int) -> Unit,
    onAdjustCommanderDamage: (playerId: Int, fromOpponentId: Int, delta: Int) -> Unit,
    onAdjustCounter: (playerId: Int, counter: String, delta: Int) -> Unit,
    onToggleMonarch: (playerId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (topCount, _) = seatSplit(players.size)
    val topPlayers = players.take(topCount)
    val bottomPlayers = players.drop(topCount)

    @Composable
    fun panel(p: PlayerState, mod: Modifier) {
        PlayerPanel(
            player = p,
            opponents = players.filter { it.id != p.id },
            recentDelta = recentDeltas[p.id],
            isActive = p.id == activePlayerId,
            isMonarch = p.id == monarchPlayerId,
            onAdjustLife = onAdjustLife,
            onClearDelta = onClearDelta,
            onAdjustPoison = onAdjustPoison,
            onAdjustCommanderDamage = onAdjustCommanderDamage,
            onAdjustCounter = onAdjustCounter,
            onToggleMonarch = onToggleMonarch,
            modifier = mod,
        )
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            topPlayers.forEach { p -> panel(p, Modifier.weight(1f).rotate(180f)) }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            bottomPlayers.forEach { p -> panel(p, Modifier.weight(1f)) }
        }
    }
}
```

- [ ] **Step 3: Replace `app/src/main/java/com/magictablet/screens/GameScreen.kt`** (add Advance turn / Random first player menu items + marker wiring)

```kotlin
package com.magictablet.screens

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameViewModel
import com.magictablet.game.ui.NewGameSheet
import com.magictablet.game.ui.SeatLayout

@Composable
fun GameScreen(viewModel: GameViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentDeltas by viewModel.recentDeltas.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showNewGame by remember { mutableStateOf(false) }

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

        Box(Modifier.align(Alignment.Center)) {
            FilledTonalButton(onClick = { menuOpen = true }) { Text("⚙") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Advance turn") },
                    onClick = { menuOpen = false; viewModel.advanceTurn() },
                )
                DropdownMenuItem(
                    text = { Text("Random first player") },
                    onClick = { menuOpen = false; viewModel.randomFirstPlayer() },
                )
                DropdownMenuItem(
                    text = { Text("New game") },
                    onClick = { menuOpen = false; showNewGame = true },
                )
                DropdownMenuItem(
                    text = { Text("Relinquish device owner") },
                    onClick = { menuOpen = false; relinquishDeviceOwner(context) },
                )
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
}

private fun relinquishDeviceOwner(context: Context) {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val pkg = context.packageName
    val message = if (dpm.isDeviceOwnerApp(pkg)) {
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(pkg)
        "Device owner relinquished"
    } else {
        "Not device owner"
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
```

- [ ] **Step 4: Build, install, verify on device**

Run:
```bash
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
"$ADB" shell am start -n com.magictablet/.MainActivity
```
Expected: each panel shows a faint 👑 at its top-end. ⚙ → **Advance turn** brightens/thickens the border on seat 1, then moves it to seat 2, 3, … wrapping. **Random first player** highlights a random seat. Tapping a seat's 👑 brightens it (monarch) and dims any previous one; tapping the monarch's 👑 clears it — and **no life change occurs** on any 👑 tap (verify the number). Life tap/hold and the counters drawer still work.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "M1b Task 2: active-turn highlight + monarch marker + advance/random-first wiring"
```

---

### Task 3: Dice & coin overlay

**Files:**
- Create: `app/src/main/java/com/magictablet/game/ui/DiceOverlay.kt`
- Modify (replace): `app/src/main/java/com/magictablet/screens/GameScreen.kt`

**Interfaces:**
- Produces `DiceOverlay(onDismiss)`. `GameScreen` hub gains **Dice & coin** → shows it.

- [ ] **Step 1: Create `app/src/main/java/com/magictablet/game/ui/DiceOverlay.kt`**

```kotlin
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
```

- [ ] **Step 2: Replace `app/src/main/java/com/magictablet/screens/GameScreen.kt`** (add Dice & coin item + overlay)

```kotlin
package com.magictablet.screens

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameViewModel
import com.magictablet.game.ui.DiceOverlay
import com.magictablet.game.ui.NewGameSheet
import com.magictablet.game.ui.SeatLayout

@Composable
fun GameScreen(viewModel: GameViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentDeltas by viewModel.recentDeltas.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showNewGame by remember { mutableStateOf(false) }
    var showDice by remember { mutableStateOf(false) }

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

        Box(Modifier.align(Alignment.Center)) {
            FilledTonalButton(onClick = { menuOpen = true }) { Text("⚙") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Advance turn") }, onClick = { menuOpen = false; viewModel.advanceTurn() })
                DropdownMenuItem(text = { Text("Random first player") }, onClick = { menuOpen = false; viewModel.randomFirstPlayer() })
                DropdownMenuItem(text = { Text("Dice & coin") }, onClick = { menuOpen = false; showDice = true })
                DropdownMenuItem(text = { Text("New game") }, onClick = { menuOpen = false; showNewGame = true })
                DropdownMenuItem(text = { Text("Relinquish device owner") }, onClick = { menuOpen = false; relinquishDeviceOwner(context) })
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
}

private fun relinquishDeviceOwner(context: Context) {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val pkg = context.packageName
    val message = if (dpm.isDeviceOwnerApp(pkg)) {
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(pkg)
        "Device owner relinquished"
    } else {
        "Not device owner"
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
```

- [ ] **Step 3: Build, install, verify on device**

Run:
```bash
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
"$ADB" shell am start -n com.magictablet/.MainActivity
```
Expected: ⚙ → **Dice & coin** opens a dialog with **Coin / d6 / d20**; tapping each shows a result ("Coin: Heads", "d6: 4", "d20: 17") in range; **Roll again** re-rolls the same kind; **Close** dismisses.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "M1b Task 3: dice & coin overlay (Coin/d6/d20)"
```

---

### Task 4: Game timer (readout chip + hub controls + UI tick)

**Files:**
- Create: `app/src/main/java/com/magictablet/game/ui/TimerChip.kt`
- Modify (replace): `app/src/main/java/com/magictablet/screens/GameScreen.kt`

**Interfaces:**
- Produces `TimerChip(elapsedSeconds, running, modifier)`. `GameScreen` collects `viewModel.timer`, adds Start/Pause + Reset menu items, renders the chip, and drives `tickTimer()` via a `LaunchedEffect`.

- [ ] **Step 1: Create `app/src/main/java/com/magictablet/game/ui/TimerChip.kt`**

```kotlin
package com.magictablet.game.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

@Composable
fun TimerChip(elapsedSeconds: Long, running: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, tonalElevation = 3.dp) {
        Text(
            text = if (running) formatElapsed(elapsedSeconds) else "⏸ ${formatElapsed(elapsedSeconds)}",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
```

- [ ] **Step 2: Replace `app/src/main/java/com/magictablet/screens/GameScreen.kt`** (timer state + tick + chip + controls)

```kotlin
package com.magictablet.screens

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameViewModel
import com.magictablet.game.ui.DiceOverlay
import com.magictablet.game.ui.NewGameSheet
import com.magictablet.game.ui.SeatLayout
import com.magictablet.game.ui.TimerChip
import kotlinx.coroutines.delay

@Composable
fun GameScreen(viewModel: GameViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentDeltas by viewModel.recentDeltas.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showNewGame by remember { mutableStateOf(false) }
    var showDice by remember { mutableStateOf(false) }

    // UI-driven 1s tick so the ViewModel stays coroutine-free and unit-testable.
    LaunchedEffect(timer.running) {
        while (timer.running) {
            delay(1000)
            viewModel.tickTimer()
        }
    }

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

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (timer.running || timer.elapsedSeconds > 0) {
                TimerChip(elapsedSeconds = timer.elapsedSeconds, running = timer.running)
            }
            Box {
                FilledTonalButton(onClick = { menuOpen = true }) { Text("⚙") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Advance turn") }, onClick = { menuOpen = false; viewModel.advanceTurn() })
                    DropdownMenuItem(text = { Text("Random first player") }, onClick = { menuOpen = false; viewModel.randomFirstPlayer() })
                    DropdownMenuItem(text = { Text("Dice & coin") }, onClick = { menuOpen = false; showDice = true })
                    DropdownMenuItem(
                        text = { Text(if (timer.running) "Pause timer" else "Start timer") },
                        onClick = { menuOpen = false; if (timer.running) viewModel.pauseTimer() else viewModel.startTimer() },
                    )
                    DropdownMenuItem(text = { Text("Reset timer") }, onClick = { menuOpen = false; viewModel.resetTimer() })
                    DropdownMenuItem(text = { Text("New game") }, onClick = { menuOpen = false; showNewGame = true })
                    DropdownMenuItem(text = { Text("Relinquish device owner") }, onClick = { menuOpen = false; relinquishDeviceOwner(context) })
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
}

private fun relinquishDeviceOwner(context: Context) {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val pkg = context.packageName
    val message = if (dpm.isDeviceOwnerApp(pkg)) {
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(pkg)
        "Device owner relinquished"
    } else {
        "Not device owner"
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
```

- [ ] **Step 3: Build, install, verify on device**

Run:
```bash
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
"$ADB" shell am start -n com.magictablet/.MainActivity
```
Expected: ⚙ → **Start timer** → a `MM:SS` chip appears above the ⚙ and ticks up each second; the menu item now reads **Pause timer** (pauses, chip shows the ⏸ prefix and stops); **Reset timer** hides the chip. **New game** also clears the timer. Advance turn / dice / life all still work.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "M1b Task 4: simple game timer (readout chip + start/pause/reset + UI tick)"
```

---

## Definition of Done (M1b)

- [ ] ⚙ hub: Advance turn, Random first player, Dice & coin, Start/Pause + Reset timer, New game, Relinquish.
- [ ] Advance turn moves the active-seat highlight (wraps); Random first player lands on a valid seat.
- [ ] Each seat has a 👑; tap claims monarch (exclusive), tap the monarch passes/clears — no life change.
- [ ] Dice overlay rolls Coin/d6/d20 in range; Roll again / Close work.
- [ ] Timer starts/pauses/resets; the chip ticks near the hub and hides on reset; New game clears it.
- [ ] `./gradlew :app:testDebugUnitTest` passes (new turn/monarch/dice/timer tests).

## Self-Review notes

- **Spec coverage:** state+logic+dice+timer (T1) · markers + advance/random/monarch wiring (T2) · dice overlay (T3) · timer chip + controls + tick (T4). Every DoD item maps to a task.
- **Type consistency:** `advanceTurn`/`setActivePlayer`/`toggleMonarch`, `TimerState`, `rollDie`/`flipCoin`, VM intents, and the `PlayerPanel`/`SeatLayout`/`GameScreen` signatures are consistent across tasks; `PlayerPanel`, `SeatLayout`, and `GameScreen` are re-shown in full when their signatures change.
- **Click-through guard:** the monarch 👑 is `clickable`; the M1a `HoldRepeat.kt` fix (`awaitFirstDown()` default) already stops life-zone click-through — the plan explicitly forbids reintroducing `requireUnconsumed = false`.
- **VM testability:** the timer tick is UI-driven (LaunchedEffect), so the VM adds no coroutine/dispatcher and all new logic is unit-tested (T1).
- **Known risks:** hub menu length (7 items — acceptable); crown marker vs top-start badges / rotation (verify on device); timer only advances while the Game screen is composed (fine here).
