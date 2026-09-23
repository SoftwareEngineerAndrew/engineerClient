package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.renderBoundingBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items

/**
 * A Skeletor's spawn is a ring of bones closing in. This keeps the first bone of each ring and
 * discards the rest as they appear, so the spawn reads as a single bone moving.
 *
 * Working assumption: each bone is an armor stand carrying a BONE item (head or hand), and a
 * ring's bones spawn within [RING_RADIUS] of each other. If that's wrong, "Debug Spawns" prints
 * what's actually around a Skeletor when it spawns, so the real shape can be matched instead.
 */
object SimplifySkeletors : Module(
    name = "Simplify Skeletors",
    category = Category.custom("Blood Rush"),
    description = "Replaces a Skeletor's closing ring of bones with a single bone.",
) {
    private val highlight by BooleanSetting("Highlight Spawn", false, desc = "Blue box on each Skeletor's nametag stand (detection check).")
    private val debug by BooleanSetting("Debug Spawns", false, desc = "When a Skeletor or a bone ring appears, prints the nearby entities to chat so the simplifier can be fixed if the animation isn't what it expects.")

    private const val RING_RADIUS = 6.0

    /** The one bone kept per ring. */
    private val keptBones = mutableListOf<ArmorStand>()
    private val skeletors = mutableSetOf<Entity>()
    private val dumped = mutableSetOf<Int>()

    init {
        on<EntityEvent.SetItemSlot> {
            if (!DungeonUtils.inDungeons || stack.item != Items.BONE) return@on
            if (slot != EquipmentSlot.HEAD && slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND) return@on
            val stand = entity as? ArmorStand ?: return@on
            keptBones.removeIf { !it.isAlive }
            val ringBone = keptBones.firstOrNull { it !== stand && it.distanceTo(stand) <= RING_RADIUS }
            if (ringBone == null) {
                if (stand !in keptBones) {
                    keptBones += stand
                    if (debug) dumpAround(stand, "bone ring")
                }
            } else {
                stand.remove(Entity.RemovalReason.DISCARDED)
            }
        }

        on<TickEvent.End> {
            if (!DungeonUtils.inClear) return@on
            level.entitiesForRendering().forEach { e ->
                if (e !is ArmorStand || !e.isAlive || "Skeletor" !in e.name.string) return@forEach
                if (skeletors.add(e) && debug) dumpAround(e, "Skeletor nametag")
            }
            skeletors.removeIf { !it.isAlive }
        }

        on<RenderEvent.Extract> {
            if (!highlight) return@on
            skeletors.forEach { if (it.isAlive) drawStyledBox(it.renderBoundingBox, Colors.MINECRAFT_BLUE, 1) }
        }

        on<LevelEvent.Load> {
            keptBones.clear()
            skeletors.clear()
            dumped.clear()
        }
    }

    private fun dumpAround(center: Entity, what: String) {
        if (!dumped.add(center.id)) return
        val level = mc.level ?: return
        val near = level.entitiesForRendering().filter { it !== center && it.distanceTo(center) <= 8f }
        modMessage("§b[Skeletor debug] §f$what at ${center.blockPosition().toShortString()} §7(${near.size} nearby)")
        near.take(20).forEach { e ->
            val items = if (e is ArmorStand) listOf(EquipmentSlot.HEAD, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)
                .map { e.getItemBySlot(it) }.filter { !it.isEmpty }.joinToString(",") { it.item.toString() } else ""
            modMessage("§7 - ${e.type.toShortString()} \"${e.name.string}\" ${"%.1f".format(e.distanceTo(center))}m ${if (e.isInvisible) "inv " else ""}$items")
        }
    }
}
