package com.magictablet.game

import kotlin.random.Random

/** Uniform die roll in 1..sides. */
fun rollDie(sides: Int, random: Random = Random.Default): Int = random.nextInt(sides) + 1

/** "Heads" or "Tails". */
fun flipCoin(random: Random = Random.Default): String = if (random.nextBoolean()) "Heads" else "Tails"
