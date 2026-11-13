package com.engineerclient.skyblock

import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.lore
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object ArrowFix : Module(
    name = "Arrow Fix",
    category = Category.SKYBLOCK,
    description = "No bow pullback on shortbows.",
    key = null,
) {
    private val known = HashMap<String, Boolean>()

    fun clears(item: ItemStack?): Boolean {
        if (!enabled || item == null || item.isEmpty || !item.`is`(Items.BOW)) return false
        val id = item.itemId
        known[id]?.let { return it }
        return item.lore.any { "Shortbow: Instantly shoots!" in it.string }.also { known[id] = it }
    }
}
