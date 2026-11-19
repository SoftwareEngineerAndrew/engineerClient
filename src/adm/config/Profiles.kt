package com.adm.config

import com.adm.AdmMod
import com.adm.features.ModuleManager
import java.io.File

/** Named copies of every module config, saved and loaded whole. */
object Profiles {
    private val dir = File(AdmMod.configFile, "profiles")
    private val validName = Regex("[A-Za-z0-9_-]{1,32}")

    fun valid(name: String) = validName.matches(name)

    fun list(): List<String> = dir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()

    fun save(name: String) {
        ModuleManager.saveConfigurations()
        val target = File(dir, name)
        target.deleteRecursively()
        for (config in ModuleManager.configs) {
            val src = config.file
            if (src.isFile) src.copyTo(File(target, src.relativeTo(AdmMod.configFile).path), overwrite = true)
        }
    }

    /** False when there is no such profile. */
    fun load(name: String): Boolean {
        val source = File(dir, name)
        if (!source.isDirectory) return false
        for (config in ModuleManager.configs) {
            val saved = File(source, config.file.relativeTo(AdmMod.configFile).path)
            if (saved.isFile) saved.copyTo(config.file, overwrite = true)
        }
        ModuleManager.loadConfigurations()
        return true
    }

    fun delete(name: String): Boolean = File(dir, name).let { it.isDirectory && it.deleteRecursively() }
}
