package com.adm.clickgui.ui

import androidx.compose.runtime.*
import com.mojang.blaze3d.platform.InputConstants
import com.adm.AdmMod.mc
import com.adm.clickgui.ClickGUI
import com.adm.clickgui.GuiTheme
import com.adm.features.Category
import com.adm.features.Module
import com.adm.features.ModuleManager
import com.adm.features.impl.render.ClickGUIModule
import com.adm.utils.render.Corners
import com.adm.utils.render.roundedRect
import com.adm.utils.render.roundedShadow
import com.adm.utils.ui.compose.*

@Composable
fun Panel(category: Category, onPress: () -> Unit) {
    val place = remember { ClickGUIModule.panelSetting.getOrPut(category.name) { ClickGUIModule.PanelData(0, 0) } }
    val members = remember { ModuleManager.modulesByCategory[category]?.sortedByDescending { mc.font.width(it.name) }.orEmpty() }
    val scroll = remember(ClickGUI.searchString) { ScrollState() }

    val panel = Column {
        PanelHeader(category, place) {
            place.extended = !place.extended
            scroll.offset = 0
        }
        AnimatedVisibility({ place.extended }) { ModuleList(members, scroll) }
        Box().size(GuiTheme.ROW_WIDTH, GuiTheme.CAP).drawBehind { graphics ->
            val lit = place.extended && members.lastOrNull()?.enabled == true
            graphics.roundedRect(x, y, right, bottom, (if (lit) GuiTheme.accent else GuiTheme.background).rgba, FOOT)
        }
    }
    panel.width(GuiTheme.ROW_WIDTH)
        .offset({ place.x.coerceIn(0, rightmost()) }, { place.y.coerceIn(0, lowest()) })
        .onPress(onPress)
        .onScroll { amount ->
            val open = place.extended
            if (open) scroll.by(amount)
            open
        }
        .drawBehind { graphics -> graphics.roundedShadow(x, y, right, bottom, GuiTheme.shadow.rgba, GuiTheme.PANEL_BLUR, WHOLE) }
}

@Composable
private fun ModuleList(members: List<Module>, scroll: ScrollState) {
    Box {
        Column { for (module in members) key(module) { ModuleView(module) } }.offset(y = { scroll.offset })
    }
        .width(GuiTheme.ROW_WIDTH)
        .height {
            scroll.maxScroll = height - KEEP_VISIBLE
            (height + scroll.offset).coerceAtLeast(0)
        }
        .clip()
}

@Composable
private fun PanelHeader(category: Category, place: ClickGUIModule.PanelData, onToggle: () -> Unit) {
    var heldX by remember { mutableIntStateOf(0) }
    var heldY by remember { mutableIntStateOf(0) }
    val title = remember(category) { "§l${category.name}" }

    Box().size(GuiTheme.ROW_WIDTH, GuiTheme.ROW_HEIGHT)
        .pointerInput(
            onDrag = { event ->
                if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                    place.x = (event.x().toInt() - heldX).coerceIn(0, rightmost())
                    place.y = (event.y().toInt() - heldY).coerceIn(0, lowest())
                }
            },
        ) { event ->
            when (event.button()) {
                InputConstants.MOUSE_BUTTON_RIGHT -> {
                    onToggle()
                    true
                }
                InputConstants.MOUSE_BUTTON_LEFT -> {
                    heldX = event.x().toInt() - x
                    heldY = event.y().toInt() - y
                    true
                }
                else -> false
            }
        }
        .drawBehind { graphics ->
            graphics.roundedRect(x, y, right, bottom, GuiTheme.background.rgba, HEAD)
            graphics.textCentered(title, x, y, right, bottom)
        }
}

private fun rightmost() = (ClickGUI.virtualWidth - GuiTheme.ROW_WIDTH).coerceAtLeast(0)
private fun lowest() = (ClickGUI.virtualHeight - GuiTheme.ROW_HEIGHT).coerceAtLeast(0)

private const val KEEP_VISIBLE = 72

private val WHOLE = Corners(GuiTheme.RADIUS)
private val HEAD = Corners.top(GuiTheme.RADIUS)
private val FOOT = Corners.bottom(GuiTheme.RADIUS)
