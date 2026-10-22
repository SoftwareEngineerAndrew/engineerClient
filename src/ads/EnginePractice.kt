package com.engineerclient.ads

import net.rustclear.mod.Floor
import net.rustclear.mod.engine.Engine
import net.rustclear.mod.engine.EngineServer
import net.rustclear.mod.engine.EngineWorld
import net.rustclear.mod.engine.PracticeSpec

/**
 * The Practice page's way into the dungeon engine: opens its solo world set up for a [PracticeRequest],
 * or switches the world already open to it.
 */
object EnginePractice {

    /** Opens (or switches the open engine world to) [req]. */
    fun start(req: PracticeRequest) {
        val spec = specOf(req)
        if (EngineServer.server() != null) {
            EngineServer.practiceNow(spec)
            return
        }
        // Solo: a private world, never shared through the relay (that is Online.hostParty's).
        EngineServer.openOnStart(false)
        EngineServer.preparePractice(spec)
        EngineWorld.open()
    }

    /** Rooms the single-room practice can build, as /practice names them (underscores for spaces). */
    fun roomNames(): List<String> = Engine.practiceRooms()

    /** The seed of the last dungeon the engine planned in this game, or null if none yet. */
    fun lastSeed(): Long? = EngineServer.lastSeed()

    private fun specOf(req: PracticeRequest): PracticeSpec = when (req) {
        is PracticeRequest.FullClear -> PracticeSpec.fullClear(req.floor, req.seed)
        is PracticeRequest.Room -> PracticeSpec.room(req.floor, req.room, req.door)
        is PracticeRequest.BloodRush -> PracticeSpec.bloodRush(req.floor, req.seed)
        is PracticeRequest.BloodCamp -> PracticeSpec.bloodCamp(req.floor)
        // The Wither King is M7's alone.
        is PracticeRequest.Boss -> PracticeSpec.boss(
            if (req.phase == PracticeRequest.Phase.P5) Floor.M7 else req.floor, req.phase.name.lowercase())
    }
}
