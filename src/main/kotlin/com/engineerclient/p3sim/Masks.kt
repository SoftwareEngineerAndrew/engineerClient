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
        // Phoenix covers no longer than a mask (3 s, not its lore's 4 s): on Hypixel the next death tick, 60 ticks on, still hits (analysis/masks).
        Item("PHOENIX", "Phoenix", 1200, 60, "§eYour §r§cPhoenix Pet §r§esaved you from certain death!"),
    )

    // Skins as on Hypixel (dungeonbreaker.md).
    private const val BONZO_TEX = "eyJ0aW1lc3RhbXAiOjE1ODc5MDgzMDU4MjYsInByb2ZpbGVJZCI6IjJkYzc3YWU3OTQ2MzQ4MDI5NDI4MGM4NDIyNzRiNTY3IiwicHJvZmlsZU5hbWUiOiJzYWR5MDYxMCIsInNpZ25hdHVyZVJlcXVpcmVkIjp0cnVlLCJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMTI3MTZlY2JmNWI4ZGEwMGIwNWYzMTZlYzZhZjYxZThiZDAyODA1YjIxZWI4ZTQ0MDE1MTQ2OGRjNjU2NTQ5YyJ9fX0="
    private const val SPIRIT_TEX = "eyJ0aW1lc3RhbXAiOjE1MDUyMjI5OTg3MzQsInByb2ZpbGVJZCI6IjBiZTU2MmUxNzIyODQ3YmQ5MDY3MWYxNzNjNjA5NmNhIiwicHJvZmlsZU5hbWUiOiJ4Y29vbHgzIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsibWV0YWRhdGEiOnsibW9kZWwiOiJzbGltIn0sInVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWJiZTcyMWQ3YWQ4YWI5NjVmMDhjYmVjMGI4MzRmNzc5YjUxOTdmNzlkYTRhZWEzZDEzZDI1M2VjZTlkZWMyIn19fQ=="

    val SPIRIT_MASK get() = mask(SPIRIT_TEX, "STARRED_SPIRIT_MASK", "§d\ue068 Necrotic Spirit Mask §6✪✪✪✪✪", listOf("§6Ability: Second Wind", "§7Instead of dying, gain §a+50 Speed§7 and", "§7damage immunity for §a3§7 seconds. Also heals you", "§7for §a10%§7 of your max health.", "§8Cooldown: §a30s"))
    // Odin's invincibility timer reads Bonzo's cooldown from "Cooldown: Ns".
    val BONZO_MASK get() = mask(BONZO_TEX, "STARRED_BONZO_MASK", "§5\ue068 Sunny Bonzo's Mask §6✪✪✪✪✪", listOf("§6Ability: Clownin' Around", "§7Gain §c+40 Strength§7 and fully heal when you", "§7would die, then §a3§7 seconds of immunity.", "§8Cooldown: §a180s"))

    private fun mask(tex: String, id: String, name: String, lore: List<String>): ItemStack {
        val s = SimItems.head(tex, name)
        s.set(DataComponents.LORE, ItemLore(lore.map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", id); it.putBoolean("p3sim", true) }))
        return s
    }

    /** Where the spare mask is kept: the first inventory slot (as on Hypixel: /stats' slot 54). */
    const val SPARE_SLOT = 9

    /** Real Masks: the chosen one on your head, the other in [SPARE_SLOT] (/stats swaps them). Off: no masks. */
    fun equip(p: ServerPlayer) {
        val inv = p.inventory
        if (!P3Sim.realMasks) {
            if (SimItems.idOf(p.getItemBySlot(EquipmentSlot.HEAD))?.endsWith("_MASK") == true) p.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY)
            if (SimItems.idOf(inv.getItem(SPARE_SLOT))?.endsWith("_MASK") == true) inv.setItem(SPARE_SLOT, ItemStack.EMPTY)
        } else {
            val spirit = P3Sim.wornMaskS.value == 0
            p.setItemSlot(EquipmentSlot.HEAD, if (spirit) SPIRIT_MASK else BONZO_MASK)
            inv.setItem(SPARE_SLOT, if (spirit) BONZO_MASK else SPIRIT_MASK)
        }
        p.inventoryMenu.broadcastChanges()
    }

    // ------------------------------------------------------------------ /stats: Stats & Equipment

    /** Hypixel's /stats window (as recorded in Better PF runs); click a mask in your inventory below to wear it. */
    fun openStats(p: ServerPlayer) {
        p.openMenu(net.minecraft.world.SimpleMenuProvider({ id, inv, _ -> StatsMenu(id, inv, p) }, Component.literal("Stats & Equipment")))
    }

    // Equipment as in the recordings (the heads' skins; the armour's dyes).
    private const val NECKLACE_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTY5MjI5ODE4Nzc4NiwKICAicHJvZmlsZUlkIiA6ICI1MWIyZGY3NWEyYWM0OTA5YmM4YzlkMzM3Y2EwNDNkYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJMaWNvcm5lQXVCZXVycmUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWEzNjFhNjdiNjNkMDQ1YTBhNjNiNTI1YzFhNzAxMjhmNjkwOWVmMWFjN2JjYzZlNDYzMWViODk1ZjA3NTAyZCIKICAgIH0KICB9Cn0="
    private const val CLOAK_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTY5MjI5ODIwNzA1MywKICAicHJvZmlsZUlkIiA6ICIzZWUxYWRlMzljZDI0ZjFkOWYwODliYjA2ZTkzNTY5YSIsCiAgInByb2ZpbGVOYW1lIiA6ICJSdXNvR01SIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2ZhMjQzMTE0ODU3MmZlZDdiYzFlYWNmMGQyMjlkZGIyMTE1ZDFhMmNhMTgxZDMyM2QzZmNhNTIyNmU1MTZhMWQiCiAgICB9CiAgfQp9"
    private const val BELT_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTY0MzYwMjI5OTA2MSwKICAicHJvZmlsZUlkIiA6ICI0ZTMwZjUwZTdiYWU0M2YzYWZkMmE3NDUyY2ViZTI5YyIsCiAgInByb2ZpbGVOYW1lIiA6ICJfdG9tYXRvel8iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjFkMmIwMzZkZDY2NGJiOTBjOWQ0NDNjMTk5OGZiNTI2Mzk4YWI0ZGRkZWI3OWI4NDAxYjE2YjlhNGQxMGJhMyIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"
    private const val GLOVES_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTY5MjI5ODIyMjY4MywKICAicHJvZmlsZUlkIiA6ICI4NzE3ZGFhNmM3OTU0NzE2YmJlYWQ0MDRkYzg0NDQzZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTa3VsbDAwMDAiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTUyMjg2NzcyMTJiZTQzZWFhZDIzZDQ3ZWQ4NDNlMTVmYjFlNjgzODQ1OTRjMDliNThiMjNmODI0MjdlNTQ5YSIKICAgIH0KICB9Cn0="
    private const val BLACK_CAT_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTcwODczNzEyMTIzNSwKICAicHJvZmlsZUlkIiA6ICJmY2ZhYTg0MzA0YjE0NDUxOThkNWYxNzQ3ZjI0Y2Q5MCIsCiAgInByb2ZpbGVOYW1lIiA6ICJTdGV3eVdvbGZ5IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzgyODJiNWE5YmJlMmNkMzIyMzcyNDAyM2NkNGY2YWQ0MTNmNWJiOWUwZWRlZjgxNzAwYjhhZmMzMDcyZDA0YTUiCiAgICB9CiAgfQp9"
    private const val PHOENIX_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTY0Mjg2NTc3MTM5MSwKICAicHJvZmlsZUlkIiA6ICJiYjdjY2E3MTA0MzQ0NDEyOGQzMDg5ZTEzYmRmYWI1OSIsCiAgInByb2ZpbGVOYW1lIiA6ICJsYXVyZW5jaW8zMDMiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjZiMWI1OWJjODkwYzljOTc1Mjc3ODdkZGUyMDYwMGM4Yjg2ZjZiOTkxMmQ1MWE2YmZjZGIwZTRjMmFhM2M5NyIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"

    private fun leather(item: net.minecraft.world.item.Item, rgb: Int, name: String): ItemStack =
        Terminals.named(item, name).also { it.set(DataComponents.DYED_COLOR, net.minecraft.world.item.component.DyedItemColor(rgb)) }

    /**
     * 9x6 "Stats & Equipment", laid out as Hypixel's (Better PF recordings): black panes; your held
     * item at 2; necklace, cloak, belt, gloves down column 1 (10/19/28/37); helmet, chestplate,
     * leggings, boots down column 2 (11/20/29/38); stat categories on the right; pet at 47; Close,
     * Active Effects, Achievements at 49-51. Your inventory below: click a mask there to wear it
     * (the one you had on goes where it was).
     */
    class StatsMenu(id: Int, inv: net.minecraft.world.entity.player.Inventory, private val sp: ServerPlayer) :
        net.minecraft.world.inventory.ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, inv, net.minecraft.world.SimpleContainer(54), 6) {
        init { draw() }

        private fun draw() {
            val c = container
            for (i in 0 until 54) c.setItem(i, Terminals.FILLER)
            c.setItem(2, sp.mainHandItem.copy())
            c.setItem(10, SimItems.head(NECKLACE_TEX, "§6 Strengthened Bone Necklace §6✪✪✪✪✪"))
            c.setItem(19, SimItems.head(CLOAK_TEX, "§6 Menacing Shadow Assassin Cloak §6✪✪✪✪✪"))
            c.setItem(28, SimItems.head(BELT_TEX, "§5Implosion Belt"))
            c.setItem(37, SimItems.head(GLOVES_TEX, "§6Menacing Soulweaver Gloves §6✪✪✪✪✪"))
            c.setItem(11, sp.getItemBySlot(EquipmentSlot.HEAD).copy().takeUnless { it.isEmpty } ?: Terminals.named(net.minecraft.world.item.Items.GRAY_STAINED_GLASS_PANE, "§7Empty Helmet Slot"))
            c.setItem(20, leather(net.minecraft.world.item.Items.LEATHER_CHESTPLATE, 0x42c99a, "§d✿ Loving Maxor's Chestplate §6✪✪✪✪✪§c➎"))
            c.setItem(29, leather(net.minecraft.world.item.Items.LEATHER_LEGGINGS, 0x68fba0, "§d✿ Necrotic Maxor's Leggings §6✪✪✪✪✪§c➎"))
            c.setItem(38, leather(net.minecraft.world.item.Items.LEATHER_BOOTS, 0x57f6c0, "§d✿ Necrotic Maxor's Boots §6✪✪✪✪✪§c➎"))
            c.setItem(14, Terminals.named(net.minecraft.world.item.Items.STONE_SWORD, "§cCombat Stats"))
            c.setItem(15, Terminals.named(net.minecraft.world.item.Items.STONE_PICKAXE, "§6Mining Stats"))
            c.setItem(16, Terminals.named(net.minecraft.world.item.Items.GOLDEN_HOE, "§eFarming Stats"))
            c.setItem(23, Terminals.named(net.minecraft.world.item.Items.JUNGLE_SAPLING, "§2Foraging Stats"))
            c.setItem(24, Terminals.named(net.minecraft.world.item.Items.FISHING_ROD, "§bFishing Stats"))
            c.setItem(25, Terminals.named(net.minecraft.world.item.Items.CLOCK, "§dMiscellaneous Stats"))
            c.setItem(32, Terminals.named(net.minecraft.world.item.Items.LEAD, "§aHunting Stats"))
            c.setItem(34, Terminals.named(net.minecraft.world.item.Items.BOOK, "§3Wisdom Stats"))
            c.setItem(47, if (P3Sim.phoenix) SimItems.head(PHOENIX_TEX, "§7[Lvl 76] §6Phoenix") else SimItems.head(BLACK_CAT_TEX, "§7[Lvl 100] §6Black Cat"))
            c.setItem(49, Terminals.named(net.minecraft.world.item.Items.BARRIER, "§cClose"))
            c.setItem(50, Terminals.named(net.minecraft.world.item.Items.POTION, "§aActive Effects"))
            c.setItem(51, Terminals.named(net.minecraft.world.item.Items.DIAMOND, "§aSkyBlock Achievements"))
        }

        override fun clicked(slot: Int, button: Int, input: net.minecraft.world.inventory.ContainerInput, p: net.minecraft.world.entity.player.Player) {
            if (slot == 49) { sp.closeContainer(); return }
            // Below the window: your inventory (54-80 = inventory 9-35, 81-89 = the hotbar).
            val index = when (slot) { in 54..80 -> slot - 45; in 81..89 -> slot - 81; else -> -1 }
            val clickedItem = if (index >= 0) sp.inventory.getItem(index) else ItemStack.EMPTY
            val id = SimItems.idOf(clickedItem)
            if (id != null && id.endsWith("_MASK")) {
                val worn = sp.getItemBySlot(EquipmentSlot.HEAD).copy()
                sp.setItemSlot(EquipmentSlot.HEAD, clickedItem.copy())
                sp.inventory.setItem(index, worn)
                P3Sim.wornMaskS.value = if (id.endsWith("SPIRIT_MASK")) 0 else 1
                Sim.sound(SoundEvents.ARMOR_EQUIP_GENERIC.value(), 1f, 1f)
                if (!P3Sim.realMasks) Sim.chat("§7Turn on §eReal Masks§7 (menu, Settings) for the one you wear to be the one that saves you.")
            }
            draw()
            broadcastFullState()
            sp.inventoryMenu.broadcastChanges()
        }

        override fun quickMoveStack(p: net.minecraft.world.entity.player.Player, i: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(p: net.minecraft.world.entity.player.Player) = true
    }

    // ------------------------------------------------------------------ the pet rod

    /** The Pet Rod: Phoenix (saves you once, no Black Cat bonus) <-> Black Cat (+100 speed); applySpeed does the rest. */
    fun swapPet(p: ServerPlayer) {
        P3Sim.phoenixS.value = !P3Sim.phoenix
        Fight.applySpeed(p)
        Sim.sound(SoundEvents.FISHING_BOBBER_THROW, 0.5f, 0.4f)
        // A rod cast swaps pets on Hypixel through an Autopet rule: its line 2-3 ticks after the rod comes
        // out (party/autopet.mjs, 60 runs: 74 of these lines with the rod held), exactly as below.
        Sim.chat(if (P3Sim.phoenix) "§cAutopet §eequipped your §7[Lvl 100] §5Phoenix§e! §a§lVIEW RULE"
            else "§cAutopet §eequipped your §7[Lvl 100] §6Black Cat§5 ✦§e! §a§lVIEW RULE")
    }

    /** Invincible until (after a proc). */
    private var safeUntil = 0

    fun reset() {
        items.forEach { it.readyAt = 0 }; safeUntil = 0
        // Odin's Invincibility Timer restarts with ours (it only ever hears procs, not a sim restart).
        com.engineerclient.EngineerClient.mc.execute { com.engineerclient.misc.OdinMasksUsed.resetTimers() }
        reviveGen++
        if (ghost) { ghost = false; saved = null; Sim.player?.let { p -> p.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY); p.abilities.flying = false; p.abilities.mayfly = false; p.onUpdateAbilities() } }
    }

    private fun worn(): String? = Sim.player?.let { SimItems.idOf(it.getItemBySlot(EquipmentSlot.HEAD))?.removePrefix("STARRED_") }

    /** For the menu: each one's cooldown (and which you're wearing). */
    fun status(): String {
        val worn = worn()
        return items.filter { it.id != "PHOENIX" || P3Sim.phoenix || !P3Sim.realMasks }.joinToString(" ") {
            val left = it.readyAt - Fight.serverTick
            val mark = if (P3Sim.realMasks && it.id == worn) "§e⛑" else ""
            mark + if (left <= 0) "§a${it.name}" else "§c${it.name} ${(left + 19) / 20}s"
        }
    }

    /** [by]: the killer named in the death line, or null for the plain "You died" (chat-attacks.md §1.2). */
    fun hit(p: ServerPlayer, by: String?, goldor: (() -> Unit)? = null) {
        val now = Fight.serverTick
        // [goldor]: Goldor's line and its quiet wither.ambient, which come after the death/proc chat and sounds (MASKS-04, DEATH-10).
        if (ghost || now < safeUntil) { goldor?.invoke(); return }
        val ready = items.filter { it.readyAt <= now && (it.id != "PHOENIX" || P3Sim.phoenix || !P3Sim.realMasks) }
        val item = if (!P3Sim.realMasks) ready.firstOrNull()
            else ready.firstOrNull { it.id == SimItems.idOf(p.getItemBySlot(EquipmentSlot.HEAD))?.removePrefix("STARRED_") }
                ?: ready.firstOrNull { it.id == "PHOENIX" }
        if (item != null) {
            item.readyAt = now + item.cooldown
            // Auto (Real Masks off): Phoenix saves you whatever pet is out, swapped in as it does.
            if (item.id == "PHOENIX" && !P3Sim.phoenix) { P3Sim.phoenixS.value = true; Fight.applySpeed(p) }
            safeUntil = now + item.safe
            // Proc particles as recorded (MASKS-08): explosion x3 at your feet, Phoenix adds lava x18.
            Sim.level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION, p.x, p.y, p.z, 3, 1.0, 1.0, 1.0, 0.0)
            if (item.id == "PHOENIX") Sim.level.sendParticles(net.minecraft.core.particles.ParticleTypes.LAVA, p.x, p.y, p.z, 18, 0.1, 0.1, 0.1, 0.08)
            // Proc order as recorded (MASKS-04/06/07): masks eat + cure then the proc chat; Phoenix chat, then
            // extinguish, two infects and the ghast scream; then Goldor's line. No enderman sound on Spirit.
            if (item.id == "PHOENIX") {
                Sim.chat(item.line)
                Sim.sound(SoundEvents.LAVA_EXTINGUISH, 1f, 1.49f, null, net.minecraft.sounds.SoundSource.BLOCKS)
                Sim.sound(SoundEvents.ZOMBIE_INFECT, 1f, 1.19f)
                Sim.sound(SoundEvents.ZOMBIE_INFECT, 1f, 1.19f)
                Sim.sound(SoundEvents.GHAST_SCREAM, 1f, 1.41f + kotlin.random.Random.nextFloat() * 0.15f)
            } else {
                Sim.sound(SoundEvents.GENERIC_EAT, 0.9f, 0.59f)
                Sim.sound(SoundEvents.ZOMBIE_VILLAGER_CURE, 1f, 2f)
                Sim.chat(item.line)
            }
            goldor?.invoke()
            return
        }
        Sim.chat(if (by == null) "§c ☠ §r§7You died and became a ghost." else "§c ☠ §r§7You were killed by $by and became a ghost.")
        // The Revive Stone line shows in about half of the deaths on main (DEATH-15); it changes nothing.
        if (kotlin.random.Random.nextBoolean()) Sim.chat("§aYour Revive Stone revived you and broke!")
        becomeGhost(p)
        Sim.sound(SoundEvents.GENERIC_HURT, 1f, 0.889f, null, net.minecraft.sounds.SoundSource.NEUTRAL)
        goldor?.invoke()
        // No immunity after dying or a revive (rec2 14-01-12: killed again on the next death tick).
    }

    // ------------------------------------------------------------------ the ghost

    /** Dead and waiting for a revive: invisible, flying where you died, with the ghost kit. */
    var ghost = false
        private set
    private var saved: List<ItemStack>? = null
    private var savedSlot = 0
    private var reviveGen = 0

    private fun ghostItem(base: net.minecraft.world.item.Item, name: String, count: Int = 1) =
        ItemStack(base, count).also { it.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { s -> s.withItalic(false) }) }

    private fun becomeGhost(p: ServerPlayer) {
        ghost = true
        val inv = p.inventory
        saved = (0 until 36).map { inv.getItem(it).copy() }
        savedSlot = inv.selectedSlot
        for (i in 0 until 36) inv.setItem(i, ItemStack.EMPTY)
        // The ghost kit as recorded (DEATH-05): Haunt in hotbar 1, potions and axe, the map, Ghost Arrows. Inert here
        // (the potions are class-dependent on Hypixel; the Haunt menu is not modelled).
        inv.setItem(0, ghostItem(net.minecraft.world.item.Items.PLAYER_HEAD, "§aHaunt"))
        inv.setItem(3, ghostItem(net.minecraft.world.item.Items.POTION, "§fStrength Potion"))
        inv.setItem(5, ghostItem(net.minecraft.world.item.Items.IRON_AXE, "§fGhost Axe"))
        inv.setItem(8, ghostItem(net.minecraft.world.item.Items.MAP, "§fMagical Map"))
        inv.setItem(9, ghostItem(net.minecraft.world.item.Items.ARROW, "§fGhost Arrow", 10))
        inv.selectedSlot = 0
        p.connection.send(net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0))
        p.addEffect(net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, -1, 0, false, false, false))
        p.abilities.mayfly = true
        p.abilities.flying = true
        p.onUpdateAbilities()
        p.containerMenu.broadcastChanges()
        p.inventoryMenu.broadcastChanges()
        Sim.title("§eYou became a ghost!", "§7Hopefully your teammates will be able to revive you!", 0, 100, 5)
        // Revived 119-137 ticks on (median ~124); the countdown titles run 5..1 from 100 ticks before (DEATH-01/04).
        val delay = if (kotlin.random.Random.nextInt(10) < 6) kotlin.random.Random.nextInt(120, 126) else kotlin.random.Random.nextInt(119, 138)
        val gen = ++reviveGen
        for (k in 0 until 5) Fight.later(delay - 100 + 20 * k, "revive title") {
            if (ghost && gen == reviveGen) Sim.title("§e§lBEING REVIVED", "§aYou will be revived in ${5 - k}s", 0, 30, 0)
        }
        Fight.later(delay, "revive") { if (ghost && gen == reviveGen) revive(p) }
    }

    private fun revive(p: ServerPlayer) {
        // The reviver: the nearest bot (reviver choice isn't known; bots never die). No bots: a self-revive in place.
        val bot = Party.bots().filter { it.entity != null }.minByOrNull { it.pos.distanceToSqr(p.position()) }
        endGhost(p)
        Sim.chat("§a ❣ §r§b${Sim.me}§r§a was revived by §r§b${bot?.name ?: Sim.me}§r§a!")
        Sim.chat("§cAutopet rule triggered but couldn't find your pet!")
        if (bot != null) Sim.tp(p, bot.pos.x, bot.pos.y, bot.pos.z, p.yRot, p.xRot)
    }

    /** Back to normal: abilities, visibility and the inventory you had. */
    private fun endGhost(p: ServerPlayer) {
        ghost = false
        p.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY)
        p.abilities.flying = false
        p.abilities.mayfly = false
        p.onUpdateAbilities()
        saved?.let { s -> s.forEachIndexed { i, st -> p.inventory.setItem(i, st) } }
        saved = null
        p.inventory.selectedSlot = savedSlot
        p.connection.send(net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(savedSlot))
        p.containerMenu.broadcastChanges()
        p.inventoryMenu.broadcastChanges()
    }
}
