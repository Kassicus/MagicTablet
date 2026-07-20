package com.magictablet.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSyncLogicTest {
    @Test fun include_normalCard() {
        assertTrue(shouldInclude(RawCard(oracleId = "x", name = "Llanowar Elves", layout = "normal")))
    }

    @Test fun exclude_layouts() {
        for (layout in listOf("token", "double_faced_token", "emblem", "art_series")) {
            assertFalse(layout, shouldInclude(RawCard(oracleId = "x", name = "n", layout = layout)))
        }
    }

    @Test fun exclude_alchemyPrefix() {
        assertFalse(shouldInclude(RawCard(oracleId = "x", name = "A-Faceless Haven", layout = "normal")))
    }

    @Test fun exclude_blankOracleId() {
        assertFalse(shouldInclude(RawCard(oracleId = null, name = "n", layout = "normal")))
        assertFalse(shouldInclude(RawCard(oracleId = "", name = "n", layout = "normal")))
    }

    @Test fun toCardRow_simple() {
        val row = toCardRow(
            RawCard(oracleId = "abc", name = "Lightning Bolt", manaCost = "{R}", typeLine = "Instant",
                oracleText = "Lightning Bolt deals 3 damage to any target.", keywords = emptyList())
        )
        assertEquals("abc", row.oracleId)
        assertEquals("{R}", row.manaCost)
        assertEquals("Instant", row.typeLine)
        assertTrue(row.oracleText.contains("3 damage"))
        assertEquals(emptyList<String>(), row.keywords)
    }

    @Test fun toCardRow_dfcJoinsFaces() {
        val row = toCardRow(
            RawCard(
                oracleId = "dfc", name = "Front // Back", manaCost = "", typeLine = "", oracleText = null,
                faces = listOf(
                    RawFace(manaCost = "{G}", typeLine = "Creature - Elf", oracleText = "Front text."),
                    RawFace(manaCost = "", typeLine = "Land", oracleText = "Back text."),
                ),
            )
        )
        assertEquals("{G}", row.manaCost)
        assertEquals("Creature - Elf // Land", row.typeLine)
        assertEquals("Front text.\n//\nBack text.", row.oracleText)
    }

    @Test fun toCardRow_missingOracleText_empty() {
        assertEquals("", toCardRow(RawCard(oracleId = "x", name = "Weird")).oracleText)
    }

    @Test fun toRulingRow_maps() {
        assertEquals(
            RulingRow("abc", "2020-01-01", "It works."),
            toRulingRow(RawRuling("abc", "2020-01-01", "It works.")),
        )
    }

    @Test fun toRulingRow_nullOnBlankId() {
        assertNull(toRulingRow(RawRuling(oracleId = null, publishedAt = "x", comment = "y")))
    }
}
