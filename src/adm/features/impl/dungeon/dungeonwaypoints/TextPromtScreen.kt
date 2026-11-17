package com.adm.features.impl.dungeon.dungeonwaypoints

import com.adm.AdmMod.mc
import com.adm.clickgui.AdmScreen
import com.adm.utils.ui.compose.UiHost
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

internal class TextPromptScreen(heading: String, onDone: (String) -> Unit) : AdmScreen(Component.literal(heading)) {
    override val ui = UiHost {
        NamePrompt(heading, onDone, { mc.gui.setScreen(null) }, dim = false)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        ui.render(graphics, width, height, mouseX, mouseY)
    }

    override fun removed() {
        super.removed()
        ui.dispose()
    }
}
