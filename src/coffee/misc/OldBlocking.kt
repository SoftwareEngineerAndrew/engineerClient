package com.coffeeclient.misc

import com.coffeeclient.CoffeeClient.mc
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.core.component.DataComponents
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4f

/**
 * Old Blocking: while you hold use with a sword, your first-person sword is drawn in 1.8.9's
 * blocking pose (OldBlockingMixin), without the swing, as 1.8.9 drew it. Drawing only: nothing
 * is sent, no input is taken or changed, and the item is used exactly as it would be anyway.
 *
 * The pose is 1.8.9's own chain, rebuilt on top of today's: after the arm transform (the same in
 * both, equip height included), 1.8.9 turned 45 degrees, scaled 0.4, applied doBlockTransformations
 * and then the sword model's first-person display; today's handheld display is applied after us,
 * so it is undone here. Mirrored for a left main arm.
 */
object OldBlocking : Module(
    name = "Old Blocking",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "Visual only: holding right click with a sword shows 1.8.9's sword blocking in first person. Changes nothing in game.",
) {
    private val hypixelSwords by BooleanSetting("SkyBlock Swords", true, desc = "Also SkyBlock items whose rarity line says SWORD (e.g. LEGENDARY DUNGEON SWORD), whatever item they are.")

    /** Whether [stack] in [hand] is drawn blocking right now. Render thread. */
    @JvmStatic
    fun blocking(stack: ItemStack, hand: InteractionHand): Boolean {
        if (!enabled || hand != InteractionHand.MAIN_HAND || stack.isEmpty) return false
        val player = mc.player ?: return false
        if (mc.gui.screen() != null || !mc.options.keyUse.isDown || player.isUsingItem) return false
        return isSword(stack)
    }

    private fun isSword(stack: ItemStack): Boolean {
        if (stack.`is`(ItemTags.SWORDS)) return true
        if (!hypixelSwords) return false
        val rarity = stack.get(DataComponents.LORE)?.lines()?.lastOrNull { it.string.isNotBlank() }?.string ?: return false
        return rarity.contains(" SWORD")
    }

    /** The 1.8.9 blocking pose as one matrix, to apply right after the arm transform. */
    @JvmStatic
    fun pose(arm: HumanoidArm): Matrix4f = if (arm == HumanoidArm.RIGHT) RIGHT else LEFT

    private fun rad(d: Float) = Math.toRadians(d.toDouble()).toFloat()

    private val RIGHT: Matrix4f = Matrix4f()
        // transformFirstPersonItem (after its arm translate): turn 45 degrees, scale 0.4.
        .rotateY(rad(45f)).scale(0.4f)
        // doBlockTransformations.
        .translate(-0.5f, 0.2f, 0f).rotateY(rad(30f)).rotateX(rad(-80f)).rotateY(rad(60f))
        // The 1.8.9 sword's "firstperson" display: translate, then rotate Y, X, Z, then scale.
        .translate(0f, 4f / 16f, 2f / 16f).rotateY(rad(-135f)).rotateZ(rad(25f)).scale(1.7f)
        // Undo today's "firstperson_righthand" handheld display, which is applied after this.
        .mul(Matrix4f().translate(1.13f / 16f, 3.2f / 16f, 1.13f / 16f).rotateY(rad(-90f)).rotateZ(rad(25f)).scale(0.68f).invert())

    private val LEFT: Matrix4f = Matrix4f().scale(-1f, 1f, 1f).mul(RIGHT).scale(-1f, 1f, 1f)
}
