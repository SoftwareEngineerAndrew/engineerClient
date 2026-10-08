package com.devgineerclient.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What the HUD is showing right now (title, subtitle, action bar and their timers), for the Dungeon
 * Recorder's keyframes (HudCapture). The changes themselves come from GuiTitleTapMixin.
 */
@Mixin(Hud.class)
public interface RecGuiAccessor {

    @Accessor("title")
    Component dc$recTitle();

    @Accessor("subtitle")
    Component dc$recSubtitle();

    @Accessor("titleTime")
    int dc$recTitleTime();

    @Accessor("titleFadeInTime")
    int dc$recTitleFadeIn();

    @Accessor("titleStayTime")
    int dc$recTitleStay();

    @Accessor("titleFadeOutTime")
    int dc$recTitleFadeOut();

    @Accessor("overlayMessageString")
    Component dc$recOverlayMessage();

    @Accessor("overlayMessageTime")
    int dc$recOverlayMessageTime();

    @Accessor("animateOverlayMessageColor")
    boolean dc$recAnimateOverlay();
}
