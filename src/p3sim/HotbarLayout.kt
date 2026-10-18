package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the sim's items go on every hotbar reset (a start or restart, each phase's start, Reset Items), through
 * [SimItems.giveHotbar], in this order:
 *
 * 1. **Your saved hotbar** for this part and class, on every skill (P3Plan's preset): "Save Hotbar" (sim menu) records
 *    where each sim item is right now (hotbar and inventory, by item id), the slot you hold, what you wear and your pet,
 *    in `config/engineerclient/p3sim-hotbar.json`. Every reset lays it out again. Older files' per-skill layouts are
 *    used on their skill until this class's part is saved again; their shared ones where a class has none.
 * 2. **As you left them**, with none saved: whatever you did with your items, armour and pet since the last reset
 *    stays (for each part and class: [keep]; in the file too, so a relaunch keeps them).
 *    Black Cat At Start puts Black Cat back on at every start; Terror At Terms still puts the Terror loadout on at P3.
 * 3. **The sim's default.** "Default Hotbar" forgets this part's and class's saved one.
 *
 * P1/P2 and P3 have different items on the bar (Hyperion or Superboom), so each part has its own. Masks stay out of
 * it: the spare one always goes in [Masks.SPARE_SLOT], which no item is laid into. An item a layout doesn't place (one
 * added to the sim later) goes to its default slot, or the first free one.
 */
object HotbarLayout {

    /** [worn]: what you wore and your pet with it (null in older files: [Saved.worn]). */
    private class Layout(val slots: Map<String, Int> = emptyMap(), val selected: Int = 3, val worn: Loadouts.Worn? = null)
    /**
     * [roles]: "P3/ARCHER", one for every skill (older files: "Normal PF/P3/ARCHER", per skill). [p12]/[p3]/[worn]: from before layouts were per class.
     * [live]: each key's items as you last left them ([keep]), so they stay so after the game is closed too.
     */
    private data class Saved(val p12: Layout? = null, val p3: Layout? = null, val worn: Loadouts.Worn? = null, val roles: Map<String, Layout>? = null,
                             val live: Map<String, Layout>? = null)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    /** Each player's saved layouts (the host's from this computer, a joiner's from their game: [PlayerFiles]). */
    private val cache = ConcurrentHashMap<String, Saved>()
    /** Each player's items as they last had them, by [key]: what that part's and class's next reset lays out. */
    private val live = ConcurrentHashMap<String, ConcurrentHashMap<String, Layout>>()
    /** The [key] each player's items were last laid out for (their inventory is that one, as they've moved it since), and the slot it held. */
    private val given = ConcurrentHashMap<String, Pair<String, Int>>()
    private fun owner() = PlayerFiles.owner()?.toString() ?: ""
    private fun lives() = live.getOrPut(owner()) { ConcurrentHashMap(saved().live.orEmpty().filterValues { it.slots != null }) }

    /** How you left your items, written to the file (it reads them back after a relaunch). */
    private fun keepLives() = write(saved().copy(live = lives().toMap()))

    /** This game's file changed (the sim's server saved a joiner's): read it again. */
    fun forgetLocal() { cache.remove("") }
    /** A joiner's files arrived (or they left): read theirs again, and nothing of theirs from before (a class picked for them till then). */
    fun forget(id: java.util.UUID) { cache.remove(id.toString()); live.remove(id.toString()); given.remove(id.toString()) }

    private fun saved(): Saved = cache[owner()] ?: run {
        val s = runCatching { PlayerFiles.read(PlayerFiles.HOTBAR)?.let { gson.fromJson(it, Saved::class.java) } }
            .onFailure { EngineerClient.logger.error("[ec] p3sim hotbar load failed", it); PlayerFiles.setAsideLocal(PlayerFiles.HOTBAR) }
            .getOrNull() ?: Saved()
        cache[owner()] = s
        s
    }

    private fun partKey(p3: Boolean) = if (p3) "P3" else "P12"
    /** "P3/ARCHER": a part's layout for your class, on every skill. */
    private fun key(p3: Boolean) = "${partKey(p3)}/${P3Sim.myClass.name}"
    /** "Normal PF/P3/ARCHER": an older file's layout for one skill. */
    private fun skillKey(p3: Boolean) = "${P3Plan.skillName()}/${key(p3)}"
    /** An older file's per-skill layout of [p3]'s part for your class, on any skill. */
    private fun isSkillKey(k: String, p3: Boolean) = k.endsWith("/" + key(p3)) && k.count { it == '/' } == 2

    private fun savedLayout(p3: Boolean): Layout? = saved().roles?.let { it[key(p3)] ?: it[skillKey(p3)] } ?: if (p3) saved().p3 else saved().p12
    /** A saved hotbar for this part and class wins; as you left it only where there's none. */
    private fun layout(p3: Boolean): Layout? = savedLayout(p3) ?: lives()[key(p3)]

    /** What to wear for [p3]'s part: as saved (with the hotbar), else as you left it; null: the defaults. */
    fun worn(p3: Boolean): Loadouts.Worn? {
        val s = savedLayout(p3)
        if (s != null) return s.worn ?: saved().worn
        return lives()[key(p3)]?.worn
    }

    /** Whether [p3]'s part has a saved layout for this class (or an older one that covers it). */
    fun has(p3: Boolean) = savedLayout(p3) != null

    /** [p]'s items, where they are now, holding [selected], and [worn]; null: no sim items (nothing to go by). */
    private fun capture(p: ServerPlayer, worn: Loadouts.Worn?, selected: Int = p.inventory.selectedSlot): Layout? {
        val inv = p.inventory
        val slots = LinkedHashMap<String, Int>()
        for (i in 0..35) {
            val s = inv.getItem(i)
            // Slot 9's quiver preview / Magical Map are the SkyBlock Menu's other faces (SimItems.tickSlot9); P1's crystal is held in its slot.
            val id = (if (SimItems.isSlot9(s)) "SKYBLOCK_MENU" else SimItems.idOf(s)?.let { if (it == "MAXOR_ENERGY_CRYSTAL") "SKYBLOCK_MENU" else it }) ?: continue
            if (id.endsWith("_MASK")) continue
            slots.putIfAbsent(id, i)
        }
        return if (slots.isEmpty()) null else Layout(slots, selected, worn)
    }

    /** Before a reset: [p]'s items, armour and pet as they are now become what their last layout's next reset gives. */
    fun keep(p: ServerPlayer) {
        // A ghost's kit isn't theirs (their own comes back with them).
        if (Masks.ghost) return
        val (k, held) = given[owner()] ?: return
        val was = lives()[k]
        // Terror At Terms put that on (it does again): what they wore before stays.
        val worn = if (k.contains("/P3/") && P3Sim.terrorAtTerms) was?.worn else Loadouts.capture(p)
        // The slot held is the layout's (a restart mid-swing shouldn't start you on that item).
        capture(p, worn, held)?.let { lives()[k] = it; keepLives() }
    }

    /** [p3]'s part was just laid out, holding [held]: the player's items are its from now on. */
    fun gave(p3: Boolean, held: Int) { given[owner()] = key(p3) to held }

    /** A kit that is no boss layout went on (the run's clear): the next reset must not take it as the last layout's items. */
    fun forgetGiven() { given.remove(owner()) }

    /** Reset Items: [p3]'s part back to the saved layout (or the default), not as left. */
    fun dropLive(p3: Boolean) { lives().remove(key(p3)); given.remove(owner()); keepLives() }

    /**
     * Where the items go: [defaults] is each item at its default inventory slot. Returns every item at
     * its slot and the hotbar slot to hold.
     */
    fun arrange(defaults: List<Pair<Int, ItemStack>>, p3: Boolean): Pair<List<Pair<Int, ItemStack>>, Int> {
        val l = layout(p3) ?: return defaults to 3
        val taken = HashSet<Int>().apply { add(Masks.SPARE_SLOT) }
        val placed = ArrayList<Pair<Int, ItemStack>>()
        val left = ArrayList<Pair<Int, ItemStack>>()
        for ((slot, stack) in defaults) {
            val want = SimItems.idOf(stack)?.let { l.slots[it] }
            if (want != null && want in 0..35 && taken.add(want)) placed += want to stack else left += slot to stack
        }
        for ((slot, stack) in left) {
            val at = if (slot !in taken) slot else (0..35).first { it !in taken }
            taken += at
            placed += at to stack
        }
        return placed to l.selected.coerceIn(0, 8)
    }

    /** Saves [p]'s current layout as [p3]'s part's for this class, on every skill (older per-skill ones go). Returns what to tell them. */
    fun save(p: ServerPlayer, p3: Boolean): String {
        val l = capture(p, Loadouts.capture(p)) ?: return "§cnothing to save: no sim items in your inventory"
        lives()[key(p3)] = l
        val s = saved()
        write(s.copy(roles = s.roles.orEmpty().filterKeys { !isSkillKey(it, p3) } + (key(p3) to l), live = lives().toMap()))
        return "§7saved your §f${part(p3)}§7 hotbar (${l.slots.size} items, holding slot ${l.selected + 1}) with your armour, helmet and pet; Reset Items brings it back"
    }

    /** Forgets [p3]'s part's saved layout for this class, on every skill (and how it was left): its resets go back to the sim's default. */
    fun reset(p3: Boolean): String {
        lives().remove(key(p3)); given.remove(owner()); keepLives()
        if (!has(p3)) return "§7the ${part(p3)} hotbar is already the default"
        val s = saved().copy(live = lives().toMap())
        val roles = s.roles.orEmpty()
        when {
            // This class's, and any older per-skill ones of it.
            roles.keys.any { it == key(p3) || isSkillKey(it, p3) } -> write(s.copy(roles = roles.filterKeys { it != key(p3) && !isSkillKey(it, p3) }))
            else -> {
                val otherLeft = if (p3) s.p12 != null else s.p3 != null
                write(if (p3) s.copy(p3 = null, worn = if (otherLeft) s.worn else null) else s.copy(p12 = null, worn = if (otherLeft) s.worn else null))
            }
        }
        return "§7${part(p3)} hotbar back to the ${if (has(p3)) "one saved for every class" else "default"}"
    }

    private fun part(p3: Boolean) = (if (p3) "P3" else "P1/P2") + " " + Roles.label(P3Sim.myClass)

    private fun write(s: Saved) {
        cache[owner()] = s
        EngineerClient.safely("p3sim hotbar save") { PlayerFiles.write(PlayerFiles.HOTBAR, gson.toJson(s)) }
    }
}
