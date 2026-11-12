package com.engineerclient.pov

/**
 * POV Previews' "Sharing FPS ❗" at its lowest ([NONE]): shown as off (NumberSettingNoneMixin) and nothing of yours is shared.
 * Its own object, as every slider's text asks it: loading POV Previews itself from there would start it mid-startup.
 */
object ShareFps {
    const val NONE = 15
    @JvmStatic fun showsNone(setting: String, value: Number): Boolean = setting == "Sharing FPS ❗" && value.toInt() <= NONE
}
