package com.adm.features.impl.dungeon

import com.engineerclient.ClassDetect
import com.adm.AdmMod.scope
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.ActionSetting
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.commands.fetchAndDisplayCataStats
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.formatNumber
import com.adm.utils.formatTime
import com.adm.utils.handlers.schedule
import com.adm.utils.lore
import com.adm.utils.modMessage
import com.adm.utils.network.hypixelapi.HypixelData
import com.adm.utils.network.hypixelapi.RequestUtils
import com.adm.utils.noControlCodes
import com.adm.utils.romanToInt
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.PartyUtils
import com.adm.utils.skyblock.dungeon.DungeonClass
import com.adm.utils.skyblock.dungeon.Floor
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.concurrent.ConcurrentHashMap

object BetterPartyFinder : Module(
    name = "Better Party Finder",
    description = "Party Finder overlay, stats of players who join your party and autokick.",
) {
    private val showLevelReq by BooleanSetting("Show Level Req", true, desc = "Red level requirement on each party.")
    private val showMissingOverlay by BooleanSetting("Show Missing Classes", true, desc = "Missing classes on each party's head.")
    private val showMissingTooltip by BooleanSetting("Show Missing List", true, desc = "Missing classes at the bottom of the tooltip, yours in colour.")
    private val tooltipCata by BooleanSetting("Tooltip Cata Level", false, desc = "Each member's Catacombs level in the tooltip.")
    private val tooltipSecrets by BooleanSetting("Tooltip Secrets", false, desc = "Each member's total and average secrets in the tooltip.")
    private val tooltipMp by BooleanSetting("Tooltip Magical Power", false, desc = "Each member's magical power in the tooltip.")
    private val tooltipPb by BooleanSetting("Tooltip PB", false, desc = "Each member's S+ PB on the party's floor in the tooltip.")

    private val statsDisplay by BooleanSetting("Stats display", true, desc = "Displays stats of players who join your party")
    private val sendKickLine by BooleanSetting("Send Kick Line", true, desc = "Sends a line in party chat to kick a player.").withDependency { statsDisplay }

    private val autoKickToggle by BooleanSetting("Auto Kick", desc = "Automatically kicks players who don't meet requirements.")
    private val floor by SelectorSetting("Floor", Floor.F7, desc = "Determines which floor to check pb.", options = Floor.entries.filter { !it.isMM }).withDependency { autoKickToggle }
    private val mmToggle by BooleanSetting("Master Mode", true, desc = "Use master mode times").withDependency { autoKickToggle }
    private val informKicked by BooleanSetting("Inform Kicked", desc = "Informs the player why they were kicked.").withDependency { autoKickToggle }
    private val maximumSeconds by NumberSetting("Minimum PB", 400, 60..480, 5, desc = "Minimum amount of seconds before kicking.", unit = "s").withDependency { autoKickToggle }
    private val secretsMin by NumberSetting("Minimum Secrets", 0, 0..200, desc = "Secret minimum in thousands for kicking.", unit = "k").withDependency { autoKickToggle }
    private val magicalPowerReq by NumberSetting("Magical Power", 1300, 0..2000, 20, desc = "Magical power minimum for kicking.").withDependency { autoKickToggle  }
    private val apiOffKick by BooleanSetting("Api Off Kick", false, desc = "Kicks if the player's api is off. If this setting is disabled, it will ignore the item check when players have api disabled.").withDependency { autoKickToggle }

    private val kickCache by BooleanSetting("Kick Cache", true, desc = "Caches kicked players to automatically kick when they attempt to rejoin.").withDependency { autoKickToggle }
    private val action by ActionSetting("Clear Cache", desc = "Clears the kick list cache.") { kickedList.clear() }.withDependency { autoKickToggle && kickCache }

    //https://regex101.com/r/XYnAVm/2
    private val pfRegex = Regex("^Party Finder > (?:\\[.{1,7}])? ?(.{1,16}) joined the dungeon group! \\(.*\\)$")

    private val kickedList = mutableSetOf<String>()

    private val partyMemberRegex = Regex("^\\s*(\\w{1,16}):?\\s+(Archer|Tank|Berserk|Healer|Mage)\\s*\\((\\d+)\\)\\s*$")
    private val levelRequiredRegex = Regex("Dungeon Level Required:\\s*(\\d+)")
    private val floorRegex = Regex("Floor:\\s*Floor\\s+(\\w+)")
    private val classes = DungeonClass.entries.filter { it != DungeonClass.EMPTY }
    private val listingSlots = setOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34)

    private class Fetched(val member: HypixelData.MemberData?, val time: Long = System.currentTimeMillis())
    private val profiles = ConcurrentHashMap<String, Fetched>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val fetchPermits = Semaphore(5)

    init {
        on<MessageEvent.Chat> {
            if (!statsDisplay && !autoKickToggle) return@on
            val (name) = pfRegex.find(message)?.destructured ?: return@on
            if (name == mc.player?.name?.string) return@on

            scope.launch {
                val profile = RequestUtils.getProfile(name)
                if (statsDisplay) fetchAndDisplayCataStats(profile)
                if (sendKickLine) modMessage(Component.literal("§aPress to kick $name").withStyle {
                    it.withClickEvent(ClickEvent.RunCommand("/party kick $name"))
                })

                if (autoKickToggle && PartyUtils.isLeader()) {
                    if (kickCache && name in kickedList ) {
                        sendCommand("party kick $name")
                        modMessage("Kicked $name since they have been kicked previously.")
                        return@launch
                    }

                    val kickedReasons = mutableListOf<String>()

                    val member = profile.getOrNull()?.memberData ?: return@launch modMessage("Could not find member data for $name")

                    val dungeons = member.dungeons
                    val floorName = "${if (mmToggle) "m" else "f"}${floor.floorNumber}"
                    val dungeon = if (mmToggle) dungeons.dungeonTypes.mastermode else dungeons.dungeonTypes.catacombs
                    dungeon.fastestTimeSPlus["${floor.floorNumber}"]?.let {
                        if (maximumSeconds < it / 1000)
                            kickedReasons.add(
                                "Did not meet time req for $floorName: ${formatTime(it.toLong())}/${formatTime(maximumSeconds * 1000L, 0)}"
                            )
                    } ?: kickedReasons.add("Couldn't confirm completion status for $floorName")

                    (dungeons.secrets + member.playerStats.bloodMobKills / 4).let {
                        if (it < (secretsMin * 1000)) kickedReasons.add("Did not meet secret req: ${formatNumber(it.toString())}/${secretsMin}k")
                    }

                    if (member.inventoryApi) {
                        val mp = member.magicalPower
                        if (mp < magicalPowerReq) kickedReasons.add("Did not meet mp req: ${mp}/$magicalPowerReq")
                    } else if (apiOffKick) kickedReasons.add("Inventory API is off")

                    if (kickedReasons.isNotEmpty()) {
                        if (informKicked) {
                            schedule(6) { sendCommand("party kick $name") }
                            sendCommand("pc Kicked $name for: ${kickedReasons.joinToString(", ")}")
                        } else sendCommand("party kick $name")

                        kickedList.add(name)
                        return@launch modMessage("Kicking $name for: \n${kickedReasons.joinToString(" \n")}")
                    }
                }
            }
        }
    }

    private var finderScreen: AbstractContainerScreen<*>? = null
    private var finderStar: ItemStack? = null
    private var finderOpen = false

    private fun inDungeonFinder(screen: AbstractContainerScreen<*>): Boolean {
        val star = screen.menu.slots.getOrNull(50)?.item
        if (screen !== finderScreen || star !== finderStar) {
            finderScreen = screen
            finderStar = star
            finderOpen = isDungeonFinder(screen, star)
        }
        return finderOpen
    }

    // The Party Finder of other activities shares the title; only the dungeon one shows Combat Level nowhere on its nether star.
    private fun isDungeonFinder(screen: AbstractContainerScreen<*>, star: ItemStack?): Boolean {
        if (star == null || !star.`is`(Items.NETHER_STAR) || screen.title.string != "Party Finder") return false
        val line = star.lore.getOrNull(5)?.string ?: return false
        return !line.contains("Combat Level: ")
    }

    private fun listing(screen: AbstractContainerScreen<*>, stack: ItemStack): Boolean =
        enabled && stack.`is`(Items.PLAYER_HEAD) && inDungeonFinder(screen) &&
            screen.menu.slots.any { it.index in listingSlots && it.item === stack }

    private fun classNamed(name: String) = classes.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private class Marks(val stack: ItemStack, val levelReq: String?, val missingTop: String, val missingBottom: String)
    private val marks = arrayOfNulls<Marks>(35)

    private fun marksOf(index: Int, stack: ItemStack): Marks {
        marks[index]?.takeIf { it.stack === stack }?.let { return it }
        var levelRequired = 0
        val taken = HashSet<DungeonClass>()
        for (line in stack.lore) {
            val clean = line.string.noControlCodes
            levelRequiredRegex.find(clean)?.let { levelRequired = it.groupValues[1].toInt() }
            partyMemberRegex.matchEntire(clean)?.let { m -> classNamed(m.groupValues[2])?.let(taken::add) }
        }
        val missing = classes.filter { it !in taken }.map { "§${it.colorCode}§l${it.name[0]}" }
        return Marks(
            stack,
            if (levelRequired != 0) "§c$levelRequired" else null,
            missing.take(2).joinToString(""),
            missing.drop(2).take(2).joinToString(""),
        ).also { marks[index] = it }
    }

    fun drawSlot(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, slot: Slot) {
        if (!showLevelReq && !showMissingOverlay) return
        if (slot.index !in listingSlots) return
        val stack = slot.item.takeUnless { it.isEmpty || !it.`is`(Items.PLAYER_HEAD) } ?: return
        if (!enabled || !inDungeonFinder(screen)) return

        val m = marksOf(slot.index, stack)
        if (showLevelReq && m.levelReq != null)
            drawScaled(graphics, m.levelReq, slot.x + 16f - mc.font.width(m.levelReq) * 0.6f, slot.y.toFloat(), 0.6f)

        if (showMissingOverlay) {
            if (m.missingTop.isNotEmpty()) drawScaled(graphics, m.missingTop, slot.x.toFloat(), slot.y + 10f, 0.65f)
            if (m.missingBottom.isNotEmpty()) drawScaled(graphics, m.missingBottom, slot.x.toFloat(), slot.y + 4f, 0.65f)
        }
    }

    private fun drawScaled(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float, scale: Float) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        pose.scale(scale)
        graphics.text(mc.font, text, 0, 0, -1, true)
        pose.popMatrix()
    }

    /** Returns [lines] itself when there is nothing to change. */
    fun decorateTooltip(screen: AbstractContainerScreen<*>, stack: ItemStack, lines: List<Component>): List<Component> {
        if (!listing(screen, stack)) return lines

        var floor = 0
        var master = false
        val missing = classes.toMutableList()
        val wantStats = tooltipCata || tooltipSecrets || tooltipMp || tooltipPb

        val out = lines.map { line ->
            val clean = line.string.noControlCodes
            if (clean.contains("Dungeon: Master Mode")) master = true
            floorRegex.find(clean)?.let { floor = romanToInt(it.groupValues[1]) }
            val m = partyMemberRegex.matchEntire(clean) ?: return@map line
            val (name, className, level) = m.destructured
            classNamed(className)?.let(missing::remove)
            val stats = if (wantStats) memberStats(name, floor, master) else ""
            Component.literal(" §b$name: §e$className ${levelColor(level.toInt())}$level $stats")
        }.toMutableList()

        if (showMissingTooltip) {
            val mine = ClassDetect.myClass()
            out.add(Component.literal("§cMissing: §7" + missing.joinToString(", ") { clazz ->
                val label = clazz.name.lowercase().replaceFirstChar(Char::titlecase)
                if (clazz == mine) "§${clazz.colorCode}§l$label§7" else label
            }))
        }
        return out
    }

    private fun memberStats(name: String, floor: Int, master: Boolean): String {
        val key = name.lowercase()
        val fetched = profiles[key]?.takeIf { System.currentTimeMillis() - it.time < 10 * 60 * 1000L }
        if (fetched == null) {
            if (pending.add(key)) scope.launch {
                fetchPermits.withPermit {
                    val result = RequestUtils.getProfile(name)
                    profiles[key] = Fetched(result.getOrNull()?.memberData)
                    pending.remove(key)
                }
            }
            return "§7(Loading...)"
        }
        val member = fetched.member ?: return "§c(Failed)"
        return buildString {
            if (tooltipCata) append("§b(§6${member.dungeons.dungeonTypes.catacombs.level.toInt()}§b)§r ")
            if (tooltipSecrets) append("§8[§a${member.dungeons.secrets}§8/§b${"%.2f".format(member.dungeons.avrSecrets)}§8]§r ")
            if (tooltipMp) append("§8[§d${if (member.inventoryApi) member.magicalPower else "API off"}§8]§r ")
            if (tooltipPb) {
                val types = member.dungeons.dungeonTypes
                val pb = (if (master) types.mastermode else types.catacombs).fastestTimeSPlus["$floor"]
                append("§8[§9${pb?.let { mmss(it.toLong()) } ?: "N/A"}§8]§r ")
            }
        }
    }

    private fun mmss(ms: Long): String {
        val secs = ms / 1000
        return "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
    }

    private fun levelColor(level: Int) = when {
        level >= 50 -> "§c§l"
        level >= 45 -> "§c"
        level >= 40 -> "§6"
        level >= 35 -> "§d"
        level >= 30 -> "§9"
        level >= 25 -> "§b"
        level >= 20 -> "§2"
        level >= 15 -> "§a"
        level >= 10 -> "§e"
        level >= 5 -> "§f"
        else -> "§7"
    }
}
