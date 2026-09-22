package com.bloodrushwaypoints.pf

import com.bloodrushwaypoints.BrwMod
import com.odtheking.odin.OdinMod
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.calculateDungeonLevel
import com.odtheking.odin.utils.network.hypixelapi.HypixelData
import com.odtheking.odin.utils.network.hypixelapi.RequestUtils
import kotlinx.coroutines.launch
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Party Finder listings, in-line: every member row of a party's tooltip gets that player's
 * Catacombs level, secret count and personal best for the floor the party is listed for.
 *
 * ```
 *   Members:
 *   TimTaroo: Berserk (47) | 47.3 | 41.2k | 4:31
 * ```
 *
 * Same data as Odin's Better Party Finder autokick: `RequestUtils.getProfile` (Odin's own
 * API, cached by Odin), Catacombs level from `dungeons.dungeon_types.catacombs.experience`,
 * `dungeons.secrets`, and the S+ / S / any-score fastest time for the listing's floor, master
 * mode or not. The tooltip is rebuilt every frame, so a row shows `…` until the fetch lands
 * and then fills in on its own.
 *
 * Hooked at `AbstractContainerScreen.getTooltipFromContainerItem` (see ContainerTooltipMixin):
 * the lines are rewritten, never a second GUI.
 */
object PartyFinderStats : Module(
    name = "Party Finder Stats",
    category = Category.custom("Blood Rush"),
    description = "Shows every listed player's Catacombs level, secrets and floor PB in the Party Finder tooltip.",
    toggled = true, // existing installs have no saved state for a new module; on by default
) {
    private val showCata by BooleanSetting("Cata Level", true, desc = "Catacombs level, one decimal.")
    private val showSecrets by BooleanSetting("Secrets", true, desc = "Total secrets found, in thousands.")
    private val showPb by BooleanSetting("Floor PB", true, desc = "Fastest time on the floor the party is listed for, master mode aware.")
    private val pbType by SelectorSetting("PB Type", "S+", arrayListOf("S+", "S", "Any"), desc = "Which fastest-time the PB column shows. Autokick uses S+.")

    private sealed interface Entry
    private object Loading : Entry
    private class Failed(val at: Long) : Entry
    private class Ready(val member: HypixelData.MemberData) : Entry

    private const val RETRY_FAILED_MS = 60_000L

    /** Lower-case IGN → state. Odin caches the profile itself; this only remembers the outcome. */
    private val cache = ConcurrentHashMap<String, Entry>()

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
        if (!enabled) return lines
        if (!screen.title.string.startsWith("Party Finder")) return lines
        if (!clean(stack.hoverName.string).endsWith("'s Party")) return lines

        var floor: String? = null
        var master = false
        var inMembers = false
        val out = ArrayList<Component>(lines.size)
        for (line in lines) {
            val text = clean(line.string).trim()
            floorLine.find(text)?.let { floor = romanFloors[it.groupValues[1].trim()] }
            dungeonLine.find(text)?.let { master = it.groupValues[1].contains("Master", ignoreCase = true) }
            if (text == "Members:") inMembers = true

            val member = if (inMembers) memberLine.find(text) else null
            out += if (member == null) line else line.copy().append(Component.literal(statsFor(member.groupValues[1], floor, master)))
        }
        return out
    }

    private fun statsFor(name: String, floor: String?, master: Boolean): String {
        val key = name.lowercase()
        val entry = cache[key]
        if (entry == null || (entry is Failed && System.currentTimeMillis() - entry.at > RETRY_FAILED_MS)) {
            cache[key] = Loading
            fetch(name, key)
            return " §8· §7…"
        }
        return when (entry) {
            is Loading -> " §8· §7…"
            is Failed -> " §8· §c?"
            is Ready -> render(entry.member, floor, master)
        }
    }

    private fun fetch(name: String, key: String) {
        OdinMod.scope.launch {
            val result = runCatching { RequestUtils.getProfile(name) }.getOrElse { Result.failure(it) }
            cache[key] = result.fold(
                onSuccess = { it.memberData?.let(::Ready) ?: Failed(System.currentTimeMillis()) },
                onFailure = { Failed(System.currentTimeMillis()) },
            )
            if (cache.size > 300) cache.clear() // a night of scrolling listings; nothing here is precious
        }
    }

    private fun render(member: HypixelData.MemberData, floor: String?, master: Boolean): String {
        val d = member.dungeons
        val parts = ArrayList<String>(3)
        if (showCata) parts += "§e" + String.format(Locale.ROOT, "%.1f", calculateDungeonLevel(d.dungeonTypes.catacombs.experience))
        if (showSecrets) parts += "§b" + secrets(d.secrets)
        if (showPb) parts += "§d" + pb(if (master) d.dungeonTypes.mastermode else d.dungeonTypes.catacombs, floor)
        if (parts.isEmpty()) return ""
        return " §8| " + parts.joinToString(" §8| ")
    }

    private fun secrets(count: Long): String = when {
        count >= 100_000 -> String.format(Locale.ROOT, "%.0fk", count / 1000.0)
        count >= 1_000 -> String.format(Locale.ROOT, "%.1fk", count / 1000.0)
        else -> count.toString()
    }

    private fun pb(type: HypixelData.DungeonTypeData, floor: String?): String {
        if (floor == null) return "§7—"
        val ms: Double? = when (pbType) {
            0 -> type.fastestTimeSPlus[floor]
            1 -> type.fastestTimeS[floor]
            else -> type.fastestTimes[floor]?.toDouble()
        }
        if (ms == null || ms <= 0) return "§7—"
        val total = (ms / 1000).toLong()
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }

    private fun clean(s: String) = formatting.replace(s, "")

    /** For the debug HUD / a command: how many players are known. */
    fun cached(): Int = cache.count { it.value is Ready }

    init {
        BrwMod.logger.info("[brw] Party Finder Stats ready")
    }
}
