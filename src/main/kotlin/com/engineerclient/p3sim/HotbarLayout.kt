package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.io.File

/**
 * Your own layout for the sim's items, kept on this computer in `config/engineerclient/p3sim-hotbar.json`.
 *
 * "Save Hotbar" (sim menu) records where each sim item is in your inventory right now - hotbar and
 * the rest, by item id - and which hotbar slot you hold. From then on every hotbar reset (a fight
 * starting, each phase's start, Reset Items) lays the items out that way through [SimItems.giveHotbar].
 * P1/P2 and P3 have different items on the bar (Hyperion or Superboom), so each has its own layout,
 * saved from whichever part of the fight you are in. "Default Hotbar" forgets that part's layout.
 *
 * Masks stay out of it: the spare one always goes in [Masks.SPARE_SLOT], which no item is laid into.
 * An item the saved layout doesn't place (one added to the sim later) goes to its default slot, or
 * the first free one.
 */
object HotbarLayout {

    private class Layout(val slots: Map<String, Int> = emptyMap(), val selected: Int = 3)
    /** [worn]: what you wear and your pet, saved with the hotbar (one for both parts); null in older files = the defaults. */
    private data class Saved(val p12: Layout? = null, val p3: Layout? = null, val worn: Loadouts.Worn? = null)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = File(Minecraft.getInstance().gameDirectory, "config/engineerclient/p3sim-hotbar.json")
    private var saved: Saved? = null

    private fun saved(): Saved = saved ?: run {
        val s = runCatching { if (file.exists()) gson.fromJson(file.readText(), Saved::class.java) else null }
            .onFailure { EngineerClient.logger.error("[ec] p3sim hotbar load failed", it) }
            .getOrNull() ?: Saved()
        saved = s
        s
    }

    private fun layout(p3: Boolean): Layout? = if (p3) saved().p3 else saved().p12

    /** The saved worn loadout (armour, helmet, pet), or null: the defaults. */
    fun worn(): Loadouts.Worn? = saved().worn

    /** Whether [p3]'s part has a saved layout. */
    fun has(p3: Boolean) = layout(p3) != null

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

    /** Saves [p]'s current layout as [p3]'s part's. Returns what to tell them. */
    fun save(p: ServerPlayer, p3: Boolean): String {
        val inv = p.inventory
        val slots = LinkedHashMap<String, Int>()
        for (i in 0..35) {
            val id = SimItems.idOf(inv.getItem(i)) ?: continue
            if (id.endsWith("_MASK")) continue
            slots.putIfAbsent(id, i)
        }
        if (slots.isEmpty()) return "§cnothing to save: no sim items in your inventory"
        val l = Layout(slots, inv.selectedSlot)
        val s = saved()
        write(if (p3) s.copy(p3 = l, worn = Loadouts.capture(p)) else s.copy(p12 = l, worn = Loadouts.capture(p)))
        return "§7saved your §f${part(p3)}§7 hotbar (${slots.size} items, holding slot ${l.selected + 1}) with your armour, helmet and pet; every hotbar reset uses it now"
    }

    /** Forgets [p3]'s part's layout: its resets go back to the sim's default. */
    fun reset(p3: Boolean): String {
        if (!has(p3)) return "§7the ${part(p3)} hotbar is already the default"
        val s = saved()
        val otherLeft = if (p3) s.p12 != null else s.p3 != null
        write(if (p3) s.copy(p3 = null, worn = if (otherLeft) s.worn else null) else s.copy(p12 = null, worn = if (otherLeft) s.worn else null))
        return "§7${part(p3)} hotbar back to the default"
    }

    private fun part(p3: Boolean) = if (p3) "P3" else "P1/P2"

    private fun write(s: Saved) {
        saved = s
        EngineerClient.safely("p3sim hotbar save") {
            file.parentFile.mkdirs()
            file.writeText(gson.toJson(s))
        }
    }
}
