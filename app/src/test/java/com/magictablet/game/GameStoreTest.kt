package com.magictablet.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun newStore() = GameStore(File(temp.newFolder(), "game.json"))

    private fun sampleSnapshot(): GameSnapshot {
        val state = initialGame(4, 40)
            .adjustLife(1, -5)
            .adjustPoison(2, 3)
            .adjustCommanderDamage(1, 3, 7)
            .adjustCounter(1, "energy", 2)
            .setActivePlayer(2)
            .toggleMonarch(3)
            .pushStackItem(StackItem(1, 1, "oid-1", StackKind.Spell, "Bolt", "P2"))
            .pushStackItem(StackItem(2, 2, null, StackKind.Triggered, "Draw", ""))
        return GameSnapshot(state, nextStackId = 3)
    }

    @Test fun saveThenLoad_roundTrips() {
        val store = newStore()
        val snapshot = sampleSnapshot()
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }

    @Test fun load_missingFile_returnsNull() {
        assertNull(newStore().load())
    }

    @Test fun load_garbage_returnsNull() {
        val file = File(temp.newFolder(), "game.json")
        file.writeText("{ not valid json ")
        assertNull(GameStore(file).load())
    }
}
