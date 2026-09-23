package com.engineerclient.pov

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.render.textDim
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * Shows each teammate's own view inside the Spirit Leap menu: the quarter of the screen Odin puts
 * them in is rendered from their eyes, so you pick a leap by looking at where it lands rather than
 * by reading a name.
 *
 * The work itself is [PovCapture]; this is the switchboard. Nothing happens unless the leap menu
 * is actually open ([wants]) — a preview is a handful of extra world renders while a menu is up
 * and nothing at all for the rest of the run.
 */
object PovPreviews : Module(
    name = "POV Previews",
    category = Category.custom("Blood Rush"),
    description = "Renders each teammate's first-person view into their quarter of Odin's Spirit Leap menu.",
) {

    /**
     * How many of the four feeds are re-rendered per frame. The other three keep their last image,
     * which at 100+ fps is 30 ms old — invisible for judging where somebody is standing, and it
     * keeps the cost at one extra quarter-screen render per frame.
     */
    val previewsPerFrame by NumberSetting("Previews Per Frame", 1, 1, 4, 1, desc = "Feeds refreshed each frame; the rest keep their last image. Higher is smoother and costs more.")

    /**
     * Fraction of the quadrant each feed is rendered at; the blit scales it back up. Half
     * resolution is a quarter of the fragments and, on a preview you are reading for position
     * rather than detail, hard to notice.
     */
    val resolution by NumberSetting("Resolution", 0.5f, 0.25f, 1f, 0.05f, desc = "Render scale of each feed, as a fraction of its quadrant. Lower is cheaper and softer.", unit = "x")

    /**
     * Sodium's render lists hold whatever the last cull produced — your own frustum — so without a
     * re-cull a preview shows only the chunks YOU can see, which from a teammate's eyes is mostly
     * a hole. Re-culling also queues meshing for sections only the preview sees, so a cold
     * direction fills in over a few frames.
     */
    val recullTerrain by BooleanSetting("Re-cull Terrain", true, desc = "Re-runs Sodium's terrain cull from the teammate's eyes. Off is cheaper but shows only the chunks already visible to you.")

    /**
     * Below 1, the previews blend over your own view instead of replacing it - and your own view
     * keeps rendering underneath regardless of [skipOwnView], since there's something to see
     * through to now.
     */
    val opacity by NumberSetting("Opacity", 1f, 0.1f, 1f, 0.05f, desc = "How opaque the previews are. Below 1 you can see your own game through them (your own view keeps rendering, so this costs a world render).", unit = "x")

    // Separate from [opacity]: these apply to Odin's own leap boxes (colour, head, name, class)
    // drawn on top of the previews, only while the previews are actually up.
    val leapBoxScale by NumberSetting("Leap Box Size", 1f, 0.3f, 1f, 0.05f, desc = "Shrinks Odin's leap boxes toward the centre while the previews are showing.", unit = "x")
    val leapBoxOpacity by NumberSetting("Leap Box Opacity", 1f, 0.1f, 1f, 0.05f, desc = "Fades Odin's leap boxes (colour, head, name, class) while the previews are showing.", unit = "x")

    /**
     * With four previews tiling the screen, your own view is behind all of them. Skipping it is a
     * whole world render saved per frame, and costs nothing to resume: the next frame simply does
     * not take that branch.
     */
    val skipOwnView by BooleanSetting("Skip Own View", true, desc = "Skips rendering your own view while the previews cover the screen. Saves a full world render per frame.")

    /**
     * Vanilla = whatever `Camera.update` lerps to (smooth, ~3 ticks / 150 ms behind the server).
     * Raw = the last packet, no smoothing (lowest latency, 20 Hz steps).
     * Custom = EC's own time-based replay, [smoothingTicks] ticks in the past.
     */
    val headSmoothing by SelectorSetting("Head Smoothing", "Vanilla", arrayListOf("Vanilla", "Raw", "Custom"), desc = "How a teammate's head movement is interpolated for their preview.")

    val smoothingTicks by NumberSetting("Smoothing Ticks", 3, 0, 6, 1, desc = "How far in the past Custom renders the pose, in ticks (50 ms each). 0 is the same as Raw.")
        .withDependency { headSmoothing == 2 }

    // ---- which HUDs survive on top of a preview -------------------------------------------
    //
    // A preview is submitted in the screen phase, after every HUD has been extracted, so it
    // covers the lot. These are the ones you cannot afford to lose while a leap menu is up; each
    // is simply submitted a second time, above the previews. Odin's leap boxes and the player
    // names need no entry — Odin draws them after us anyway.
    private val keepHuds by DropdownSetting("Keep HUDs On Top")
    private val keepTickTimers by BooleanSetting("Keep Tick Timers", true, desc = "Redraws Odin's Necron / Goldor / Storm / Secrets tick timers over the previews.").withDependency { keepHuds }
    private val keepInvincibility by BooleanSetting("Keep Invincibility Timer", true, desc = "Redraws Odin's Invincibility Timer HUD over the previews.").withDependency { keepHuds }
    private val keepMelody by BooleanSetting("Keep Melody Display", true, desc = "Redraws Odin's Melody progress GUI over the previews.").withDependency { keepHuds }
    private val keepRoleHud by BooleanSetting("Keep Role HUD", true, desc = "Redraws EC's own role HUD over the previews.").withDependency { keepHuds }

    private val TICK_TIMER_HUDS = listOf(
        "Necron Hud", "Goldor Hud", "Storm Pad Hud", "Storm Lightning Hud", "Storm PY Hud", "Storm Tick Hud", "Secrets Hud",
    )

    /** HUDSetting names, exactly as the owning module declares them. */
    fun keptHudNames(): Set<String> {
        val names = HashSet<String>()
        if (keepTickTimers) names += TICK_TIMER_HUDS
        if (keepInvincibility) names += "Invincibility Timer"
        if (keepMelody) names += "Progress GUI"
        if (keepRoleHud) names += "Your Role"
        return names
    }

    val showCost by BooleanSetting("Show Cost", false, desc = "HUD line with the milliseconds the previews added to the last frame.")

    val mode: PovPose.Mode
        get() = when (headSmoothing) {
            1 -> PovPose.Mode.RAW
            2 -> PovPose.Mode.CUSTOM
            else -> PovPose.Mode.VANILLA
        }

    private val costHud by HUD("POV Cost", "Milliseconds the POV previews added to the last frame, and how many feeds are live.", x = 10, y = 200, scale = 1f) { example ->
        val line = if (example) "§7POV §f1.8§7ms  §f4§7/4" else PovCapture.costLine() ?: return@HUD 0 to 0
        drawLine(this, line)
    }

    private fun drawLine(gfx: GuiGraphicsExtractor, line: String): Pair<Int, Int> {
        val width = gfx.textDim(line, 0, 0, Colors.WHITE).first
        gfx.text(line, 0, 0, Colors.WHITE)
        return width to 9
    }

    init {
        // Above Odin's CustomGUIImpl (EventPriority.HIGHEST): the bus sorts listeners by priority
        // descending, so this runs first and everything Odin submits for the leap menu — its
        // boxes, the names, EC's own ring — is submitted after the previews and lands on top.
        on<ScreenEvent.Render>(EventPriority.HIGHEST + 100) {
            // Runs before Odin draws its boxes (see the priority note above), so this frame picks it up.
            val active = wants()
            LeapMenu.overlayScale = if (active) leapBoxScale else 1f
            LeapMenu.overlayAlpha = if (active) leapBoxOpacity else 1f
            EngineerClient.safely("pov gui") { PovCapture.onScreenExtract(guiGraphics) }
        }

        // CUSTOM keeps a short ring of packet poses per player; it is fed here, once per client
        // tick, on the render thread. Odin only delivers this while the module is enabled, which
        // is also the only time anything reads the ring.
        on<TickEvent.End> {
            PovPose.onClientTick()
            if (!wants()) PovCapture.releaseNow()
        }

        on<LevelEvent.Load> {
            PovPose.reset()
            PovCapture.onWorldChange()
        }
    }

    override fun onDisable() {
        LeapMenu.overlayScale = 1f
        LeapMenu.overlayAlpha = 1f
        PovPose.reset()
        // The feeds are GPU targets; they are freed on the render thread, next time the capture
        // path runs, not here — a module toggle can come from a keybind at any point in the frame.
        PovCapture.requestFree()
        super.onDisable()
    }

    /**
     * True only while there is something to draw into: the module on, Odin's leap menu on (it is
     * what cancels the vanilla chest render, so without it the previews would sit under a chest
     * GUI), and the Spirit Leap screen open. Same title check as `LeapHighlight.leapScreen`.
     */
    fun wants(): Boolean {
        if (!enabled || PovCapture.disabledForSession) return false
        if (!LeapMenu.enabled) return false
        val screen = Minecraft.getInstance().screen as? AbstractContainerScreen<*> ?: return false
        return screen.title.string.equalsOneOf("Spirit Leap", "Teleport to Player")
    }
}
