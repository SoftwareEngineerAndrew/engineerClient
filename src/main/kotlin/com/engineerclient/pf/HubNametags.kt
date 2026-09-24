package com.engineerclient.pf

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.render.drawText

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
 * Dungeon Hub only, so the busy main hub stays uncluttered — read live off the tab list's
 * "Area: Dungeon Hub" info line, the same line Odin's LocationUtils parses. NPCs pose as players
 * all over the hub; real players are the ones with a version-4 UUID (the same test the Better PF
 * recorder uses).
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

    // Last render pass, for the diagnostic log line.
    @Volatile private var lastSeen = 0
    @Volatile private var lastDrawn = 0

    init {
        on<RenderEvent.Extract> {
            val hub = inDungeonHub()
            diagLog(hub)
            if (!hub) return@on
            val level = mc.level ?: return@on
            val me = mc.player ?: return@on
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
            // Selector index → PB mode and floor: 0 is Entrance, then F1..F7, then M1..M7.
            val mode = if (pbFloor >= 8) "m" else "f"
            val floor = if (pbFloor >= 8) (pbFloor - 7).toString() else pbFloor.toString()
            var seen = 0
            var drawn = 0
            for (p in level.players()) {
                seen++
                if (p === me || !p.isAlive || p.isInvisible) continue
                if (p.uuid.version() != 4) continue // hub NPCs pose as players; real ones are v4
                if (p.distanceToSqr(me) > RANGE_SQ) continue
                val line = lineFor(p.name.string, mode, floor)
                if (line.isEmpty()) continue
                drawn++
                drawText(line, p.getPosition(pt).add(0.0, p.bbHeight + height.toDouble(), 0.0), scale, false)
            }
            lastSeen = seen
            lastDrawn = drawn
        }
    }

    /**
     * The tab list's "Area: Dungeon Hub" info line, read live every frame. Odin's LocationUtils
     * parses the same line off player-info packets, but only while its cached area is Unknown —
     * an Area packet that lands before Odin's world-load reset is missed for the whole lobby.
     * Reading the current tab entries directly has no ordering to get wrong. All entries, not
     * just the listed ones: Hypixel's info lines are fake players that need not be listed.
     */
    private fun inDungeonHub(): Boolean {
        val infos = mc.connection?.onlinePlayers ?: return false
        return infos.any { info ->
            val s = info.tabListDisplayName?.string?.trim() ?: return@any false
            s.startsWith("Area:") && "Dungeon Hub" in s
        }
    }

    // ------------------------------------------------------------------ diagnostics
    //
    // Into the game log (not chat), readable from logs/latest.log after a session: the gate,
    // what the tab list actually contains, and how many players got a line last frame.

    private var lastLog = 0L
    private var lastHub = false

    private fun diagLog(hub: Boolean) {
        val now = System.currentTimeMillis()
        if (hub == lastHub && now - lastLog < 15_000) return
        lastLog = now
        lastHub = hub
        val all = mc.connection?.onlinePlayers ?: emptyList()
        val listed = mc.connection?.listedOnlinePlayers ?: emptyList()
        val areaIsh = all.mapNotNull { it.tabListDisplayName?.string?.trim() }
            .filter { "Area" in it || "Dungeon" in it || "Hub" in it }
        com.engineerclient.EngineerClient.logger.info(
            "[ec] hub nametags: gate=$hub tab=${all.size} entries (${listed.size} listed) " +
                "drawn=$lastDrawn/$lastSeen area-ish=${areaIsh.take(4)}"
        )
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
