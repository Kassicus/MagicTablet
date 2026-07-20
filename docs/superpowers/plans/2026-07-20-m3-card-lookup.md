# M3 — Card Lookup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Cards placeholder into an offline card/ruling lookup over the M2 FTS4 seed DB: search → ranked results → detail (oracle text, type line, mana cost, keywords, rulings), plus in-session recently-viewed.

**Architecture:** A plain read-only SQLite helper (`CardDb`) reads `filesDir/cards.db` (seeded from the `assets/cards.db` asset on first run) — no Room for the card DB. A pure `buildMatchQuery` sanitizes user text into safe FTS4 prefix terms; ranking (name matches first) is done in SQL. A `CardsViewModel` runs debounced searches on `Dispatchers.IO`; `CardsScreen` renders search/results/detail/recently-viewed.

**Tech Stack:** Kotlin, Compose + Material 3, AndroidX Lifecycle 2.8.7, `android.database.sqlite` + `org.json` (built-in), JUnit 4 + AndroidX Test (instrumented).

## Global Constraints

From the spec (`docs/superpowers/specs/2026-07-20-m3-card-lookup-design.md`) and verified state:

- New package `com.magictablet.cards`. Replaces the `screens/CardsScreen.kt` placeholder. **No change to Game/Stack or the M1 game state.**
- **Read-only card DB via a plain SQLite helper — NOT Room** (Room's `createFromAsset` fights the hand-built, sync-replaceable FTS4 DB). Read from `filesDir/cards.db`, seeded by copying `assets/cards.db` if missing.
- **No new *runtime* dependencies:** keywords parsed with `org.json` (Android built-in). The only new deps are **androidTest-only**: `androidx.test.ext:junit:1.2.1`, `androidx.test:runner:1.6.2`.
- The DB tables are `Card(oracleId, name, manaCost, typeLine, oracleText, keywords)`, `Ruling(id, oracleId, publishedAt, text)`, and FTS4 `CardFts(name, oracleText, oracleId)` (from M2).
- **FTS4 (not FTS5):** MATCH selects candidates; ranking is the SQL `CASE` (exact→prefix→substring name, then oracle-text). No `bm25`.
- `buildMatchQuery`: lowercase, split on non-alphanumeric, drop empties, append `*` per token; blank → `""`.
- All DB access off the main thread (`Dispatchers.IO`); first-run ~48 MB copy behind a `loading`/ready state.
- The `app/src/main/assets/cards.db` seed must be present for the instrumented test + app run (it is; regenerate via `tools/build_card_db` if not). It is gitignored.
- **Environment preamble** (prefix build/adb commands; shell state does not persist between commands):

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
  ```

- **Sandbox:** Gradle + adb/USB commands need `dangerouslyDisableSandbox: true`. Install/tests on device `TK12110626B081745`. Give `connectedDebugAndroidTest` a generous timeout (e.g. 600000 ms).

> **Testing note:** Task 1 = offline JVM unit tests (TDD). Task 2 = an on-device instrumented test (the definitive FTS4-on-device proof). Task 3 = on-device manual UI verification.

---

### Task 1: Card models + pure FTS4 query builder (TDD)

**Files:**
- Create: `app/src/main/java/com/magictablet/cards/CardModels.kt`
- Create: `app/src/main/java/com/magictablet/cards/CardQuery.kt`
- Test: `app/src/test/java/com/magictablet/cards/CardQueryTest.kt`

**Interfaces:**
- Produces `CardSummary`, `CardDetail`, `RulingItem`; `fun buildMatchQuery(userText: String): String`.

- [ ] **Step 1: Write the failing test** — `app/src/test/java/com/magictablet/cards/CardQueryTest.kt`

```kotlin
package com.magictablet.cards

import org.junit.Assert.assertEquals
import org.junit.Test

class CardQueryTest {
    @Test fun singleWord_getsPrefix() {
        assertEquals("lightning*", buildMatchQuery("Lightning"))
    }

    @Test fun multiWord_eachPrefixedAndLowercased() {
        assertEquals("lightning* bol*", buildMatchQuery("Lightning bol"))
    }

    @Test fun punctuation_splitsAndStrips() {
        assertEquals("gaea* s* cradle*", buildMatchQuery("Gaea's, Cradle"))
    }

    @Test fun digitsKept() {
        assertEquals("bolt* 3*", buildMatchQuery("Bolt 3"))
    }

    @Test fun blank_isEmpty() {
        assertEquals("", buildMatchQuery(""))
        assertEquals("", buildMatchQuery("   "))
        assertEquals("", buildMatchQuery(",,, --- "))
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.cards.CardQueryTest"
```
Expected: FAIL — `buildMatchQuery` unresolved.

- [ ] **Step 3: Create `app/src/main/java/com/magictablet/cards/CardModels.kt`**

```kotlin
package com.magictablet.cards

data class CardSummary(
    val oracleId: String,
    val name: String,
    val manaCost: String,
    val typeLine: String,
)

data class RulingItem(
    val publishedAt: String,
    val text: String,
)

data class CardDetail(
    val oracleId: String,
    val name: String,
    val manaCost: String,
    val typeLine: String,
    val oracleText: String,
    val keywords: List<String>,
    val rulings: List<RulingItem>,
)
```

- [ ] **Step 4: Create `app/src/main/java/com/magictablet/cards/CardQuery.kt`**

```kotlin
package com.magictablet.cards

private val NON_ALNUM = Regex("[^a-z0-9]+")

/**
 * Build a safe FTS4 MATCH query from raw user text: lowercase, split on non-alphanumeric runs,
 * drop empties, and append a prefix '*' to each token (as-you-type). Blank input -> "".
 * Sanitizing to alphanumeric prefix terms keeps FTS special characters from breaking MATCH.
 */
fun buildMatchQuery(userText: String): String =
    userText.lowercase()
        .split(NON_ALNUM)
        .filter { it.isNotEmpty() }
        .joinToString(" ") { "$it*" }
```

- [ ] **Step 5: Run the test, verify it passes**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.cards.CardQueryTest"
```
Expected: all 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "M3 Task 1: card models + pure FTS4 query builder (TDD)"
```

---

### Task 2: CardDb (SQLite access) + on-device instrumented test

**Files:**
- Create: `app/src/main/java/com/magictablet/cards/CardDb.kt`
- Modify: `gradle/libs.versions.toml` (androidTest deps)
- Modify: `app/build.gradle.kts` (testInstrumentationRunner + androidTest deps)
- Test: `app/src/androidTest/java/com/magictablet/cards/CardDbTest.kt`

**Interfaces:**
- Consumes `buildMatchQuery`, `CardSummary`, `CardDetail`, `RulingItem` (Task 1).
- Produces `CardDb(context)` with `fun prepare()`, `fun search(userText, limit=50): List<CardSummary>`, `fun card(oracleId): CardDetail?`, and `CardDb.DB_NAME = "cards.db"`.

- [ ] **Step 1: Add androidTest deps to `gradle/libs.versions.toml`** — under `[versions]` add:

```toml
androidxTestExtJunit = "1.2.1"
androidxTestRunner = "1.6.2"
```
under `[libraries]` add:
```toml
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
```

- [ ] **Step 2: Wire androidTest in `app/build.gradle.kts`** — in `defaultConfig`, add:

```kotlin
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
```
and in `dependencies { }` add:
```kotlin
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
```

- [ ] **Step 3: Create `app/src/main/java/com/magictablet/cards/CardDb.kt`**

```kotlin
package com.magictablet.cards

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.File

/**
 * Read-only access to the bundled card database. Seeds [DB_NAME] into filesDir from assets on first
 * use, then opens it read-only. All methods block on I/O — call from Dispatchers.IO.
 */
class CardDb(private val appContext: Context) {
    private var db: SQLiteDatabase? = null

    /** Seed filesDir/cards.db from assets if missing, then open read-only. Idempotent. */
    fun prepare() {
        if (db != null) return
        val target = File(appContext.filesDir, DB_NAME)
        if (!target.exists()) {
            appContext.assets.open(DB_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun search(userText: String, limit: Int = 50): List<CardSummary> {
        val match = buildMatchQuery(userText)
        if (match.isEmpty()) return emptyList()
        val conn = db ?: return emptyList()
        val raw = userText.trim().lowercase()
        val out = ArrayList<CardSummary>()
        conn.rawQuery("$SEARCH_SQL LIMIT $limit", arrayOf(match, raw, raw, raw)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    CardSummary(
                        oracleId = c.getString(0),
                        name = c.getString(1),
                        manaCost = c.getString(2) ?: "",
                        typeLine = c.getString(3) ?: "",
                    )
                )
            }
        }
        return out
    }

    fun card(oracleId: String): CardDetail? {
        val conn = db ?: return null
        var base: CardDetail? = null
        conn.rawQuery(CARD_SQL, arrayOf(oracleId)).use { c ->
            if (c.moveToNext()) {
                base = CardDetail(
                    oracleId = oracleId,
                    name = c.getString(0),
                    manaCost = c.getString(1) ?: "",
                    typeLine = c.getString(2) ?: "",
                    oracleText = c.getString(3) ?: "",
                    keywords = parseKeywords(c.getString(4)),
                    rulings = emptyList(),
                )
            }
        }
        val detail = base ?: return null
        val rulings = ArrayList<RulingItem>()
        conn.rawQuery(RULINGS_SQL, arrayOf(oracleId)).use { c ->
            while (c.moveToNext()) {
                rulings.add(RulingItem(c.getString(0) ?: "", c.getString(1) ?: ""))
            }
        }
        return detail.copy(rulings = rulings)
    }

    private fun parseKeywords(json: String?): List<String> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        const val DB_NAME = "cards.db"

        // ORDER BY floats name matches above oracle-text-only matches (FTS4 has no bm25).
        // LIMIT is appended from a trusted Int; the ? params are match, raw, raw, raw.
        private const val SEARCH_SQL = """
            SELECT Card.oracleId, Card.name, Card.manaCost, Card.typeLine
            FROM CardFts JOIN Card ON Card.oracleId = CardFts.oracleId
            WHERE CardFts MATCH ?
            ORDER BY
              CASE
                WHEN lower(Card.name) = ? THEN 0
                WHEN lower(Card.name) LIKE ? || '%' THEN 1
                WHEN instr(lower(Card.name), ?) > 0 THEN 2
                ELSE 3
              END,
              length(Card.name), Card.name
        """

        private const val CARD_SQL =
            "SELECT name, manaCost, typeLine, oracleText, keywords FROM Card WHERE oracleId = ?"
        private const val RULINGS_SQL =
            "SELECT publishedAt, text FROM Ruling WHERE oracleId = ? ORDER BY publishedAt"
    }
}
```

- [ ] **Step 4: Create `app/src/androidTest/java/com/magictablet/cards/CardDbTest.kt`**

```kotlin
package com.magictablet.cards

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardDbTest {
    private lateinit var db: CardDb

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getFileStreamPath(CardDb.DB_NAME).delete() // force a fresh seed each run
        db = CardDb(ctx)
        db.prepare()
    }

    @Test fun search_ranksExactNameFirst() {
        val results = db.search("lightning bolt")
        assertTrue("expected non-empty results", results.isNotEmpty())
        assertEquals("Lightning Bolt", results.first().name)
    }

    @Test fun card_hasOracleTextAndRulings() {
        val id = db.search("lightning bolt").first { it.name == "Lightning Bolt" }.oracleId
        val detail = db.card(id)
        assertNotNull(detail)
        assertTrue(detail!!.oracleText.contains("3 damage"))
    }
}
```

- [ ] **Step 5: Run the instrumented test on the device** (the FTS4-on-device proof)

Run (needs the device + the seed asset present; use `dangerouslyDisableSandbox: true`, generous timeout):
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:connectedDebugAndroidTest --tests "com.magictablet.cards.CardDbTest"
```
Expected: both tests PASS on `TK12110626B081745` — proving FTS4 `MATCH` + ranking + the join work on the tablet's framework SQLite against the real seed DB. (If the seed asset is missing, first run `tools/build_card_db/.venv/bin/python tools/build_card_db/build_card_db.py`.)

- [ ] **Step 6: Also confirm the unit suite still builds/passes**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL (M1/M3 unit tests green).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "M3 Task 2: read-only CardDb (FTS4 search + detail) + on-device instrumented test"
```

---

### Task 3: CardsViewModel + Cards screen UI

**Files:**
- Create: `app/src/main/java/com/magictablet/cards/CardsViewModel.kt`
- Modify (replace): `app/src/main/java/com/magictablet/screens/CardsScreen.kt`

**Interfaces:**
- Consumes `CardDb`, `CardSummary`, `CardDetail`, `RulingItem`.
- `CardsScreen(viewModel: CardsViewModel = viewModel())` — `App.kt`'s `CardsScreen()` call site keeps working (default arg).

- [ ] **Step 1: Create `app/src/main/java/com/magictablet/cards/CardsViewModel.kt`**

```kotlin
package com.magictablet.cards

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CardsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = CardDb(app)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _selected = MutableStateFlow<CardDetail?>(null)
    val selected: StateFlow<CardDetail?> = _selected.asStateFlow()

    private val _recent = MutableStateFlow<List<CardSummary>>(emptyList())
    val recent: StateFlow<List<CardSummary>> = _recent.asStateFlow()

    val results: StateFlow<List<CardSummary>> =
        combine(
            _query.debounce(250).map { it.trim() }.distinctUntilChanged(),
            _ready,
        ) { q, ready -> q to ready }
            .flatMapLatest { (q, ready) ->
                if (!ready || q.isEmpty()) flowOf(emptyList())
                else flow { emit(db.search(q)) }.flowOn(Dispatchers.IO)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            db.prepare()
            _ready.value = true
        }
    }

    fun onQueryChange(text: String) {
        _query.value = text
    }

    fun openCard(oracleId: String) {
        viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) { db.card(oracleId) } ?: return@launch
            _selected.value = detail
            val summary = CardSummary(detail.oracleId, detail.name, detail.manaCost, detail.typeLine)
            _recent.value = (listOf(summary) + _recent.value.filterNot { it.oracleId == summary.oracleId }).take(15)
        }
    }

    fun closeDetail() {
        _selected.value = null
    }
}
```

- [ ] **Step 2: Replace `app/src/main/java/com/magictablet/screens/CardsScreen.kt`**

```kotlin
package com.magictablet.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.cards.CardDetail
import com.magictablet.cards.CardSummary
import com.magictablet.cards.CardsViewModel

@Composable
fun CardsScreen(viewModel: CardsViewModel = viewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()

    BackHandler(enabled = selected != null) { viewModel.closeDetail() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text("Search cards") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { viewModel.onQueryChange("") }) { Text("Clear") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        val detail = selected
        when {
            detail != null -> CardDetailView(detail, onBack = viewModel::closeDetail)
            !ready -> Hint("Preparing card database…")
            query.isBlank() -> RecentList(recent, viewModel::openCard)
            else -> ResultsList(results, viewModel::openCard)
        }
    }
}

@Composable
private fun ResultsList(items: List<CardSummary>, onOpen: (String) -> Unit) {
    if (items.isEmpty()) { Hint("No matches"); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.oracleId }) { CardRow(it, onOpen) }
    }
}

@Composable
private fun RecentList(items: List<CardSummary>, onOpen: (String) -> Unit) {
    if (items.isEmpty()) { Hint("Search for a card"); return }
    Column(Modifier.fillMaxSize()) {
        Text("Recently viewed", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn { items(items, key = { it.oracleId }) { CardRow(it, onOpen) } }
    }
}

@Composable
private fun CardRow(card: CardSummary, onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onOpen(card.oracleId) }.padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(card.name, style = MaterialTheme.typography.bodyLarge)
            if (card.manaCost.isNotEmpty()) Text(card.manaCost, style = MaterialTheme.typography.bodyMedium)
        }
        if (card.typeLine.isNotEmpty()) {
            Text(card.typeLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CardDetailView(detail: CardDetail, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text(detail.name, style = MaterialTheme.typography.headlineSmall)
        if (detail.manaCost.isNotEmpty()) Text(detail.manaCost, style = MaterialTheme.typography.bodyLarge)
        if (detail.typeLine.isNotEmpty()) Text(detail.typeLine, style = MaterialTheme.typography.bodyMedium)
        if (detail.oracleText.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(detail.oracleText, style = MaterialTheme.typography.bodyMedium)
        }
        if (detail.keywords.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Keywords: ${detail.keywords.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        }
        if (detail.rulings.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Rulings", style = MaterialTheme.typography.titleMedium)
            detail.rulings.forEach { r ->
                Spacer(Modifier.height(6.dp))
                Text(r.publishedAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(r.text, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}
```

- [ ] **Step 3: Build, install, verify on device**

Run:
```bash
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
"$ADB" shell am start -n com.magictablet/.MainActivity
```
Expected on the **Cards** tab: (first launch) a brief "Preparing card database…", then a search box. Typing `lightning bolt` shows results with **Lightning Bolt at/near the top**; tapping it opens a detail with its oracle text ("…3 damage…"), type line, mana cost, and any rulings; system-back returns to the list. Clearing the query shows **Recently viewed** with the cards you opened. Game/Stack tabs unchanged. No network used.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "M3 Task 3: CardsViewModel + offline Cards screen (search/detail/recent)"
```

---

## Definition of Done (M3)

- [ ] Cards screen searches as you type; results ranked with name matches first.
- [ ] Tapping a result opens detail (oracle text, type line, mana cost, keywords, rulings by date); back returns.
- [ ] Blank query shows Recently viewed (populated by opening cards this session).
- [ ] First launch seeds `filesDir/cards.db` from the asset behind a loading state; later launches are instant.
- [ ] Fully offline (no network anywhere in M3).
- [ ] `CardQueryTest` unit tests pass; the `CardDbTest` instrumented test passes on the device.

## Self-Review notes

- **Spec coverage:** models + query builder + tests (T1); CardDb SQLite access + FTS4 ranking + on-device instrumented test (T2); ViewModel + UI + recently-viewed + first-run seed (T3). Every DoD item maps to a task.
- **Type consistency:** `CardSummary`/`CardDetail`/`RulingItem`, `buildMatchQuery`, `CardDb.prepare/search/card/DB_NAME`, and the `CardsViewModel`/`CardsScreen` signatures are consistent across tasks; `CardsScreen(viewModel = viewModel())` preserves the `App.kt` call site.
- **Deps discipline:** no new runtime deps (keywords via `org.json`); only androidTest-only `androidx.test.ext:junit` + `androidx.test:runner`.
- **FTS4:** ranking is the SQL `CASE`; `buildMatchQuery` sanitizes user input (prevents FTS-syntax injection); the on-device instrumented test is the definitive FTS4-on-device proof against the real seed.
- **Known risks:** first-run ~48 MB copy (loading state); seed asset must exist for the test/run (regenerate via M2 script if absent); LIMIT is interpolated from a trusted Int (not user input) — the `?` binds are the match/raw terms only.
