package com.engineerclient.ads.settings

import net.minecraft.client.Minecraft

/** One panel of the `/ads` screen: [settings] of [owner] under [title]. */
data class SettingsPanel(val owner: Settings, val title: String, val settings: List<Setting<*>>)

/**
 * What `/ads` shows: every part of ADS with settings registers its panels here (the P3 Sim's, and each feature
 * ADS carries of its own), in the order they register.
 */
object AdsSettingsRegistry {
    private val sources = mutableListOf<() -> List<SettingsPanel>>()

    /** Adds [source]'s panels to the screen; asked each time it opens, so panels can depend on state. */
    fun register(source: () -> List<SettingsPanel>) {
        sources += source
    }

    fun open() {
        val mc = Minecraft.getInstance()
        mc.execute { mc.gui.setScreen(AdsSettingsScreen(sources.flatMap { it() })) }
    }
}
