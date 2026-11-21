package com.adm.features.impl.skyblock

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.events.core.onSend
import com.adm.features.Module
import com.adm.utils.Colors
import com.adm.utils.isItem
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawStyledBox
import com.adm.utils.render.text
import com.adm.utils.skyblock.LocationUtils
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.phys.AABB
import kotlin.math.roundToInt

object SpringBoots : Module(
    name = "Spring Boots",
    description = "Shows the current jump height of your spring boots."
) {
    private val hud by HUD("Spring Boots", "Shows how high you will jump.") { example ->
        val blocks = height
        if (blocks == 0f && !example) return@HUD NONE
        val percent = showPercent
        if (blocks != labelFor || percent != labelPercent || label.isEmpty()) {
            labelFor = blocks
            labelPercent = percent
            label = tierColor(blocks) + if (percent) "${(blocks * 100 / HEIGHTS.last()).roundToInt()}%" else blocks
            labelX = 1 + mc.font.width(PREFIX)
            labelSize = (labelX + mc.font.width(label)) to mc.font.lineHeight
        }
        text(PREFIX, 1, 1, Colors.MINECRAFT_LIGHT_PURPLE, true)
        text(label, labelX, 1, Colors.WHITE, true)
        labelSize
    }

    private val boxColor by ColorSetting("Box Color", Colors.MINECRAFT_RED, true, desc = "Color of the landing box.")
    private val boxStyle by SelectorSetting("Box Style", BoxStyle.OUTLINE, desc = "Style of the landing box.")
    private val throughWalls by BooleanSetting("Through Walls", true, desc = "Draws the box through blocks.")
    private val showPercent by BooleanSetting("Show Percent", false, desc = "Shows the charge as a percent of the highest jump.")

    private const val PREFIX = "Height: "
    private var label = ""
    private var labelFor = 0f
    private var labelPercent = false
    private var labelX = 0
    private var labelSize = 0 to 0
    private val NONE = 0 to 0

    // Jump height in blocks for each charge step heard while sneaking.
    private val HEIGHTS = floatArrayOf(
        0.0f, 3.0f, 6.5f, 9.0f, 11.5f, 13.5f, 16.0f, 18.0f, 19.0f,
        20.5f, 22.5f, 25.0f, 26.5f, 28.0f, 29.0f, 30.0f, 31.0f, 33.0f,
        34.0f, 35.5f, 37.0f, 38.0f, 39.5f, 40.0f, 41.0f, 42.5f, 43.5f,
        44.0f, 45.0f, 46.0f, 47.0f, 48.0f, 49.0f, 50.0f, 51.0f, 52.0f,
        53.0f, 54.0f, 55.0f, 56.0f, 57.0f, 58.0f, 59.0f, 60.0f, 61.0f
    )
    private const val LOW_PITCH = 0.6984127f
    private val HIGH_PITCHES = floatArrayOf(0.82539684f, 0.8888889f, 0.93650794f, 1.0476191f, 1.1746032f, 1.3174603f, 1.7777778f)

    private var lowSteps = 0
    private var highSteps = 0
    @Volatile private var height = 0f

    private fun isHighPitch(pitch: Float): Boolean {
        for (p in HIGH_PITCHES) if (p == pitch) return true
        return false
    }

    private fun tierColor(blocks: Float): String = when {
        blocks <= 13.5 -> "§c"
        blocks <= 22.5 -> "§e"
        blocks <= 33.0 -> "§6"
        blocks <= 43.5 -> "§a"
        else -> "§b"
    }

    // The sneak the server last heard: the charge is its, so a client-only crouch must not keep it.
    private var serverSneak = false

    private fun reset() {
        lowSteps = 0
        highSteps = 0
        height = 0f
    }

    private fun pling(pitch: Float) {
        val player = mc.player ?: return
        if (!serverSneak || !player.onGround() || !EquipmentSlot.FEET.isItem("SPRING_BOOTS")) return
        if (pitch == LOW_PITCH) {
            if (lowSteps < 2) lowSteps++
        } else highSteps++
        height = HEIGHTS[(lowSteps + highSteps).coerceIn(0, HEIGHTS.size - 1)]
    }

    private val onLaunch = Runnable { reset() }

    init {
        onReceive<ClientboundSoundPacket> {
            if (!LocationUtils.isInSkyblock) return@onReceive
            val id = sound.value().location()
            if (SoundEvents.NOTE_BLOCK_PLING.`is`(id)) {
                if (pitch != LOW_PITCH && !isHighPitch(pitch)) return@onReceive
                val p = pitch
                mc.execute { pling(p) }
            } else if (id == SoundEvents.FIREWORK_ROCKET_LAUNCH.location() && (pitch == 0.0952381f || pitch == 1.6984127f)) {
                mc.execute(onLaunch)
            }
        }

        onSend<ServerboundPlayerInputPacket> {
            serverSneak = input().shift()
            if (!serverSneak && (lowSteps != 0 || highSteps != 0)) reset()
        }

        on<TickEvent.End> {
            if (lowSteps == 0 && highSteps == 0 && height == 0f) return@on
            if (!LocationUtils.isInSkyblock || EquipmentSlot.FEET.isItem("SPRING_BOOTS")) return@on
            reset()
        }

        on<RenderExtractEvent> {
            val blocks = height
            if (blocks == 0f || !LocationUtils.isInSkyblock) return@on
            val player = mc.player ?: return@on
            val y = player.y + blocks
            drawStyledBox(AABB(player.x - 0.5, y, player.z - 0.5, player.x + 0.5, y + 1.0, player.z + 0.5), boxColor, boxStyle, !throughWalls)
        }
    }
}
