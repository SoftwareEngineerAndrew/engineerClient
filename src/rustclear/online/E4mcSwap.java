package net.rustclear.mod.online;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Finishes replacing e4mc with e4all once Minecraft has closed, for when the running game held
 * e4mc's jar locked (Windows) and E4mcDownload left e4all as "name.pending". A process of its own
 * (E4mcPreLaunch starts it): waits for the game, deletes e4mc, then puts e4all in place, so the two
 * are never in the mods folder together. Plain Java only.
 *
 * Arguments: the game's process id, e4mc's jar, e4all's pending jar.
 */
public final class E4mcSwap {
    private E4mcSwap() {
    }

    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path replace = Path.of(args[1]);
        Path pending = Path.of(args[2]);
        Path target = pending.resolveSibling(pending.getFileName().toString().replace(E4mcDownload.PENDING, ""));
        try {
            ProcessHandle.of(pid).ifPresent(game -> game.onExit().orTimeout(10, TimeUnit.MINUTES).join());
        } catch (CompletionException stillRunning) {
            // The game never closed: leave both files; the start-up prompt offers the swap again.
            return;
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                Files.deleteIfExists(replace);
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (Exception stillLocked) {
                Thread.sleep(500);
            }
        }
    }
}
