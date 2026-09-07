package com.bloodrushwaypoints.rotation

import com.bloodrushwaypoints.BrwConfig
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.dungeon.PositionalMessages

/**
 * Reads the client's ACTUAL configuration and says what is wrong with it — not a checklist to
 * eyeball, a check. The prescribed setup is: Odin does the party announcing (Leap Announce and
 * Announce Invincibility on — the whole party already runs that), BRW listens, and the
 * positional-message boxes carry the exact arrival texts the rotation waits on.
 */
object SetupCheck {

    data class Item(val ok: Boolean, val what: String, val fix: String = "")

    private fun module(name: String) = ModuleManager.modules[name.lowercase()]

    private fun bool(module: String, setting: String): Boolean? =
        module(module)?.settings?.get(setting)?.value as? Boolean

    fun run(): List<Item> {
        val items = mutableListOf<Item>()

        fun moduleOn(name: String, why: String) {
            val m = module(name)
            items += when {
                m == null -> Item(false, "Odin $name: not found", "is Odin loaded?")
                m.enabled -> Item(true, "Odin $name on")
                else -> Item(false, "Odin $name is OFF", why)
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
        moduleOn("Dungeon Waypoints", "BRW's waypoints do not render without it")
        moduleOn("Positional Messages", "the arrival texts come from its boxes")

        // The exact texts the rotation waits on must exist as boxes.
        val have = PositionalMessages.posMessageStrings
            .mapNotNull { it.message?.trim()?.lowercase() }.toSet()
        RotationSpec.graph.roles
            .map { it.arrived }.filter { it.isNotBlank() && it != RotationSpec.ARRIVED_ON_LEAP }.distinct()
            .forEach { text ->
                items += if (text.trim().lowercase() in have) Item(true, "posmsg \"$text\"")
                else Item(false, "posmsg \"$text\" missing", "add a /posmsg box with exactly that text")
            }

        items += if (P3Rotation.enabled) Item(true, "BRW Blood Rush Roles on")
        else Item(false, "BRW Blood Rush Roles is OFF", "enable it in the Blood Rush panel")

        val mine = RotationSpec.graph.role(BrwConfig.data.myStartingRole)
        items += if (mine != null) Item(true, "your starting role: ${mine.name}")
        else Item(false, "no starting role set", "/brw role <${RotationSpec.graph.startingRoles.joinToString("|") { it.name }}>")

        if (P3Rotation.announceToParty) {
            items += Item(false, "BRW Announce Procs & Leaps is ON while Odin announces too", "turn one off or the party hears everything twice")
        }
        return items
    }

    fun lines(): List<String> {
        val items = run()
        val bad = items.count { !it.ok }
        val head = if (bad == 0) "§8[§6BRW§8]§a setup complete" else "§8[§6BRW§8]§c $bad setup problem${if (bad == 1) "" else "s"}"
        return listOf(head) + items.map { it ->
            (if (it.ok) "§a ✔ §7" else "§c ✘ §f") + it.what + (if (!it.ok && it.fix.isNotBlank()) " §8— ${it.fix}" else "")
        }
    }
}
