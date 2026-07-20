package com.magictablet.cards

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardDbRecoveryTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getFileStreamPath(CardDb.DB_NAME).delete()
    }

    @Test fun missingDb_prepareSeedsFromAsset() {
        val db = CardDb(ctx)
        db.prepare()
        assertTrue(db.search("lightning bolt").any { it.name == "Lightning Bolt" })
    }

    @Test fun corruptDb_reopenRecoversFromAsset() {
        CardDb(ctx).apply { prepare(); close() }          // seed once
        ctx.getFileStreamPath(CardDb.DB_NAME).writeText("garbage, not a sqlite database")
        val db = CardDb(ctx)
        db.reopen()                                       // validity check must re-seed
        assertTrue(db.search("lightning bolt").any { it.name == "Lightning Bolt" })
    }
}
