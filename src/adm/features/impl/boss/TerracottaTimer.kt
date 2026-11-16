package com.adm.features.impl.boss

import com.adm.events.BlockUpdateEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.center
import com.adm.utils.render.drawText
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.toFixed
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.FlowerPotBlock
import java.util.concurrent.CopyOnWriteArrayList

object TerracottaTimer : Module(
    name = "Terracotta Timer",
    description = "Displays the time until the terracotta respawns."
) {
    private var terracottaSpawning = CopyOnWriteArrayList<Terracotta>()
    private data class Terracotta(val pos: BlockPos, var time: Float)

    init {
        on<BlockUpdateEvent> {
            if (DungeonUtils.isFloor(6) && DungeonUtils.inBoss && updated.block is FlowerPotBlock && terracottaSpawning.none { it.pos == pos })
                terracottaSpawning.add(Terracotta(pos, if (DungeonUtils.floor?.isMM == true) 12f else 15f))
        }

        on<TickEvent.Server> {
            terracottaSpawning.removeAll {
                it.time -= .05f
                it.time <= 0
            }
        }

        on<RenderExtractEvent> {
            if (!DungeonUtils.inBoss || !DungeonUtils.isFloor(6) || terracottaSpawning.isEmpty()) return@on
            terracottaSpawning.forEach {
                drawText("§${getColor(it.time)}${it.time.toFixed()}s", it.pos.center, 2f, false)
            }
        }
    }

    private fun getColor(time: Float): Char {
        return when {
            time > 5f -> 'a'
            time > 2f -> '6'
            else -> 'c'
        }
    }
}