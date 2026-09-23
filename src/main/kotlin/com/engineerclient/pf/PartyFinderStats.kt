package com.engineerclient.pf

import com.engineerclient.EngineerClient
import com.engineerclient.RushProfiles
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.calculateDungeonLevel
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.Locale

/**
 * Party Finder listings, in-line: every member row of a party's tooltip gets that player's
 * Catacombs level, secret count and personal best for the floor the party is listed for.
 *
 * ```
 *   Members: · missing: Mage, Tank
 *   TimTaroo: Berserk (47) | 47.3 | 41.2k | 4:31
 * ```
 *
 * The header also says which of the five classes nobody in the party has taken, so scrolling the
 * listings answers "does this party have room for what I play" without opening any of them.
 * Your own class is bolded in that list when it is one of them.
 *
 * The numbers come from [PlayerStats] (fetched through Odin's profile API, kept on disk for a
 * day). The tooltip is rebuilt every frame, so a row shows `…` until the fetch lands and then
 * fills in on its own.
 *
 * Hooked at `AbstractContainerScreen.getTooltipFromContainerItem` (see ContainerTooltipMixin):
 * the lines are rewritten, never a second GUI.
 */
object PartyFinderStats : Module(
    name = "Party Finder Stats",
    category = Category.custom("Engineer Client"),
    description = "Shows every listed player's Catacombs level, secrets and floor PB in the Party Finder tooltip.",
    toggled = true, // existing installs have no saved state for a new module; on by default
) {
    private val showCata by BooleanSetting("Cata Level", true, desc = "Catacombs level, one decimal.")
    private val showSecrets by BooleanSetting("Secrets", true, desc = "Total secrets found, in thousands.")
    private val showPb by BooleanSetting("Floor PB", true, desc = "Fastest time on the floor the party is listed for, master mode aware.")
    private val showMissing by BooleanSetting("Missing Classes", true, desc = "On the Members line, which of the five classes nobody in the party has taken.")
    private val markMyClass by BooleanSetting("Mark My Class", true, desc = "Bold your own class in that list — your override, else live tab detection, else your last known class.")
    private val pbType by SelectorSetting("PB Type", "S+", arrayListOf("S+", "S", "Any"), desc = "Which fastest-time the PB column shows. Autokick uses S+.")

    private const val PARTY_SIZE = 5
    private val PLAYABLE = DungeonClass.entries.filter { it != DungeonClass.EMPTY }

    private val memberLine = Regex("^(\\w{1,16}): (\\w+) \\((\\d+)\\)$")
    private val floorLine = Regex("^Floor: (.+)$")
    private val dungeonLine = Regex("^Dungeon: (.+)$")
    private val formatting = Regex("§.")

    internal val romanFloors = mapOf(
        "Entrance" to "0", "Floor I" to "1", "Floor II" to "2", "Floor III" to "3",
        "Floor IV" to "4", "Floor V" to "5", "Floor VI" to "6", "Floor VII" to "7",
    )

    /** Returns [lines] untouched (same instance) when this is not a Party Finder party item. */
    fun decorate(screen: AbstractContainerScreen<*>, stack: ItemStack, lines: List<Component>): List<Component> {
        if (!enabled) return lines
        if (!screen.title.string.startsWith("Party Finder")) return lines
        if (!clean(stack.hoverName.string).endsWith("'s Party")) return lines

        var floor: String? = null
        var master = false
        var inMembers = false
        val missing = missingClasses(lines)
        val out = ArrayList<Component>(lines.size)
        for (line in lines) {
            val raw = clean(line.string)
            val text = raw.trim()
            floorLine.find(text)?.let { floor = romanFloors[it.groupValues[1].trim()] }
            dungeonLine.find(text)?.let { master = it.groupValues[1].contains("Master", ignoreCase = true) }
            if (text == "Members:") {
                inMembers = true
                // Hypixel's own header already ends in a space; only pad when it does not.
                val pad = if (raw.endsWith(" ")) "" else " "
                out += if (missing.isEmpty()) line else line.copy().append(Component.literal(pad + missing))
                continue
            }

            val member = if (inMembers) memberLine.find(text) else null
            out += if (member == null) line else line.copy().append(Component.literal(statsFor(member.groupValues[1], floor, master)))
        }
        return out
    }

    /**
     * Which of the five classes nobody in the listing has taken, rendered for the `Members:`
     * line. Carries no leading space of its own — the caller pads it, because Hypixel's header
     * already ends in one and two spaces showed.
     *
     * Read off the same member rows the stat columns use, so it costs one extra walk of a tooltip
     * that is already being rebuilt every frame. Empty string when the module setting is off, when
     * the party has no seat left, or when no row parsed — a listing whose wording we do not
     * recognise says nothing rather than claiming all five classes are missing.
     *
     * A party can be short of more classes than it has seats (two Mages in a 4/5 party leaves one
     * seat and two classes absent), so this is deliberately "missing" and not "needs": every class
     * named really is absent, but filling them all is not always possible.
     */
    private fun missingClasses(lines: List<Component>): String {
        if (!showMissing) return ""
        var inMembers = false
        var members = 0
        val taken = HashSet<DungeonClass>()
        for (line in lines) {
            val text = clean(line.string).trim()
            if (text == "Members:") { inMembers = true; continue }
            if (!inMembers) continue
            val member = memberLine.find(text) ?: continue
            members++
            classNamed(member.groupValues[2])?.let { taken += it }
        }
        if (members == 0) return ""
        if (members >= PARTY_SIZE) return "§8· §7full"
        val absent = PLAYABLE.filter { it !in taken }
        if (absent.isEmpty()) return ""
        val mine = if (markMyClass) RushProfiles.effectiveClass() else null
        return "§8· §cmissing: " + absent.joinToString("§8, ") { clazz ->
            // Party Finder's own spelling, so the list matches the rows under it — it writes
            // "Berserk" where the mod's pack names and class override say "Berserker", which is
            // why the ownership test goes through friendlyName rather than the label.
            val label = clazz.name.lowercase(Locale.ROOT).replaceFirstChar(Char::titlecase)
            // Odin's own per-class colour, so this reads like the leap menu and the role HUD.
            if (RushProfiles.friendlyName(clazz) == mine) "§l§${clazz.colorCode}$label§r"
            else "§${clazz.colorCode}$label"
        }
    }

    /**
     * The class a member row names. Both spellings are accepted — Odin's enum and Party Finder
     * say "Berserk", the mod's pack names and class override say "Berserker" — because getting
     * this wrong is silent and wrong in the worst direction: an unrecognised spelling would leave
     * that class out of the taken set and report a class the party already has as missing.
     */
    private fun classNamed(text: String): DungeonClass? = PLAYABLE.firstOrNull {
        it.name.equals(text, ignoreCase = true) || RushProfiles.friendlyName(it).equals(text, ignoreCase = true)
    }

    private fun statsFor(name: String, floor: String?, master: Boolean): String =
        when (val entry = PlayerStats.lookup(name)) {
            is PlayerStats.Loading -> " §8· §7…"
            is PlayerStats.Failed -> " §8· §c?"
            is PlayerStats.Ready -> render(entry.stats, floor, master)
        }

    private fun render(stats: PlayerStats.Stats, floor: String?, master: Boolean): String {
        val parts = ArrayList<String>(3)
        if (showCata) parts += "§e" + PlayerStats.cataStr(stats)
        if (showSecrets) parts += "§b" + PlayerStats.secretsStr(stats.secrets)
        if (showPb) parts += "§d" + PlayerStats.pbStr(stats, if (master) "m" else "f", floor, pbType)
        if (parts.isEmpty()) return ""
        return " §8| " + parts.joinToString(" §8| ")
    }

    private fun clean(s: String) = formatting.replace(s, "")

    /**
     * For Better PF Menu (the website's copy of the menu): [name]'s numbers as a JSON object - Cata
     * level, secrets, S+ PB on the listing's floor - or null until they're known. Fetches them like a
     * tooltip would, but only a few at a time: a whole menu's players at once would hammer the API.
     */
    fun webStats(name: String, floor: String?, master: Boolean): com.google.gson.JsonObject? {
        val stats = (PlayerStats.lookupThrottled(name, WEB_FETCHES) as? PlayerStats.Ready)?.stats ?: return null
        return com.google.gson.JsonObject().apply {
            addProperty("cata", Math.round(calculateDungeonLevel(stats.cataXp) * 10) / 10.0)
            addProperty("secrets", stats.secrets)
            floor?.let { f -> stats.sPlus[if (master) "m" else "f"]?.get(f)?.takeIf { it > 0 }?.let { addProperty("pb", it.toLong()) } }
        }
    }

    private const val WEB_FETCHES = 4

    init {
        EngineerClient.logger.info("[ec] Party Finder Stats ready")
    }
}
