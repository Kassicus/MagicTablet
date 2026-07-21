package com.magictablet.game

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** The persisted unit: the whole live game plus the id counter so restored stack items don't collide. */
@Serializable
data class GameSnapshot(val state: GameState, val nextStackId: Long)

/** Persistence port. [NoPersistence] is the default so context-free unit tests need no store. */
interface GamePersistence {
    fun load(): GameSnapshot?
    fun save(snapshot: GameSnapshot)
}

object NoPersistence : GamePersistence {
    override fun load(): GameSnapshot? = null
    override fun save(snapshot: GameSnapshot) {}
}

/**
 * A single-file JSON snapshot store. Takes the target [file] (not a Context) so it is fully unit-testable.
 * Writes atomically (tmp -> rename); load/save swallow exceptions so persistence can never crash the app.
 */
class GameStore(private val file: File) : GamePersistence {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): GameSnapshot? = try {
        if (!file.exists()) null else json.decodeFromString<GameSnapshot>(file.readText())
    } catch (e: Exception) {
        null
    }

    override fun save(snapshot: GameSnapshot) {
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // best-effort; a failed autosave must never crash the app
        }
    }
}
