// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.devices

import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.settings.BooleanSetting
import com.engineerclient.ads.settings.KeybindSetting
import com.engineerclient.ads.settings.Settings
import com.engineerclient.ads.settings.withDependency
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * Arrow Align (F7's third device) in ADS's worlds, as Odin's Arrow Align: the clicks each arrow still needs over
 * its frame (green under 3, gold under 5, red after), worked out by matching the frames to the device's nine known
 * solutions, and optionally wrong clicks blocked (a frame already right).
 */
object AdsArrowAlign : Settings("Arrow Align", "devices-arrow-align.json") {
    private val enabledS = +BooleanSetting("Solver", true, desc = "Shows how many clicks each arrow needs, in the P3 Sim and Rustclear worlds.")
    private val blockWrongS = +BooleanSetting("Block Wrong Clicks", true, desc = "Clicks on frames that are already right don't go through. Holding the prevent key lets them.")
    private val preventKeyS = +KeybindSetting("Prevent Blocking", InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_LSHIFT), desc = "While held, wrong clicks aren't blocked, even if you aren't sneaking.").withDependency { blockWrongS.value }
    private val invertS = +BooleanSetting("Invert Key", false, desc = "Blocks wrong clicks only while the prevent key is held.").withDependency { blockWrongS.value }

    private val frameGridCorner = BlockPos(-2, 120, 75)
    private val centerBlock = BlockPos(0, 120, 77)

    private val recentClicks = mutableMapOf<Int, Long>()
    private val clicksRemaining = mutableMapOf<Int, Int>()
    private var rotations: List<Int>? = null
    private var solution: List<Int>? = null

    fun reset() { recentClicks.clear(); clicksRemaining.clear(); rotations = null; solution = null }

    fun tick() {
        if (!enabledS.value) return
        clicksRemaining.clear()
        if ((mc.player?.blockPosition()?.distSqr(centerBlock) ?: return) > 200) {
            rotations = null
            solution = null
            return
        }
        val current = frames().also { rotations = it }
        for (arr in possibleSolutions) {
            if (arr.indices.any { (arr[it] == -1 || current[it] == -1) && arr[it] != current[it] }) continue
            solution = arr
            for (i in arr.indices) clicksNeeded(current[i], arr[i]).takeIf { it != 0 }?.let { clicksRemaining[i] = it }
        }
    }

    /** A right click on [entity]: counted if it's one of the device's arrows; true blocks it (a wrong click). */
    fun onClick(entity: Entity): Boolean {
        if (!enabledS.value) return false
        if (entity !is ItemFrame || entity.item.item != Items.ARROW) return false
        val pos = entity.blockPosition()
        val index = (pos.y - frameGridCorner.y) + (pos.z - frameGridCorner.z) * 5
        val current = rotations ?: return false
        if (pos.x != frameGridCorner.x || index !in 0..24 || current[index] == -1) return false

        val block = blockWrongS.value && !(DeviceSolvers.isDown(preventKeyS.value) xor invertS.value)
        if (!clicksRemaining.containsKey(index) && block) return true

        recentClicks[index] = System.currentTimeMillis()
        val turned = current.toMutableList().apply { this[index] = (this[index] + 1) % 8 }
        rotations = turned
        val want = solution?.get(index) ?: return false
        if (clicksNeeded(turned[index], want) == 0) clicksRemaining.remove(index)
        return false
    }

    fun draw(ctx: LevelRenderContext) {
        if (!enabledS.value) return
        for ((index, needed) in clicksRemaining) {
            val code = when {
                needed == 0 -> continue
                needed < 3 -> 'a'
                needed < 5 -> '6'
                else -> 'c'
            }
            val c = Vec3.atCenterOf(framePos(index))
            DeviceRender.text(ctx, "§$code$needed", Vec3(c.x - 0.3, c.y + 0.1, c.z))
        }
    }

    /** Each frame's rotation (-1: no arrow there); a frame clicked in the last second keeps the rotation it was clicked to. */
    private fun frames(): List<Int> {
        val level = mc.level ?: return List(25) { -1 }
        val arrows = level.entitiesForRendering().filter { it is ItemFrame && it.item.item == Items.ARROW }.map { it as ItemFrame }
        if (arrows.isEmpty()) return List(25) { -1 }
        val now = System.currentTimeMillis()
        return (0..24).map { i ->
            val kept = rotations?.takeIf { recentClicks[i]?.let { now - it < 1000 } == true }?.get(i)
            kept ?: arrows.find { it.blockPosition() == framePos(i) }?.rotation ?: -1
        }
    }

    private fun framePos(index: Int): BlockPos = frameGridCorner.offset(0, index % 5, index / 5)

    private fun clicksNeeded(current: Int, target: Int): Int = (8 - current + target) % 8

    private val possibleSolutions = listOf(
        listOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, -1, -1, 7, 1),
        listOf(-1, -1, 7, 7, 5, -1, 7, 1, -1, 5, -1, -1, -1, -1, -1, -1, 7, 5, -1, 1, -1, -1, 7, 7, 1),
        listOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, -1, 7, 5, -1, -1, -1, -1, 5, -1, -1, -1, 3, 3),
        listOf(5, 3, 3, 3, -1, 5, -1, -1, -1, -1, 7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, -1),
        listOf(5, 3, 3, 3, 3, 5, -1, -1, -1, 1, 7, 7, -1, -1, 1, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
        listOf(7, 7, 7, 7, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
        listOf(-1, -1, -1, -1, -1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
        listOf(-1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, 7, 7, 7, 7, 1, -1, -1, -1, -1, -1),
        listOf(-1, -1, -1, -1, -1, -1, 1, -1, 1, -1, 7, 1, 7, 1, 3, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
    )
}
