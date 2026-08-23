package com.ascent

import com.ascent.waypoints.AscentPackFiles
import com.ascent.waypoints.AscentWaypoints
import com.ascent.waypoints.loadWaypoints
import com.odtheking.odin.OdinMod
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import kotlinx.coroutines.launch

/**
 * The 5 classes x {2,3,4,5} players-on-rush x dedicated-door matrix = 40 pregenerated
 * (initially blank) Ascent waypoint packs, plus the logic that keeps the Ascent
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
            if (AscentPackFiles.createPack(packName(clazz, players, door))) created++
        }
        if (created > 0) AscentMod.logger.info("[ascent] created $created blank profile packs")
    }

    /**
     * The class the profile should use right now: manual override, else live tab
     * detection, else the stashed last-known class. Null = nothing to go on yet.
     */
    fun effectiveClass(): String? {
        AscentConfig.data.classOverride?.let { return it.takeIf { c -> c in CLASSES } }
        ClassDetect.detected?.let { return friendlyName(it) }
        return AscentConfig.data.lastKnownClass?.takeIf { it in CLASSES }
    }

    fun activePackName(): String? {
        val clazz = effectiveClass() ?: return null
        return packName(clazz, AscentConfig.data.playersOnRush, AscentConfig.data.dedicatedDoor)
    }

    private var warnedModuleOff = false

    /**
     * Point the Ascent Waypoints module's pack selection at the current profile pack
     * (+ any custom packs) and reload. Safe to call often — no-ops when the selection
     * already matches.
     */
    fun applySelection(reason: String) {
        if (!AscentConfig.data.enabled) return
        if (!AscentWaypoints.enabled && !warnedModuleOff) {
            warnedModuleOff = true
            AscentMod.logger.warn("[ascent] the Ascent Waypoints module is disabled — profiles are applied but nothing will render until it is enabled")
            AscentMod.chat("§8[§6Ascent§8]§e the §fAscent Waypoints§e module is OFF — enable it in Odin's ClickGUI (Ascent panel) or nothing will render")
        }
        val pack = activePackName() ?: run {
            AscentMod.logger.info("[ascent] no class known yet ($reason) — leaving pack selection alone")
            return
        }
        val desired = (listOf(pack) + AscentConfig.data.customPacks).distinct()
        if (AscentWaypoints.selectedPackIds == desired && AscentWaypoints.editPackId == pack) return

        OdinMod.scope.launch {
            try {
                AscentPackFiles.createPack(pack) // silent no-op if it already exists
                AscentWaypoints.selectedPackIds = desired.toMutableList()
                AscentWaypoints.editPackId = pack
                AscentWaypoints.loadWaypoints() // normalizes + persists selection via the module config
                AscentMod.logger.info("[ascent] switched to '$pack' + ${desired.size - 1} custom ($reason)")
                AscentMod.chat("§8[§6Ascent§8]§7 waypoints: §a$pack§7 ($reason)")
            } catch (t: Throwable) {
                AscentMod.logger.error("[ascent] failed to apply pack selection '$pack' ($reason)", t)
            }
        }
    }
}
