package com.coffeeclient.misc

import com.engineerclient.enumSelector
import com.coffeeclient.CoffeeClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.SecretClicked
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks

/**
 * Termsim (the name only - nothing to do with terminals): where new things go, each its own
 * toggle with its settings under it.
 *
 *  - Lever Clicks: left clicking a lever in a dungeon gets Odin's Secret Clicked chime and box, as
 *    a right click does - its own (private) chime and box, so its settings and its on/off apply.
 */
object Termsim : Module(
    name = "Termsim",
    key = null,
    category = Category.custom("Coffee Client"),
    description = "Where new things go: Lever Clicks.",
) {
    private val leverClicks by BooleanSetting("Lever Clicks", true, desc = "Left clicking a lever in a dungeon plays Odin's Secret Clicked chime and draws its box, the same as right clicking it. Uses Secret Clicked's settings (and needs it on).")

    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    /** On by default, once (turning it off sticks). */
    fun enableByDefault() {
        if (switchedOn) return
        switchedOn = true
        if (!enabled) toggle()
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }

    // --- Lever Clicks ------------------------------------------------------------------------------

    private val secretBox by lazy { SecretClicked::class.java.getDeclaredMethod("secretBox", BlockPos::class.java).apply { isAccessible = true } }
    private val secretChime by lazy { SecretClicked::class.java.getDeclaredMethod("secretChime").apply { isAccessible = true } }

    /** A left click on [pos] (LeverClickMixin), before the game handles it. Client thread. */
    @JvmStatic
    fun onLeftClickBlock(pos: BlockPos) {
        if (!enabled || !leverClicks || !SecretClicked.enabled || !DungeonUtils.inDungeons) return
        if (CoffeeClient.mc.level?.getBlockState(pos)?.`is`(Blocks.LEVER) != true) return
        CoffeeClient.safely("lever click") {
            secretBox.invoke(SecretClicked, pos)
            secretChime.invoke(SecretClicked)
        }
    }
}
