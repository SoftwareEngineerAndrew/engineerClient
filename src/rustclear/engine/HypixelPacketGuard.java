package net.rustclear.mod.engine;

/**
 * The Hypixel Mod API only ever reads the packets Hypixel sends and only ever writes the ones it
 * sends back, so in singleplayer, where both ends run in this game, any of its packets crossing the
 * connection fails one way or the other. Once the Rustclear world says hello (HypixelSignals), it
 * starts asking for player and party info. Those failures drop the one packet instead of the player.
 */
public final class HypixelPacketGuard {
    private HypixelPacketGuard() {
    }

    /** True when [t] or one of its causes was thrown from Hypixel Mod API code. */
    public static boolean fromHypixel(Throwable t) {
        for (int depth = 0; t != null && depth < 16; t = t.getCause(), depth++) {
            for (StackTraceElement frame : t.getStackTrace()) {
                if (frame.getClassName().startsWith("net.hypixel.")) {
                    return true;
                }
            }
        }
        return false;
    }
}
