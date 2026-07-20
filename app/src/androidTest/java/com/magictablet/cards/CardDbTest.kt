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
