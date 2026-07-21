package com.magictablet.game

data class RuleHint(val label: String, val ruleNumber: String)

const val RESOLUTION_PROCEDURE =
    "Resolve one object at a time, from the top of the stack down. After each resolution the active " +
        "player gets priority. Abilities that trigger during resolution go on top of the stack (in turn " +
        "order) before anyone gets priority. A spell or ability whose targets have all become illegal " +
        "doesn't resolve — it and its effects are removed. (CR 608, 603)"

private val TRIGGER_WORDS = Regex("(?i)\\b(when|whenever|at)\\b")
private val TARGET_WORD = Regex("(?i)\\btarget")

fun stackHints(item: StackItem, oracleText: String?, keywords: List<String>): List<RuleHint> {
    val text = oracleText ?: item.label
    val hints = ArrayList<RuleHint>()
    when (item.kind) {
        StackKind.Spell -> {
            hints.add(RuleHint("Casting spells", "601"))
            hints.add(RuleHint("Resolving a spell", "608"))
        }
        StackKind.Activated -> hints.add(RuleHint("Activated abilities", "602"))
        StackKind.Triggered -> hints.add(RuleHint("Triggered abilities", "603"))
    }
    if (item.kind != StackKind.Triggered && TRIGGER_WORDS.containsMatchIn(text)) {
        hints.add(RuleHint("‘When/Whenever/At’ = a triggered ability", "603"))
    }
    if (item.kind != StackKind.Activated && text.contains(':')) {
        hints.add(RuleHint("‘[cost]: [effect]’ = an activated ability", "602"))
    }
    if (TARGET_WORD.containsMatchIn(text)) {
        hints.add(RuleHint("Has a target — if all targets become illegal, it doesn't resolve", "115"))
    }
    if (keywords.isNotEmpty()) {
        hints.add(RuleHint("Keyword ability: ${keywords.joinToString(", ")}", "702"))
    }
    hints.add(RuleHint("Resolving spells & abilities", "608"))
    return hints.distinctBy { it.ruleNumber }
}
