package com.devgineerclient.mixin;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The boss bars the client holds (what the HUD draws), for the Dungeon Recorder (HudCapture). */
@Mixin(BossHealthOverlay.class)
public interface RecBossOverlayAccessor {

    @Accessor("events")
    Map<UUID, LerpingBossEvent> dc$recEvents();
}
