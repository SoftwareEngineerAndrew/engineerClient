package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.BlockInteractEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import java.util.Locale
import kotlin.math.hypot

object RelicHelper : Module(
    name = "Relic Helper",
    category = Category.BOSS,
    description = "Blocks right clicks on the wrong relic cauldron and sends every relic's place time.",
    key = null,
) {
    private val blockWrong by BooleanSetting("Block Wrong Cauldron", true, desc = "Right clicks on a cauldron that isn't your relic's do nothing.")
    private val allTimes by BooleanSetting("All Relic Times", true, desc = "Once all five relics are in, says in chat when each was placed.")

    private enum class Relic(val code: String, val cauldron: BlockPos) {
        GREEN("§a", BlockPos(49, 7, 44)),
        RED("§c", BlockPos(51, 7, 42)),
        PURPLE("§5", BlockPos(54, 7, 41)),
        ORANGE("§6", BlockPos(57, 7, 42)),
        BLUE("§b", BlockPos(59, 7, 44)),
    }

    private val PICKED = Regex("^(.+) picked the Corrupted (Red|Purple|Orange|Green|Blue) Relic!$")

    private var held: Relic? = null
    private var p5Ticks = -1
    private val placed = HashMap<Relic, Int>()
    private var sent = false

    private fun relicAt(pos: BlockPos) = Relic.entries.firstOrNull { pos.x == it.cauldron.x && pos.z == it.cauldron.z && (pos.y == 7 || pos.y == 6) }

    private fun reset() { held = null; p5Ticks = -1; placed.clear(); sent = false }

    init {
        on<MessageEvent.Chat> {
            val m = PICKED.matchEntire(message) ?: return@on
            if (m.groupValues[1] == mc.user.name) held = Relic.valueOf(m.groupValues[2].uppercase())
        }

        on<BlockInteractEvent> {
            if (!blockWrong || !DungeonUtils.inBoss) return@on
            val relic = held ?: return@on
            val clicked = relicAt(pos) ?: return@on
            val name = mc.player?.mainHandItem?.hoverName?.string ?: ""
            // The held item can lag behind the pickup, so the SkyBlock Menu and arrows still count as the relic.
            val holdingRelic = name.contains("Relic") || name.contains("SkyBlock Menu", true) || name.contains("Arrow")
            if (holdingRelic && clicked == relic) { held = null; return@on }
            cancel()
            EngineerClient.chat("§cBlocked a click on the wrong cauldron.")
        }

        on<TickEvent.Server> {
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
                val name = r.name.lowercase().replaceFirstChar { it.uppercase() }
                EngineerClient.chat("${r.code}$name §aRelic placed in §e${String.format(Locale.ROOT, "%.2f", placed.getValue(r) / 20.0)}s§a.")
            }
        }

        on<LevelEvent.Load> { reset() }
    }
}
