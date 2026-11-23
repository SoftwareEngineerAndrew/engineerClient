package com.engineerclient.dungeon

import com.adm.AdmMod.mc
import com.adm.events.LevelEvent
import com.adm.events.core.on
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.MapCheckmark
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.render.text
import com.adm.utils.skyblock.dungeon.DungeonPlayer
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.Floor
import com.engineerclient.EngineerClient
import com.engineerclient.clearbreakdown.ClearBreakdown
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.ceil

/**
 * Clear Info's Skip Counter: secrets left behind in rooms you or a teammate went into and left, against how many 300
 * lets you skip. Secrets are 40 points; every bonus point (crypts, Mimic, Prince, bats, Paul) lets a fortieth of the
 * needed secrets go. The bonus counted is the planned one until the run's own is higher.
 *
 * A room isn't left while anyone is in it or past it (further from the entrance, through it): they'll walk back through
 * it to get anywhere else. It is the moment nobody is past it any more: walked back out without its secrets, or a leap.
 * The blood room holds nothing open: nobody walks back from it.
 */
object SkipCounter {
    /** Each player's room as last placed. */
    private val at = HashMap<String, DungeonRoom>()
    private val visited = HashSet<DungeonRoom>()

    fun install() {
        ClientTickEvents.END_CLIENT_TICK.register { EngineerClient.safely("skip counter") { tick() } }
        on<LevelEvent.Load> { at.clear(); visited.clear() }
    }

    private fun tick() {
        if (!DungeonUtils.inDungeons || DungeonUtils.inBoss) return
        val me = mc.user.name
        for (p in DungeonUtils.dungeonTeammates) {
            if (p.isDead) { at.remove(p.name); continue }
            val room = (if (p.name == me) DungeonUtils.currentRoom else roomOf(p)) ?: continue
            at[p.name] = room
            visited += room
        }
    }

    private fun roomOf(p: DungeonPlayer): DungeonRoom? {
        p.entity?.let { return ClearBreakdown.roomAt(it.x, it.z) }
        if (p.mapPos.data == 0L) return null
        val x = ((p.mapPos.x + 128) / 2.0 - DungeonScan.startX) * 32.0 / DungeonScan.roomGap - 200
        val z = ((p.mapPos.z + 128) / 2.0 - DungeonScan.startY) * 32.0 / DungeonScan.roomGap - 200
        return ClearBreakdown.roomAt(x, z)
    }

    /** Each room's neighbour on the way back to the entrance, by the doors on the map. */
    private fun towardEntrance(): Map<DungeonRoom, DungeonRoom> {
        val start = DungeonScan.rooms.firstOrNull { it.type == RoomType.ENTRANCE } ?: return emptyMap()
        val links = HashMap<DungeonRoom, MutableSet<DungeonRoom>>()
        for (d in DungeonScan.doors.values) {
            val a = DungeonScan.tiles.getOrNull(d.originTileIndex)?.room ?: continue
            val b = DungeonScan.tiles.getOrNull(d.destinationTileIndex)?.room ?: continue
            if (a === b) continue
            links.getOrPut(a) { HashSet() } += b
            links.getOrPut(b) { HashSet() } += a
        }
        val back = HashMap<DungeonRoom, DungeonRoom>()
        val queue = ArrayDeque(listOf(start))
        val seen = hashSetOf(start)
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            for (n in links[r].orEmpty()) if (seen.add(n)) { back[n] = r; queue += n }
        }
        return back
    }

    /** The rooms someone is in or past. */
    private fun open(): Set<DungeonRoom> {
        val back = towardEntrance()
        val out = HashSet<DungeonRoom>()
        for (r in at.values + listOfNotNull(DungeonUtils.currentRoom)) {
            out += r
            if (r.type == RoomType.BLOOD) continue
            var p = back[r]
            while (p != null) { out += p; p = back[p] }
        }
        return out
    }

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

    /** Secrets not found in rooms left behind. Solo, only rooms you've cleared: one you left uncleared you're coming back to. */
    private fun skipped(): Int {
        val solo = DungeonUtils.dungeonTeammates.size <= 1
        val busy = open()
        return DungeonScan.rooms.sumOf { room ->
            val max = room.data?.maxSecrets ?: 0
            val left = if (solo) room.checkmark == MapCheckmark.WHITE else room in visited || room.walkedInto || room.playerWalkedInto
            if (room in busy || max == 0 || !left) 0
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
