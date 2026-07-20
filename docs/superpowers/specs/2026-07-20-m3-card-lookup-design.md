# M3 — Card Lookup — Design Spec

**Date:** 2026-07-20
**Milestone:** M3 (DESIGN.md §5.2, §8)
**Status:** Approved scope, pending spec review

> Master brief: `DESIGN.md` §5.2. M3 turns the Cards placeholder into an offline card/ruling lookup
> over the FTS4 seed DB from M2. Builds on M0–M2 (on `main`). The in-app WiFi sync is a **separate
> later milestone**; M3 sets up the internal-storage DB access that sync will reuse.

---

## 1. Purpose

Let players look up any card's Oracle text, type line, mana cost, and rulings, fully offline: a search
box over the FTS4 index → ranked results → detail view. Plus an in-session "recently viewed" list so the
screen is useful before typing.

## 2. Scope

**In scope (M3 ships):**
- Read-only access to the card DB via a plain SQLite helper (no Room for the card DB — see §3), reading
  from **internal storage**, seeded by copying `assets/cards.db` on first run.
- FTS4 `MATCH` search with app-layer ranking (name matches above oracle-text matches).
- Results list → detail (oracle text, type line, mana cost, keywords, rulings by date).
- In-session recently-viewed list (in-memory, capped, deduped).
- Unit tests on the pure query builder; an on-device instrumented test against the real seed DB.

**Out of scope (deferred):**
- Persisted favorites; mana-symbol images (text `{R}` only).
- **In-app WiFi sync** (next milestone) — M3 only reads; sync will replace `filesDir/cards.db`.
- Any change to Game / Stack screens or the M1 game state.

## 3. Architecture

Deviation from `DESIGN.md` §3 (which suggested Room `createFromAsset` for the reference DB): the card DB
is read **via a plain read-only SQLite helper**, not Room. Rationale: the DB is hand-built (Python), FTS4,
and will be **replaced by the WiFi sync**; Room's `createFromAsset` requires a matching `room_master_table`
identity hash + exact generated FTS DDL, which fights a hand-built, swappable DB. Room still owns the
*mutable game state* later (M6). New package `com.magictablet.cards`.

- **`cards/CardDb.kt`** — manages the DB. `suspend fun prepare(context)`: if `filesDir/cards.db` is missing,
  copy it from `assets/cards.db` (streamed), then open read-only via
  `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)`. Exposes:
  - `fun search(userText: String, limit: Int = 50): List<CardSummary>`
  - `fun card(oracleId: String): CardDetail?`
  All DB calls run off the main thread (called from `Dispatchers.IO` in the VM).
- **`cards/CardQuery.kt`** — pure `fun buildMatchQuery(userText: String): String` (unit-tested): lowercases,
  splits on non-alphanumeric, drops empties, appends prefix `*` per token — e.g. `"Lightning bol"` →
  `"lightning* bol*"`. Empty/blank input → `""`. This sanitizes user text so FTS4 special characters can't
  break the `MATCH` query, and gives as-you-type prefix matching.
- **`cards/CardModels.kt`** — `CardSummary(oracleId, name, manaCost, typeLine)`,
  `CardDetail(oracleId, name, manaCost, typeLine, oracleText, keywords: List<String>, rulings: List<RulingItem>)`,
  `RulingItem(publishedAt, text)`.
- **`cards/CardsViewModel.kt`** (`AndroidViewModel`) — see §5.
- **`screens/CardsScreen.kt`** — replaces the placeholder; see §6. `App.kt` already routes
  `Screen.Cards -> CardsScreen()`; `CardsScreen(viewModel: CardsViewModel = viewModel())` keeps that call
  site working (no `App.kt` change).

## 4. Queries (SQLite / FTS4)

**Search** (bind: `matchQuery` from `buildMatchQuery`, then `rawLower = userText.trim().lowercase()` three
times, then `limit`):

```sql
SELECT Card.oracleId, Card.name, Card.manaCost, Card.typeLine
FROM CardFts JOIN Card ON Card.oracleId = CardFts.oracleId
WHERE CardFts MATCH ?
ORDER BY
  CASE
    WHEN lower(Card.name) = ?            THEN 0   -- exact name
    WHEN lower(Card.name) LIKE ? || '%'  THEN 1   -- name prefix
    WHEN instr(lower(Card.name), ?) > 0  THEN 2   -- name substring
    ELSE 3                                        -- oracle-text-only match
  END,
  length(Card.name), Card.name
LIMIT ?;
```

This is the FTS4 replacement for FTS5 `bm25()` ranking: MATCH selects candidates; the `CASE` floats
name matches above body-only matches. If `matchQuery` is empty, search returns `emptyList()` without
querying.

**Detail:**
```sql
SELECT name, manaCost, typeLine, oracleText, keywords FROM Card WHERE oracleId = ?;
SELECT publishedAt, text FROM Ruling WHERE oracleId = ? ORDER BY publishedAt;
```
`keywords` (a JSON array string) is parsed with `org.json.JSONArray` (Android built-in — **no new
dependency**) into `List<String>`.

## 5. CardsViewModel

`StateFlow`s: `query: String`, `results: List<CardSummary>`, `selected: CardDetail?`,
`recent: List<CardSummary>`, `loading: Boolean` (true until the DB is prepared).

- On init: `viewModelScope.launch(Dispatchers.IO) { cardDb.prepare(app); ready = true; loading = false }`.
- **Debounced search:** the `query` flow is `debounce(250)` → `map { trim }` → `distinctUntilChanged` →
  gated on `ready` → `flatMapLatest { q -> if (q.isEmpty()) emptyList else cardDb.search(q) }` on
  `Dispatchers.IO`; result feeds `results`. `flatMapLatest` cancels an in-flight search when the query
  changes. (`@OptIn(FlowPreview, ExperimentalCoroutinesApi)`.)
- `onQueryChange(text)` sets `query`. `openCard(oracleId)` loads the detail on IO, sets `selected`, and
  prepends its `CardSummary` to `recent` (dedup by `oracleId`, cap 15). `closeDetail()` clears `selected`.

## 6. CardsScreen UI

- A search `TextField` (or `OutlinedTextField`) bound to `query`, with a clear button; placeholder
  "Search cards".
- Body:
  - `selected != null` → **detail view** (scrollable): name, `manaCost`, `typeLine`, `oracleText`
    (preserving line breaks), `keywords` (small chips or comma text), then a **Rulings** section listing
    each `RulingItem` (date + text). A back affordance; `BackHandler(enabled = selected != null)` calls
    `closeDetail()`.
  - else `loading` → a "Preparing card database…" spinner (first-run seed copy).
  - else `query.isBlank()` → **Recently viewed** list (or a "Search for a card" prompt if empty).
  - else → **results** list (or "No matches" if empty).
- Result / recent row: card `name` prominent, `manaCost` + `typeLine` secondary; tap → `openCard`.
- Landscape + dark (unchanged); content within the safe-area insets already applied at the app shell.

## 7. Seeding & threading

- First run: copy the ~48 MB `assets/cards.db` → `filesDir/cards.db` (streamed, on `Dispatchers.IO`), shown
  as the `loading` state. Subsequent runs open instantly.
- The app reads only `filesDir/cards.db`. This is what the future WiFi sync replaces; `CardDb` will gain a
  reopen/invalidate path then (out of scope here, noted in §9).

## 8. Testing

- **Unit (JVM):** `buildMatchQuery` — `"Lightning Bolt"` → `"lightning* bolt*"`; multi-space + punctuation
  (`"Gaea's, Cradle"`) → `"gaea* s* cradle*"`; digits kept; empty/whitespace → `""`.
- **Instrumented (on device — the definitive FTS4-on-device close-out):** open the real seed DB and assert
  `search("lightning bolt")` returns Lightning Bolt as the **first** result; `card(thatOracleId)` has
  `oracleText` containing "3 damage" and returns its rulings list. Re-adds the androidTest infra
  (`androidx.test.ext:junit:1.2.1`, `androidx.test:runner:1.6.2`, `testInstrumentationRunner`).
- **Manual on-device:** type a query, open a card, view rulings, back out, see it in Recently viewed.

## 9. Definition of done (M3)

- [ ] Cards screen: typing searches; results are ranked with name matches first; tap opens a detail with
      oracle text, type line, mana cost, keywords, and rulings (by date); back returns to the list.
- [ ] Blank query shows Recently viewed (populated as you open cards this session).
- [ ] First launch seeds `filesDir/cards.db` from the asset with a loading state; later launches are instant.
- [ ] Fully offline — no network use anywhere in M3.
- [ ] `buildMatchQuery` unit tests pass; the on-device instrumented test (Lightning Bolt search + detail) passes.

## 10. Risks / seams

- **First-run copy latency** (~48 MB) — mitigated by the IO copy + loading state.
- **WiFi-sync seam (next milestone):** sync writes a fresh `filesDir/cards.db`; `CardDb` needs a
  reopen/invalidate then. Also, copy-if-missing means a *newer bundled seed* in a future app version won't
  replace an existing internal DB — a simple bundled-DB version marker handles that; deferred to the sync
  milestone.
- **FTS4 query safety** — `buildMatchQuery` strips to alphanumeric prefix terms so user input can't inject
  FTS syntax; covered by unit tests.

## 11. References

- `DESIGN.md` §5.2 (Cards screen), §4 (Card/Ruling), §3 (stack rationale — Room deviation noted here).
- Builds on M2's FTS4 seed DB (`Card`, `Ruling`, `CardFts` in `assets/cards.db`).
