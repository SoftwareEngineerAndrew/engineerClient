package com.adm.features.impl.dungeon.dungeonwaypoints

import androidx.compose.runtime.*
import com.mojang.blaze3d.platform.InputConstants
import com.adm.AdmMod.mc
import com.adm.clickgui.GuiTheme
import com.adm.clickgui.ui.Button
import com.adm.clickgui.ui.TextBox
import com.adm.utils.render.Corners
import com.adm.utils.render.roundedRect
import com.adm.utils.render.roundedShadow
import com.adm.utils.ui.animations.Easing
import com.adm.utils.ui.compose.*

internal const val PANEL_PAD = 8
internal const val BUTTON_H = 20

private const val OPEN_SCALE = 0.92f
private const val PROMPT_W = 240
private val panelCorners = Corners(GuiTheme.RADIUS)

@Composable
internal fun ModalPanel(width: Int, height: () -> Int, content: @Composable () -> Unit): UiNode {
    val grow = remember { Animatable(0f) }
    SideEffect { grow.animateTo(1f, 150, Easing.EASE_IN_OUT) }

    return Box(content)
        .width(width)
        .height { height() }
        .offset({ (mc.window.guiScaledWidth - width) / 2 }, { (mc.window.guiScaledHeight - height()) / 2 })
        .scale { OPEN_SCALE + (1f - OPEN_SCALE) * grow.value }
        .drawBehind { g ->
            g.roundedShadow(x, y, right, bottom, GuiTheme.shadow.rgba, GuiTheme.PANEL_BLUR, panelCorners)
            g.roundedRect(x, y, right, bottom, GuiTheme.background.rgba, panelCorners)
        }
}

internal fun UiNode.centerIn(width: Int, y: Int) = offset({ (width - this.width) / 2 }, { y })

@Composable
internal fun NamePrompt(title: String, onDone: (String) -> Unit, onCancel: () -> Unit, dim: Boolean = true) {
    var typed by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val host = LocalHost.current
    DisposableEffect(host) {
        host.focus(field)
        onDispose {}
    }

    if (dim) Canvas(size = { mc.window.guiScaledWidth to mc.window.guiScaledHeight }) { g ->
        g.fill(x, y, right, bottom, 0x80000000.toInt())
    }
    ModalPanel(PROMPT_W, { 80 }) {
        Text({ "§l$title" }).centerIn(PROMPT_W, PANEL_PAD)
        TextBox(typed, { typed = it }, placeholder = "Enter text", maxLength = 32, focusRequester = field)
            .size(PROMPT_W - PANEL_PAD * 2, BUTTON_H)
            .at(PANEL_PAD, 24)
        Button("Done", 100, BUTTON_H) { onDone(typed) }.at(16, 52)
        Button("Cancel", 100, BUTTON_H, onCancel).at(124, 52)
    }.onKey { event ->
        when (event.key) {
            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> onDone(typed)
            InputConstants.KEY_ESCAPE -> onCancel()
            else -> return@onKey false
        }
        true
    }
}
