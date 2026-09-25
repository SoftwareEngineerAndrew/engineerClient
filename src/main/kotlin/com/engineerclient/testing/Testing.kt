package com.engineerclient.testing

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.Highlight
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB

/**
 * Work in progress, not for release: features being tried out before they earn a module of their
 * own. Remove this module before shipping.
 */
object Testing : Module(
    name = "Testing",
    category = Category.custom("Engineer Client"),
    description = "Features being tried out. Removed before release.",
) {

    private val starredSpawns by BooleanSetting(
        "Starred Mobs Spawn", false,
        desc = "Marks where each starred mob was first seen with a half-block box in Odin's Highlight colour, and records the spot per room.",
    )

    /** Where a starred mob was first seen: in the world, and in its room's own coordinates. */
    data class Spawn(val mob: String, val x: Double, val y: Double, val z: Double, val relative: BlockPos?, val atMs: Long)

    /**
     * Every starred spawn this run, by room name. Collected, not yet used — the point is to have
     * the data before deciding what to do with it. [relative] is the spot in the room's own
     * (rotated to north) coordinates, so the same room can be compared across runs; null when Odin
     * has not worked out the room's rotation yet.
     */
    val spawnsByRoom = LinkedHashMap<String, MutableList<Spawn>>()

    private val spawns = mutableListOf<Spawn>()
    private val seen = HashSet<Int>()

    // Odin's Highlight: which name tags count as starred mobs, and how a tag finds its mob.
    private val MOBS = listOf("Lurker", "Dreadlord", "Souleater", "Zombie", "Skeleton", "Skeletor", "Sniper", "Super Archer", "Spider", "Fels", "Withermancer", "Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer")
    private val STARRED = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$")

    init {
        on<LevelEvent.Load> { spawns.clear(); seen.clear(); spawnsByRoom.clear() }

        on<TickEvent.End> {
            if (!starredSpawns || !DungeonUtils.inClear) return@on
            for (tag in level.entitiesForRendering()) {
                if (tag !is ArmorStand || !tag.isAlive) continue
                val name = tag.name.string
                if (MOBS.none { it in name } || !STARRED.matches(name)) continue
                val mob = level.getEntities(tag, tag.boundingBox.move(0.0, -1.0, 0.0)) { isMob(it) }.firstOrNull() ?: continue
                if (!seen.add(mob.id)) continue
                record(mobName(name), mob)
            }
        }

        on<RenderEvent.Extract> {
            if (!starredSpawns || !DungeonUtils.inDungeons) return@on
            val colour = (Highlight.settings["Highlight color"] as? ColorSetting)?.value ?: Colors.WHITE
            val style = (Highlight.settings["Render Style"] as? SelectorSetting)?.value ?: 1
            // 1x1 on the floor, half a block tall, centred on where the mob stood.
            for (s in spawns) drawStyledBox(AABB(s.x - 0.5, s.y, s.z - 0.5, s.x + 0.5, s.y + 0.5, s.z + 0.5), colour, style, true)
        }
    }

    private fun record(name: String, mob: Entity) {
        val room = roomAt(mob.x, mob.z)
        val relative = room?.takeIf { it.rotation != null && it.clayPos != null }?.getRelativeCoords(mob.blockPosition())
        val spawn = Spawn(name, mob.x, mob.y, mob.z, relative, System.currentTimeMillis())
        spawns += spawn
        spawnsByRoom.getOrPut(room?.name ?: "Unknown") { mutableListOf() } += spawn
    }

    /** The map room a world position is in: tiles are 32 blocks apart, the first centred at -185. */
    private fun roomAt(x: Double, z: Double) = run {
        val tx = Math.floorDiv(Math.floor(x).toInt() + 200, 32)
        val tz = Math.floorDiv(Math.floor(z).toInt() + 200, 32)
        if (tx !in 0..5 || tz !in 0..5) null else DungeonScan.tiles[tx + tz * 6].room
    }

    /** " ✯ Skeleton Master 900k❤" -> "Skeleton Master". */
    private fun mobName(tag: String) = tag.substringAfter("✯ ").substringBeforeLast(' ').trim()

    // Odin's Highlight rule for what can sit under a starred tag.
    private fun isMob(e: Entity): Boolean = when (e) {
        is ArmorStand -> false
        is WitherBoss -> false
        is Player -> e.uuid.version() == 2 && e != mc.player
        else -> !e.isInvisible
    }
}
