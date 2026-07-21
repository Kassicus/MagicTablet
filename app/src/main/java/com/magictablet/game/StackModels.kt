package com.magictablet.game

enum class StackKind { Spell, Activated, Triggered }

data class StackItem(
    val id: Long,
    val controllerId: Int,
    val cardOracleId: String?,
    val kind: StackKind,
    val label: String,
    val targets: String = "",
)

private fun GameState.priorityStart(): Int? = activePlayerId ?: players.firstOrNull()?.id

fun GameState.pushStackItem(item: StackItem): GameState =
    copy(stack = stack + item, priorityPlayerId = priorityStart(), consecutivePasses = 0)

fun GameState.resolveTop(): GameState {
    if (stack.isEmpty()) return this
    val rest = stack.dropLast(1)
    return copy(
        stack = rest,
        priorityPlayerId = if (rest.isEmpty()) null else priorityStart(),
        consecutivePasses = 0,
    )
}

fun GameState.removeStackItem(id: Long): GameState {
    val rest = stack.filterNot { it.id == id }
    return copy(stack = rest, priorityPlayerId = if (rest.isEmpty()) null else priorityPlayerId)
}

fun GameState.clearStack(): GameState =
    copy(stack = emptyList(), priorityPlayerId = null, consecutivePasses = 0)

fun GameState.passPriority(): GameState {
    val current = priorityPlayerId ?: return this
    val next = consecutivePasses + 1
    if (next >= players.size) return resolveTop()
    val idx = players.indexOfFirst { it.id == current }
    val nextId = if (idx < 0) players.first().id else players[(idx + 1) % players.size].id
    return copy(priorityPlayerId = nextId, consecutivePasses = next)
}
