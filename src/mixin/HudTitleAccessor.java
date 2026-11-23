package com.engineerclient.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Hud.class)
public interface HudTitleAccessor {
    @Accessor("title")
    void ec$setTitle(Component title);

    @Accessor("subtitle")
    void ec$setSubtitle(Component subtitle);

    @Accessor("titleFadeInTime")
    void ec$setFadeIn(int ticks);

    @Accessor("titleStayTime")
    void ec$setStay(int ticks);

    @Accessor("titleFadeOutTime")
    void ec$setFadeOut(int ticks);

    @Accessor("titleTime")
    void ec$setTitleTime(int ticks);
}
