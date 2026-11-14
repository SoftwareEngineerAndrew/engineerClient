package com.engineerclient.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ChatComponent.class)
public interface ChatCopyAccessor {
    @Accessor("trimmedMessages")
    List<GuiMessage.Line> ec$lines();

    @Accessor("chatScrollbarPos")
    int ec$scroll();

    @Invoker("getWidth")
    int ec$width();

    @Invoker("getScale")
    double ec$scale();
}
