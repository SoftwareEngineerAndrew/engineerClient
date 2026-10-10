package com.engineerclient

import com.engineerclient.misc.HealthMana
import com.engineerclient.splits.OdinSplitsLook
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.clickgui.settings.impl.HudElement
import com.odtheking.odin.clickgui.settings.impl.drawAtHud
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Every HUD made through Odin's `Module.HUD`, by its setting. Odin draws HUDs through Compose and
 * has no per-element draw call to hook, so `HudElementMixin` wraps each HUD's draw
 * function as it is made ([Draw]). The wrapper is where two things step in, for Odin's own modules
 * without touching them:
 *  - Random Stuff's Hide Health/Mana Above %: Odin's Health HUD and Mana HUD draw nothing while the
 *    stat is above the threshold. The HUD editor (example = true) always draws them.
 *  - The Engineer Splits look: for Odin's two Splits HUDs, [OdinSplitsLook.render] draws instead.
 * It is also what POV Previews calls to draw a kept HUD a second time, above the previews.
 */
object OdinHuds {

    class Draw(private val block: (GuiGraphicsExtractor, Boolean) -> Pair<Int, Int>) : (GuiGraphicsExtractor, Boolean) -> Pair<Int, Int> {
        var hud: HudElement? = null

        override fun invoke(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
            val h = hud ?: return block(g, example)
            if (!example && HealthMana.hidesOdinHud(h)) return 0 to 0
            return OdinSplitsLook.render(h, g, example) ?: block(g, example)
        }
    }

    private val draws = HashMap<HUDSetting, Draw>()
    private var pending: Draw? = null

    /** `Module.HUD` is making a HUD from [block]: what it is given instead. */
    @JvmStatic
    fun wrap(block: (GuiGraphicsExtractor, Boolean) -> Pair<Int, Int>): (GuiGraphicsExtractor, Boolean) -> Pair<Int, Int> =
        Draw(block).also { pending = it }

    /** ...and the setting it made from it. */
    @JvmStatic
    fun made(setting: HUDSetting) {
        val draw = pending ?: return
        pending = null
        draw.hud = setting.value
        draws[setting] = draw
    }

    /** Draws [setting]'s HUD again where Odin puts it (not the editor's example). */
    fun redraw(g: GuiGraphicsExtractor, setting: HUDSetting) {
        val draw = draws[setting] ?: return
        g.drawAtHud(setting.value) { draw(this, false) }
    }
}
