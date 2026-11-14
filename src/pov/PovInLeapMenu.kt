package com.engineerclient.pov

import com.odtheking.odin.clickgui.settings.RenderableSetting
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.dungeon.LeapMenu

/** POV Previews as a section of Leap Menu: its switch and settings live there, the module itself stays out of the GUI. */
object PovInLeapMenu {
    private val dropdown = DropdownSetting("POV Previews", desc = "Shows settings for the teammate views in the leap menu.")
    private val on = BooleanSetting("Show POV Previews", false, desc = "Teammates' live views in the leap menu.").withDependency { dropdown.value }

    fun install() {
        LeapMenu.registerSetting(dropdown)
        LeapMenu.registerSetting(on)
        for ((name, s) in PosePov.settings.toList()) {
            if (s is KeybindSetting) continue
            PosePov.settings.remove(name)
            if (s is RenderableSetting<*>) {
                val old = s.visibilityDependency
                s.withDependency { dropdown.value && on.value && old?.invoke() != false }
            }
            LeapMenu.registerSetting(s)
        }
        on.value = PosePov.enabled
        on<TickEvent.End> { if (on.value != PosePov.enabled) PosePov.toggle() }
        EventBus.subscribe(this)
    }
}
