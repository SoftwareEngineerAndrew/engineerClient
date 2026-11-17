package com.adm.features.impl.boss

import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.events.*
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.Colors
import com.adm.utils.modMessage
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawStyledBox
import com.adm.utils.render.textDim
import com.adm.utils.renderBoundingBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

object LividSolver : Module(
    name = "Livid Solver",
    description = "Provides a visual cue for the correct Livid's location in the boss fight."
) {
    private val hud by HUD("Invulnerability Timer", "Shows time remaining on Livid's invulnerability.") { example ->
        val ticks = if (example) INVULN_TICKS else if (inF5Boss() && invulnTicks > 0) invulnTicks else return@HUD 0 to 0
        textDim(timerText(ticks), 0, 0)
    }
    private val highlightColor by ColorSetting("Highlight Color", Colors.MINECRAFT_LIGHT_PURPLE, true, desc = "Color of the highlight box around Livid.")

    private const val INVULN_TICKS = 340
    private const val START_LINE = "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows."
    private val WOOL_POS = BlockPos(5, 108, 43)

    private val names = arrayOf("Vendetta", "Crossed", "Arcade", "Smile", "Doctor", "Purple", "Scream", "Frog", "Hockey")
    private val codes = charArrayOf('f', 'd', 'e', 'a', '7', '5', '9', '2', 'c')
    private val wools: Array<Block> = arrayOf(DyeColor.WHITE, DyeColor.MAGENTA, DyeColor.YELLOW, DyeColor.LIME, DyeColor.GRAY, DyeColor.PURPLE, DyeColor.BLUE, DyeColor.GREEN, DyeColor.RED)
        .map { Blocks.WOOL.pick(it) }.toTypedArray()
    private val bodyNames = Array(names.size) { "${names[it]} Livid" }
    private const val DEFAULT_LIVID = 8

    private var livid = DEFAULT_LIVID
    private val bodies = arrayOfNulls<Player>(names.size)
    private var invulnTicks = 0

    private var shownTicks = -1
    private var shownText = ""

    private fun inF5Boss() = DungeonUtils.inBoss && DungeonUtils.floor?.floorNumber == 5

    private fun timerText(ticks: Int): String {
        if (ticks != shownTicks) {
            val color = if (ticks > 260) "§a" else if (ticks > 130) "§e" else "§c"
            shownText = "§bLivid: $color${ticks}t "
            shownTicks = ticks
        }
        return shownText
    }

    init {
        on<MessageEvent.Chat> {
            if (message == START_LINE && DungeonUtils.inDungeons && DungeonUtils.floor?.floorNumber == 5) invulnTicks = INVULN_TICKS
        }

        on<BlockUpdateEvent> {
            if (pos != WOOL_POS || !inF5Boss()) return@on
            val block = updated.block
            val found = wools.indexOfFirst { it === block }
            if (found < 0) return@on
            livid = found
            modMessage("Found Livid: §${codes[found]}${names[found]}")
        }

        on<EntityEvent.SetData> {
            val player = entity as? Player ?: return@on
            if (player.plainTextName == bodyNames[livid] && inF5Boss()) bodies[livid] = player
        }

        on<RenderExtractEvent> {
            val body = bodies[livid] ?: return@on
            if (!inF5Boss() || mc.player?.getEffect(MobEffects.BLINDNESS) != null) return@on
            drawStyledBox(body.renderBoundingBox, highlightColor, BoxStyle.FILLED_OUTLINE, true)
        }

        on<TickEvent.Server> {
            if (invulnTicks >= 0 && DungeonUtils.floor?.floorNumber == 5) invulnTicks--
        }

        on<LevelEvent.Load> {
            livid = DEFAULT_LIVID
            bodies.fill(null)
            invulnTicks = 0
        }
    }
}
