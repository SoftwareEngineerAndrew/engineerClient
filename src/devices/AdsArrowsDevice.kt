// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.devices

import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.settings.BooleanSetting
import com.engineerclient.ads.settings.NumberSetting
import com.engineerclient.ads.settings.SelectorSetting
import com.engineerclient.ads.settings.Settings
import com.engineerclient.devices.DeviceSolvers.DeviceColor
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * The Arrows Device (i4, F7's fourth device) in ADS's worlds, as Odin's Arrows Device: the board's blocks already
 * hit, the one lit to hit now, and optionally where to aim to hit the most of the rest. Forgets it all once the
 * device is done (your "completed a device!" line, or its stand turning Active while you're in the room), on a new
 * world and on a P3 Sim restart.
 */
object AdsArrowsDevice : Settings("Arrows Device", "devices-arrows-device.json") {
    private val enabledS = +BooleanSetting("Solver", true, desc = "Marks the i4 board's blocks already hit and the one to hit, in the P3 Sim and Rustclear worlds.")
    private val markedS = +SelectorSetting("Marked Color", DeviceColor.AQUA, desc = "The blocks already hit.")
    private val targetS = +SelectorSetting("Target Color", DeviceColor.LIGHT_PURPLE, desc = "The block to hit.")
    private val opacityS = +NumberSetting("Opacity", 50, 10..100, 5, unit = "%", desc = "How solid the boxes are.")
    private val aimS = +BooleanSetting("Show Aim Positions", false, desc = "Where to aim (green, then gold, then red) to hit the most blocks not hit yet, two at a time.")

    private val roomBox = AABB(20.0, 100.0, 30.0, 89.0, 151.0, 51.0)
    /** Where the device's stand is (its name turns "Active" when it's done). */
    private val standBox = AABB(62.5, 124.5, 33.5, 64.5, 127.5, 35.5)

    private val marked = mutableSetOf<BlockPos>()
    private var target: BlockPos? = null
    private var aims: List<AimPosition> = emptyList()
    private var standActive: Boolean? = null

    private data class AimPosition(val position: Vec3, val coveredBlocks: Set<BlockPos>, val distance: Double)

    private val devicePositions = listOf(
        BlockPos(68, 130, 50), BlockPos(66, 130, 50), BlockPos(64, 130, 50),
        BlockPos(68, 128, 50), BlockPos(66, 128, 50), BlockPos(64, 128, 50),
        BlockPos(68, 126, 50), BlockPos(66, 126, 50), BlockPos(64, 126, 50),
    )

    private val adjacentPairs by lazy {
        devicePositions.flatMapIndexed { i, a ->
            devicePositions.drop(i + 1).mapNotNull { b -> if (abs(a.x - b.x) == 2 && a.y == b.y && a.z == b.z) a to b else null }
        }
    }

    fun reset() { marked.clear(); target = null; aims = emptyList(); standActive = null }

    private val inRoom: Boolean get() = mc.player?.let { roomBox.contains(it.position()) } == true

    fun blockChanged(pos: BlockPos, old: BlockState, updated: BlockState) {
        if (!enabledS.value || pos !in devicePositions) return
        if (old.block == Blocks.EMERALD_BLOCK && updated.block == Blocks.DYED_TERRACOTTA.blue()) {
            marked += pos.immutable()
            if (target == pos) target = null
            if (aimS.value) aims = optimalAims(pos)
        } else if (old.block == Blocks.DYED_TERRACOTTA.blue() && updated.block == Blocks.EMERALD_BLOCK) {
            marked -= pos
            target = pos.immutable()
            if (aimS.value) aims = optimalAims(pos)
        }
    }

    /** Odin listens for the stand's name being set; here it is looked at each tick, and done is its turn to Active. */
    fun tick() {
        if (!enabledS.value) return
        val level = mc.level ?: return
        val active = level.getEntitiesOfClass(ArmorStand::class.java, standBox).any { it.name.string.replace(CONTROL_CODES, "") == "Active" }
        if (standActive == false && active && inRoom) reset()
        standActive = active
    }

    fun chat(text: String) {
        if (!enabledS.value || !inRoom) return
        if (DEVICE_DONE.find(text)?.groupValues?.get(1) == mc.player?.name?.string) { reset(); standActive = true }
    }

    fun draw(ctx: LevelRenderContext) {
        if (!enabledS.value) return
        val alpha = opacityS.value / 100.0
        for (p in marked) DeviceRender.filled(ctx, AABB(p), markedS.value.argb(alpha))
        target?.let { DeviceRender.filled(ctx, AABB(it), targetS.value.argb(alpha)) }
        if (aimS.value) {
            val colors = listOf(DeviceColor.GREEN, DeviceColor.GOLD, DeviceColor.RED)
            aims.take(3).forEachIndexed { i, aim ->
                DeviceRender.filled(ctx, AABB.unitCubeFromLowerCorner(aim.position.add(-0.5, -0.5, -0.1)), colors[i].argb(alpha))
            }
        }
    }

    private fun optimalAims(target: BlockPos): List<AimPosition> {
        val unmarked = devicePositions.filterNot { it in marked }

        fun aimAt(a: BlockPos, b: BlockPos): AimPosition? =
            listOf(a, b).filter { it in unmarked }.takeIf { it.isNotEmpty() }?.let { AimPosition(a.midpoint(b), it.toSet(), 0.0) }

        val first = adjacentPairs.filter { (a, b) -> target == a || target == b }
            .mapNotNull { (a, b) -> aimAt(a, b) }
            .maxByOrNull { it.coveredBlocks.size } ?: return emptyList()
        val rest = adjacentPairs.filterNot { (a, b) -> target == a || target == b }.mapNotNull { (a, b) -> aimAt(a, b) }
        return bestCombination(first, rest)
    }

    private fun bestCombination(first: AimPosition, aims: List<AimPosition>): List<AimPosition> {
        val result = mutableListOf(first)
        val covered = first.coveredBlocks.toMutableSet()
        repeat(2) {
            val best = aims.filterNot { it in result }.maxWithOrNull(compareBy(
                { it.coveredBlocks.count { b -> b !in covered } }, // new unmarked blocks
                { it.coveredBlocks.size }, // total blocks covered
                { -(result.last().position.distanceTo(it.position)) }, // closer to the last aim
            )) ?: return@repeat
            result += best
            covered += best.coveredBlocks
        }
        return result.mapIndexed { i, a -> a.copy(distance = if (i == 0) 0.0 else a.position.distanceTo(result[i - 1].position)) }
    }

    private fun BlockPos.midpoint(other: BlockPos): Vec3 = Vec3((x + other.x) / 2.0 + 0.5, y + 0.5, z + 0.5)

    private val DEVICE_DONE = Regex("^(.{1,16}) completed a device! \\((\\d)/(\\d)\\)$")
    private val CONTROL_CODES = Regex("§.")
}
