package com.coffeeclient.misc

import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module

/** While on, the sounds on the mute list (Sound Collector's /sound <number> a, /sound mutes) never play (SoundVolumeMixin). */
object SoundEditor : Module(
    name = "Sound Editor",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "Mutes every sound on your mute list (/sound <number> a with Sound Collector; /sound mutes to see or undo it).",
) {
    @JvmStatic
    fun mutes(id: String): Boolean = enabled && com.devgineerclient.misc.SoundCollector.isMuted(id)
}
