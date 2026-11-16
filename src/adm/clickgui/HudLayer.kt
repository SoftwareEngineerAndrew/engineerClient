package com.adm.clickgui

import androidx.compose.runtime.*
import com.adm.AdmMod.mc
import com.adm.clickgui.settings.impl.HUDSetting
import com.adm.clickgui.settings.impl.HudElement
import com.adm.features.ModuleManager.hudSettingsCache
import com.adm.utils.Colors
import com.adm.utils.render.roundedOutline
import com.adm.utils.ui.compose.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.roundToInt
import kotlin.math.sign

object HudLayer {
    private var example by mutableStateOf(false)

    val ui by lazy {
        UiHost {
            for (setting in hudSettingsCache) key(setting) {
                if (setting.isEnabled) Hud(setting)
            }
        }
    }

    fun render(graphics: GuiGraphicsExtractor, example: Boolean, mouseX: Int, mouseY: Int) {
        this.example = example
        ui.render(graphics, mc.window.guiScaledWidth, mc.window.guiScaledHeight, mouseX, mouseY)
    }

    fun settle() = ui.settle()

    fun hovered(mouseX: Int, mouseY: Int): HUDSetting? = ui.hitTest(mouseX, mouseY) as? HUDSetting

    @Composable
    private fun Hud(setting: HUDSetting) {
        val hud = setting.hud
        CompositionLocalProvider(LocalTextShadow provides true) {
            Box {
                val content = Box { setting.content(example) }
                content.scale { hud.scale }
                    .offset({ ((hud.scale - 1f) * content.width / 2f).roundToInt() }, { ((hud.scale - 1f) * content.height / 2f).roundToInt() })
                if (example) Handle(setting)
            }.offset({ hud.x }, { hud.y })
        }
    }

    @Composable
    private fun Handle(setting: HUDSetting) {
        var grabX by remember { mutableIntStateOf(0) }
        var grabY by remember { mutableIntStateOf(0) }
        val hud = setting.hud
        val interaction = remember { InteractionSource() }

        Canvas(size = { hud.scaledWidth to hud.scaledHeight }) {}
            .tag(setting)
            .hoverable(interaction)
            .pointerInput(
                onDrag = { event ->
                    hud.x = event.x().toInt() - grabX
                    hud.y = event.y().toInt() - grabY
                    hud.clampToScreen()
                },
            ) { event ->
                grabX = event.x().toInt() - hud.x
                grabY = event.y().toInt() - hud.y
                true
            }
            .onScroll { amount ->
                hud.scale = (hud.scale + amount.sign.toFloat() * SCROLL_STEP).coerceIn(HudElement.MIN_SCALE, HudElement.MAX_SCALE)
                hud.clampToScreen()
                true
            }
            .drawBehind { graphics ->
                graphics.roundedOutline(x - 1, y - 1, right + 1, bottom + 1, Colors.WHITE.rgba, if (interaction.hovered) 1.5f else 1f, 3f)
            }
    }

    @Composable
    fun DrawnHudContent(hud: HudElement, example: Boolean, block: GuiGraphicsExtractor.(example: Boolean) -> Pair<Int, Int>) {
        Canvas(size = { hud.width.coerceAtLeast(1) to hud.height.coerceAtLeast(1) }) { graphics ->
            graphics.pose().pushMatrix()
            graphics.pose().translate((x + hud.centerOffset).toFloat(), y.toFloat())
            val (width, height) = graphics.block(example)
            graphics.pose().popMatrix()
            hud.onMeasured(width, height, example)
        }
    }

    private const val SCROLL_STEP = 0.1f
}
