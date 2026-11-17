package com.adm.clickgui

import androidx.compose.runtime.*
import com.mojang.blaze3d.platform.InputConstants
import com.adm.AdmMod.mc
import com.adm.clickgui.ui.Panel
import com.adm.clickgui.ui.TextBox
import com.adm.features.Category
import com.adm.features.impl.render.ClickGUIModule
import com.adm.utils.ui.compose.FocusRequester
import com.adm.utils.ui.compose.UiHost
import com.adm.utils.ui.compose.offset
import com.adm.utils.ui.compose.size
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component

object ClickGUI : AdmScreen(Component.literal("Click GUI")) {

    var searchString by mutableStateOf("")
        private set

    private val searchFocus = FocusRequester()
    // Back to front: the last one draws on top and is picked first.
    private val stack = mutableStateListOf<Category>()

    override val ui by lazy {
        if (Category.categories.keys.any { ClickGUIModule.panelSetting[it] == null }) ClickGUIModule.resetPositions()
        stack.addAll(Category.categories.values)
        UiHost { Contents() }
    }

    @Composable
    private fun Contents() {
        TextBox(
            searchString, { searchString = it },
            placeholder = "Search...", maxLength = 18, centered = true, radius = 6f, focusRequester = searchFocus,
        ).size(SEARCH_W, SEARCH_H).offset({ virtualWidth / 2 - SEARCH_W / 2 }, { virtualHeight - SEARCH_FROM_BOTTOM })
        for (category in stack) key(category) {
            Panel(category) { raise(category) }
        }
    }

    private var zoom = 1f
    override val guiScale: Float get() = zoom

    val virtualWidth get() = (mc.window.guiScaledWidth / zoom).toInt()
    val virtualHeight get() = (mc.window.guiScaledHeight / zoom).toInt()

    override fun init() {
        super.init()
        zoom = ClickGUIModule.clickGuiScale / mc.window.guiScale
        ui.focus(searchFocus)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.scale(zoom, zoom)
        ui.render(graphics, virtualWidth, virtualHeight, (mouseX / zoom).toInt(), (mouseY / zoom).toInt())
        pose.popMatrix()
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key != InputConstants.KEYCODE_F || !event.hasControlDownWithQuirk()) return super.keyPressed(event)
        ui.focus(searchFocus)
        return true
    }

    private fun raise(category: Category) {
        if (stack.last() == category) return
        stack.remove(category)
        stack.add(category)
    }

    private const val SEARCH_W = 120
    private const val SEARCH_H = 20
    private const val SEARCH_FROM_BOTTOM = 45
}
