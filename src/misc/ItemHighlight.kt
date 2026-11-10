package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.util.Mth
import net.minecraft.world.entity.item.ItemEntity
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Boxes the dropped dungeon items worth picking up (keys, revive stones, spirit leaps and so on),
 * through walls. The colour is the pickup state: red when out of reach, gold while the item is
 * still too fresh to pick up, green once it can be taken. Nothing is drawn beyond 20 blocks.
 *
 * Tracked items are not drawn as items any more, only as the box; see
 * [com.engineerclient.mixin.OwnArrowsHideMixin], which asks [hides].
 */
object ItemHighlight : Module(
    name = "Item Highlight",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Highlights important dropped dungeon items through walls: red out of reach, gold when close but not yet pickable, green when pickable.",
    key = null,
) {
    private val farColor by ColorSetting("Far Color", Color(255, 85, 85, 1f), true, desc = "More than 3.5 blocks away.")
    private val freshColor by ColorSetting("Fresh Color", Color(255, 170, 0, 1f), true, desc = "Within 3.5 blocks but the item has not existed long enough to be picked up.")
    private val readyColor by ColorSetting("Ready Color", Color(85, 255, 85, 1f), true, desc = "Within 3.5 blocks and pickable.")

    private val names = setOf(
        "Revive Stone", "Trap", "Decoy", "Inflatable Jerry", "Defuse Kit", "Dungeon Chest Key",
        "Treasure Talisman", "Architect's First Draft", "Spirit Leap", "Healing VIII Splash Potion",
        "Training Weights", "Candycomb",
    )

    private val tracked: MutableSet<ItemEntity> = Collections.newSetFromMap(IdentityHashMap())

    /** True when [item] is boxed instead of drawn. */
    fun hides(item: ItemEntity): Boolean = enabled && item in tracked

    private fun wanted(item: ItemEntity): Boolean =
        DungeonUtils.inClear && item.item.hoverName.string in names

    private fun colorFor(item: ItemEntity): Color? {
        val me = mc.player ?: return null
        val distance = item.position().distanceTo(me.position())
        return when {
            distance > 20.0 -> null
            distance > 3.5 -> farColor
            item.tickCount > 11 -> readyColor
            else -> freshColor
        }
    }

    init {
        on<TickEvent.End> {
            val level = mc.level ?: return@on
            tracked.removeIf { it.isRemoved }
            for (entity in level.entitiesForRendering()) {
                if (entity is ItemEntity && entity !in tracked && wanted(entity)) tracked.add(entity)
            }
        }

        on<LevelEvent.Load> { tracked.clear() }

        on<RenderExtractEvent> {
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false).toDouble()
            for (item in tracked) {
                val color = colorFor(item) ?: continue
                val x = Mth.lerp(pt, item.xOld, item.x)
                val y = Mth.lerp(pt, item.yOld, item.y)
                val z = Mth.lerp(pt, item.zOld, item.z)
                val box = item.getDimensions(item.pose).makeBoundingBox(x, y, z).inflate(0.1).move(0.0, 0.05, 0.0)
                drawFilledBox(box, color, depth = false)
            }
        }
    }
}
