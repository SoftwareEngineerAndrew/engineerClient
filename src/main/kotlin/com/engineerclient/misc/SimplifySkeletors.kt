package com.engineerclient.misc

import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.renderBoundingBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand

/**
 * Step 1 of 2: detection only. Highlights the invisible nametag armor stand Odin's own Highlight
 * module already keys "Skeletor" spawns off of (see Highlight.kt's dungeonMobSpawns set), so the
 * detection can be eyeballed in-game before step 2 touches the actual spawn animation.
 */
object SimplifySkeletors : Module(
    name = "Simplify Skeletors",
    category = Category.custom("Blood Rush"),
    description = "Step 1: highlights where a Skeletor is spawning (blue box) to verify detection before simplifying the spawn animation.",
) {
    private val spawns = mutableSetOf<Entity>()

    init {
        on<TickEvent.End> {
            if (!DungeonUtils.inClear) return@on
            level.entitiesForRendering().forEach { e ->
                if (e !is ArmorStand || !e.isAlive || "Skeletor" !in e.name.string) return@forEach
                spawns.add(e)
            }
            spawns.removeIf { !it.isAlive }
        }

        on<RenderEvent.Extract> {
            spawns.forEach { entity ->
                if (entity.isAlive) drawStyledBox(entity.renderBoundingBox, Colors.MINECRAFT_BLUE, 1)
            }
        }

        on<LevelEvent.Load> {
            spawns.clear()
        }
    }
}
