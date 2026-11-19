package com.adm.clickgui.settings.impl

import androidx.compose.runtime.Composable
import com.adm.clickgui.settings.SettingState
import com.adm.clickgui.settings.RenderableSetting
import com.adm.clickgui.ui.Icon
import com.adm.clickgui.GuiTheme
import com.adm.clickgui.ui.SettingRow
import com.adm.clickgui.ui.animateProgress
import com.adm.utils.ui.animations.Easing
import net.minecraft.resources.Identifier
import kotlin.math.PI

class DropdownSetting(
    name: String,
    override val default: Boolean = false,
    desc: String
) : RenderableSetting<Boolean>(name, desc) {
    private var label = name
    override val shownName: String get() = label

    // A second constructor so the first keeps the signature other mods were built against
    constructor(name: String, default: Boolean = false, desc: String, label: String) : this(name, default, desc) { this.label = label }

    override var value: Boolean by SettingState(default)
    private var enabled: Boolean by this::value

    @Composable
    override fun Content() {
        SettingRow(GuiTheme.DROPDOWN_HEIGHT) { hovered ->
            val turn = animateProgress(enabled, 200, Easing.EASE_IN_OUT)
            Icon(CHEVRON, CHEVRON_SIZE, hovered = hovered, rotation = { turn.value * QUARTER_TURN }, onClick = { enabled = !enabled })
        }
    }

    private companion object {
        val CHEVRON: Identifier = Identifier.fromNamespaceAndPath("engineerclient", "textures/chevron.png")
        const val QUARTER_TURN = (PI / 2).toFloat()
        const val CHEVRON_SIZE = 13
    }
}