package com.adm.features.impl.boss

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.events.*
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.Colors
import com.adm.utils.alert
import com.adm.utils.modMessage
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawStyledBox
import com.adm.utils.render.drawTracer
import com.adm.utils.render.textDim
import com.adm.utils.renderBoundingBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
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
    private val timerPrefix by BooleanSetting("Timer Prefix", true, desc = "\"Livid:\" in front of the timer.")
    private val highlightColor by ColorSetting("Highlight Color", Colors.MINECRAFT_LIGHT_PURPLE, true, desc = "Color of the highlight box around Livid.")
    private val tracer by BooleanSetting("Tracer", false, desc = "Draws a line to the correct Livid.")
    private val tracerColor by ColorSetting("Tracer Color", Colors.MINECRAFT_LIGHT_PURPLE, true, desc = "Color of the tracer.").withDependency { tracer }
    private val hideWrong by BooleanSetting("Hide Wrong Livids", false, desc = "Hides the other Livids once the correct one is known.")
    private val iceSprayAlert by BooleanSetting("Ice Spray Alert", false, desc = "Title and sound when Livid's invulnerability ends.")

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
    private var known = false
    private val bodies = arrayOfNulls<Player>(names.size)
    private var invulnTicks = 0

    private var shownTicks = -1
    private var shownText = ""

    private fun inF5Boss() = DungeonUtils.inBoss && DungeonUtils.floor?.floorNumber == 5

    private fun found(index: Int) {
        if (known && index == livid) return
        livid = index
        known = true
        modMessage("Found Livid: §${codes[index]}${names[index]}")
    }

    @JvmStatic
    fun hidesWrong(entity: Entity): Boolean {
        if (!enabled || !hideWrong || !known || entity !is Player || !inF5Boss()) return false
        val name = entity.plainTextName
        return name != bodyNames[livid] && name.endsWith(" Livid") && name in bodyNames
    }

    private var shownPrefix = true

    private fun timerText(ticks: Int): String {
        val prefix = com.adm.features.impl.render.ClickGUIModule.timerPrefixes && timerPrefix
        if (ticks != shownTicks || prefix != shownPrefix) {
            val color = if (ticks > 260) "§a" else if (ticks > 130) "§e" else "§c"
            shownText = "${if (prefix) "§bLivid: " else ""}$color${ticks}t "
            shownTicks = ticks
            shownPrefix = prefix
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
            val index = wools.indexOfFirst { it === block }
            if (index >= 0) found(index)
        }

        // The wool update can land before the boss starts, so the block is read again each tick.
        on<TickEvent.End> {
            if (!inF5Boss()) return@on
            val block = level.getBlockState(WOOL_POS).block
            val index = wools.indexOfFirst { it === block }
            if (index >= 0) found(index)
            val body = bodies[livid]
            if (body != null && !body.isRemoved) return@on
            bodies[livid] = null
            val players = level.players()
            for (i in players.indices) {
                val p = players[i]
                if (p.plainTextName == bodyNames[livid]) {
                    bodies[livid] = p
                    break
                }
            }
        }

        on<EntityEvent.SetData> {
            val player = entity as? Player ?: return@on
            if (inF5Boss() && player.plainTextName == bodyNames[livid]) bodies[livid] = player
        }

        on<RenderExtractEvent> {
            val body = bodies[livid] ?: return@on
            if (!inF5Boss() || mc.player?.getEffect(MobEffects.BLINDNESS) != null) return@on
            val box = body.renderBoundingBox
            drawStyledBox(box, highlightColor, BoxStyle.FILLED_OUTLINE, true)
            if (tracer && !body.isDeadOrDying) drawTracer(box.center, tracerColor, false)
        }

        on<TickEvent.Server> {
            if (invulnTicks >= 0 && DungeonUtils.floor?.floorNumber == 5) {
                invulnTicks--
                if (invulnTicks == 0 && iceSprayAlert) mc.execute { alert("§bIce Spray Livid!") }
            }
        }

        on<LevelEvent.Load> {
            livid = DEFAULT_LIVID
            known = false
            bodies.fill(null)
            invulnTicks = 0
        }
    }
}
