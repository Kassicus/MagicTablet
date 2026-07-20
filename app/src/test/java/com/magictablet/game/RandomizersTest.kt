package com.magictablet.game

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RandomizersTest {
    @Test fun rollDie_inRange() {
        val r = Random(42)
        repeat(200) {
            val v6 = rollDie(6, r); assertTrue(v6 in 1..6)
            val v20 = rollDie(20, r); assertTrue(v20 in 1..20)
        }
    }

    @Test fun flipCoin_isHeadsOrTails() {
        val r = Random(1)
        repeat(50) { assertTrue(flipCoin(r) in setOf("Heads", "Tails")) }
    }
}
