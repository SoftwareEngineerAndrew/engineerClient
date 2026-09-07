package com.bloodrushwaypoints.rotation

import com.bloodrushwaypoints.BrwMod
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.render.roundedFill
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.ui.widget.CustomGUIImpl
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * Marks the player you should leap to inside the Spirit Leap menu.
 *
 * Draws over Odin's own leap menu — `CustomGUIImpl` runs every registered handler in turn, and BRW
 * registers after Odin, so this lands on top of Odin's boxes without touching its code. Odin's Leap
 * Menu module has to be on: the vanilla chest would need the screen's protected `leftPos`/`topPos`
 * to place a ring on a slot, which is a mixin BRW does not need to own. If it is off we say so once
 * rather than drawing nothing.
 *
 * Soft ring = this is who you want. Solid ring = they are in position, click now.
 */
object LeapHighlight {

    private val SOFT = Color(255, 190, 60, 0.55f)
    private val READY = Color(90, 235, 120, 0.95f)
    private var warnedNoMenu = false

    fun register() {
        CustomGUIImpl.register(
            CustomGUIImpl.HandlerSet(
                enabled = { P3Rotation.enabled && P3Rotation.highlightLeaps && leapScreen() != null },
                render = fun ScreenEvent.Render.(): Any {
                    BrwMod.safely("leap highlight") { draw(guiGraphics) }
                    // Never claim the event: Odin still has to draw its own menu, and returning
                    // false leaves the vanilla screen alone when Odin's menu is off.
                    return false
                },
            )
        )
    }

    private fun leapScreen(): AbstractContainerScreen<*>? {
        val screen = BrwMod.mc.screen as? AbstractContainerScreen<*> ?: return null
        if (!screen.title.string.equalsOneOf("Spirit Leap", "Teleport to Player")) return null
        return screen
    }

    private fun draw(gfx: GuiGraphicsExtractor) {
        val target = LeapSignal.current() ?: return
        if (!LeapMenu.enabled) {
            if (!warnedNoMenu) {
                warnedNoMenu = true
                BrwMod.chat("§8[§6BRW§8]§7 leap highlight needs Odin's §fLeap Menu§7 module switched on.")
            }
            return
        }
        drawOnOdinMenu(gfx, target.ign, if (target.ready) READY else SOFT)
    }

    /**
     * Odin lays its four boxes out around the screen centre: quadrant order is top-left,
     * top-right, bottom-left, bottom-right, each [LeapMenu.BOX_WIDTH] x [LeapMenu.BOX_HEIGHT]
     * offset 24px from the middle. Mirrored here rather than read from Odin, whose render scale
     * is private — a custom scale shifts our ring off its box, which is cosmetic.
     */
    private fun drawOnOdinMenu(gfx: GuiGraphicsExtractor, ign: String, color: Color) {
        val index = DungeonUtils.leapTeammates.indexOfFirst { it.name.equals(ign, ignoreCase = true) }
        if (index < 0) return
        val halfW = BrwMod.mc.window.guiScaledWidth / 2
        val halfH = BrwMod.mc.window.guiScaledHeight / 2
        val col = index % 2
        val row = index / 2
        val x = if (col == 0) halfW - 24 - LeapMenu.BOX_WIDTH else halfW + 24
        val y = if (row == 0) halfH - 24 - LeapMenu.BOX_HEIGHT else halfH + 24
        outline(gfx, x, y, x + LeapMenu.BOX_WIDTH, y + LeapMenu.BOX_HEIGHT, color, 9)
    }

    private fun outline(gfx: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Color, radius: Int) {
        // A translucent wash plus a hard ring: the wash reads at a glance, the ring survives
        // being drawn over Odin's own coloured box.
        gfx.roundedFill(x0, y0, x1, y1, color.withAlpha(0.18f).rgba, radius, color.rgba, 2.5f)
    }
}
