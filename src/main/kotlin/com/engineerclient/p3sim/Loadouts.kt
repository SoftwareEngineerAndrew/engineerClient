package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.JsonParser
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore

/**
 * Hypixel's Loadouts window ("(1/3) Loadouts"), opened with /loadouts (recorder-2: 556 `/loadouts` commands,
 * 996 opens, the rest being the window re-sent after a click). Its items, slots and lore are the recorded
 * ones (assets/engineerclient/p3sim/loadouts.json, from the first open of rec2 13-30-21_F7_c48518; the
 * helmet / chest / legs / boots / pet slots show what you wear right now). Of Andrew's saved loadouts only
 * the four the sim models are kept: Cat terms, Phoenix terms, Terror (renamed from "Speed Terror") and
 * Mask terms. A click equips: armour, helmet and pet change, speed follows through [Fight.applySpeed], and
 * the chat line and sounds are the recorded ones (lever click 0.5, then "You equipped X!", then horse saddle 1.0).
 */
object Loadouts {
    private class Entry(val slot: Int, val item: String, val name: String, val lore: List<String>, val tex: String?,
                        val dye: Int?, val glint: Boolean, val style: String?, val id: String?)

    private val entries: Map<Int, Entry> by lazy {
        val out = HashMap<Int, Entry>()
        EngineerClient.safely("p3sim loadouts.json") {
            val text = Loadouts::class.java.getResourceAsStream("/assets/engineerclient/p3sim/loadouts.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@safely
            for (e in JsonParser.parseString(text).asJsonArray) {
                val o = e.asJsonObject
                fun str(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString
                out[o.get("slot").asInt] = Entry(o.get("slot").asInt, o.get("item").asString, str("name") ?: "",
                    o.getAsJsonArray("lore").map { it.asString }, str("tex"), o.get("dye")?.asInt, o.get("glint")?.asBoolean ?: false, str("style"), str("id"))
            }
        }
        out
    }

    private fun Entry.stack(): ItemStack {
        val s = if (tex != null) SimItems.head(tex, name)
            else Terminals.named(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(item)), name)
        if (lore.isNotEmpty()) s.set(DataComponents.LORE, ItemLore(lore.map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        if (glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        if (dye != null) s.set(DataComponents.DYED_COLOR, net.minecraft.world.item.component.DyedItemColor(dye))
        if (id != null) s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", id); it.putBoolean("p3sim", true) }))
        if (style != null) SimItems.hy(s, null, style)
        return s
    }

    /** Terror Helmet as recorded (rec2 13-30-21 inventory slot 9); worn with the set it is Hydra Strike 4/4. */
    private fun terrorHelmet(): ItemStack {
        val e = entries[54] ?: return ItemStack.EMPTY
        return Entry(e.slot, e.item, e.name, e.lore.map { it.replace("(3/4)", "(4/4)").replace("after 7s", "after 10s") }, e.tex, e.dye, e.glint, e.style, e.id).stack()
    }

    private enum class Helm { RACING, MASK, TERROR }
    private class Def(val slot: Int, val name: String, val set: SimItems.ArmorSet, val helm: Helm, val phoenix: Boolean)

    // Gear, pet and speed as the recorded loadouts' lore and walking speeds (Cat 0.65, Phoenix 0.55 with the Racing Helmet; Terror and Mask 0.55 with Black Cat).
    private val defs = listOf(
        Def(24, "Cat terms", SimItems.ArmorSet.WISE, Helm.RACING, false),
        Def(23, "Phoenix terms", SimItems.ArmorSet.WISE, Helm.RACING, true),
        Def(34, "Terror", SimItems.ArmorSet.TERROR, Helm.TERROR, false),
        Def(41, "Mask terms", SimItems.ArmorSet.MAXOR, Helm.MASK, false),
    )

    fun open(p: ServerPlayer) {
        p.openMenu(SimpleMenuProvider({ id, inv, _ -> LoadoutsMenu(id, inv, p) }, Component.literal("(1/3) Loadouts")))
    }

    /** Anything that can sit on your head in the sim: a mask, the Racing / Wise / Terror helmet. */
    fun isHelmet(s: ItemStack): Boolean {
        val id = SimItems.idOf(s) ?: return false
        return id.endsWith("_MASK") || id.endsWith("_HELMET")
    }

    private fun wearsHelm(h: Helm, s: ItemStack): Boolean {
        val id = SimItems.idOf(s) ?: return false
        return when (h) { Helm.RACING -> id == "RACING_HELMET"; Helm.TERROR -> id == "TERROR_HELMET"; Helm.MASK -> id.endsWith("_MASK") }
    }

    private fun chestId(set: SimItems.ArmorSet) = when (set) {
        SimItems.ArmorSet.MAXOR -> "MITHRIL_COAT"; SimItems.ArmorSet.TERROR -> "TERROR_CHESTPLATE"; SimItems.ArmorSet.WISE -> "WISE_WITHER_CHESTPLATE"
    }

    private fun matches(p: ServerPlayer, d: Def) =
        SimItems.idOf(p.getItemBySlot(EquipmentSlot.CHEST)) == chestId(d.set) && wearsHelm(d.helm, p.getItemBySlot(EquipmentSlot.HEAD)) && P3Sim.phoenix == d.phoenix

    /** The helmet [h] on your head; the one you had goes to the spare slot (or the first free one), a copy already in your inventory is swapped in. */
    private fun wearHelmet(p: ServerPlayer, h: Helm) {
        val inv = p.inventory
        val old = p.getItemBySlot(EquipmentSlot.HEAD).copy()
        if (wearsHelm(h, old)) return
        val wantMask = if (P3Sim.wornMaskS.value == 0) "SPIRIT_MASK" else "BONZO_MASK"
        val candidates = (0 until 36).filter { wearsHelm(h, inv.getItem(it)) }
        val from = (if (h == Helm.MASK) candidates.firstOrNull { SimItems.idOf(inv.getItem(it))?.removePrefix("STARRED_") == wantMask } else null) ?: candidates.firstOrNull()
        if (from != null) {
            p.setItemSlot(EquipmentSlot.HEAD, inv.getItem(from).copy())
            inv.setItem(from, old)
        } else {
            when (h) {
                Helm.RACING -> SimItems.equipHelmet(p, false)
                Helm.TERROR -> p.setItemSlot(EquipmentSlot.HEAD, terrorHelmet())
                Helm.MASK -> p.setItemSlot(EquipmentSlot.HEAD, if (wantMask == "SPIRIT_MASK") Masks.SPIRIT_MASK else Masks.BONZO_MASK)
            }
            if (!old.isEmpty) {
                val slot = if (inv.getItem(Masks.SPARE_SLOT).isEmpty) Masks.SPARE_SLOT else (9 until 36).firstOrNull { inv.getItem(it).isEmpty }
                if (slot != null) inv.setItem(slot, old)
            }
        }
        if (h == Helm.MASK) SimItems.idOf(p.getItemBySlot(EquipmentSlot.HEAD))?.let { P3Sim.wornMaskS.value = if (it.endsWith("SPIRIT_MASK")) 0 else 1 }
    }

    private fun sound(id: String, vol: Float, pitch: Float, src: SoundSource) {
        BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(id))?.let { Sim.sound(it, vol, pitch, null, src) }
    }

    /** Equips [d] as Hypixel does: a tick after the click the lever clicks, the green chat line prints and the saddle creaks. */
    private fun equip(p: ServerPlayer, d: Def) {
        if (matches(p, d)) { Sim.chatStyled("§c${d.name} is already equipped!"); return }
        Fight.later(1, "loadout ${d.name}") {
            SimItems.equipArmor(p, d.set)
            wearHelmet(p, d.helm)
            P3Sim.phoenixS.value = d.phoenix
            Fight.applySpeed(p)
            sound("minecraft:block.lever.click", 0.5f, 1f, SoundSource.BLOCKS)
            Sim.chatStyled("§aYou equipped ${d.name}!")
            sound("minecraft:entity.horse.saddle", 1f, 1f, SoundSource.NEUTRAL)
            p.inventoryMenu.broadcastChanges()
            (p.containerMenu as? LoadoutsMenu)?.refresh()
        }
    }

    /** 9x6 as recorded; your gear (11 helmet, 20/29/38 armour, 21 pet) is live. Your inventory is below, inert. */
    class LoadoutsMenu(id: Int, inv: Inventory, private val sp: ServerPlayer) :
        ChestMenu(MenuType.GENERIC_9x6, id, inv, SimpleContainer(54), 6) {
        init { draw() }

        fun refresh() { draw(); broadcastFullState() }

        private fun draw() {
            val c = container
            for (i in 0 until 54) c.setItem(i, Terminals.FILLER)
            for ((slot, e) in entries) if (slot < 54) c.setItem(slot, e.stack())
            val head = sp.getItemBySlot(EquipmentSlot.HEAD).copy()
            c.setItem(11, if (head.isEmpty) Terminals.named(Items.GRAY_STAINED_GLASS_PANE, "§7Empty Helmet Slot") else head)
            for ((slot, eq) in listOf(20 to EquipmentSlot.CHEST, 29 to EquipmentSlot.LEGS, 38 to EquipmentSlot.FEET)) {
                val s = sp.getItemBySlot(eq).copy()
                if (!s.isEmpty) c.setItem(slot, s)
            }
            if (P3Sim.phoenix) c.setItem(21, SimItems.head(Masks.PHOENIX_TEX, "§7[Lvl 100] §5Phoenix"))
        }

        override fun clicked(slot: Int, button: Int, input: ContainerInput, p: Player) {
            if (slot == 49) { sp.closeContainer(); return }
            // Left-click equips ("Left-click to equip!"); right-click would edit, which the sim doesn't have.
            if (button == 0) defs.firstOrNull { it.slot == slot }?.let { equip(sp, it) }
            draw()
            broadcastFullState()
        }

        override fun quickMoveStack(p: Player, i: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(p: Player) = true
    }
}
