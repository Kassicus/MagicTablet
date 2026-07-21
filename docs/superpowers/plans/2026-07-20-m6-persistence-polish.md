# M6 — Persistence & Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the live game survive a reboot (kotlinx.serialization JSON snapshot), add format presets to New Game, and clear the UI-polish / fast-follow backlog — reaching the v1 DoD.

**Architecture:** A `GameStore(file)` autosaves a `GameSnapshot` (whole `GameState` + `nextStackId`) to internal storage; `GameViewModel` stays a plain `ViewModel` with an injected `GamePersistence` (default `NoPersistence`, so unit tests are untouched) and a debounced autosave; `App()` provides the real store once and shares the one `GameViewModel` with Game + Stack. Presets are a small data list + New Game chips. Part C is grouped polish edits.

**Tech Stack:** Kotlin, kotlinx.serialization, Jetpack Compose (Material 3), existing coroutines/lifecycle.

## Global Constraints

From the spec (`docs/superpowers/specs/2026-07-20-m6-persistence-polish-design.md`):

- Persistence = **kotlinx.serialization JSON snapshot**, NOT Room. One live game, restore-after-reboot. Adds exactly one plugin (`org.jetbrains.kotlin.plugin.serialization`, version ref = existing `kotlin`) + one lib (`kotlinx-serialization-json` 1.7.3) via the version catalog. **No other new deps.**
- **Fail-safe:** every persistence read/write is guarded — missing/corrupt snapshot → fresh default game; a failed save is best-effort. Persistence must NEVER crash the app.
- `GameViewModel` stays a **plain `ViewModel`** with `persistence: GamePersistence = NoPersistence` — the existing `GameViewModelTest` keeps calling `GameViewModel()` unchanged.
- **Deferred, NOT in M6:** the M5 `removeActiveAdmin`-on-exit tidy-up; disabling the stock launcher (external `adb`).
- **Environment preamble** (every Gradle/adb command; pass `dangerouslyDisableSandbox: true`):

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
  ```
  Device serial: `TK12110626B081745`.

> **Testing:** Tasks 1, 5, 7 add JVM unit tests (TDD where noted). Tasks 2, 3, 4 finish with an on-device check. Task 6 is code-review-verified robustness (build + install + a happy-path sync if WiFi is available).

---

### Task 1: Persistence core — deps + `@Serializable` + `GameStore` (TDD)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/magictablet/game/GameModels.kt`
- Modify: `app/src/main/java/com/magictablet/game/StackModels.kt`
- Create: `app/src/main/java/com/magictablet/game/GameStore.kt`
- Test: `app/src/test/java/com/magictablet/game/GameStoreTest.kt`

**Interfaces:**
- Produces: `@Serializable GameSnapshot(state: GameState, nextStackId: Long)`, `interface GamePersistence { load(): GameSnapshot?; save(snapshot) }`, `object NoPersistence`, `class GameStore(file: File)`.

- [ ] **Step 1: Write the failing test** — `app/src/test/java/com/magictablet/game/GameStoreTest.kt`

```kotlin
package com.magictablet.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun newStore() = GameStore(File(temp.newFolder(), "game.json"))

    private fun sampleSnapshot(): GameSnapshot {
        val state = initialGame(4, 40)
            .adjustLife(1, -5)
            .adjustPoison(2, 3)
            .adjustCommanderDamage(1, 3, 7)
            .adjustCounter(1, "energy", 2)
            .setActivePlayer(2)
            .toggleMonarch(3)
            .pushStackItem(StackItem(1, 1, "oid-1", StackKind.Spell, "Bolt", "P2"))
            .pushStackItem(StackItem(2, 2, null, StackKind.Triggered, "Draw", ""))
        return GameSnapshot(state, nextStackId = 3)
    }

    @Test fun saveThenLoad_roundTrips() {
        val store = newStore()
        val snapshot = sampleSnapshot()
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }

    @Test fun load_missingFile_returnsNull() {
        assertNull(newStore().load())
    }

    @Test fun load_garbage_returnsNull() {
        val file = File(temp.newFolder(), "game.json")
        file.writeText("{ not valid json ")
        assertNull(GameStore(file).load())
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.game.GameStoreTest"
```
Expected: FAIL — unresolved `GameStore`/`GameSnapshot`.

- [ ] **Step 3: Add the serialization plugin + lib to the version catalog** — in `gradle/libs.versions.toml`, under `[libraries]` add:
```toml
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version = "1.7.3" }
```
and under `[plugins]` add:
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 4: Apply them in `app/build.gradle.kts`** — in the `plugins { }` block, after `alias(libs.plugins.kotlin.compose)` add:
```kotlin
    alias(libs.plugins.kotlin.serialization)
```
and in `dependencies { }`, after `implementation(libs.androidx.material3)` add:
```kotlin
    implementation(libs.kotlinx.serialization.json)
```

- [ ] **Step 5: Annotate the models `@Serializable`**

In `GameModels.kt`, add the import (with the other imports at the top — the file currently starts with `package com.magictablet.game` and top-level `const val`s, so put the import right under the package line):
```kotlin
import kotlinx.serialization.Serializable
```
Then add `@Serializable` immediately above `data class PlayerState(` and above `data class GameState(`.

In `StackModels.kt`, add under the package line:
```kotlin
import kotlinx.serialization.Serializable
```
Then add `@Serializable` immediately above `enum class StackKind {` and above `data class StackItem(`.

- [ ] **Step 6: Create `app/src/main/java/com/magictablet/game/GameStore.kt`**

```kotlin
package com.magictablet.game

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** The persisted unit: the whole live game plus the id counter so restored stack items don't collide. */
@Serializable
data class GameSnapshot(val state: GameState, val nextStackId: Long)

/** Persistence port. [NoPersistence] is the default so context-free unit tests need no store. */
interface GamePersistence {
    fun load(): GameSnapshot?
    fun save(snapshot: GameSnapshot)
}

object NoPersistence : GamePersistence {
    override fun load(): GameSnapshot? = null
    override fun save(snapshot: GameSnapshot) {}
}

/**
 * A single-file JSON snapshot store. Takes the target [file] (not a Context) so it is fully unit-testable.
 * Writes atomically (tmp -> rename); load/save swallow exceptions so persistence can never crash the app.
 */
class GameStore(private val file: File) : GamePersistence {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): GameSnapshot? = try {
        if (!file.exists()) null else json.decodeFromString<GameSnapshot>(file.readText())
    } catch (e: Exception) {
        null
    }

    override fun save(snapshot: GameSnapshot) {
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // best-effort; a failed autosave must never crash the app
        }
    }
}
```

- [ ] **Step 7: Run it, verify it passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.game.GameStoreTest"
```
Expected: PASS (3/3). If the round-trip fails on the maps, that signals a serialization issue — do NOT add custom serializers without checking; `Map<Int,Int>` encodes with string keys by default and should round-trip.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "M6 Task 1: kotlinx.serialization GameStore snapshot + @Serializable models (TDD)"
```

---

### Task 2: Wire persistence into `GameViewModel` + `App`

**Files:**
- Modify: `app/src/main/java/com/magictablet/game/GameViewModel.kt`
- Modify: `app/src/main/java/com/magictablet/App.kt`

**Interfaces:**
- Consumes Task 1 (`GamePersistence`/`NoPersistence`/`GameStore`/`GameSnapshot`).
- Produces: `GameViewModel(persistence)` + `class GameViewModelFactory(persistence): ViewModelProvider.Factory`.

- [ ] **Step 1: Edit `GameViewModel.kt`**

Add imports (with the existing `kotlinx.coroutines.flow.*` imports):
```kotlin
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
```

Change the class declaration + add the opt-in. Replace:
```kotlin
class GameViewModel : ViewModel() {
```
with:
```kotlin
@OptIn(FlowPreview::class)
class GameViewModel(private val persistence: GamePersistence = NoPersistence) : ViewModel() {
```

Add an `init` block that restores + autosaves — insert it immediately after the `private var nextStackId = 1L` line:
```kotlin

    init {
        persistence.load()?.let { snapshot ->
            _state.value = snapshot.state
            nextStackId = snapshot.nextStackId
        }
        _state.drop(1)
            .debounce(AUTOSAVE_DEBOUNCE_MS)
            .onEach { persistence.save(GameSnapshot(_state.value, nextStackId)) }
            .launchIn(viewModelScope)
    }
```

Add the debounce constant — inside the `companion object`, alongside the existing consts:
```kotlin
        const val AUTOSAVE_DEBOUNCE_MS = 500L
```

Add the factory at the end of the file (after the closing brace of `class GameViewModel`):
```kotlin

class GameViewModelFactory(private val persistence: GamePersistence) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = GameViewModel(persistence) as T
}
```

- [ ] **Step 2: Wire the real store in `App.kt`**

Add imports:
```kotlin
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameStore
import com.magictablet.game.GameViewModel
import com.magictablet.game.GameViewModelFactory
import java.io.File
```

Inside `App()`, at the top of the function body (before `var screen by ...`), add:
```kotlin
    val context = LocalContext.current
    val gameViewModel: GameViewModel = viewModel(
        factory = remember { GameViewModelFactory(GameStore(File(context.filesDir, "game.json"))) },
    )
```

Then in the `when (screen)`, replace:
```kotlin
                    Screen.Game -> GameScreen()
                    Screen.Cards -> CardsScreen()
                    Screen.Stack -> StackScreen()
```
with:
```kotlin
                    Screen.Game -> GameScreen(viewModel = gameViewModel)
                    Screen.Cards -> CardsScreen()
                    Screen.Stack -> StackScreen(gameViewModel = gameViewModel)
```

- [ ] **Step 3: Confirm the unit suite still passes** (the existing `GameViewModelTest` uses `GameViewModel()` → `NoPersistence`, so it must be green):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.game.*"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build, install, verify persistence on device**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
./gradlew :app:installDebug
"$ADB" -s TK12110626B081745 shell am start -n com.magictablet/.MainActivity
```
On the tablet: on the Game screen change some life totals, set a monarch, Advance turn, and add a Stack item (Stack tab → Add). Wait ~1s (debounce), then force-stop and relaunch:
```bash
"$ADB" -s TK12110626B081745 shell am force-stop com.magictablet
"$ADB" -s TK12110626B081745 shell am start -n com.magictablet/.MainActivity
```
Confirm the game came back exactly (life totals, monarch, active seat, and the stack item). Then New Game, force-stop + relaunch again → the new game is what restores (not the old one). Confirm `filesDir/game.json` exists:
```bash
"$ADB" -s TK12110626B081745 shell run-as com.magictablet ls -l files/game.json
```
Report what you saw.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "M6 Task 2: inject GameStore into GameViewModel (debounced autosave/restore) + App wiring"
```

---

### Task 3: Format presets in New Game

**Files:**
- Create: `app/src/main/java/com/magictablet/game/GameFormat.kt`
- Modify: `app/src/main/java/com/magictablet/game/ui/NewGameSheet.kt`

- [ ] **Step 1: Create `app/src/main/java/com/magictablet/game/GameFormat.kt`**

```kotlin
package com.magictablet.game

data class GameFormat(val name: String, val playerCount: Int, val startingLife: Int)

val GAME_FORMATS: List<GameFormat> = listOf(
    GameFormat("Commander", 4, 40),
    GameFormat("Commander Duel", 2, 40),
    GameFormat("Standard", 2, 20),
    GameFormat("Multiplayer 20", 4, 20),
)
```

- [ ] **Step 2: Add a preset row to `NewGameSheet.kt`**

Add the import (with the other `com.magictablet` imports — there are none yet, so add it after the last `androidx` import):
```kotlin
import com.magictablet.game.GAME_FORMATS
```

Then, immediately after the `Text("New game")` line, insert:
```kotlin

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
```

- [ ] **Step 3: Build, install, verify on device**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
./gradlew :app:installDebug
"$ADB" -s TK12110626B081745 shell am start -n com.magictablet/.MainActivity
```
On the tablet: ⚙ → New game → confirm a **Format** row of chips shows; tap **Commander Duel** → the Players row selects 2 and the life field becomes 40; tap **Standard** → 2 and 20. Start → the game has that count/life. Screenshot + report.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "M6 Task 3: format presets (GameFormat) in the New Game sheet"
```

---

### Task 4: Game-UI polish (drawer key, crown hit-area, life autosize, seat border)

**Files:**
- Modify: `app/src/main/java/com/magictablet/game/ui/SeatLayout.kt`
- Modify: `app/src/main/java/com/magictablet/game/ui/PlayerPanel.kt`

- [ ] **Step 1: `SeatLayout.kt` — key each panel on `player.id` + pass `anyActive`**

Add the import:
```kotlin
import androidx.compose.runtime.key
```

In the nested `panel` function, add `anyActive` to the `PlayerPanel(...)` call — replace:
```kotlin
            isActive = p.id == activePlayerId,
            isMonarch = p.id == monarchPlayerId,
```
with:
```kotlin
            isActive = p.id == activePlayerId,
            anyActive = activePlayerId != null,
            isMonarch = p.id == monarchPlayerId,
```

Wrap both panel loops in `key(p.id)` — replace:
```kotlin
        Row(Modifier.weight(1f).fillMaxWidth()) {
            topPlayers.forEach { p -> panel(p, Modifier.weight(1f).rotate(180f)) }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            bottomPlayers.forEach { p -> panel(p, Modifier.weight(1f)) }
        }
```
with:
```kotlin
        Row(Modifier.weight(1f).fillMaxWidth()) {
            topPlayers.forEach { p -> key(p.id) { panel(p, Modifier.weight(1f).rotate(180f)) } }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            bottomPlayers.forEach { p -> key(p.id) { panel(p, Modifier.weight(1f)) } }
        }
```

- [ ] **Step 2: `PlayerPanel.kt` — new param, solid border, bigger crown, life font scale**

Add the import:
```kotlin
import androidx.compose.foundation.layout.size
```

Add the `anyActive` parameter — replace:
```kotlin
    isActive: Boolean,
    isMonarch: Boolean,
```
with:
```kotlin
    isActive: Boolean,
    anyActive: Boolean,
    isMonarch: Boolean,
```

Make the border solid when no seat is active (fresh game) — replace:
```kotlin
            .border(
                width = if (isActive) 4.dp else 2.dp,
                color = if (isActive) accent else accent.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            ),
```
with:
```kotlin
            .border(
                width = if (isActive) 4.dp else 2.dp,
                color = if (isActive || !anyActive) accent else accent.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            ),
```

Scale the life font by digit count (Compose BOM 2024.12.01 has no native `autoSize`, so use a size heuristic) — replace:
```kotlin
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = player.life.toString(),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.alpha(if (lost) 0.4f else 1f),
                )
            }
```
with:
```kotlin
            val lifeText = player.life.toString()
            val lifeFontSize = when {
                lifeText.length >= 4 -> 44.sp
                lifeText.length == 3 -> 56.sp
                else -> 72.sp
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = lifeText,
                    fontSize = lifeFontSize,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.alpha(if (lost) 0.4f else 1f),
                )
            }
```

Enlarge the crown tap target to ~44dp — replace:
```kotlin
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .clickable { onToggleMonarch(player.id) },
            ) {
                Text("👑", fontSize = 20.sp, modifier = Modifier.alpha(if (isMonarch) 1f else 0.3f))
            }
```
with:
```kotlin
            Box(
                Modifier.align(Alignment.TopEnd).size(44.dp)
                    .clickable { onToggleMonarch(player.id) },
                contentAlignment = Alignment.Center,
            ) {
                Text("👑", fontSize = 20.sp, modifier = Modifier.alpha(if (isMonarch) 1f else 0.3f))
            }
```

- [ ] **Step 3: Build, install, verify on device**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
./gradlew :app:installDebug
"$ADB" -s TK12110626B081745 shell am start -n com.magictablet/.MainActivity
```
On the tablet: (a) a **fresh game** (New game) shows **solid** seat borders (not faint); after **Advance turn** the active seat is thick/bright and others faint. (b) Expand a player's ▴ counters drawer, then New game with a **different count** → no drawer is stuck open on the wrong seat. (c) The 👑 is easy to tap. (d) Set a player's life to a 3–4 digit value (e.g. tap up past 100, or a big negative) → the number shrinks to fit instead of clipping, especially in a 6-player game. Screenshot + report.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "M6 Task 4: game-UI polish (drawer key, solid fresh-game border, crown hit-area, life font scale)"
```

---

### Task 5: Cards search polish (Searching… state + diacritics fold)

**Files:**
- Modify: `app/src/main/java/com/magictablet/cards/CardQuery.kt`
- Test: `app/src/test/java/com/magictablet/cards/CardQueryTest.kt`
- Modify: `app/src/main/java/com/magictablet/cards/CardsViewModel.kt`
- Modify: `app/src/main/java/com/magictablet/screens/CardsScreen.kt`

- [ ] **Step 1: Add a failing diacritics test** — append inside `CardQueryTest` (class body):

```kotlin
    @Test fun foldsDiacritics() {
        assertEquals("dul*", buildMatchQuery("Dûl"))
        assertEquals("jotun* grunt*", buildMatchQuery("Jötun Grunt"))
    }
```
(If `assertEquals` / `Test` aren't already imported in `CardQueryTest`, add `import org.junit.Assert.assertEquals` and `import org.junit.Test`.)

- [ ] **Step 2: Run it, verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.cards.CardQueryTest"
```
Expected: FAIL — `buildMatchQuery("Dûl")` currently yields `"dûl*"`, not `"dul*"`.

- [ ] **Step 3: Fold diacritics in `CardQuery.kt`** — replace the whole file with:

```kotlin
package com.magictablet.cards

import java.text.Normalizer

private val NON_ALNUM = Regex("[^a-z0-9]+")
private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** NFD-decompose then strip combining marks so accented input matches the index's remove_diacritics folding. */
private fun foldDiacritics(text: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")

/**
 * Build a safe FTS4 MATCH query from raw user text: fold diacritics, lowercase, split on non-alphanumeric
 * runs, drop empties, and append a prefix '*' to each token (as-you-type). Blank input -> "".
 */
fun buildMatchQuery(userText: String): String =
    foldDiacritics(userText).lowercase()
        .split(NON_ALNUM)
        .filter { it.isNotEmpty() }
        .joinToString(" ") { "$it*" }
```

- [ ] **Step 4: Run it, verify it passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.cards.CardQueryTest"
```
Expected: PASS.

- [ ] **Step 5: Add a `searching` state to `CardsViewModel.kt`**

Add the import (with the other `kotlinx.coroutines.flow` imports):
```kotlin
import kotlinx.coroutines.flow.onEach
```

Add the state field — after the `_recent`/`recent` pair, insert:
```kotlin

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
```

In the `results` chain, clear `searching` when results emit — replace:
```kotlin
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```
with:
```kotlin
            .onEach { _searching.value = false }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

Set `searching` when the query changes — replace:
```kotlin
    fun onQueryChange(text: String) { _query.value = text }
```
with:
```kotlin
    fun onQueryChange(text: String) {
        _searching.value = text.isNotBlank()
        _query.value = text
    }
```

- [ ] **Step 6: Show "Searching…" in `CardsScreen.kt`**

Collect the new flow — after:
```kotlin
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
```
add:
```kotlin
    val searching by viewModel.searching.collectAsStateWithLifecycle()
```

Add a branch to the `when` — replace:
```kotlin
            query.isBlank() -> RecentList(recent, viewModel::openCard)
            else -> ResultsList(results, viewModel::openCard)
```
with:
```kotlin
            query.isBlank() -> RecentList(recent, viewModel::openCard)
            searching && results.isEmpty() -> Hint("Searching…")
            else -> ResultsList(results, viewModel::openCard)
```

- [ ] **Step 7: Build, install, verify on device**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
./gradlew :app:installDebug
"$ADB" -s TK12110626B081745 shell am start -n com.magictablet/.MainActivity
```
On the tablet: Cards tab → type a query letter-by-letter → it shows "Searching…" (not a flash of "No matches") until results appear; a nonsense query still ends on "No matches". Report.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "M6 Task 5: cards search polish (Searching… state + diacritics fold in buildMatchQuery)"
```

---

### Task 6: Card Sync robustness (db race, FD-leak, HTTP status)

**Files:**
- Modify: `app/src/main/java/com/magictablet/cards/CardDb.kt`
- Modify: `app/src/main/java/com/magictablet/cards/CardSync.kt`

- [ ] **Step 1: Serialize `CardDb` access with a lock**

Add a lock field — replace:
```kotlin
class CardDb(private val appContext: Context) {
    private var db: SQLiteDatabase? = null
```
with:
```kotlin
class CardDb(private val appContext: Context) {
    private val lock = Any()
    private var db: SQLiteDatabase? = null
```

Wrap `prepare()` — replace:
```kotlin
    fun prepare() {
        if (db != null) return
        ensureSeeded()
        openInternal()
    }
```
with:
```kotlin
    fun prepare() = synchronized(lock) {
        if (db != null) return@synchronized
        ensureSeeded()
        openInternal()
    }
```

Wrap `reopen()` — replace:
```kotlin
    fun reopen() {
        close()
        ensureSeeded()
        openInternal()
    }
```
with:
```kotlin
    fun reopen() = synchronized(lock) {
        db?.close()
        db = null
        ensureSeeded()
        openInternal()
    }
```

Wrap `close()` — replace:
```kotlin
    fun close() {
        db?.close()
        db = null
    }
```
with:
```kotlin
    fun close() = synchronized(lock) {
        db?.close()
        db = null
    }
```

Wrap `search(...)` body — replace:
```kotlin
    fun search(userText: String, limit: Int = 50): List<CardSummary> {
        val match = buildMatchQuery(userText)
```
with:
```kotlin
    fun search(userText: String, limit: Int = 50): List<CardSummary> = synchronized(lock) {
        val match = buildMatchQuery(userText)
```
and change that method's early `return emptyList()` and final `return out` to `return@synchronized` — replace:
```kotlin
        if (match.isEmpty()) return emptyList()
        val conn = db ?: return emptyList()
```
with:
```kotlin
        if (match.isEmpty()) return@synchronized emptyList()
        val conn = db ?: return@synchronized emptyList()
```
and replace the method's final:
```kotlin
        return out
    }
```
with:
```kotlin
        return@synchronized out
    }
```

Wrap `card(...)` body — replace:
```kotlin
    fun card(oracleId: String): CardDetail? {
        val conn = db ?: return null
```
with:
```kotlin
    fun card(oracleId: String): CardDetail? = synchronized(lock) {
        val conn = db ?: return@synchronized null
```
and replace:
```kotlin
        val detail = base ?: return null
```
with:
```kotlin
        val detail = base ?: return@synchronized null
```
and replace the method's final:
```kotlin
        return detail.copy(rulings = rulings)
    }
```
with:
```kotlin
        return@synchronized detail.copy(rulings = rulings)
    }
```

- [ ] **Step 2: Fix the `streamArray` FD-leak in `CardSync.kt`** — replace:

```kotlin
    private fun streamArray(file: File, gz: Boolean, onElement: (JsonReader) -> Unit) {
        val raw = BufferedInputStream(FileInputStream(file))
        val stream = if (gz) GZIPInputStream(raw) else raw
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            r.beginArray()
            while (r.hasNext()) onElement(r)
            r.endArray()
        }
    }
```
with:
```kotlin
    private fun streamArray(file: File, gz: Boolean, onElement: (JsonReader) -> Unit) {
        FileInputStream(file).use { fis ->
            val buffered = BufferedInputStream(fis)
            val stream = if (gz) GZIPInputStream(buffered) else buffered
            JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) onElement(r)
                r.endArray()
            }
        }
    }
```

- [ ] **Step 3: Add an HTTP status check in `CardSync.kt`**

Add a helper — immediately after the `private fun open(urlStr: String): HttpURLConnection { ... }` function, add:
```kotlin

    /** Throw with the status + a snippet of the error body on any non-2xx response. */
    private fun HttpURLConnection.checkOk() {
        val code = responseCode
        if (code / 100 != 2) {
            val body = try {
                errorStream?.use { it.readBytes().toString(Charsets.UTF_8).take(200) }
            } catch (e: Exception) {
                null
            }
            throw java.io.IOException("HTTP $code${if (body.isNullOrBlank()) "" else ": $body"}")
        }
    }
```

Call it in `fetchBulkUrls()` — replace:
```kotlin
        val conn = open(BULK_DATA_URL)
        val json = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
```
with:
```kotlin
        val conn = open(BULK_DATA_URL)
        conn.checkOk()
        val json = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
```

Call it in `download(...)` — replace:
```kotlin
        val conn = open(urlStr)
        conn.setRequestProperty("Accept-Encoding", "gzip")
        val total = conn.contentLengthLong
```
with:
```kotlin
        val conn = open(urlStr)
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.checkOk()
        val total = conn.contentLengthLong
```

- [ ] **Step 4: Build + install + regression-check**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug
"$ADB" -s TK12110626B081745 shell am start -n com.magictablet/.MainActivity
```
Expected: unit suite BUILD SUCCESSFUL; the app installs and card **search** still works (proves the `synchronized` wrapping didn't deadlock or break queries — search several cards). If the tablet has WiFi, run one Cards → ↻ Update sync and confirm it still completes (proves `streamArray` + `checkOk` didn't break the happy path); if no WiFi, note that and rely on the build + review. Report.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "M6 Task 6: Card Sync robustness (CardDb lock, streamArray FD-leak, HTTP status check)"
```

---

### Task 7: Test gaps + release hygiene

**Files:**
- Modify: `app/src/test/java/com/magictablet/game/GameLogicTest.kt`
- Modify: `app/src/test/java/com/magictablet/game/GameViewModelTest.kt`
- Create: `app/proguard-rules.pro`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add loss-reason precedence tests** — append inside `GameLogicTest` (class body). `assertEquals`/`assertFalse`/`assertNull`/`assertTrue` are already imported.

```kotlin
    @Test fun lossReason_zeroLifeTakesPrecedence() {
        val p = PlayerState(id = 1, seat = 1, name = "P1", colorIndex = 0, life = 0, poison = 10, commanderDamage = mapOf(2 to 21))
        assertTrue(p.isLost())
        assertEquals("0 life", p.lossReason())
    }

    @Test fun lossReason_poisonBeforeCommander() {
        val p = PlayerState(id = 1, seat = 1, name = "P1", colorIndex = 0, life = 20, poison = 10, commanderDamage = mapOf(2 to 21))
        assertTrue(p.isLost())
        assertEquals("poison", p.lossReason())
    }

    @Test fun lossReason_commanderDamage() {
        val p = PlayerState(id = 1, seat = 1, name = "P1", colorIndex = 0, life = 20, poison = 0, commanderDamage = mapOf(2 to 21))
        assertTrue(p.isLost())
        assertEquals("cmdr", p.lossReason())
    }

    @Test fun notLost_withEmptyCommanderDamage() {
        val p = PlayerState(id = 1, seat = 1, name = "P1", colorIndex = 0, life = 20)
        assertFalse(p.isLost())
        assertNull(p.lossReason())
    }
```

- [ ] **Step 2: Add a token-monotonicity test** — append inside `GameViewModelTest` (class body). `assertTrue` is already imported.

```kotlin
    @Test fun adjustLife_recentDeltaTokenIsMonotonic() {
        val vm = GameViewModel()
        vm.adjustLife(1, -1)
        val first = vm.recentDeltas.value[1]?.token ?: 0L
        vm.adjustLife(1, -1)
        val second = vm.recentDeltas.value[1]?.token ?: 0L
        assertTrue(second > first)
    }
```

- [ ] **Step 3: Run the new tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.game.GameLogicTest" --tests "com.magictablet.game.GameViewModelTest"
```
Expected: PASS.

- [ ] **Step 4: Add an empty `app/proguard-rules.pro`** (the release `proguardFiles` references it but it's absent):

```proguard
# MagicTablet R8/ProGuard rules.
# Minification is currently disabled (isMinifyEnabled = false); this file exists so the release
# proguardFiles reference resolves, and is the place to add keep rules if minification is enabled later.
```

- [ ] **Step 5: Modernize `kotlinOptions` in `app/build.gradle.kts`**

Remove the deprecated block inside `android { }` — delete:
```kotlin
    kotlinOptions {
        jvmTarget = "17"
    }
```
Then add a top-level `kotlin { }` block immediately after the closing brace of the `android { }` block (before `dependencies {`):
```kotlin
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
```

- [ ] **Step 6: Full build + unit suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL (confirms the `kotlin { compilerOptions }` migration compiles). If the `kotlin { }` block fights the build, revert Step 5 to the `kotlinOptions { jvmTarget = "17" }` form and note it — it's the lowest-value item and must not block the milestone.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "M6 Task 7: fill loss-reason/token test gaps + release hygiene (proguard-rules, kotlin compilerOptions)"
```

---

## Definition of Done (M6)

- [ ] The live game (life/poison/commander-damage/counters, active/monarch, **and the stack**) autosaves and restores after force-stop/reboot; missing/corrupt snapshot → fresh game, no crash.
- [ ] New Game offers format presets that set count + life.
- [ ] Part C landed: drawer-key + solid fresh-game border + crown hit-area + life font scale; cards Searching… + diacritics fold; Card Sync db-lock + FD-leak + HTTP status; the loss-reason/token test gaps; `proguard-rules.pro` + modern `kotlin` compiler options.
- [ ] `GameStoreTest` + the new unit tests pass; the whole suite is green; persistence verified on-device.
- [ ] Reaches the v1 DoD (DESIGN.md §10).

## Self-Review notes

- **Spec coverage:** Part A = Tasks 1–2; Part B = Task 3; Part C = Tasks 4 (game UI), 5 (cards search), 6 (Card Sync), 7 (test gaps + hygiene). The two deferred items (M5 removeActiveAdmin, launcher-disable) are excluded per spec §2.
- **Type consistency:** `GameSnapshot(state, nextStackId)`, `GamePersistence.load/save`, `GameStore(File)`, `GameViewModel(persistence = NoPersistence)`, `GameViewModelFactory(persistence)`, `PlayerPanel(..., anyActive, ...)`, `CardsViewModel.searching`, `buildMatchQuery` — used consistently across tasks and call sites (App passes `viewModel`/`gameViewModel`; SeatLayout passes `anyActive`).
- **No placeholders:** every step shows exact code/edits and the exact command + expected result.
- **Known risks:** `Map<Int,Int>` JSON round-trip (Task 1 test proves it); the life-font heuristic is a digit-count scale, not true autosize (BOM 2024.12.01 lacks `autoSize`); the `synchronized` wrapping in `CardDb` must use `return@synchronized` (early returns) — Task 6 spells this out; the `kotlin { compilerOptions }` migration is the one build-risk item, with a documented revert.
- **Ordering:** Task 1 must land first (adds the plugin/deps the rest compile against). Tasks 4–7 are independent of each other and of 2–3 beyond the shared branch.
