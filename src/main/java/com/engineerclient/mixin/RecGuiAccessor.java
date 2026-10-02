package com.engineerclient.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What the HUD is showing right now (title, subtitle, action bar and their timers), for the Dungeon
 * Recorder's keyframes (HudCapture). The changes themselves come from GuiTitleTapMixin.
 */
@Mixin(Gui.class)
public interface RecGuiAccessor {

    @Accessor("title")
    Component ec$recTitle();

    @Accessor("subtitle")
    Component ec$recSubtitle();

    @Accessor("titleTime")
    int ec$recTitleTime();

    @Accessor("titleFadeInTime")
    int ec$recTitleFadeIn();

    @Accessor("titleStayTime")
    int ec$recTitleStay();

    @Accessor("titleFadeOutTime")
    int ec$recTitleFadeOut();

    @Accessor("overlayMessageString")
    Component ec$recOverlayMessage();

    @Accessor("overlayMessageTime")
    int ec$recOverlayMessageTime();

    @Accessor("animateOverlayMessageColor")
    boolean ec$recAnimateOverlay();
}
