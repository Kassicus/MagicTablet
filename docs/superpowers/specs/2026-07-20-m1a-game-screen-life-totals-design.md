# M1a — Game Screen (Life Totals + Counters) — Design Spec

**Date:** 2026-07-20
**Milestone:** M1a (first slice of M1 in `DESIGN.md` §8; see also §5.1)
**Status:** Approved scope, pending spec review

> Master brief: `DESIGN.md` (source of truth). This spec covers **M1a only** — the Game
> screen through life tracking, the fading recent-delta, the poison / commander-damage /
> generic-counters drawer, and new-game setup. Table utilities are **M1b**; Room persistence
> is **M6**. Builds on the M0 shell already merged to `main`.

---

## 1. Purpose

Turn the M0 `GameScreen` placeholder into a fully usable multiplayer life tracker (Commander-first):
per-seat panels rotated to face players around the tablet, fast tap/hold life adjustment with a
fading recent-delta, and an expandable drawer for poison, per-opponent commander damage, and generic
counters — plus a new-game setup for player count and starting life. This is the "usable app fast"
milestone (`DESIGN.md` §8).

## 2. Scope

**In scope (M1a ships):**

- `GameViewModel` + in-memory `GameState` (`StateFlow`); replaces the `GameScreen()` placeholder.
- Two-row seat layout for 2–6 players (top row rotated 180°, bottom upright).
- Per-panel collapsed view: large life number, left-half −1 / right-half +1 tap zones, press-and-hold
  auto-repeat with acceleration, and a recent-delta that accumulates during a burst and fades.
- Per-panel expandable drawer (rotated to the seat): poison, commander-damage opponent grid,
  Energy + Experience counters, collapse control.
- New-game setup sheet: player count 2–6 (default 4), starting life 40 / 25 / 20 / custom (default 40).
  App boots into a playable default 4×40 game.
- Loss-state treatment: dim panel + skull + reason on life ≤ 0, poison ≥ 10, or 21 commander damage
  from a single opponent; panel stays interactive.
- Unit tests on `GameViewModel` logic.

**Out of scope (deferred):**

- **M1b:** dice + coin flip, random first player, turn/monarch marker, game timer, player-name editing,
  add-your-own named counters, 4-edge (90°) seat layout, elimination/seat-removal.
- **M6:** Room persistence (M1a state is in-memory; survives configuration change via `ViewModel`,
  not process death).
- Anything on the Cards/Stack screens, and the M0 `AdminReceiver` / kiosk plumbing (untouched).

## 3. Architecture

- **State holder:** a single `GameViewModel` (androidx.lifecycle) exposing `val state: StateFlow<GameState>`.
  The `GameScreen` collects it with `collectAsStateWithLifecycle`. No new persistence.
- **Package layout** (under `com.magictablet`):
  - `game/GameViewModel.kt`, `game/GameState.kt` (data classes + pure logic helpers)
  - `screens/GameScreen.kt` (replaces the placeholder) + `game/ui/` composables (`PlayerPanel.kt`,
    `PlayerDrawer.kt`, `NewGameSheet.kt`, `SeatLayout.kt`)
- **Dependencies to add** (kept minimal, all AndroidX Compose-family — consistent with DESIGN.md §3):
  - `androidx.lifecycle:lifecycle-viewmodel-compose`
  - `androidx.lifecycle:lifecycle-runtime-compose` (for `collectAsStateWithLifecycle`)
  These are added to the version catalog + `app/build.gradle.kts`. No other libraries.
- **Recent-delta** is transient UI state owned by the `GameViewModel` (a separate map, not part of
  persisted `GameState`); the panel composable animates its fade.

## 4. Data model (in-memory)

```
GameState(
    startingLife: Int,
    players: List<PlayerState>,      // ordered by seat, size == playerCount
)

PlayerState(
    id: Int,                          // == seat (1..6); stable within a game, no add/remove in M1a
    seat: Int,                        // 1..playerCount
    name: String,                     // default "P{seat}"; editing deferred to M1b
    colorIndex: Int,                  // 0..5 index into the seat palette (§9)
    life: Int,
    poison: Int,                      // loss at >= 10
    commanderDamage: Map<Int, Int>,   // opponentId -> damage; loss if any value >= 21
    counters: Map<String, Int>,       // keys "energy", "experience" in M1a
)
```

Derived (pure functions, unit-tested):
- `isLost(p): Boolean = p.life <= 0 || p.poison >= 10 || p.commanderDamage.values.any { it >= 21 }`
- `lossReason(p): String?` → `"0 life"` | `"poison"` | `"cmdr"` | null (first matching, in that order)

**Deliberate non-rules-engine choice:** commander damage is tracked as an independent tally and does
**not** auto-reduce `life`. Players adjust life separately. This keeps the app a manual assistant and
avoids double-counting logic (consistent with `DESIGN.md` §2 "not a rules engine").

## 5. GameViewModel intents

```
fun newGame(playerCount: Int, startingLife: Int)   // rebuild players 1..n at startingLife, zeroed counters
fun adjustLife(playerId: Int, delta: Int)          // also feeds the recent-delta accumulator
fun adjustPoison(playerId: Int, delta: Int)        // clamp >= 0
fun adjustCommanderDamage(playerId: Int, fromOpponentId: Int, delta: Int)  // clamp >= 0
fun adjustCounter(playerId: Int, counter: String, delta: Int)             // clamp >= 0; keys energy|experience
```

- All counts clamp at a floor of 0 except `life`, which may go negative (0 and below = loss).
- Initial `GameState` on VM creation = `newGame(4, 40)`.
- `newGame` seats players 1..n, assigns `colorIndex = seat-1`, names `"P{seat}"`, life = startingLife,
  poison 0, empty `commanderDamage`, counters `{energy:0, experience:0}`.

**Recent-delta accumulator** (transient, in VM): `val recentDeltas: StateFlow<Map<Int, RecentDelta>>`
where `RecentDelta(amount: Int, token: Long)`. On `adjustLife`, `amount += delta` and `token` is bumped;
a `viewModelScope` job (restarted each change) clears that player's entry after **1000 ms** of no further
change. The panel composable shows `amount` (signed) and fades it out when cleared.

## 6. Seat layout

- Root: `Column(fillMaxSize)` of two equal-weight `Row`s. **Top row rotated 180°** via
  `Modifier.rotate(180f)` applied per top-row panel; bottom row upright.
- **Split:** `topCount = playerCount / 2` (floor), `bottomCount = playerCount - topCount` (ceil).
  Seats fill left-to-right: top row = seats 1..topCount, bottom row = seats topCount+1..playerCount.
  Results: 2→1/1, 3→1/2, 4→2/2, 5→2/3, 6→3/3.
- Each panel is a `Box(Modifier.weight(1f).fillMaxSize())` within its row, with a small gap/outline
  between panels.

## 7. Panel — collapsed

- Large centered life number (auto-sized to fill, high contrast on the seat's dark surface).
- **Tap zones:** left 50% of the panel = `adjustLife(-1)`, right 50% = `adjustLife(+1)`. Because the
  whole panel (including these zones) is rotated for top-row seats, left/right stay correct per player.
- **Hold-to-repeat:** on press, fire one ±1 immediately, then after an initial delay of **400 ms**
  repeat every **120 ms**, accelerating to every **40 ms** after the hold passes **2 s**. Release stops.
  (Implemented with `pointerInput` + a coroutine loop; exact curve may be tuned during build.)
- **Recent-delta:** signed accumulated value (e.g. `+3`, `−5`) shown adjacent to the number while a
  burst is active; fades out (~300 ms) once the 1 s idle window clears it.
- **Chevron** handle in the panel center opens the drawer (§8).
- **Collapsed badges:** a small `☠{poison}` badge if poison > 0 and a small `CMD` badge if any
  commander damage > 0 — so threats are visible without expanding.

## 8. Panel — expanded drawer (rotated to the seat)

Opened by the chevron; overlays/expands within the panel bounds, scrollable if content exceeds height
(relevant at 6 players). Contents:

- **Poison:** label + value + `[−] [+]`; value ≥ 10 marks the loss reason.
- **Commander damage:** a compact grid of one chip per **opponent** (all other seats). Each chip is
  seat-color-coded (§9), shows the opponent's short name + damage, with `[−] [+]`. A value ≥ 21 on any
  chip marks the loss reason. Grid wraps; drawer scrolls if needed.
- **Counters:** `Energy` and `Experience` rows, each value + `[−] [+]`.
- **Collapse** control (chevron/close) returns to the collapsed view.

## 9. Seat identity & palette

Six distinct, dark-theme-friendly accent colors, indexed by `colorIndex` (0-based = seat−1), used for
the panel accent/outline and commander-damage chips:

| idx | seat | name | hex |
|----|------|--------|---------|
| 0 | P1 | red    | `#E5484D` |
| 1 | P2 | amber  | `#FFB224` |
| 2 | P3 | green  | `#30A46C` |
| 3 | P4 | blue   | `#5B8DEF` |
| 4 | P5 | purple | `#A56EFF` |
| 5 | P6 | teal   | `#2EC7C0` |

Panel surface stays dark (Material 3 dark theme from M0); the accent is an outline/label tint, not a
flooded background, to preserve life-number contrast.

## 10. New-game setup

- The app boots into a playable **4 × 40** game (VM initial state), so it's usable with zero setup.
- A **"New game"** affordance (a small control on the Game screen — e.g. a top-corner button that does
  not conflict with seat tap zones) opens a **setup sheet** (`ModalBottomSheet` or dialog):
  - Player count: segmented **2 3 4 5 6** (default current count).
  - Starting life: presets **40 / 25 / 20** + **Custom** (numeric entry, clamp 1..999); default 40.
  - **Start** applies `newGame(count, life)` and dismisses; **Cancel** dismisses unchanged.
- Starting a new game resets all life/poison/commander-damage/counters.

## 11. Loss state

- When `isLost(player)` is true: the panel **dims** (reduced alpha / desaturated surface), overlays a
  **skull** glyph and the **`lossReason`** text, but remains fully interactive (all tap zones and the
  drawer still work — life can climb back and clear the state). No automatic elimination or seat removal
  in M1a.

## 12. Testing

Unit tests on `GameViewModel` / pure helpers (JVM, JUnit — already wired in M0):
- `newGame(n, life)` for n = 2..6 produces n players, correct seats/ids/colors/names, all at `life`,
  counters zeroed.
- Seat split (`topCount`/`bottomCount`) for 2..6 = 1/1, 1/2, 2/2, 2/3, 3/3.
- `adjustLife` accumulates into `recentDeltas` (amount sums across a burst).
- Clamping: poison, commander damage, counters never go below 0; life may go negative.
- `isLost` / `lossReason` for each condition (life ≤ 0, poison ≥ 10, one commander-damage ≥ 21) and the
  reason precedence order.
- `adjustCommanderDamage` writes per-opponent and detects the 21 threshold on a single opponent.

Interaction, rotation, hold-to-repeat, and the fade are verified on the tablet (`TK12110626B081745`).

## 13. Definition of done (M1a)

- [ ] App boots into a playable 4×40 game; the Game tab shows four seat panels (2 rotated 180°).
- [ ] Player count 2–6 each render with the correct two-row split and rotation.
- [ ] Left/right tap = ∓1; press-and-hold auto-repeats and accelerates; recent-delta shows and fades.
- [ ] Drawer opens per seat (rotated) with working poison, per-opponent commander-damage grid, and
      Energy/Experience counters; all clamp at 0 (life may go negative).
- [ ] New-game sheet changes player count + starting life and resets the game.
- [ ] Loss conditions dim the panel with a skull + reason while keeping it interactive.
- [ ] `GameViewModel` unit tests pass (`./gradlew :app:testDebugUnitTest`).

## 14. Risks / open items

- **Commander-damage grid density at 6 players** on the 800px-tall screen — the drawer is scrollable as
  the fallback; chip sizing tuned on-device.
- **Hold-to-repeat feel** — the 400/120/40 ms curve is a starting point, tuned on the tablet.
- **Rotated hit-testing** — verify tap zones register correctly under `Modifier.rotate(180f)` on the
  top row (Compose transforms input, but confirm on-device).
- **lifecycle-viewmodel-compose / lifecycle-runtime-compose versions** — pick versions compatible with
  the pinned Compose BOM 2024.12.01 during planning.

## 15. References

- `DESIGN.md` §5.1 (Game screen), §4 (data model, mutable side), §2 (non-goals), §9 (open questions).
- `docs/superpowers/specs/2026-07-20-m0-skeleton-kiosk-smoketest-design.md` (the shell this builds on).
