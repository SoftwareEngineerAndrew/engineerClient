package com.engineerclient.p3sim

import com.engineerclient.EngineerClient.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.network.chat.Component
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * The title screen's GUI button: ADS's own front page, a plain screen (no world is opened or loaded). Five big
 * buttons, each with its icon in a box to its left: Terms, Solo Clear, Scenarios, Practice and Settings. Esc goes
 * back to [parent].
 */
class AdsHubScreen(private val parent: Screen?) : Screen(Component.literal(P3Sim.FULL_NAME)) {
    /**
     * A big button's icon: an item's texture ([tex], 16x16; an animated one is a strip of [frames], the first is
     * drawn), or the gear ([GEAR], drawn here). Textures, not ItemStacks: on the title screen no ItemStack can be
     * made (26.3 binds the item components with a world: "Components not bound yet").
     */
    private class Entry(val label: String, val color: Int, val tex: Identifier?, val frames: Int = 1)

    private val entries = listOf(
        Entry("Terms", 0xFFF0A21C.toInt(), Identifier.withDefaultNamespace("textures/block/command_block_front.png"), 4),
        Entry("Solo Clear", 0xFF5BBCE8.toInt(), Identifier.withDefaultNamespace("textures/item/ender_pearl.png")),
        Entry("Scenarios", 0xFFB4B4B4.toInt(), Identifier.withDefaultNamespace("textures/item/spyglass.png")),
        Entry("Practice", 0xFF213F9A.toInt(), Identifier.withDefaultNamespace("textures/item/paper.png")),
        Entry("Settings", 0xFF4A4A4A.toInt(), null),
    )

    override fun init() {
        super.init()
        val w = ICON + GAP + BUTTON_W
        val total = entries.size * ROW_H - (ROW_H - ICON)
        val x = width / 2 - w / 2
        val y0 = height / 2 - total / 2 + 10
        entries.forEachIndexed { i, e -> addRenderableWidget(BigButton(e, x, y0 + i * ROW_H, w)) }
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        val total = entries.size * ROW_H - (ROW_H - ICON)
        g.centeredText(font, "§6§l${P3Sim.FULL_NAME}", width / 2, height / 2 - total / 2 - 12, -1)
    }

    override fun onClose() { mc.gui.setScreen(parent) }

    /** One of the five: its icon box, then the coloured button with its label. */
    private class BigButton(val e: Entry, x: Int, y: Int, w: Int) : AbstractButton(x, y, w, ICON, Component.literal(e.label)) {
        // Not wired up yet.
        override fun onPress(input: InputWithModifiers) {}

        override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
            val hot = isHoveredOrFocused
            val base = if (hot) shade(e.color, 1.18) else e.color
            // The icon's box, in the button's colour, darker.
            bevel(g, x, y, ICON, ICON, shade(base, 0.55), hot)
            val ix = x + (ICON - 16) / 2; val iy = y + (ICON - 16) / 2
            if (e.tex != null) g.blit(RenderPipelines.GUI_TEXTURED, e.tex, ix, iy, 0f, 0f, 16, 16, 16, 16 * e.frames) else gear(g, ix, iy)
            // The button.
            val bx = x + ICON + GAP
            bevel(g, bx, y, BUTTON_W, ICON, base, hot)
            val font = mc.font
            val light = luma(base) > 0.6
            val tx = bx + BUTTON_W / 2 - font.width(e.label) / 2
            g.text(font, e.label, tx, y + (ICON - 8) / 2, if (light) 0xFF202020.toInt() else -1, !light)
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
    }

    companion object {
        private const val ICON = 24
        private const val GAP = 4
        private const val BUTTON_W = 160
        private const val ROW_H = ICON + 6

        /** A Minecraft-style block of [color]: a dark border, a lighter top edge and a darker bottom one; white border when [hot]. */
        private fun bevel(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Int, hot: Boolean) {
            g.fill(x, y, x + w, y + h, if (hot) -1 else 0xFF000000.toInt())
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, color)
            g.fill(x + 1, y + 1, x + w - 1, y + 2, shade(color, 1.35))
            g.fill(x + 1, y + 1, x + 2, y + h - 1, shade(color, 1.2))
            g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, shade(color, 0.6))
            g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, shade(color, 0.75))
        }

        /** [c] (ARGB) with its RGB scaled by [k], clamped. */
        private fun shade(c: Int, k: Double): Int {
            fun ch(s: Int) = (((c shr s) and 0xFF) * k).toInt().coerceIn(0, 255) shl s
            return (c and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
        }

        private fun luma(c: Int) = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)) / 255.0

        /**
         * The settings gear, 16x16 like an item: iron, lit from the top left. O outline, L light, M mid, D dark.
         * (The game has no gear texture of its own.)
         */
        private val GEAR = listOf(
            "......OOOO......",
            "...OO.OLLO.OO...",
            "..OLLOOLLMOOMO..",
            "..OLLLLLMMMMMO..",
            "...OLLLMMMMMO...",
            ".OOOLLOOOOMMOOO.",
            ".OLLLO....OMMDO.",
            ".OLLO......OMDO.",
            ".OLMO......ODDO.",
            ".OLMMO....ODDDO.",
            ".OOOMMOOOODDOOO.",
            "...OMMMDDDDDO...",
            "..OMMMMDDDDDDO..",
            "..OMDOODDOODDO..",
            "...OO.ODDO.OO...",
            "......OOOO......",
        )
        private val GEAR_COLORS = mapOf(
            'O' to 0xFF262626.toInt(), 'L' to 0xFFE2E2E2.toInt(), 'M' to 0xFFA9A9A9.toInt(), 'D' to 0xFF6B6B6B.toInt(),
        )

        private fun gear(g: GuiGraphicsExtractor, x: Int, y: Int) {
            GEAR.forEachIndexed { row, line ->
                line.forEachIndexed { col, ch -> GEAR_COLORS[ch]?.let { g.fill(x + col, y + row, x + col + 1, y + row + 1, it) } }
            }
        }
    }
}
