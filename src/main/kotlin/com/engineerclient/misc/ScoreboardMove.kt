package com.engineerclient.misc

import com.engineerclient.mixin.GuiSidebarInvoker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.numbers.StyledFormat
import net.minecraft.world.scores.Objective

/**
 * The movable scoreboard: vanilla's sidebar, drawn by its own code, only somewhere else. The
 * sidebar call vanilla makes is skipped (ScoreboardSidebarMixin), keeping its objective; the HUD
 * ([RandomStuff]'s "Scoreboard") then makes the same call with the drawing moved so the sidebar's
 * box starts at the HUD's corner. So the line hider and everything else about it still apply.
 */
object ScoreboardMove {
    private val mc: Minecraft get() = Minecraft.getInstance()

    /** The HUD is on: vanilla's own sidebar call is skipped. */
    @JvmField @Volatile var active = false
    /** The HUD's own call is going through. */
    @JvmField var drawing = false
    /** The objective vanilla was about to draw this frame (null: no sidebar). */
    @JvmField var objective: Objective? = null

    @JvmStatic fun frameStart() { objective = null }

    /** Draws the sidebar with its box's top left at 0, 0; returns the box's size (0, 0: none). */
    fun draw(gfx: GuiGraphicsExtractor): Pair<Int, Int> {
        val objective = objective ?: return 0 to 0
        val (w, h, left, top) = box(gfx, objective) ?: return 0 to 0
        gfx.pose().pushMatrix()
        gfx.pose().translate(-left.toFloat(), -top.toFloat())
        drawing = true
        try { (mc.gui as GuiSidebarInvoker).`ec$displayScoreboardSidebar`(gfx, objective) }
        finally { drawing = false; gfx.pose().popMatrix() }
        return w to h
    }

    /** Where vanilla puts the sidebar's box (Gui.displayScoreboardSidebar): width, height, left, top. */
    private fun box(gfx: GuiGraphicsExtractor, objective: Objective): List<Int>? {
        val scoreboard = objective.scoreboard
        val format = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT)
        val entries = ScoreboardLines.visibleEntries(scoreboard, objective).filter { !it.isHidden }
            .sortedWith(compareByDescending<net.minecraft.world.scores.PlayerScoreEntry> { it.value() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() })
            .take(15)
        val font = mc.font
        val spacer = font.width(": ")
        var max = if (ScoreboardLines.hidesTitle()) 0 else font.width(objective.displayName)
        for (e in entries) {
            val score = font.width(e.formatValue(format))
            max = maxOf(max, font.width(ScoreboardLines.lineText(scoreboard, e)) + if (score > 0) spacer + score else 0)
        }
        val k = entries.size * 9
        val bottom = gfx.guiHeight() / 2 + k / 3
        return listOf(max + 4, k + 10, gfx.guiWidth() - max - 5, bottom - k - 10)
    }
}
