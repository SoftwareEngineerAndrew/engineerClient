package com.engineerclient.render

import com.engineerclient.mixin.HudSidebarInvoker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import com.adm.utils.NameMemo
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import java.util.Optional
import net.minecraft.world.scores.Objective

/** The scoreboard and nametag parts of [Visibility]: its settings, this state and the hooks the mixins call. */
object ScoreboardNametags {
    private val mc: Minecraft get() = Minecraft.getInstance()
    private val v = Visibility
    private val enabled get() = v.enabled
    val fakeName get() = v.fakeName
    val serverReplacement get() = v.serverReplacement
    val ipReplacement get() = v.ipReplacement
    val hideIp get() = v.hideIp

    fun namesOn(): Boolean = enabled && v.nameChanger
    fun serverOn(): Boolean = enabled && v.serverIdHider

    @JvmStatic
    fun ownTag(entity: Entity): Boolean = enabled && v.showOwn && entity === mc.player

    @JvmStatic
    fun dinnerbone(entity: Entity): Boolean {
        if (!enabled || !v.hideDinnerbone) return false
        val name = entity.customName ?: return false
        return dinnerbones[entity, name]
    }

    private val dinnerbones = NameMemo { it.string.equals("Dinnerbone", ignoreCase = true) }
    private val DIALOGUE_END = Regex("[?!.]$")

    /** Mobs' gold lines ("What was that?"), bosses' bold dark red ones, and "JERRY! RUN!" / "???" over fleeing mobs. */
    @JvmStatic
    fun hidesMobDialogue(entity: Entity): Boolean {
        if (!enabled || !v.hideMobDialogue || entity !is ArmorStand || !DungeonUtils.inDungeons) return false
        val name = entity.customName ?: return false
        return dialogue[entity, name]
    }

    private val dialogue = NameMemo(::isDialogue, 1024)

    fun clearMemos() {
        dinnerbones.clear()
        dialogue.clear()
    }

    private fun isDialogue(name: Component): Boolean {
        val text = name.string
        if (text == "JERRY! RUN!" || text == "???") return true
        var style: Style? = null
        name.visit({ s, part -> if (part.isNotBlank()) Optional.of(s) else Optional.empty() }, Style.EMPTY).ifPresent { style = it }
        val color = style?.color?.serialize() ?: return false
        return color == "gold" && style?.isBold != true && (DIALOGUE_END.containsMatchIn(text) || text == "Oh") ||
            color == "dark_red" && style?.isBold == true && text.any { it.isLetter() }
    }

    @JvmStatic fun tagBackdrop(): Boolean = enabled && v.noBackground
    @JvmStatic fun tagShadow(): Boolean = enabled && v.shadow

    @JvmStatic
    fun sidebarFill(color: Int): Int =
        if (enabled && v.customOpacity) (color and 0xFFFFFF) or ((v.opacity * 255 / 100) shl 24) else color

    // Vanilla's sidebar call is skipped while the HUD is on; the HUD then makes the same call moved to its corner.
    @JvmField @Volatile var moving = false
    @JvmField var drawing = false
    @JvmField var objective: Objective? = null
    private var seen: IntArray? = null
    private var drawn: IntArray? = null

    @JvmStatic fun frameStart() { objective = null }

    @JvmStatic fun fillSeen(x1: Int, y1: Int, x2: Int, y2: Int) {
        val b = seen
        seen = if (b == null) intArrayOf(x1, y1, x2, y2) else intArrayOf(minOf(b[0], x1), minOf(b[1], y1), maxOf(b[2], x2), maxOf(b[3], y2))
    }

    fun hud(gfx: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val size = drawSidebar(gfx)
        if (example && size.first == 0) {
            gfx.fill(0, 0, 80, 60, 0x66000000)
            gfx.text(mc.font, "Scoreboard", 18, 2, -1, false)
            return 80 to 60
        }
        return size
    }

    private fun drawSidebar(gfx: GuiGraphicsExtractor): Pair<Int, Int> {
        val objective = objective ?: return 0 to 0
        val box = drawn ?: intArrayOf(gfx.guiWidth() - 100, gfx.guiHeight() / 2 - 60, gfx.guiWidth(), gfx.guiHeight() / 2 + 60)
        gfx.pose().pushMatrix()
        gfx.pose().translate(-box[0].toFloat(), -box[1].toFloat())
        drawing = true
        seen = null
        try { (mc.gui.hud as HudSidebarInvoker).`ec$displayScoreboardSidebar`(gfx, objective) }
        finally { drawing = false; gfx.pose().popMatrix(); drawn = seen }
        return (box[2] - box[0]) to (box[3] - box[1])
    }
}
