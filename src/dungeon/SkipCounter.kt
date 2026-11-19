package com.engineerclient.dungeon

import com.adm.AdmMod.mc
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.tile.MapCheckmark
import com.adm.utils.render.text
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.Floor
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.ceil

/**
 * Clear Info's Skip Counter: secrets left behind in rooms you or a teammate (the map's P2P share) went into and left,
 * against how many 300 lets you skip. Secrets are 40 points; every bonus point (crypts, Mimic, Prince, bats, Paul) lets
 * a fortieth of the needed secrets go. The bonus counted is the planned one until the run's own is higher.
 */
object SkipCounter {
    fun render(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val line = if (example) line(4, 9) else {
            if (!DungeonUtils.inDungeons || ClearInfo.skipHideInBoss && DungeonUtils.inBoss) return 0 to 0
            val floor = DungeonUtils.floor ?: return 0 to 0
            val total = total()
            if (total <= 0) return 0 to 0
            line(skipped(), allowed(floor, total))
        }
        g.text(line, 1, 1)
        return mc.font.width(line) + 2 to mc.font.lineHeight + 1
    }

    private fun line(skipped: Int, allowed: Int): String {
        val left = allowed - skipped
        val colour = when { left < 0 -> "§c"; left <= 1 -> "§e"; else -> "§a" }
        return "§7Skipped: $colour$skipped§7/§f$allowed" + if (left < 0) " §c(${-left} over)" else " §7($colour$left§7 left)"
    }

    /** Secrets in the run: the tab list's count once there is one, else every room on the map once all are known. */
    private fun total(): Int {
        DungeonUtils.totalSecrets.takeIf { it > 0 }?.let { return it }
        val rooms = DungeonScan.rooms
        if (rooms.isEmpty() || rooms.any { it.data == null }) return 0
        return rooms.sumOf { it.data?.maxSecrets ?: 0 }
    }

    /**
     * Secrets not found in rooms left behind, other than the one you're in. Solo, only rooms you've cleared: one you
     * left uncleared you're coming back to. In a party, any room someone has been in.
     */
    private fun skipped(): Int {
        val here = DungeonUtils.currentRoom
        val solo = DungeonUtils.dungeonTeammates.size <= 1
        return DungeonScan.rooms.sumOf { room ->
            val max = room.data?.maxSecrets ?: 0
            val left = if (solo) room.checkmark == MapCheckmark.WHITE else room.walkedInto || room.playerWalkedInto
            if (room === here || max == 0 || !left) 0
            else (max - (room.foundSecrets ?: 0)).coerceAtLeast(0)
        }
    }

    private fun allowed(floor: Floor, total: Int): Int {
        val paul = if (DungeonUtils.isPaul) 10 else 0
        val planned = (if (floor.isMM && floor.floorNumber >= 6) ClearInfo.skipBonusM67 else ClearInfo.skipBonusOther).toInt()
        val bonus = maxOf(planned, DungeonUtils.getBonusScore - paul) + paul
        val deaths = (DungeonUtils.deathCount * 2 - 1).coerceAtLeast(0)
        val needed = ceil(total * floor.requiredPercentage * (40 - bonus + deaths) / 40.0).toInt().coerceAtLeast(0)
        return (total - needed).coerceAtLeast(0)
    }
}
