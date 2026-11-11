package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.alert
import com.odtheking.odin.utils.playSoundSettings
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * Block Instaclear: holds back a left or right click on a wither door while the room behind it would instaclear.
 *
 * What an instaclear is (tools/instaclear, 1708 recorded wither doors): Hypixel makes a room's mobs only once a player
 * has stood in the map tile in front of it, 0-11 ticks later. A wither door opened before then finds the room empty and
 * clears it on the spot; its mobs still come, but the key never drops. The rush has to restart.
 *
 * The door is let through once either is true:
 *  - a mob (a mob entity, or a "✯ Name 1.2M❤" stand) is loaded inside the room behind: its mobs are made. Of 772
 *    doors opened with one seen there, none instacleared;
 *  - a teammate (you included) has stood in the tile in front of the door for [waitTicks]: the room has had its
 *    check, for a room whose mobs are all out of your tracking range. Under 15 ticks there 2.7-59% instacleared,
 *    at 15+ 0.2%.
 * Together they would have held 24 of 25 instaclears and 3 of 788 safe opens. Doors into fairy and blood are never held.
 *
 * Hypixel opens a door on a swing aimed at it (from the swing alone, up to about 6 blocks: 450 of 701 recorded own
 * opens had no block click) as well as a right click on it, so the aim is our own raycast to [REACH], not the
 * crosshair's (the game's block reach is shorter). The click is cancelled before the game sends anything.
 */
object BlockInstaclear : Module(
    name = "Block Instaclear",
    key = null,
    category = Category.custom("Engineer Client", 860, 10),
    description = "Blood rush: stops a left or right click on a wither door until the room behind has its mobs, so it can't instaclear (room white at once, no key).",
    toggled = true,
) {
    private val waitTicks by NumberSetting("Door Tile Wait", 15, 0..40, 1, desc = "With no mob seen in the room behind, the door opens once a teammate has stood this long in the tile in front of it.", unit = " ticks")
    private val showAlert by BooleanSetting("Alert", true, desc = "Show a title when a door click is held back.")
    private val alertText by StringSetting("Alert Text", "&cRoom not loaded!", 64, desc = "The title shown. & codes work.", placeholder = "").withDependency { showAlert }
    private val playSound by BooleanSetting("Sound", true, desc = "Play a sound when a door click is held back.")
    private val soundId = +StringSetting("Sound Id", "block.note_block.bass", 64, desc = "Sound id, as /playsound takes it.", placeholder = "").withDependency { playSound }
    private val soundPitch = +NumberSetting("Sound Pitch", 0.5f, 0.1..2.0, 0.01f, desc = "Pitch.").withDependency { playSound }
    private val soundVolume = +NumberSetting("Sound Volume", 1f, 0.1..1.0, 0.01f, desc = "Volume.").withDependency { playSound }
    init {
        +ActionSetting("Play Sound", desc = "Plays it.") { playSoundSettings(sound()) }.withDependency { playSound }
    }

    private fun sound() = Triple(soundId.value, soundVolume.value, soundPitch.value)

    /** How far a swing or a click opens a door: Hypixel answered swings at a door up to about 6 blocks away. */
    private const val REACH = 6.5
    /** One alert per click burst, not one per click. */
    private const val ALERT_GAP = 10

    private var ticks = 0
    /** Per map tile (x + z * 6): the tick a teammate first stood in it, -1 never. */
    private val firstIn = IntArray(36) { -1 }
    private var lastAlert = Int.MIN_VALUE / 2

    init {
        on<LevelEvent.Load> { firstIn.fill(-1); ticks = 0; lastAlert = Int.MIN_VALUE / 2 }
        on<TickEvent.End> {
            ticks++
            if (!DungeonUtils.inDungeons || DungeonUtils.inBoss) return@on
            EngineerClient.safely("block instaclear tiles") {
                val names = DungeonUtils.dungeonTeammates.mapTo(HashSet()) { it.name }
                for (p in level.players()) {
                    if (p !== mc.player && p.name.string !in names) continue
                    val t = tileAt(p.x, p.z) ?: continue
                    if (firstIn[t] < 0) firstIn[t] = ticks
                }
            }
        }
    }

    /** A door the click would open, and the rooms either side of it. */
    private class Door(val near: Int, val behind: Int)

    /** Left click (swing): true holds it back. From the Minecraft.startAttack mixin. */
    @JvmStatic
    fun onAttack(): Boolean = hold(alert = true)

    /** Holding left click: held back silently while it's on such a door. */
    @JvmStatic
    fun blocksContinueAttack(): Boolean = hold(alert = false)

    /** Right click: true holds it back. From the Minecraft.startUseItem mixin. */
    @JvmStatic
    fun onUse(): Boolean = hold(alert = true)

    private fun hold(alert: Boolean): Boolean {
        if (!enabled || !DungeonUtils.inDungeons || DungeonUtils.inBoss) return false
        var held = false
        EngineerClient.safely("block instaclear") {
            val door = aimedDoor() ?: return@safely
            if (safe(door)) return@safely
            held = true
            if (alert && ticks - lastAlert >= ALERT_GAP) {
                lastAlert = ticks
                if (showAlert) alert(alertText.replace('&', '§'), false)
                if (playSound) playSoundSettings(sound())
            }
        }
        return held
    }

    /** The wither door your look meets within [REACH], first thing in the way, or null. */
    private fun aimedDoor(): Door? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val eye = player.eyePosition
        val end = eye.add(player.getViewVector(1f).scale(REACH))
        val hit = level.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        if (hit.type != HitResult.Type.BLOCK) return null
        val pos = (hit as BlockHitResult).blockPos
        if (level.getBlockState(pos).block != Blocks.COAL_BLOCK) return null
        return doorAt(pos, player)
    }

    /**
     * The door [pos] is part of: wither doors are 3x3 blocks, 4 high from y 69, centred on a door spot (the gap between
     * two tiles, -169 + 32t, halfway along a tile, -185 + 32t). The room behind is the side away from [viewer].
     */
    private fun doorAt(pos: BlockPos, viewer: Entity): Door? {
        if (pos.y !in 68..73) return null
        val gx = Math.floorDiv(pos.x + 169 + 16, 32); val gz = Math.floorDiv(pos.z + 169 + 16, 32)
        val cx = Math.floorDiv(pos.x + 185 + 16, 32); val cz = Math.floorDiv(pos.z + 185 + 16, 32)
        val gapX = -169 + 32 * gx; val gapZ = -169 + 32 * gz
        val midX = -185 + 32 * cx; val midZ = -185 + 32 * cz
        return when {
            // A door in a gap running north-south: the tiles west and east of it.
            kotlin.math.abs(pos.x - gapX) <= 1 && kotlin.math.abs(pos.z - midZ) <= 1 -> {
                val west = index(gx, cz) ?: return null; val east = index(gx + 1, cz) ?: return null
                if (viewer.x < gapX + 0.5) Door(west, east) else Door(east, west)
            }
            kotlin.math.abs(pos.z - gapZ) <= 1 && kotlin.math.abs(pos.x - midX) <= 1 -> {
                val north = index(cx, gz) ?: return null; val south = index(cx, gz + 1) ?: return null
                if (viewer.z < gapZ + 0.5) Door(north, south) else Door(south, north)
            }
            else -> null
        }
    }

    /** Whether the room behind [door] has had its mobs made, or isn't a room that drops a key. */
    private fun safe(door: Door): Boolean {
        val room = DungeonScan.tiles.getOrNull(door.behind)?.room
        if (room != null && room.type in setOf(RoomType.FAIRY, RoomType.BLOOD, RoomType.ENTRANCE)) return true
        // Stood in long enough: the room has had its check.
        val since = firstIn[door.near]
        if (since >= 0 && ticks - since >= waitTicks) return true
        // Its mobs are here.
        val tiles = if (room == null) listOf(door.behind) else (0 until 36).filter { DungeonScan.tiles.getOrNull(it)?.room === room }.ifEmpty { listOf(door.behind) }
        val level = mc.level ?: return true
        for (e in level.entitiesForRendering()) {
            val mob = when (e) {
                is ArmorStand -> e.customName?.string?.contains('❤') == true
                is Bat -> false // secret bats
                is Mob -> e.isAlive
                else -> false
            }
            if (mob && tileAt(e.x, e.z)?.let { it in tiles } == true) return true
        }
        return false
    }

    /** The map tile (x + z * 6) a point is in, or null in the gap between tiles or off the map. */
    private fun tileAt(x: Double, z: Double): Int? {
        val ox = Math.floorMod(Math.floor(x).toInt() + 200, 32); val oz = Math.floorMod(Math.floor(z).toInt() + 200, 32)
        if (ox > 30 || oz > 30) return null
        return index(Math.floorDiv(Math.floor(x).toInt() + 200, 32), Math.floorDiv(Math.floor(z).toInt() + 200, 32))
    }

    private fun index(tx: Int, tz: Int): Int? = if (tx in 0..5 && tz in 0..5) tx + tz * 6 else null
}
