package com.magictablet.cards

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.File

/**
 * Read-only access to the bundled/synced card database. Seeds [DB_NAME] into filesDir from assets on
 * first use (atomically), validates it on open (re-seeding from the asset if missing/invalid), and can
 * [reopen] after a sync swaps the file. All methods block on I/O — call from Dispatchers.IO.
 */
class CardDb(private val appContext: Context) {
    private var db: SQLiteDatabase? = null

    fun prepare() {
        if (db != null) return
        ensureSeeded()
        openInternal()
    }

    /** Close and reopen the DB file (used after a sync atomically replaces it). */
    fun reopen() {
        close()
        ensureSeeded()
        openInternal()
    }

    fun close() {
        db?.close()
        db = null
    }

    private fun dbFile() = File(appContext.filesDir, DB_NAME)

    private fun ensureSeeded() {
        if (!dbFile().exists()) seedFromAsset()
    }

    private fun seedFromAsset() {
        val tmp = File(appContext.filesDir, "$DB_NAME.tmp")
        appContext.assets.open(DB_NAME).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        val target = dbFile()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun openInternal() {
        val target = dbFile()
        val opened = try {
            val conn = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
            if (isValid(conn)) conn else { conn.close(); null }
        } catch (e: Exception) {
            null
        }
        if (opened != null) {
            db = opened
            return
        }
        // Recovery: internal DB missing or invalid (e.g. a truncated seed copy) -> re-seed + open.
        target.delete()
        seedFromAsset()
        db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun isValid(conn: SQLiteDatabase): Boolean = try {
        conn.rawQuery("SELECT count(*) FROM Card", null).use { c ->
            c.moveToFirst() && c.getLong(0) > 0
        }
    } catch (e: Exception) {
        false
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
