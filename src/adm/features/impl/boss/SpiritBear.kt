package com.adm.features.impl.boss

import com.adm.events.BlockUpdateEvent
import com.adm.events.LevelEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.Colors
import com.adm.utils.render.textDim
import com.adm.utils.skyblock.dungeon.DungeonUtils
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks

object SpiritBear : Module(
    name = "Spirit Bear",
    description = "Displays the current state of Spirit Bear."
) {
    private val hud by HUD(name, "Displays the current state of Spirit Bear in the HUD.", false) { example ->
        if (example) return@HUD textDim("§6Bear: §e1.45s", 0, 0, Colors.WHITE)
        val floor = DungeonUtils.floor
        if (floor?.floorNumber != 4 || !DungeonUtils.inBoss) return@HUD 0 to 0
        textDim(label(floor.isMM), 0, 0, Colors.WHITE)
    }

    private const val LAMP_Y = 77
    private const val SPAWN_TICKS = 68
    private val BEAR_LAMP = BlockPos.asLong(7, LAMP_Y, 34)

    // The lamps light one per kill; the last one lit starts the bear's spawn.
    private val normalLamps = lamps(
        -3, 33, -9, 31, -16, 26, -20, 20, -23, 13, -24, 6, -24, 0, -22, -7, -18, -13, -12, -19, -5, -22, 1, -24, 8, -24,
        14, -23, 21, -19, 27, -14, 31, -8, 33, -1, 34, 5, 33, 12, 31, 19, 27, 25, 20, 30, 14, 33, 7, 34
    )
    private val masterLamps = lamps(
        -2, 33, -7, 32, -13, 28, -17, 24, -21, 18, -23, 13, -24, 7, -24, 2, -23, -4, -21, -9, -17, -14, -12, -19, -6, -22,
        -1, -23, 5, -24, 10, -24, 16, -22, 21, -19, 27, -15, 30, -10, 32, -5, 34, 1, 34, 7, 33, 12, 31, 18, 28, 23, 23, 28,
        18, 31, 12, 33, 7, 34
    )

    private fun lamps(vararg xz: Int) = LongOpenHashSet(xz.size / 2).apply {
        for (i in xz.indices step 2) add(BlockPos.asLong(xz[i], LAMP_Y, xz[i + 1]))
    }

    // -1 before the last lamp is lit, 0 once the bear is up, otherwise ticks until it spawns.
    private var spawnTicks = -1
    private var lit = 0

    private var labelKey = Long.MIN_VALUE
    private var labelText = ""

    private fun label(master: Boolean): String {
        val key = (spawnTicks.toLong() shl 32) or (lit.toLong() shl 1) or (if (master) 1L else 0L)
        if (key != labelKey) {
            labelKey = key
            labelText = "§6Bear: " + when {
                spawnTicks < 0 -> "§d$lit/${if (master) masterLamps.size else normalLamps.size}"
                spawnTicks > 0 -> "§e${spawnTicks / 20}.${((spawnTicks % 20) * 5).toString().padStart(2, '0')}s"
                else -> "§aAlive!"
            }
        }
        return labelText
    }

    init {
        on<BlockUpdateEvent> {
            if (pos.y != LAMP_Y) return@on
            val floor = DungeonUtils.floor
            if (floor?.floorNumber != 4 || !DungeonUtils.inBoss) return@on
            val key = pos.asLong()
            val lamps = if (floor.isMM) masterLamps else normalLamps
            if (key !in lamps) return@on
            val now = updated.block
            val before = old.block
            if (now === Blocks.SEA_LANTERN && before === Blocks.COAL_BLOCK) {
                if (lit < lamps.size) lit++
                if (key == BEAR_LAMP) spawnTicks = SPAWN_TICKS
            } else if (now === Blocks.COAL_BLOCK && before === Blocks.SEA_LANTERN) {
                if (lit > 0) lit--
                if (key == BEAR_LAMP) spawnTicks = -1
            }
        }

        on<TickEvent.Server> {
            if (spawnTicks > 0) spawnTicks--
        }

        on<LevelEvent.Load> {
            lit = 0
            spawnTicks = -1
        }
    }
}
