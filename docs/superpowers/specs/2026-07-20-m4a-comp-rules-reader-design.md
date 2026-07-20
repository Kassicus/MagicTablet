# M4a — Comprehensive Rules Corpus + Reader — Design Spec

**Date:** 2026-07-20
**Milestone:** M4a (first of two M4 sub-projects: CR reader, then the stack assistant M4b)
**Status:** Approved scope, pending spec review

> Master brief: `DESIGN.md` §5.3 (stack guidance references the CR), §9 (CR deep-link decision).
> M4a delivers an offline Comprehensive Rules reader the app **fetches itself** over WiFi from a
> **user-editable URL**, parses on-device, and browses/deep-links by rule number. M4b (the stack
> assistant) deep-links into this reader. Builds on M3/Card Sync (reuses their on-device fetch +
> SQLite patterns). No build-time seed (fetch-on-first-use).

---

## 1. Purpose

Give players the full Comprehensive Rules on the tablet, offline: fetched in-app from an editable URL
(the CR URL changes every release, so it must be updatable **in the app**), parsed into a browsable,
deep-linkable rule corpus. This is the reference M4b's stack guidance (tier 3) will link into.

## 2. Scope

**In scope:**
- Editable CR URL in `SharedPreferences` (framework — **no new deps**), with a documented default,
  editable from the reader.
- `CrParser` (pure) — parse CR `.txt` lines into `Rule`/`Glossary` rows; unit-tested.
- `CrDbBuilder` — build `filesDir/cr.db` (SQLite) from parsed rows.
- `CrSync` — fetch the CR `.txt` from the editable URL, parse, build, validity-check, atomic-swap, reopen.
- `CrDb` — read-only helper: `roots`, `children`, `rule`, `glossary`, `hasRules`.
- `CrViewModel` + `CrReader` modal (browse hierarchy, jump-to-number, glossary, `openAt(number)` for
  deep-links, an "Update rules" sheet with the URL field + progress).
- A **"Comprehensive Rules"** item in the Game ⚙ hub to open the reader.

**Out of scope:**
- The stack assistant + tier-1/2/3 guidance (**M4b** — deep-links into this reader).
- Any build-time CR pipeline / bundled seed (fetch-on-first-use). A fresh install shows "Load rules (WiFi)".
- Full-text CR search (browse + jump-by-number + glossary term lookup only). No card/Game/Stack changes
  beyond the ⚙-hub entry.

## 3. Architecture

New package `com.magictablet.rules`. Reuses the Card Sync patterns (HttpURLConnection download, on-device
SQLite build, temp+validate+atomic-rename swap, read-only DB helper). `INTERNET`/`ACCESS_NETWORK_STATE` are
already in the manifest (Card Sync).

- **`rules/CrParser.kt`** (pure, unit-tested) — see §4.
- **`rules/CrDbBuilder.kt`** — creates the `Rule`/`Glossary` schema (§5) and inserts parsed rows via compiled
  statements in a transaction (same on-device-safe SQLite approach as `CardDbBuilder`, incl. stepping
  `PRAGMA journal_mode` via `rawQuery(...).use { it.moveToFirst() }`).
- **`rules/CrSync.kt`** — `sync(url, onProgress): CrSyncResult` on IO: download the CR text from `url` (headers,
  gzip-aware, byte progress) to `cacheDir` → `CrParser.parse` → `CrDbBuilder.build` to `filesDir/cr.db.new` →
  validate (`SELECT count(*) FROM Rule > 0`) → atomic rename over `cr.db`. Progress:
  `Connecting / Downloading(bytes,total) / Parsing(rules) / Finalizing`; result `Success(rules, terms) | Error(msg)`.
- **`rules/CrDb.kt`** — plain read-only SQLite over `filesDir/cr.db`. **No asset seed** — if `cr.db` is absent,
  `hasRules()` is false and the reader prompts to load. `prepare()`/`reopen()` open + validity-check (on invalid
  → delete + hasRules false; no re-seed since there's no asset). API: `hasRules(): Boolean`, `roots(): List<CrRule>`,
  `children(number): List<CrRule>`, `rule(number): CrRule?`, `glossary(term): String?`.
- **`rules/CrViewModel.kt`** (`AndroidViewModel`) — holds `CrDb`, the CR URL pref, `syncState`, and reader
  navigation state (current `number` + a back-stack). Intents: `open()`, `openAt(number)`, `up()`, `jumpTo(number)`,
  `setUrl(url)`, `startUpdate()`, `dismissSync()`.
- **`rules/CrReader.kt`** — the modal composable (§6).
- **`screens/GameScreen.kt`** — add a "Comprehensive Rules" ⚙-hub `DropdownMenuItem`; host the `CrReader` modal
  (shown via a `showRules` state) with a `CrViewModel = viewModel()` (Activity-scoped, so M4b's Stack screen can
  share it).

## 4. CrParser (the parse algorithm)

The WotC CR `.txt` is: intro → a "Contents" table → the numbered rules body → "Glossary" → "Credits". Each
numbered rule is a **single (long) line**; example lines follow their rule; entries are blank-line separated.

`fun parse(lines: Sequence<String>): CrCorpus` (returns `rules: List<RuleRow>`, `glossary: List<GlossaryRow>`):
- **Modes:** start in RULES; on a lone `Glossary` line → GLOSSARY; on a lone `Credits` line → STOP.
- **RULES:** a line matching the rule-number regex `^(\d+(?:\.\d+)*[a-z]?)\.?\s+(.+)$` starts a rule
  (`number` = group 1, `text` = group 2). A non-blank, non-rule-number line appends to the current rule's text
  (captures `Example:` lines). Dedup by `number` (last wins — the Contents table lists section/subsection titles
  identically to the body, so duplicates are harmless).
- **GLOSSARY:** blank-line-separated blocks; first line = `term`, remaining lines = `definition`.
- **`parent(number)`:** ends in a letter (`603.2a`) → strip the letter (`603.2`); else contains `.` (`603.2`) →
  `substringBeforeLast('.')` (`603`); else 3 digits (`603`) → first digit (`6`); else single digit (`6`) → `null` (root).
- **`sortKey(number)`:** zero-pad each numeric segment to 3 and keep a trailing letter, so ordering is correct
  (`603.9` < `603.10`; `603.2a` < `603.2b`). e.g. `603.2a` → `"603.002.a"`, `6` → `"006"`.

Pure and unit-tested (§8). The regex/heuristics are validated against a real CR by the manual update test;
the parser is isolated so tweaks are localized.

## 5. Schema (cr.db)

```sql
CREATE TABLE Rule (
  number  TEXT PRIMARY KEY,
  sortKey TEXT NOT NULL,
  parent  TEXT,            -- NULL for the 1..9 sections
  text    TEXT NOT NULL
);
CREATE INDEX idx_rule_parent ON Rule(parent, sortKey);
CREATE TABLE Glossary (
  term       TEXT PRIMARY KEY,
  definition TEXT NOT NULL
);
```
- `roots()` = `WHERE parent IS NULL ORDER BY sortKey` (the sections). `children(n)` = `WHERE parent = ? ORDER BY sortKey`.
- `rule(n)` = `WHERE number = ?`. `glossary(t)` = case-insensitive term match.

## 6. Reader UI (`CrReader` modal + `CrViewModel`)

Opened from the ⚙ hub (and, in M4b, via `openAt(number)`). A full-screen `Dialog`.

- **No rules loaded** (`!hasRules`): a message "Comprehensive Rules not loaded — connect to WiFi and update", the
  editable **URL** `OutlinedTextField` (prefilled from the pref), and an **Update rules** button → `startUpdate()`.
- **Loaded:** breadcrumb of the current `number`; the current rule/section `text`; a tappable list of its
  `children` (drill down); **Back/Up** (pops the nav back-stack / goes to `parent`); a **jump box** (type `603.2` →
  `jumpTo`); a **glossary** lookup; and an **Update rules** affordance (edit URL + refresh).
- **Update progress:** while `syncState` is `Running`, an inline progress row (Connecting → Downloading% →
  Parsing N → Finalizing → Done "Loaded N rules, M terms" / Error), Update disabled during a run.
- `openAt(number)`: opens the modal focused on that rule (the deep-link entry for M4b).

## 7. Editable URL

- Stored in `SharedPreferences("magictablet", MODE_PRIVATE)` under `cr_url`, defaulting to a documented recent
  CR `.txt` URL (the plan pins a concrete current one). Read/written by `CrViewModel`; edited in the reader's
  Update sheet. This is the "update the URL in the app" requirement.

## 8. Testing

- **Unit (JVM), `CrParserTest`:** a representative CR snippet (a section `6. …`, a subsection `603. …`, rules
  `603.1.`/`603.2.`, subrules `603.2a`/`603.2b`, an `Example:` line, a `Glossary` entry) → assert numbers,
  `parent` values (subrule→rule→subsection→section→null), `sortKey` ordering, text (incl. example appended), and
  the glossary term/definition. Plus `parent`/`sortKey` edge cases (`603.9` vs `603.10`).
- **Instrumented (on-device), `CrDbBuilderTest`:** build a small `cr.db` from sample `RuleRow`/`GlossaryRow` via
  `CrDbBuilder` on the tablet, open with `CrDb`, and assert `roots()`, `children("6")`, `rule("603.2a")`, and a
  glossary lookup. Proves the on-device SQLite build+read path for the CR schema.
- **Manual on-device:** open ⚙ → Comprehensive Rules → set the URL → Update rules → real CR fetch+parse
  completes (report rule/term counts); browse to a section, `jumpTo("603.2")`, read the text; a glossary lookup.

## 9. Definition of done (M4a)

- [ ] Game ⚙ hub has "Comprehensive Rules" → opens the reader modal.
- [ ] The CR URL is editable in-app (pref-backed, documented default); "Update rules" fetches from it, parses
      on-device, builds `cr.db`, and the reader then shows the rules. A fresh state shows the "Load rules" prompt.
- [ ] Browse hierarchy (section → rule → subrule) + jump-to-number + glossary lookup all work; `openAt(number)`
      focuses a given rule (the M4b deep-link API).
- [ ] Network is used only during a user-triggered rules update.
- [ ] `CrParserTest` passes; the `CrDbBuilder`/`CrDb` instrumented test passes; one real CR update verified
      manually with reported counts.

## 10. M4a → M4b seam

- M4b's stack tier-3 hints call `CrViewModel.openAt(number)` (e.g. `"603"`) to open the reader at a rule. The
  `CrViewModel` is Activity-scoped (`viewModel()`), so the Stack screen shares the same instance/loaded corpus.
  M4b may lift the modal host from `GameScreen` to a shared location; noted, not built here.

## 11. Risks / open items

- **Parser fragility** vs the real CR format (heuristic single-line-per-rule + example attach + glossary blocks) —
  isolated + unit-tested; the manual real-fetch test is the true validation; expect minor parser tuning against
  the actual CR text.
- **Editable URL can go stale / be wrong** — it's user-editable and the reader surfaces fetch errors clearly.
- **~2–3 MB CR fetch + parse on the weak SoC** — streaming line parse + batched inserts keep it bounded.

## 12. References

- `DESIGN.md` §5.3 (three-tier guidance references CR), §9 (CR deep-link), §11 (WotC IP — CR shown for reference).
- Reuses Card Sync (`CardSync`/`CardDbBuilder`/`CardDb`) patterns: on-device fetch, SQLite build, atomic swap,
  read-only helper, and the Android PRAGMA/`execSQL` lesson.
