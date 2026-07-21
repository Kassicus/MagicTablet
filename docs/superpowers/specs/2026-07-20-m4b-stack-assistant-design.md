# M4b — Stack Assistant — Design Spec

**Date:** 2026-07-20
**Milestone:** M4b (second M4 sub-project; completes DESIGN.md §5.3). Builds on M4a (CR reader) + M1 (game state) + M3 (card lookup).
**Status:** Approved scope, pending spec review

> Master brief: `DESIGN.md` §5.3 (card-aware stack assistant) + §2 (NOT a rules engine). M4b is the last
> big feature: a manual, card-aware stack with a priority tracker and three-tier resolution guidance that
> deep-links into the M4a CR reader. It **never computes interaction outcomes** — it surfaces the card's
> real text + rulings, states the always-correct procedure, and offers soft pattern hints; players adjudicate.

---

## 1. Purpose

Turn the Stack placeholder into a manual LIFO stack: add spells/abilities (linked to a card or free-typed),
show each linked item's oracle text + rulings inline, track priority with a pass-around, and on resolution
show the always-correct procedure + pattern-matched hints that link into the Comprehensive Rules.

## 2. Scope

**In scope:**
- `GameState` gains `stack: List<StackItem>` + priority fields; pure stack/priority ops (unit-tested); thin
  `GameViewModel` intents. The stack is **shared game state** (screens share the Activity-scoped `GameViewModel`).
- Stack screen: add (controller + kind + card-link/free-text + targets), LIFO list with inline oracle+rulings
  for linked items, priority row with pass-around, Resolve top.
- Resolution guidance: tier 1 (oracle+rulings, inline), tier 2 (static procedure), tier 3 (`stackHints`
  pattern→CR mapping, unit-tested) with tap-to-open CR deep-links into the M4a reader (hosted here).

**Out of scope:**
- Computing any interaction outcome / legality (the §2 boundary — hard).
- Room persistence of the stack (M6). Editing a resolved item's history. Reordering the stack.

## 3. Architecture

- **`game/StackModels.kt`** — `StackItem`, `StackKind`, and pure `GameState` extension ops (§5), unit-tested.
- **`game/GameModels.kt` / `GameState`** — gains `stack: List<StackItem> = emptyList()`,
  `priorityPlayerId: Int? = null`, `consecutivePasses: Int = 0`. `initialGame`/`newGame` reset them.
- **`game/StackGuidance.kt`** (pure, unit-tested) — `RuleHint(label, ruleNumber)`, `stackHints(item, oracleText, keywords): List<RuleHint>`, and the static `RESOLUTION_PROCEDURE` string (§6).
- **`GameViewModel`** — intents `addToStack(...)`, `resolveTop()`, `removeStackItem(id)`, `clearStack()`,
  `passPriority()`; a `nextStackId` counter; and `lastResolved: StateFlow<StackItem?>` (set to the item removed
  from the top whenever a resolution happens, by either `resolveTop` or a pass-around in `passPriority`).
- **`CardsViewModel`** — add `suspend fun getDetail(oracleId): CardDetail?` (`db.card` on IO) so the stack can
  load a linked item's oracle text + rulings + keywords (reuses M3's prepared `CardDb`).
- **`screens/StackScreen.kt`** (replaces placeholder) — uses the shared `GameViewModel`, `CardsViewModel`,
  and `CrViewModel` (all `viewModel()` → Activity-scoped, shared). Hosts its own `CrReader` modal (a
  `showRules` state) so a tier-3 hint can `crViewModel.openAt(number)` + open it — the host-lift M4a flagged.

## 4. Data model

```
enum class StackKind { Spell, Activated, Triggered }

data class StackItem(
    val id: Long,
    val controllerId: Int,      // a player id (seat)
    val cardOracleId: String?,  // null for a free-typed ability
    val kind: StackKind,
    val label: String,          // card name, or the free-typed ability text
    val targets: String = "",   // optional free-text note
)

// GameState additions:
val stack: List<StackItem> = emptyList()   // bottom -> top; top = last
val priorityPlayerId: Int? = null          // whose priority; null = none
val consecutivePasses: Int = 0             // passes since the last stack action
```

## 5. Pure stack + priority ops (`StackModels.kt`, unit-tested)

`priorityStart()` = `activePlayerId ?: players.firstOrNull()?.id`.

- `GameState.pushStackItem(item): GameState` → `copy(stack = stack + item, priorityPlayerId = priorityStart(), consecutivePasses = 0)`.
- `GameState.resolveTop(): GameState` → if `stack` empty, `this`; else drop the last item; `consecutivePasses = 0`;
  `priorityPlayerId =` `priorityStart()` if the stack is still non-empty, else `null`.
- `GameState.removeStackItem(id): GameState` → `copy(stack = stack.filterNot { it.id == id }, priorityPlayerId = if (resultingStackEmpty) null else priorityPlayerId)`.
- `GameState.clearStack(): GameState` → `copy(stack = emptyList(), priorityPlayerId = null, consecutivePasses = 0)`.
- `GameState.passPriority(): GameState` — if `priorityPlayerId == null`, `this`. Else `next = consecutivePasses + 1`;
  if `next >= players.size` (everyone passed in succession) → `resolveTop()` (which resets priority + passes);
  else advance `priorityPlayerId` to the **next seat** in `players` order (wrap) and set `consecutivePasses = next`.

This is a manual pass-tracker, not an APNAP/legality engine (per §2). `newGame`/`initialGame` reset stack + priority.

## 6. Resolution guidance

- **Tier 1** (most reliable): a linked item's actual **oracle text + rulings** — shown inline in the list (loaded via
  `CardsViewModel.getDetail`) and in the resolution panel.
- **Tier 2** (always correct): the static `RESOLUTION_PROCEDURE`:
  > "Resolve one object at a time, from the top of the stack down. After each resolution the active player gets
  > priority. Abilities that trigger during resolution go on top of the stack (in turn order) before anyone gets
  > priority. A spell or ability whose targets have all become illegal doesn't resolve — it and its effects are
  > removed. (CR 608, 603)"
- **Tier 3** (soft hints): `stackHints(item, oracleText, keywords)` returns `RuleHint(label, ruleNumber)` items:
  - By kind: Spell → `608` (resolving) + `601` (casting); Activated → `602`; Triggered → `603`.
  - Pattern (over `oracleText ?: label`, case-insensitive): `\b(when|whenever|at)\b` → `603`; a `:` (cost:effect) → `602`;
    `\btarget` → `115` (with the "targets all illegal → doesn't resolve" note); non-empty `keywords` → `702`.
  - Dedup by `ruleNumber`. Each hint is a tap target → `CrViewModel.openAt(ruleNumber)` + open the CR reader.

If the CR isn't loaded yet, tapping a hint opens the reader on its "Load rules" prompt (M4a behavior).

## 7. Stack screen UI

- **Header:** "Stack" + **Add** button (+ **Clear** when non-empty).
- **Priority row** (when `priorityPlayerId != null`): `Priority: P{seat}` (seat color) + **Pass** + **Resolve top**.
- **List** (top first = `stack.reversed()`): each `StackItemRow` — controller color dot + a kind chip + `label`;
  a linked item (`cardOracleId != null`) expands to its oracle text + rulings; a small remove (×). Empty state:
  "Stack is empty — add a spell or ability."
- **Add sheet** (`ModalBottomSheet`): controller `FilterChip`s (players by seat), kind `FilterChip`s
  (Spell/Activated/Triggered), a **card search** field (`CardsViewModel` results → tap to link, sets
  `cardOracleId` + `label`) *or* a free-text **ability** field, an optional **targets** field, and **Add**
  (enabled when a controller + kind are chosen and either a card is linked or the free text is non-empty).
- **Resolution guidance** (when `GameViewModel.lastResolved != null`, from Resolve top or a pass-around): a panel/
  dialog showing the resolved item, tier-1 (if linked: oracle + rulings via `getDetail`), tier-2 procedure, and
  tier-3 hints as tappable CR links; a Close (clears `lastResolved`).
- Hosts the `CrReader` modal (`showRules`) for the deep-links.

## 8. Testing

- **Unit (JVM), `StackModelsTest`:** `pushStackItem` adds to the top + sets priority to the active player + zeroes
  passes; `resolveTop` pops the top + resets priority (null when empty); `removeStackItem`/`clearStack`;
  `passPriority` advances to the next seat and wraps; **`passPriority` player-count times in succession resolves the
  top** (the pass-around) and resets.
- **Unit (JVM), `StackGuidanceTest`:** `stackHints` — Spell→608/601, Activated→602, Triggered→603; `Whenever…`→603;
  a `:`→602; `…target…`→115; non-empty keywords→702; dedup by ruleNumber. `RESOLUTION_PROCEDURE` non-empty.
- **On-device manual:** add a linked spell (e.g. search "Lightning Bolt") + a free-typed triggered ability; expand
  the linked item → oracle text + rulings; Pass around the table → the top resolves and the guidance panel shows
  tier-2 + tier-3; tap a tier-3 CR link → the CR reader opens at that rule.

## 9. Definition of done (M4b)

- [ ] Stack screen: Add (controller + kind + card-link/free-text + targets) puts an item on top (LIFO); linked items
      expand to oracle text + rulings.
- [ ] Priority row shows whose priority; Pass advances around the table; all-passed resolves the top; Resolve top works.
- [ ] Resolving shows the guidance panel — tier-2 procedure + tier-3 pattern hints; tapping a hint opens the CR reader
      at that rule number.
- [ ] The app never computes an interaction outcome (only text/procedure/hints/references).
- [ ] `StackModelsTest` + `StackGuidanceTest` pass; on-device manual flow verified.

## 10. Risks / seams

- **`GameViewModel` size** — mitigated by keeping stack/priority logic in pure `StackModels.kt`.
- **Priority tracker is a helper, not APNAP/legality** — deliberate (§2). It counts consecutive passes; a new push
  or a resolution resets the count.
- **CR deep-link needs the CR loaded** — `openAt` opens the reader; if unloaded, its "Load rules" prompt shows (M4a).
- **Loading oracle/rulings per linked item** — load on expand (and for the resolved item), on IO; cache per item.

## 11. References

- `DESIGN.md` §5.3 (stack + three tiers), §4 (StackItem model), §2 (not a rules engine).
- M4a `CrViewModel.openAt` (deep-link) + `CrReader` (host it here); M3 `CardsViewModel`/`CardDb` (card detail);
  M1 game state (players, activePlayerId).
