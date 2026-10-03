package com.engineerclient.p3sim

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore

/**
 * The three invincibility items, as Hypixel procs them on a hit that would kill you: Spirit Mask
 * (30 s), Bonzo's Mask (180 s) and Phoenix (60 s), with the game's chat lines (Odin's invincibility
 * timer and Masks Used read them). With none left you die: back to the start of the section.
 *
 * Real Masks off: the first one off cooldown saves you. On (dungeonbreaker.md, masks): they're
 * helmets, only the one you wear saves you, then Phoenix if it's your pet; swap them in /stats,
 * each keeps its own cooldown. The Pet Rod swaps Phoenix and Black Cat.
 */
object Masks {
    private class Item(val id: String, val name: String, val cooldown: Int, val safe: Int, val line: String) { var readyAt = 0 }

    private val items = listOf(
        // The exact lines (chat-attacks.md §1.2; Bonzo's with Hypixel's glyph, dungeonbreaker.md).
        Item("SPIRIT_MASK", "Spirit Mask", 600, 60, "§6Second Wind Activated§r§a! Your Spirit Mask saved your life!"),
        Item("BONZO_MASK", "Bonzo's Mask", 3600, 60, "§aYour §r§9 Bonzo's Mask §r§asaved your life!"),
        Item("PHOENIX", "Phoenix", 1200, 80, "§eYour §r§cPhoenix Pet §r§esaved you from certain death!"),
    )

    // Skins as on Hypixel (dungeonbreaker.md).
    private const val BONZO_TEX = "eyJ0aW1lc3RhbXAiOjE1ODc5MDgzMDU4MjYsInByb2ZpbGVJZCI6IjJkYzc3YWU3OTQ2MzQ4MDI5NDI4MGM4NDIyNzRiNTY3IiwicHJvZmlsZU5hbWUiOiJzYWR5MDYxMCIsInNpZ25hdHVyZVJlcXVpcmVkIjp0cnVlLCJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMTI3MTZlY2JmNWI4ZGEwMGIwNWYzMTZlYzZhZjYxZThiZDAyODA1YjIxZWI4ZTQ0MDE1MTQ2OGRjNjU2NTQ5YyJ9fX0="
    private const val SPIRIT_TEX = "eyJ0aW1lc3RhbXAiOjE1MDUyMjI5OTg3MzQsInByb2ZpbGVJZCI6IjBiZTU2MmUxNzIyODQ3YmQ5MDY3MWYxNzNjNjA5NmNhIiwicHJvZmlsZU5hbWUiOiJ4Y29vbHgzIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsibWV0YWRhdGEiOnsibW9kZWwiOiJzbGltIn0sInVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWJiZTcyMWQ3YWQ4YWI5NjVmMDhjYmVjMGI4MzRmNzc5YjUxOTdmNzlkYTRhZWEzZDEzZDI1M2VjZTlkZWMyIn19fQ=="

    val SPIRIT_MASK get() = mask(SPIRIT_TEX, "SPIRIT_MASK", "§5Spirit Mask §6✪", listOf("§6Ability: Second Wind", "§7Saves you from a death once, then", "§73s of invincibility.", "§8Cooldown: §a30s"))
    // Odin's invincibility timer reads Bonzo's cooldown from "Cooldown: Ns".
    val BONZO_MASK get() = mask(BONZO_TEX, "BONZO_MASK", "§9 Bonzo's Mask", listOf("§6Ability: Clownin' Around", "§7Saves you from a death once, then", "§73s of invincibility.", "§8Cooldown: §a180s"))

    private fun mask(tex: String, id: String, name: String, lore: List<String>): ItemStack {
        val s = SimItems.head(tex, name)
        s.set(DataComponents.LORE, ItemLore(lore.map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", id); it.putBoolean("p3sim", true) }))
        return s
    }

    /** Real Masks: the chosen one on your head (/stats swaps it). */
    fun equip(p: ServerPlayer) {
        if (!P3Sim.realMasks) { if (SimItems.idOf(p.getItemBySlot(EquipmentSlot.HEAD))?.endsWith("_MASK") == true) p.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY); return }
        p.setItemSlot(EquipmentSlot.HEAD, if (P3Sim.wornMaskS.value == 0) SPIRIT_MASK else BONZO_MASK)
    }

    // ------------------------------------------------------------------ /stats: swapping masks

    /** The /stats window: your masks, a click puts that one on (each keeps its own cooldown). */
    fun openStats(p: ServerPlayer) {
        p.openMenu(net.minecraft.world.SimpleMenuProvider({ id, inv, _ -> StatsMenu(id, inv) }, Component.literal("Your Equipment and Stats")))
    }

    private val SLOTS = mapOf(11 to 0, 15 to 1)

    class StatsMenu(id: Int, inv: net.minecraft.world.entity.player.Inventory) :
        net.minecraft.world.inventory.ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x3, id, inv, net.minecraft.world.SimpleContainer(27), 3) {
        init { draw() }

        private fun draw() {
            for (i in 0 until 27) container.setItem(i, Terminals.FILLER)
            val worn = P3Sim.wornMaskS.value
            for ((slot, m) in SLOTS) {
                val s = if (m == 0) SPIRIT_MASK else BONZO_MASK
                val lore = s.get(DataComponents.LORE)?.lines().orEmpty() + Component.literal("") +
                    Component.literal(if (m == worn) "§aCurrently wearing" else "§eClick to wear!")
                s.set(DataComponents.LORE, ItemLore(lore.map { l -> l.copy().withStyle { it.withItalic(false) } }))
                container.setItem(slot, s)
            }
            container.setItem(13, Terminals.named(net.minecraft.world.item.Items.BONE, "§aPet: §6${if (P3Sim.phoenix) "Phoenix" else "Black Cat"} §7(Pet Rod swaps it)"))
        }

        override fun clicked(slot: Int, button: Int, input: net.minecraft.world.inventory.ContainerInput, p: net.minecraft.world.entity.player.Player) {
            val m = SLOTS[slot] ?: return
            val sp = p as ServerPlayer
            if (m != P3Sim.wornMaskS.value) {
                P3Sim.wornMaskS.value = m
                Sim.sound(SoundEvents.ARMOR_EQUIP_GENERIC.value(), 1f, 1f)
                if (P3Sim.realMasks) equip(sp)
                else Sim.chat("§7Turn on §eReal Masks§7 (menu, Settings) for the one you wear to be the one that saves you.")
            }
            draw()
            broadcastChanges()
            sp.inventoryMenu.broadcastChanges()
        }

        override fun quickMoveStack(p: net.minecraft.world.entity.player.Player, i: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(p: net.minecraft.world.entity.player.Player) = true
    }

    // ------------------------------------------------------------------ the pet rod

    /** The Pet Rod: Phoenix (saves you once, 400 speed) <-> Black Cat (your full speed). */
    fun swapPet(p: ServerPlayer) {
        P3Sim.phoenixS.value = !P3Sim.phoenix
        Fight.applySpeed(p)
        Sim.sound(SoundEvents.FISHING_BOBBER_THROW, 0.5f, 0.4f)
        Sim.chat("§aYou summoned your §6${if (P3Sim.phoenix) "Phoenix" else "Black Cat"}§r§a!")
    }

    /** Invincible until (after a proc). */
    private var safeUntil = 0

    fun reset() { items.forEach { it.readyAt = 0 }; safeUntil = 0 }

    private fun worn(): String? = Sim.player?.let { SimItems.idOf(it.getItemBySlot(EquipmentSlot.HEAD)) }

    /** For the menu: each one's cooldown (and which you're wearing). */
    fun status(): String {
        val worn = worn()
        return items.filter { it.id != "PHOENIX" || P3Sim.phoenix }.joinToString(" ") {
            val left = it.readyAt - Fight.serverTick
            val mark = if (P3Sim.realMasks && it.id == worn) "§e⛑" else ""
            mark + if (left <= 0) "§a${it.name}" else "§c${it.name} ${(left + 19) / 20}s"
        }
    }

    /** [by]: the killer named in the death line, or null for the plain "You died" (chat-attacks.md §1.2). */
    fun hit(p: ServerPlayer, by: String?) {
        val now = Fight.serverTick
        if (now < safeUntil) return
        val ready = items.filter { it.readyAt <= now && (it.id != "PHOENIX" || P3Sim.phoenix) }
        val item = if (!P3Sim.realMasks) ready.firstOrNull()
            else ready.firstOrNull { it.id == SimItems.idOf(p.getItemBySlot(EquipmentSlot.HEAD)) }
                ?: ready.firstOrNull { it.id == "PHOENIX" }
        if (item != null) {
            item.readyAt = now + item.cooldown
            safeUntil = now + item.safe
            Sim.chat(item.line)
            // Proc sounds as measured (chat-attacks.md §2): masks cure + wither + eat, Phoenix extinguish + infect + wither.
            if (item.id == "PHOENIX") {
                Sim.sound(SoundEvents.LAVA_EXTINGUISH, 1f, 1.49f)
                Sim.sound(SoundEvents.ZOMBIE_INFECT, 1f, 1.19f)
            } else {
                Sim.sound(SoundEvents.ZOMBIE_VILLAGER_CURE, 1f, 2f)
                Sim.sound(SoundEvents.GENERIC_EAT, 0.9f, 0.59f)
            }
            Sim.sound(SoundEvents.WITHER_AMBIENT, 1f, 1f)
            return
        }
        Sim.chat(if (by == null) "§c ☠ §r§7You died and became a ghost." else "§c ☠ §r§7You were killed by $by and became a ghost.")
        Sim.title("§cYou died", "§7No masks left", 0, 40, 10)
        val phase = Fight.phase as? GoldorPhase
        val spot = phase?.let { Spots.p3Start(it.section.coerceIn(1, 5)) } ?: Spots.LOBBY
        Sim.tp(p, spot.x, spot.y, spot.z, spot.yaw, spot.pitch)
        safeUntil = now + 60
    }
}
