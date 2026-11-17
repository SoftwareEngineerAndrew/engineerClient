package com.adm.features.impl.nether

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.clickgui.settings.impl.drawAtHud
import com.adm.events.GuiEvent
import com.adm.events.ScreenCloseEvent
import com.adm.events.SetSlotEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.features.impl.dungeon.Croesus.cachedPrices
import com.adm.utils.Colors
import com.adm.utils.lore
import com.adm.utils.render.text
import com.adm.utils.romanToInt
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Optional

object Vesuvius : Module(
    name = "Vesuvius",
    description = "Enhances the Vesuvius chest menu with profit calculations and highlights."
) {
    private val hideClaimed by BooleanSetting("Hide Claimed", true, desc = "Hides chests that have already been claimed.")
    private val useSalvagePrices by BooleanSetting("Use Salvaged", false, desc = "Uses the essence you would get by salvaging the piece instead.")

    private val kuudraPetBonus by NumberSetting("Kuudra Pet Bonus", 0.0, 0.0..20.0, 0.05, desc = "The essence bonus from Kuudra pet.", unit = "%")
    private val lavaLeechBonus by NumberSetting("Lava Leech Bonus", 0.0, 0.0..13.0, 0.05, desc = "The essence bonus from the Lava Leech Shard.", unit = "%")

    private val vesuviusHud by HUD("Croesus Chest HUD", "Displays all chest contents with prices, sorted by profit.") {
        if (!it) return@HUD 0 to 0
        drawChest(sample)
    }

    internal class KuudraKey(val name: String, val coins: Int, val material: Int, val tier: Int)

    internal val kuudraKeys = arrayOf(
        KuudraKey("Kuudra Key", 155200, 2, 1),
        KuudraKey("Hot Kuudra Key", 310400, 4, 2),
        KuudraKey("Burning Kuudra Key", 582000, 16, 3),
        KuudraKey("Fiery Kuudra Key", 1164000, 40, 4),
        KuudraKey("Infernal Kuudra Key", 2328000, 80, 5),
    )

    internal fun keyNamed(name: String): KuudraKey? = kuudraKeys.firstOrNull { it.name == name }

    private val bookLine = Regex("^Enchanted Book \\(?([\\w ]+) (\\w+)\\)$")
    private val essenceLine = Regex("^(\\w+) Essence x(\\d+)$")
    private val shardLine = Regex("^([A-Za-z' ]+) Shard(?: x(\\d+))?$")
    private val teethLine = Regex("^Kuudra Teeth x(\\d+)$")
    private val pearlLine = Regex("^Heavy Pearl x(\\d+)$")
    private val salvageable = Regex("Boots|Leggings|Chestplate|Helmet|Cloak|Aurora Staff|Hollow Wand")

    private val skippedLines = hashSetOf("Contents", "Cost", "Click to open!", "FREE", "Already opened!", "Can't open another chest!", "Paid Chest", "")
    private val ultimates = hashSetOf("Fatal Tempo", "Inferno")
    private val renamedItems = mapOf("Hellstorm Wand" to "HELLSTORM_STAFF", "Aurora Staff" to "RUNIC_STAFF")
    private val renamedBooks = mapOf(
        "Vivacious Vitality" to "ENCHANTED_BOOK-FEROCIOUS_MANA",
        "Hardened Vitality" to "ENCHANTED_BOOK-HARDENED_MANA",
        "Strong Vitality" to "ENCHANTED_BOOK-STRONG_MANA",
        "Vampiric Vitality" to "ENCHANTED_BOOK-MANA_VAMPIRE",
    )
    private val salvageEssence = doubleArrayOf(120.0, 138.0, 158.0, 182.0, 210.0, 240.0, 272.0, 308.0)
    private val gold = TextColor.fromLegacyFormat(ChatFormatting.GOLD)
    private val lightPurple = TextColor.fromLegacyFormat(ChatFormatting.LIGHT_PURPLE)

    private class Chest(val names: List<Component>, prices: DoubleArray, cost: Double, profit: Double) {
        val prices = Array(prices.size) { "%,.0f".format(prices[it]) }
        val cost = "%,.0f".format(cost)
        val profit = "%,.0f".format(profit)
    }

    private var openChest: Chest? = null

    private val sample by lazy {
        Chest(
            listOf(
                Component.literal("Fervor Helmet").withStyle(ChatFormatting.GOLD),
                Component.literal("Enchanted Book (").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal("Ferocious Mana V").withStyle(ChatFormatting.LIGHT_PURPLE))
                    .append(Component.literal(")").withStyle(ChatFormatting.WHITE)),
                Component.literal("Crimson Essence").withStyle(ChatFormatting.LIGHT_PURPLE).append(Component.literal(" x2000").withStyle(ChatFormatting.DARK_GRAY)),
                Component.literal("Kuudra Teeth").withStyle(ChatFormatting.DARK_PURPLE).append(Component.literal(" x4").withStyle(ChatFormatting.DARK_GRAY)),
                Component.literal("Kraken Shard").withStyle(ChatFormatting.GOLD).append(Component.literal(" x2").withStyle(ChatFormatting.DARK_GRAY)),
            ),
            doubleArrayOf(748000.0, 118500000.0, 2420000.0, 35480.0, 821226.0),
            3247000.0, 118542706.0
        )
    }

    private var rewardTitle: Component? = null
    private var isRewardChest = false
    private var listTitle: Component? = null
    private var isChestList = false

    private const val SLOTS = 54
    private val slotStacks = arrayOfNulls<ItemStack>(SLOTS)
    private val slotClaimed = BooleanArray(SLOTS)

    init {
        on<GuiEvent.DrawTooltip> {
            if (openChest != null && vesuviusHud.enabled && rewardChest(screen.title))
                guiGraphics.drawAtHud(vesuviusHud) { drawChest(openChest ?: return@drawAtHud) }
        }

        on<GuiEvent.RenderSlot> {
            if (!hideClaimed || !chestList(screen.title)) return@on
            if (claimed(slot.index, slot.item)) cancel()
        }

        on<SetSlotEvent> {
            val wanted = when (slotIndex) {
                31 -> itemStack.item == Items.CHEST
                13, 14 -> itemStack.item == Items.PLAYER_HEAD
                else -> false
            }
            if (!wanted) return@on
            val title = mc.gui.screen()?.title ?: return@on
            if (rewardChest(title)) openChest = readChest(itemStack)
        }

        on<ScreenCloseEvent> {
            openChest = null
        }
    }

    private fun rewardChest(title: Component): Boolean {
        if (title !== rewardTitle) {
            rewardTitle = title
            val s = title.string
            isRewardChest = s == "Free Chest" || s == "Paid Chest" || s.startsWith("Kuudra - ") && s.length > 9
        }
        return isRewardChest
    }

    private fun chestList(title: Component): Boolean {
        if (title !== listTitle) {
            listTitle = title
            val s = title.string
            isChestList = "Vesuvius" in s || "Croesus" in s
        }
        return isChestList
    }

    private fun claimed(index: Int, stack: ItemStack): Boolean {
        if (index !in 0 until SLOTS) return isClaimed(stack)
        if (slotStacks[index] !== stack) {
            slotStacks[index] = stack
            slotClaimed[index] = isClaimed(stack)
        }
        return slotClaimed[index]
    }

    private fun isClaimed(stack: ItemStack): Boolean =
        stack.hoverName.string == "Kuudra's Hollow" && stack.lore.any { it.string == "No more chests to open!" }

    private fun readChest(stack: ItemStack): Chest {
        val names = ArrayList<Component>()
        val prices = ArrayList<Double>()
        var cost = 0.0
        for (line in stack.lore) {
            val s = line.string
            if ("Kuudra Key" in s) {
                cost = getPriceOfKey(s)
                continue
            }
            if (s in skippedLines) continue
            names.add(line)
            prices.add(parseItemValue(line) ?: 0.0)
        }
        return Chest(names, prices.toDoubleArray(), cost, prices.sum() - cost)
    }

    private fun GuiGraphicsExtractor.drawChest(chest: Chest): Pair<Int, Int> {
        val right = 251
        var y = 0
        for (i in chest.names.indices) {
            val price = chest.prices[i]
            text(mc.font, chest.names[i], 0, y, -1)
            text(price, right - mc.font.width(price), y, Colors.MINECRAFT_GRAY)
            y += 9
        }
        y += 6
        text("§cKey Cost:", 0, y)
        text(chest.cost, right - mc.font.width(chest.cost), y, Colors.MINECRAFT_RED)
        y += 12
        text("§aProfit:", 0, y)
        text(chest.profit, right - mc.font.width(chest.profit), y, Colors.MINECRAFT_GREEN)
        y += 9
        return right to y
    }

    fun parseItemValue(component: Component): Double? {
        val full = component.string
        val item = full.replace("✪", "").trim()
        val bonus = (1 + kuudraPetBonus / 100.0) * (1 + lavaLeechBonus / 100.0)

        if (useSalvagePrices && "Molten" in item) return price("ESSENCE_CRIMSON") * 600.0 * bonus

        bookLine.find(item)?.let {
            val (name, level) = it.destructured
            val tier = romanToInt(level)
            renamedBooks[name]?.let { id -> return cachedPrices["$id-$tier"] }
            val ult = if (name in ultimates) "ULTIMATE_" else ""
            return cachedPrices["ENCHANTED_BOOK-$ult${idOf(name)}-$tier"]
        }
        essenceLine.find(item)?.let {
            val (name, amount) = it.destructured
            return (cachedPrices["ESSENCE_${name.uppercase()}"] ?: return null) * amount.toDouble() * bonus
        }
        shardLine.find(item)?.let {
            return price("SHARD_${idOf(it.groupValues[1])}") * (it.groupValues[2].toDoubleOrNull() ?: 1.0)
        }
        teethLine.find(item)?.let { return price("KUUDRA_TEETH") * it.groupValues[1].toDouble() }
        pearlLine.find(item)?.let { return price("HEAVY_PEARL") * it.groupValues[1].toDouble() }

        if (useSalvagePrices && salvageable.containsMatchIn(full))
            return price("ESSENCE_CRIMSON") * (salvageEssence.getOrNull(stars(component)) ?: 0.0)

        renamedItems[item]?.let { return cachedPrices[it] }
        return cachedPrices[idOf(item)]
    }

    fun getPriceOfKey(key: String): Double {
        val found = keyNamed(key) ?: return 0.0
        val material = minOf(price("ENCHANTED_RED_SAND"), price("ENCHANTED_MYCELIUM"))
        return found.coins + material * found.material + price("CORRUPTED_NETHER_STAR") * 2
    }

    private fun price(id: String): Double = cachedPrices[id] ?: 0.0

    private fun idOf(name: String) = name.uppercase().replace(" ", "_")

    // Gold stars count once, purple (master) stars twice.
    private fun stars(component: Component): Int {
        var total = 0
        component.visit({ style, text ->
            val n = text.count { it == '✪' }
            if (n > 0) when (style.color) {
                gold -> total += n
                lightPurple -> total += n * 2
            }
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return total
    }
}
