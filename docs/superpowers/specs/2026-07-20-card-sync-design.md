# Card Sync (In-App WiFi Refresh) — Design Spec

**Date:** 2026-07-20
**Milestone:** Card Sync (the in-app-sync milestone resolved in DESIGN.md §7.5 / §9; follows M3)
**Status:** Approved scope, pending spec review

> Master brief: `DESIGN.md` §7.5 (refresh decision). This milestone lets the **tablet** refresh its
> card database over WiFi by downloading Scryfall bulk data and rebuilding the FTS4 DB on-device — a
> Kotlin port of the M2 Python pipeline. Builds on M2 (seed) + M3 (`CardDb`, Cards screen). Also folds
> in the two `CardDb` carry-overs from M3 review (reopen + sticky-failure recovery).

---

## 1. Purpose

Keep the offline card data current without a computer: a manual **"Update card data"** action on the
Cards screen downloads the latest Scryfall Oracle Cards + Rulings, rebuilds the trimmed FTS4 SQLite
on-device, and swaps it in so search uses fresh data — all while the app stays otherwise offline.

## 2. Scope

**In scope:**
- `CardSync` — on-device download + FTS4 rebuild (Kotlin port of M2's filter/extract/schema).
- Pure `CardSyncLogic` (`shouldInclude`/`toCardRow`/`toRulingRow`) mirroring the M2 Python functions.
- `CardDb` hardening (fixes M3 carry-overs): `reopen()`, atomic seed copy (temp+rename), and a
  post-open validity check that re-seeds from the asset on failure.
- `CardsViewModel` sync state + `startSync()`; a Cards-screen **↻ Update** action + progress UI.
- `INTERNET` + `ACCESS_NETWORK_STATE` manifest permissions.
- Unit tests (ported logic) + instrumented tests (small on-device FTS4 build; recovery) + one manual real sync.

**Out of scope (deferred):**
- Auto-check on launch; background execution / WorkManager (foreground coroutine only, kiosk is always-on).
- Syncing anything but cards (no rules text, etc.).
- The M3 UI-polish minors (search "No matches" flicker; `buildMatchQuery` diacritics).

## 3. Architecture

New/changed in package `com.magictablet.cards`:

- **`CardSyncLogic.kt`** (pure, unit-tested) — the M2 port:
  - `RawCard(oracleId, name, layout, manaCost, typeLine, oracleText, keywords: List<String>, faces: List<RawFace>)`,
    `RawFace(manaCost, typeLine, oracleText)`, `RawRuling(oracleId, publishedAt, comment)`.
  - `EXCLUDED_LAYOUTS = setOf("token","double_faced_token","emblem","art_series")`.
  - `fun shouldInclude(c: RawCard): Boolean` — `oracleId` non-blank, `layout !in EXCLUDED_LAYOUTS`, `name` not starting `"A-"`.
  - `fun toCardRow(c: RawCard): CardRow` — `CardRow(oracleId, name, manaCost, typeLine, oracleText, keywordsJson)`;
    `manaCost`/`typeLine` fall back to faces joined `" // "`; `oracleText` falls back to faces joined `"\n//\n"`
    **only when null**; `keywordsJson = JSONArray(keywords).toString()`.
  - `fun toRulingRow(r: RawRuling): RulingRow?` — `null` if no `oracleId`; else `RulingRow(oracleId, publishedAt ?: "", comment ?: "")`.
- **`CardSync.kt`** — orchestration (§4). `suspend fun sync(onProgress: (SyncProgress) -> Unit): SyncResult`.
- **`CardDb.kt`** (modified, §5) — `reopen()`, atomic seed, validity/recovery.
- **`CardsViewModel.kt`** (modified) — `syncState: StateFlow<SyncUiState>`, `startSync()`.
- **`CardsScreen.kt`** (modified) — ↻ Update action + progress dialog.
- **`AndroidManifest.xml`** — `INTERNET`, `ACCESS_NETWORK_STATE`.

## 4. Sync flow (`CardSync`)

Runs on `Dispatchers.IO`. Uses `java.net.HttpURLConnection` (no new deps).

1. **Connectivity check** — via `ConnectivityManager`; if no validated internet, `SyncResult.Error("No network")`.
2. **Bulk URLs** — `GET https://api.scryfall.com/bulk-data` with `User-Agent: MagicTablet/0.1 (personal fan project)`
   and `Accept: application/json`; take `download_uri` for `oracle_cards` and `rulings`. Error if either missing.
3. **Download** each to a temp file in `cacheDir` (`oracle_cards.json[.gz]`, `rulings.json[.gz]`):
   - Request `Accept-Encoding: gzip`; stream the response body to the temp file in 64 KB chunks; report
     `Downloading(which, bytesRead, contentLength)` for a progress bar. Record whether `Content-Encoding: gzip`.
4. **Build** a fresh DB at `filesDir/cards.db.new` (delete any stale one first):
   - Create the **same FTS4 schema as M2**: `Card`, `Ruling`, `idx_ruling_oracleId`, and
     `CardFts USING fts4(name, oracleText, oracleId, notindexed=oracleId, tokenize=unicode61 "remove_diacritics=2")`.
     `PRAGMA journal_mode=OFF; synchronous=OFF` for the throwaway build.
   - Stream the oracle file with `android.util.JsonReader` (wrap in `GZIPInputStream` if gzipped): read each array
     element into a `RawCard`; skip `!shouldInclude`; else insert `toCardRow` into `Card` + `CardFts` (batched
     transactions ~2000 rows); collect kept `oracleId`s. Report `Building(count)` periodically.
   - Stream rulings; insert `toRulingRow` for rulings whose `oracleId` is kept.
5. **Validity check** — open `cards.db.new` read-only, assert `SELECT count(*) FROM Card > 0`; close.
6. **Atomic swap** — `Finalizing`: close the live `CardDb` handle, `File(cards.db.new).renameTo(cards.db)`
   (atomic within `filesDir`), then `CardDb.reopen()`. Delete temp downloads.
7. Return `SyncResult.Success(cardCount, rulingCount)`; any exception → `SyncResult.Error(message)` (the live DB
   is untouched because we only swap after a successful build+validity).

**Progress model:** `sealed SyncProgress { Connecting; Downloading(which, bytes, total); Building(cards); Finalizing }`.
`sealed SyncResult { Success(cards, rulings); Error(message) }`.

## 5. CardDb changes (fixes M3 carry-overs)

- **`reopen()`** — `close()` the current `SQLiteDatabase`, then re-run the open path (open `filesDir/cards.db`
  read-only) so the app uses the freshly-swapped DB. `close()` exposed for the swap.
- **Atomic seed** — `prepare()` copies the asset to `cards.db.tmp` then `renameTo(cards.db)` (no more truncated
  DB left by an interrupted copy).
- **Validity + recovery** — after opening (in `prepare`/`reopen`), run `SELECT count(*) FROM Card`; if it throws or
  is 0, close, delete `cards.db`, re-copy from the asset, and reopen. Kills the sticky-failure path where a
  truncated DB was served forever.

## 6. CardsViewModel + UI

- **`syncState: StateFlow<SyncUiState>`** — `sealed { Idle; Running(SyncProgress); Done(cards, rulings); Error(msg) }`.
- **`startSync()`** — if already running, no-op; else `viewModelScope.launch(Dispatchers.IO)`: set `Running`, call
  `CardSync.sync { syncState = Running(it) }`, on `Success` → `db.reopen()`, re-run the current query, set `Done`;
  on `Error` → `Error`. `dismissSync()` returns to `Idle`.
- **`CardsScreen`** — an **↻ Update** `IconButton`/text action in the search row; tapping calls `startSync()`.
  When `syncState != Idle`, show a modal (`AlertDialog`): phase text ("Connecting…", "Downloading cards 42%",
  "Building… 12,000", "Finalizing…"), a determinate bar during download / indeterminate during build, and a
  **Close** button enabled only on `Done`/`Error` (Done shows "Updated: N cards, M rulings"). Update is disabled
  while running.

## 7. Networking details

- All requests set the `User-Agent` + `Accept` headers (Scryfall etiquette). Connect/read timeouts (e.g. 30 s
  connect, generous read). Follow redirects (the `download_uri` is a CDN URL).
- Handle optional gzip: request `Accept-Encoding: gzip`; if the response `Content-Encoding` is gzip, wrap the temp
  file's read in `GZIPInputStream` for parsing. Progress is reported against the transferred (possibly compressed)
  `Content-Length`, so the bar is accurate either way.
- Downloads go to `cacheDir` (OS can reclaim) and are deleted after a successful build.

## 8. Testing

- **Unit (JVM), `CardSyncLogicTest`:** mirror the M2 Python tests — `shouldInclude` excludes each excluded layout,
  an `A-` name, and a blank `oracleId`, includes a normal card; `toCardRow` maps a simple card and joins a DFC
  (`" // "` / `"\n//\n"`), and a missing `oracleText` → `""`; `toRulingRow` maps `comment`→text and returns null
  for a missing `oracleId`.
- **Instrumented (on-device):**
  - `CardSyncBuildTest` — build a small FTS4 DB from a few in-memory `RawCard`s via the sync's DB-builder (no
    network), open + `MATCH` → verify a known fake card is found. **Proves on-device FTS4 CREATE+build+query with
    the real `unicode61 remove_diacritics=2` schema.**
  - `CardDbRecoveryTest` — seed; corrupt/truncate `filesDir/cards.db`; `reopen()` → the validity check re-seeds
    from the asset → `search` works again. And a normal `reopen()` after swapping a file picks up the new data.
- **Manual on-device:** run one real full sync (tap Update → watch phases complete → confirm search reflects
  fresh data). The ~190 MB download + full build is verified manually, not in CI.

## 9. Definition of done

- [ ] Cards screen has an ↻ Update action that runs a sync with visible phase/progress and a Close on completion.
- [ ] A sync downloads Scryfall bulk, rebuilds the FTS4 DB on-device, validity-checks it, atomically replaces
      `filesDir/cards.db`, `CardDb` reopens, and search uses the fresh data. The live DB is untouched on any failure.
- [ ] `CardDb` seeds atomically and re-seeds from the asset when the internal DB is missing/invalid.
- [ ] `INTERNET` + `ACCESS_NETWORK_STATE` added; the app uses the network **only** during a user-triggered sync.
- [ ] Unit tests (`CardSyncLogic`) pass; instrumented tests (on-device small build + recovery) pass; one real
      sync verified manually (report the resulting card/ruling counts).

## 10. Risks / seams

- **Time/memory on weak hardware:** ~190 MB download + ~30k-card build takes minutes; streaming (`JsonReader`,
  chunked download, batched inserts) keeps memory bounded; progress UI sets expectations.
- **unicode61 FTS4 CREATE on-device:** M3 proved query works; `CardSyncBuildTest` proves CREATE+build works.
- **Swap safety:** build to a temp file and only close+rename+reopen after a passing validity check, so an
  interrupted or failed sync never corrupts the served DB.
- **Kiosk timing (future):** in kiosk/Lock-Task mode (M5), a sync running for minutes must not trip watchdogs;
  the foreground coroutine + progress UI is fine, but revisit if M5 adds constraints.

## 11. References

- `DESIGN.md` §7 (pipeline), §7.5 (refresh decision), §5.2 (Cards).
- `tools/build_card_db/build_card_db.py` — the reference implementation this ports (schema, filters, field mapping).
- M3 `CardDb` (the read path this extends) + the M3-review carry-overs (reopen, atomic seed, validity/recovery).
