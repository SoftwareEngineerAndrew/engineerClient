package com.devgineerclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Devgineer Client's entrypoint. {@link DevgineerClient} (the recorders, splits, rotation...) is
 * built on Odin, so it only starts with Odin; without it this class is all Fabric loads, and none
 * of Devgineer's classes are.
 */
public final class DevgineerEntry implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (FabricLoader.getInstance().isModLoaded("odin")) DevgineerClient.INSTANCE.onInitializeClient();
    }
}
