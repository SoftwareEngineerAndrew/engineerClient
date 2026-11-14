package com.engineerclient.render

import com.engineerclient.mixin.HudSidebarInvoker
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.Entity
import net.minecraft.world.scores.Objective

object ScoreboardNametags : Module(
    name = "Scoreboard and Nametags",
    category = Category.RENDER,
    description = "Moves the scoreboard, hides your name and server id, and tweaks nametags.",
    key = null,
) {
    private val scoreboardDropdown by DropdownSetting("Scoreboard", desc = "Shows settings for the scoreboard.")
    private val scoreboardHud by HUD("Scoreboard HUD", "Moves and scales the scoreboard. Off: it stays where the game puts it.", true, 400, 100, 1f) { example ->
        val size = drawSidebar(this)
        if (example && size.first == 0) {
            fill(0, 0, 80, 60, 0x66000000)
            text(mc.font, "Scoreboard", 18, 2, -1, false)
            return@HUD 80 to 60
        }
        size
    }.withDependency { scoreboardDropdown }
    private val customOpacity by BooleanSetting("Custom Opacity", false, desc = "Uses your own scoreboard background opacity.").withDependency { scoreboardDropdown }
    private val opacity by NumberSetting("Background Opacity", 30, 0..100, 5, desc = "Opacity of the scoreboard background.", unit = "%").withDependency { scoreboardDropdown && customOpacity }

    private val namesDropdown by DropdownSetting("Names", desc = "Shows settings for hiding your name.")
    val nameChanger by BooleanSetting("Name Changer", false, desc = "Shows a fake name instead of yours everywhere text is drawn.").withDependency { namesDropdown }
    val fakeName by StringSetting("Fake Name", "Player", 16, desc = "The name shown instead of yours.", placeholder = "").withDependency { namesDropdown && nameChanger }

    private val serverDropdown by DropdownSetting("Server ID", desc = "Shows settings for hiding the server id.")
    val serverIdHider by BooleanSetting("Server ID Hider", false, desc = "Hides the server id and lobby address on the sidebar.").withDependency { serverDropdown }
    val serverReplacement by StringSetting("Server Text", "m1A", 16, desc = "Shown instead of the server id.", placeholder = "").withDependency { serverDropdown && serverIdHider }
    val ipReplacement by StringSetting("Address Text", "", 32, desc = "Shown instead of the lobby address; empty hides it.", placeholder = "").withDependency { serverDropdown && serverIdHider }
    val hideIp by BooleanSetting("Hide Address", true, desc = "Also replace www.hypixel.net.").withDependency { serverDropdown && serverIdHider }

    private val nametagsDropdown by DropdownSetting("Nametags", desc = "Shows settings for nametags.")
    private val showOwn by BooleanSetting("Show Own Nametag", false, desc = "Draws your own nametag above your head.").withDependency { nametagsDropdown }
    private val noBackground by BooleanSetting("Hide Nametag Background", false, desc = "Removes the black box behind nametags.").withDependency { nametagsDropdown }
    private val shadow by BooleanSetting("Shadowed Nametag", false, desc = "Draws nametag text with a shadow.").withDependency { nametagsDropdown }
    private val hideDinnerbone by BooleanSetting("Hide Dinnerbone Nametag", false, desc = "Hides the nametag of anything named Dinnerbone.").withDependency { nametagsDropdown }

    fun namesOn(): Boolean = enabled && nameChanger
    fun serverOn(): Boolean = enabled && serverIdHider

    fun showsOwn(entity: Entity): Boolean = enabled && showOwn && entity === mc.player
    fun hidesDinnerbone(entity: Entity): Boolean = enabled && hideDinnerbone && entity.customName?.string.equals("Dinnerbone", ignoreCase = true)
    fun background(color: Int): Int = if (enabled && noBackground) 0 else color
    fun shadow(original: Boolean): Boolean = original || (enabled && shadow)

    @JvmStatic
    fun sidebarFill(color: Int): Int =
        if (enabled && customOpacity) (color and 0xFFFFFF) or ((opacity * 255 / 100) shl 24) else color

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

    init {
        on<TickEvent.End> { moving = enabled && scoreboardHud.enabled }
    }

    override fun onDisable() {
        super.onDisable()
        moving = false
    }
}
