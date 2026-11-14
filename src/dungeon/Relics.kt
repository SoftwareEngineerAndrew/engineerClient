package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.BlockInteractEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.SetSlotEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.PersonalBest
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.render.drawCustomBeacon
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import java.util.Locale
import kotlin.math.hypot

object Relics : Module(
    name = "Relics",
    category = Category.BOSS,
    description = "P5 relics: correct cauldron beacon, wrong cauldron click block, place times and PBs.",
    key = null,
) {
    private val relicBeacon by BooleanSetting("Relic Beacon", true, desc = "Draws a beacon at your relic's cauldron.")
    private val blockWrong by BooleanSetting("Block Wrong Cauldron", true, desc = "Right clicks on a cauldron that isn't your relic's do nothing.")
    private val allTimes by BooleanSetting("All Relic Times", true, desc = "Once all five relics are in, says in chat when each was placed.")

    private val relicPBs = PersonalBest(this, "Relics")

    private enum class Relic(val id: String, val colorCode: Char, val color: Color, val cauldron: BlockPos) {
        Green("GREEN_KING_RELIC", 'a', Colors.MINECRAFT_GREEN, BlockPos(49, 7, 44)),
        Red("RED_KING_RELIC", 'c', Colors.MINECRAFT_RED, BlockPos(51, 7, 42)),
        Purple("PURPLE_KING_RELIC", '5', Colors.MINECRAFT_DARK_PURPLE, BlockPos(54, 7, 41)),
        Orange("ORANGE_KING_RELIC", '6', Colors.MINECRAFT_GOLD, BlockPos(57, 7, 42)),
        Blue("BLUE_KING_RELIC", 'b', Colors.MINECRAFT_BLUE, BlockPos(59, 7, 44)),
    }

    private val PICKED = Regex("^(.+) picked the Corrupted (Red|Purple|Orange|Green|Blue) Relic!$")

    private var currentRelic: Relic? = null
    private var relicPlaceTick = -1
    private var p5Ticks = -1
    private val placed = HashMap<Relic, Int>()
    private var sent = false

    private fun relicAt(pos: BlockPos) = Relic.entries.firstOrNull { pos.x == it.cauldron.x && pos.z == it.cauldron.z && (pos.y == 7 || pos.y == 6) }

    private fun reset() {
        currentRelic = null; relicPlaceTick = -1; p5Ticks = -1; placed.clear(); sent = false
    }

    init {
        on<SetSlotEvent> {
            if (DungeonUtils.getF7Phase() != M7Phases.P5 || menu !== mc.player?.inventoryMenu || slotIndex != 44) return@on
            currentRelic = Relic.entries.find { it.id == itemStack.itemId }
            if (currentRelic != null && relicPlaceTick == -1) relicPlaceTick = 0
        }

        on<MessageEvent.Chat> {
            val m = PICKED.matchEntire(message) ?: return@on
            if (m.groupValues[1] == mc.user.name) currentRelic = Relic.entries.find { it.name == m.groupValues[2] }
        }

        on<BlockInteractEvent> {
            if (DungeonUtils.getF7Phase() != M7Phases.P5) return@on
            val relic = currentRelic ?: return@on
            val clicked = relicAt(pos) ?: return@on
            val name = mc.player?.mainHandItem?.hoverName?.string ?: ""
            // The held item can lag behind the pickup, so the SkyBlock Menu and arrows still count as the relic.
            val holdingRelic = name.contains("Relic") || name.contains("SkyBlock Menu", true) || name.contains("Arrow")
            if (blockWrong && !(holdingRelic && clicked == relic)) {
                cancel()
                EngineerClient.chat("§cBlocked a click on the wrong cauldron.")
                return@on
            }
            if (clicked != relic || relicPlaceTick < 0 || !mc.level?.getBlockState(pos)?.block.equalsOneOf(Blocks.CAULDRON, Blocks.ANVIL)) return@on
            relicPBs.time(relic.name, relicPlaceTick / 20f, message = "§${relic.colorCode}${relic.name} relic §7placed in §6")
            relicPlaceTick = -2
        }

        on<RenderExtractEvent> {
            if (DungeonUtils.getF7Phase() != M7Phases.P5 || !relicBeacon) return@on
            currentRelic?.let { drawCustomBeacon("", it.cauldron, it.color, distance = false) }
        }

        on<TickEvent.Server> {
            if (DungeonUtils.getF7Phase() == M7Phases.P5 && relicPlaceTick >= 0) relicPlaceTick++
            if (p5Ticks < 0 && DungeonUtils.inBoss && DungeonUtils.getF7Phase() == M7Phases.P5) p5Ticks = 0
            else if (p5Ticks >= 0) p5Ticks++
        }

        on<TickEvent.End> {
            if (!allTimes || sent || p5Ticks < 0) return@on
            val level = mc.level ?: return@on
            for (e in level.entitiesForRendering()) {
                if (e !is ArmorStand) continue
                val id = e.getItemBySlot(EquipmentSlot.MAINHAND).itemId.ifEmpty { e.getItemBySlot(EquipmentSlot.HEAD).itemId }
                if (!id.contains("RELIC")) continue
                for (r in Relic.entries) {
                    if (r !in placed && hypot(e.x - r.cauldron.x - 0.5, e.z - r.cauldron.z - 0.5) < 1.5) placed[r] = p5Ticks
                }
            }
            if (placed.size < Relic.entries.size) return@on
            sent = true
            for (r in Relic.entries.sortedBy { placed[it] }) {
                EngineerClient.chat("§${r.colorCode}${r.name} §aRelic placed in §e${String.format(Locale.ROOT, "%.2f", placed.getValue(r) / 20.0)}s§a.")
            }
        }

        on<LevelEvent.Load> { reset() }
    }
}
