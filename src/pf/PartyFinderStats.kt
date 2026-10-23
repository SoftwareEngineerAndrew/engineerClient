package com.engineerclient.pf

import com.engineerclient.ClassDetect
import com.engineerclient.misc.RandomStuff
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.Locale

/**
 * Party Finder listings, in-line: the `Members:` line of a party's tooltip says which of the five
 * classes nobody in the party has taken, so scrolling the listings answers "does this party have
 * room for what I play" without opening any of them.
 *
 * ```
 *   Members: · missing: Mage, Tank
 * ```
 *
 * Your own class is bolded in that list when it is one of them. On with Random Stuff's Party
 * Finder Classes toggle.
 *
 * Hooked at `AbstractContainerScreen.getTooltipFromContainerItem` (see ContainerTooltipMixin):
 * the lines are rewritten, never a second GUI.
 */
object PartyFinderStats {

    private const val PARTY_SIZE = 5
    private val PLAYABLE = DungeonClass.entries.filter { it != DungeonClass.EMPTY }

    private val memberLine = Regex("^(\\w{1,16}): (\\w+) \\((\\d+)\\)$")
    private val formatting = Regex("§.")

    /** Returns [lines] untouched (same instance) when this is not a Party Finder party item. */
    fun decorate(screen: AbstractContainerScreen<*>, stack: ItemStack, lines: List<Component>): List<Component> {
        if (!RandomStuff.showsPartyFinderStats()) return lines
        if (!screen.title.string.startsWith("Party Finder")) return lines
        if (!clean(stack.hoverName.string).endsWith("'s Party")) return lines

        val missing = missingClasses(lines)
        if (missing.isEmpty()) return lines
        return lines.map { line ->
            val raw = clean(line.string)
            if (raw.trim() != "Members:") line
            // Hypixel's own header already ends in a space; only pad when it does not.
            else line.copy().append(Component.literal((if (raw.endsWith(" ")) "" else " ") + missing))
        }
    }

    /**
     * Which of the five classes nobody in the listing has taken, rendered for the `Members:`
     * line. Carries no leading space of its own — the caller pads it, because Hypixel's header
     * already ends in one and two spaces showed.
     *
     * Read off the member rows, one walk of a tooltip that is already being rebuilt every frame. Empty string when no row parsed — a listing whose
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

    private fun clean(s: String) = formatting.replace(s, "")
}
