package com.engineerclient.render

import com.adm.clickgui.settings.impl.ActionSetting
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.LevelEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.itemId
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.core.component.DataComponents
import net.minecraft.tags.ItemTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffectUtil
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.SwingAnimation
import org.joml.Quaternionf
import kotlin.math.pow

object ItemAnimations : Module(
    name = "Item Animations",
    category = Category.RENDER,
    description = "Moves, turns and resizes the held item and changes its swing, swap and blocking.",
    key = null,
) {
    private val reset by ActionSetting("Reset Values", desc = "Puts the sliders back to default.") { resetSliders() }
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
    private var swingX by NumberSetting("Swing X", 1.0, -2.0..2.0, 0.05, desc = "Scales how far the swing moves the item sideways.")
    private var swingY by NumberSetting("Swing Y", 1.0, -2.0..2.0, 0.05, desc = "Scales how far the swing moves the item up.")
    private var swingZ by NumberSetting("Swing Z", 1.0, -2.0..2.0, 0.05, desc = "Scales how far the swing moves the item forward.")
    private val ignoreEffects by BooleanSetting("Ignore Effects", false, desc = "Haste, mining fatigue and conduit power don't change the swing.")
    private val inPlaceSwing by BooleanSetting("In Place Swing", false, desc = "The swing only turns the item.")
    private val disableSwing by BooleanSetting("Disable Swing", false, desc = "No swing animation.")
    private val disableTermSwing by BooleanSetting("Disable Terminator Swing", false, desc = "No swing animation while holding a Terminator.")
    private val noHandSway by BooleanSetting("No Hand Sway", false, desc = "The item doesn't lag behind when you turn.")
    private val noItemReset by BooleanSetting("No Item Reset On Swap", false, desc = "A new held item appears at once instead of lowering and raising.")
    private val oldBlocking by BooleanSetting("Old Blocking", false, desc = "Holding right click with a sword shows 1.8.9's blocking pose.")

    private val snapshot = DoubleArray(8) { Double.NaN }
    private val heldTurn = Quaternionf()
    private var turnsHeld = false
    private var shiftX = 0f
    private var shiftY = 0f
    private var shiftZ = 0f
    private var shiftsHeld = false
    private var sizeFactor = 1f
    private var swingRate = 1.0

    private var moveHeld = false
    private var resizeItem = false
    private var resizeHand = false
    private var resizeMap = false
    private var swayAllowed = true
    private var swingStill = false
    private var blockingNow = false

    private var swingAge = 0
    private var swinging = false
    private var phase = 0f
    private var lastPhase = 0f

    private var checkedStack: ItemStack? = null
    private var checkedTerminator = false

    private val blockTurnRight = Quaternionf().rotationXYZ(rad(-102.25), rad(13.365), rad(78.05))
    private val blockTurnLeft = Quaternionf().rotationXYZ(rad(-102.25), rad(-13.365), rad(-78.05))
    private const val BLOCK_SHIFT = 0.14142136f

    init {
        on<LevelEvent.Load> {
            swinging = false
            swingAge = 0
            checkedStack = null
        }
    }

    private fun rad(degrees: Double) = Math.toRadians(degrees).toFloat()

    private fun resetSliders() {
        xOffset = 0.0; yOffset = 0.0; zOffset = 0.0
        yaw = 0.0; pitch = 0.0; roll = 0.0
        size = 0.0; swingSpeed = 0.0
        swingX = 1.0; swingY = 1.0; swingZ = 1.0
    }

    private fun syncSliders() {
        val p = pitch; val yw = yaw; val r = roll
        val x = xOffset; val y = yOffset; val z = zOffset
        val s = size; val sp = swingSpeed
        val seen = snapshot
        if (p == seen[0] && yw == seen[1] && r == seen[2] && x == seen[3] && y == seen[4] && z == seen[5] && s == seen[6] && sp == seen[7]) return
        seen[0] = p; seen[1] = yw; seen[2] = r; seen[3] = x; seen[4] = y; seen[5] = z; seen[6] = s; seen[7] = sp
        turnsHeld = p != 0.0 || yw != 0.0 || r != 0.0
        heldTurn.rotationXYZ(rad(p), rad(yw), rad(r))
        shiftX = x.toFloat(); shiftY = y.toFloat(); shiftZ = z.toFloat()
        shiftsHeld = x != 0.0 || y != 0.0 || z != 0.0
        sizeFactor = 2.0.pow(s).toFloat()
        swingRate = 2.0.pow(sp)
    }

    private fun holdingTerminator(stack: ItemStack): Boolean {
        if (stack !== checkedStack) {
            checkedStack = stack
            checkedTerminator = !stack.isEmpty && stack.itemId == "TERMINATOR"
        }
        return checkedTerminator
    }

    private fun swingHidden(held: ItemStack?): Boolean =
        disableSwing || (disableTermSwing && held != null && holdingTerminator(held))

    // Mirrors LivingEntity.getModifiedSwingDuration for the default swing.
    private fun swingLength(): Int {
        val base = SwingAnimation.DEFAULT.duration()
        if (ignoreEffects) return base
        val player = mc.player ?: return base
        if (MobEffectUtil.hasDigSpeed(player)) return base - (1 + MobEffectUtil.getDigSpeedAmplification(player))
        val fatigue = player.getEffect(MobEffects.MINING_FATIGUE) ?: return base
        return base + (1 + fatigue.amplifier) * 2
    }

    @JvmStatic
    fun swingStarted() {
        if (!enabled) return
        syncSliders()
        if (swinging && swingAge >= 0 && swingAge * swingRate < swingLength() / 2) return
        swingAge = -1
        swinging = true
    }

    @JvmStatic
    fun tick() {
        if (!enabled) return
        syncSliders()
        lastPhase = phase
        val length = swingLength()
        if (!swinging) {
            swingAge = 0
        } else if (++swingAge * swingRate >= length) {
            swingAge = 0
            swinging = false
        }
        phase = (swingAge * swingRate / length).toFloat()
    }

    @JvmStatic
    fun beginFrame(vanillaSwing: Float, partialTicks: Float): Float {
        if (!enabled) {
            moveHeld = false; resizeItem = false; resizeHand = false; resizeMap = false
            swayAllowed = true; swingStill = false; blockingNow = false
            return vanillaSwing
        }
        syncSliders()
        val player = mc.player
        val held = player?.mainHandItem
        moveHeld = held != null && (changeEmptyHand || !held.isEmpty) && (changeMap || !held.has(DataComponents.MAP_ID)) && (turnsHeld || shiftsHeld)
        resizeItem = sizeFactor != 1f
        resizeHand = resizeItem && changeEmptyHand
        resizeMap = resizeItem && changeMap
        swayAllowed = !noHandSway
        swingStill = inPlaceSwing
        blockingNow = oldBlocking && player != null && mc.gui.screen() == null && mc.options.keyUse.isDown && !player.isUsingItem
        if (swingHidden(held)) return 0f
        return Mth.lerp(partialTicks, lastPhase, if (phase < lastPhase) phase + 1f else phase)
    }

    @JvmStatic
    fun keepSway() = swayAllowed

    @JvmStatic
    fun placeHeld(pose: PoseStack): PoseStack {
        if (moveHeld) {
            if (turnsHeld) pose.rotate(heldTurn)
            if (shiftsHeld) pose.translate(shiftX, shiftY, shiftZ)
        }
        return pose
    }

    @JvmStatic
    fun sizeItem(pose: PoseStack): PoseStack {
        if (resizeItem) pose.scale(sizeFactor, sizeFactor, sizeFactor)
        return pose
    }

    @JvmStatic
    fun sizeHand(pose: PoseStack): PoseStack {
        if (resizeHand) pose.scale(sizeFactor, sizeFactor, sizeFactor)
        return pose
    }

    @JvmStatic
    fun sizeMapHand(pose: PoseStack): PoseStack {
        if (resizeMap) pose.scale(sizeFactor, sizeFactor, sizeFactor)
        return pose
    }

    @JvmStatic
    fun sizeMap(pose: PoseStack): PoseStack {
        if (resizeMap) {
            pose.translate(64f, 64f, 0f)
            pose.scale(sizeFactor, sizeFactor, sizeFactor)
            pose.translate(-64f, -64f, 0f)
        }
        return pose
    }

    @JvmStatic
    fun staticSwing() = swingStill

    @JvmStatic fun swingX() = if (enabled) swingX.toFloat() else 1f
    @JvmStatic fun swingY() = if (enabled) swingY.toFloat() else 1f
    @JvmStatic fun swingZ() = if (enabled) swingZ.toFloat() else 1f

    @JvmStatic
    fun blockPose(pose: PoseStack, arm: HumanoidArm, stack: ItemStack, hand: InteractionHand): Boolean {
        if (!blockingNow || hand != InteractionHand.MAIN_HAND || !stack.`is`(ItemTags.SWORDS)) return false
        val right = arm == HumanoidArm.RIGHT
        pose.translate(if (right) -BLOCK_SHIFT else BLOCK_SHIFT, 0.08f, BLOCK_SHIFT)
        pose.rotate(if (right) blockTurnRight else blockTurnLeft)
        return true
    }

    @JvmStatic
    fun keepsHeight(): Boolean {
        if (!enabled) return false
        return noItemReset || inPlaceSwing || swingHidden(mc.player?.mainHandItem)
    }

    @JvmStatic
    fun instantSwap() = enabled && noItemReset
}
