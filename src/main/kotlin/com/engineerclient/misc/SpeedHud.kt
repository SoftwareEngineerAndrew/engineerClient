package com.engineerclient.misc

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.ai.attributes.Attributes

/**
 * Your SkyBlock speed (✦500). Hypixel sets it as your walk speed (abilities, and the movement
 * speed attribute's base: 0.5 = 500); the P3 Sim sets the attribute. Whichever is higher is the
 * stat (both are 0.1 = 100 by default). Optionally your actual horizontal speed too.
 */
object SpeedHud : Module(
    name = "Speed HUD",
    category = Category.custom("Engineer Client", 1030, 10),
    description = "Shows your SkyBlock speed (works on Hypixel and in the P3 Sim)."
) {
    private val actual by BooleanSetting("Show Actual", false, desc = "Also your measured horizontal speed, blocks a second.")
    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    /** On by default, once (turning it off sticks). */
    fun enableByDefault() {
        if (switchedOn) return
        switchedOn = true
        if (!enabled) toggle()
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }

    private val hud by HUD("Speed", "Your SkyBlock speed stat.", true, 10, 40, 1.5f) { example ->
        val line = if (example) "§f✦500" + if (actual) " §7(28.0 b/s)" else "" else {
            if (mc.player == null) return@HUD 0 to 0
            "§f✦${speed()}" + if (actual) " §7(${"%.1f".format(bps)} b/s)" else ""
        }
        text(line, 0, 0, Colors.WHITE, shadow = true)
        mc.font.width(line) to 10
    }

    /** The speed stat, from what the server set (abilities or the attribute). */
    fun speed(): Int {
        val p = mc.player ?: return 0
        val attr = p.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue ?: 0.1
        return Math.round(maxOf(p.abilities.walkingSpeed.toDouble(), attr) * 1000).toInt()
    }

    /** Horizontal speed over the last second (blocks/s), smoothed. */
    private var bps = 0.0
    private var lastX = Double.NaN
    private var lastZ = 0.0

    init {
        on<TickEvent.End> {
            val p = mc.player ?: run { lastX = Double.NaN; return@on }
            if (!lastX.isNaN()) {
                val d = Math.hypot(p.x - lastX, p.z - lastZ) * 20
                // Teleports (leaps, etherwarps) aren't running.
                if (d < 60) bps += (d - bps) * 0.25
            }
            lastX = p.x; lastZ = p.z
        }
    }
}
