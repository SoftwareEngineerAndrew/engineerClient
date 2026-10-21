package net.rustclear.mod.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every dungeon's map is id 1024 (the id client mods look for), but map data is kept once for the
 * whole server. In a duel each team's dimension answers 1024 with its own run's map, so the vanilla
 * map tracker never shows a team the other team's dungeon. Everywhere else this does nothing.
 */
@Mixin(ServerLevel.class)
public class EngineMapMixin {
    @Inject(method = "getMapData", at = @At("HEAD"), cancellable = true)
    private void rustclear$teamMap(MapId id, CallbackInfoReturnable<MapItemSavedData> cir) {
        MapItemSavedData map = EngineServer.dungeonMap((ServerLevel) (Object) this, id);
        if (map != null) {
            cir.setReturnValue(map);
        }
    }
}
