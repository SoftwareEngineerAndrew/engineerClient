package com.engineerclient.leap

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.features.impl.boss.termsim.NumbersSim
import com.odtheking.odin.features.impl.boss.termsim.StartGUI
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import net.minecraft.client.gui.screens.Screen
import com.odtheking.odin.utils.render.roundedFill
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.resources.Identifier

/**
 * Additions to Odin's Leap Menu that ship with engineerClient, so they work on anyone's Odin: they
 * patch Odin's leap menu from outside (see the mixins in `mixin/odin`) rather than living in a
 * modified copy of it. Odin's Leap Menu has to be on for any of it, as it is the menu being added to.
 *
 * Map Leap (the dungeon map as the leap menu, in the clear) is not offered: its drawing and picking
 * are kept in [LeapMap], and the wiring it needs is described at the bottom of this file.
 */
object LeapExtras : Module(
    name = "Leap Extras",
    category = Category.custom("Engineer Client"),
    description = "Adds to Odin's Leap Menu: a click delay when it opens, and an outline of where each person will be. Also: crouch + left click with Infinileap for the numbers termsim.",
    toggled = true,
) {
    private val clickDelay by NumberSetting("Click Delay", 1, 0, 10, 1, desc = "Ticks after the leap menu opens during which mouse clicks are ignored, so letting go of the right-click that opened it can't leap you by accident.", unit = "t")

    private val leapOutline by BooleanSetting("Leap Outline", false, desc = "Draws a very faint rectangle where each person in the leap menu would be, so your mouse can already be on the right one when it opens.")

    private val leapTermsim by BooleanSetting("Crouch Click Termsim", false, desc = "Crouch + left click with Infinileap in your hand opens Odin's numbers terminal simulator (click in order). Finishing one starts another half a second later, in the same menu; Escape to stop.")

    private val OUTLINE_GREY = Color(128, 128, 128, 0.12f)

    /** The simulator on screen was opened by [onAttack]: finishing it starts the next one instead of Odin's termsim menu. */
    private var simFromLeap = false
    /** Bumped by every finish and every stop, so a pending restart knows whether it's still wanted. */
    private var simRound = 0
    private const val NEXT_SIM_MS = 500L

    /** Left click (from the mixin). True: it opened the simulator, and the click goes no further (no swing sent). */
    @JvmStatic
    fun onAttack(): Boolean {
        if (!enabled || !leapTermsim) return false
        val player = EngineerClient.mc.player ?: return false
        if (!player.isShiftKeyDown || EngineerClient.mc.screen != null) return false
        if (player.mainHandItem.itemId != "INFINITE_SPIRIT_LEAP") return false
        simFromLeap = true
        NumbersSim.open(0L)
        return true
    }

    /**
     * Every screen the game is told to open (from the mixin). True: don't. Odin's termsim menu,
     * which a finished simulator opens, is kept off when that simulator came from [onAttack]: the
     * finished one stays up and a new one replaces it [NEXT_SIM_MS] later, opened again rather than
     * refilled so Odin starts a new terminal (its solver and its finish) as it would for any.
     * Anything but a simulator (Escape, say) ends it.
     */
    /** A simulator from [onAttack] is on screen (no first click protection on it). */
    @JvmStatic
    fun simActive(): Boolean = simFromLeap && EngineerClient.mc.screen is TermSimGUI

    @JvmStatic
    fun cancelScreen(screen: Screen?): Boolean {
        if (!simFromLeap) return false
        if (screen === StartGUI && EngineerClient.mc.screen === NumbersSim) {
            val round = ++simRound
            Thread.ofVirtual().start {
                Thread.sleep(NEXT_SIM_MS)
                EngineerClient.mc.execute {
                    if (simFromLeap && round == simRound && EngineerClient.mc.screen === NumbersSim) NumbersSim.open(0L)
                }
            }
            return true
        }
        if (screen !is TermSimGUI) { simFromLeap = false; simRound++ }
        return false
    }

    /**
     * Set by POV Previews while its previews are up: Odin's leap boxes shrink toward the middle by
     * [overlayScale] and their background fades by [overlayAlpha] (not the head or the text), so the
     * previews behind stay readable. Read by `LeapMenuRenderMixin`.
     */
    @JvmStatic var overlayScale = 1f
    @JvmStatic var overlayAlpha = 1f

    /** An ARGB colour with its alpha times [overlayAlpha]. */
    @JvmStatic
    fun fade(argb: Int): Int {
        if (overlayAlpha >= 1f) return argb
        val a = ((argb ushr 24) * overlayAlpha).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0xFFFFFF)
    }

    private var openedAt = 0L

    /** Within Click Delay of the leap menu opening. Read by the click mixins. */
    @JvmStatic
    fun inClickDelay(): Boolean = enabled && System.currentTimeMillis() - openedAt < clickDelay * 50L

    init {
        on<ScreenEvent.Open> {
            if (screen is AbstractContainerScreen<*> && screen.title.string.equalsOneOf("Spirit Leap", "Teleport to Player"))
                openedAt = System.currentTimeMillis()
        }

        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, Identifier.fromNamespaceAndPath("engineerclient", "leap_outline")) { g, _ ->
            EngineerClient.safely("leap outline") { drawOutline(g) }
        }
    }

    /** Odin's Leap Menu "Render Scale", which sizes its boxes. */
    private fun menuScale(): Float = (LeapMenu.settings["Render Scale"] as? NumberSetting<*>)?.value?.toFloat() ?: 1f

    /**
     * The leap menu's four boxes, drawn very faintly while it is closed — the same places, size and
     * scale as the boxes it opens with (Odin draws each from a corner 24 pixels off the middle),
     * each in the class colour of the person the menu will put there (its own list, sorted its own
     * way). Grey where nobody is, or outside a dungeon.
     */
    private fun drawOutline(g: GuiGraphicsExtractor) {
        if (!enabled || !leapOutline || !LeapMenu.enabled || EngineerClient.mc.screen != null) return
        val window = EngineerClient.mc.window
        val halfW = window.guiScaledWidth / 2
        val halfH = window.guiScaledHeight / 2
        val scale = menuScale() * overlayScale
        repeat(4) { i ->
            val clazz = DungeonUtils.leapTeammates.getOrNull(i)?.clazz
            val color = if (clazz == null || clazz == DungeonClass.EMPTY) OUTLINE_GREY else clazz.color.withAlpha(OUTLINE_GREY.alphaFloat)
            val col = i % 2
            val row = i / 2
            val localX = if (col == 0) -LeapMenu.BOX_WIDTH else 0
            val localY = if (row == 0) -LeapMenu.BOX_HEIGHT else 0
            g.pose().pushMatrix()
            g.pose().translate((if (col == 0) halfW - 24 else halfW + 24).toFloat(), (if (row == 0) halfH - 24 else halfH + 24).toFloat())
            g.pose().scale(scale, scale)
            g.roundedFill(localX, localY, localX + LeapMenu.BOX_WIDTH, localY + LeapMenu.BOX_HEIGHT, color.rgba, 9)
            g.pose().popMatrix()
        }
    }

    // --- Map Leap (not offered) ---------------------------------------------------------------
    //
    // [LeapMap] draws the dungeon map with everyone's head on it and says which head the cursor is
    // on. To bring it back as an option:
    //  - a "Map Leap" BooleanSetting, a "Map Leap Size" NumberSetting (0.3..1, default 0.8), and
    //    colour settings for a [LeapMap.Palette] (rooms by RoomType, doors by DoorType, background,
    //    unknown room, unopened door);
    //  - in the clear (DungeonUtils.inDungeons && !inBoss), a CustomGUIImpl.HandlerSet registered
    //    before Odin's, whose render calls LeapMap.render(...) and returns true (so Odin's boxes are
    //    not drawn), and whose click finds LeapMap.targetAt(...) and leaps to that player the way
    //    Odin's LeapMenu.mouseTrigger does (clicking their head's slot in the Spirit Leap chest).
}
