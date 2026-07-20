package com.magictablet.cards

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.File

data class BuildCounts(val cards: Int, val rulings: Int)

/**
 * Builds a fresh, trimmed FTS4 card DB at [dbPath] (same schema as the M2 pipeline). The forEach*
 * callbacks drive streaming iteration so the source can be a network JsonReader or an in-memory list.
 */
object CardDbBuilder {
    fun build(
        dbPath: String,
        forEachCard: (onCard: (RawCard) -> Unit) -> Unit,
        forEachRuling: (onRuling: (RawRuling) -> Unit) -> Unit,
    ): BuildCounts {
        File(dbPath).delete()
        val db = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        try {
            // PRAGMA journal_mode returns a result row, so it must go through rawQuery — Android's
            // execSQL rejects any statement that returns data. synchronous returns nothing (execSQL ok).
            db.rawQuery("PRAGMA journal_mode = OFF", null).close()
            db.execSQL("PRAGMA synchronous = OFF")
            createSchema(db)

            val kept = HashSet<String>()
            var cardCount = 0
            val cardStmt = db.compileStatement("INSERT OR IGNORE INTO Card VALUES (?,?,?,?,?,?)")
            val ftsStmt = db.compileStatement("INSERT INTO CardFts (name, oracleText, oracleId) VALUES (?,?,?)")
            db.beginTransaction()
            try {
                forEachCard { raw ->
                    if (!shouldInclude(raw)) return@forEachCard
                    val row = toCardRow(raw)
                    kept.add(row.oracleId)
                    cardStmt.clearBindings()
                    cardStmt.bindString(1, row.oracleId)
                    cardStmt.bindString(2, row.name)
                    cardStmt.bindString(3, row.manaCost)
                    cardStmt.bindString(4, row.typeLine)
                    cardStmt.bindString(5, row.oracleText)
                    cardStmt.bindString(6, JSONArray(row.keywords).toString())
                    cardStmt.executeInsert()
                    ftsStmt.clearBindings()
                    ftsStmt.bindString(1, row.name)
                    ftsStmt.bindString(2, row.oracleText)
                    ftsStmt.bindString(3, row.oracleId)
                    ftsStmt.executeInsert()
                    cardCount++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                cardStmt.close(); ftsStmt.close()
            }

            var rulingCount = 0
            val rulingStmt = db.compileStatement("INSERT INTO Ruling (oracleId, publishedAt, text) VALUES (?,?,?)")
            db.beginTransaction()
            try {
                forEachRuling { raw ->
                    val row = toRulingRow(raw) ?: return@forEachRuling
                    if (row.oracleId !in kept) return@forEachRuling
                    rulingStmt.clearBindings()
                    rulingStmt.bindString(1, row.oracleId)
                    rulingStmt.bindString(2, row.publishedAt)
                    rulingStmt.bindString(3, row.text)
                    rulingStmt.executeInsert()
                    rulingCount++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                rulingStmt.close()
            }

            return BuildCounts(cardCount, rulingCount)
        } finally {
            db.close()
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE Card (oracleId TEXT PRIMARY KEY, name TEXT NOT NULL, manaCost TEXT, typeLine TEXT, oracleText TEXT, keywords TEXT)")
        db.execSQL("CREATE TABLE Ruling (id INTEGER PRIMARY KEY AUTOINCREMENT, oracleId TEXT NOT NULL, publishedAt TEXT, text TEXT)")
        db.execSQL("CREATE INDEX idx_ruling_oracleId ON Ruling(oracleId)")
        db.execSQL(
            "CREATE VIRTUAL TABLE CardFts USING fts4(name, oracleText, oracleId, notindexed=oracleId, tokenize=unicode61 \"remove_diacritics=2\")"
        )
    }
}
