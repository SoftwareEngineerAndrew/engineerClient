package com.engineerclient.render

import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.itemId
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.core.component.DataComponents
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemStack
import net.minecraft.world.effect.MobEffectUtil
import net.minecraft.world.effect.MobEffects
import org.joml.Matrix4f
import kotlin.math.pow

object ItemAnimations : Module(
    name = "Item Animations",
    category = Category.RENDER,
    description = "Moves, turns and resizes the held item and changes its swing, swap and blocking.",
    key = null,
) {
    private val reset by ActionSetting("Reset Values", desc = "Puts the sliders back to default.") { resetValues() }
    private var xOffset by NumberSetting("X Offset", 0.0, -1.0..1.0, 0.001, desc = "Moves the held item sideways.")
    private var yOffset by NumberSetting("Y Offset", 0.0, -1.0..1.0, 0.001, desc = "Moves the held item up or down.")
    private var zOffset by NumberSetting("Z Offset", 0.0, -1.0..1.0, 0.001, desc = "Moves the held item closer or further.")
    private var yaw by NumberSetting("Yaw", 0.0, -180.0..180.0, 0.0001, desc = "Turns the held item left or right, in degrees.")
    private var pitch by NumberSetting("Pitch", 0.0, -180.0..180.0, 0.0001, desc = "Tilts the held item up or down, in degrees.")
    private var roll by NumberSetting("Roll", 0.0, -180.0..180.0, 0.0001, desc = "Rolls the held item, in degrees.")
    private var size by NumberSetting("Item Size", 0.0, -4.0..4.0, 0.01, desc = "Each 1 doubles the size, each -1 halves it.")
    private val changeEmptyHand by BooleanSetting("Change Empty Hand", true, desc = "Also applies to an empty hand.")
    private val changeMap by BooleanSetting("Change Holding Map", true, desc = "Also applies while holding a map.")
    private var swingSpeed by NumberSetting("Swing Speed", 0.0, -4.0..4.0, 0.01, desc = "Each 1 doubles the swing speed, each -1 halves it.")
    private val ignoreEffects by BooleanSetting("Ignore Effects", false, desc = "Haste, mining fatigue and conduit power don't change the swing.")
    private val inPlaceSwing by BooleanSetting("In Place Swing", false, desc = "The swing only turns the item.")
    private val disableSwing by BooleanSetting("Disable Swing", false, desc = "No swing animation.")
    private val disableTermSwing by BooleanSetting("Disable Terminator Swing", false, desc = "No swing animation while holding a Terminator.")
    private val noHandSway by BooleanSetting("No Hand Sway", false, desc = "The item doesn't lag behind when you turn.")
    private val noItemReset by BooleanSetting("No Item Reset On Swap", false, desc = "A new held item appears at once instead of lowering and raising.")
    private val oldBlocking by BooleanSetting("Old Blocking", false, desc = "Holding right click with a sword shows 1.8.9's blocking pose.")

    private const val BASE_SWING_TICKS = 6

    private var swingTick = 0
    private var inSwing = false
    private var progress = 0f
    private var lastProgress = 0f

    init {
        on<LevelEvent.Load> { inSwing = false; swingTick = 0 }
    }

    private fun resetValues() {
        xOffset = 0.0; yOffset = 0.0; zOffset = 0.0
        yaw = 0.0; pitch = 0.0; roll = 0.0
        size = 0.0; swingSpeed = 0.0
    }

    @JvmStatic
    fun transformHeld(pose: PoseStack) {
        if (!enabled) return
        val held = mc.player?.mainHandItem ?: return
        if (held.isEmpty && !changeEmptyHand) return
        if (held.has(DataComponents.MAP_ID) && !changeMap) return
        pose.rotateDegrees(Axis.XP, pitch.toFloat())
        pose.rotateDegrees(Axis.YP, yaw.toFloat())
        pose.rotateDegrees(Axis.ZP, roll.toFloat())
        if (xOffset != 0.0 || yOffset != 0.0 || zOffset != 0.0) pose.translate(xOffset.toFloat(), yOffset.toFloat(), zOffset.toFloat())
    }

    @JvmStatic
    fun scaleItem(pose: PoseStack) {
        if (!enabled) return
        val s = 2.0.pow(size).toFloat()
        if (s != 1f) pose.scale(s, s, s)
    }

    @JvmStatic
    fun scaleHand(pose: PoseStack) {
        if (changeEmptyHand) scaleItem(pose)
    }

    @JvmStatic
    fun scaleMapHand(pose: PoseStack) {
        if (changeMap) scaleItem(pose)
    }

    @JvmStatic
    fun scaleMap(pose: PoseStack) {
        if (!enabled || !changeMap) return
        pose.translate(64f, 64f, 0f)
        scaleItem(pose)
        pose.translate(-64f, -64f, 0f)
    }

    @JvmStatic
    fun noSwingMove() = enabled && inPlaceSwing

    private fun noSwingTurn(): Boolean {
        if (!enabled) return false
        if (disableSwing) return true
        if (!disableTermSwing) return false
        val held = mc.player?.mainHandItem ?: return false
        return !held.isEmpty && held.itemId == "TERMINATOR"
    }

    @JvmStatic
    fun holdsHeight() = noSwingMove() || noSwingTurn()

    @JvmStatic
    fun noHandSway() = enabled && noHandSway

    private fun swingTicks(): Int {
        val player = mc.player
        if (ignoreEffects || player == null) return BASE_SWING_TICKS
        if (MobEffectUtil.hasDigSpeed(player)) return BASE_SWING_TICKS - 1 - MobEffectUtil.getDigSpeedAmplification(player)
        val fatigue = player.getEffect(MobEffects.MINING_FATIGUE) ?: return BASE_SWING_TICKS
        return BASE_SWING_TICKS + 2 * (fatigue.amplifier + 1)
    }

    private fun elapsed() = swingTick * 2.0.pow(swingSpeed)

    @JvmStatic
    fun swingStarted() {
        if (!enabled) return
        if (inSwing && swingTick >= 0 && elapsed() < swingTicks() / 2) return
        swingTick = -1
        inSwing = true
    }

    @JvmStatic
    fun swingTick() {
        if (!enabled) return
        lastProgress = progress
        val length = swingTicks()
        if (!inSwing) swingTick = 0
        else if (++swingTick * 2.0.pow(swingSpeed) >= length) {
            swingTick = 0
            inSwing = false
        }
        progress = (elapsed() / length).toFloat()
    }

    @JvmStatic
    fun swing(vanilla: Float, partialTicks: Float): Float {
        if (!enabled) return vanilla
        if (noSwingTurn()) return 0f
        val step = progress - lastProgress
        return lastProgress + (if (step < 0f) step + 1f else step) * partialTicks
    }

    @JvmStatic
    fun noItemReset() = enabled && noItemReset

    @JvmStatic
    fun blocking(stack: ItemStack, hand: InteractionHand): Boolean {
        if (!enabled || !oldBlocking || hand != InteractionHand.MAIN_HAND || stack.isEmpty) return false
        val player = mc.player ?: return false
        if (mc.gui.screen() != null || !mc.options.keyUse.isDown || player.isUsingItem) return false
        return stack.`is`(ItemTags.SWORDS)
    }

    @JvmStatic
    fun blockPose(arm: HumanoidArm): Matrix4f = if (arm == HumanoidArm.RIGHT) BLOCK_RIGHT else BLOCK_LEFT

    private fun rad(d: Float) = Math.toRadians(d.toDouble()).toFloat()

    // 1.8.9's chain after the arm transform: transformFirstPersonItem, doBlockTransformations, the old sword display,
    // then today's handheld display undone since it is applied after this.
    private val BLOCK_RIGHT: Matrix4f = Matrix4f()
        .rotateY(rad(45f)).scale(0.4f)
        .translate(-0.5f, 0.2f, 0f).rotateY(rad(30f)).rotateX(rad(-80f)).rotateY(rad(60f))
        .translate(0f, 4f / 16f, 2f / 16f).rotateY(rad(-135f)).rotateZ(rad(25f)).scale(1.7f)
        .mul(Matrix4f().translate(1.13f / 16f, 3.2f / 16f, 1.13f / 16f).rotateY(rad(-90f)).rotateZ(rad(25f)).scale(0.68f).invert())

    private val BLOCK_LEFT: Matrix4f = Matrix4f().scale(-1f, 1f, 1f).mul(BLOCK_RIGHT).scale(-1f, 1f, 1f)
}
