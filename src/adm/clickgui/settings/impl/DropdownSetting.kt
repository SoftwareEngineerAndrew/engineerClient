package com.adm.clickgui.settings.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.adm.clickgui.settings.RenderableSetting
import com.adm.clickgui.ui.Icon
import com.adm.clickgui.ui.SettingRow
import com.adm.clickgui.ui.animateProgress
import com.adm.utils.ui.animations.Easing
import net.minecraft.resources.Identifier
import kotlin.math.PI

/**
 * A setting intended to show or hide other settings in the GUI.
 *
 * @author Bonsai
 */
class DropdownSetting(
    name: String,
    override val default: Boolean = false,
    desc: String
) : RenderableSetting<Boolean>(name, desc) {

    override var value: Boolean by mutableStateOf(default)
    private var enabled: Boolean by this::value

    @Composable
    override fun Content() {
        SettingRow { hovered ->
            val turn = animateProgress(enabled, 200, Easing.EASE_IN_OUT)
            Icon(CHEVRON, CHEVRON_SIZE, hovered = hovered, rotation = { turn.value * QUARTER_TURN }, onClick = { enabled = !enabled })
        }
    }

    private companion object {
        val CHEVRON: Identifier = Identifier.fromNamespaceAndPath("engineerclient", "textures/chevron.png")
        const val QUARTER_TURN = (PI / 2).toFloat()
        const val CHEVRON_SIZE = 15
    }
}