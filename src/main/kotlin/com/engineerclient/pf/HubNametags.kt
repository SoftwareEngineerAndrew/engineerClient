package com.engineerclient.pf

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils

/**
 * In the Dungeon Hub, every real player gets a stat line floating over their nametag — the same
 * numbers, colours and disk-backed cache as the Party Finder tooltip ([PlayerStats]):
 *
 * ```
 *   §e47.3 §8| §b41.2k §8| §d4:31
 * ```
 *
 * Catacombs level, total secrets, and the fastest time on a chosen floor (F7 by default — the
 * tooltip reads the listing's floor, but a hub has no listing, so here it is a setting).
 *
 * Dungeon Hub only, read off Odin's scoreboard-driven location ("⏣ Dungeon Hub"), so the busy
 * main hub stays uncluttered. NPCs pose as players all over the hub; real players are the ones
 * with a version-4 UUID (the same test the Better PF recorder uses).
 */
object HubNametags : Module(
    name = "Hub Nametag Stats",
    category = Category.custom("Blood Rush"),
    description = "Catacombs level, secrets and floor PB over players' nametags in the Dungeon Hub.",
    toggled = true, // existing installs have no saved state for a new module; on by default
) {
    private val showCata by BooleanSetting("Cata Level", true, desc = "Catacombs level, one decimal.")
    private val showSecrets by BooleanSetting("Secrets", true, desc = "Total secrets found, in thousands.")
    private val showPb by BooleanSetting("Floor PB", true, desc = "Fastest time on the chosen floor.")
    private val pbFloor by SelectorSetting("PB Floor", "F7", arrayListOf("E", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"), desc = "Which floor the PB column shows.")
    private val pbType by SelectorSetting("PB Type", "S+", arrayListOf("S+", "S", "Any"), desc = "Which fastest-time the PB column shows.")
    private val scale by NumberSetting("Text Scale", 1f, 0.5f, 2f, increment = 0.1f, desc = "The scale of the stat line.")
    private val height by NumberSetting("Height", 0.85f, 0.5f, 1.5f, increment = 0.05f, desc = "How far above the player's head the line sits, in blocks.")

    /** Vanilla stops rendering nametags past 64 blocks; match it. */
    private const val RANGE_SQ = 64.0 * 64.0

    init {
        on<RenderEvent.Extract> {
            if (!LocationUtils.isCurrentArea(Island.DungeonHub)) return@on
            val level = mc.level ?: return@on
            val me = mc.player ?: return@on
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
            // Selector index → PB mode and floor: 0 is Entrance, then F1..F7, then M1..M7.
            val mode = if (pbFloor >= 8) "m" else "f"
            val floor = if (pbFloor >= 8) (pbFloor - 7).toString() else pbFloor.toString()
            for (p in level.players()) {
                if (p === me || !p.isAlive || p.isInvisible) continue
                if (p.uuid.version() != 4) continue // hub NPCs pose as players; real ones are v4
                if (p.distanceToSqr(me) > RANGE_SQ) continue
                val line = lineFor(p.name.string, mode, floor)
                if (line.isEmpty()) continue
                drawText(line, p.getPosition(pt).add(0.0, p.bbHeight + height.toDouble(), 0.0), scale, false)
            }
        }
    }

    private fun lineFor(name: String, mode: String, floor: String): String =
        when (val entry = PlayerStats.lookup(name)) {
            is PlayerStats.Loading -> "§7…"
            is PlayerStats.Failed -> "§c?"
            is PlayerStats.Ready -> {
                val stats = entry.stats
                val parts = ArrayList<String>(3)
                if (showCata) parts += "§e" + PlayerStats.cataStr(stats)
                if (showSecrets) parts += "§b" + PlayerStats.secretsStr(stats.secrets)
                if (showPb) parts += "§d" + PlayerStats.pbStr(stats, mode, floor, pbType)
                parts.joinToString(" §8| ")
            }
        }
}
