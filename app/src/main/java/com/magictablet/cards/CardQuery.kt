package com.magictablet.cards

import java.text.Normalizer

private val NON_ALNUM = Regex("[^a-z0-9]+")
private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** NFD-decompose then strip combining marks so accented input matches the index's remove_diacritics folding. */
private fun foldDiacritics(text: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")

/**
 * Build a safe FTS4 MATCH query from raw user text: fold diacritics, lowercase, split on non-alphanumeric
 * runs, drop empties, and append a prefix '*' to each token (as-you-type). Blank input -> "".
 */
fun buildMatchQuery(userText: String): String =
    foldDiacritics(userText).lowercase()
        .split(NON_ALNUM)
        .filter { it.isNotEmpty() }
        .joinToString(" ") { "$it*" }
