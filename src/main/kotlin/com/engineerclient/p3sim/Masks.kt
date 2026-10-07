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

    // Names and lore as Hypixel sends them (recorder-2 inv lines); the "Cooldown: Ns" line stays for Odin's timer.
    val SPIRIT_MASK get() = mask(SPIRIT_TEX, "STARRED_SPIRIT_MASK", SimItems.Lore.STARRED_SPIRIT_MASK_NAME, SimItems.Lore.STARRED_SPIRIT_MASK, "mythic")
    // Odin's invincibility timer reads Bonzo's cooldown from "Cooldown: Ns".
    val BONZO_MASK get() = mask(BONZO_TEX, "STARRED_BONZO_MASK", SimItems.Lore.STARRED_BONZO_MASK_NAME, SimItems.Lore.STARRED_BONZO_MASK, "epic")

    private fun mask(tex: String, id: String, name: String, lore: List<String>, style: String): ItemStack {
        val s = SimItems.head(tex, name)
        s.set(DataComponents.LORE, ItemLore(lore.map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", id); it.putBoolean("p3sim", true) }))
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        SimItems.hy(s, null, style)
        return s
    }

    /** Where the spare mask is kept: the first inventory slot (as on Hypixel: /stats' slot 54). */
    const val SPARE_SLOT = 9

    /** Real Masks: the chosen one on your head, the other in [SPARE_SLOT] (/stats swaps them). Off: no masks. */
    fun equip(p: ServerPlayer) {
        val inv = p.inventory
        if (!P3Sim.realMasks) {
            if (SimItems.idOf(p.getItemBySlot(EquipmentSlot.HEAD))?.endsWith("_MASK") == true) SimItems.wear(p, EquipmentSlot.HEAD, ItemStack.EMPTY)
            if (SimItems.idOf(inv.getItem(SPARE_SLOT))?.endsWith("_MASK") == true) inv.setItem(SPARE_SLOT, ItemStack.EMPTY)
        } else {
            val spirit = P3Sim.wornMaskS.value == 0
            SimItems.wear(p, EquipmentSlot.HEAD, if (spirit) SPIRIT_MASK else BONZO_MASK)
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
    const val PHOENIX_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTY0Mjg2NTc3MTM5MSwKICAicHJvZmlsZUlkIiA6ICJiYjdjY2E3MTA0MzQ0NDEyOGQzMDg5ZTEzYmRmYWI1OSIsCiAgInByb2ZpbGVOYW1lIiA6ICJsYXVyZW5jaW8zMDMiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjZiMWI1OWJjODkwYzljOTc1Mjc3ODdkZGUyMDYwMGM4Yjg2ZjZiOTkxMmQ1MWE2YmZjZGIwZTRjMmFhM2M5NyIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"

    /**
     * 9x6 "Stats & Equipment", laid out as Hypixel's (Better PF recordings): black panes; your held
     * item at 2; necklace, cloak, belt, gloves down column 1 (10/19/28/37); helmet, chestplate,
     * leggings, boots down column 2 (11/20/29/38); stat categories on the right; pet at 47; Close,
     * Active Effects, Achievements at 49-51. Your inventory below: click a mask there to wear it
     * (the one you had on goes where it was).
     */
    class StatsMenu(id: Int, inv: net.minecraft.world.entity.player.Inventory, private val sp: ServerPlayer) :
        net.minecraft.world.inventory.ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, inv, net.minecraft.world.SimpleContainer(54), 6) {
        /** The recorded /stats window's names and lore (rec2, pass2 census run34), by slot: equipment heads, stat categories, the pet, the buttons. */
        private val REC_NAME = mapOf(10 to "§6\ue068 Blooming Bone Necklace §6\u272a\u272a\u272a\u272a\u272a", 19 to "§d\ue068 Blooming Shadow Assassin Cloak §6\u272a\u272a\u272a\u272a\u272a", 28 to "§6Blooming Implosion Belt", 37 to "§6Blooming Soulweaver Gloves §6\u272a\u272a\u272a\u272a\u272a", 47 to "§7[Lvl 100] §6Black Cat§5 \u2726")
        private val REC_LORE = mapOf(
            10 to listOf("§7Defense: §a+288", "§7Crit Chance: §9+7.75%", "§7Farming Fortune: §6+32 §9(+5)", "§7Speed: §f+38.4 §9(+6)", "", "§6Ability: Gladiator's Will ", "§7Gain §a+3\ue008 Defense §7for each enemy", "§7within §e10 §7blocks up to §a+30\ue008 Defense§7.", "§7Range increases to §e30 §7blocks and", "§7the cap increases to §a+60\ue008 Defense", "§7when you play as a §aTank §7in dungeons.", "", "§7Increases the range of your", "§aDiversion §7passive by §e15 §7blocks.", "", "§6§l§ka §6§lLEGENDARY DUNGEON NECKLACE §6§l§ka"),
            19 to listOf("§7Strength: §c+160", "§7Farming Fortune: §6+38.4 §9(+6)", "§7Speed: §f+70.4 §9(+6)", "", "§6Piece Bonus: Bloodrush", "§7On teleport: Your next melee hit", "§7within §a5s §7deals §c10% §7more damage.", "§8Cooldown: §a3s", "", "§d§l§ka §d§lMYTHIC DUNGEON CLOAK §d§l§ka"),
            28 to listOf("§7Defense: §a+70", "§7Farming Fortune: §6+5 §9(+5)", "§7Speed: §f+6 §9(+6)", "", "§6Ability: Consolidated ", "§7Increases all explosion damage dealt by §a25%§7.", "", "§6§l§ka §6§lLEGENDARY BELT §6§l§ka"),
            37 to listOf("§7Strength: §c+64", "§7Crit Damage: §9+64%", "§7Farming Fortune: §6+32 §9(+5)", "§7Speed: §f+38.4 §9(+6)", "", "§7While in §cThe Catacombs§7, summon a", "§cHaunted Skull §7that slowly revolves", "§7around you every §a15 §7seconds or", "§7when you kill a mob with melee damage.", "", "§7When an enemy is hit by a §cHaunted", "§cSkull§7, they are stunned for §b2", "§7seconds and deal §c5% §7less damage.", "", "§6§l§ka §6§lLEGENDARY DUNGEON GLOVES §6§l§ka"),
            14 to listOf("§7Stats that influence how much", "§7damage you take and deal when in", "§7combat.", "", " §c\ue010 Health §f6,870.74", " §a\ue008 Defense §f1,345.4", " §f\ue027 True Defense §f8", " §c\ue00d Strength §f1,301.96", " §9\ue02c Crit Chance §f97.75%", " §9\ue007 Crit Damage §f972.78%", " §e\ue001 Attack Speed §f100%", " §c\ue00b Ferocity §f0", " §e\ue024 Swing Range §f8.38", " §b\ue003 Intelligence §f1,074.56", " §c\ue002 Ability Damage §f36%", " §c\ue011 Health Regen §f284.34", " §4\ue028 Vitality §f122", " §a\ue014 Mending §f110", "", "§eClick for details!"),
            15 to listOf("§7Stats that influence what you can", "§7break, how quickly you can break it,", "§7and how many drops you receive", "§7when mining.", "", " §2\ue005 Breaking Power §f0", " §6\ue015 Mining Speed §f256", " §e\ue016 Mining Spread §f0", " §e\ue00f Gemstone Spread §f0", " §5\ue01c Pristine §f0", " §6\ue053 Mining Fortune §f232", " §6\ue053 Ore Fortune §f0", " §6\ue053 Block Fortune §f0", " §6\ue053 Dwarven Metal Fortune §f0", " §6\ue053 Gemstone Fortune §f10", "", "§eClick for details!"),
            16 to listOf("§7Stats that influence how many drops", "§7you receive and how many pests", "§7spawn when farming.", "", " §2\ue019 Bonus Pest Chance §f80%", " §e\ue02b Overbloom §f0", " §6\ue051 Farming Fortune §f455.8", " §6\ue051 Wheat Fortune §f0", " §6\ue051 Carrot Fortune §f0", " §6\ue051 Potato Fortune §f0", " §6\ue051 Pumpkin Fortune §f0", " §6\ue051 Sugar Cane Fortune §f0", " §6\ue051 Melon Slice Fortune §f0", " §6\ue051 Cactus Fortune §f0", " §6\ue051 Cocoa Beans Fortune §f25", " §6\ue051 Mushroom Fortune §f0", " §6\ue051 Nether Wart Fortune §f0", " §6\ue051 Sunflower Fortune §f0", " §6\ue051 Moonflower Fortune §f0", " §6\ue051 Wild Rose Fortune §f0", "", "§eClick for details!"),
            23 to listOf("§7Stat that includes how many drops", "§7you receive when foraging.", "", " §7§m\ue023 Sweep 0", " §6\ue054 Foraging Fortune §f105", " §6\ue054 Fig Fortune §f15", " §6\ue054 Mangrove Fortune §f15", " §6\ue054 Helix Fortune §f0", " §4\ue02e Timber §f0%", "", "§eClick for details!"),
            24 to listOf("§7Stats that influence what you catch", "§7and how quickly you catch it while", "§7fishing.", "", " §b\ue00c Fishing Speed §f15", " §3\ue021 Sea Creature Chance §f22.8%", " §9\ue009 Double Hook Chance §f0%", " §6\ue02a Trophy Chance §f1%", " §6\ue025 Treasure Chance §f3.2%", "", "§eClick for details!"),
            25 to listOf("§7Stats that augment various aspects", "§7of your gameplay.", "", " §f\ue022 Speed §f550", " §b\ue01a Magic Find §f40", " §d\ue013 Pet Luck §f66", " §c\ue012 Heat Resistance §f1", " §b\ue006 Cold Resistance §f1", " §3\ue01d Respiration §f45", " §9\ue01b Pressure Resistance §f30", " §5\ue00a Fear §f6", " §d\ue077 Tracking §f0", "", "§eClick for details!"),
            32 to listOf("§7Stats that influence how quickly you", "§7hunt mobs and how many shards you", "§7get for doing so.", "", " §b\ue02d Pull §f0", " §d\ue05b Hunting Fortune §f26", " §b\u2763 Charm Chance §f1.54%", "", "§eClick for details!"),
            34 to listOf("§7Stats that influence how much §3Skill", "§3XP §7you gain.", "", " §3\u262f Combat Wisdom §f65.5", " §3\u262f Farming Wisdom §f26", " §3\u262f Fishing Wisdom §f27", " §3\u262f Mining Wisdom §f85", " §3\u262f Foraging Wisdom §f26", " §3\u262f Enchanting Wisdom §f25", " §3\u262f Alchemy Wisdom §f25", " §3\u262f Carpentry Wisdom §f25", " §3\u262f Runecrafting Wisdom §f29", " §3\u262f Taming Wisdom §f25", " §3\u262f Social Wisdom §f25", " §3\u262f Hunting Wisdom §f25", "", "§eClick for details!"),
            47 to listOf("§8Combat Pet, Ivory Skin", "", "§7Intelligence: §b+100", "§7Speed: §f+125", "§7Magic Find: §b+15", "§7Pet Luck: §d+15", "", "§6Hunter", "§7Increases your §f\ue022 Speed §7and speed", "§7cap by §a+100§7.", "", "§6Omen", "§7Grants §d+15\ue013 Pet Luck§7.", "", "§6Supernatural", "§7Grants §b+15\ue01a Magic Find§7.", "", "§6Held Item: §5Unalloyed Speed", "§7Grants §f+50 Max Speed Cap§7.", "", "§b§lMAX LEVEL", "§8\u25b8 477,309,419 XP", "", "§8Can be upgraded at Kat in The Hub!", "§6§lLEGENDARY"),
            50 to listOf("§7View and manage all of your active", "§7potion effects.", "", "§7Drink Potions or splash them on the", "§7ground to buff yourself!", "", "§7Currently Active: §e3", "", "§8Also accessible via /effects.", "", "§eClick to view!"),
            51 to listOf("§7View the available §eHypixel", "§7achievements for SkyBlock.", "", "§7These achievements reward", "§eAchievement Points§7, which let you", "§7unlock rewards on the Hypixel", "§7Network.", "", "§7Unlocked: §b225§7/§b336 §8(66%§8)", "§7Points: §e2,090§7/§e3,120 §8(66%§8)", "", "§7Legacy Unlocked: §b1", "§7Legacy Points: §e5", "", "§eClick to view achievements!"),
        )

        /** [s] with the recorded lore for [slot] (if there is one); the stat icons lose their vanilla attribute lines as on main. */
        private fun rec(slot: Int, s: ItemStack): ItemStack {
            REC_LORE[slot]?.let { l -> s.set(DataComponents.LORE, net.minecraft.world.item.component.ItemLore(l.map { Component.literal(it).withStyle { st -> st.withItalic(false) } })) }
            if (slot in 14..16) s.set(DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY)
            return s
        }

        /** What you wear in [eq], or main's grey "Empty ... Slot" pane. */
        private fun worn(eq: EquipmentSlot, empty: String): ItemStack =
            sp.getItemBySlot(eq).copy().takeUnless { it.isEmpty } ?: Terminals.named(net.minecraft.world.item.Items.GRAY_STAINED_GLASS_PANE, "§7Empty $empty Slot")

        init { draw() }

        private fun draw() {
            val c = container
            for (i in 0 until 54) c.setItem(i, Terminals.FILLER)
            c.setItem(2, sp.mainHandItem.copy())
            c.setItem(10, rec(10, SimItems.head(NECKLACE_TEX, REC_NAME.getValue(10))))
            c.setItem(19, rec(19, SimItems.head(CLOAK_TEX, REC_NAME.getValue(19))))
            c.setItem(28, rec(28, SimItems.head(BELT_TEX, REC_NAME.getValue(28))))
            c.setItem(37, rec(37, SimItems.head(GLOVES_TEX, REC_NAME.getValue(37))))
            // Helmet and armour: what you actually wear (main shows your worn pieces, lore and all).
            c.setItem(11, worn(EquipmentSlot.HEAD, "Helmet"))
            c.setItem(20, worn(EquipmentSlot.CHEST, "Chestplate"))
            c.setItem(29, worn(EquipmentSlot.LEGS, "Leggings"))
            c.setItem(38, worn(EquipmentSlot.FEET, "Boots"))
            c.setItem(14, rec(14, Terminals.named(net.minecraft.world.item.Items.STONE_SWORD, "§cCombat Stats")))
            c.setItem(15, rec(15, Terminals.named(net.minecraft.world.item.Items.STONE_PICKAXE, "§6Mining Stats")))
            c.setItem(16, rec(16, Terminals.named(net.minecraft.world.item.Items.GOLDEN_HOE, "§aFarming Stats")))
            c.setItem(23, rec(23, Terminals.named(net.minecraft.world.item.Items.JUNGLE_SAPLING, "§2Foraging Stats")))
            c.setItem(24, rec(24, Terminals.named(net.minecraft.world.item.Items.FISHING_ROD, "§bFishing Stats")))
            c.setItem(25, rec(25, Terminals.named(net.minecraft.world.item.Items.CLOCK, "§dMiscellaneous Stats")))
            c.setItem(32, rec(32, Terminals.named(net.minecraft.world.item.Items.LEAD, "§eHunting Stats")))
            c.setItem(34, rec(34, Terminals.named(net.minecraft.world.item.Items.BOOK, "§3Wisdom Stats")))
            c.setItem(47, if (P3Sim.phoenix) SimItems.head(PHOENIX_TEX, "§7[Lvl 76] §6Phoenix") else rec(47, SimItems.head(BLACK_CAT_TEX, REC_NAME.getValue(47))))
            c.setItem(49, Terminals.named(net.minecraft.world.item.Items.BARRIER, "§cClose"))
            c.setItem(50, rec(50, Terminals.named(net.minecraft.world.item.Items.POTION, "§aActive Effects")))
            c.setItem(51, rec(51, Terminals.named(net.minecraft.world.item.Items.DIAMOND, "§aSkyBlock Achievements")))
        }

        override fun clicked(slot: Int, button: Int, input: net.minecraft.world.inventory.ContainerInput, p: net.minecraft.world.entity.player.Player) {
            if (slot == 49) { sp.closeContainer(); return }
            // Below the window: your inventory (54-80 = inventory 9-35, 81-89 = the hotbar).
            val index = when (slot) { in 54..80 -> slot - 45; in 81..89 -> slot - 81; else -> -1 }
            val clickedItem = if (index >= 0) sp.inventory.getItem(index) else ItemStack.EMPTY
            val id = SimItems.idOf(clickedItem)
            if (id != null && Loadouts.isHelmet(clickedItem)) {
                val worn = sp.getItemBySlot(EquipmentSlot.HEAD).copy()
                sp.setItemSlot(EquipmentSlot.HEAD, clickedItem.copy())
                sp.inventory.setItem(index, worn)
                if (id.endsWith("_MASK")) P3Sim.wornMaskS.value = if (id.endsWith("SPIRIT_MASK")) 0 else 1
                Fight.applySpeed(sp)  // the Racing Helmet adds 100 speed
                Sim.sound(SoundEvents.ARMOR_EQUIP_GENERIC.value(), 1f, 1f)
                if (!P3Sim.realMasks && id.endsWith("_MASK")) Sim.chat("§7Turn on §eReal Masks§7 (menu, Settings) for the one you wear to be the one that saves you.")
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
        // The speed update lands 2-3 ticks after the Autopet chat (PETS-04: 2 x21, 3 x20 of 47); the swap itself is silent
        // bar one vol-0 splash at (200,300,400) (PETS-05).
        Fight.later(2 + kotlin.random.Random.nextInt(2), "pet speed") { Fight.applySpeed(p) }
        // The splash goes to you alone (SimItems.castRod); then main's player_abilities with the new pet's walking speed,
        // ahead of the movement_speed attribute that follows 2-3 ticks on.
        p.abilities.setWalkingSpeed(Fight.speedStat(p).coerceAtLeast(100) / 1000f)
        p.onUpdateAbilities()
        // A rod cast swaps pets on Hypixel through an Autopet rule: its line 2-3 ticks after the rod comes
        // out (party/autopet.mjs, 60 runs: 74 of these lines with the rod held), exactly as below.
        Sim.chat(if (P3Sim.phoenix) "§cAutopet §eequipped your §7[Lvl 100] §5Phoenix§e! §a§lVIEW RULE"
            else "§cAutopet §eequipped your §7[Lvl 100] §6Black Cat§5 ✦§e! §a§lVIEW RULE")
    }

    /** Invincible until (after a proc). */
    private var safeUntil = 0

    /** Phoenix's scream as main names it: an unregistered (direct) sound event. */
    private val PHOENIX_SCREAM = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.parse("minecraft:mob.ghast.affectionate_scream"))

    fun reset() {
        items.forEach { it.readyAt = 0 }; safeUntil = 0
        // Odin's Invincibility Timer restarts with ours (it only ever hears procs, not a sim restart).
        com.engineerclient.EngineerClient.mc.execute { com.engineerclient.misc.OdinMasksUsed.resetTimers() }
        reviveGen++
        if (ghost) { ghost = false; saved = null; Sim.player?.let { p -> restoreArmor(p); p.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY); p.abilities.flying = false; p.abilities.mayfly = false; p.onUpdateAbilities() } }
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
        // A named hit meets the Creeper Veil first (death ticks, by == null, go through it: CLOAK-01).
        if (by != null && SimItems.veilAbsorbs()) return
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
                Sim.sound(SoundEvents.LAVA_EXTINGUISH, 1f, 1.492f, null, net.minecraft.sounds.SoundSource.BLOCKS)
                Sim.sound(SoundEvents.ZOMBIE_INFECT, 1f, 1.19f, null, net.minecraft.sounds.SoundSource.HOSTILE)
                Sim.sound(SoundEvents.ZOMBIE_INFECT, 1f, 1.19f, null, net.minecraft.sounds.SoundSource.HOSTILE)
                // Main sends the legacy id mob.ghast.affectionate_scream (no such sound in 26.1.2's assets, so the client
                // plays nothing): sent as the same direct, unregistered sound event.
                Sim.sound(PHOENIX_SCREAM, 1f, 1.41f + kotlin.random.Random.nextFloat() * 0.15f)
            } else {
                Sim.sound(SoundEvents.GENERIC_EAT, 0.9f, 0.59f, null, net.minecraft.sounds.SoundSource.PLAYERS)
                Sim.sound(SoundEvents.ZOMBIE_VILLAGER_CURE, 1f, 2f, null, net.minecraft.sounds.SoundSource.HOSTILE)
                Sim.chat(item.line)
            }
            goldor?.invoke()
            return
        }
        Sim.chat(if (by == null) "§c ☠ §r§7You died and became a ghost." else "§c ☠ §r§7You were killed by $by and became a ghost.")
        // The Revive Stone line shows in 71% of the deaths on main (DEATH-15); it changes nothing.
        if (kotlin.random.Random.nextInt(100) < 71) Sim.chat("§aYour Revive Stone revived you and broke!")
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
    private var savedArmor: Map<net.minecraft.world.entity.EquipmentSlot, ItemStack>? = null
    private var reviveGen = 0

    private val ARMOR_SLOTS = listOf(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET)

    /** Puts the armour you died in back (INV-11: the ghost's armour slots are empty). */
    private fun restoreArmor(p: ServerPlayer) {
        savedArmor?.forEach { (slot, st) -> p.setItemSlot(slot, st) }
        savedArmor = null
    }

    private fun ghostItem(base: net.minecraft.world.item.Item, name: String, count: Int = 1) =
        ItemStack(base, count).also { it.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { s -> s.withItalic(false) }) }

    private fun becomeGhost(p: ServerPlayer) {
        ghost = true
        val inv = p.inventory
        saved = (0 until 36).map { inv.getItem(it).copy() }
        savedSlot = inv.selectedSlot
        // The Hyperion stays where it is (recorded in 7 of 9 ghost kits); the rest of the bar and the main inventory go.
        for (i in 0 until 36) if (SimItems.idOf(inv.getItem(i)) != "HYPERION") inv.setItem(i, ItemStack.EMPTY)
        // The ghost kit as recorded (INV-11): Haunt in hotbar 1, the class's two abilities in 4 and 6, the Magical Map in 9, Ghost Arrows
        // in the first inventory slot. The Haunt opens the "Teleport to Player" menu; the abilities are inert here. The selected slot stays.
        inv.setItem(0, SimItems.ghostStack(net.minecraft.world.item.Items.PLAYER_HEAD, "HAUNT_ABILITY", "§aHaunt"))
        val (a3, a5) = when (P3Sim.myClass) {
            com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.MAGE -> SimItems.ghostStack(net.minecraft.world.item.Items.BRICKS, "MAGE_DUNGEON_ABILITY_1", "§aPop-up Wall") to SimItems.ghostStack(net.minecraft.world.item.Items.BLAZE_POWDER, "MAGE_DUNGEON_ABILITY_3", "§aFireball")
            com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.TANK -> SimItems.ghostStack(net.minecraft.world.item.Items.PLAYER_HEAD, "TANK_DUNGEON_ABILITY_1", "§aStun Potion") to SimItems.ghostStack(net.minecraft.world.item.Items.PLAYER_HEAD, "TANK_DUNGEON_ABILITY_2", "§aAbsorption Potion")
            // Berserk is the recorded Warrior kit; Archer and Healer were never recorded, so they get it too.
            else -> SimItems.ghostStack(net.minecraft.world.item.Items.PLAYER_HEAD, "WARRIOR_DUNGEON_ABILITY_1", "§aStrength Potion") to SimItems.ghostStack(net.minecraft.world.item.Items.IRON_AXE, "GHOST_THROWING_AXE", "§aGhost Axe")
        }
        inv.setItem(3, a3)
        inv.setItem(5, a5)
        inv.setItem(8, SimItems.ghostStack(net.minecraft.world.item.Items.FILLED_MAP, "MAP", "§aMagical Map"))
        inv.setItem(9, SimItems.ghostStack(net.minecraft.world.item.Items.ARROW, null, "§fGhost Arrow", 10))
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
        Corpse.spawn(p, delay)
        // The corpse wears what you had on; the ghost's own armour slots are empty (INV-11).
        savedArmor = ARMOR_SLOTS.associateWith { p.getItemBySlot(it).copy() }
        ARMOR_SLOTS.forEach { p.setItemSlot(it, ItemStack.EMPTY) }
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
        restoreArmor(p)
        saved = null
        p.inventory.selectedSlot = savedSlot
        p.connection.send(net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(savedSlot))
        p.containerMenu.broadcastChanges()
        p.inventoryMenu.broadcastChanges()
    }
}
