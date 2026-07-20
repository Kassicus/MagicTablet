package com.magictablet.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrParserTest {
    private val snippet = """
        6. Spells, Abilities, and Effects
        603. Handling Triggered Abilities
        603.1. Triggered abilities have a trigger condition and an effect.
        603.2. Whenever a game event or game state matches a trigger event, the ability triggers.
        603.2a A triggered ability is controlled by the player who controlled its source.
        Example: A card says to do a thing.
        603.2b Some triggered abilities are written differently.

        Glossary

        Absorb
        A keyword ability that prevents damage.

        Deathtouch
        A keyword ability. See rule 702.2.

        Credits
        Lots of people.
    """.trimIndent()

    private val corpus = parseCr(snippet.lineSequence())

    @Test fun parentsResolveUpTheHierarchy() {
        val byNumber = corpus.rules.associateBy { it.number }
        assertNull(byNumber.getValue("6").parent)
        assertEquals("6", byNumber.getValue("603").parent)
        assertEquals("603", byNumber.getValue("603.1").parent)
        assertEquals("603.2", byNumber.getValue("603.2a").parent)
    }

    @Test fun exampleLineAppendsToRule() {
        val r = corpus.rules.first { it.number == "603.2a" }
        assertTrue(r.text.contains("Example: A card says"))
    }

    @Test fun sortKeyOrdersNumerically() {
        assertTrue(ruleSortKey("603.2") < ruleSortKey("603.10"))
        assertTrue(ruleSortKey("603.2a") < ruleSortKey("603.2b"))
        assertTrue(ruleSortKey("6") < ruleSortKey("603"))
    }

    @Test fun glossaryParsed_creditsExcluded() {
        val g = corpus.glossary.associate { it.term to it.definition }
        assertTrue(g.containsKey("Absorb"))
        assertTrue(g.getValue("Deathtouch").contains("702.2"))
        assertTrue(!g.containsKey("Lots of people."))
        assertTrue(corpus.rules.none { it.text.contains("Lots of people") })
    }
}
