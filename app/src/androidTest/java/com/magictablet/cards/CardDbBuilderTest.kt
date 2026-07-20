package com.magictablet.cards

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CardDbBuilderTest {
    @Test fun buildsQueryableFts4Db_onDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(ctx.cacheDir, "build_test.db").path

        val cards = listOf(
            RawCard(oracleId = "1", name = "Test Bolt", layout = "normal", manaCost = "{R}",
                typeLine = "Instant", oracleText = "deals 3 damage.", keywords = emptyList()),
            RawCard(oracleId = "2", name = "A Soldier", layout = "token"), // excluded layout
        )
        val rulings = listOf(
            RawRuling("1", "2020-01-01", "It works."),
            RawRuling("2", "2020-01-01", "orphan — its card was excluded"),
        )

        val counts = CardDbBuilder.build(
            path,
            forEachCard = { onCard -> cards.forEach(onCard) },
            forEachRuling = { onRuling -> rulings.forEach(onRuling) },
        )
        assertEquals(1, counts.cards)     // token excluded
        assertEquals(1, counts.rulings)   // orphan (oracleId "2" not kept) dropped

        val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT name FROM CardFts WHERE CardFts MATCH ?", arrayOf("bolt*")).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Test Bolt", c.getString(0))
            }
        } finally {
            db.close()
            File(path).delete()
        }
    }
}
