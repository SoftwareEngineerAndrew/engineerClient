package com.engineerclient.skyblock

import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.client.gui.components.Button

object MoveDisconnectButton : Module(
    name = "Move Disconnect Button",
    category = Category.SKYBLOCK,
    description = "Moves Disconnect to the bottom of the Esc menu, away from your crosshair.",
    key = null,
) {
    @JvmStatic
    fun move(button: Button?, width: Int, height: Int) {
        if (!enabled || button == null || mc.hasSingleplayerServer()) return
        button.setPosition(width / 2 - button.width / 2, height - 40)
    }
}
