package com.devgineerclient.mixin;

import java.util.Set;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The Dungeon Recorder's view of a container screen (ScreenCapture): where the window sits, the
 * slot under the mouse and a drag (quick craft) in progress.
 */
@Mixin(AbstractContainerScreen.class)
public interface RecContainerScreenAccessor {

    @Accessor("leftPos")
    int dc$recLeftPos();

    @Accessor("topPos")
    int dc$recTopPos();

    @Accessor("imageWidth")
    int dc$recImageWidth();

    @Accessor("imageHeight")
    int dc$recImageHeight();

    @Accessor("hoveredSlot")
    Slot dc$recHoveredSlot();

    @Accessor("isQuickCrafting")
    boolean dc$recIsQuickCrafting();

    @Accessor("quickCraftSlots")
    Set<Slot> dc$recQuickCraftSlots();
}
