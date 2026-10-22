package com.engineerclient.ads

/**
 * The Practice page's way into the dungeon engine: opens its solo world set up for a [PracticeRequest],
 * or switches the world already open to it.
 */
object EnginePractice {

    /** Opens (or switches the open engine world to) [req]. */
    fun start(req: PracticeRequest) {
        TODO("engine practice backend")
    }

    /** Rooms the single-room practice can build, as /practice names them (underscores for spaces). */
    fun roomNames(): List<String> = emptyList()

    /** The seed of the last dungeon the engine planned in this game, or null if none yet. */
    fun lastSeed(): Long? = null
}
