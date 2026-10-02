package com.engineerclient.mixin;

import java.util.Set;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The Dungeon Recorder's view of a container screen (ScreenCapture): where the window sits, the
 * slot under the mouse and a drag (quick craft) in progress. Separate from ContainerScreenAccessor
 * (Better PF's) with its own method names so the two can never clash.
 */
@Mixin(AbstractContainerScreen.class)
public interface RecContainerScreenAccessor {

    @Accessor("leftPos")
    int ec$recLeftPos();

    @Accessor("topPos")
    int ec$recTopPos();

    @Accessor("imageWidth")
    int ec$recImageWidth();

    @Accessor("imageHeight")
    int ec$recImageHeight();

    @Accessor("hoveredSlot")
    Slot ec$recHoveredSlot();

    @Accessor("isQuickCrafting")
    boolean ec$recIsQuickCrafting();

    @Accessor("quickCraftSlots")
    Set<Slot> ec$recQuickCraftSlots();
}
