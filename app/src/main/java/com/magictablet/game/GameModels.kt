package com.magictablet.game

import kotlinx.serialization.Serializable

const val POISON_LOSS = 10
const val COMMANDER_DAMAGE_LOSS = 21
const val SEAT_COLOR_COUNT = 6

@Serializable
data class PlayerState(
    val id: Int,
    val seat: Int,
    val name: String,
    val colorIndex: Int,
    val life: Int,
    val poison: Int = 0,
    val commanderDamage: Map<Int, Int> = emptyMap(),
    val counters: Map<String, Int> = mapOf("energy" to 0, "experience" to 0),
)

@Serializable
data class GameState(
    val startingLife: Int,
    val players: List<PlayerState>,
    val activePlayerId: Int? = null,
    val monarchPlayerId: Int? = null,
    val stack: List<StackItem> = emptyList(),
    val priorityPlayerId: Int? = null,
    val consecutivePasses: Int = 0,
)

data class RecentDelta(val amount: Int, val token: Long)

fun PlayerState.isLost(): Boolean =
    life <= 0 || poison >= POISON_LOSS || commanderDamage.values.any { it >= COMMANDER_DAMAGE_LOSS }

fun PlayerState.lossReason(): String? = when {
    life <= 0 -> "0 life"
    poison >= POISON_LOSS -> "poison"
    commanderDamage.values.any { it >= COMMANDER_DAMAGE_LOSS } -> "cmdr"
    else -> null
}

/** floor of the count goes to the (rotated) top row, ceil to the bottom row. */
fun seatSplit(playerCount: Int): Pair<Int, Int> {
    val top = playerCount / 2
    return top to (playerCount - top)
}

fun initialGame(playerCount: Int, startingLife: Int): GameState {
    val players = (1..playerCount).map { seat ->
        PlayerState(
            id = seat,
            seat = seat,
            name = "P$seat",
            colorIndex = (seat - 1) % SEAT_COLOR_COUNT,
            life = startingLife,
        )
    }
    return GameState(startingLife = startingLife, players = players)
}

private fun GameState.updatePlayer(playerId: Int, transform: (PlayerState) -> PlayerState): GameState =
    copy(players = players.map { if (it.id == playerId) transform(it) else it })

fun GameState.adjustLife(playerId: Int, delta: Int): GameState =
    updatePlayer(playerId) { it.copy(life = it.life + delta) }

fun GameState.adjustPoison(playerId: Int, delta: Int): GameState =
    updatePlayer(playerId) { it.copy(poison = (it.poison + delta).coerceAtLeast(0)) }

fun GameState.adjustCommanderDamage(playerId: Int, fromOpponentId: Int, delta: Int): GameState =
    updatePlayer(playerId) {
        val next = ((it.commanderDamage[fromOpponentId] ?: 0) + delta).coerceAtLeast(0)
        it.copy(commanderDamage = it.commanderDamage + (fromOpponentId to next))
    }

fun GameState.adjustCounter(playerId: Int, counter: String, delta: Int): GameState =
    updatePlayer(playerId) {
        val next = ((it.counters[counter] ?: 0) + delta).coerceAtLeast(0)
        it.copy(counters = it.counters + (counter to next))
    }

data class TimerState(
    val running: Boolean = false,
    val elapsedSeconds: Long = 0,
)

fun GameState.advanceTurn(): GameState {
    if (players.isEmpty()) return this
    val currentIndex = players.indexOfFirst { it.id == activePlayerId }
    val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % players.size
    return copy(activePlayerId = players[nextIndex].id)
}

fun GameState.setActivePlayer(id: Int): GameState = copy(activePlayerId = id)

fun GameState.toggleMonarch(id: Int): GameState =
    copy(monarchPlayerId = if (monarchPlayerId == id) null else id)
