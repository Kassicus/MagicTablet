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
