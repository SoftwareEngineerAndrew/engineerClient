package com.engineerclient.pf

import com.engineerclient.EngineerClient
import com.engineerclient.RushProfiles
import com.odtheking.odin.OdinMod
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.calculateDungeonLevel
import com.odtheking.odin.utils.network.hypixelapi.HypixelData
import com.odtheking.odin.utils.network.hypixelapi.RequestUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import kotlinx.coroutines.launch
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
 * Same data as Odin's Better Party Finder autokick: `RequestUtils.getProfile` (Odin's own
 * API, cached by Odin), Catacombs level from `dungeons.dungeon_types.catacombs.experience`,
 * `dungeons.secrets`, and the S+ / S / any-score fastest time for the listing's floor, master
 * mode or not. The tooltip is rebuilt every frame, so a row shows `…` until the fetch lands
 * and then fills in on its own.
 *
 * Stats change rarely and the same few hundred players list night after night, so the three
 * numbers (well, the cata XP, secrets and every floor's fastest times for both modes, so the PB
 * Type switch still works) are kept on disk for a day: `config/engineerclient/pfstats.json`.
 * Odin's own profile cache only lives five minutes, and only in memory.
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
    private val showMissing by BooleanSetting("Missing Classes", true, desc = "On the Members line, which of the five classes nobody in the party has taken.")
    private val markMyClass by BooleanSetting("Mark My Class", true, desc = "Bold your own class in that list — your override, else live tab detection, else your last known class.")
    private val pbType by SelectorSetting("PB Type", "S+", arrayListOf("S+", "S", "Any"), desc = "Which fastest-time the PB column shows. Autokick uses S+.")

    private sealed interface Entry
    private object Loading : Entry
    private class Failed(val at: Long) : Entry
    private class Ready(val stats: Stats) : Entry

    /** What a row needs, small enough to keep on disk for everyone you have ever scrolled past. */
    data class Stats(
        val cataXp: Double = 0.0,
        val secrets: Long = 0,
        /** "f"/"m" → floor "0".."7" → ms, one map per PB type. */
        val sPlus: Map<String, Map<String, Double>> = emptyMap(),
        val s: Map<String, Map<String, Double>> = emptyMap(),
        val any: Map<String, Map<String, Double>> = emptyMap(),
        val fetchedAt: Long = 0,
    )

    private const val PARTY_SIZE = 5
    private val PLAYABLE = DungeonClass.entries.filter { it != DungeonClass.EMPTY }

    private const val RETRY_FAILED_MS = 60_000L
    private const val STATS_TTL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_STORED = 2000

    /** Lower-case IGN → state. Loaded from disk once; a Ready entry older than a day is refetched. */
    private val cache = ConcurrentHashMap<String, Entry>()
    private val loaded = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)
    private val gson = GsonBuilder().create()
    private val file = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("pfstats.json")

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
        if (loaded.compareAndSet(false, true)) load()

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

    private fun statsFor(name: String, floor: String?, master: Boolean): String {
        val key = name.lowercase()
        val entry = cache[key]
        val now = System.currentTimeMillis()
        if (entry == null || (entry is Failed && now - entry.at > RETRY_FAILED_MS)) {
            cache[key] = Loading
            fetch(name, key)
            return " §8· §7…"
        }
        if (entry is Ready && now - entry.stats.fetchedAt > STATS_TTL_MS && key !in refreshing) {
            // A day old: refresh in the background, keep showing what we have meanwhile.
            refreshing += key
            fetch(name, key)
        }
        return when (entry) {
            is Loading -> " §8· §7…"
            is Failed -> " §8· §c?"
            is Ready -> render(entry.stats, floor, master)
        }
    }

    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    private fun fetch(name: String, key: String) {
        OdinMod.scope.launch {
            val result = runCatching { RequestUtils.getProfile(name) }.getOrElse { Result.failure(it) }
            val fresh = result.getOrNull()?.memberData?.let { Ready(toStats(it)) }
            when {
                fresh != null -> { cache[key] = fresh; dirty.set(true) }
                // A failed refresh keeps yesterday's numbers rather than replacing them with "?".
                cache[key] !is Ready -> cache[key] = Failed(System.currentTimeMillis())
            }
            refreshing -= key
            if (dirty.compareAndSet(true, false)) save()
        }
    }

    private fun toStats(member: HypixelData.MemberData): Stats {
        val d = member.dungeons.dungeonTypes
        fun times(pick: (HypixelData.DungeonTypeData) -> Map<String, Number>) =
            mapOf("f" to pick(d.catacombs).mapValues { it.value.toDouble() }, "m" to pick(d.mastermode).mapValues { it.value.toDouble() })
        return Stats(
            cataXp = d.catacombs.experience,
            secrets = member.dungeons.secrets,
            sPlus = times { it.fastestTimeSPlus },
            s = times { it.fastestTimeS },
            any = times { it.fastestTimes },
            fetchedAt = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------ disk

    private fun load() {
        EngineerClient.safely("pf stats load") {
            if (!Files.exists(file)) return@safely
            val type = object : TypeToken<Map<String, Stats>>() {}.type
            val stored: Map<String, Stats> = gson.fromJson(Files.readString(file), type) ?: return@safely
            stored.forEach { (key, stats) -> cache[key] = Ready(stats) }
            EngineerClient.logger.info("[ec] pf stats: ${stored.size} players from disk")
        }
    }

    /** Off the render thread (called from the fetch coroutine). Atomic rename, oldest evicted past the cap. */
    private fun save() {
        EngineerClient.safely("pf stats save") {
            val ready = cache.entries.mapNotNull { (k, v) -> (v as? Ready)?.let { k to it.stats } }
                .sortedByDescending { it.second.fetchedAt }
                .take(MAX_STORED)
                .toMap()
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("pfstats.json.tmp")
            Files.writeString(tmp, gson.toJson(ready))
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun render(stats: Stats, floor: String?, master: Boolean): String {
        val parts = ArrayList<String>(3)
        if (showCata) parts += "§e" + String.format(Locale.ROOT, "%.1f", calculateDungeonLevel(stats.cataXp))
        if (showSecrets) parts += "§b" + secrets(stats.secrets)
        if (showPb) parts += "§d" + pb(stats, if (master) "m" else "f", floor)
        if (parts.isEmpty()) return ""
        return " §8| " + parts.joinToString(" §8| ")
    }

    private fun secrets(count: Long): String = when {
        count >= 100_000 -> String.format(Locale.ROOT, "%.0fk", count / 1000.0)
        count >= 1_000 -> String.format(Locale.ROOT, "%.1fk", count / 1000.0)
        else -> count.toString()
    }

    private fun pb(stats: Stats, mode: String, floor: String?): String {
        if (floor == null) return "§7—"
        val ms: Double? = when (pbType) {
            0 -> stats.sPlus[mode]?.get(floor)
            1 -> stats.s[mode]?.get(floor)
            else -> stats.any[mode]?.get(floor)
        }
        if (ms == null || ms <= 0) return "§7—"
        val total = (ms / 1000).toLong()
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }

    private fun clean(s: String) = formatting.replace(s, "")

    /** For the debug HUD / a command: how many players are known. */
    fun cached(): Int = cache.count { it.value is Ready }

    init {
        EngineerClient.logger.info("[ec] Party Finder Stats ready")
    }
}
