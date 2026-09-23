package com.engineerclient.misc

import com.odtheking.odin.events.GuiEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.Color.Companion.multiplyAlpha

/**
 * Odin has no "junk drop" list of its own (checked - nothing under features/impl/dungeon or
 * events/EventDispatcher's dungeonItemDrops covers this, that list is the opposite: things worth
 * grabbing). This starter set is the small, uncontroversial core of Catacombs trash - common mob
 * drops that pad out a dungeon inventory and never do anything - not a full sweep of every
 * situational item. Tell me what's missing or wrong and I'll adjust the set.
 */
object JunkHighlight : Module(
    name = "Junk Highlight",
    category = Category.custom("Blood Rush"),
    description = "Faint red backdrop on inventory slots holding known-useless dungeon drops.",
) {
    private val junkNames = hashSetOf(
        "Bone", "Rotten Flesh", "String", "Spider Eye", "Gunpowder", "Arrow",
        "Ink Sac", "Spider's Eye", "Wither Skeleton Skull", "Egg",
    )

    private val backdropColor = Colors.MINECRAFT_RED.multiplyAlpha(0.35f)

    init {
        on<GuiEvent.RenderSlot> {
            val item = slot.item
            if (item.isEmpty || item.hoverName.string !in junkNames) return@on
            guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, backdropColor.rgba)
        }
    }
}
