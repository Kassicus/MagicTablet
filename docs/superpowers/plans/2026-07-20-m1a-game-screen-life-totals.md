# M1a — Game Screen (Life Totals + Counters) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the M0 `GameScreen` placeholder into a usable multiplayer life tracker: per-seat rotated panels for 2–6 players, tap/hold life adjust with a fading delta, a poison/commander-damage/counters drawer, new-game setup, and non-destructive loss states.

**Architecture:** A `GameViewModel` holds an in-memory `GameState` (`StateFlow`); pure update functions on `GameState` are unit-tested independently of the ViewModel. The Game screen renders a two-row seat layout (top row rotated 180°). Each panel is a collapsed life view (left/right ± tap zones, hold-to-repeat, fading recent-delta) plus an expandable drawer. A center control hub hosts New game + the relocated device-owner maintenance action. In-memory only — Room persistence is M6.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose (BOM 2024.12.01) + Material 3, AndroidX Lifecycle (ViewModel + runtime-compose), JUnit 4.

## Global Constraints

Every task implicitly includes these. Copied from the spec (`docs/superpowers/specs/2026-07-20-m1a-game-screen-life-totals-design.md`) and the verified machine state:

- **Package root:** `com.magictablet`. New code under `com.magictablet.game` (logic/VM), `com.magictablet.game.ui` (composables), `com.magictablet.screens` (GameScreen), `com.magictablet.ui.theme` (colors).
- **State:** in-memory `GameViewModel` + `StateFlow`. **No Room, no persistence** (that is M6). Only new deps allowed: `androidx.lifecycle:lifecycle-viewmodel-compose` and `androidx.lifecycle:lifecycle-runtime-compose`, version `2.8.7`. No other libraries.
- **Thresholds:** poison loss `>= 10`; commander damage loss `>= 21` (single opponent); life loss `<= 0`. Non-life counts clamp at floor `0`; life may go negative.
- **Commander damage does NOT auto-reduce life** (manual tally — DESIGN.md §2 "not a rules engine").
- **Seat split:** `topCount = playerCount / 2` (floor), `bottomCount = playerCount - topCount`. Top row rotated 180°, bottom upright. Seats 1..topCount on top, rest on bottom. Results: 2→1/1, 3→1/2, 4→2/2, 5→2/3, 6→3/3.
- **Tap zones:** left half = `adjustLife(-1)`, right half = `adjustLife(+1)`. Hold-to-repeat: fire once immediately, then after 400 ms repeat every 120 ms, accelerating to every 40 ms after 2 s of hold.
- **Recent-delta:** accumulates signed across a burst; clears 1000 ms after the last change; UI fades it out.
- **Defaults:** boot into 4 players × 40 life. New-game presets: life 40 / 25 / 20 / custom (clamp 1..999); player count 2–6.
- **Seat palette (colorIndex 0..5):** `#E5484D`, `#FFB224`, `#30A46C`, `#5B8DEF`, `#A56EFF`, `#2EC7C0`.
- **Default names:** `"P{seat}"`. Name editing is deferred to M1b.
- **Orientation/theme:** landscape-locked, dark Material 3 (from M0) — unchanged.
- **Environment preamble** (prefix build/adb commands; shell state does not persist between commands):

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
  ```

- **Sandbox:** Gradle builds (network) and adb/USB commands need the Bash param `dangerouslyDisableSandbox: true`. Install via `./gradlew :app:installDebug` — never Android Studio Run. Target device `TK12110626B081745`.

> **Testing note:** Task 1 is pure logic with full JVM unit tests (TDD). Tasks 2–5 are Compose UI; their gate is build + on-device verification (concrete steps given). The `GameViewModel` accumulator is unit-testable and covered in Task 1.

---

### Task 1: GameViewModel, models, and pure game logic (TDD)

**Files:**
- Modify: `gradle/libs.versions.toml` (add lifecycle)
- Modify: `app/build.gradle.kts` (add lifecycle deps)
- Create: `app/src/main/java/com/magictablet/game/GameModels.kt`
- Create: `app/src/main/java/com/magictablet/game/GameViewModel.kt`
- Test: `app/src/test/java/com/magictablet/game/GameLogicTest.kt`
- Test: `app/src/test/java/com/magictablet/game/GameViewModelTest.kt`

**Interfaces:**
- Produces `GameState`, `PlayerState`, `RecentDelta` data classes; pure fns `initialGame(playerCount, startingLife)`, `seatSplit(n): Pair<Int,Int>`, `GameState.adjustLife/adjustPoison/adjustCommanderDamage/adjustCounter`, `PlayerState.isLost()`, `PlayerState.lossReason()`; constants `POISON_LOSS=10`, `COMMANDER_DAMAGE_LOSS=21`.
- Produces `GameViewModel` with `state: StateFlow<GameState>`, `recentDeltas: StateFlow<Map<Int,RecentDelta>>`, and `newGame`, `adjustLife`, `clearRecentDelta`, `adjustPoison`, `adjustCommanderDamage`, `adjustCounter`.

- [ ] **Step 1: Add lifecycle to `gradle/libs.versions.toml`** — under `[versions]` add `lifecycle = "2.8.7"`; under `[libraries]` add:

```toml
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
```

- [ ] **Step 2: Add deps to `app/build.gradle.kts`** — in the `dependencies { }` block, after `implementation(libs.androidx.activity.compose)`:

```kotlin
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
```

- [ ] **Step 3: Write the failing test** — `app/src/test/java/com/magictablet/game/GameLogicTest.kt`

```kotlin
package com.magictablet.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLogicTest {
    @Test fun initialGame_buildsPlayers() {
        val g = initialGame(4, 40)
        assertEquals(4, g.players.size)
        assertEquals(40, g.startingLife)
        assertEquals(listOf(1, 2, 3, 4), g.players.map { it.id })
        assertEquals(listOf(1, 2, 3, 4), g.players.map { it.seat })
        assertEquals(listOf("P1", "P2", "P3", "P4"), g.players.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), g.players.map { it.colorIndex })
        assertTrue(g.players.all { it.life == 40 && it.poison == 0 })
        assertEquals(mapOf("energy" to 0, "experience" to 0), g.players.first().counters)
    }

    @Test fun seatSplit_isBottomHeavy() {
        assertEquals(1 to 1, seatSplit(2))
        assertEquals(1 to 2, seatSplit(3))
        assertEquals(2 to 2, seatSplit(4))
        assertEquals(2 to 3, seatSplit(5))
        assertEquals(3 to 3, seatSplit(6))
    }

    @Test fun adjustLife_canGoNegative() {
        val g = initialGame(2, 1).adjustLife(1, -3)
        assertEquals(-2, g.players.first { it.id == 1 }.life)
    }

    @Test fun counts_clampAtZero() {
        var g = initialGame(2, 40)
        g = g.adjustPoison(1, -5)
        g = g.adjustCounter(1, "energy", -2)
        g = g.adjustCommanderDamage(1, 2, -4)
        val p = g.players.first { it.id == 1 }
        assertEquals(0, p.poison)
        assertEquals(0, p.counters["energy"])
        assertEquals(0, p.commanderDamage[2] ?: 0)
    }

    @Test fun commanderDamage_isPerOpponent() {
        val g = initialGame(4, 40)
            .adjustCommanderDamage(1, 2, 21)
            .adjustCommanderDamage(1, 3, 5)
        val p = g.players.first { it.id == 1 }
        assertEquals(21, p.commanderDamage[2])
        assertEquals(5, p.commanderDamage[3])
        assertTrue(p.isLost())
        assertEquals("cmdr", p.lossReason())
    }

    @Test fun lossReason_precedence() {
        assertNull(initialGame(2, 40).players.first().lossReason())
        assertEquals("0 life", initialGame(2, 40).adjustLife(1, -40).players.first { it.id == 1 }.lossReason())
        assertEquals("poison", initialGame(2, 40).adjustPoison(1, 10).players.first { it.id == 1 }.lossReason())
        assertFalse(initialGame(2, 40).players.first().isLost())
    }
}
```

- [ ] **Step 4: Run the test, verify it fails** (types not defined)

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.game.GameLogicTest"
```
Expected: FAIL — unresolved references (`initialGame`, `GameState`, etc.).

- [ ] **Step 5: Create `app/src/main/java/com/magictablet/game/GameModels.kt`**

```kotlin
package com.magictablet.game

const val POISON_LOSS = 10
const val COMMANDER_DAMAGE_LOSS = 21
const val SEAT_COLOR_COUNT = 6

data class PlayerState(
    val id: Int,
    val seat: Int,
    val name: String,
    val colorIndex: Int,
    val life: Int,
    val poison: Int = 0,
    val commanderDamage: Map<Int, Int> = emptyMap(),
    val counters: Map<String, Int> = mapOf("energy" to 0, "experience" to 0),
)

data class GameState(
    val startingLife: Int,
    val players: List<PlayerState>,
)

data class RecentDelta(val amount: Int, val token: Long)

fun PlayerState.isLost(): Boolean =
    life <= 0 || poison >= POISON_LOSS || commanderDamage.values.any { it >= COMMANDER_DAMAGE_LOSS }

fun PlayerState.lossReason(): String? = when {
    life <= 0 -> "0 life"
    poison >= POISON_LOSS -> "poison"
    commanderDamage.values.any { it >= COMMANDER_DAMAGE_LOSS } -> "cmdr"
    else -> null
}

/** floor of the count goes to the (rotated) top row, ceil to the bottom row. */
fun seatSplit(playerCount: Int): Pair<Int, Int> {
    val top = playerCount / 2
    return top to (playerCount - top)
}

fun initialGame(playerCount: Int, startingLife: Int): GameState {
    val players = (1..playerCount).map { seat ->
        PlayerState(
            id = seat,
            seat = seat,
            name = "P$seat",
            colorIndex = (seat - 1) % SEAT_COLOR_COUNT,
            life = startingLife,
        )
    }
    return GameState(startingLife = startingLife, players = players)
}

private fun GameState.updatePlayer(playerId: Int, transform: (PlayerState) -> PlayerState): GameState =
    copy(players = players.map { if (it.id == playerId) transform(it) else it })

fun GameState.adjustLife(playerId: Int, delta: Int): GameState =
    updatePlayer(playerId) { it.copy(life = it.life + delta) }

fun GameState.adjustPoison(playerId: Int, delta: Int): GameState =
    updatePlayer(playerId) { it.copy(poison = (it.poison + delta).coerceAtLeast(0)) }

fun GameState.adjustCommanderDamage(playerId: Int, fromOpponentId: Int, delta: Int): GameState =
    updatePlayer(playerId) {
        val next = ((it.commanderDamage[fromOpponentId] ?: 0) + delta).coerceAtLeast(0)
        it.copy(commanderDamage = it.commanderDamage + (fromOpponentId to next))
    }

fun GameState.adjustCounter(playerId: Int, counter: String, delta: Int): GameState =
    updatePlayer(playerId) {
        val next = ((it.counters[counter] ?: 0) + delta).coerceAtLeast(0)
        it.copy(counters = it.counters + (counter to next))
    }
```

- [ ] **Step 6: Create `app/src/main/java/com/magictablet/game/GameViewModel.kt`**

```kotlin
package com.magictablet.game

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class GameViewModel : ViewModel() {
    private val _state = MutableStateFlow(initialGame(DEFAULT_PLAYER_COUNT, DEFAULT_STARTING_LIFE))
    val state: StateFlow<GameState> = _state.asStateFlow()

    private val _recentDeltas = MutableStateFlow<Map<Int, RecentDelta>>(emptyMap())
    val recentDeltas: StateFlow<Map<Int, RecentDelta>> = _recentDeltas.asStateFlow()

    fun newGame(playerCount: Int, startingLife: Int) {
        _state.value = initialGame(playerCount, startingLife)
        _recentDeltas.value = emptyMap()
    }

    fun adjustLife(playerId: Int, delta: Int) {
        _state.update { it.adjustLife(playerId, delta) }
        _recentDeltas.update { current ->
            val existing = current[playerId]
            current + (playerId to RecentDelta(
                amount = (existing?.amount ?: 0) + delta,
                token = (existing?.token ?: 0L) + 1L,
            ))
        }
    }

    fun clearRecentDelta(playerId: Int) {
        _recentDeltas.update { it - playerId }
    }

    fun adjustPoison(playerId: Int, delta: Int) = _state.update { it.adjustPoison(playerId, delta) }

    fun adjustCommanderDamage(playerId: Int, fromOpponentId: Int, delta: Int) =
        _state.update { it.adjustCommanderDamage(playerId, fromOpponentId, delta) }

    fun adjustCounter(playerId: Int, counter: String, delta: Int) =
        _state.update { it.adjustCounter(playerId, counter, delta) }

    companion object {
        const val DEFAULT_PLAYER_COUNT = 4
        const val DEFAULT_STARTING_LIFE = 40
    }
}
```

- [ ] **Step 7: Create `app/src/test/java/com/magictablet/game/GameViewModelTest.kt`**

```kotlin
package com.magictablet.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameViewModelTest {
    @Test fun defaultGame_isFourByForty() {
        val vm = GameViewModel()
        assertEquals(4, vm.state.value.players.size)
        assertEquals(40, vm.state.value.startingLife)
    }

    @Test fun adjustLife_accumulatesRecentDelta() {
        val vm = GameViewModel()
        vm.adjustLife(1, -1)
        vm.adjustLife(1, -1)
        vm.adjustLife(1, -1)
        assertEquals(-3, vm.recentDeltas.value[1]?.amount)
        assertEquals(37, vm.state.value.players.first { it.id == 1 }.life)
    }

    @Test fun clearRecentDelta_removesEntry() {
        val vm = GameViewModel()
        vm.adjustLife(2, 5)
        vm.clearRecentDelta(2)
        assertNull(vm.recentDeltas.value[2])
    }

    @Test fun newGame_resetsState() {
        val vm = GameViewModel()
        vm.adjustLife(1, -10)
        vm.newGame(2, 20)
        assertEquals(2, vm.state.value.players.size)
        assertEquals(20, vm.state.value.players.first { it.id == 1 }.life)
        assertEquals(emptyMap<Int, RecentDelta>(), vm.recentDeltas.value)
    }
}
```

- [ ] **Step 8: Run all game tests, verify they pass**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.game.*"
```
Expected: `BUILD SUCCESSFUL`, all tests pass. (This also confirms the lifecycle deps resolve.)

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "M1a Task 1: GameViewModel + in-memory game state and pure logic (TDD)"
```

---

### Task 2: Seat layout + interactive collapsed panel

**Files:**
- Create: `app/src/main/java/com/magictablet/ui/theme/SeatColors.kt`
- Create: `app/src/main/java/com/magictablet/game/ui/HoldRepeat.kt`
- Create: `app/src/main/java/com/magictablet/game/ui/PlayerPanel.kt`
- Create: `app/src/main/java/com/magictablet/game/ui/SeatLayout.kt`
- Modify (replace): `app/src/main/java/com/magictablet/screens/GameScreen.kt`

**Interfaces:**
- Consumes Task 1's `GameViewModel`, `PlayerState`, `RecentDelta`, `seatSplit`.
- Produces `SeatLayout(...)`, `PlayerPanel(...)`, `Modifier.holdRepeatClick(onClick)`, `SeatColors: List<Color>`.
- Note: the M0 "Relinquish device owner" button/helper is removed from `GameScreen` here and **relocated to the control hub in Task 4** (device is currently un-owned, so no functional gap on the merged branch).

- [ ] **Step 1: Create `app/src/main/java/com/magictablet/ui/theme/SeatColors.kt`**

```kotlin
package com.magictablet.ui.theme

import androidx.compose.ui.graphics.Color

/** Seat accent colors indexed by PlayerState.colorIndex (0..5). */
val SeatColors: List<Color> = listOf(
    Color(0xFFE5484D), // P1 red
    Color(0xFFFFB224), // P2 amber
    Color(0xFF30A46C), // P3 green
    Color(0xFF5B8DEF), // P4 blue
    Color(0xFFA56EFF), // P5 purple
    Color(0xFF2EC7C0), // P6 teal
)
```

- [ ] **Step 2: Create `app/src/main/java/com/magictablet/game/ui/HoldRepeat.kt`**

```kotlin
package com.magictablet.game.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val INITIAL_DELAY_MS = 400L
private const val REPEAT_MS = 120L
private const val ACCEL_AFTER_MS = 2000L
private const val FAST_REPEAT_MS = 40L

/** Fires [onClick] once on press, then auto-repeats (accelerating) while held. */
fun Modifier.holdRepeatClick(onClick: () -> Unit): Modifier = composed {
    val current = rememberUpdatedState(onClick)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            current.value()
            val job = launch {
                delay(INITIAL_DELAY_MS)
                var elapsed = 0L
                var interval = REPEAT_MS
                while (isActive) {
                    current.value()
                    delay(interval)
                    elapsed += interval
                    if (elapsed >= ACCEL_AFTER_MS) interval = FAST_REPEAT_MS
                }
            }
            waitForUpOrCancellation()
            job.cancel()
        }
    }
}
```

- [ ] **Step 3: Create `app/src/main/java/com/magictablet/game/ui/PlayerPanel.kt`**

```kotlin
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
```

- [ ] **Step 4: Create `app/src/main/java/com/magictablet/game/ui/SeatLayout.kt`**

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
    onAdjustLife: (playerId: Int, delta: Int) -> Unit,
    onClearDelta: (playerId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (topCount, _) = seatSplit(players.size)
    val topPlayers = players.take(topCount)
    val bottomPlayers = players.drop(topCount)

    Column(modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            topPlayers.forEach { p ->
                PlayerPanel(
                    player = p,
                    recentDelta = recentDeltas[p.id],
                    onAdjustLife = onAdjustLife,
                    onClearDelta = onClearDelta,
                    modifier = Modifier.weight(1f).rotate(180f),
                )
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            bottomPlayers.forEach { p ->
                PlayerPanel(
                    player = p,
                    recentDelta = recentDeltas[p.id],
                    onAdjustLife = onAdjustLife,
                    onClearDelta = onClearDelta,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
```

- [ ] **Step 5: Replace `app/src/main/java/com/magictablet/screens/GameScreen.kt`** (removes the M0 relinquish button — relocated in Task 4)

```kotlin
package com.magictablet.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameViewModel
import com.magictablet.game.ui.SeatLayout

@Composable
fun GameScreen(viewModel: GameViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentDeltas by viewModel.recentDeltas.collectAsStateWithLifecycle()

    SeatLayout(
        players = state.players,
        recentDeltas = recentDeltas,
        onAdjustLife = viewModel::adjustLife,
        onClearDelta = viewModel::clearRecentDelta,
        modifier = Modifier.fillMaxSize(),
    )
}
```

- [ ] **Step 6: Build, install, verify on device**

Run:
```bash
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
"$ADB" shell am start -n com.magictablet/.MainActivity
```
Expected on device: the Game tab shows **4 panels** (2×2), top two rotated 180°, each showing **40** with a colored border. Tapping the left half of a panel decrements, right half increments; **press-and-hold** auto-repeats and accelerates; a colored **delta** (e.g. `-3`) appears while adjusting and fades ~1s after you stop. Cards/Stack tabs unchanged.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "M1a Task 2: rotated seat layout + interactive life panels (tap/hold/delta)"
```

---

### Task 3: Expandable drawer + collapsed badges

**Files:**
- Create: `app/src/main/java/com/magictablet/game/ui/PlayerDrawer.kt`
- Modify (replace): `app/src/main/java/com/magictablet/game/ui/PlayerPanel.kt`
- Modify (replace): `app/src/main/java/com/magictablet/game/ui/SeatLayout.kt`
- Modify (replace): `app/src/main/java/com/magictablet/screens/GameScreen.kt`

**Interfaces:**
- `PlayerPanel` and `SeatLayout` gain `allPlayers: List<PlayerState>` and drawer callbacks `onAdjustPoison`, `onAdjustCommanderDamage`, `onAdjustCounter`. `GameScreen` wires them to the VM.
- Produces `PlayerDrawer(player, opponents, onAdjustPoison, onAdjustCommanderDamage, onAdjustCounter, onCollapse)`.

- [ ] **Step 1: Create `app/src/main/java/com/magictablet/game/ui/PlayerDrawer.kt`**

```kotlin
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
    onAdjustPoison: (delta: Int) -> Unit,
    onAdjustCommanderDamage: (fromOpponentId: Int, delta: Int) -> Unit,
    onAdjustCounter: (counter: String, delta: Int) -> Unit,
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
```

- [ ] **Step 2: Replace `app/src/main/java/com/magictablet/game/ui/PlayerPanel.kt`** (adds chevron, expandable drawer, badges)

```kotlin
package com.magictablet.game.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magictablet.game.PlayerState
import com.magictablet.game.RecentDelta
import com.magictablet.ui.theme.SeatColors

@Composable
fun PlayerPanel(
    player: PlayerState,
    opponents: List<PlayerState>,
    recentDelta: RecentDelta?,
    onAdjustLife: (playerId: Int, delta: Int) -> Unit,
    onClearDelta: (playerId: Int) -> Unit,
    onAdjustPoison: (playerId: Int, delta: Int) -> Unit,
    onAdjustCommanderDamage: (playerId: Int, fromOpponentId: Int, delta: Int) -> Unit,
    onAdjustCounter: (playerId: Int, counter: String, delta: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = SeatColors[player.colorIndex % SeatColors.size]
    var expanded by remember { mutableStateOf(false) }

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
            // Tap zones (bottom layer).
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().holdRepeatClick { onAdjustLife(player.id, -1) })
                Box(Modifier.weight(1f).fillMaxHeight().holdRepeatClick { onAdjustLife(player.id, 1) })
            }

            // Life number.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = player.life.toString(),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            // Recent delta (top-center), fades on clear.
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

            // Collapsed badges (top-start): poison / commander damage.
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

            // Chevron to open the drawer (bottom-center); consumes its own tap area.
            TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Text("▴ counters", fontSize = 13.sp, color = accent)
            }
        }
    }
}
```

- [ ] **Step 3: Replace `app/src/main/java/com/magictablet/game/ui/SeatLayout.kt`** (passes opponents + drawer callbacks)

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
    onAdjustLife: (playerId: Int, delta: Int) -> Unit,
    onClearDelta: (playerId: Int) -> Unit,
    onAdjustPoison: (playerId: Int, delta: Int) -> Unit,
    onAdjustCommanderDamage: (playerId: Int, fromOpponentId: Int, delta: Int) -> Unit,
    onAdjustCounter: (playerId: Int, counter: String, delta: Int) -> Unit,
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
            onAdjustLife = onAdjustLife,
            onClearDelta = onClearDelta,
            onAdjustPoison = onAdjustPoison,
            onAdjustCommanderDamage = onAdjustCommanderDamage,
            onAdjustCounter = onAdjustCounter,
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

- [ ] **Step 4: Replace `app/src/main/java/com/magictablet/screens/GameScreen.kt`** (wire drawer callbacks)

```kotlin
package com.magictablet.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameViewModel
import com.magictablet.game.ui.SeatLayout

@Composable
fun GameScreen(viewModel: GameViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentDeltas by viewModel.recentDeltas.collectAsStateWithLifecycle()

    SeatLayout(
        players = state.players,
        recentDeltas = recentDeltas,
        onAdjustLife = viewModel::adjustLife,
        onClearDelta = viewModel::clearRecentDelta,
        onAdjustPoison = viewModel::adjustPoison,
        onAdjustCommanderDamage = viewModel::adjustCommanderDamage,
        onAdjustCounter = viewModel::adjustCounter,
        modifier = Modifier.fillMaxSize(),
    )
}
```

- [ ] **Step 5: Build, install, verify on device**

Run:
```bash
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
"$ADB" shell am start -n com.magictablet/.MainActivity
```
Expected: each panel shows a **"▴ counters"** control; tapping it opens a drawer (rotated correctly for top-row seats) with **Poison**, a **commander-damage grid** of opponent chips (color-coded, ± each), and **Energy**/**Exp** rows; all clamp at 0. Raising poison>0 or any CMD>0 shows a small **☠**/`CMD` badge on the collapsed panel. **Collapse** returns to the life view; life tap/hold still works.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "M1a Task 3: expandable poison/commander-damage/counters drawer + badges"
```

---

### Task 4: Center control hub + new-game setup + relinquish relocation

**Files:**
- Create: `app/src/main/java/com/magictablet/game/ui/NewGameSheet.kt`
- Modify (replace): `app/src/main/java/com/magictablet/screens/GameScreen.kt`

**Interfaces:**
- Produces `NewGameSheet(currentCount, currentLife, onStart, onDismiss)`.
- `GameScreen` gains a centered hub button → dropdown with **New game** (opens the sheet) and **Relinquish device owner** (the relocated M0 maintenance action, `clearDeviceOwnerApp`).

- [ ] **Step 1: Create `app/src/main/java/com/magictablet/game/ui/NewGameSheet.kt`**

```kotlin
package com.magictablet.game.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New game")

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
```

- [ ] **Step 2: Replace `app/src/main/java/com/magictablet/screens/GameScreen.kt`** (add center hub + relocated relinquish)

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
            onAdjustLife = viewModel::adjustLife,
            onClearDelta = viewModel::clearRecentDelta,
            onAdjustPoison = viewModel::adjustPoison,
            onAdjustCommanderDamage = viewModel::adjustCommanderDamage,
            onAdjustCounter = viewModel::adjustCounter,
            modifier = Modifier.fillMaxSize(),
        )

        Box(Modifier.align(Alignment.Center)) {
            FilledTonalButton(onClick = { menuOpen = true }) { Text("⚙") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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

- [ ] **Step 3: Build, install, verify on device**

Run:
```bash
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
"$ADB" shell am start -n com.magictablet/.MainActivity
```
Expected: a **⚙** button sits at screen center; tapping it opens a menu with **New game** and **Relinquish device owner**. New game opens the sheet — change player count (2–6) and starting life (40/25/20 or custom), **Start** rebuilds the table (e.g. pick 6 → six panels, 3 rotated). Relinquish shows the "Not device owner" toast (device is un-owned).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "M1a Task 4: center control hub, new-game sheet, relocated relinquish action"
```

---

### Task 5: Loss-state treatment

**Files:**
- Modify (replace): `app/src/main/java/com/magictablet/game/ui/PlayerPanel.kt` (add dim + skull + reason overlay)

**Interfaces:**
- Consumes `PlayerState.isLost()` / `lossReason()` from Task 1. No signature change.

- [ ] **Step 1: Replace `app/src/main/java/com/magictablet/game/ui/PlayerPanel.kt`** (identical to Task 3's version plus a loss overlay on the collapsed view)

```kotlin
package com.magictablet.game.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
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
    onAdjustLife: (playerId: Int, delta: Int) -> Unit,
    onClearDelta: (playerId: Int) -> Unit,
    onAdjustPoison: (playerId: Int, delta: Int) -> Unit,
    onAdjustCommanderDamage: (playerId: Int, fromOpponentId: Int, delta: Int) -> Unit,
    onAdjustCounter: (playerId: Int, counter: String, delta: Int) -> Unit,
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
            .border(2.dp, accent, RoundedCornerShape(12.dp)),
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
            // Tap zones stay active even when lost (life can recover).
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().holdRepeatClick { onAdjustLife(player.id, -1) })
                Box(Modifier.weight(1f).fillMaxHeight().holdRepeatClick { onAdjustLife(player.id, 1) })
            }

            // Life number, dimmed when lost.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = player.life.toString(),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.alpha(if (lost) 0.4f else 1f),
                )
            }

            // Recent delta.
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

            // Loss overlay: skull + reason.
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

            // Collapsed badges.
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

            TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Text("▴ counters", fontSize = 13.sp, color = accent)
            }
        }
    }
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
Expected: hold a panel's left zone down to 0 → the life number dims and a **☠ 0 life** appears, but the panel is still tappable (tap right to climb back → the loss state clears). Same treatment when poison hits 10 (**☠ poison**) or a single opponent's commander damage hits 21 (**☠ cmdr**).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "M1a Task 5: non-destructive loss-state treatment (dim + skull + reason)"
```

---

## Definition of Done (M1a)

- [ ] Boots into a playable 4×40 game; four seat panels (2 rotated 180°).
- [ ] Player counts 2–6 render with the correct split (1/1, 1/2, 2/2, 2/3, 3/3) and top-row rotation.
- [ ] Left/right tap = ∓1; press-and-hold auto-repeats and accelerates; recent-delta shows and fades.
- [ ] Drawer opens per seat (rotated) with poison, per-opponent commander-damage grid, Energy/Experience; all clamp at 0, life may go negative.
- [ ] New-game sheet changes player count + starting life and resets the game.
- [ ] Loss conditions (life ≤ 0 / poison ≥ 10 / cmdr ≥ 21) dim the panel with a skull + reason, still interactive.
- [ ] Device-owner maintenance (relinquish) reachable from the center hub.
- [ ] `./gradlew :app:testDebugUnitTest` passes (Task 1 logic + VM tests).

## Self-Review notes

- **Spec coverage:** VM/state/logic (T1) · rotated seat layout + collapsed panel interaction (T2) · drawer + badges (T3) · new-game setup + relocated maintenance (T4) · loss states (T5). Every DoD item maps to a task.
- **Spec reconciliation:** the spec said the M0 relinquish button is "untouched," but it lived in `GameScreen`, which M1a rewrites. Resolved by **relocating** it into the Task 4 control hub — the maintenance path is preserved, just moved. (Flag for the human at plan review.)
- **Type consistency:** `GameViewModel`, `GameState`, `PlayerState`, `RecentDelta`, `seatSplit`, `isLost`/`lossReason`, `SeatColors`, `PlayerPanel`/`SeatLayout`/`PlayerDrawer`/`NewGameSheet` signatures are consistent across tasks; `PlayerPanel` is fully re-shown in T3 and T5 when its signature/body changes.
- **Dependency discipline:** only `lifecycle-viewmodel-compose` + `lifecycle-runtime-compose` (2.8.7) added; no Room, no persistence, no other libs.
- **Known risks:** rotated hit-testing (verify on device, T2); FlowRow/ModalBottomSheet are `@OptIn` experimental but stable in Compose 1.7; commander-damage grid density at 6p handled by the scrollable drawer; hold-repeat curve tuned on device.
