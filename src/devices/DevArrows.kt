package com.engineerclient.devices

import com.devgineerclient.practice.I4Aims
import com.engineerclient.EngineerClient.mc
import com.adm.events.*
import com.adm.events.core.on
import com.adm.utils.alert
import com.adm.utils.modMessage
import com.adm.utils.render.BoxBatch
import com.adm.utils.render.drawBoxes
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** The Arrows Device (i4): blocks already hit and the one to hit, and where to aim (Terminator cubes). */
object DevArrows {
    fun reset() {
        markedPositions.fill(false)
        targetPosition = -1
        optimalAimPositions = 0
    }

    /** A new attempt (the P3 Sim's restore): as reset, and not done. */
    fun restart() { reset(); isDeviceComplete = false }

    // Nine targets on the wall at z 50: x 68/66/64 by y 130/128/126, numbered row by row from the top left.
    private const val TARGETS = 9
    private const val PAIRS = 6

    private val deviceCompleteRegex = Regex("^(.{1,16}) completed a device! \\((\\d)/(\\d)\\)$")
    private val roomBoundingBox = AABB(20.0, 100.0, 30.0, 89.0, 151.0, 51.0)

    // The dev recorder reads these four by name.
    private val markedPositions = BooleanArray(TARGETS)
    private var targetPosition = -1
    private var isDeviceComplete = false
    private var optimalAimPositions = 0

    private val targetBox = Array(TARGETS) { AABB(BlockPos(68 - it % 3 * 2, 130 - it / 3 * 2, 50)) }

    // A pair is two side by side targets one Terminator shot between them can hit.
    private fun pairLeft(p: Int) = p / 2 * 3 + p % 2
    private val pairMid = Array(PAIRS) { Vec3(68 - pairLeft(it) % 3 * 2 - 1 + 0.5, 130 - pairLeft(it) / 3 * 2 + 0.5, 50.5) }
    private val pairCube = Array(PAIRS) { AABB.unitCubeFromLowerCorner(pairMid[it].add(-0.5, -0.5, -0.1)) }

    private val aims = IntArray(3)
    private val boxes = BoxBatch()

    private val showAims get() = Devices.showAimPositions && !I4Aims.replacesOdinAims()

    private val inP3 get() = Devices.arrowsOn && DungeonUtils.getF7Phase() == M7Phases.P3

    private val isPlayerInRoom: Boolean
        get() = mc.player?.let { roomBoundingBox.contains(it.position()) } == true

    fun install() {
        on<BlockUpdateEvent> {
            val i = targetAt(pos)
            if (i < 0 || !inP3) return@on
            val blue = Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE)
            if (old.block == Blocks.EMERALD_BLOCK && updated.block == blue) {
                markedPositions[i] = true
                if (targetPosition == i) targetPosition = -1
                if (showAims) planAims(i)
            } else if (old.block == blue && updated.block == Blocks.EMERALD_BLOCK) {
                markedPositions[i] = false
                targetPosition = i
                if (showAims) planAims(i)
            }
        }

        on<RenderExtractEvent> {
            if (!inP3) return@on
            boxes.clear()
            val depth = Devices.depthCheck
            for (i in 0 until TARGETS) if (markedPositions[i]) boxes.filled(targetBox[i], Devices.markedColor, depth)
            if (targetPosition >= 0) boxes.filled(targetBox[targetPosition], Devices.targetColor, depth)
            if (showAims) for (k in 0 until optimalAimPositions) {
                val color = when (k) { 0 -> Devices.firstAimColor; 1 -> Devices.secondAimColor; else -> Devices.thirdAimColor }
                boxes.filled(pairCube[aims[k]], color, true)
            }
            drawBoxes(boxes)
        }

        on<LevelEvent.Load> { restart() }

        on<MessageEvent.Chat> {
            if (isDeviceComplete || !message.contains(" completed a device! (") || !inP3 || !isPlayerInRoom) return@on
            if (deviceCompleteRegex.find(message)?.groupValues?.get(1) == mc.player?.name?.string) onComplete("Chat")
        }

        on<EntityEvent.SetData> {
            val stand = entity as? ArmorStand ?: return@on
            if (isDeviceComplete || !inP3 || !isPlayerInRoom) return@on
            if (stand.customName?.string == "Active") onComplete("Entity")
        }
    }

    private fun onComplete(method: String) {
        isDeviceComplete = true

        if (Devices.completeAlert) {
            modMessage("§aSharp shooter device complete §7($method)")
            alert("§aDevice Complete")
        }
        reset()
    }

    private fun targetAt(pos: BlockPos): Int {
        val dx = 68 - pos.x
        val dy = 130 - pos.y
        if (pos.z != 50 || dx !in 0..4 || dy !in 0..4 || dx % 2 != 0 || dy % 2 != 0) return -1
        return dy / 2 * 3 + dx / 2
    }

    /** Bits of the pair's targets not hit yet. */
    private fun open(p: Int): Int {
        val left = pairLeft(p)
        return (if (markedPositions[left]) 0 else 1 shl left) or (if (markedPositions[left + 1]) 0 else 1 shl (left + 1))
    }

    private fun touches(p: Int, target: Int) = pairLeft(p) == target || pairLeft(p) + 1 == target

    /** First the pair through [target] that hits most, then twice the pair adding most new hits (ties: more hits, then nearer the last aim). */
    private fun planAims(target: Int) {
        optimalAimPositions = 0
        var first = -1
        for (p in 0 until PAIRS) {
            if (!touches(p, target)) continue
            val n = Integer.bitCount(open(p))
            if (n > 0 && (first < 0 || n > Integer.bitCount(open(first)))) first = p
        }
        if (first < 0) return
        aims[0] = first
        optimalAimPositions = 1
        var covered = open(first)

        repeat(2) {
            val last = pairMid[aims[optimalAimPositions - 1]]
            var best = -1
            var bestNew = 0
            var bestAll = 0
            var bestDist = 0.0
            for (p in 0 until PAIRS) {
                if (touches(p, target) || chosen(p)) continue
                val bits = open(p)
                if (bits == 0) continue
                val new = Integer.bitCount(bits and covered.inv())
                val all = Integer.bitCount(bits)
                val dist = last.distanceTo(pairMid[p])
                if (best < 0 || new > bestNew || new == bestNew && (all > bestAll || all == bestAll && dist < bestDist)) {
                    best = p; bestNew = new; bestAll = all; bestDist = dist
                }
            }
            if (best < 0) return
            aims[optimalAimPositions++] = best
            covered = covered or open(best)
        }
    }

    private fun chosen(p: Int): Boolean {
        for (k in 0 until optimalAimPositions) if (aims[k] == p) return true
        return false
    }
}
