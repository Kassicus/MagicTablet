# M1b — Table Utilities — Design Spec

**Date:** 2026-07-20
**Milestone:** M1b (second slice of M1; see `DESIGN.md` §5.1 "Table utilities")
**Status:** Approved scope, pending spec review

> Master brief: `DESIGN.md`. Builds directly on **M1a** (Game screen life tracker, on `main`).
> M1b adds the table utilities that hang off the ⚙ control hub plus two per-seat markers.
> In-memory only — Room persistence remains **M6**.

---

## 1. Purpose

Add the multiplayer table conveniences from `DESIGN.md` §5.1: advance-turn and random-first-player
(with an active-seat highlight), a monarch marker, a dice + coin roller, and a simple overall game
timer — all reached from the ⚙ hub, with the two markers shown on the seat panels.

## 2. Scope

**In scope (M1b ships):**
- `GameState` gains `activePlayerId: Int?` and `monarchPlayerId: Int?` (nullable; reset by New game).
- VM intents: `advanceTurn`, `randomFirstPlayer`, `toggleMonarch`; timer `startTimer`/`pauseTimer`/`resetTimer`/`tickTimer`.
- ⚙ hub menu grows: **Advance turn**, **Random first player**, **Dice & coin**, **Start/Pause timer**, **Reset timer** (New game + Relinquish unchanged).
- Dice & coin overlay: Coin / d6 / d20 → result + Roll again / Close.
- Simple overall game timer: `MM:SS` readout chip near the hub when running/elapsed>0; UI-driven 1 s tick.
- Per-seat markers on `PlayerPanel`: active-turn border highlight; a tappable 👑 monarch marker.
- Unit tests on the new pure logic (turn/monarch) + dice range helper + timer state ops.

**Out of scope (deferred):**
- Per-player (chess-clock) turn timer — chose the simple overall timer.
- Player-name editing; the M1a review backlog polish (autosize life text, key `expanded`, etc. — tracked separately).
- Room persistence (M6). Cards/Stack screens. Kiosk hardening (M5).

## 3. Architecture

- Builds on M1a's `GameViewModel` (in-memory `StateFlow`) and the `com.magictablet.game` / `game.ui`
  packages. No new Gradle dependencies.
- **Pure logic** (turn/monarch, dice roll) lives as testable functions in `com.magictablet.game`;
  randomness (`kotlin.random.Random`) is injected or lives in the VM/overlay so the pure functions stay
  deterministic and unit-testable — mirroring the M1a pattern (VM has no timer coroutine; the tick is
  UI-driven so the VM stays dispatcher-free and testable).
- **Files:**
  - Modify `game/GameModels.kt` (state fields + pure turn/monarch fns), add `game/Randomizers.kt` (dice/coin helpers).
  - Modify `game/GameViewModel.kt` (turn/monarch/timer intents + timer state).
  - Add `game/ui/DiceOverlay.kt`, `game/ui/TimerChip.kt`.
  - Modify `game/ui/PlayerPanel.kt` (active highlight + crown marker), `game/ui/SeatLayout.kt` (pass marker state/callbacks), `screens/GameScreen.kt` (hub menu items, overlays, timer readout + UI tick, wiring).

## 4. Data model additions (in-memory)

```
GameState(
    startingLife: Int,
    players: List<PlayerState>,
    activePlayerId: Int? = null,     // whose turn; null until set
    monarchPlayerId: Int? = null,    // the monarch; null = none
)
```
`initialGame(...)` sets both to `null`. Timer + last-dice-roll are NOT part of `GameState` (transient).

Pure functions (unit-tested), in `com.magictablet.game`:
- `GameState.advanceTurn(): GameState` — if `activePlayerId == null` → the first seat (`players.first().id`);
  else the next player in `players` order, wrapping (`(index+1) % size`). Players are ordered by seat, so
  this advances clockwise by seat.
- `GameState.setActivePlayer(id: Int): GameState` — `copy(activePlayerId = id)`.
- `GameState.toggleMonarch(id: Int): GameState` — if `monarchPlayerId == id` → `null` (pass/clear), else `id`.

`game/Randomizers.kt`:
- `fun rollDie(sides: Int, random: Random = Random.Default): Int` — returns `random.nextInt(sides) + 1` (1..sides).
- `fun flipCoin(random: Random = Random.Default): String` — "Heads" or "Tails".

## 5. GameViewModel intents (additions)

```
fun advanceTurn()                        // _state.update { it.advanceTurn() }
fun randomFirstPlayer()                  // pick players.random(Random.Default).id → setActivePlayer
fun toggleMonarch(playerId: Int)         // _state.update { it.toggleMonarch(playerId) }
// timer (transient VM state, dispatcher-free):
val timer: StateFlow<TimerState>         // TimerState(running: Boolean, elapsedSeconds: Long)
fun startTimer()                         // running = true
fun pauseTimer()                         // running = false
fun resetTimer()                         // running = false, elapsedSeconds = 0
fun tickTimer()                          // if running, elapsedSeconds += 1  (called by a UI LaunchedEffect once/sec)
```
`newGame(...)` already rebuilds `GameState` (so active/monarch reset via `initialGame`); it also resets the timer.

## 6. ⚙ hub menu (grows from M1a)

`GameScreen`'s hub `DropdownMenu` items, in order:
- **Advance turn** → `advanceTurn()`
- **Random first player** → `randomFirstPlayer()` (optionally a brief toast "P{n} goes first")
- **Dice & coin** → opens the dice overlay (§7)
- **Start timer** / **Pause timer** (label reflects `timer.running`) → `startTimer()`/`pauseTimer()`
- **Reset timer** → `resetTimer()`
- **New game** → new-game sheet (unchanged)
- **Relinquish device owner** → maintenance (unchanged)

## 7. Dice & coin overlay

A small centered overlay (`Dialog` or `AlertDialog`), opened from the hub:
- Buttons: **Coin**, **d6**, **d20**. Tapping rolls via `flipCoin` / `rollDie(6)` / `rollDie(20)`.
- Shows the last result large (e.g. "d20: 17" or "Coin: Heads").
- **Roll again** re-rolls the last-chosen type; **Close** dismisses. Result state is local to the overlay.

## 8. Game timer

- VM `timer` state (`running`, `elapsedSeconds`). A `LaunchedEffect(timer.running)` in `GameScreen` runs
  `while (running) { delay(1000); viewModel.tickTimer() }` — the tick is UI-driven so the VM needs no
  coroutine/dispatcher and stays unit-testable.
- A compact **`MM:SS`** (or `H:MM:SS` past an hour) **readout chip** renders near the center hub whenever
  `running || elapsedSeconds > 0`. Off/hidden before the timer is first started. Controls live in the hub menu.
- `resetTimer` hides the chip again (elapsed 0, not running).

## 9. Per-seat markers (on `PlayerPanel`)

`PlayerPanel` gains `isActive: Boolean`, `isMonarch: Boolean`, `onToggleMonarch: (playerId: Int) -> Unit`.
`SeatLayout` computes these from `activePlayerId`/`monarchPlayerId` and passes `onToggleMonarch`; `GameScreen`
wires it to the VM.

- **Active turn:** when `isActive`, the panel's border is brightened/thickened (a ring/glow using the seat
  accent) — purely visual, not interactive.
- **Monarch marker:** a small 👑 control at the panel's **top-end** (`Alignment.TopEnd`, opposite the
  top-start poison/CMD badges). Bright/filled 👑 when `isMonarch`, faint outline otherwise. `onClick`
  → `toggleMonarch(player.id)` (claim, or pass/clear if already monarch). It is a `clickable` control, so
  the M1a `awaitFirstDown()` (default `requireUnconsumed = true`) fix prevents any life-zone click-through.

## 10. Testing

Unit tests (JVM, JUnit), on the new pure logic + VM state:
- `initialGame` sets `activePlayerId`/`monarchPlayerId` = null.
- `advanceTurn`: from null → first seat; wraps last → first; advances by seat order for 2–6.
- `setActivePlayer` / `toggleMonarch` claim-then-pass semantics; toggling a different seat moves the crown.
- `newGame` clears active + monarch (and resets the timer).
- `rollDie(sides)` in `1..sides` across sides (e.g. 6, 20) with a seeded `Random`; `flipCoin` ∈ {Heads, Tails}.
- Timer state ops: `startTimer` → running; `tickTimer` increments only when running; `pauseTimer` stops;
  `resetTimer` zeroes + not running.

Marker rendering, the dice overlay, the timer tick, and rotated-seat correctness are verified on the tablet
(`TK12110626B081745`).

## 11. Definition of done (M1b)

- [ ] ⚙ hub offers Advance turn, Random first player, Dice & coin, Start/Pause + Reset timer (plus New game, Relinquish).
- [ ] Advance turn moves the active-seat highlight to the next seat (wraps); Random first player lands on a valid seat.
- [ ] Each seat shows a 👑 marker; tapping claims monarch (exclusive), tapping the monarch passes/clears it — with no life change (no click-through).
- [ ] Dice overlay rolls Coin / d6 / d20 with in-range results; Roll again / Close work.
- [ ] Timer starts/pauses/resets; the `MM:SS` chip ticks near the hub and hides on reset.
- [ ] New game clears active player, monarch, and the timer.
- [ ] `./gradlew :app:testDebugUnitTest` passes (new turn/monarch/dice/timer tests).

## 12. Risks / open items

- **Hub menu length** (7 items) — acceptable as a DropdownMenu; could become a grouped controls sheet later if it grows further.
- **Crown marker placement** at top-end must not overlap the top-start badges or the rotated-seat layout — verify on device at 2–6.
- **Timer accuracy** — UI-driven 1 s tick is fine for a casual game timer; it only advances while the Game screen is composed (always, here). A wall-clock delta approach is a possible later refinement.

## 13. References

- `DESIGN.md` §5.1 (table utilities), §4 (Game.activePlayerId / turnNumber).
- `docs/superpowers/specs/2026-07-20-m1a-game-screen-life-totals-design.md` (the slice this builds on).
