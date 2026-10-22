package com.engineerclient.p3sim.run.api

import com.engineerclient.p3sim.run.Run
import java.util.UUID

/**
 * A party member the human can Spirit Leap to (or, as a ghost, Haunt to): one row of the leap window. The positions are where the member
 * stands now ([RunState] players). [here] is false when the member is not somewhere the human can be taken (their chunk is not loaded,
 * they are in a ghost state): the window leaves them out, as the boss sim does for a bot that is not standing in the fight.
 */
data class LeapTarget(
    val uuid: UUID,
    /** The name on the head and in the chat line (a bot's name, or a real player's). */
    val name: String,
    /** `HEALER MAGE BERSERK ARCHER TANK`. */
    val dungeonClass: String,
    val x: Double, val y: Double, val z: Double,
    val yaw: Float, val pitch: Float,
    val here: Boolean,
)

/**
 * Teammate bots in the clear. Implemented by [com.engineerclient.p3sim.clear.bots.BotsModule]. The bots move visibly door
 * to door and their rooms check off on the measured schedule; there is no bot combat in version 1. Server thread only.
 */
interface BotService {
    /** The bots' UUIDs (empty when bots are off). */
    fun botIds(run: Run): List<UUID> = emptyList()

    /** The party member (bot or human) the ClearPlan assigns the room to, or null. */
    fun ownerOfRoom(run: Run, roomIndex: Int): UUID? = null

    /**
     * The party members other than [viewer] in the viewer's leap order (slots 1 to 4), bots and real players. Spirit Leap and the ghost's
     * Haunt window ask this while a run is in a dungeon stage instead of the boss sim's `Party.mates` (which only knows the P3 bots).
     * The damage work item adapts `SimItems.openLeap` and `openHaunt` to it (it owns `SimItems.kt`); the bots module answers.
     */
    fun leapTargets(run: Run, viewer: UUID): List<LeapTarget> = emptyList()

    /** Asks for a re-plan (the player took a bot's room, or opened a door). The module decides when to do it. */
    fun requestReplan(run: Run, reason: String) {}
}
