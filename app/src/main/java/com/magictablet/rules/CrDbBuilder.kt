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
