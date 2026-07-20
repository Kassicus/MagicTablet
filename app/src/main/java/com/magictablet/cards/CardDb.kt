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
