package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos

object BoxIcedMobs : Module(
    name = "Box Iced Mobs",
    category = Category.DUNGEON,
    description = "Boxes mobs frozen by Ice Spray; worked out client side, so lag shifts it.",
    key = null,
) {
    private val wireColor by ColorSetting("Outline Color", Color(-5383962), true, desc = "Box outline.")
    private val fillColor by ColorSetting("Fill Color", Color(1353563366), true, desc = "Box fill.")
    private val lineWidth by NumberSetting("Line Width", 3f, 0.0..10.0, 0.5f, desc = "Outline width.")

    private const val RANGE = 8.0
    private val CONE = cos(Math.toRadians(45.0))
    private const val FROZEN_TICKS = 100

    private val spawned = ConcurrentLinkedQueue<Int>()
    private val waiting = ArrayList<IntArray>()
    private val frozen = LinkedHashMap<LivingEntity, Int>()
    @Volatile private var serverTicks = 0
    private var heldSprayAt = -100

    private fun isSpray(stack: ItemStack?) = stack?.itemId.let { it == "ICE_SPRAY_WAND" || it == "STARRED_ICE_SPRAY_WAND" }

    init {
        onReceive<ClientboundAddEntityPacket> { if (type == EntityTypes.ITEM) spawned.add(id) }
        on<TickEvent.Server> { serverTicks++ }
        on<LevelEvent.Load> { spawned.clear(); waiting.clear(); frozen.clear(); heldSprayAt = -100 }

        on<TickEvent.End> {
            val level = mc.level ?: return@on
            val me = mc.player ?: return@on
            val now = serverTicks
            if (!DungeonUtils.inDungeons) { spawned.clear(); waiting.clear(); frozen.clear(); return@on }
            if (isSpray(me.mainHandItem)) heldSprayAt = now
            frozen.entries.removeIf { now >= it.value || it.key.isRemoved || it.key.isDeadOrDying }

            while (true) waiting += intArrayOf(spawned.poll() ?: break, 5)
            var ice = false
            val it = waiting.iterator()
            while (it.hasNext()) {
                val w = it.next()
                val item = level.getEntity(w[0]) as? ItemEntity
                if (item != null) { it.remove(); if (item.item.`is`(Items.ICE)) ice = true }
                else if (--w[1] < 0) it.remove()
            }
            if (!ice) return@on

            val sprayers = DungeonUtils.dungeonTeammates.mapNotNull { p -> p.entity?.takeIf { isSpray(it.mainHandItem) } }.toMutableList()
            if (sprayers.isEmpty() && now - heldSprayAt < 5) sprayers += me
            for (sprayer in sprayers) freezeInFront(sprayer, now)
        }

        on<RenderExtractEvent> {
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false).toDouble()
            for (mob in frozen.keys) {
                val x = Mth.lerp(pt, mob.xOld, mob.x)
                val y = Mth.lerp(pt, mob.yOld, mob.y)
                val z = Mth.lerp(pt, mob.zOld, mob.z)
                val box = mob.getDimensions(mob.pose).makeBoundingBox(x, y, z).inflate(0.1)
                drawFilledBox(box, fillColor, depth = true)
                drawWireFrameBox(box, wireColor, lineWidth, true)
            }
        }
    }

    private fun freezeInFront(sprayer: Player, now: Int) {
        val level = mc.level ?: return
        val eye = sprayer.eyePosition
        val look = sprayer.lookAngle
        val teammates = DungeonUtils.dungeonTeammates.mapNotNullTo(HashSet()) { it.entity }
        for (entity in level.entitiesForRendering()) {
            if (entity !is LivingEntity || entity is ArmorStand || entity === sprayer || entity === mc.player || entity in teammates) continue
            val bb = entity.boundingBox
            val points = listOf(bb.center, Vec3(bb.minX, bb.minY, bb.minZ), Vec3(bb.maxX, bb.minY, bb.minZ), Vec3(bb.minX, bb.minY, bb.maxZ), Vec3(bb.maxX, bb.minY, bb.maxZ),
                Vec3(bb.minX, bb.maxY, bb.minZ), Vec3(bb.maxX, bb.maxY, bb.minZ), Vec3(bb.minX, bb.maxY, bb.maxZ), Vec3(bb.maxX, bb.maxY, bb.maxZ))
            val inCone = points.any { p ->
                val d = p.subtract(eye)
                val len = d.length()
                len <= RANGE && (len < 0.01 || d.dot(look) / len >= CONE)
            }
            if (inCone) frozen[entity] = now + FROZEN_TICKS
        }
    }
}
