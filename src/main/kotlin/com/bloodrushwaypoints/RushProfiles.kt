package com.bloodrushwaypoints

import com.bloodrushwaypoints.waypoints.BrwPackFiles
import com.bloodrushwaypoints.waypoints.BrwWaypoints
import com.bloodrushwaypoints.waypoints.loadWaypoints
import com.odtheking.odin.OdinMod
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import kotlinx.coroutines.launch

/**
 * The 5 classes x {2,3,4,5} players-on-rush x dedicated-door matrix = 40 pregenerated
 * (initially blank) BRW waypoint packs, plus the logic that keeps the Blood Rush Waypoints
 * Waypoints module's pack selection pointed at the right one. Odin's own
 * DungeonWaypoints module and its packs are never touched.
 */
object RushProfiles {

    /** Friendly names used in pack names; detection maps Odin's enum onto these. */
    val CLASSES = listOf("Mage", "Archer", "Berserker", "Healer", "Tank")

    fun friendlyName(clazz: DungeonClass): String? = when (clazz) {
        DungeonClass.MAGE -> "Mage"
        DungeonClass.ARCHER -> "Archer"
        DungeonClass.BERSERK -> "Berserker"
        DungeonClass.HEALER -> "Healer"
        DungeonClass.TANK -> "Tank"
        else -> null
    }

    fun packName(clazz: String, players: Int, dedicatedDoor: Boolean): String =
        "BR $clazz ${players}p ${if (dedicatedDoor) "Door" else "NoDoor"}"

    fun isProfilePack(name: String): Boolean = name.startsWith("BR ")

    /** Create any of the 40 packs that don't exist yet (createPack is silent on existing names). */
    suspend fun ensureAllPacks() {
        var created = 0
        for (clazz in CLASSES) for (players in 2..5) for (door in listOf(true, false)) {
            if (BrwPackFiles.createPack(packName(clazz, players, door))) created++
        }
        if (created > 0) BrwMod.logger.info("[brw] created $created blank profile packs")
    }

    /**
     * The class the profile should use right now: manual override, else live tab
     * detection, else the stashed last-known class. Null = nothing to go on yet.
     */
    fun effectiveClass(): String? {
        BrwConfig.data.classOverride?.let { return it.takeIf { c -> c in CLASSES } }
        ClassDetect.detected?.let { return friendlyName(it) }
        return BrwConfig.data.lastKnownClass?.takeIf { it in CLASSES }
    }

    fun activePackName(): String? {
        val clazz = effectiveClass() ?: return null
        return packName(clazz, BrwConfig.data.playersOnRush, BrwConfig.data.dedicatedDoor)
    }

    private var warnedModuleOff = false

    /**
     * Point the Blood Rush Waypoints module's pack selection at the current profile pack
     * (+ any custom packs) and reload. Safe to call often — no-ops when the selection
     * already matches.
     */
    fun applySelection(reason: String) {
        if (!BrwConfig.data.enabled) return
        if (!BrwWaypoints.enabled && !warnedModuleOff) {
            warnedModuleOff = true
            BrwMod.logger.warn("[brw] the Blood Rush Waypoints module is disabled — profiles are applied but nothing will render until it is enabled")
            BrwMod.chat("§8[§6BRW§8]§e the §fBlood Rush Waypoints§e module is OFF — enable it in Odin's ClickGUI (Blood Rush panel) or nothing will render")
        }
        val pack = activePackName() ?: run {
            BrwMod.logger.info("[brw] no class known yet ($reason) — leaving pack selection alone")
            return
        }
        val desired = (listOf(pack) + BrwConfig.data.customPacks).distinct()
        if (BrwWaypoints.selectedPackIds == desired && BrwWaypoints.editPackId == pack) return

        OdinMod.scope.launch {
            try {
                BrwPackFiles.createPack(pack) // silent no-op if it already exists
                BrwWaypoints.selectedPackIds = desired.toMutableList()
                BrwWaypoints.editPackId = pack
                BrwWaypoints.loadWaypoints() // normalizes + persists selection via the module config
                BrwMod.logger.info("[brw] switched to '$pack' + ${desired.size - 1} custom ($reason)")
                BrwMod.chat("§8[§6BRW§8]§7 waypoints: §a$pack§7 ($reason)")
            } catch (t: Throwable) {
                BrwMod.logger.error("[brw] failed to apply pack selection '$pack' ($reason)", t)
            }
        }
    }
}
