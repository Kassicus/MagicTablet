package com.magictablet.rules

data class RuleRow(
    val number: String,
    val sortKey: String,
    val parent: String?,
    val text: String,
)

data class GlossaryRow(val term: String, val definition: String)

data class CrCorpus(val rules: List<RuleRow>, val glossary: List<GlossaryRow>)

private val RULE_LINE = Regex("^(\\d+(?:\\.\\d+)*[a-z]?)\\.?\\s+(.+)$")

/** 603.2a -> 603.2 -> 603 -> 6 -> null (root). */
fun ruleParent(number: String): String? = when {
    number.last().isLetter() -> number.dropLast(1)
    number.contains('.') -> number.substringBeforeLast('.')
    number.length == 3 -> number.take(1)
    else -> null
}

/** Zero-pad numeric segments so 603.9 < 603.10 and 603.2a < 603.2b. */
fun ruleSortKey(number: String): String {
    val letter = if (number.last().isLetter()) number.last().toString() else ""
    val numeric = if (letter.isEmpty()) number else number.dropLast(1)
    val padded = numeric.split('.').joinToString(".") { it.padStart(3, '0') }
    return if (letter.isEmpty()) padded else "$padded.$letter"
}

fun parseCr(lines: Sequence<String>): CrCorpus {
    val rules = LinkedHashMap<String, RuleRow>() // dedup by number, last wins
    val glossary = ArrayList<GlossaryRow>()
    var mode = 0 // 0 rules, 1 glossary, 2 done
    var current: String? = null
    val block = ArrayList<String>()

    fun flushGlossary() {
        if (block.isEmpty()) return
        val term = block.first().trim()
        val def = block.drop(1).joinToString("\n").trim()
        if (term.isNotEmpty() && def.isNotEmpty()) glossary.add(GlossaryRow(term, def))
        block.clear()
    }

    for (raw in lines) {
        val line = raw.trimEnd()
        if (mode == 2) break
        if (mode == 0) {
            when (line.trim()) {
                "Glossary" -> { mode = 1; current = null; continue }
                "Credits" -> { mode = 2; continue }
            }
            val m = RULE_LINE.matchEntire(line)
            if (m != null) {
                val number = m.groupValues[1]
                rules[number] = RuleRow(number, ruleSortKey(number), ruleParent(number), m.groupValues[2].trim())
                current = number
            } else if (line.isNotBlank() && current != null) {
                val e = rules.getValue(current!!)
                rules[current!!] = e.copy(text = e.text + "\n" + line.trim())
            }
        } else { // glossary
            if (line.trim() == "Credits") { flushGlossary(); mode = 2; continue }
            if (line.isBlank()) flushGlossary() else block.add(line)
        }
    }
    flushGlossary()
    return CrCorpus(rules.values.toList(), glossary)
}
