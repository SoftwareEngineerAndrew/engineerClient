package com.engineerclient.ads

import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * Hypixel's SkyBlock resource pack (namespace hypixel_skyblock): item models and tooltip frames that only exist while
 * the game has that pack, which Hypixel's server sends. Items made in ADS's own worlds ask for its models and tooltip
 * styles only when it is loaded; without it a missing model is the purple and black square, so the item keeps its
 * plain Minecraft look instead.
 */
object HypixelPack {
    const val NAMESPACE = "hypixel_skyblock"

    /** Is [path] (under assets/hypixel_skyblock/) in the loaded resource packs? False if it can't be told. */
    private fun has(path: String): Boolean = runCatching {
        Minecraft.getInstance().resourceManager.getResource(Identifier.fromNamespaceAndPath(NAMESPACE, path)).isPresent
    }.getOrDefault(false)

    /** Sets [stack]'s model to hypixel_skyblock:item/[model] if the pack has it (its items/item/[model].json). */
    @JvmStatic
    fun model(stack: ItemStack, model: String) {
        if (has("items/item/$model.json")) stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(NAMESPACE, "item/$model"))
    }

    /** Sets [stack]'s tooltip style to hypixel_skyblock:[style] if the pack has its frame (gui sprites tooltip/[style]_frame). */
    @JvmStatic
    fun tooltip(stack: ItemStack, style: String) {
        if (has("textures/gui/sprites/tooltip/${style}_frame.png")) stack.set(DataComponents.TOOLTIP_STYLE, Identifier.fromNamespaceAndPath(NAMESPACE, style))
    }
}
