package com.engineerclient.mixin;

import com.engineerclient.render.NoGlint;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The worn-armour half of {@link NoGlint}.
 *
 * <p>Armour on a body never becomes an {@code ItemStackRenderState} — it is drawn straight from
 * the equipment models, and its glint is a {@code RenderTypes.armorEntityGlint()} pass gated on
 * the same {@code hasFoil()} question. Same redirect, different renderer; the shorter
 * {@code renderLayers} overload delegates to this one, so a single injection covers both.
 */
@Mixin(EquipmentLayerRenderer.class)
public class ArmorFoilMixin {

    @Redirect(
        method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;hasFoil()Z")
    )
    private boolean ec$noArmorGlint(ItemStack stack) {
        if (NoGlint.INSTANCE.hidesArmorGlint()) return false;
        return stack.hasFoil();
    }
}
