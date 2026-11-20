package com.engineerclient.skyblock

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.adm.AdmMod
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.features.impl.dungeon.Croesus
import com.adm.utils.customData
import com.adm.utils.itemId
import com.adm.utils.network.WebUtils.fetchJson
import com.adm.utils.network.WebUtils.fetchString
import com.adm.utils.skyblock.LocationUtils
import com.adm.utils.noControlCodes
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.ItemStack
import java.io.StringReader
import java.util.Locale

/** Croesus' price section: lowest BIN, bazaar prices and an estimated worth for any item, in its tooltip. */
object ItemWorth {
    private val dropdown = DropdownSetting("Prices", desc = "Shows settings for item price tooltips.")
    private val showLbin = BooleanSetting("Show Lowest BIN", true, desc = "The item's lowest BIN in its tooltip.").withDependency { dropdown.value }
    private val showBazaar = BooleanSetting("Show Bazaar", true, desc = "Bazaar buy and sell prices in the tooltip.").withDependency { dropdown.value }
    private val showWorth = BooleanSetting("Show Item Worth", true, desc = "Estimated worth: the base item plus what's applied to it.").withDependency { dropdown.value }
    private val showNpc = BooleanSetting("Show NPC Sell", true, desc = "What an NPC pays for the item.").withDependency { dropdown.value }
    private val onlyShift = BooleanSetting("Only While Shift", false, desc = "Price lines only while you hold shift.").withDependency { dropdown.value }

    @Volatile private var lbin: Map<String, Double> = emptyMap()
    @Volatile private var bazaarBuy: Map<String, Double> = emptyMap()
    @Volatile private var bazaarSell: Map<String, Double> = emptyMap()
    @Volatile private var npcSell: Map<String, Double> = emptyMap()
    @Volatile private var idsByName: Map<String, String> = emptyMap()

    @Volatile private var fetching = false
    @Volatile private var lastFetch = 0L

    fun install() {
        for (s in listOf(dropdown, showLbin, showBazaar, showWorth, showNpc, onlyShift)) Croesus.registerSetting(s)
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!Croesus.enabled || !LocationUtils.isInSkyblock) return@register
            if (onlyShift.value && !Minecraft.getInstance().hasShiftDown()) return@register
            ensureFresh()
            for (text in tooltipText(stack, lines)) lines.add(Component.literal(text))
        }
    }

    // Prices are fetched only while something asks for them, at most every few minutes.
    private fun ensureFresh() {
        if (fetching || System.currentTimeMillis() - lastFetch < REFRESH_MS) return
        if (!Croesus.enabled || !LocationUtils.isInSkyblock) return
        fetching = true
        AdmMod.scope.launch {
            try {
                refresh()
            } finally {
                lastFetch = System.currentTimeMillis()
                fetching = false
            }
        }
    }

    private suspend fun refresh() {
        if (idsByName.isEmpty()) loadItems()
        fetchJson<Map<String, Double>>(LBIN_URL).getOrNull()?.let { lbin = it }
        val body = fetchString(BAZAAR_URL).getOrNull() ?: return
        val buy = HashMap<String, Double>(2048)
        val sell = HashMap<String, Double>(2048)
        // Streamed: the order books in each product are skipped, never built.
        runCatching {
            JsonReader(StringReader(body)).obj { key ->
                if (key != "products") return@obj skipValue()
                obj { id ->
                    obj { field ->
                        if (field != "quick_status") return@obj skipValue()
                        obj { price ->
                            when (price) {
                                "buyPrice" -> buy[id] = nextDouble()
                                "sellPrice" -> sell[id] = nextDouble()
                                else -> skipValue()
                            }
                        }
                    }
                }
            }
        }.onFailure { return }
        bazaarBuy = buy
        bazaarSell = sell
    }

    private suspend fun loadItems() {
        val body = fetchString(ITEMS_URL).getOrNull() ?: return
        val npc = HashMap<String, Double>(8192)
        val names = HashMap<String, String>(8192)
        runCatching {
            JsonReader(StringReader(body)).obj { key ->
                if (key != "items") return@obj skipValue()
                beginArray()
                while (hasNext()) {
                    var id: String? = null
                    var sellPrice: Double? = null
                    var name: String? = null
                    obj { field ->
                        when (field) {
                            "id" -> id = nextString()
                            "npc_sell_price" -> sellPrice = nextDouble()
                            "name" -> name = nextString()
                            else -> skipValue()
                        }
                    }
                    val itemId = id ?: continue
                    sellPrice?.let { npc[itemId] = it }
                    name?.noControlCodes?.let { names.putIfAbsent(it, itemId) }
                }
                endArray()
            }
        }.onFailure { return }
        npcSell = npc
        idsByName = names
    }

    private inline fun JsonReader.obj(field: JsonReader.(String) -> Unit) {
        if (peek() == JsonToken.NULL) return nextNull()
        beginObject()
        while (hasNext()) field(nextName())
        endObject()
    }

    /** What one of [id] is worth: its bazaar instant-sell price, else its lowest BIN. */
    fun price(id: String): Double? {
        ensureFresh()
        return bazaarSell[id]?.takeIf { it > 0 } ?: lbin[id]
    }

    /** The item id Hypixel's item list gives for a display name without colour codes. */
    fun idForName(name: String): String? {
        ensureFresh()
        return idsByName[name]
    }

    /** A pet's lowest BIN by its type and tier, e.g. SPIRIT and LEGENDARY. */
    fun petPrice(type: String, tier: String): Double? {
        ensureFresh()
        return lbin["PET-$type-$tier"]
    }

    fun petPrice(stack: ItemStack): Double? {
        val info = stack.customData.getString("petInfo").orElse(null) ?: return null
        val json = runCatching { JsonParser.parseString(info).asJsonObject }.getOrNull() ?: return null
        val type = json.get("type")?.asString ?: return null
        val tier = json.get("tier")?.asString ?: return null
        return petPrice(type, tier)
    }

    private class TooltipKey(val data: CustomData, val count: Int, val lbin: Map<String, Double>, val buy: Map<String, Double>, val sell: Map<String, Double>, val npc: Map<String, Double>, val flags: Int) {
        fun same(o: TooltipKey) = data === o.data && count == o.count && lbin === o.lbin && buy === o.buy && sell === o.sell && npc === o.npc && flags == o.flags
    }

    // The hovered item's tooltip is rebuilt every frame; its price lines only change with the item or the prices.
    @Volatile private var lastTooltip: Pair<TooltipKey, List<String>>? = null

    private fun tooltipText(stack: ItemStack, lines: List<Component>): List<String> {
        val flags = (if (showLbin.value) 1 else 0) or (if (showBazaar.value) 2 else 0) or (if (showWorth.value) 4 else 0) or (if (showNpc.value) 8 else 0)
        val count = ownedShards(stack, lines) ?: stack.count
        val key = TooltipKey(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY), count, lbin, bazaarBuy, bazaarSell, npcSell, flags)
        lastTooltip?.let { (k, text) -> if (k.same(key)) return text }
        val text = tooltip(stack, count)
        lastTooltip = key to text
        return text
    }

    // A shard's tooltip in the Hunting Box says how many are owned; its price is for all of them.
    private fun ownedShards(stack: ItemStack, lines: List<Component>): Int? {
        if (!stack.itemId.startsWith("SHARD_")) return null
        for (line in lines) {
            val s = line.string
            if (!s.startsWith("Owned: ") || !(s.endsWith(" Shards") || s.endsWith(" Shard"))) continue
            return s.substring(7).substringBefore(' ').replace(",", "").toIntOrNull()
        }
        return null
    }

    private fun tooltip(stack: ItemStack, count: Int): List<String> {
        val id = stack.itemId.ifEmpty { return emptyList() }
        val out = ArrayList<String>()
        if (showLbin.value) lbin[id]?.let { out += line("Lowest BIN", it, count) }
        if (showBazaar.value) {
            bazaarBuy[id]?.let { out += line("Bazaar Buy", it, count) }
            bazaarSell[id]?.let { out += line("Bazaar Sell", it, count) }
        }
        if (showNpc.value) npcSell[id]?.takeIf { it > 0 }?.let { out += line("NPC Sell", it, count) }
        if (showWorth.value) worth(stack)?.takeIf { it.second.isNotEmpty() }?.let { (total, _) -> out += line("Item Worth", total, 1) }
        return out
    }

    private fun line(label: String, each: Double, count: Int): String {
        val text = if (count > 1) "§6${coins(each * count)} §7(${coins(each)} each)" else "§6${coins(each)}"
        return "§7$label: $text"
    }

    /** The estimated worth and what it's made of (name to coins); null when the base item has no price. */
    fun worth(stack: ItemStack): Pair<Double, List<Pair<String, Double>>>? {
        val id = stack.itemId.ifEmpty { return null }
        val data = stack.customData
        val base = price(id) ?: return null
        val parts = ArrayList<Pair<String, Double>>()
        parts += "Base" to base * stack.count
        fun add(name: String, item: String, n: Int = 1) { if (n > 0) price(item)?.let { parts += name to it * n } }

        if (data.getInt("rarity_upgrades").orElse(0) > 0) add("Recombobulator", "RECOMBOBULATOR_3000")
        val hpb = data.getInt("hot_potato_count").orElse(0)
        add("Hot Potato Books", "HOT_POTATO_BOOK", minOf(hpb, 10))
        add("Fuming Potato Books", "FUMING_POTATO_BOOK", maxOf(0, hpb - 10))
        add("Art of War", "THE_ART_OF_WAR", data.getInt("art_of_war_count").orElse(0))
        add("Farming for Dummies", "FARMING_FOR_DUMMIES", data.getInt("farming_for_dummies_count").orElse(0))
        add("Jalapeno Book", "JALAPENO_BOOK", data.getInt("jalapeno_count").orElse(0))
        add("Wood Singularity", "WOOD_SINGULARITY", data.getInt("wood_singularity_count").orElse(0))
        add("Mana Disintegrator", "MANA_DISINTEGRATOR", data.getInt("mana_disintegrator_count").orElse(0))
        add("Transmission Tuners", "TRANSMISSION_TUNER", data.getInt("tuned_transmission").orElse(0))
        add("Polarvoid Books", "POLARVOID_BOOK", data.getInt("polarvoid").orElse(0))
        add("Bookworm Books", "BOOKWORM_BOOK", data.getInt("bookworm_books").orElse(0))
        if (data.getInt("ethermerge").orElse(0) > 0) add("Etherwarp", "ETHERWARP_CONDUIT")
        data.getString("power_ability_scroll").orElse(null)?.let { add("Power Scroll", it) }
        data.getString("dye_item").orElse(null)?.let { add("Dye", it) }
        data.getString("talisman_enrichment").orElse(null)?.let { add("Enrichment", "TALISMAN_ENRICHMENT_" + it.uppercase(Locale.ROOT)) }
        data.getString("skin").orElse(null)?.let { add("Skin", it) }
        for (scroll in data.getListOrEmpty("ability_scroll")) scroll.asString().orElse(null)?.let { add("Scroll", it) }

        val stars = data.getInt("upgrade_level").orElse(0)
        for (i in 6..minOf(stars, 10)) add("Master Star", MASTER_STARS[i - 6])

        val enchants = data.getCompoundOrEmpty("enchantments")
        for (name in enchants.keySet()) {
            val level = enchants.getInt(name).orElse(0)
            add("Enchants", "ENCHANTMENT_${name.uppercase(Locale.ROOT)}_$level")
        }

        val gems = data.getCompoundOrEmpty("gems")
        for (slot in gems.keySet()) {
            if (slot.endsWith("_gem") || slot == "unlocked_slots") continue
            val quality = gems.getString(slot).orElse(null) ?: gems.getCompoundOrEmpty(slot).getString("quality").orElse(null) ?: continue
            val type = gems.getString("${slot}_gem").orElse(null) ?: slot.substringBeforeLast('_')
            add("Gemstones", "${quality.uppercase(Locale.ROOT)}_${type.uppercase(Locale.ROOT)}_GEM")
        }

        val merged = parts.groupBy({ it.first }, { it.second }).map { (k, v) -> k to v.sum() }
        return merged.sumOf { it.second } to merged
    }

    fun coins(v: Double): String = when {
        v >= 1e9 -> String.format(Locale.ROOT, "%.2fB", v / 1e9)
        v >= 1e6 -> String.format(Locale.ROOT, "%.2fM", v / 1e6)
        v >= 1e3 -> String.format(Locale.ROOT, "%.1fk", v / 1e3)
        else -> String.format(Locale.ROOT, "%.0f", v)
    }

    private val MASTER_STARS = listOf("FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR")
    private const val LBIN_URL = "https://lb.odtheking.com/lowestbins.json"
    private const val BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar"
    private const val ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items"
    private const val REFRESH_MS = 5 * 60_000L
}
