package com.engineerclient.pf

import com.engineerclient.ClassDetect
import com.engineerclient.misc.RandomStuff
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
 *   Player: Berserk (47) | 47.3 | 41.2k | 4:31
 * ```
 *
 * The header also says which of the five classes nobody in the party has taken, so scrolling the
 * listings answers "does this party have room for what I play" without opening any of them.
 * Your own class is bolded in that list when it is one of them. On with Random Stuff's Party
 * Finder Stats toggle; the PB column is the S+ time, as Odin's autokick uses.
 *
 * The numbers come from [PlayerStats] (Odin's profile API, sped up by Devonian when it is
 * installed, kept on disk for a day). The tooltip is rebuilt every frame, so a row shows `…`
 * until the numbers land and then fills in on its own.
 *
 * Hooked at `AbstractContainerScreen.getTooltipFromContainerItem` (see ContainerTooltipMixin):
 * the lines are rewritten, never a second GUI.
 */
object PartyFinderStats {

    private const val PARTY_SIZE = 5
    private val PLAYABLE = DungeonClass.entries.filter { it != DungeonClass.EMPTY }

    private val memberLine = Regex("^(\\w{1,16}): (\\w+) \\((\\d+)\\)$")
    private val floorLine = Regex("^Floor: (.+)$")
    private val dungeonLine = Regex("^Dungeon: (.+)$")
    private val formatting = Regex("§.")

    private val romanFloors = mapOf(
        "Entrance" to "0", "Floor I" to "1", "Floor II" to "2", "Floor III" to "3",
        "Floor IV" to "4", "Floor V" to "5", "Floor VI" to "6", "Floor VII" to "7",
    )

    /** Returns [lines] untouched (same instance) when this is not a Party Finder party item. */
    fun decorate(screen: AbstractContainerScreen<*>, stack: ItemStack, lines: List<Component>): List<Component> {
        if (!RandomStuff.showsPartyFinderStats()) return lines
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
     * that is already being rebuilt every frame. Empty string when no row parsed — a listing whose
     * wording is not recognised says nothing rather than claiming all five classes are missing.
     *
     * A party can be short of more classes than it has seats (two Mages in a 4/5 party leaves one
     * seat and two classes absent), so this is deliberately "missing" and not "needs": every class
     * named really is absent, but filling them all is not always possible.
     */
    private fun missingClasses(lines: List<Component>): String {
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
        val mine = ClassDetect.myClass()
        return "§8· §cmissing: " + absent.joinToString("§8, ") { clazz ->
            // Party Finder's own spelling, so the list matches the rows under it.
            val label = clazz.name.lowercase(Locale.ROOT).replaceFirstChar(Char::titlecase)
            // Odin's own per-class colour, so this reads like the leap menu and the role HUD.
            if (clazz == mine) "§l§${clazz.colorCode}$label§r"
            else "§${clazz.colorCode}$label"
        }
    }

    /**
     * The class a member row names. Both "Berserk" (Odin's enum, Party Finder) and "Berserker" are
     * accepted, because getting this wrong is silent and wrong in the worst direction: an
     * unrecognised spelling would leave that class out of the taken set and report a class the
     * party already has as missing.
     */
    private fun classNamed(text: String): DungeonClass? = PLAYABLE.firstOrNull {
        it.name.equals(text, ignoreCase = true) || (it == DungeonClass.BERSERK && text.equals("Berserker", ignoreCase = true))
    }

    private fun statsFor(name: String, floor: String?, master: Boolean): String =
        when (val entry = PlayerStats.lookup(name)) {
            is PlayerStats.Loading -> " §8· §7…"
            is PlayerStats.Failed -> " §8· §c?"
            is PlayerStats.Ready -> " §8| §e" + PlayerStats.cataStr(entry.stats) + " §8| §b" + PlayerStats.secretsStr(entry.stats.secrets) +
                " §8| §d" + PlayerStats.pbStr(entry.stats, if (master) "m" else "f", floor)
        }

    private fun clean(s: String) = formatting.replace(s, "")
}
