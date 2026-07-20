package com.magictablet.cards

data class CardSummary(
    val oracleId: String,
    val name: String,
    val manaCost: String,
    val typeLine: String,
)

data class RulingItem(
    val publishedAt: String,
    val text: String,
)

data class CardDetail(
    val oracleId: String,
    val name: String,
    val manaCost: String,
    val typeLine: String,
    val oracleText: String,
    val keywords: List<String>,
    val rulings: List<RulingItem>,
)
