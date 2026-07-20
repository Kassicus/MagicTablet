# MTG Table Assistant — Design Brief

A single-purpose Android app for a shared tablet in the middle of the table: track life totals for multiplayer Magic, look up cards and rulings offline, and manage a manual stack with resolution guidance. The tablet runs this app and nothing else, in kiosk mode.

This brief is the source of truth for the build. Where it states a **non-goal** or a **constraint**, treat it as binding — several of the scope boundaries here were chosen deliberately to keep the project buildable on weak hardware.

---

## 1. Target device & constraints

- **Device:** ECOPAD 10.1, Android 15, Rockchip-class SoC. ~4GB real RAM (the "12GB" spec is 4GB physical + 8GB virtual swap), 64GB storage, 6000mAh battery, 1280×800 IPS.
- **Single purpose:** the tablet only ever runs this app. It's been debloated (Google apps, Play Store, and vendor cruft removed; Play Services kept-but-quieted because it's boot-critical) and set up with a local/offline account — no Google sign-in — which preserves Device Owner provisioning for kiosk mode.
- **Performance budget:** assume a slow CPU and a weak GPU. Favor low memory and fast cold start over feature richness. This is why the stack is native (see §3) and why we avoid any embedded browser/JS runtime for the app shell.
- **Offline-first:** the tablet may have no network during play. All core features — life tracking, card lookup, stack assistance — must work fully offline. Network is optional and only used for periodic card-data refresh.
- **Non-commercial:** this is a personal fan project under Wizards of the Coast's Fan Content Policy. See §12.

---

## 2. Goals & non-goals

**Goals**
- Fast, reliable multiplayer life tracking (Commander-first).
- Instant offline card + ruling lookup.
- A manual, card-aware stack assistant that shows what's on the stack, what each card does, and guides correct resolution order and priority.
- Run locked-down as a kiosk that survives reboots.

**Non-goals (do not build these)**
- **Not a rules engine.** The app never computes the outcome of arbitrary card interactions (layered continuous effects, replacement-effect ordering, state-based edge cases). It surfaces authoritative text + rules references and lets the players adjudicate. Building a legality-computing engine is explicitly out of scope.
- **Not a digital play client.** Cards do not "live" in the app; players play with physical cards. The app assists, it doesn't run the game.
- **No card images** (storage + licensing weight; text and rulings only).
- **No online account, no telemetry, no ads, no monetization.**

---

## 3. Tech stack & architecture

- **Language/UI:** Kotlin + Jetpack Compose. Rationale: lowest memory/startup overhead vs. Flutter/React Native/webview, direct access to the Device Owner and Lock Task APIs needed for kiosk, and the UI is simple enough that Compose's overhead is a non-issue.
- **Structure:** single `Activity`, Compose UI, state-based navigation between three screens (Game / Cards / Stack). `ViewModel` + `StateFlow` for state.
- **Persistence — two halves:**
  - *Mutable live state* (`Game`, `Player`, `StackItem`) → Room, so a screen-off or crash never loses a match mid-game.
  - *Read-only reference* (`Card`, `Ruling`) → a prebuilt SQLite database shipped in `assets/` and opened via Room's `createFromAsset`, with an FTS index for search (see §5, §7).
- **Dependencies (keep minimal):** Compose, Room, kotlinx.serialization. Avoid heavy libraries. WebView is acceptable *only* for rendering static rules/oracle text inside a native screen — never as the app shell.
- **SDK:** compileSdk/targetSdk 35 (Android 15). minSdk can be modest (e.g. 26) since it targets one known device.
- **Orientation:** likely landscape-locked for the table layout — confirm in §11.

---

## 4. Data model

Mutable side (Room):

- `Game` — `id`, `format`, `startingLife`, `playerCount`, `activePlayerId`, `turnNumber`
- `Player` — `id`, `gameId`, `name`, `seat`, `life`, `poison`, `counters` (JSON: energy/experience/etc.), `commanderDamage` (JSON: opponentId → amount)
- `StackItem` — `id`, `gameId`, `controllerId`, `cardOracleId` (nullable — abilities may not map to a looked-up card), `orderIndex`, `kind` (spell / activated / triggered), `targets`

Bundled read-only side (SQLite in assets):

- `Card` — `oracleId` (PK), `name`, `manaCost`, `typeLine`, `oracleText`, `keywords` (JSON)
- `Ruling` — `id`, `oracleId` (FK), `publishedAt`, `text`

Key relationships: `Game 1—* Player`, `Game 1—* StackItem`, `Player 1—* StackItem` (controller), `Card 0..1—* StackItem` (optional link), `Card 1—* Ruling`. The load-bearing join is `StackItem.cardOracleId → Card.oracleId` — adding a card to the stack immediately yields its full text and rulings.

---

## 5. Feature specs

### 5.1 Game screen (life totals) — default screen

- Player panels arranged around the screen edges and **rotated to face each seat** (two panels upside-down at a 4-player table).
- Per panel: large life number; tap and **hold-to-repeat** +/− zones; a "recent delta" number that fades after ~1s; an expandable row for **poison** (10 = loss), **per-opponent commander damage** (21 = loss), and generic counters (energy/experience).
- Configurable **player count 2–6** and **starting life** (40 default for Commander, plus 20/25/custom presets).
- Table utilities: new game / reset, dice + coin flip, random first player, turn/monarch marker, optional game timer.
- Reads/writes only the mutable side.

### 5.2 Cards screen (offline lookup)

- Search box over the FTS index (match on `name` and `oracleText`).
- Results list → detail view: Oracle text, type line, mana cost, and the rulings joined from `Ruling`.
- Pure reads against the bundled DB. Fully offline. Consider recent/favorites.

### 5.3 Stack screen (card-aware assistant)

- Add an item: pick a controller (player), then search a card (links to `Card`) or free-type an ability. It lands on **top** (LIFO) as a `StackItem`.
- Show the LIFO list; each item displays its Oracle text and rulings inline when linked.
- **Resolve top** pops the item and shows resolution guidance.
- **Priority tracker:** whose priority, pass / pass-around, "all passed → top resolves."

**Resolution guidance comes from three tiers (descending reliability):**
1. **Authoritative display** — the card's actual Oracle text and official rulings. Settles most real table disputes on its own.
2. **Always-correct general procedure** — resolve one at a time top-down; priority after each resolution; triggers go on top in turn order; a spell whose targets all become illegal doesn't resolve. These are card-independent and can always be stated.
3. **Pattern-matched hints (soft)** — recognize `When/Whenever/At` = triggered, `[cost]: [effect]` = activated, presence of `target`, and known keywords; surface reminder text and a deep-link to the relevant Comprehensive Rules section.

Do **not** attempt to compute arbitrary interaction outcomes. For genuine edge cases, present the exact card text + rule reference and let players adjudicate.

---

## 6. Kiosk / Device Owner (in-app requirements)

Device provisioning (debloat + offline account) is already done. The app must include the kiosk pieces:

- A `DeviceAdminReceiver` declared in the manifest with a `device_admin` meta-data XML (policies: force-lock, etc.).
- Provisioned once via ADB on the account-free device:
  `adb shell dpm set-device-owner <package>/.AdminReceiver`
- As Device Owner: call `dpm.setLockTaskPackages(admin, arrayOf(packageName))`, then `startLockTask()` in the main Activity so the user can't leave the app (no exit prompt, survives reboot).
- Register the main Activity as **HOME/launcher** (add `HOME` + `DEFAULT` categories alongside `LAUNCHER`) so the app is the only thing that ever shows. Only disable/remove the stock `launcher3` *after* this is set.
- Use `FLAG_KEEP_SCREEN_ON` **only while a game is active**, so the screen sleeps normally otherwise (battery).
- Provide an in-app maintenance path to un-kiosk (`dpm.clearDeviceOwnerApp(...)`) for updates.

---

## 7. Card data pipeline (build-time, offline bundle)

Goal: turn Scryfall bulk exports into a trimmed SQLite file bundled in `assets/`.

1. Fetch two Scryfall bulk exports: **Oracle Cards** (one entry per unique card — current name, mana cost, type line, oracle text, keywords) and **Rulings** (joined to cards via `oracle_id`). These are `jsonl.gz`; stream them.
2. Trim to only the fields in §4. **Drop card images and price fields** (prices go stale within a day and aren't needed).
3. Write into SQLite: a `Card` table, a `Ruling` table, and an **FTS5 virtual table** over `name` + `oracle_text` for search. Query it from Room via a raw `MATCH` query / dedicated DAO.
4. Bundle the resulting `.db` in `assets/` and open with Room `createFromAsset`. Size is a small fraction of the 64GB storage once images/prices are excluded.
5. **Refresh cadence:** gameplay text changes rarely, so a weekly or post-set-release rebuild is plenty. **Decision (2026-07-20):** this build script produces the **seed `cards.db` shipped in `assets/`** (so the app works offline on install); refresh is via **in-app WiFi sync** — the tablet itself downloads the Scryfall bulk exports and rebuilds the FTS DB into internal storage (a Kotlin port of this script's parse/filter/FTS logic). The app reads the newer internal-storage DB in preference to the bundled seed.

A standalone script (Python + sqlite3, or Kotlin) that does steps 1–3 should live in the repo (e.g. `tools/build_card_db/`).

---

## 8. Suggested build order

- **M0 — Skeleton:** Compose single-Activity app, three-screen navigation, theme, SDK config.
- **M1 — Life totals:** the Game screen end to end (rotated panels, counters, utilities). Standalone, no data dependency — gives a usable app fast.
- **M2 — Data pipeline:** the §7 script → bundled `Card`/`Ruling` SQLite with FTS.
- **M3 — Card lookup:** search + detail on the bundled DB.
- **M4 — Stack assistant:** add/resolve items, card linkage, the three-tier guidance, Comp Rules deep-links.
- **M5 — Kiosk hardening:** Device Owner receiver, Lock Task, HOME launcher, keep-screen-on-during-game, maintenance exit.
- **M6 — Persistence & polish:** Room save/restore of live state, config presets, dice/timer, data-refresh mechanism.

---

## 9. Open questions (decide before or during build)

- Which format/player-count presets to ship by default (Commander 4×40 assumed primary).
- Comp Rules deep-links: bundle the full Comprehensive Rules text for in-app display, or just reference rule numbers?
- Card-data refresh: ~~manual re-bundle + reinstall, or in-app WiFi sync?~~ **Resolved (2026-07-20): in-app WiFi sync — the tablet rebuilds from Scryfall bulk on-device; a seed DB ships in `assets/` for offline-first.** (See §7.5.)
- Commander-damage UI density at 5–6 players.
- Orientation: landscape-locked?

---

## 10. Definition of done (v1)

A locked-down tablet that boots straight into the app, tracks a 4-player Commander game (life, poison, commander damage, counters), looks up any card's text and rulings offline, and lets players build and resolve a stack with card text and correct procedural guidance — with no way to exit to the rest of the OS.

---

## 11. Attribution & licensing

- Personal, non-commercial fan project under Wizards of the Coast's Fan Content Policy. Not affiliated with or endorsed by Wizards of the Coast.
- Card data from Scryfall — follow Scryfall's usage terms and include appropriate attribution. Gameplay text and card names are Wizards of the Coast IP; the app displays them for reference only.
- No card images are bundled or distributed.
