package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Drops the enchantment glint from item rendering for the No Enchant Glint setting in
 * {@link RandomStuff}.
 *
 * <p>These two {@code ItemModel} implementations are the only things in the game that call
 * {@code LayerRenderState.setFoilType}, and both reach it the same way: {@code stack.hasFoil()}
 * picks {@code SPECIAL}/{@code STANDARD}, and a false answer skips the call entirely, leaving the
 * layer on the {@code NONE} it was cleared to. So a redirect that answers false is exactly a
 * glint-less item, with no second render path to keep in step.
 *
 * <p>The redirect captures {@code update}'s own parameters, which is where the
 * {@link ItemDisplayContext} comes from — it is what separates an item in a GUI slot from one in
 * a hand or on the ground, and the glint settings let those be switched independently.
 *
 * <p>Only the drawing changes. {@code hasFoil()} still answers honestly everywhere else, so
 * Odin's terminal solvers — which read the glint component, not the screen — are untouched.
 *
 * <p>The identity element is not decoration. GUI items are not redrawn per frame: they are baked
 * into {@code GuiItemAtlas}, whose slots are keyed by the model identity {@code update} builds,
 * and the foil type is not part of that key. Without a marker, one cached image would serve both
 * answers — so an item first drawn glint-less would stay glint-less when a terminal opened and
 * {@code Glint: Keep In Terminals} asked for it back. Appending on the hidden branch alone gives the two
 * states separate slots and leaves the vanilla key untouched when the setting is off.
 */
@Mixin({CuboidItemModelWrapper.class, SpecialModelWrapper.class})
public class ItemFoilMixin {

    /** Cache key marker for "this layer was drawn without its glint"; see the class docs. */
    private static final String EC_GLINT_HIDDEN = "engineerclient:glint_hidden";

    @Redirect(
        method = "update",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;hasFoil()Z")
    )
    private boolean ec$noItemGlint(
        ItemStack foilStack,
        ItemStackRenderState renderState,
        ItemStack stack,
        ItemModelResolver resolver,
        ItemDisplayContext displayContext,
        ClientLevel level,
        ItemOwner owner,
        int seed
    ) {
        if (RandomStuff.INSTANCE.hidesGlint(displayContext)) {
            renderState.appendModelIdentityElement(EC_GLINT_HIDDEN);
            return false;
        }
        return foilStack.hasFoil();
    }
}
