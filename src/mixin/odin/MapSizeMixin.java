package com.engineerclient.mixin.odin;

import com.engineerclient.mapicons.MapSizes;
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonDoor;
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * {@link MapSizes} on Odin's Dungeon Map. Odin writes its sizes into the code as numbers: 16 a room (fillRoom, and
 * renderGuess's guess tile with its halves 8 and thirds 5), 8 a door across the 4 gap (renderDoors). The room ones
 * are swapped for Room Size; the doors are drawn by MapSizes instead whenever a size isn't Odin's. A room's check
 * mark and text are placed for a 16 room, so they're moved by half the difference: renderRoomText as a whole, and
 * renderIcon on its own when renderGuess calls it. Mangled names (inline-class IVec2 arguments) are matched by regex,
 * since Mixin rejects their '-'.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.map.MapRendererKt", remap = false)
public class MapSizeMixin {

    @Unique
    private static boolean ec$inRoomText;

    @ModifyConstant(method = "fillRoom", constant = @Constant(intValue = 16), remap = false, require = 0)
    private static int ec$roomSize(int value) {
        return MapSizes.room();
    }

    @ModifyConstant(method = "/^renderGuess-/", constant = @Constant(intValue = 16), remap = false, require = 0)
    private static int ec$guessSize(int value) {
        return MapSizes.room();
    }

    @ModifyConstant(method = "/^renderGuess-/", constant = @Constant(intValue = 8), remap = false, require = 0)
    private static int ec$guessHalf(int value) {
        return MapSizes.room() / 2;
    }

    @ModifyConstant(method = "/^renderGuess-/", constant = @Constant(intValue = 5), remap = false, require = 0)
    private static int ec$guessThird(int value) {
        return MapSizes.room() / 3;
    }

    /** The icon's 2 from the tile's corner (centring 12 on 16), moved to centre it on the room; renderRoomText moves its own. */
    @ModifyConstant(method = "/^renderIcon-/", constant = @Constant(intValue = 2), remap = false, require = 0)
    private static int ec$iconInset(int value) {
        return ec$inRoomText ? value : value + Math.round(MapSizes.shift());
    }

    @Inject(method = "renderRoomText", at = @At("HEAD"), remap = false, require = 0)
    private static void ec$roomTextStart(GuiGraphicsExtractor g, DungeonRoom room, CallbackInfo ci) {
        ec$inRoomText = true;
        g.pose().pushMatrix();
        float s = MapSizes.shift();
        g.pose().translate(s, s);
    }

    @Inject(method = "renderRoomText", at = @At("RETURN"), remap = false, require = 0)
    private static void ec$roomTextEnd(GuiGraphicsExtractor g, DungeonRoom room, CallbackInfo ci) {
        g.pose().popMatrix();
        ec$inRoomText = false;
    }

    @Inject(method = "renderDoors", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    @SuppressWarnings("unchecked")
    private static void ec$doors(GuiGraphicsExtractor g, Collection<?> doors, CallbackInfo ci) {
        if (MapSizes.isDefault()) return;
        MapSizes.drawDoors(g, (Collection<DungeonDoor>) doors);
        ci.cancel();
    }
}
