package com.engineerclient.leap;

import com.odtheking.odin.features.impl.dungeon.LeapMenu;
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Odin's {@code LeapMenu.mouseTrigger}: the leap itself (refusing the empty placeholder and dead
 * players, then clicking that player's head in the Spirit Leap chest). It is public on the JVM but
 * internal to Odin's Kotlin, so Kotlin outside Odin can't name it; Java can.
 */
public final class OdinLeap {
    private OdinLeap() {}

    /** [index] is the player's place in Odin's leap teammates; it only bounds-checks it. */
    public static void leap(AbstractContainerScreen<?> screen, DungeonPlayer player, int index) {
        LeapMenu.INSTANCE.mouseTrigger(screen, player, index);
    }
}
