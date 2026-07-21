package com.magictablet.game

data class GameFormat(val name: String, val playerCount: Int, val startingLife: Int)

val GAME_FORMATS: List<GameFormat> = listOf(
    GameFormat("Commander", 4, 40),
    GameFormat("Commander Duel", 2, 40),
    GameFormat("Standard", 2, 20),
    GameFormat("Multiplayer 20", 4, 20),
)
