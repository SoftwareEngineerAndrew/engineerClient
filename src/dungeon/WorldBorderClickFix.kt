package com.engineerclient.dungeon

import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module

object WorldBorderClickFix : Module(
    name = "World Border Click Fix",
    category = Category.DUNGEON,
    description = "Lets you click blocks when Hypixel moves the world border onto you.",
    key = null,
) {
    @JvmStatic
    fun ignoresBorder(): Boolean = enabled
}
