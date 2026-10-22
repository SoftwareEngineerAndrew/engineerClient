package com.coffeeclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Coffee Client's entrypoint. {@link CoffeeClient} is Odin modules through and through, so it only
 * starts with Odin; without it this class is all Fabric loads, and none of Coffee's classes are.
 */
public final class CoffeeEntry implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (FabricLoader.getInstance().isModLoaded("odin")) CoffeeClient.INSTANCE.onInitializeClient();
    }
}
