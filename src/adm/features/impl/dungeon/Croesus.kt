package com.adm.features.impl.dungeon

import com.adm.AdmMod
import com.adm.AdmMod.scope
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.ActionSetting
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.clickgui.settings.impl.drawAtHud
import com.adm.events.GuiEvent
import com.adm.events.MessageEvent
import com.adm.events.ScreenCloseEvent
import com.adm.events.SetSlotEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.Module
import com.adm.utils.*
import com.adm.utils.network.WebUtils.fetchJson
import com.adm.utils.render.getStringWidth
import com.adm.utils.render.roundedOutline
import com.adm.utils.render.text
import com.adm.utils.render.textDim
import com.adm.utils.skyblock.dungeon.DungeonUtils
import kotlinx.coroutines.launch
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.IdentityHashMap

object Croesus : Module(
    name = "Croesus",
    description = "Enhances the Croesus chest menu with profit calculations and highlights."
) {
    private val highlightState by BooleanSetting("Highlight State", true, desc = "Highlights chests in the Croesus menu based on their claim status.")
    private val highlightProfitable by BooleanSetting("Highlight Profitable", true, desc = "Highlights the most and 2nd most profitable chests.")
    private val hideClaimed by BooleanSetting("Hide Claimed", true, desc = "Hides chests that have already been claimed.")
    private val includeKey by BooleanSetting("Include Key", desc = "Count Dungeon Chest Key as unclaimed.").withDependency { hideClaimed }
    private val minimized by BooleanSetting("Minimized", false, desc = "Only display profit for each chest instead of all items.")

    private val croesusHud by HUD("Croesus Chest HUD", "Displays all chest contents with prices, sorted by profit.") {
        if (!it) return@HUD 0 to 0
        drawChests(sampleChests)
    }

    private val chestCount by HUD("Chest Count HUD", "Displays the number of chests opened in the current Croesus session.") {
        if (it || DungeonUtils.inDungeons) textDim(countText(), 0, 0) else 0 to 0
    }

    private val chestWarning by NumberSetting("Chest Warning Threshold", 55, 0..60, desc = "Displays a warning in the chest profit HUD if the profit is below this amount.").withDependency { chestCount.enabled }
    private val refresh by ActionSetting("Refresh Prices", desc = "Manually refresh the cached prices used for profit calculations.") {
        scope.launch {
            loadPrices()
            modMessage("§aCroesus prices refreshed.")
        }
    }

    var cachedPrices = emptyMap<String, Double>()

    private const val PRICES_URL = "https://lb.odtheking.com/averages/7day"
    private const val KISMET = "Kismet Feather"
    private const val KEY = "Dungeon Chest Key"
    private const val NO_MORE = "No more chests to open!"
    private const val OPENED = "Opened Chest: "
    private const val UNCLAIMED = " Unclaimed chests: "
    private val EXTRA_STATS = " ".repeat(29) + "> EXTRA STATS <"

    private val rewardChestTitle = Regex("^(Wood|Iron|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?$")
    private val menuTitle = Regex("^(?:\\(\\d+/\\d+\\) )?Croesus$")
    private val runTitle = Regex("^(?:Master )?Catacombs - ([FloorVI\\d ]*)$")
    private val bookLine = Regex("^Enchanted Book \\(?([\\w ]+) (\\w+)\\)$")
    private val bookData = Regex("^\\{([a-zA-Z0-9_]+):(\\d+)}$")
    private val essenceLine = Regex("^(\\w+) Essence x(\\d+)$")
    private val shardLine = Regex("^([A-Za-z ]+) Shard$")
    private val shardStack = Regex("^([A-Za-z ]+) Shard x1$")
    private val coinsLine = Regex("^([\\d,]+) Coins$")

    private val ultimates = setOf("Soul Eater", "Combo", "Legion", "One For All", "Rend", "Bank", "Swarm", "Last Stand", "Wisdom", "No Pain No Gain")

    private class Reward(val name: String, val price: Double)

    private class Chest(val title: Component, val rewards: List<Reward>, val profit: Double, val slot: Int) {
        val profitLine = "§8- §6Profit: ${if (profit >= 0) "§2" else "§c"}${"%,.0f".format(profit)}"
        val rewardLines = Array(rewards.size) { "  §7${rewards[it].name}: §a${"%,.0f".format(rewards[it].price)}" }
    }

    // What a run's head in the Croesus menu says, read once per stack instead of every frame.
    private class RunHead(val claimed: Boolean, val keyUsed: Boolean, val stateColor: Int, val kismetUsed: Boolean)
    private val NOT_A_RUN = RunHead(false, false, 0, false)

    private enum class View { OTHER, MENU, RUN, REWARD_CHEST }

    private var viewScreen: Screen? = null
    private var view = View.OTHER
    private val runHeads = IdentityHashMap<ItemStack, RunHead>()

    private var chests = emptyList<Chest>()
    private var bestSlot = -1
    private var secondSlot = -1
    private val runChests = linkedMapOf<Int, Chest>()
    private val rewardsBySlot = linkedMapOf<Int, Reward>()
    private val costsBySlot = linkedMapOf<Int, Double>()

    @Volatile private var openedChests = 0
    private var countShown = -1
    private var countLine = ""

    private fun countText(): String {
        val n = openedChests
        if (n != countShown) {
            countShown = n
            countLine = "§6Chests: §a$n"
        }
        return countLine
    }

    private suspend fun loadPrices() {
        cachedPrices = fetchJson<Map<String, Double>>(PRICES_URL).getOrElse { AdmMod.logger.error("Failed to fetch lowest bin prices for Croesus module.", it); emptyMap() }
    }

    private fun viewOf(screen: Screen?): View {
        if (screen !== viewScreen) {
            viewScreen = screen
            runHeads.clear()
            val title = screen?.title?.string
            view = when {
                title == null -> View.OTHER
                rewardChestTitle.matches(title) -> View.REWARD_CHEST
                runTitle.matches(title) -> View.RUN
                menuTitle.matches(title) -> View.MENU
                else -> View.OTHER
            }
        }
        return view
    }

    private fun countChest() {
        val n = ++openedChests
        if (n > chestWarning) alert("§cChest limit reached!")
    }

    init {
        scope.launch { loadPrices() }

        on<GuiEvent.DrawTooltip> {
            if (!croesusHud.enabled) return@on
            val v = viewOf(screen)
            if (v == View.REWARD_CHEST || v == View.RUN) guiGraphics.drawAtHud(croesusHud) { drawChests(chests) }
        }

        on<GuiEvent.RenderSlot> {
            when (viewOf(screen)) {
                View.MENU -> {
                    val stack = slot.item
                    val head = runHeads.getOrPut(stack) { readRunHead(stack) }
                    if (head === NOT_A_RUN) return@on
                    if (hideClaimed && head.claimed && (!includeKey || head.keyUsed)) return@on cancel()
                    if (highlightState) guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, head.stateColor)
                    if (head.kismetUsed) guiGraphics.roundedOutline(slot.x, slot.y, slot.x + 16, slot.y + 16, Colors.MINECRAFT_AQUA.rgba, 1f)
                }
                View.RUN -> {
                    if (!highlightProfitable) return@on
                    val color = when (slot.index) {
                        bestSlot -> Colors.MINECRAFT_DARK_GREEN.rgba
                        secondSlot -> Colors.MINECRAFT_YELLOW.rgba
                        else -> return@on
                    }
                    guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color)
                }
                else -> {}
            }
        }

        on<SetSlotEvent> {
            when (viewOf(mc.gui.screen())) {
                View.REWARD_CHEST -> if (slotIndex <= 40) readRewardSlot(slotIndex, itemStack)
                View.RUN -> if (slotIndex <= 16) readRunSlot(slotIndex, itemStack)
                else -> {}
            }
        }

        on<ScreenCloseEvent> {
            bestSlot = -1
            secondSlot = -1
            chests = emptyList()
            runChests.clear()
            rewardsBySlot.clear()
            costsBySlot.clear()
            runHeads.clear()
        }

        onReceive<ClientboundPlayerInfoUpdatePacket> {
            val actions = actions()
            if (!actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME) && !actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) return@onReceive
            for (entry in entries()) {
                val line = entry.displayName?.string ?: continue
                if (!line.startsWith(UNCLAIMED)) continue
                val digits = line.substring(UNCLAIMED.length)
                if (digits.isEmpty() || !digits.all { it in '0'..'9' }) continue
                val n = digits.toIntOrNull() ?: continue
                openedChests = n
                if (n > chestWarning) mc.execute { alert("§cChest limit reached!") }
            }
        }

        on<MessageEvent.Chat> {
            if (message == EXTRA_STATS && DungeonUtils.inBoss) countChest()
        }
    }

    private fun readRunHead(stack: ItemStack): RunHead {
        val name = stack.hoverName.string
        if (name != "The Catacombs" && name != "Master Mode The Catacombs") return NOT_A_RUN
        val lore = stack.lore
        var noMore = false
        var opened = false
        for (line in lore) {
            val s = line.string
            if (s == NO_MORE) noMore = true
            else if (s.length > OPENED.length && s.startsWith(OPENED) && s.indexOf('\n') < 0) opened = true
        }
        val color = if (noMore) Colors.MINECRAFT_RED.rgba else if (opened) Colors.MINECRAFT_GOLD.rgba else Colors.MINECRAFT_GREEN.rgba
        return RunHead(noMore || opened, struckThrough(KEY, lore), color, struckThrough(KISMET, lore))
    }

    private fun struckThrough(name: String, lore: List<Component>): Boolean {
        for (line in lore) for (part in line.siblings) if (part.style.isStrikethrough && part.string == name) return true
        return false
    }

    private fun readRunSlot(slot: Int, stack: ItemStack) {
        val chest = if (stack.isEmpty || stack.item != Items.PLAYER_HEAD) null else readRunChest(slot, stack)
        if (chest == null) runChests.remove(slot) else runChests[slot] = chest

        val sorted = runChests.values.sortedByDescending { it.profit }
        chests = sorted
        bestSlot = sorted.getOrNull(0)?.takeIf { it.profit > 0 }?.slot ?: -1
        secondSlot = sorted.getOrNull(1)?.takeIf { it.profit > 0 }?.slot ?: -1
    }

    private fun readRunChest(slot: Int, stack: ItemStack): Chest? {
        val lore = stack.loreString
        val first = lore.indexOf("Contents") + 1
        if (first == 0) return null
        val end = lore.indexOfFirst { it.isEmpty() }
        if (end < first) return null
        val cost = lore.getOrNull(end + 2)?.let { coinsLine.find(it)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() } ?: 0.0
        var total = 0.0
        val rewards = ArrayList<Reward>(end - first)
        for (i in first until end) {
            val line = lore[i]
            val price = priceOfLine(line) ?: 0.0
            total += price
            rewards += Reward(line, price)
        }
        return Chest(stack.hoverName, rewards, total - cost, slot)
    }

    private fun priceOfLine(line: String): Double? {
        bookLine.find(line)?.let { m ->
            val (name, level) = m.destructured
            val prefix = if (name in ultimates) "ULTIMATE_" else ""
            return cachedPrices["ENCHANTED_BOOK-$prefix${name.uppercase().replace(" ", "_")}-${romanToInt(level)}"]
        }
        essenceLine.find(line)?.let { m ->
            val (name, amount) = m.destructured
            return cachedPrices["ESSENCE_${name.uppercase()}"]?.let { it * amount.toDouble() }
        }
        shardLine.find(line)?.let { m -> return cachedPrices["SHARD_${m.groupValues[1].uppercase().replace(" ", "_")}"] }
        renamedIds[line]?.let { return cachedPrices[it] }
        return cachedPrices[line.uppercase().replace("'", "").replace(" -", "").replace(" ", "_")]
    }

    private fun readRewardSlot(slot: Int, stack: ItemStack) {
        if (stack.isEmpty || stack.item == Items.CHEST) {
            rewardsBySlot.remove(slot)
            costsBySlot.remove(slot)
            if (!stack.isEmpty) for (line in stack.loreString) {
                coinsLine.find(line)?.let { costsBySlot[slot] = it.groupValues[1].replace(",", "").toDouble() }
            }
        } else {
            costsBySlot.remove(slot)
            val reward = if (stack.item == Items.ENCHANTED_BOOK) bookReward(stack) else stackReward(stack)
            if (reward == null) rewardsBySlot.remove(slot) else rewardsBySlot[slot] = reward
        }

        var profit = 0.0
        for (r in rewardsBySlot.values) profit += r.price
        chests = listOf(Chest(Component.literal("§eChest"), rewardsBySlot.values.toList(), profit - costsBySlot.values.sum(), -1))
    }

    private fun bookReward(stack: ItemStack): Reward? {
        val (name, level) = bookData.find(stack.customData.get("enchantments").toString())?.destructured ?: return null
        return cachedPrices["ENCHANTED_BOOK-${name.uppercase()}-$level"]?.let { Reward(stack.hoverName.string, it) }
    }

    private fun stackReward(stack: ItemStack): Reward? {
        val name = stack.hoverName.string
        essenceLine.find(name)?.let { m ->
            val (essence, amount) = m.destructured
            val price = cachedPrices["ESSENCE_${essence.uppercase()}"] ?: return null
            return Reward(name, price * amount.toDouble())
        }
        shardStack.find(name)?.let { m ->
            cachedPrices["SHARD_${m.groupValues[1].uppercase().replace(" ", "_").replace("'s", "")}"]?.let { return Reward(name, it) }
        }
        return cachedPrices[stack.itemId]?.let { Reward(name, it) }
    }

    private fun GuiGraphicsExtractor.drawChests(list: List<Chest>): Pair<Int, Int> {
        val nameWidth = getStringWidth("Diamond ")
        val full = !minimized
        var y = 0
        var width = 0
        for (chest in list) {
            text(chest.title.visualOrderText, 0, y)
            val w = nameWidth + textDim(chest.profitLine, nameWidth, y).first
            if (w > width) width = w
            y += 9
            if (full) for (line in chest.rewardLines) {
                val dim = textDim(line, 0, y)
                if (dim.first > width) width = dim.first
                y += dim.second
            }
            y += 2
        }
        return width to y
    }

    private val renamedIds = mapOf(
        "Shiny Wither Chestplate" to "WITHER_CHESTPLATE",
        "Shiny Wither Leggings" to "WITHER_LEGGINGS",
        "Shiny Necron's Handle" to "NECRON_HANDLE",
        "Necron's Handle" to "NECRON_HANDLE",
        "Shiny Wither Helmet" to "WITHER_HELMET",
        "Shiny Wither Boots" to "WITHER_BOOTS",
        "Wither Shield" to "WITHER_SHIELD_SCROLL",
        "Implosion" to "IMPLOSION_SCROLL",
        "Shadow Warp" to "SHADOW_WARP_SCROLL",
        "Necron Dye" to "DYE_NECRON",
        "Livid Dye" to "DYE_LIVID",
        "Giant's Sword" to "GIANTS_SWORD",
        "NECROMANCERS_BROOCH" to "NECROMANCER_BROOCH",
        "SHADOW_WARP" to "SHADOW_WARP_SCROLL",
        "SPIRIT_STONE" to "SPIRIT_DECOY",
        "WARPED_STONE" to "AOTE_STONE"
    )

    private val sampleChests by lazy {
        listOf(
            Chest(Component.literal("Bedrock"), listOf(Reward("Enchanted Book (One For All V)", 85000000.0), Reward("Wither Catalyst", 12000000.0), Reward("Necron's Handle", 550000000.0)), 632000000.0, 0),
            Chest(Component.literal("Obsidian"), listOf(Reward("Enchanted Book (Legion V)", 45000000.0), Reward("Wither Blood", 8500000.0)), 46500000.0, 1),
            Chest(Component.literal("Diamond"), listOf(Reward("Wither Essence x10", 2000000.0), Reward("Recombobulator 3000", 6500000.0)), 5000000.0, 2)
        )
    }
}
