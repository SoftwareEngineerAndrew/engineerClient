package com.engineerclient.dungeon

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.playSoundSettings
import net.minecraft.network.chat.Component

internal fun showTitle(text: String, seconds: Double) {
    val hud = mc.gui.hud
    hud.setTimes(0, (seconds * 20).toInt().coerceAtLeast(1), 0)
    hud.setTitle(Component.literal(text.replace('&', '§')))
}

internal class SoundOption(module: Module, default: String, pitch: Float = 1f) {
    private val on = module.registerSetting(BooleanSetting("Sound", true, desc = "Play a sound with it."))
    private val id = module.registerSetting(StringSetting("Sound Id", default, 64, desc = "Sound id, as /playsound takes it.", placeholder = "").withDependency { on.value })
    private val pitch = module.registerSetting(NumberSetting("Sound Pitch", pitch, 0.1..2.0, 0.01f, desc = "Pitch.").withDependency { on.value })
    private val volume = module.registerSetting(NumberSetting("Sound Volume", 1f, 0.1..1.0, 0.01f, desc = "Volume.").withDependency { on.value })

    init {
        module.registerSetting(ActionSetting("Play Sound", desc = "Plays it.") { force() }.withDependency { on.value })
    }

    val enabled get() = on.value

    fun play() { if (on.value) force() }

    private fun force() = playSoundSettings(Triple(id.value, volume.value, pitch.value))
}
