package com.adm.features.impl.boss

import com.adm.events.BlockUpdateEvent
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.render.drawText
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.phys.Vec3

object TerracottaTimer : Module(
    name = "Terracotta Timer",
    description = "Displays the time until the terracotta respawns."
) {
    private class Pot(val key: Long, val label: Vec3, @Volatile var ticks: Int) {
        var shownTicks = -1
        var text = ""
    }

    // Written by block updates on the client thread and counted down on the network thread, so it is only ever swapped whole.
    @Volatile
    private var pots = emptyArray<Pot>()
    private var giants = false

    init {
        on<BlockUpdateEvent> {
            if (giants || updated.block !is FlowerPotBlock || !DungeonUtils.inBoss) return@on
            val floor = DungeonUtils.floor ?: return@on
            if (floor.floorNumber != 6) return@on
            val key = pos.asLong()
            val current = pots
            for (p in current) if (p.key == key) return@on
            pots = current + Pot(key, Vec3.atCenterOf(pos), if (floor.isMM) 240 else 300)
        }

        on<TickEvent.Server> {
            val current = pots
            if (current.isEmpty()) return@on
            var expired = 0
            for (p in current) if (--p.ticks <= 0) expired++
            if (expired > 0) pots = current.filter { it.ticks > 0 }.toTypedArray()
        }

        on<RenderExtractEvent> {
            val current = pots
            if (current.isEmpty() || !DungeonUtils.inBoss || DungeonUtils.floor?.floorNumber != 6) return@on
            for (p in current) {
                val t = p.ticks
                if (t <= 0) continue
                if (t != p.shownTicks) {
                    p.shownTicks = t
                    val color = if (t > 100) 'a' else if (t > 40) '6' else 'c'
                    p.text = "§$color${t / 20}.${((t % 20) * 5).toString().padStart(2, '0')}s"
                }
                drawText(p.text, p.label, 2f, false)
            }
        }

        on<MessageEvent.Chat> {
            if (message != "[BOSS] Sadan: ENOUGH!" || DungeonUtils.floor?.floorNumber != 6) return@on
            giants = true
            pots = emptyArray()
        }

        on<LevelEvent.Load> {
            pots = emptyArray()
            giants = false
        }
    }
}
