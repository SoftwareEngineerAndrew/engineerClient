package com.engineerclient.devices

import com.devgineerclient.practice.I4Aims
import com.engineerclient.EngineerClient.mc
import com.adm.events.*
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.utils.alert
import com.adm.utils.modMessage
import com.adm.utils.render.drawFilledBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** The Arrows Device (i4): blocks already hit and the one to hit, and where to aim (Terminator cubes). */
object DevArrows {
    fun reset() {
        markedPositions.clear()
        targetPosition = null
        optimalAimPositions = emptyList()
    }

    /** A new attempt (the P3 Sim's restore): as reset, and not done. */
    fun restart() { reset(); isDeviceComplete = false }

    private val deviceCompleteRegex = Regex("^(.{1,16}) completed a device! \\((\\d)/(\\d)\\)$")
    private val roomBoundingBox = AABB(20.0, 100.0, 30.0, 89.0, 151.0, 51.0)
    private val markedPositions = mutableSetOf<BlockPos>()
    private var targetPosition: BlockPos? = null
    private var isDeviceComplete = false

    private data class AimPosition(val position: Vec3, val coveredBlocks: Set<BlockPos>, val distance: Double)

    private var optimalAimPositions: List<AimPosition> = emptyList()

    private val adjacentPairs by lazy {
        devicePositions.flatMapIndexed { i, block1 ->
            devicePositions.drop(i + 1).mapNotNull { block2 ->
                if (abs(block1.x - block2.x) == 2 && block1.y == block2.y && block1.z == block2.z) block1 to block2 else null
            }
        }
    }

    private val showAims get() = Devices.showAimPositions && !I4Aims.replacesOdinAims()

    fun install() {
        on<BlockUpdateEvent> {
            if (!Devices.arrowsOn || DungeonUtils.getF7Phase() != M7Phases.P3 || !devicePositions.contains(pos)) return@on

            if (old.block == Blocks.EMERALD_BLOCK && updated.block == Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE)) {
                markedPositions.add(pos.immutable())
                if (targetPosition == pos) targetPosition = null
                if (showAims) optimalAimPositions = calculateOptimalAimPositions(pos)
            } else if (old.block == Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE) && updated.block == Blocks.EMERALD_BLOCK) {
                markedPositions.remove(pos)
                targetPosition = pos.immutable()
                if (showAims) optimalAimPositions = calculateOptimalAimPositions(pos)
            }
        }

        on<RenderExtractEvent> {
            if (!Devices.arrowsOn || DungeonUtils.getF7Phase() != M7Phases.P3) return@on

            markedPositions.forEach { position ->
                drawFilledBox(AABB(position), Devices.markedColor, depth = Devices.depthCheck)
            }

            targetPosition?.let { position ->
                drawFilledBox(AABB(position), Devices.targetColor, depth = Devices.depthCheck)
            }

            if (showAims) {
                val colors = listOf(Devices.firstAimColor, Devices.secondAimColor, Devices.thirdAimColor)
                optimalAimPositions.take(3).forEachIndexed { index, aimPos ->
                    drawFilledBox(AABB.unitCubeFromLowerCorner(aimPos.position.add(-0.5, -0.5, -0.1)), colors[index], true)
                }
            }
        }

        on<LevelEvent.Load> {
            markedPositions.clear()
            targetPosition = null
            isDeviceComplete = false
            optimalAimPositions = emptyList()
        }

        on<MessageEvent.Chat> {
            if (!Devices.arrowsOn || DungeonUtils.getF7Phase() != M7Phases.P3 || !isPlayerInRoom || isDeviceComplete) return@on
            if (deviceCompleteRegex.find(message)?.groupValues?.get(1) == mc.player?.name?.string) onComplete("Chat")
        }

        on<EntityEvent.SetData> {
            if (!Devices.arrowsOn || DungeonUtils.getF7Phase() != M7Phases.P3 || !isPlayerInRoom || isDeviceComplete) return@on
            if (entity.name.string == "Active") onComplete("Entity")
        }
        EventBus.subscribe(this)
    }

    private fun onComplete(method: String) {
        isDeviceComplete = true

        if (Devices.completeAlert) {
            modMessage("§aSharp shooter device complete §7($method)")
            alert("§aDevice Complete")
        }
        reset()
    }

    private val isPlayerInRoom: Boolean
        get() = mc.player?.let { roomBoundingBox.contains(it.position()) } == true

    private fun calculateOptimalAimPositions(target: BlockPos): List<AimPosition> {
        val unmarkedBlocks = devicePositions.filterNot { it in markedPositions }

        fun createAimPosition(block1: BlockPos, block2: BlockPos): AimPosition? =
            listOf(block1, block2).filter { it in unmarkedBlocks }.takeIf { it.isNotEmpty() }?.let {
                AimPosition(block1.midpoint(block2), it.toSet(), 0.0)
            }

        val greenAim = adjacentPairs
            .filter { (block1, block2) -> target in listOf(block1, block2) }
            .mapNotNull { (block1, block2) -> createAimPosition(block1, block2) }
            .maxByOrNull { it.coveredBlocks.size } ?: return emptyList()

        val remainingAimPositions = adjacentPairs
            .filterNot { (block1, block2) -> target in listOf(block1, block2) }
            .mapNotNull { (block1, block2) -> createAimPosition(block1, block2) }

        return findBestCombination(greenAim, remainingAimPositions)
    }

    private fun findBestCombination(greenAim: AimPosition, aimPositions: List<AimPosition>): List<AimPosition> {
        val result = mutableListOf(greenAim)
        val covered = greenAim.coveredBlocks.toMutableSet()

        repeat(2) {
            val best = aimPositions.filterNot { it in result }
                .maxWithOrNull(compareBy(
                    { it.coveredBlocks.count { block -> block !in covered } }, // New unmarked blocks
                    { it.coveredBlocks.size }, // Total blocks covered
                    { -(result.last().position.distanceTo(it.position)) } // Closer to last position
                )) ?: return@repeat

            result.add(best)
            covered.addAll(best.coveredBlocks)
        }

        return result.mapIndexed { index, aimPos ->
            AimPosition(aimPos.position, aimPos.coveredBlocks, if (index == 0) 0.0 else aimPos.position.distanceTo(result[index - 1].position))
        }
    }

    private fun BlockPos.midpoint(other: BlockPos): Vec3 =
        Vec3((x + other.x) / 2.0 + 0.5, y.toDouble() + 0.5, z.toDouble() + 0.5)

    private val devicePositions = listOf(
        BlockPos(68, 130, 50), BlockPos(66, 130, 50), BlockPos(64, 130, 50),
        BlockPos(68, 128, 50), BlockPos(66, 128, 50), BlockPos(64, 128, 50),
        BlockPos(68, 126, 50), BlockPos(66, 126, 50), BlockPos(64, 126, 50)
    )
}