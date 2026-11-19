package com.adm.features.impl.render

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.events.LevelEvent
import com.adm.events.ParticleEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.core.on
import com.adm.events.core.onSend
import com.adm.features.Module
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.Colors
import com.adm.utils.ServerUtils
import com.adm.utils.itemId
import com.adm.utils.render.drawCylinder
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

object GyroWand : Module(
    name = "Gyro Wand",
    description = "Shows area of effect and cooldown of the Gyrokinetic Wand."
) {
    private val color by ColorSetting("Color", Colors.MINECRAFT_DARK_PURPLE.withAlpha(0.5f), true, desc = "The color of the Gyrokinetic Wand range.")
    private val showCooldown by BooleanSetting("Show Cooldown", true, desc = "Shows the cooldown of the Gyrokinetic Wand.")
    private val cooldownColor by ColorSetting("Cooldown Color", Colors.MINECRAFT_RED.withAlpha(0.5f), true, desc = "The color of the cooldown of the Gyrokinetic Wand.").withDependency { showCooldown }
    private val depthCheck by BooleanSetting("Depth Check", true, desc = "Whether or not the cylinder should have a depth check.")

    // The cast is five still witch particles at one height within 120 ms.
    private var groupStart = 0L
    private var groupY = 0.0
    private var groupSize = 0
    private var castAt = 0L
    private var usedAt = 0L

    private fun cooling(now: Long) = now - castAt < DungeonUtils.getAbilityCooldown(30_000L)

    init {
        on<ParticleEvent> {
            if (xd != 0.0 || yd != 0.0 || zd != 0.0 || particle !== ParticleTypes.WITCH) return@on
            val now = System.currentTimeMillis()
            // Only a burst shortly after your own click: another player's gyro makes the same one.
            if (cooling(now) || now - usedAt > 200L + ServerUtils.averagePing) return@on
            if (groupSize == 0 || now - groupStart > 120L || y - groupY > 0.02 || groupY - y > 0.02) {
                groupStart = now
                groupY = y
                groupSize = 1
            } else if (++groupSize >= 5) {
                castAt = now
                groupSize = 0
            }
        }

        onSend<ServerboundUseItemPacket> {
            if (mc.player?.mainHandItem?.itemId == "GYROKINETIC_WAND") usedAt = System.currentTimeMillis()
        }

        on<RenderExtractEvent> {
            val player = mc.player ?: return@on
            val held = player.mainHandItem
            if (!held.`is`(Items.BLAZE_ROD) || held.itemId != "GYROKINETIC_WAND") return@on
            val hit = Etherwarp.getEtherPos(player.position(), 25.0)
            val block = hit.pos ?: return@on
            if (hit.state?.isAir != false) return@on
            val level = mc.level ?: return@on
            val above = block.above()
            if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty) return@on
            val tint = if (showCooldown && cooling(System.currentTimeMillis())) cooldownColor else color
            drawCylinder(Vec3(block.x + 0.5, block.y + 1.0, block.z + 0.5), 10f, 0.3f, tint, 64, depth = depthCheck)
        }

        on<LevelEvent.Load> {
            castAt = 0L
            usedAt = 0L
            groupSize = 0
        }
    }
}
