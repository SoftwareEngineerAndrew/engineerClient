package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.RenderableSetting
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.clickgui.settings.impl.HudElement
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.boss.TerminalsStatus
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A "Look" for Odin's own Terminals Status module, as [com.engineerclient.splits.OdinSplitsLook] is for its Splits:
 * Odin's Term Info HUD, or ADM's - the section's terms, levers, device and gate as Devonian counts them ([TermInfo]),
 * or with Simple Mode just the total. Registered into Odin's module ([install]), so Odin saves them and its HUD keeps
 * its place, scale and toggle; only what is drawn inside it changes, through `HudElementMixin` asking [render].
 */
object TerminalsStatusLook {

    private enum class Look(private val label: String) { ODIN("Odin"), ADM("ADM"); override fun toString() = label }

    private val look = SelectorSetting("Look", Look.ADM,
        desc = "Odin: Odin's Term Info. ADM: the current section's terms, levers, device and gate on their own lines (or just the total, Simple Mode), shown through all of P3. Added by engineerClient.")

    private val adm: Boolean get() = look.value == Look.ADM

    private val simple = BooleanSetting("Simple Mode", false,
        desc = "Only the total progress of the section, e.g. 3/7 (green once the gate is down). Off: terms, levers, device and gate on their own lines.")
        .withDependency { adm }

    private val forceCursorReset = BooleanSetting("Force Cursor Reset", false,
        desc = "Opening a terminal always puts the cursor in the middle of the screen. On Linux (Xwayland) the game sometimes leaves it where it was; this moves it again. Terminals only, no other menu. Added by engineerClient.")

    /** Force Cursor Reset: read by Coffee Client's CursorReset. */
    fun forcesCursorReset(): Boolean = TerminalsStatus.enabled && forceCursorReset.value

    private const val HUD_NAME = "Term Info"

    /** Adds Look (first), Simple Mode and Force Cursor Reset (last) to Terminals Status, Odin's Show hidden with the ADM look, and reads the config again for them. */
    fun install() {
        TerminalsStatus.registerSetting(look)
        TerminalsStatus.registerSetting(simple)
        TerminalsStatus.registerSetting(forceCursorReset)
        val all = LinkedHashMap(TerminalsStatus.settings)
        TerminalsStatus.settings.clear()
        all.remove(look.name)?.let { TerminalsStatus.settings[look.name] = it }
        all.remove(HUD_NAME)?.let { TerminalsStatus.settings[HUD_NAME] = it }
        all.remove(simple.name)?.let { TerminalsStatus.settings[simple.name] = it }
        TerminalsStatus.settings.putAll(all)
        (TerminalsStatus.settings["Show"] as? RenderableSetting<*>)?.let { show ->
            val odin = show.visibilityDependency
            show.visibilityDependency = { !adm && odin?.invoke() != false }
        }
        ModuleManager.loadConfigurations()
    }

    private var failed = false

    /** What Terminals Status' HUD draws instead with the ADM look: its size. Null lets Odin draw (any other HUD, the Odin look, or once an error here). */
    @JvmStatic
    fun render(hud: HudElement, g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int>? {
        if (!adm || failed) return null
        if (hud !== (TerminalsStatus.settings[HUD_NAME] as? HUDSetting)?.value) return null
        return try {
            TermInfo.drawInfo(g, example, simple.value)
        } catch (t: Throwable) {
            failed = true
            EngineerClient.logger.error("[ec] ADM Terminals Status look failed - back to Odin's look for this session", t)
            null
        }
    }
}
