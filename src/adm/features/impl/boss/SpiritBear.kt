package com.adm.features.impl.boss

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.events.BlockUpdateEvent
import com.adm.events.EntityEvent
import com.adm.events.LevelEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.Colors
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawStyledBox
import com.adm.utils.render.textDim
import com.adm.utils.renderBoundingBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Ghast
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks

object SpiritBear : Module(
    name = "Spirit Bear",
    description = "Displays the current state of Spirit Bear."
) {
    private val hud by HUD(name, "Displays the current state of Spirit Bear in the HUD.", false) { example ->
        if (example) return@HUD textDim("§6Bear: §e1.45s", 0, 0, Colors.WHITE)
        val floor = DungeonUtils.floor
        if (floor?.floorNumber != 4 || !DungeonUtils.inBoss) return@HUD 0 to 0
        textDim(label(floor.isMM), 0, 0, Colors.WHITE)
    }

    private val thorn by BooleanSetting("Highlight Thorn", false, desc = "Draws a box around Thorn.")
    private val thornColor by ColorSetting("Thorn Color", Colors.MINECRAFT_RED, true, desc = "Color of Thorn's box.").withDependency { thorn }
    private val bow by BooleanSetting("Highlight Spirit Bow", false, desc = "Draws a box around the Spirit Bow.")
    private val bowColor by ColorSetting("Spirit Bow Color", Colors.MINECRAFT_AQUA, true, desc = "Color of the Spirit Bow's box.").withDependency { bow }
    private val bear by BooleanSetting("Highlight Spirit Bear", false, desc = "Draws a box around the Spirit Bear.")
    private val bearColor by ColorSetting("Spirit Bear Color", Colors.MINECRAFT_LIGHT_PURPLE, true, desc = "Color of the Spirit Bear's box.").withDependency { bear }

    private var thornEntity: Entity? = null
    private var bowEntity: Entity? = null
    private var bearEntity: Entity? = null

    private fun inF4() = DungeonUtils.inDungeons && DungeonUtils.floor?.floorNumber == 4

    private fun RenderExtractEvent.box(entity: Entity?, color: com.adm.utils.Color) {
        if (entity == null || entity.isRemoved) return
        drawStyledBox(entity.renderBoundingBox, color, BoxStyle.FILLED_OUTLINE, true)
    }

    private const val LAMP_Y = 77
    private const val SPAWN_TICKS = 68
    private val BEAR_LAMP = BlockPos.asLong(7, LAMP_Y, 34)

    // The lamps light one per kill; the last one lit starts the bear's spawn.
    private val normalLamps = lamps(
        -3, 33, -9, 31, -16, 26, -20, 20, -23, 13, -24, 6, -24, 0, -22, -7, -18, -13, -12, -19, -5, -22, 1, -24, 8, -24,
        14, -23, 21, -19, 27, -14, 31, -8, 33, -1, 34, 5, 33, 12, 31, 19, 27, 25, 20, 30, 14, 33, 7, 34
    )
    private val masterLamps = lamps(
        -2, 33, -7, 32, -13, 28, -17, 24, -21, 18, -23, 13, -24, 7, -24, 2, -23, -4, -21, -9, -17, -14, -12, -19, -6, -22,
        -1, -23, 5, -24, 10, -24, 16, -22, 21, -19, 27, -15, 30, -10, 32, -5, 34, 1, 34, 7, 33, 12, 31, 18, 28, 23, 23, 28,
        18, 31, 12, 33, 7, 34
    )

    private fun lamps(vararg xz: Int) = LongOpenHashSet(xz.size / 2).apply {
        for (i in xz.indices step 2) add(BlockPos.asLong(xz[i], LAMP_Y, xz[i + 1]))
    }

    // -1 before the last lamp is lit, 0 once the bear is up, otherwise ticks until it spawns.
    private var spawnTicks = -1
    private var lit = 0

    private var labelKey = Long.MIN_VALUE
    private var labelText = ""

    private fun label(master: Boolean): String {
        val key = (spawnTicks.toLong() shl 32) or (lit.toLong() shl 1) or (if (master) 1L else 0L)
        if (key != labelKey) {
            labelKey = key
            labelText = "§6Bear: " + when {
                spawnTicks < 0 -> "§d$lit/${if (master) masterLamps.size else normalLamps.size}"
                spawnTicks > 0 -> "§e${spawnTicks / 20}.${((spawnTicks % 20) * 5).toString().padStart(2, '0')}s"
                else -> "§aAlive!"
            }
        }
        return labelText
    }

    init {
        on<BlockUpdateEvent> {
            if (pos.y != LAMP_Y) return@on
            val floor = DungeonUtils.floor
            if (floor?.floorNumber != 4 || !DungeonUtils.inBoss) return@on
            val key = pos.asLong()
            val lamps = if (floor.isMM) masterLamps else normalLamps
            if (key !in lamps) return@on
            val now = updated.block
            val before = old.block
            if (now === Blocks.SEA_LANTERN && before === Blocks.COAL_BLOCK) {
                if (lit < lamps.size) lit++
                if (key == BEAR_LAMP) spawnTicks = SPAWN_TICKS
            } else if (now === Blocks.COAL_BLOCK && before === Blocks.SEA_LANTERN) {
                if (lit > 0) lit--
                if (key == BEAR_LAMP) spawnTicks = -1
            }
        }

        on<TickEvent.Server> {
            if (spawnTicks > 0) spawnTicks--
        }

        on<EntityEvent.Add> {
            if (!inF4()) return@on
            when (entity) {
                is Ghast -> thornEntity = entity
                is Player -> if (entity.plainTextName.startsWith("Spirit Bear")) bearEntity = entity
                is ArmorStand -> if (entity.getItemBySlot(EquipmentSlot.MAINHAND).hoverName.string == "Bow") bowEntity = entity
            }
        }

        on<EntityEvent.SetItemSlot> {
            if (slot != EquipmentSlot.MAINHAND || entity !is ArmorStand || !inF4()) return@on
            if (stack.hoverName.string == "Bow") bowEntity = entity
            else if (entity === bowEntity) bowEntity = null
        }

        on<RenderExtractEvent> {
            if (!thorn && !bow && !bear || !inF4() || !DungeonUtils.inBoss) return@on
            if (thorn) box(thornEntity, thornColor)
            if (bow) box(bowEntity, bowColor)
            if (bear) box(bearEntity, bearColor)
        }

        on<LevelEvent.Load> {
            thornEntity = null
            bowEntity = null
            bearEntity = null
            lit = 0
            spawnTicks = -1
        }
    }
}
