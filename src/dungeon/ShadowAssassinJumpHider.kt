package com.engineerclient.dungeon

import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module

object ShadowAssassinJumpHider : Module(
    name = "Shadow Assassin Jump Hider",
    category = Category.DUNGEON,
    description = "Lets you click blocks when a Shadow Assassin jump moves the world border onto you.",
    key = null,
) {
    @JvmStatic
    fun ignoresBorder(): Boolean = enabled
}
