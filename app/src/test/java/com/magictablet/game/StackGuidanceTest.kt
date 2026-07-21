package com.magictablet.game

import org.junit.Assert.assertTrue
import org.junit.Test

class StackGuidanceTest {
    private fun item(kind: StackKind, label: String = "x") = StackItem(1, 1, null, kind, label)
    private fun rules(hints: List<RuleHint>) = hints.map { it.ruleNumber }.toSet()

    @Test fun spell_maps_601_608() {
        assertTrue(rules(stackHints(item(StackKind.Spell), "Deal 3 damage.", emptyList())).containsAll(setOf("601", "608")))
    }

    @Test fun triggered_maps_603() {
        assertTrue("603" in rules(stackHints(item(StackKind.Triggered), null, emptyList())))
    }

    @Test fun whenever_flags_triggered() {
        assertTrue("603" in rules(stackHints(item(StackKind.Spell), "Whenever a creature dies, draw a card.", emptyList())))
    }

    @Test fun colon_flags_activated() {
        assertTrue("602" in rules(stackHints(item(StackKind.Spell), "{T}: Add {G}.", emptyList())))
    }

    @Test fun target_flags_115() {
        assertTrue("115" in rules(stackHints(item(StackKind.Spell), "Destroy target creature.", emptyList())))
    }

    @Test fun keywords_flag_702() {
        assertTrue("702" in rules(stackHints(item(StackKind.Spell), "A creature.", listOf("Flying"))))
    }

    @Test fun hints_dedupByRuleNumber() {
        val hints = stackHints(item(StackKind.Spell), "Deal damage.", emptyList())
        assertTrue(hints.size == hints.map { it.ruleNumber }.distinct().size)
    }

    @Test fun procedure_nonEmpty() {
        assertTrue(RESOLUTION_PROCEDURE.isNotBlank())
    }
}
