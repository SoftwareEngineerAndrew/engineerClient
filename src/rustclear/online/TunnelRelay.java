package net.rustclear.mod.online;

import java.util.concurrent.CompletableFuture;

/**
 * Without e4mc: the world is opened to LAN, and the host runs a tunnel themselves (playit.gg and
 * the like) to its port, then pastes the tunnel's address into the Party screen
 * (Relays.useTunnelAddress). Friends on the same network can join over LAN meanwhile. The address
 * must be a host name; a raw IP is refused.
 */
final class TunnelRelay implements RelayProvider {
    @Override
    public String name() {
        return "Tunnel";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public CompletableFuture<String> startSharing() {
        int port = Relays.openToLan();
        return CompletableFuture.failedFuture(new IllegalStateException(port < 0
            ? "Couldn't open the world for sharing."
            : "Install e4mc to share automatically, or point a tunnel like playit.gg at port " + port
                + " and paste its address in Party > Invite."));
    }

    @Override
    public void stopSharing() {
    }
}
