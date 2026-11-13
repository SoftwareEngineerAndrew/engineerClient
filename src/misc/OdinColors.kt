package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors

object OdinColors : Module(
    name = "Colors",
    category = Category.RENDER,
    description = "Edit Odin's shared colour palette; settings still on their default colour follow it.",
    key = null,
) {
    private val palette: List<Pair<String, Color>> = listOf(
        "Dark Blue" to Colors.MINECRAFT_DARK_BLUE,
        "Dark Green" to Colors.MINECRAFT_DARK_GREEN,
        "Dark Aqua" to Colors.MINECRAFT_DARK_AQUA,
        "Dark Red" to Colors.MINECRAFT_DARK_RED,
        "Dark Purple" to Colors.MINECRAFT_DARK_PURPLE,
        "Gold" to Colors.MINECRAFT_GOLD,
        "Gray" to Colors.MINECRAFT_GRAY,
        "Dark Gray" to Colors.MINECRAFT_DARK_GRAY,
        "Blue" to Colors.MINECRAFT_BLUE,
        "Green" to Colors.MINECRAFT_GREEN,
        "Aqua" to Colors.MINECRAFT_AQUA,
        "Red" to Colors.MINECRAFT_RED,
        "Light Purple" to Colors.MINECRAFT_LIGHT_PURPLE,
        "Yellow" to Colors.MINECRAFT_YELLOW,
        "White" to Colors.WHITE,
        "Black" to Colors.BLACK,
        "Transparent" to Colors.TRANSPARENT,
        "GUI Gray 38" to Colors.gray38,
        "GUI Gray 26" to Colors.gray26,
    )

    private val originals = palette.map { it.second.copy() }

    private val edits = palette.map { (name, color) ->
        +ColorSetting(name, color.copy(), true, desc = "Odin's $name.")
    }

    init {
        on<TickEvent.End> { sync { edits[it].value } }
    }

    override fun onEnable() {
        super.onEnable()
        sync { edits[it].value }
    }

    override fun onDisable() {
        super.onDisable()
        sync { originals[it] }
    }

    private fun sync(target: (Int) -> Color) {
        palette.forEachIndexed { i, (_, shared) ->
            val next = target(i)
            if (shared.rgba == next.rgba) return@forEachIndexed
            val old = shared.rgba
            shared.assign(next)
            followDefault(shared, old)
        }
    }

    // Settings copy their default when built, so the ones never changed by the user are moved along by hand.
    private fun followDefault(shared: Color, old: Int) {
        for (module in ModuleManager.modules.values) {
            if (module === this) continue
            for (setting in module.settings.values) {
                if (setting is ColorSetting && setting.default === shared && setting.value.rgba == old) {
                    setting.value = shared.copy()
                }
            }
        }
    }

    private fun Color.assign(other: Color) {
        hue = other.hue
        saturation = other.saturation
        brightness = other.brightness
        alphaFloat = other.alphaFloat
    }
}
