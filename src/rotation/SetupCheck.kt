package com.engineerclient.rotation

import com.engineerclient.EngineerClient
import com.engineerclient.EcConfig
import com.odtheking.odin.features.ModuleManager
import com.engineerclient.waypoints.PositionalMessages
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.GraphicsPreset

/**
 * Inspects the client's actual configuration and reports what is wrong with it. The expected
 * setup: Odin does the party announcing (Leap Announce and Announce Invincibility on), EC
 * listens, and the positional-message boxes carry the exact arrival texts the rotation waits on.
 */
object SetupCheck {

    data class Item(val ok: Boolean, val what: String, val fix: String = "")

    private fun module(name: String) = ModuleManager.modules[name.lowercase()]

    private fun bool(module: String, setting: String): Boolean? =
        module(module)?.settings?.get(setting)?.value as? Boolean

    fun run(): List<Item> {
        val items = mutableListOf<Item>()

        fun moduleOn(name: String, why: String, owner: String = "Odin") {
            val m = module(name)
            items += when {
                m == null -> Item(false, "$owner $name: not found", "is Odin loaded?")
                m.enabled -> Item(true, "$owner $name on")
                else -> Item(false, "$owner $name is OFF", why)
            }
        }
        fun settingOn(module: String, setting: String, why: String) {
            items += when (bool(module, setting)) {
                null -> Item(false, "Odin $module > $setting: not found", "Odin version mismatch?")
                true -> Item(true, "Odin $module > $setting on")
                false -> Item(false, "Odin $module > $setting is OFF", why)
            }
        }

        moduleOn("Leap Menu", "the leap highlight draws on it")
        settingOn("Leap Menu", "Leap Announce", "\"Leaped to X!\" is how the party learns you are through")
        moduleOn("Invincibility Timer", "it is what announces your procs")
        settingOn("Invincibility Timer", "Announce Invincibility", "the mask gate needs everyone's procs in party chat")
        moduleOn("Dungeon Waypoints", "EC's waypoints do not render without it")
        moduleOn("Positional Messages", "the arrival texts come from its boxes", "EC")

        // The exact texts the rotation waits on must exist as boxes.
        val have = PositionalMessages.posMessageStrings
            .mapNotNull { it.message?.trim()?.lowercase() }.toSet()
        (RotationSpec.graph.roles.map { it.arrived } + RotationSpec.graph.recoreArrived)
            .filter { it.isNotBlank() && it != RotationSpec.ARRIVED_ON_LEAP }.distinct()
            .forEach { text ->
                items += if (text.trim().lowercase() in have) Item(true, "posmsg \"$text\"")
                else Item(false, "posmsg \"$text\" missing", "add a /posmsg box with exactly that text")
            }

        items += if (P3Rotation.enabled) Item(true, "EC Dynamic Term Roles on")
        else Item(false, "EC Dynamic Term Roles is OFF", "enable it in the Engineer Client panel")

        val mine = RotationSpec.graph.role(EcConfig.data.myStartingRole)
        items += if (mine != null) Item(true, "your starting role: ${mine.name}")
        else Item(false, "no starting role set", "/ec role <${RotationSpec.graph.startingRoles.joinToString("|") { it.name }}>")

        // Other mods that intercept system chat at the network layer. EC reads packets ahead of
        // them, but report them anyway: a run that still misses lines starts here.
        interceptor("blade-addons.json", "enableTerminalSplits", "blade-addons Terminal Splits rewrites completion lines")?.let { items += it }
        interceptor("devonianConfig.json", "terminalHideCompletion", "devonian Hide Terminal Completion drops completion lines")?.let { items += it }

        // POV previews render a second world pass into their own target. The Fabulous preset
        // (and its Improved Transparency post-chain, which a Custom preset can also switch on)
        // owns extra render targets the nested pass cannot borrow.
        val options = EngineerClient.mc.options
        val preset = options.graphicsPreset().get()
        items += when {
            preset == GraphicsPreset.FABULOUS ->
                Item(false, "graphics preset is Fabulous", "POV previews need Fancy or Fast")
            options.improvedTransparency().get() ->
                Item(false, "Improved Transparency is ON", "POV previews need Fancy or Fast")
            else -> Item(true, "graphics preset ${preset.serializedName}")
        }

        // Informational: both are optional and bridged, but a
        // preview that looks wrong (missing terrain, missing entities) starts with which is loaded.
        fun optionalMod(id: String, name: String) {
            items += if (FabricLoader.getInstance().isModLoaded(id)) Item(true, "$name present")
            else Item(true, "$name not installed")
        }
        optionalMod("sodium", "Sodium")
        optionalMod("entityculling", "EntityCulling")

        if (P3Rotation.announceToParty) {
            items += Item(false, "EC Announce Procs & Leaps is ON while Odin announces too", "turn one off or the party hears everything twice")
        }
        return items
    }

    private fun interceptor(file: String, key: String, what: String): Item? {
        val path = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve(file)
        if (!java.nio.file.Files.exists(path)) return null
        val on = Regex("\"$key\"\\s*:\\s*true").containsMatchIn(runCatching { java.nio.file.Files.readString(path) }.getOrDefault(""))
        return if (on) Item(false, "$what is ON", "EC copes, but turn it off if completions still go missing") else Item(true, "$what is off")
    }

    fun lines(): List<String> {
        val items = run()
        val bad = items.count { !it.ok }
        val head = if (bad == 0) "${EngineerClient.PREFIX}§asetup complete" else "${EngineerClient.PREFIX}§c$bad setup problem${if (bad == 1) "" else "s"}"
        return listOf(head) + items.map { it ->
            (if (it.ok) "§a ✔ §7" else "§c ✘ §f") + it.what + (if (!it.ok && it.fix.isNotBlank()) " §8— ${it.fix}" else "")
        }
    }
}
