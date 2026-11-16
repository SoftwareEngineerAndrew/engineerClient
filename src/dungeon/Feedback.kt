package com.engineerclient.dungeon

import com.engineerclient.EngineerClient.mc
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.ActionSetting
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.clickgui.settings.impl.StringSetting
import com.adm.features.Module
import com.adm.utils.playSoundSettings
import net.minecraft.network.chat.Component

internal fun showTitle(text: String, seconds: Double) {
    val hud = mc.gui.hud
    hud.setTimes(0, (seconds * 20).toInt().coerceAtLeast(1), 0)
    hud.setTitle(Component.literal(text.replace('&', '§')))
}

internal class SoundOption(module: Module, default: String, pitch: Float = 1f, prefix: String = "", shown: () -> Boolean = { true }) {
    private val on = module.registerSetting(BooleanSetting("${prefix}Sound", true, desc = "Play a sound with it.").withDependency { shown() })
    private val id = module.registerSetting(StringSetting("${prefix}Sound Id", default, 64, desc = "Sound id, as /playsound takes it.", placeholder = "").withDependency { shown() && on.value })
    private val pitch = module.registerSetting(NumberSetting("${prefix}Sound Pitch", pitch, 0.1..2.0, 0.01f, desc = "Pitch.").withDependency { shown() && on.value })
    private val volume = module.registerSetting(NumberSetting("${prefix}Sound Volume", 1f, 0.1..1.0, 0.01f, desc = "Volume.").withDependency { shown() && on.value })

    init {
        module.registerSetting(ActionSetting("${prefix}Play Sound", desc = "Plays it.") { force() }.withDependency { shown() && on.value })
    }

    val enabled get() = on.value

    fun play() { if (on.value) force() }

    private fun force() = playSoundSettings(Triple(id.value, volume.value, pitch.value))
}
