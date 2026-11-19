package com.engineerclient.misc

import com.engineerclient.EngineerClient.mc
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.events.LevelEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import com.adm.features.impl.dungeon.SecretClicked
import com.adm.utils.Color
import com.adm.utils.NameMemo
import com.adm.utils.render.BoxBatch
import com.adm.utils.renderX
import com.adm.utils.renderY
import com.adm.utils.renderZ
import com.adm.utils.render.drawBoxes
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Secrets' Items section: dropped dungeon items worth picking up are drawn as a box through walls instead of as the item.
 * Red out of reach, gold while still too fresh to pick up, green once it can be taken; nothing past 20 blocks.
 * [com.engineerclient.mixin.OwnArrowsHideMixin] asks [hides].
 */
object ItemHighlight {
    private val dropdown = DropdownSetting("Items", desc = "Boxes dropped secret items.")
    private val on = BooleanSetting("Item Highlight", false, desc = "Boxes keys, revive stones, leaps and other dropped dungeon items through walls.").withDependency { dropdown.value }
    private val farColor = ColorSetting("Far Color", Color(255, 85, 85, 1f), true, desc = "More than 3.5 blocks away.").withDependency { dropdown.value && on.value }
    private val freshColor = ColorSetting("Fresh Color", Color(255, 170, 0, 1f), true, desc = "Within 3.5 blocks but not pickable yet.").withDependency { dropdown.value && on.value }
    private val readyColor = ColorSetting("Ready Color", Color(85, 255, 85, 1f), true, desc = "Within 3.5 blocks and pickable.").withDependency { dropdown.value && on.value }

    private val secretItems = hashSetOf(
        "Revive Stone", "Trap", "Decoy", "Inflatable Jerry", "Defuse Kit", "Dungeon Chest Key",
        "Treasure Talisman", "Architect's First Draft", "Spirit Leap", "Healing VIII Splash Potion",
        "Training Weights", "Candycomb",
    )
    private val secretNamed = NameMemo { it.string in secretItems }

    private val boxed: MutableSet<ItemEntity> = Collections.newSetFromMap(IdentityHashMap())
    private val boxes = BoxBatch()

    private fun active() = SecretClicked.enabled && on.value

    /** True when [item] is boxed instead of drawn. */
    fun hides(item: ItemEntity): Boolean = active() && item in boxed

    fun wanted(item: ItemEntity): Boolean = DungeonUtils.inClear && secretNamed[item, item.item.hoverName]

    fun install() {
        for (s in listOf(dropdown, on, farColor, freshColor, readyColor)) SecretClicked.registerSetting(s)

        on<TickEvent.End> {
            if (!active()) {
                if (boxed.isNotEmpty()) boxed.clear()
                return@on
            }
            val level = mc.level ?: return@on
            boxed.removeIf { it.isRemoved }
            for (e in level.entitiesForRendering()) {
                if (e !is ItemEntity || e in boxed || !wanted(e)) continue
                boxed.add(e)
                // No drop animation: the box sits where the item appeared instead of flying out and bouncing.
                e.deltaMovement = Vec3.ZERO
            }
        }

        on<LevelEvent.Load> { boxed.clear() }

        on<RenderExtractEvent> {
            if (!active() || boxed.isEmpty()) return@on
            val me = mc.player ?: return@on
            boxes.clear()
            for (item in boxed) {
                val dist = item.position().distanceTo(me.position())
                if (dist > 20.0) continue
                val color = when {
                    dist > 3.5 -> farColor.value
                    // Secret items have a 5 tick pickup delay, not the usual 10.
                    item.tickCount > 6 -> readyColor.value
                    else -> freshColor.value
                }
                val size = item.getDimensions(item.pose)
                val half = size.width() / 2f
                val x = item.renderX
                val y = item.renderY
                val z = item.renderZ
                boxes.filled(
                    AABB(x - half - 0.1, y - 0.1 + 0.05, z - half - 0.1, x + half + 0.1, y + size.height() + 0.1 + 0.05, z + half + 0.1),
                    color, depth = false,
                )
            }
            drawBoxes(boxes)
        }
        SubscribeWhile.add(this, { boxed.clear() }) { SecretClicked.enabled && on.value }
    }
}
