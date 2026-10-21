package net.rustclear.mod.online;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.rustclear.mod.RustclearMod;

/**
 * Installs e4mc (e4all alongside Krypton: E4mcPreLaunch.relayProject) from inside the game, when the player asks to (the Rustclear menu's Install e4mc,
 * for those who chose "Not now" at start-up). The file lands in the mods folder now and loads from
 * the next start; see E4mcDownload for where it comes from and E4mcPreLaunch for the start-up prompt.
 */
public final class E4mcInstaller {
    public enum State { IDLE, INSTALLING, INSTALLED, FAILED }

    private static volatile State state = State.IDLE;
    private static volatile String message;

    private E4mcInstaller() {
    }

    /** Whether e4mc is missing from this game (installed and waiting for a restart counts as not missing). */
    public static boolean needed() {
        return !E4mcPreLaunch.e4mcLoaded() && state != State.INSTALLED;
    }

    public static State state() {
        return state;
    }

    /** What happened, for the menu: the file installed, or why it couldn't be. */
    public static String message() {
        return message;
    }

    /** Downloads and installs e4mc in the background. */
    public static void install() {
        if (state == State.INSTALLING) {
            return;
        }
        state = State.INSTALLING;
        message = null;
        String project = E4mcPreLaunch.relayProject();
        CompletableFuture.supplyAsync(() -> E4mcDownload.install(project, E4mcPreLaunch.minecraftVersion(),
                FabricLoader.getInstance().getGameDir().resolve("mods"), RustclearMod.version(), null))
            .whenComplete((file, error) -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    state = State.FAILED;
                    message = cause.getMessage() != null ? cause.getMessage() : "Couldn't install " + project + ".";
                    RustclearMod.LOGGER.warn("Couldn't install {}: {}", project, cause.toString());
                } else {
                    state = State.INSTALLED;
                    message = "Installed. Restart Minecraft to use it.";
                    RustclearMod.LOGGER.info("Installed {} as {}", project, file);
                }
            });
    }
}
