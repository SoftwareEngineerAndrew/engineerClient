package com.engineerclient.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Hud.class)
public interface HudItemNameAccessor {
    @Accessor("toolHighlightTimer")
    int ec$toolHighlightTimer();

    @Accessor("lastToolHighlight")
    ItemStack ec$lastToolHighlight();
}
