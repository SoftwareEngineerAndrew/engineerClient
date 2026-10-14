package com.engineerclient.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where a container screen's window sits and how big it is (GUI pixels), for ADR: slot
 * positions and the mouse are recorded relative to its top-left so the viewer can redraw it.
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {

    @Accessor("leftPos")
    int adrLeftPos();

    @Accessor("topPos")
    int adrTopPos();

    @Accessor("imageWidth")
    int adrImageWidth();

    @Accessor("imageHeight")
    int adrImageHeight();
}
