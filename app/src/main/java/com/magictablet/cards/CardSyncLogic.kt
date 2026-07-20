package com.magictablet.cards

val EXCLUDED_LAYOUTS = setOf("token", "double_faced_token", "emblem", "art_series")

data class RawFace(
    val manaCost: String? = null,
    val typeLine: String? = null,
    val oracleText: String? = null,
)

data class RawCard(
    val oracleId: String?,
    val name: String,
    val layout: String? = null,
    val manaCost: String? = null,
    val typeLine: String? = null,
    val oracleText: String? = null,
    val keywords: List<String> = emptyList(),
    val faces: List<RawFace> = emptyList(),
)

data class RawRuling(
    val oracleId: String?,
    val publishedAt: String? = null,
    val comment: String? = null,
)

/** A row ready to insert into Card. keywords stays a List; JSON-encode at insert time. */
data class CardRow(
    val oracleId: String,
    val name: String,
    val manaCost: String,
    val typeLine: String,
    val oracleText: String,
    val keywords: List<String>,
)

data class RulingRow(
    val oracleId: String,
    val publishedAt: String,
    val text: String,
)

fun shouldInclude(c: RawCard): Boolean {
    if (c.oracleId.isNullOrBlank()) return false
    if (c.layout in EXCLUDED_LAYOUTS) return false
    if (c.name.startsWith("A-")) return false
    return true
}

private fun joinFaces(faces: List<RawFace>, select: (RawFace) -> String?, sep: String): String =
    faces.mapNotNull { select(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }.joinToString(sep)

/** Precondition: shouldInclude(c) is true (so oracleId is non-null). */
fun toCardRow(c: RawCard): CardRow {
    var manaCost = c.manaCost ?: ""
    if (manaCost.isEmpty() && c.faces.isNotEmpty()) manaCost = joinFaces(c.faces, { it.manaCost }, " // ")

    var typeLine = c.typeLine ?: ""
    if (typeLine.isEmpty() && c.faces.isNotEmpty()) typeLine = joinFaces(c.faces, { it.typeLine }, " // ")

    val oracleText = when {
        c.oracleText != null -> c.oracleText
        c.faces.isNotEmpty() -> joinFaces(c.faces, { it.oracleText }, "\n//\n")
        else -> ""
    }

    return CardRow(
        oracleId = c.oracleId!!,
        name = c.name,
        manaCost = manaCost,
        typeLine = typeLine,
        oracleText = oracleText,
        keywords = c.keywords,
    )
}

fun toRulingRow(r: RawRuling): RulingRow? {
    val id = r.oracleId
    if (id.isNullOrBlank()) return null
    return RulingRow(id, r.publishedAt ?: "", r.comment ?: "")
}
