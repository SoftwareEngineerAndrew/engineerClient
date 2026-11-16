package com.engineerclient.misc

import com.engineerclient.EngineerClient.mc
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.events.LevelEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.impl.dungeon.SecretClicked
import com.adm.utils.Color
import com.adm.utils.render.drawFilledBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Secrets' Items section: boxes the dropped dungeon items worth picking up (keys, revive stones, spirit leaps and so on),
 * through walls. The colour is the pickup state: red when out of reach, gold while the item is
 * still too fresh to pick up, green once it can be taken. Nothing is drawn beyond 20 blocks.
 *
 * Tracked items are not drawn as items any more, only as the box; see
 * [com.engineerclient.mixin.OwnArrowsHideMixin], which asks [hides].
 */
object ItemHighlight {
    private val dropdown = DropdownSetting("Items", desc = "Boxes dropped secret items.")
    private val on = BooleanSetting("Item Highlight", false, desc = "Boxes keys, revive stones, leaps and other dropped dungeon items through walls.").withDependency { dropdown.value }
    private val farColor = ColorSetting("Far Color", Color(255, 85, 85, 1f), true, desc = "More than 3.5 blocks away.").withDependency { dropdown.value && on.value }
    private val freshColor = ColorSetting("Fresh Color", Color(255, 170, 0, 1f), true, desc = "Within 3.5 blocks but not pickable yet.").withDependency { dropdown.value && on.value }
    private val readyColor = ColorSetting("Ready Color", Color(85, 255, 85, 1f), true, desc = "Within 3.5 blocks and pickable.").withDependency { dropdown.value && on.value }

    private val names = setOf(
        "Revive Stone", "Trap", "Decoy", "Inflatable Jerry", "Defuse Kit", "Dungeon Chest Key",
        "Treasure Talisman", "Architect's First Draft", "Spirit Leap", "Healing VIII Splash Potion",
        "Training Weights", "Candycomb",
    )

    private val tracked: MutableSet<ItemEntity> = Collections.newSetFromMap(IdentityHashMap())

    private fun active() = SecretClicked.enabled && on.value

    /** True when [item] is boxed instead of drawn. */
    fun hides(item: ItemEntity): Boolean = active() && item in tracked

    fun wanted(item: ItemEntity): Boolean =
        DungeonUtils.inClear && item.item.hoverName.string in names

    private fun colorFor(item: ItemEntity): Color? {
        val me = mc.player ?: return null
        val distance = item.position().distanceTo(me.position())
        return when {
            distance > 20.0 -> null
            distance > 3.5 -> farColor.value
            // Secret items have a 5 tick pickup delay, not the usual 10.
            item.tickCount > 6 -> readyColor.value
            else -> freshColor.value
        }
    }

    fun install() {
        for (s in listOf(dropdown, on, farColor, freshColor, readyColor)) SecretClicked.registerSetting(s)

        on<TickEvent.End> {
            if (!active()) return@on tracked.clear()
            val level = mc.level ?: return@on
            tracked.removeIf { it.isRemoved }
            for (entity in level.entitiesForRendering()) {
                if (entity is ItemEntity && entity !in tracked && wanted(entity)) {
                    tracked.add(entity)
                    // No drop animation: the box sits where the item appeared instead of flying out and bouncing.
                    entity.deltaMovement = Vec3.ZERO
                }
            }
        }

        on<LevelEvent.Load> { tracked.clear() }

        on<RenderExtractEvent> {
            if (!active()) return@on
            for (item in tracked) {
                val color = colorFor(item) ?: continue
                val box = item.getDimensions(item.pose).makeBoundingBox(item.x, item.y, item.z).inflate(0.1).move(0.0, 0.05, 0.0)
                drawFilledBox(box, color, depth = false)
            }
        }
        EventBus.subscribe(this)
    }
}
