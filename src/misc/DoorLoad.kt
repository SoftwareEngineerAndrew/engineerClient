package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.painting.Painting
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

/**
 * Wither doors in red until the room behind them has loaded, then green; and, optionally, no clicks
 * that would open one early.
 *
 * Why: a room only gets its mobs when it soft-loads, on a server tick where the instance clock is a
 * multiple of 10, once someone stands in a tile with a door into it. A wither door opened on that
 * tick (from one before to two after) finds the room with no starred mobs, the room is marked
 * cleared on the spot, and its key never exists. From the client the load is plain to see: the
 * room's mobs (and its paintings) spawn in that tick. So a room counts as loaded from the first
 * mob or painting seen inside it, plus a small margin for the click's trip to the server.
 *
 * The side that matters is the far side of the door from you. Fairy, puzzle, entrance and blood
 * rooms have nothing to load and count as loaded. If a room's mobs can't be seen at all, it counts
 * as loaded after you have stood at its door for a while, so a click is never refused for good.
 */
object DoorLoad : Module(
    name = "Door Load",
    category = Category.custom("Engineer Client"),
    description = "Wither doors show red until the room behind them has loaded its mobs, then green. Opening one before that clears the room instantly and loses its key. Can also refuse clicks on a red door.",
    key = null,
) {
    private val loadedColor by ColorSetting("Loaded Color", Color(60, 220, 90, 0.9f), true, desc = "The door once the room behind it has loaded: safe to open.")
    private val unloadedColor by ColorSetting("Unloaded Color", Color(235, 50, 50, 0.9f), true, desc = "The door while the room behind it has not loaded: opening it now clears the room and loses the key.")
    private val fill by BooleanSetting("Fill", true, desc = "Fills the box faintly as well as outlining it.")
    private val throughWalls by BooleanSetting("Through Walls", true, desc = "Drawn over blocks in the way.")
    private val blockClicks by BooleanSetting("Block Early Clicks", true, desc = "Refuses left and right clicks on a red door (and the blocks around it, which open it too) until the room behind has loaded.")
    private val blockedMessage by BooleanSetting("Blocked Message", true, desc = "Says in chat when a click was refused.")
    private val margin by NumberSetting("Safe Margin", 2, 0, 10, 1, desc = "Ticks to wait after the room's mobs appear before a click is let through.", unit = " ticks")
    private val assumeAfter by NumberSetting("Assume Loaded After", 40, 10, 200, 5, desc = "If no mobs are ever seen behind a door, it counts as loaded after you've been at it this long.", unit = " ticks")

    private var tick = 0L
    /** The client tick each grid tile first had a mob or painting seen in it. */
    private val seen = HashMap<Pair<Int, Int>, Long>()
    /** Consecutive ticks you've been at each door (by its centre). */
    private val atDoor = HashMap<Pair<Int, Int>, Int>()
    private var lastBlockedMsg = -100L

    init {
        on<LevelEvent.Load> { seen.clear(); atDoor.clear() }
        on<TickEvent.End> { EngineerClient.safely("door load tick") { onTick() } }
        on<RenderEvent.Extract> { EngineerClient.safely("door load draw") { draw() } }
    }

    private fun active() = DungeonUtils.inDungeons && !DungeonUtils.inBoss

    private fun onTick() {
        tick++
        if (!active()) return
        val level = mc.level ?: return
        val me = mc.player ?: return
        val team = DungeonUtils.dungeonTeammates.mapTo(HashSet()) { it.name }
        for (e in level.entitiesForRendering()) {
            if (!counts(e, me, team)) continue
            seen.putIfAbsent(DoorGrid.tileOf(e.x, e.z), tick)
        }
        for (d in DoorGrid.ALL) {
            val k = d.x to d.z
            val dx = me.x - (d.x + 0.5); val dz = me.z - (d.z + 0.5)
            if (dx * dx + dz * dz <= 16.0 * 16.0) atDoor[k] = (atDoor[k] ?: 0) + 1 else atDoor.remove(k)
        }
    }

    /** Whether seeing this entity means its room has loaded. */
    private fun counts(e: Entity, me: Player, team: Set<String>): Boolean = when {
        e is Painting -> true
        e === me || e is ArmorStand || e is Bat -> false
        e is Player -> e.gameProfile.name !in team
        else -> e is LivingEntity
    }

    /** Every tile of the room this tile belongs to (Odin's map), or just the tile if Odin doesn't know it. */
    private fun roomOf(t: Pair<Int, Int>) = DungeonScan.rooms.firstOrNull { r -> r.tiles.any { it.x == t.first && it.z == t.second } }

    private fun loaded(d: DoorGrid.Door): Boolean {
        val me = mc.player ?: return true
        val far = DoorGrid.farSide(d, me.x, me.z)
        val room = roomOf(far)
        if (room != null && room.type in setOf(RoomType.FAIRY, RoomType.PUZZLE, RoomType.ENTRANCE, RoomType.BLOOD)) return true
        val tiles = room?.tiles?.map { it.x to it.z } ?: listOf(far)
        val first = tiles.mapNotNull { seen[it] }.minOrNull()
        if (first != null && tick - first >= margin) return true
        return (atDoor[d.x to d.z] ?: 0) >= assumeAfter
    }

    /** A wither door still standing here: any of its 36 blocks still coal. */
    private fun witherDoorStanding(d: DoorGrid.Door): Boolean {
        val level = mc.level ?: return false
        for (y in DoorGrid.DOOR_Y0..DoorGrid.DOOR_Y1) for (dx in -1..1) for (dz in -1..1)
            if (level.getBlockState(BlockPos(d.x + dx, y, d.z + dz)).`is`(Blocks.COAL_BLOCK)) return true
        return false
    }

    private fun RenderEvent.Extract.draw() {
        if (!active()) return
        val me = mc.player ?: return
        for (d in DoorGrid.ALL) {
            val cx = d.x + 0.5; val cz = d.z + 0.5
            if ((me.x - cx) * (me.x - cx) + (me.z - cz) * (me.z - cz) > 96.0 * 96.0) continue
            if (!witherDoorStanding(d)) continue
            val c = if (loaded(d)) loadedColor else unloadedColor
            val box = AABB(d.x - 1.0, DoorGrid.DOOR_Y0.toDouble(), d.z - 1.0, d.x + 2.0, DoorGrid.DOOR_Y1 + 1.0, d.z + 2.0).inflate(0.01)
            if (fill) drawFilledBox(box, c.withAlpha(c.alphaFloat * 0.25f), depth = !throughWalls)
            drawWireFrameBox(box, c, depth = !throughWalls)
        }
    }

    /**
     * Called before the game sends a left click (start mining) or right click (use on block) on [pos]:
     * true refuses it. Only a still-standing wither door, from its click zone, before its room loads.
     */
    @JvmStatic
    fun refuseClick(pos: BlockPos): Boolean {
        if (!enabled || !blockClicks || !active()) return false
        val d = DoorGrid.doorAt(pos.x, pos.y, pos.z) ?: return false
        if (!witherDoorStanding(d) || loaded(d)) return false
        if (blockedMessage && tick - lastBlockedMsg >= 20) {
            lastBlockedMsg = tick
            EngineerClient.msg("§cDoor click blocked: the room behind hasn't loaded yet.")
        }
        return true
    }
}
