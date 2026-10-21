package net.rustclear.mod.online;

import java.util.concurrent.CompletableFuture;

/**
 * Shares the host's open Rustclear world through a relay, so guests connect to a relay's address
 * and no player ever learns another's IP. Implementations must never read, show, send or log a
 * raw IP address: the only address that leaves the host's game is the relay's join address.
 */
public interface RelayProvider {
    /** A short name for the screens ("e4mc", "Tunnel"). */
    String name();

    /** Whether this relay can be used in this game (its mod is installed, say). */
    boolean isAvailable();

    /**
     * Starts sharing the world this game is hosting. Completes with the join address guests use
     * (never an IP), or fails with a message to show the host. Call on the client thread.
     */
    CompletableFuture<String> startSharing();

    /** Stops sharing; guests already connected stay until the world closes. */
    void stopSharing();
}
