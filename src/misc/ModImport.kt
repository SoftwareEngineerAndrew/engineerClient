package com.engineerclient.misc

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.HUDSetting
import com.adm.clickgui.settings.impl.KeybindSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.features.ModuleManager
import com.engineerclient.EngineerClient
import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import java.io.File

/**
 * Once after a fresh install, turns on what the player had on in Odin, Devonian and Blade Addons and takes Odin's keybinds
 * for it, from the copies [com.engineerclient.ModTakeover] kept. A launch that stops before this leaves it for the next one.
 */
object ModImport {
    private val gson = Gson()

    fun run(gameDir: File) {
        val dir = File(gameDir, "config/${ImportTables.IMPORTED}")
        val pending = File(dir, ImportTables.PENDING)
        if (!pending.isFile) return
        var on = 0
        for (step in ImportTables.plan(dir)) {
            try { if (apply(step)) on++ } catch (t: Throwable) { EngineerClient.logger.warn("[ec] importing ${step.to} failed", t) }
        }
        ModuleManager.saveConfigurations()
        pending.delete()
        EngineerClient.logger.info("[ec] took $on settings from other mods' configs")
    }

    private fun apply(step: ImportTables.Step): Boolean {
        val module = ModuleManager.modules[step.to.substringBefore('>').lowercase()] ?: return false
        val name = step.to.substringAfter('>', "")
        if (name.isNotEmpty()) {
            val s = module.settings[name]
            val v = step.value
            when {
                s is BooleanSetting && (v == null || (v as? JsonPrimitive)?.isBoolean == true) -> s.value = v?.asBoolean ?: true
                s is HUDSetting && v == null -> s.value.enabled = true
                s is KeybindSetting && v != null -> s.read(v, gson)
                s is SelectorSetting<*> && v != null -> s.read(v, gson)
                step.loose -> return false
            }
        }
        if (!module.enabled) module.toggle()
        return true
    }
}
