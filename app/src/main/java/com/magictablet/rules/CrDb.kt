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
