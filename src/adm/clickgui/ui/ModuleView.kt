package com.adm.clickgui.ui

import androidx.compose.runtime.*
import com.adm.clickgui.ClickGUI
import com.adm.clickgui.GuiTheme
import com.adm.clickgui.blend
import com.adm.clickgui.dimmer
import com.adm.clickgui.lighter
import com.adm.clickgui.settings.RenderableSetting
import com.adm.features.Module
import com.adm.utils.ui.compose.*

@Composable
fun ModuleView(module: Module) {
    val rows = remember { module.settings.values.filterIsInstance<RenderableSetting<*>>() }
    var open by remember { mutableStateOf(false) }

    AnimatedVisibility({ module.name.contains(ClickGUI.searchString, ignoreCase = true) }, durationMillis = 150) {
        ModuleHeader(module) { if (rows.isNotEmpty()) open = !open }
        AnimatedVisibility(open, durationMillis = 250) {
            for (row in rows) AnimatedVisibility(row.isVisible) { row.Content() }
        }
    }
}

@Composable
private fun ModuleHeader(module: Module, onExpand: () -> Unit) {
    val pointer = remember { InteractionSource() }
    val on = animateProgress(module.enabled)
    val hover = animateProgress { pointer.hovered }

    Box().size(GuiTheme.ROW_WIDTH, GuiTheme.MODULE_HEIGHT)
        .hoverable(pointer)
        .tooltip(module.description)
        .clickable(onRightClick = onExpand) { module.toggle() }
        .drawBehind { graphics ->
            val background = GuiTheme.background
            val accent = GuiTheme.accent
            val lit = on.value
            var fill = blend(background.rgba, accent.rgba, lit)
            val glow = hover.value
            if (glow > 0f) fill = blend(fill, blend(background.lighter(1.4f), accent.dimmer(0.8f), lit), glow)
            graphics.fill(x, y, right, bottom, fill)
            graphics.textCentered(module.name, x, y, right, bottom)
        }
}
