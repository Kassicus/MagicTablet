# UI Refresh — Side Panel · Rules Tab · Pass-Turn Button · Monarch Rework — Design Spec

**Date:** 2026-07-20
**Milestone:** Post-v1 UI refresh (all of M0–M6 shipped; v1 DoD met). No new milestone in DESIGN.md §8 — this is a UX iteration on the shipped Game screen + navigation.
**Status:** Approved design, pending spec review

> Reworks the Game screen's control surface and app navigation. **No ViewModel or data-model changes** — every
> action already exists (`advanceTurn`, `toggleMonarch`, `randomFirstPlayer`, `newGame`, timer intents), and
> `monarchPlayerId`/`activePlayerId` already persist via M6. Pure UI/navigation restructuring. No new dependencies.

---

## 1. Purpose

Six changes:
1. Replace the center `⚙` dropdown with a **left-edge slide-out panel** (handle → animated panel over a scrim).
2. Add a dedicated **Rules tab** and move the Comprehensive Rules reader there (out of the `⚙` menu / Stack modal).
3. Put **Exit kiosk mode** at the bottom of the side panel.
4. Make the **center** a dedicated **Pass turn** button (nothing else).
5. **Hide monarch crowns by default**; add a "Become the monarch" option in the counters popup.
6. Once a monarch exists, show crowns for everyone — solid for the monarch, grayed for the rest.

## 2. Scope

**In scope:** the six changes above, as UI/navigation only.

**Out of scope / unchanged:** `GameViewModel`, `GameState`/`PlayerState`, persistence, the Cards/Stack feature logic,
the CR data pipeline (`CrViewModel`/`CrDb`/`CrSync` untouched — only its *presentation* moves from a Dialog to a tab).
No new dependencies.

## 3. Decisions (from brainstorming)

- **Panel edge = LEFT.** A small always-visible handle on the left edge; tapping slides the panel in from the left
  over a dim scrim; tapping the scrim or the handle closes it.
- **Crowns tappable-to-steal + popup option.** Once a monarch exists, tapping another player's grayed crown makes
  them the monarch (`toggleMonarch`), AND the counters popup has a "Become the monarch" button. Both routes work.
- **CR deep-link jumps to the Rules tab.** A tier-3 CR hint on the Stack screen switches to the Rules tab and opens
  that rule (`crViewModel.openAt` + `screen = Rules`); the stack is preserved (shared state).

## 4. Navigation & tab structure

- **`Screen.kt`:** add `Rules("Rules")` → enum order `Game, Cards, Stack, Rules` (4 tabs; `App`'s `TabRow` already
  iterates `Screen.entries`).
- **`App.kt`:** already owns the `screen` state and creates the shared `GameViewModel`. Also obtain the shared
  `crViewModel: CrViewModel = viewModel()` at `App` scope. Wire:
  - `Screen.Game -> GameScreen(viewModel = gameViewModel)` (GameScreen no longer takes/needs `crViewModel`).
  - `Screen.Cards -> CardsScreen()` (unchanged).
  - `Screen.Stack -> StackScreen(gameViewModel = gameViewModel, onOpenRule = { number -> crViewModel.openAt(number); screen = Screen.Rules })`.
  - `Screen.Rules -> RulesScreen(viewModel = crViewModel)`.

## 5. Rules tab (`screens/RulesScreen.kt`, remove `rules/CrReader.kt`)

- Lift the current `CrReader` inner content into a full-screen `RulesScreen(viewModel: CrViewModel = viewModel())`:
  a `Column(fillMaxSize)` with a "Comprehensive Rules" title and the same `when` over `syncState`/`hasRules` →
  `UpdatePanel` / `LoadPrompt` / `BrowsePanel`. **No `Dialog` wrapper and no Close button** (it's a tab).
- Move the private helper composables (`LoadPrompt`, `UpdatePanel`, `BrowsePanel`, `UrlField`) alongside
  `RulesScreen` (they use public `rules`-package types `CrView`/`CrSyncUiState`/`CrSyncProgress` — import them).
- **Delete `rules/CrReader.kt`** — no caller remains (Game drops it; Stack navigates to the tab).
- Behavior preserved: browse/drill/up, jump-to-number, glossary lookup, editable URL + Update, the not-loaded
  "Load rules" prompt. `openAt(number)` (called from the Stack deep-link before the tab switch) already sets the
  view, so `RulesScreen` shows that rule on arrival; if the CR isn't loaded, it shows the load prompt (M4a behavior).

## 6. Game screen (`screens/GameScreen.kt`)

Drop the `crViewModel` param, the `⚙` `DropdownMenu`, and the `CrReader`/`showRules` host. Keep `NewGameSheet`,
`DiceOverlay`, and the Exit-kiosk `AlertDialog`. New layout inside the root `Box(fillMaxSize)` over `SeatLayout`:

- **Center — Pass turn.** At `Alignment.Center`, a Column: the existing `TimerChip` (shown when
  `timer.running || timer.elapsedSeconds > 0`) above a `Button`/`FilledTonalButton` labeled **"Pass turn"** →
  `viewModel.advanceTurn()`. (First press with no active player sets P1 active, per `advanceTurn`.)
- **Left handle.** At `Alignment.CenterStart`, a small always-visible tab (a rounded `Surface`, ≈28dp wide ×
  ≈120dp tall, a "☰"/"›" glyph) → sets `panelOpen = true`. It sits above the seat panels' left edge (a minor
  overlap with the left −life zone is acceptable; the handle consumes its own taps).
- **Slide-out panel + scrim.** When `panelOpen`:
  - A scrim `Box(fillMaxSize)` with a translucent background, `clickable { panelOpen = false }` (no ripple).
  - The panel via `AnimatedVisibility(visible = panelOpen, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it })`,
    a left-anchored `Surface` (≈280dp wide, `fillMaxHeight`, tonal elevation) holding a `Column`:
    top→bottom buttons **Random first player** (`randomFirstPlayer`), **Dice & coin** (opens `DiceOverlay`),
    **Start/Pause timer** (`startTimer`/`pauseTimer` by `timer.running`), **Reset timer** (`resetTimer`),
    **New game** (opens `NewGameSheet`); then `Spacer(Modifier.weight(1f))`; then **Exit kiosk mode**
    (opens the confirm `AlertDialog`) pinned at the bottom.
  - Each panel action sets `panelOpen = false` (opening New game / Dice / Exit shows its dialog over the closed panel).
- Keep `LaunchedEffectTick(timer.running, viewModel)` for the timer tick.

## 7. Stack screen (`screens/StackScreen.kt`)

- Drop the `crViewModel` param, the hosted `CrReader`, and the `showRules` state.
- Add `onOpenRule: (String) -> Unit`. The resolution `GuidancePanel`'s rule-hint tap calls `onOpenRule(h.ruleNumber)`
  (which `App` wires to open the Rules tab at that rule). Everything else (add/list/priority/resolve/guidance) unchanged.

## 8. Monarch (`game/ui/PlayerPanel.kt`, `SeatLayout.kt`, `PlayerDrawer.kt`)

- **`SeatLayout`:** pass `monarchExists = monarchPlayerId != null` into each `PlayerPanel` (threaded like the M6
  `anyActive` flag).
- **`PlayerPanel`:** gains `monarchExists: Boolean`. Render the crown **only when `monarchExists`** — the top-end
  44dp tappable box with `Text("👑", alpha = if (isMonarch) 1f else 0.3f)`; when `!monarchExists`, render no crown.
  Tapping still calls `onToggleMonarch(player.id)` (steal for a grayed crown; renounce for the monarch's solid crown).
  Also pass `isMonarch = isMonarch` and `onBecomeMonarch = { onToggleMonarch(player.id) }` into `PlayerDrawer`.
- **`PlayerDrawer`:** gains `isMonarch: Boolean` + `onBecomeMonarch: () -> Unit`. Add a button labeled
  `if (isMonarch) "Renounce monarch" else "Become the monarch"` → `onBecomeMonarch()`. (Uses the existing
  `toggleMonarch(id)`: sets when not monarch, clears when already monarch.)

## 9. Behavior summary

- Default game: no crowns, center shows "Pass turn", left handle visible. Pass turn advances the active seat
  (highlighted by its border). Opening the left panel reveals setup/utility actions + Exit kiosk at the bottom.
- A player opens their counters drawer → "Become the monarch" → crowns appear everywhere (solid on them, grayed on
  others). During play, tapping any grayed crown transfers the monarchy; tapping the monarch's crown (or "Renounce"
  in the drawer) clears it → crowns hide again.
- Rules is a normal tab; a Stack CR hint jumps there at the rule and back-tab returns to the stack.

## 10. Testing

- **No new pure logic → no new unit tests.** The actions reuse `advanceTurn`/`toggleMonarch`/timer/`randomFirstPlayer`/
  `newGame`, already covered (`advanceTurn_viaVm`, `toggleMonarch_viaVm`, timer/newGame tests). Confirm the whole unit
  suite stays green (`:app:testDebugUnitTest`).
- **On-device manual (the gate):**
  - Left handle → panel slides in; each action works; New game / Dice / Exit-confirm launch and the panel closes;
    Exit kiosk sits at the bottom.
  - Center "Pass turn" advances the active seat highlight around the table.
  - Rules tab shows the CR reader (browse/jump/glossary/update, or the load prompt if unloaded).
  - Stack → resolve → tap a "CR NNN" hint → jumps to the Rules tab at that rule; tab back to Stack shows the stack intact.
  - No crown until a player picks "Become the monarch"; then solid on the monarch, grayed on others; tap a grayed
    crown to steal; renounce hides all crowns. Persists across a force-stop/reboot (monarch + active seat restore).

## 11. Risks / seams

- **Cross-tab nav for the deep-link:** `App` lifts `crViewModel` to share it between `RulesScreen` and the Stack's
  `onOpenRule`. All screens already resolve `viewModel()` to the Activity store, so `RulesScreen(viewModel())` (default)
  and `App`'s `crViewModel` are the same instance — but `App` passes it explicitly to avoid ambiguity.
- **Handle vs. life tap zones:** the left handle overlaps a sliver of the left `−life` zone; kept narrow. If it proves
  annoying on-device, shrink/reposition it (a trim, not a redesign).
- **Removing `CrReader`:** confirm no other references (Game + Stack are the only two hosts; both are updated here).
- **Panel over rotated seats:** the panel is a left-anchored overlay above `SeatLayout`; the top row's 180° rotation
  doesn't affect it (it's drawn in the root `Box`, not inside a rotated panel).

## 12. References

- `screens/GameScreen.kt` (current `⚙` menu + hosts), `screens/StackScreen.kt` (current CR modal + `onOpenRule` seam),
  `rules/CrReader.kt` (content to lift), `Screen.kt`/`App.kt` (tabs + shared VMs), `game/ui/PlayerPanel.kt` +
  `SeatLayout.kt` (crown + `monarchExists` threading, mirrors M6 `anyActive`), `game/ui/PlayerDrawer.kt` (counters popup).
- `GameViewModel`: `advanceTurn`, `toggleMonarch`, `randomFirstPlayer`, `newGame`, `startTimer`/`pauseTimer`/`resetTimer`.
