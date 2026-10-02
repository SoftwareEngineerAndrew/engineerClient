package com.engineerclient.mixin;

import java.util.List;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The tab list as the game would draw it, for the Dungeon Recorder (HudCapture): the rows in their
 * drawn order (vanilla's comparator and 80-row cap), the header, the footer and whether it is open.
 */
@Mixin(PlayerTabOverlay.class)
public interface RecTabOverlayAccessor {

    @Invoker("getPlayerInfos")
    List<PlayerInfo> ec$recPlayerInfos();

    @Accessor("header")
    Component ec$recHeader();

    @Accessor("footer")
    Component ec$recFooter();

    @Accessor("visible")
    boolean ec$recVisible();
}
