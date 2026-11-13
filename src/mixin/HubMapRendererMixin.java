package com.engineerclient.mixin;

import com.engineerclient.visual.HubMap;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MapRenderer.class)
public abstract class HubMapRendererMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void ec$hubMap(MapId mapId, MapItemSavedData data, MapRenderState state, CallbackInfo ci) {
        if (HubMap.applyRenderState(mapId, state)) ci.cancel();
    }
}
