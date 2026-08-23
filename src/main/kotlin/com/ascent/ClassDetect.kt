package com.ascent

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

    /** Floors entered with no live detection (fallback used) — visible via /ascent status. */
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
        AscentMod.logger.info("[ascent] detected own class from tab: ${clazz.name}")

        RushProfiles.friendlyName(clazz)?.let { friendly ->
            if (AscentConfig.data.lastKnownClass != friendly) {
                AscentConfig.data.lastKnownClass = friendly
                AscentConfig.save()
            }
        }
        RushProfiles.applySelection("class detected: ${clazz.name}")
    }

    /** Called once when the floor is known; logs loudly when the run starts on the fallback. */
    fun onFloorEnter(floorName: String) {
        if (detected == null && !announcedFallback) {
            announcedFallback = true
            fallbackFloors++
            val stash = AscentConfig.data.lastKnownClass
            AscentMod.logger.warn(
                "[ascent] floor $floorName entered with NO tab class detection " +
                    "(miss #$fallbackFloors) — using ${stash ?: "nothing (no stash either)"}"
            )
            if (stash != null) AscentMod.chat("§8[§6Ascent§8]§e tab class detection missed — using last known: §a$stash")
            else AscentMod.chat("§8[§6Ascent§8]§c no class detected and no stash — pick one in /ascent")
        }
        RushProfiles.applySelection("entered $floorName")
    }
}
