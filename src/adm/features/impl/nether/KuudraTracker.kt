package com.adm.features.impl.nether

import com.adm.clickgui.settings.impl.*
import com.adm.events.GuiEvent
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.features.ModuleManager
import com.adm.features.impl.dungeon.Croesus
import com.adm.utils.Colors
import com.adm.utils.lore
import com.adm.utils.render.text
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.item.Items

object KuudraTracker : Module(
    name = "Kuudra Tracker",
    description = "Tracks your Kuudra runs."
) {
    private val lineAmount by NumberSetting("Lines", 10, 0..40, 1, "The amount of items to display in the profit tracker.")
    private val reset by ActionSetting("Reset Profit", "Resets the Profit Tracker") {
        totalKeys = mutableListOf(0, 0, 0, 0, 0)
        singleItems.clear()
        multiItems.clear()
        paid = 0
        free = 0
        lastChest = null
        changed()
    }

    val profitHud by HUD("Kuudra Tracker HUD", "Displays the profit of all your Kudura chests.") {
        if (!it) return@HUD 0 to 0
        draw(view(true))
    }

    private var singleItems by MapSetting("Single Items", mutableMapOf<String, Int>())
    private var multiItems by MapSetting("Multi Items", mutableMapOf<String, Int>())

    private var paid by NumberSetting("Paid Chests", 0, 0..Int.MAX_VALUE, 1, "Amount of Paid Chests opened.").hide()
    private var free by NumberSetting("Free Chests", 0, 0..Int.MAX_VALUE, 1, "Amount of Paid Chests opened").hide()

    private var totalKeys by ListSetting("Total Keys", mutableListOf(0, 0, 0, 0, 0))

    private val countedSuffix = Regex("(.+)? x(\\d+)$")
    private val skippedLines = hashSetOf("Contents", "Cost", "Click to open!", "FREE", "Already opened!", "Can't open another chest!", "Paid Chest", "")

    private val whiteNames = listOf("Book")
    private val goldNames = listOf("Boots", "Leggings", "Chestplate", "Helmet", "Cloak", "Aurora Staff", "Hollow Wand", "Heavy Pearl", "Kraken Shard", "Hellwisp Shard", "Ananke Shard", "Moltenfish Shard", "Tormentor")
    private val darkPurpleNames = listOf("Kuudra Teeth", "Fire Eel Shard", "Kuudra Mandible", "Barbarian Duke X Shard", "XYZ Shard", "Hellstorm Wand", "Tentacle Dye")
    private val lightPurpleNames = listOf("Fatal Tempo", "Inferno", "Crimson Essence")
    private val blueNames = listOf("Kada Knight Shard", "Wheel of Fate", "Ananke Feather", "Burning Kuudra Core", "Matcho Shard", "Kuudra Tentacle", "Lava Flame Shard", "Wither Spectre Shard")

    /** What the last opened chest added, so a refused purchase can be taken back. */
    private class Opened(val singles: List<String>, val counted: List<Pair<String, Int>>, val keyTier: Int)

    private var lastChest: Opened? = null

    private class Row(val name: Component, val price: Double) {
        val priceText: String = "%,.0f".format(price)
    }

    private class View(val rows: List<Row>, val keyCost: String, val profit: String, val chests: String, val average: String)

    private var rows: List<Row> = emptyList()
    private var shown: View? = null
    private var sampleShown: View? = null
    private var pricesSeen: Map<String, Double>? = null
    private var stale = true

    private val sampleRows by lazy {
        listOf(
            Row(Component.literal("Fervor Helmet").withStyle(ChatFormatting.GOLD), 748000.0),
            Row(
                Component.literal("Enchanted Book (").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal("Ferocious Mana V").withStyle(ChatFormatting.LIGHT_PURPLE))
                    .append(Component.literal(")").withStyle(ChatFormatting.WHITE)),
                118500000.0
            ),
            Row(Component.literal("Crimson Essence").withStyle(ChatFormatting.LIGHT_PURPLE).append(Component.literal(" x2000").withStyle(ChatFormatting.DARK_GRAY)), 2420000.0),
            Row(Component.literal("Kuudra Teeth").withStyle(ChatFormatting.DARK_PURPLE).append(Component.literal(" x4").withStyle(ChatFormatting.DARK_GRAY)), 35480.0),
            Row(Component.literal("Kraken Shard").withStyle(ChatFormatting.GOLD).append(Component.literal(" x2").withStyle(ChatFormatting.DARK_GRAY)), 821226.0),
        ).sortedByDescending { it.price }
    }

    private var titleSeen: Component? = null
    private var titleKind = 0

    init {
        on<GuiEvent.DrawTooltip> {
            if (enabled && kindOf(screen.title) != 0) guiGraphics.drawAtHud(profitHud) { draw(view(false)) }
        }

        on<MessageEvent.Chat> {
            if (message != "You cannot afford this!" && message != "Whoa! Slow down there!") return@on
            val title = mc.gui.screen()?.title ?: return@on
            if (kindOf(title) != CHEST) return@on
            undoLastChest()
        }

        on<GuiEvent.SlotClick> {
            if (kindOf(screen.title) != CHEST) return@on
            val menu = (screen as? AbstractContainerScreen<*>)?.menu ?: return@on
            if (slotIndex !in 0 until menu.slots.size) return@on
            val clicked = menu.getSlot(slotIndex).item
            if (!clicked.`is`(Items.CHEST)) return@on
            val lore = clicked.lore
            if (lore.any { it.string == "Already opened!" || it.string == "Can't open another chest!" }) return@on
            recordChest(lore.map { it.string }, screen.title.string)
        }
    }

    private const val CHEST = 1
    private const val OTHER_REWARD = 2

    private fun kindOf(title: Component): Int {
        if (title !== titleSeen) {
            titleSeen = title
            val s = title.string
            titleKind = when {
                s == "Free Chest" || s == "Paid Chest" -> CHEST
                s.startsWith("Kuudra - ") && s.length > 9 || s.endsWith("Vesuvius") -> OTHER_REWARD
                else -> 0
            }
        }
        return titleKind
    }

    private fun recordChest(lines: List<String>, title: String) {
        val singles = ArrayList<String>()
        val counted = ArrayList<Pair<String, Int>>()
        var keyTier = 0
        for (line in lines) {
            if (line in skippedLines) continue
            if ("Kuudra Key" in line) {
                val key = Vesuvius.keyNamed(line)
                if (key != null) {
                    totalKeys[key.tier - 1]++
                    keyTier = key.tier
                    continue
                }
            }
            val match = countedSuffix.find(line)
            if (match != null) {
                val name = match.groupValues[1]
                val amount = match.groupValues[2].toIntOrNull() ?: 0
                multiItems[name] = multiItems.getOrDefault(name, 0) + amount
                counted.add(name to amount)
                continue
            }
            singleItems[line] = singleItems.getOrDefault(line, 0) + 1
            singles.add(line)
        }
        if (title == "Paid Chest") paid++
        if (title == "Free Chest") free++
        lastChest = Opened(singles, counted, keyTier)
        changed()
    }

    private fun undoLastChest() {
        val last = lastChest ?: return
        lastChest = null
        for (name in last.singles) singleItems[name] = (singleItems.getOrDefault(name, 0) - 1).coerceAtLeast(0)
        for ((name, amount) in last.counted) multiItems[name] = (multiItems.getOrDefault(name, 0) - amount).coerceAtLeast(0)
        if (last.keyTier > 0) {
            totalKeys[last.keyTier - 1] = (totalKeys[last.keyTier - 1] - 1).coerceAtLeast(0)
            paid = (paid - 1).coerceAtLeast(0)
        } else free = (free - 1).coerceAtLeast(0)
        changed()
    }

    private fun changed() {
        stale = true
        ModuleManager.saveModule(this)
    }

    private fun buildRows(): List<Row> {
        val built = ArrayList<Row>()
        for ((name, amount) in singleItems) {
            if (amount == 0) continue
            val price = Vesuvius.parseItemValue(Component.literal(name))?.times(amount) ?: continue
            built.add(Row(Component.literal(name).withStyle(colorOf(name)).append(counter(amount)), price))
        }
        for ((name, amount) in multiItems) {
            if (amount == 0) continue
            val label = Component.literal(name).append(counter(amount))
            val price = Vesuvius.parseItemValue(label) ?: continue
            built.add(Row(label.withStyle(colorOf(name)), price))
        }
        built.sortByDescending { it.price }
        return built
    }

    private fun counter(amount: Int): MutableComponent = Component.literal(" x$amount").withStyle(ChatFormatting.DARK_GRAY)

    private fun keyCosts(): Double {
        var total = 0.0
        for (key in Vesuvius.kuudraKeys) total += Vesuvius.getPriceOfKey(key.name) * totalKeys[key.tier - 1]
        return total
    }

    private fun view(sample: Boolean): View {
        if (stale || Croesus.cachedPrices !== pricesSeen) {
            pricesSeen = Croesus.cachedPrices
            stale = false
            rows = buildRows()
            shown = null
            sampleShown = null
        }
        return if (sample) sampleShown ?: makeView(sampleRows, true).also { sampleShown = it }
        else shown ?: makeView(rows, false).also { shown = it }
    }

    private fun makeView(rows: List<Row>, sample: Boolean): View {
        var profit = 0.0
        for (row in rows) profit += row.price
        val keys = keyCosts()
        val freeChests = if (sample) 0 else free
        val paidChests = if (sample) 1 else paid
        val opened = freeChests + paidChests
        return View(
            rows,
            if (sample) Vesuvius.getPriceOfKey("Infernal Kuudra Key").toString() else "%,.0f".format(keys),
            "%,.0f".format(profit - keys),
            "Total Chests: Free [$freeChests], Paid [$paidChests]",
            if (opened == 0) "Avg [0]" else "Avg [${"%,.0f".format((profit - keys) / opened)}]"
        )
    }

    private fun GuiGraphicsExtractor.draw(view: View): Pair<Int, Int> {
        val right = 300
        val font = mc.font
        var y = 0
        val limit = minOf(lineAmount, view.rows.size)
        for (i in 0 until limit) {
            val row = view.rows[i]
            text(font, row.name, 0, y, -1)
            text(font, row.priceText, right - font.width(row.priceText), y, Colors.MINECRAFT_DARK_GRAY.rgba)
            y += 9
        }
        y += 6
        text(font, "§cTotal Key Costs:", 0, y, -1)
        text(font, view.keyCost, right - font.width(view.keyCost), y, Colors.MINECRAFT_RED.rgba)
        y += 12
        text("§aProfit:", 0, y)
        text(view.profit, right - font.width(view.profit), y, Colors.MINECRAFT_GREEN)
        y += 15
        text(font, view.chests, 0, y, Colors.MINECRAFT_YELLOW.rgba)
        text(font, view.average, right - font.width(view.average), y, Colors.MINECRAFT_GOLD.rgba)
        y += 9
        return right to y
    }

    private fun colorOf(name: String): ChatFormatting = when {
        whiteNames.any { it in name } -> ChatFormatting.WHITE
        goldNames.any { it in name } -> ChatFormatting.GOLD
        darkPurpleNames.any { it in name } -> ChatFormatting.DARK_PURPLE
        lightPurpleNames.any { it in name } -> ChatFormatting.LIGHT_PURPLE
        blueNames.any { it in name } -> ChatFormatting.BLUE
        else -> ChatFormatting.WHITE
    }
}
