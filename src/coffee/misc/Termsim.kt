package com.coffeeclient.misc

import com.coffeeclient.CoffeeClient
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.BoxStyle
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.renderBoundingBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.boss.wither.WitherBoss

/**
 * Termsim (the name only - nothing to do with terminals): where new things go, each its own
 * toggle with its settings under it.
 *
 *  - Wither Highlight: a box on the withers in F7/M7's boss - the bosses themselves (Maxor, Storm,
 *    Goldor, Necron), the only withers there.
 */
object Termsim : Module(
    name = "Termsim",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "Where new things go: Wither Highlight.",
) {
    private val witherHighlight by BooleanSetting("Wither Highlight", true, desc = "A box on the withers in the F7/M7 boss: Maxor, Storm, Goldor and Necron.")
    private val witherColor by ColorSetting("Wither Color", Color(255, 85, 255, 1f), true, desc = "The box's colour.")
        .withDependency { witherHighlight }
    private val witherStyle by SelectorSetting("Wither Style", BoxStyle.OUTLINE, desc = "Outline, filled, or both.")
        .withDependency { witherHighlight }
    private val witherThroughWalls by BooleanSetting("Wither Through Walls", true, desc = "Shows the box through blocks.")
        .withDependency { witherHighlight }

    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    /** On by default, once (turning it off sticks). */
    fun enableByDefault() {
        if (switchedOn) return
        switchedOn = true
        if (!enabled) toggle()
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }

    init {
        on<RenderExtractEvent> {
            if (!witherHighlight || !DungeonUtils.isFloor(7) || !DungeonUtils.inBoss) return@on
            for (e in CoffeeClient.mc.level?.entitiesForRendering() ?: return@on) {
                if (e is WitherBoss && e.isAlive && Witherborn.isBoss(e)) drawStyledBox(e.renderBoundingBox, witherColor, witherStyle, !witherThroughWalls)
            }
        }
    }
}
