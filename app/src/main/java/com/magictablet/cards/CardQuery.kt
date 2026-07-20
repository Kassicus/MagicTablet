package com.magictablet.cards

private val NON_ALNUM = Regex("[^a-z0-9]+")

/**
 * Build a safe FTS4 MATCH query from raw user text: lowercase, split on non-alphanumeric runs,
 * drop empties, and append a prefix '*' to each token (as-you-type). Blank input -> "".
 * Sanitizing to alphanumeric prefix terms keeps FTS special characters from breaking MATCH.
 */
fun buildMatchQuery(userText: String): String =
    userText.lowercase()
        .split(NON_ALNUM)
        .filter { it.isNotEmpty() }
        .joinToString(" ") { "$it*" }
