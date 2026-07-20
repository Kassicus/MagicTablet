# M4a — Comprehensive Rules Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An offline Comprehensive Rules reader the app fetches over WiFi from a user-editable URL, parses on-device, and browses/deep-links by rule number — the reference M4b's stack guidance will link into.

**Architecture:** `CrParser` (pure) → `CrDbBuilder` (on-device SQLite build) → `CrDb` (read helper), driven by `CrSync` (download + parse + validate + atomic swap, editable URL from `SharedPreferences`). `CrReader` modal + `CrViewModel`, opened from the Game ⚙ hub. Fetch-on-first-use, no bundled seed. Reuses Card Sync's patterns.

**Tech Stack:** Kotlin, Compose, `java.net.HttpURLConnection`, `android.database.sqlite`, `SharedPreferences` — no new deps. androidTest deps already present. `INTERNET` already in the manifest (Card Sync).

## Global Constraints

From the spec (`docs/superpowers/specs/2026-07-20-m4a-comp-rules-reader-design.md`):

- New package `com.magictablet.rules`. **No new deps.** Only the ⚙-hub entry changes `GameScreen`; no other Game/Cards/Stack change.
- Fetch-on-first-use: **no bundled seed**. `filesDir/cr.db` exists only after a successful fetch; `CrDb.hasRules()` is false until then.
- Editable CR URL in `SharedPreferences("magictablet")` key `cr_url`, default `https://media.wizards.com/2026/downloads/MagicCompRules%2020260227.txt` (user-editable in the reader).
- Parser rules (verbatim, §4 of the spec): rule-line regex `^(\d+(?:\.\d+)*[a-z]?)\.?\s+(.+)$`; non-rule non-blank line appends to the current rule (examples); dedup by number; `Glossary`/`Credits` mode switches; `parent`/`sortKey` as specified.
- Same on-device SQLite build approach as `CardDbBuilder`, INCLUDING the Android PRAGMA lesson: `PRAGMA journal_mode = OFF` must be run via `rawQuery(...).use { it.moveToFirst() }` (execSQL rejects result-returning PRAGMAs; the cursor must be stepped).
- Swap safety: build to `cr.db.new`, validate (`SELECT count(*) FROM Rule > 0`), atomic rename, then `CrDb.reopen()`. Live DB untouched on any failure.
- **Environment preamble:**

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
  ```

- **Sandbox:** Gradle + adb + the real CR fetch need `dangerouslyDisableSandbox: true`. `connectedDebugAndroidTest` uses `-Pandroid.testInstrumentationRunnerArguments.class=…`. The real CR fetch is a few MB (fast). Device `TK12110626B081745`.

> **Testing:** Task 1 offline unit tests (TDD). Task 2 on-device instrumented test. Task 3 compiles (no automated test — network). Task 4 UI + one manual real CR update.

---

### Task 1: CrParser + models (pure, TDD)

**Files:**
- Create: `app/src/main/java/com/magictablet/rules/CrParser.kt`
- Test: `app/src/test/java/com/magictablet/rules/CrParserTest.kt`

**Interfaces:**
- Produces `RuleRow`, `GlossaryRow`, `CrCorpus`; `ruleParent(number)`, `ruleSortKey(number)`, `parseCr(lines): CrCorpus`.

- [ ] **Step 1: Write the failing test** — `app/src/test/java/com/magictablet/rules/CrParserTest.kt`

```kotlin
package com.magictablet.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrParserTest {
    private val snippet = """
        6. Spells, Abilities, and Effects
        603. Handling Triggered Abilities
        603.1. Triggered abilities have a trigger condition and an effect.
        603.2. Whenever a game event or game state matches a trigger event, the ability triggers.
        603.2a A triggered ability is controlled by the player who controlled its source.
        Example: A card says to do a thing.
        603.2b Some triggered abilities are written differently.

        Glossary

        Absorb
        A keyword ability that prevents damage.

        Deathtouch
        A keyword ability. See rule 702.2.

        Credits
        Lots of people.
    """.trimIndent()

    private val corpus = parseCr(snippet.lineSequence())

    @Test fun parentsResolveUpTheHierarchy() {
        val byNumber = corpus.rules.associateBy { it.number }
        assertNull(byNumber.getValue("6").parent)
        assertEquals("6", byNumber.getValue("603").parent)
        assertEquals("603", byNumber.getValue("603.1").parent)
        assertEquals("603.2", byNumber.getValue("603.2a").parent)
    }

    @Test fun exampleLineAppendsToRule() {
        val r = corpus.rules.first { it.number == "603.2a" }
        assertTrue(r.text.contains("Example: A card says"))
    }

    @Test fun sortKeyOrdersNumerically() {
        assertTrue(ruleSortKey("603.2") < ruleSortKey("603.10"))
        assertTrue(ruleSortKey("603.2a") < ruleSortKey("603.2b"))
        assertTrue(ruleSortKey("6") < ruleSortKey("603"))
    }

    @Test fun glossaryParsed_creditsExcluded() {
        val g = corpus.glossary.associate { it.term to it.definition }
        assertTrue(g.containsKey("Absorb"))
        assertTrue(g.getValue("Deathtouch").contains("702.2"))
        assertTrue(!g.containsKey("Lots of people."))
        assertTrue(corpus.rules.none { it.text.contains("Lots of people") })
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.rules.CrParserTest"
```
Expected: FAIL — unresolved `parseCr`/`RuleRow`/etc.

- [ ] **Step 3: Create `app/src/main/java/com/magictablet/rules/CrParser.kt`**

```kotlin
package com.magictablet.rules

data class RuleRow(
    val number: String,
    val sortKey: String,
    val parent: String?,
    val text: String,
)

data class GlossaryRow(val term: String, val definition: String)

data class CrCorpus(val rules: List<RuleRow>, val glossary: List<GlossaryRow>)

private val RULE_LINE = Regex("^(\\d+(?:\\.\\d+)*[a-z]?)\\.?\\s+(.+)$")

/** 603.2a -> 603.2 -> 603 -> 6 -> null (root). */
fun ruleParent(number: String): String? = when {
    number.last().isLetter() -> number.dropLast(1)
    number.contains('.') -> number.substringBeforeLast('.')
    number.length == 3 -> number.take(1)
    else -> null
}

/** Zero-pad numeric segments so 603.9 < 603.10 and 603.2a < 603.2b. */
fun ruleSortKey(number: String): String {
    val letter = if (number.last().isLetter()) number.last().toString() else ""
    val numeric = if (letter.isEmpty()) number else number.dropLast(1)
    val padded = numeric.split('.').joinToString(".") { it.padStart(3, '0') }
    return if (letter.isEmpty()) padded else "$padded.$letter"
}

fun parseCr(lines: Sequence<String>): CrCorpus {
    val rules = LinkedHashMap<String, RuleRow>() // dedup by number, last wins
    val glossary = ArrayList<GlossaryRow>()
    var mode = 0 // 0 rules, 1 glossary, 2 done
    var current: String? = null
    val block = ArrayList<String>()

    fun flushGlossary() {
        if (block.isEmpty()) return
        val term = block.first().trim()
        val def = block.drop(1).joinToString("\n").trim()
        if (term.isNotEmpty() && def.isNotEmpty()) glossary.add(GlossaryRow(term, def))
        block.clear()
    }

    for (raw in lines) {
        val line = raw.trimEnd()
        if (mode == 2) break
        if (mode == 0) {
            when (line.trim()) {
                "Glossary" -> { mode = 1; current = null; continue }
                "Credits" -> { mode = 2; continue }
            }
            val m = RULE_LINE.matchEntire(line)
            if (m != null) {
                val number = m.groupValues[1]
                rules[number] = RuleRow(number, ruleSortKey(number), ruleParent(number), m.groupValues[2].trim())
                current = number
            } else if (line.isNotBlank() && current != null) {
                val e = rules.getValue(current!!)
                rules[current!!] = e.copy(text = e.text + "\n" + line.trim())
            }
        } else { // glossary
            if (line.trim() == "Credits") { flushGlossary(); mode = 2; continue }
            if (line.isBlank()) flushGlossary() else block.add(line)
        }
    }
    flushGlossary()
    return CrCorpus(rules.values.toList(), glossary)
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.rules.CrParserTest"
```
Expected: all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "M4a Task 1: Comprehensive Rules parser + models (TDD)"
```

---

### Task 2: CrDbBuilder + CrDb + on-device build/read test

**Files:**
- Create: `app/src/main/java/com/magictablet/rules/CrDbBuilder.kt`
- Create: `app/src/main/java/com/magictablet/rules/CrDb.kt`
- Test: `app/src/androidTest/java/com/magictablet/rules/CrDbBuilderTest.kt`

**Interfaces:**
- Produces `CrDbBuilder.build(dbPath, rules, glossary): Pair<Int,Int>`; `CrDb(context)` with `prepare/reopen/close/hasRules/roots/children/rule/glossary`, `CrRule(number, parent, text)`, `CrDb.DB_NAME`.

- [ ] **Step 1: Create `app/src/main/java/com/magictablet/rules/CrDbBuilder.kt`**

```kotlin
package com.magictablet.rules

import android.database.sqlite.SQLiteDatabase
import java.io.File

object CrDbBuilder {
    /** Build a fresh cr.db at [dbPath]. Returns (rules, terms) inserted. */
    fun build(dbPath: String, rules: List<RuleRow>, glossary: List<GlossaryRow>): Pair<Int, Int> {
        File(dbPath).delete()
        val db = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        try {
            db.rawQuery("PRAGMA journal_mode = OFF", null).use { it.moveToFirst() } // step to apply
            db.execSQL("PRAGMA synchronous = OFF")
            db.execSQL("CREATE TABLE Rule (number TEXT PRIMARY KEY, sortKey TEXT NOT NULL, parent TEXT, text TEXT NOT NULL)")
            db.execSQL("CREATE INDEX idx_rule_parent ON Rule(parent, sortKey)")
            db.execSQL("CREATE TABLE Glossary (term TEXT PRIMARY KEY, definition TEXT NOT NULL)")

            val ruleStmt = db.compileStatement("INSERT OR REPLACE INTO Rule VALUES (?,?,?,?)")
            db.beginTransaction()
            try {
                for (r in rules) {
                    ruleStmt.clearBindings()
                    ruleStmt.bindString(1, r.number)
                    ruleStmt.bindString(2, r.sortKey)
                    if (r.parent == null) ruleStmt.bindNull(3) else ruleStmt.bindString(3, r.parent)
                    ruleStmt.bindString(4, r.text)
                    ruleStmt.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction(); ruleStmt.close() }

            val gStmt = db.compileStatement("INSERT OR REPLACE INTO Glossary VALUES (?,?)")
            db.beginTransaction()
            try {
                for (g in glossary) {
                    gStmt.clearBindings()
                    gStmt.bindString(1, g.term)
                    gStmt.bindString(2, g.definition)
                    gStmt.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction(); gStmt.close() }

            return rules.size to glossary.size
        } finally {
            db.close()
        }
    }
}
```

- [ ] **Step 2: Create `app/src/main/java/com/magictablet/rules/CrDb.kt`**

```kotlin
package com.magictablet.rules

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

data class CrRule(val number: String, val parent: String?, val text: String)

/** Read-only access to the fetched CR db. No asset seed: hasRules() is false until a fetch succeeds. */
class CrDb(private val appContext: Context) {
    private var db: SQLiteDatabase? = null

    fun prepare() { if (db == null) openInternal() }
    fun reopen() { close(); openInternal() }
    fun close() { db?.close(); db = null }
    fun hasRules(): Boolean = db != null

    private fun dbFile() = File(appContext.filesDir, DB_NAME)

    private fun openInternal() {
        val f = dbFile()
        if (!f.exists()) { db = null; return }
        db = try {
            val conn = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
            if (isValid(conn)) conn else { conn.close(); f.delete(); null }
        } catch (e: Exception) {
            runCatching { f.delete() }
            null
        }
    }

    private fun isValid(conn: SQLiteDatabase): Boolean = try {
        conn.rawQuery("SELECT count(*) FROM Rule", null).use { it.moveToFirst() && it.getLong(0) > 0 }
    } catch (e: Exception) { false }

    fun roots(): List<CrRule> = query("SELECT number, parent, text FROM Rule WHERE parent IS NULL ORDER BY sortKey", emptyArray())
    fun children(number: String): List<CrRule> = query("SELECT number, parent, text FROM Rule WHERE parent = ? ORDER BY sortKey", arrayOf(number))
    fun rule(number: String): CrRule? = query("SELECT number, parent, text FROM Rule WHERE number = ?", arrayOf(number)).firstOrNull()

    fun glossary(term: String): String? {
        val conn = db ?: return null
        conn.rawQuery("SELECT definition FROM Glossary WHERE term = ? COLLATE NOCASE", arrayOf(term)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun query(sql: String, args: Array<String>): List<CrRule> {
        val conn = db ?: return emptyList()
        val out = ArrayList<CrRule>()
        conn.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(CrRule(c.getString(0), c.getString(1), c.getString(2)))
        }
        return out
    }

    companion object { const val DB_NAME = "cr.db" }
}
```

- [ ] **Step 3: Create `app/src/androidTest/java/com/magictablet/rules/CrDbBuilderTest.kt`**

```kotlin
package com.magictablet.rules

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrDbBuilderTest {
    @Before fun clean() {
        InstrumentationRegistry.getInstrumentation().targetContext.getFileStreamPath(CrDb.DB_NAME).delete()
    }

    @Test fun buildAndRead_onDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val path = ctx.getFileStreamPath(CrDb.DB_NAME).path
        val rules = listOf(
            RuleRow("6", ruleSortKey("6"), null, "Spells, Abilities, and Effects"),
            RuleRow("603", ruleSortKey("603"), "6", "Handling Triggered Abilities"),
            RuleRow("603.2", ruleSortKey("603.2"), "603", "Whenever a game event matches, it triggers."),
            RuleRow("603.2a", ruleSortKey("603.2a"), "603.2", "A triggered ability is controlled by its source's controller."),
        )
        val glossary = listOf(GlossaryRow("Deathtouch", "A keyword ability."))
        CrDbBuilder.build(path, rules, glossary)

        val db = CrDb(ctx)
        db.prepare()
        assertTrue(db.hasRules())
        assertEquals(listOf("6"), db.roots().map { it.number })
        assertEquals(listOf("603"), db.children("6").map { it.number })
        assertEquals(listOf("603.2"), db.children("603").map { it.number })
        assertEquals("603.2", db.rule("603.2a")!!.parent)
        assertTrue(db.rule("603.2a")!!.text.contains("triggered ability"))
        assertEquals("A keyword ability.", db.glossary("deathtouch")) // COLLATE NOCASE
    }

    @Test fun missingDb_hasNoRules() {
        val db = CrDb(InstrumentationRegistry.getInstrumentation().targetContext)
        db.prepare()
        assertTrue(!db.hasRules())
    }
}
```

- [ ] **Step 4: Run the test on device**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.magictablet.rules.CrDbBuilderTest
```
Expected: both tests PASS — `CrDbBuilder` builds `cr.db` on-device and `CrDb` reads roots/children/rule/glossary; a missing DB reports `hasRules()=false`.

- [ ] **Step 5: Confirm unit suite still passes**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "M4a Task 2: CrDbBuilder + read-only CrDb + on-device build/read test"
```

---

### Task 3: CrSync — fetch + parse + build + validate + atomic swap

**Files:**
- Create: `app/src/main/java/com/magictablet/rules/CrSync.kt`

**Interfaces:**
- Produces `CrSync(context).sync(url, onProgress): CrSyncResult`; `sealed CrSyncProgress`; `sealed CrSyncResult`. Consumes `parseCr`, `CrDbBuilder`, `CrDb.DB_NAME`.

- [ ] **Step 1: Create `app/src/main/java/com/magictablet/rules/CrSync.kt`**

```kotlin
package com.magictablet.rules

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

sealed interface CrSyncProgress {
    data object Connecting : CrSyncProgress
    data class Downloading(val bytes: Long, val total: Long) : CrSyncProgress
    data class Parsing(val rules: Int) : CrSyncProgress
    data object Finalizing : CrSyncProgress
}

sealed interface CrSyncResult {
    data class Success(val rules: Int, val terms: Int) : CrSyncResult
    data class Error(val message: String) : CrSyncResult
}

/** Downloads the CR text from [url], parses it, and rebuilds filesDir/cr.db (atomic swap). Runs on IO. */
class CrSync(private val appContext: Context) {

    fun sync(url: String, onProgress: (CrSyncProgress) -> Unit): CrSyncResult {
        val temp = File(appContext.cacheDir, "comp_rules.txt")
        val newDb = File(appContext.filesDir, "${CrDb.DB_NAME}.new")
        return try {
            onProgress(CrSyncProgress.Connecting)
            if (!hasInternet()) return CrSyncResult.Error("No network connection")

            val gz = download(url, temp) { b, t -> onProgress(CrSyncProgress.Downloading(b, t)) }

            onProgress(CrSyncProgress.Parsing(0))
            val corpus = reader(temp, gz).use { r -> parseCr(r.lineSequence()) }
            onProgress(CrSyncProgress.Parsing(corpus.rules.size))
            if (corpus.rules.isEmpty()) return CrSyncResult.Error("No rules found — check the URL")

            newDb.delete()
            CrDbBuilder.build(newDb.path, corpus.rules, corpus.glossary)
            onProgress(CrSyncProgress.Finalizing)
            if (!validate(newDb)) { newDb.delete(); return CrSyncResult.Error("Rebuilt rules failed validation") }

            val target = File(appContext.filesDir, CrDb.DB_NAME)
            if (!newDb.renameTo(target)) { newDb.copyTo(target, overwrite = true); newDb.delete() }
            CrSyncResult.Success(corpus.rules.size, corpus.glossary.size)
        } catch (e: Exception) {
            newDb.delete()
            CrSyncResult.Error(e.message ?: e.toString())
        } finally {
            temp.delete()
        }
    }

    private fun hasInternet(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun download(urlStr: String, dest: File, onBytes: (Long, Long) -> Unit): Boolean {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "MagicTablet/0.1 (personal fan project)")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        val total = conn.contentLengthLong
        val gz = conn.getHeaderField("Content-Encoding")?.contains("gzip", ignoreCase = true) == true
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(1 shl 16)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    read += n
                    onBytes(read, total)
                }
            }
        }
        return gz
    }

    private fun reader(file: File, gz: Boolean): BufferedReader {
        val ins = FileInputStream(file)
        return try {
            val stream = if (gz) GZIPInputStream(ins) else ins
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        } catch (e: Throwable) {
            ins.close()
            throw e
        }
    }

    private fun validate(dbFile: File): Boolean = try {
        android.database.sqlite.SQLiteDatabase
            .openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            .use { conn ->
                conn.rawQuery("SELECT count(*) FROM Rule", null).use { it.moveToFirst() && it.getLong(0) > 0 }
            }
    } catch (e: Exception) {
        false
    }
}
```

- [ ] **Step 2: Confirm it compiles + unit suite green**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```
Expected: both BUILD SUCCESSFUL. (No automated test — network; the real fetch is verified in Task 4.)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "M4a Task 3: CrSync download + parse + build + validate + atomic swap"
```

---

### Task 4: CrViewModel + CrReader modal + ⚙-hub entry + real CR update

**Files:**
- Create: `app/src/main/java/com/magictablet/rules/CrViewModel.kt`
- Create: `app/src/main/java/com/magictablet/rules/CrReader.kt`
- Modify (replace): `app/src/main/java/com/magictablet/screens/GameScreen.kt`

**Interfaces:**
- `CrViewModel` (`AndroidViewModel`): `url`/`hasRules`/`view`/`syncState` StateFlows; `open`/`openAt`/`drillTo`/`up`/`jumpTo`/`setUrl`/`startUpdate`/`dismissSync`. `CrReader(viewModel, onClose)` modal. `GameScreen` ⚙ hub gains "Comprehensive Rules" → shows the reader.

- [ ] **Step 1: Create `app/src/main/java/com/magictablet/rules/CrViewModel.kt`**

```kotlin
package com.magictablet.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CrSyncUiState {
    data object Idle : CrSyncUiState
    data class Running(val progress: CrSyncProgress) : CrSyncUiState
    data class Done(val rules: Int, val terms: Int) : CrSyncUiState
    data class Error(val message: String) : CrSyncUiState
}

/** current == null means the root (the 1..9 sections in [children]). */
data class CrView(val current: CrRule?, val children: List<CrRule>)

class CrViewModel(app: Application) : AndroidViewModel(app) {
    private val db = CrDb(app)
    private val crSync = CrSync(app)
    private val prefs = app.getSharedPreferences("magictablet", Application.MODE_PRIVATE)

    private val _url = MutableStateFlow(prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL)
    val url: StateFlow<String> = _url.asStateFlow()

    private val _hasRules = MutableStateFlow(false)
    val hasRules: StateFlow<Boolean> = _hasRules.asStateFlow()

    private val _view = MutableStateFlow(CrView(null, emptyList()))
    val view: StateFlow<CrView> = _view.asStateFlow()

    private val _syncState = MutableStateFlow<CrSyncUiState>(CrSyncUiState.Idle)
    val syncState: StateFlow<CrSyncUiState> = _syncState.asStateFlow()

    private val backStack = ArrayDeque<String?>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            db.prepare()
            _hasRules.value = db.hasRules()
            refreshView(null)
        }
    }

    /** Read the DB for [number] (null = root) and publish. Call on IO. */
    private fun refreshView(number: String?) {
        val current = number?.let { db.rule(it) }
        val kids = if (number == null) db.roots() else db.children(number)
        _view.value = CrView(current, kids)
    }

    fun open() = go(null, clearBack = true)
    fun openAt(number: String) = go(number, clearBack = true)
    fun jumpTo(number: String) = go(number.trim(), clearBack = true)

    fun drillTo(number: String) {
        backStack.addLast(_view.value.current?.number)
        go(number, clearBack = false)
    }

    fun up() {
        viewModelScope.launch(Dispatchers.IO) {
            val prev = if (backStack.isNotEmpty()) backStack.removeLast()
            else _view.value.current?.let { db.rule(it.number)?.parent }
            refreshView(prev)
        }
    }

    private fun go(number: String?, clearBack: Boolean) {
        if (clearBack) backStack.clear()
        viewModelScope.launch(Dispatchers.IO) { refreshView(number) }
    }

    fun setUrl(newUrl: String) {
        _url.value = newUrl
        prefs.edit().putString(KEY_URL, newUrl).apply()
    }

    fun startUpdate() {
        if (_syncState.value is CrSyncUiState.Running) return
        _syncState.value = CrSyncUiState.Running(CrSyncProgress.Connecting)
        viewModelScope.launch(Dispatchers.IO) {
            when (val r = crSync.sync(_url.value) { p -> _syncState.value = CrSyncUiState.Running(p) }) {
                is CrSyncResult.Success -> {
                    db.reopen()
                    _hasRules.value = db.hasRules()
                    backStack.clear()
                    refreshView(null)
                    _syncState.value = CrSyncUiState.Done(r.rules, r.terms)
                }
                is CrSyncResult.Error -> _syncState.value = CrSyncUiState.Error(r.message)
            }
        }
    }

    fun dismissSync() { _syncState.value = CrSyncUiState.Idle }

    companion object {
        private const val KEY_URL = "cr_url"
        const val DEFAULT_URL = "https://media.wizards.com/2026/downloads/MagicCompRules%2020260227.txt"
    }
}
```

- [ ] **Step 2: Create `app/src/main/java/com/magictablet/rules/CrReader.kt`**

```kotlin
package com.magictablet.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CrReader(viewModel: CrViewModel, onClose: () -> Unit) {
    val hasRules by viewModel.hasRules.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val url by viewModel.url.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(16.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Comprehensive Rules", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    syncState !is CrSyncUiState.Idle ->
                        UpdatePanel(syncState, url, viewModel::setUrl, viewModel::startUpdate, viewModel::dismissSync)
                    !hasRules ->
                        LoadPrompt(url, viewModel::setUrl, viewModel::startUpdate)
                    else ->
                        BrowsePanel(view, viewModel::drillTo, viewModel::up, viewModel::jumpTo, viewModel::startUpdate)
                }
            }
        }
    }
}

@Composable
private fun LoadPrompt(url: String, onUrl: (String) -> Unit, onUpdate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Comprehensive Rules aren't loaded yet. Connect to WiFi and update.")
        UrlField(url, onUrl)
        Button(onClick = onUpdate) { Text("Update rules") }
    }
}

@Composable
private fun UpdatePanel(state: CrSyncUiState, url: String, onUrl: (String) -> Unit, onUpdate: () -> Unit, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is CrSyncUiState.Running -> when (val p = state.progress) {
                is CrSyncProgress.Connecting -> Text("Connecting…")
                is CrSyncProgress.Downloading -> {
                    Text("Downloading rules…")
                    if (p.total > 0) LinearProgressIndicator(progress = { (p.bytes.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is CrSyncProgress.Parsing -> { Text("Parsing… ${p.rules} rules"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                is CrSyncProgress.Finalizing -> Text("Finalizing…")
            }
            is CrSyncUiState.Done -> {
                Text("Loaded ${state.rules} rules, ${state.terms} glossary terms.")
                TextButton(onClick = onDone) { Text("Done") }
            }
            is CrSyncUiState.Error -> {
                Text("Update failed: ${state.message}")
                UrlField(url, onUrl)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onUpdate) { Text("Retry") }
                    TextButton(onClick = onDone) { Text("Close") }
                }
            }
            is CrSyncUiState.Idle -> {}
        }
    }
}

@Composable
private fun BrowsePanel(view: CrView, onDrill: (String) -> Unit, onUp: () -> Unit, onJump: (String) -> Unit, onUpdate: () -> Unit) {
    var jump by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onUp) { Text("‹ Up") }
            OutlinedTextField(value = jump, onValueChange = { jump = it }, placeholder = { Text("Jump to rule #") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (jump.isNotBlank()) onJump(jump) }) { Text("Go") }
            TextButton(onClick = onUpdate) { Text("Update") }
        }
        Spacer(Modifier.height(8.dp))
        val current = view.current
        if (current != null) {
            Text(current.number, style = MaterialTheme.typography.titleMedium)
            Text(current.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp).verticalScroll(rememberScrollState()).heightIn())
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(view.children, key = { it.number }) { r ->
                Column(Modifier.fillMaxWidth().clickable { onDrill(r.number) }.padding(vertical = 8.dp)) {
                    Text(r.number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(r.text, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
            }
        }
    }
}

@Composable
private fun UrlField(url: String, onUrl: (String) -> Unit) {
    OutlinedTextField(value = url, onValueChange = onUrl, label = { Text("Comprehensive Rules .txt URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
```

Note: remove the stray `.heightIn()` on the current-text `Text` if it fails to compile (it needs no args or a max) — replace that modifier chain with just `Modifier.padding(vertical = 4.dp)` if needed.

- [ ] **Step 3: Replace `app/src/main/java/com/magictablet/screens/GameScreen.kt`** — add the "Comprehensive Rules" ⚙ item + host the reader. Full file:

```kotlin
package com.magictablet.screens

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameViewModel
import com.magictablet.game.ui.DiceOverlay
import com.magictablet.game.ui.NewGameSheet
import com.magictablet.game.ui.SeatLayout
import com.magictablet.game.ui.TimerChip
import com.magictablet.rules.CrReader
import com.magictablet.rules.CrViewModel

@Composable
fun GameScreen(
    viewModel: GameViewModel = viewModel(),
    crViewModel: CrViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentDeltas by viewModel.recentDeltas.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showNewGame by remember { mutableStateOf(false) }
    var showDice by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }

    LaunchedEffectTick(timer.running, viewModel)

    Box(Modifier.fillMaxSize()) {
        SeatLayout(
            players = state.players,
            recentDeltas = recentDeltas,
            activePlayerId = state.activePlayerId,
            monarchPlayerId = state.monarchPlayerId,
            onAdjustLife = viewModel::adjustLife,
            onClearDelta = viewModel::clearRecentDelta,
            onAdjustPoison = viewModel::adjustPoison,
            onAdjustCommanderDamage = viewModel::adjustCommanderDamage,
            onAdjustCounter = viewModel::adjustCounter,
            onToggleMonarch = viewModel::toggleMonarch,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (timer.running || timer.elapsedSeconds > 0) {
                TimerChip(elapsedSeconds = timer.elapsedSeconds, running = timer.running)
            }
            Box {
                FilledTonalButton(onClick = { menuOpen = true }) { Text("⚙") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Advance turn") }, onClick = { menuOpen = false; viewModel.advanceTurn() })
                    DropdownMenuItem(text = { Text("Random first player") }, onClick = { menuOpen = false; viewModel.randomFirstPlayer() })
                    DropdownMenuItem(text = { Text("Dice & coin") }, onClick = { menuOpen = false; showDice = true })
                    DropdownMenuItem(
                        text = { Text(if (timer.running) "Pause timer" else "Start timer") },
                        onClick = { menuOpen = false; if (timer.running) viewModel.pauseTimer() else viewModel.startTimer() },
                    )
                    DropdownMenuItem(text = { Text("Reset timer") }, onClick = { menuOpen = false; viewModel.resetTimer() })
                    DropdownMenuItem(text = { Text("Comprehensive Rules") }, onClick = { menuOpen = false; showRules = true })
                    DropdownMenuItem(text = { Text("New game") }, onClick = { menuOpen = false; showNewGame = true })
                    DropdownMenuItem(text = { Text("Relinquish device owner") }, onClick = { menuOpen = false; relinquishDeviceOwner(context) })
                }
            }
        }
    }

    if (showNewGame) {
        NewGameSheet(
            currentCount = state.players.size,
            currentLife = state.startingLife,
            onStart = { count, life -> viewModel.newGame(count, life); showNewGame = false },
            onDismiss = { showNewGame = false },
        )
    }
    if (showDice) {
        DiceOverlay(onDismiss = { showDice = false })
    }
    if (showRules) {
        CrReader(viewModel = crViewModel, onClose = { showRules = false })
    }
}

@Composable
private fun LaunchedEffectTick(running: Boolean, viewModel: GameViewModel) {
    androidx.compose.runtime.LaunchedEffect(running) {
        while (running) {
            kotlinx.coroutines.delay(1000)
            viewModel.tickTimer()
        }
    }
}

private fun relinquishDeviceOwner(context: Context) {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val pkg = context.packageName
    val message = if (dpm.isDeviceOwnerApp(pkg)) {
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(pkg)
        "Device owner relinquished"
    } else {
        "Not device owner"
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
```

(Note: the timer tick was inlined into `LaunchedEffectTick` to keep the imports tidy; behavior is identical to the M1b inline `LaunchedEffect(timer.running)`. If preferred, keep the original inline `LaunchedEffect` — either is fine as long as it ticks once/second while running.)

- [ ] **Step 4: Build, install, and run a real CR update on device**

Run:
```bash
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
"$ADB" shell am start -n com.magictablet/.MainActivity
```
On the tablet (WiFi): Game tab → ⚙ → **Comprehensive Rules** → the reader shows "not loaded" with the URL field → tap **Update rules** → Connecting → Downloading → Parsing N → "Loaded N rules, M terms" → Done. Then browse: the root shows sections 1–9; drill into **6** → **603** → a rule; use **Jump to rule #** `603.2` and read the text; **‹ Up** navigates back. Drive via uiautomator/input + screenshots; **report the loaded rule/term counts**. (If no WiFi, verify the error path — "No network connection" — and note it.)

- [ ] **Step 5: Confirm unit + instrumented suites still pass**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```
Expected: BUILD SUCCESSFUL; all instrumented tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "M4a Task 4: CrViewModel + CrReader modal + gear-hub entry (editable URL, real CR fetch)"
```

---

## Definition of Done (M4a)

- [ ] Game ⚙ hub → "Comprehensive Rules" opens the reader modal.
- [ ] The CR URL is editable in-app (pref-backed default); "Update rules" fetches, parses on-device, builds `cr.db`, and the reader shows the rules; a fresh state shows the "Load rules" prompt.
- [ ] Browse (section → rule → subrule), jump-to-number, and glossary lookup work; `openAt(number)` focuses a rule (M4b deep-link API).
- [ ] Network used only during a user-triggered update.
- [ ] `CrParserTest` passes; the `CrDbBuilder`/`CrDb` instrumented test passes; one real CR update verified manually with counts.

## Self-Review notes

- **Spec coverage:** parser + models + tests (T1); builder + read helper + on-device test (T2); fetch/parse/build/swap engine (T3); VM + reader modal + ⚙ entry + real update (T4). Every DoD item maps to a task.
- **Type consistency:** `RuleRow`/`GlossaryRow`/`CrCorpus`, `ruleParent`/`ruleSortKey`/`parseCr`, `CrDbBuilder.build`, `CrDb`(+`CrRule`/`DB_NAME`), `CrSync`(`CrSyncProgress`/`CrSyncResult`), `CrViewModel`(`CrSyncUiState`/`CrView`), and `CrReader` are consistent across tasks; `GameScreen` re-shown in full.
- **Deps discipline:** no new deps (SharedPreferences/HttpURLConnection/SQLite built-in). Reuses Card Sync patterns + the PRAGMA-step lesson.
- **Fetch-on-first-use:** no asset seed; `CrDb.hasRules()` false until a fetch; the reader prompts to load.
- **Known risks:** parser heuristics vs the real CR (unit-tested + manual real-fetch validates; tune the regex/mode logic if the manual run mis-parses); the default URL may go stale (user-editable); `CrReader` current-text `Modifier` chain (`heightIn()`) may need trimming to compile — noted at the file.
