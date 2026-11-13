package com.engineerclient.skyblock

import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.ChatFormatting
import net.minecraft.world.item.context.BlockPlaceContext

object NoItemPlace : Module(
    name = "No Item Place",
    category = Category.SKYBLOCK,
    description = "Stops head items like power orbs, tubas and relics from placing ghost blocks.",
    key = null,
) {
    private val exact = setOf(
        "BOUQUET_OF_LIES", "FLOWER_OF_TRUTH", "BAT_WAND", "STARRED_BAT_WAND", "INFINITE_SPIRIT_LEAP", "ROYAL_PIGEON",
        "ARROW_SWAPPER", "JINGLE_BELLS", "FIRE_FREEZE_STAFF", "UMBERELLA", "ETHERWARP_CONDUIT", "KUUDRA_SHOP_ITEM",
    )
    private val suffixes = listOf("_TUBA", "_POWER_ORB", "_POCKET_BLACK_HOLE", "_FISHING_NET")

    fun blocks(context: BlockPlaceContext): Boolean {
        if (!enabled) return false
        val item = context.player?.mainHandItem ?: return false
        val id = item.itemId
        if (id.startsWith("ABIPHONE") || suffixes.any { id.endsWith(it) } || id in exact) return true
        return DungeonUtils.getF7Phase() == M7Phases.P5 && ChatFormatting.stripFormatting(item.hoverName.string)?.endsWith(" Relic") == true
    }
}
