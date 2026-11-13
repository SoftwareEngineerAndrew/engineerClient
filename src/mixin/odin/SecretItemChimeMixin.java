package com.engineerclient.mixin.odin;

import com.engineerclient.misc.OdinInstantChime;
import com.odtheking.odin.events.SecretPickupEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.SecretClicked$3", remap = false)
public class SecretItemChimeMixin {

    @Inject(method = "invoke(Lcom/odtheking/odin/events/SecretPickupEvent$Item;)V", at = @At(value = "INVOKE", target = "Lcom/odtheking/odin/features/impl/dungeon/SecretClicked;access$secretChime(Lcom/odtheking/odin/features/impl/dungeon/SecretClicked;)V", remap = false), cancellable = true, remap = false)
    private void ec$instantChime(SecretPickupEvent.Item event, CallbackInfo ci) {
        if (OdinInstantChime.alreadyChimed(event.getEntity())) ci.cancel();
    }
}
