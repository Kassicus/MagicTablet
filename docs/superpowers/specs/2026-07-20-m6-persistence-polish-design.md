# M6 — Persistence & Polish — Design Spec

**Date:** 2026-07-20
**Milestone:** M6 (DESIGN.md §8, the final milestone) — reaches the v1 DoD (§10).
**Status:** Approved design, pending spec review

> Master brief: `DESIGN.md` §8 (M6 = "Room save/restore of live state, config presets … data-refresh") + §10 (v1 DoD).
> M6 makes the live game survive a reboot (the last v1 blocker), adds format presets, and clears the accumulated
> UI-polish / fast-follow backlog. **Persistence uses a kotlinx.serialization JSON snapshot, not Room** — the app
> deliberately dropped Room for cards in M3 (raw SQLite), and the need here is a single live-game snapshot, not a
> queryable history. (The "data-refresh mechanism" in §8 is already shipped: Card Sync + the CR reader.)

---

## 1. Purpose

Three parts, one milestone:
- **A. Persistence** — autosave the live `GameState` (incl. the stack) to internal storage and restore it on launch,
  so a kiosk reboot doesn't wipe the game. The v1 DoD blocker.
- **B. Presets** — named format quick-picks (Commander 4×40, etc.) in the New Game sheet.
- **C. Polish sweep** — the backlogged UI bugs, Card Sync robustness fixes, test gaps, and release hygiene.

## 2. Scope

**In scope:** Parts A, B, C below.

**Out of scope (explicitly deferred):**
- The M5 `removeActiveAdmin`-on-exit tidy-up (a privileged call in the sole kiosk escape-hatch path — wants its own
  dedicated on-device test; not bundled into a large branch).
- Disabling the stock launcher (an external `adb` provisioning step, documented in the M5 runbook — not code).
- Multiple saved games / save history / undo (single live snapshot only — YAGNI).
- Remembering the last-used format preset (the sheet already defaults to the current count/life).

## 3. Part A — Game-state persistence

### 3.1 Dependencies
Add (matched to the existing Kotlin 2.1.0): the **Kotlin serialization compiler plugin**
(`org.jetbrains.kotlin.plugin.serialization`, version ref = the existing `kotlin`) and
**`org.jetbrains.kotlinx:kotlinx-serialization-json`** (1.7.3). Both go through the version catalog
(`gradle/libs.versions.toml`) + `app/build.gradle.kts`. No other new deps.

### 3.2 Serializable models
Annotate `@Serializable` on `GameState`, `PlayerState`, `StackItem`, and `StackKind` (enum) in
`game/GameModels.kt` / `game/StackModels.kt`. The `Map<Int,Int>` (commander damage), `Map<String,Int>` (counters),
the `List<StackItem>`, and the enum all serialize with **no custom converters**. `TimerState`/`RecentDelta` are
**not** annotated (transient, not persisted).

### 3.3 `game/GameStore.kt`
- `@Serializable data class GameSnapshot(val state: GameState, val nextStackId: Long)` — the persisted unit
  (`nextStackId` is carried so restored stack items don't collide with new ones).
- `interface GamePersistence { fun load(): GameSnapshot?; fun save(snapshot: GameSnapshot) }`.
- `object NoPersistence : GamePersistence` — no-op (load→null, save→{}); the default so unit tests need no context.
- `class GameStore(file: File) : GamePersistence` — reads/writes the given `file` via `Json { ignoreUnknownKeys = true }`;
  `save` writes **atomically** (`<file>.tmp` → `renameTo`); both `load` and `save` swallow exceptions
  (`load`→`null`, `save`→best-effort) so persistence can never crash the app. Taking a `File` (not a `Context`) keeps
  the whole store **unit-testable** with a JUnit `@TempDir`.

### 3.4 `GameViewModel` (stays a plain `ViewModel`; injected persistence)
- Constructor gains `persistence: GamePersistence = NoPersistence` — existing `GameViewModelTest` keeps calling
  `GameViewModel()` (→ `NoPersistence`, no context) unchanged.
- `init`: `persistence.load()?.let { restore _state = it.state; nextStackId = it.nextStackId }` (else the current
  `initialGame` default stands).
- **Autosave:** a `viewModelScope` coroutine — `_state.drop(1).debounce(500).collect { persistence.save(GameSnapshot(_state.value, nextStackId)) }`
  (`@OptIn(FlowPreview::class)`, mirroring `CardsViewModel`'s `debounce` usage) — coalesces rapid taps into one write.
- `newGame`/every mutation flows through `_state`, so the autosave picks them up; no explicit save calls scattered around.

### 3.5 Wiring (`App.kt`)
`App()` constructs the single shared `GameViewModel` via a `GameViewModelFactory(GameStore(File(context.filesDir, "game.json")))`
(a `ViewModelProvider.Factory`) using `viewModel(factory = remember { … })`, and passes that instance to **both**
`GameScreen(viewModel = gameViewModel)` and `StackScreen(gameViewModel = gameViewModel)` (they already share it via
the Activity `ViewModelStore`). `CardsViewModel`/`CrViewModel` keep their plain `viewModel()`. The screens keep their
`= viewModel()` default params (for previews/tests); production injection happens once at `App`.

### 3.6 Semantics
- Restore on launch if a snapshot exists; **missing or corrupt → a fresh default game** (never crash).
- New Game overwrites the snapshot (via the autosave after `_state` changes).
- **Dropped** (transient, reset on restart): `recentDeltas`, `lastResolved`, and the session `timer`.

### 3.7 Testing
- Pure JVM `GameStoreTest` (JUnit `@TempDir`): `save(snapshot)` then `load()` **round-trips** a state with a
  commander-damage map, non-default counters, a 2–3 item stack, priority fields, and monarch/active set (loaded
  snapshot **equals** the original — verifying `@Serializable` covers the maps/enum/stack); `load()` on a
  **missing** file → `null`; `load()` on a file containing **garbage** → `null` (no throw).
- On-device: play a game (life changes + a stack item), force-stop / reboot, relaunch → the game is restored;
  New Game then restores to the new game after a reboot.

## 4. Part B — Config / format presets

- **`game/GameFormat.kt`:** `data class GameFormat(val name: String, val playerCount: Int, val startingLife: Int)`
  and `val GAME_FORMATS = listOf(GameFormat("Commander", 4, 40), GameFormat("Commander Duel", 2, 40),
  GameFormat("Standard", 2, 20), GameFormat("Multiplayer 20", 4, 20))`.
- **New Game sheet** (`game/ui/NewGameSheet.kt`): a preset chip row at the top; tapping a chip sets the sheet's
  count + life state (the existing count/life pickers + Custom life remain). No persistence of the choice.
- Test: a trivial `GameFormat` sanity check (counts in 2..6, life > 0) is optional; presets are otherwise UI —
  verified on-device (tap a preset → the count/life pickers update → Start yields that game).

## 5. Part C — Polish / fast-follow sweep

Grouped; each item names its file. The implementation plan carries the verbatim code.

**C1 — Game UI (`screens/GameScreen.kt`, `game/ui/SeatLayout.kt`, `game/ui/PlayerPanel.kt`):**
- Key `PlayerPanel`'s `expanded` drawer state on `player.id` (wrap in `key(player.id)` in `SeatLayout`) so a drawer
  left open doesn't re-open on a different player after a new-game count change.
- Enlarge the crown 👑 tap target (min touch size instead of padding-before-`clickable`).
- **Autosize** the life number (fixed 72sp can clip at custom 999 / large negatives on ⅓-width 6-player panels).
- Render the active-seat border **solid** when `activePlayerId == null` (fresh game), not 50% alpha.
- Remove the unused `androidx.compose.foundation.layout.padding` import in `GameScreen.kt`.

**C2 — Cards (`cards/CardsViewModel.kt`, `screens/CardsScreen.kt`, `cards/CardQuery.kt`):**
- Add a "Searching…" state so the ~250ms gap while typing doesn't flash "No matches".
- Normalize query **diacritics** in `buildMatchQuery` to match the index's `remove_diacritics=2` folding (so
  "Lim-Dûl" typed with the accent still matches).

**C3 — Card Sync robustness (`cards/CardDb.kt`, `cards/CardSync.kt`):**
- Guard `CardDb`'s bare `var db` so a `search()` concurrent with `reopen()` can't crash (a lock / `@Volatile` + swap).
- Fix the `streamArray` FD-leak (open the raw stream inside the `use { }` so a `GZIPInputStream` ctor throw still closes it).
- Check the HTTP `responseCode` and drain `errorStream` (4xx/5xx currently surface as a cryptic `FileNotFoundException`).

**C4 — Test gaps (`game/…Test.kt`):**
- `RecentDelta.token` monotonicity across repeated `adjustLife`; `lossReason` precedence (poison vs commander damage
  vs 0 life); `isLost`/`lossReason` with empty `commanderDamage`.

**C5 — Release hygiene (`app/build.gradle.kts`, `app/proguard-rules.pro`):**
- Add an empty `proguard-rules.pro` (referenced but absent).
- Modernize `kotlinOptions` → the non-deprecated `compilerOptions { jvmTarget = … }` form.

## 6. Architecture / decisions

- **Snapshot, not ORM:** one JSON file, whole-state, autosaved+debounced — matches "one live game survives reboot."
  Continues the app's lightweight, Room-free direction (records the §8 "Room" wording as superseded, like the
  FTS5→FTS4 and Room-cards→raw-SQLite pivots before it).
- **Injected `GamePersistence`:** keeps `GameViewModel` a context-free plain `ViewModel` (pure unit tests unchanged),
  with the real `GameStore` provided once at `App` via a factory. The stack shares that single instance (Activity-scoped).
- **Fail-safe persistence:** every read/write is guarded — a corrupt/absent snapshot or a failed write degrades to a
  default game / a skipped save, never a crash. Critical for an unattended kiosk.

## 7. Definition of done (M6)

- [ ] The live game (players' life/poison/commander-damage/counters, active/monarch, **and the stack**) is autosaved and
      restored after an app restart / reboot; missing/corrupt snapshot → a fresh game, no crash.
- [ ] New Game sheet offers format presets (Commander 4×40 etc.) that set count + life.
- [ ] Part C items landed (or explicitly trimmed during planning): the game-UI fixes, cards flicker + diacritics,
      Card Sync robustness (db race, FD-leak, HTTP code), the M1 test gaps, and release hygiene.
- [ ] `GameStoreTest` (JSON round-trip) + the new unit tests pass; the whole suite is green; persistence verified on-device.
- [ ] Reaches the v1 DoD (DESIGN.md §10).

## 8. Risks / seams

- **Serialization stability:** the models are plain data classes; adding fields later requires `ignoreUnknownKeys`
  (set) for forward-compat and defaults for back-compat. A schema change that renames a field would drop an old
  snapshot — acceptable (degrades to a fresh game).
- **Autosave churn:** debounced 500ms coalesces rapid life taps; atomic `.tmp`→rename avoids torn files on power loss.
- **Large branch:** M6 is intentionally broad (A+B+C). The plan splits it into independently-reviewable tasks
  (persistence core; presets; then C grouped by area) so each has its own review gate.
- **Part C breadth:** if any C item proves larger than a polish fix (e.g. life autosize interacting with the rotated
  layout), it can be trimmed to its own follow-up during planning rather than bloating the milestone.

## 9. References

- `DESIGN.md` §8 (M6), §10 (v1 DoD), §4 (state model).
- M1 game state (`GameModels`/`GameViewModel`), M4b stack (`StackModels`), M3 cards (`CardQuery`/`CardDb`), Card Sync
  (`CardSync`). The UI-polish + fast-follow backlog recorded in the project memory (`magictablet-status.md`).
- kotlinx.serialization (`@Serializable`, `Json.encodeToString`/`decodeFromString`); `ViewModelProvider.Factory`.
