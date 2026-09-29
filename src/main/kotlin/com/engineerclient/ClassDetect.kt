package com.engineerclient

import com.engineerclient.waypoints.BrwWaypoints
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

/**
 * Own-class detection. Odin already parses the dungeon tab list into
 * DungeonUtils.dungeonTeammates (class + level per player); we only watch the
 * entry that is us. Tab detection fails roughly 1 run in 200 — that case falls
 * back to the stashed last-known class, and the miss is counted and logged so
 * a wording change presents as a number instead of silence.
 */
object ClassDetect {

    var detected: DungeonClass? = null
        private set

    /** Floors entered with no live detection (fallback used) — visible via /brw status. */
    var fallbackFloors: Int = 0
        private set

    private var announcedFallback = false

    fun reset() {
        detected = null
        announcedFallback = false
    }

    /** Called every ~second while in a dungeon. */
    fun poll() {
        if (!DungeonUtils.inDungeons) return
        val clazz = DungeonUtils.currentDungeonPlayer.clazz
        if (clazz == DungeonClass.EMPTY || clazz == detected) return

        detected = clazz
        announcedFallback = false
        EngineerClient.logger.info("[ec] detected own class from tab: ${clazz.name}")

        RushProfiles.friendlyName(clazz)?.let { friendly ->
            if (EcConfig.data.lastKnownClass != friendly) {
                EcConfig.data.lastKnownClass = friendly
                EcConfig.save()
            }
        }
        RushProfiles.applySelection("class detected: ${clazz.name}")
    }

    /** Called once when the floor is known; logs loudly when the run starts on the fallback. */
    fun onFloorEnter(floorName: String) {
        if (detected == null && !announcedFallback) {
            announcedFallback = true
            fallbackFloors++
            val stash = EcConfig.data.lastKnownClass
            EngineerClient.logger.warn(
                "[ec] floor $floorName entered with NO tab class detection " +
                    "(miss #$fallbackFloors) — using ${stash ?: "nothing (no stash either)"}"
            )
            // Only Blood Rush Waypoints needs the class; the fallback itself is just logged (above).
            if (stash == null && BrwWaypoints.enabled) EngineerClient.msg("§cBlood Rush Waypoints: couldn't tell your class - pick it in §f/ec")
        }
        RushProfiles.applySelection("entered $floorName")
    }
}
