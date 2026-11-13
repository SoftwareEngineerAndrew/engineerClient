package com.engineerclient.render

import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module

object NoItemResetOnSwap : Module(
    name = "No Item Reset On Swap",
    category = Category.RENDER,
    description = "A new held item appears at once instead of lowering and raising.",
    key = null,
) {
    @JvmStatic
    fun active() = enabled
}
