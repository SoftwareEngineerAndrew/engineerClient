package com.adm.features.impl.nether

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.RenderExtractEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.Colors
import com.adm.utils.render.drawText
import com.adm.utils.render.drawWireFrameBox
import com.adm.utils.render.textDim
import com.adm.utils.skyblock.KuudraUtils
import com.adm.utils.toFixed
import net.minecraft.world.phys.Vec3

object KuudraInfo : Module(
    name = "Kuudra Info",
    description = "Displays information about Kuudra entity itself."
) {
    private val highlightKuudra by BooleanSetting("Highlight Kuudra", true, desc = "Highlights the kuudra entity.")
    private val kuudraColor by ColorSetting("Kuudra Color", Colors.MINECRAFT_RED, true, desc = "Color of the kuudra highlight.").withDependency { highlightKuudra }
    private val kuudraHPDisplay by BooleanSetting("Kuudra HP", true, desc = "Renders Kuudra's HP infront of it.")
    private val healthSize by NumberSetting("Health Size", 4f, 3.0..8.0, 0.1, desc = "Size of the health display.").withDependency { kuudraHPDisplay }
    private val scaledHealth by BooleanSetting("Use Scaled", true, desc = "Use scaled health for the display meaning the health will update in tier 5 when below 25,000.").withDependency { kuudraHPDisplay }
    private val hud by HUD("Health Display", "Displays the current health of Kuudra.") { example ->
        if (example) return@HUD textDim("§a99.975M/240M§c❤", 0, 0)
        if (!KuudraUtils.inKuudra) return@HUD 0 to 0
        val kuudra = KuudraUtils.kuudraEntity ?: return@HUD 0 to 0
        textDim(healthText(kuudra.health), 0, 0)
    }

    private var shownHealth = Float.NaN
    private var shownScaled = false
    private var shownText = ""

    init {
        on<RenderExtractEvent> {
            val kuudra = KuudraUtils.kuudraEntity ?: return@on
            if (!KuudraUtils.inKuudra) return@on

            if (highlightKuudra) drawWireFrameBox(kuudra.boundingBox, kuudraColor, depth = true)
            if (kuudraHPDisplay) {
                val look = kuudra.lookAngle
                val at = Vec3(kuudra.x + look.x * 13.0, kuudra.y + (look.y * 13.0 + 10.0), kuudra.z + look.z * 13.0)
                drawText(healthText(kuudra.health), at, healthSize, depth = true)
            }
        }
    }

    private fun healthText(hp: Float): String {
        val scaled = hp <= 25000 && scaledHealth && KuudraUtils.kuudraTier == 5
        if (hp.toRawBits() == shownHealth.toRawBits() && scaled == shownScaled) return shownText
        val color = when {
            hp > 99000 -> "§a"
            hp > 75000 -> "§2"
            hp > 50000 -> "§e"
            hp > 25000 -> "§6"
            hp > 10000 -> "§c"
            else -> "§4"
        }
        val thousands = hp / 1000
        shownHealth = hp
        shownScaled = scaled
        shownText = if (scaled) "$color${(thousands * 9.6).toFixed()}M§7/§a240M§c❤" else "$color${thousands}K§7/§a100k§c❤"
        return shownText
    }
}
