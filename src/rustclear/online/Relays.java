package net.rustclear.mod.online;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;
import net.rustclear.mod.RustclearMod;

/**
 * Which relay shares the host's world, and the address it was shared at. e4mc (an optional mod)
 * when installed; otherwise the host opens to LAN and pastes a tunnel address (playit.gg and the
 * like) into the Party screen. Raw IP addresses are refused everywhere ([isRawIp]), so only relay
 * and tunnel host names ever leave this game.
 */
public final class Relays {
    /** How long e4mc gets to hand out a domain. */
    private static final long TIMEOUT_SECONDS = 30;
    /** Dotted IPv4, or anything with two colons (IPv6), optionally with a port. */
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$");
    private static final Pattern IPV6 = Pattern.compile("^\\[?[0-9a-fA-F:.]*:[0-9a-fA-F:.]*:[0-9a-fA-F:.]*]?(:\\d+)?$");
    private static final Pattern HOST = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:\\d{1,5})?$");

    private static final RelayProvider E4MC = new E4mcRelay();
    private static final RelayProvider TUNNEL = new TunnelRelay();
    private static RelayProvider active;

    private Relays() {
    }

    /** The relay this game would share through: e4mc if installed, else a pasted tunnel address. */
    public static RelayProvider provider() {
        return E4MC.isAvailable() ? E4MC : TUNNEL;
    }

    /** True when [address] is a bare IP (v4 or v6), which is never shown, stored or sent. */
    public static boolean isRawIp(String address) {
        String a = address.trim();
        return IPV4.matcher(a).matches() || IPV6.matcher(a).matches();
    }

    /** [address] cleaned for use as a join address, or null if it isn't a host name (IPs included). */
    public static String cleanAddress(String address) {
        if (address == null) {
            return null;
        }
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || a.length() > 253 || isRawIp(a) || !HOST.matcher(a).matches()) {
            return null;
        }
        return a;
    }

    /**
     * Starts sharing the world this game hosts. Completes with the join address; on success it is
     * also kept in RustclearMod.shareAddress for the Party screen and the lobby listing.
     */
    public static CompletableFuture<String> start() {
        RelayProvider provider = provider();
        active = provider;
        return provider.startSharing().orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((address, error) -> {
            if (error == null) {
                RustclearMod.shareAddress = address;
            }
        });
    }

    public static void stop() {
        if (active != null) {
            active.stopSharing();
            active = null;
        }
        RustclearMod.shareAddress = null;
    }

    /** The host's tunnel address from the Party screen; false (and nothing kept) if it isn't a host name. */
    public static boolean useTunnelAddress(String address) {
        String clean = cleanAddress(address);
        if (clean == null) {
            return false;
        }
        RustclearMod.shareAddress = clean;
        return true;
    }

    /**
     * Opens the host's world to LAN on a free port, which is what e4mc shares and what a tunnel
     * forwards. Returns the port, or -1 if the world can't be opened.
     */
    static int openToLan() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return -1;
        }
        if (server.isPublished()) {
            return server.getPort();
        }
        int port = HttpUtil.getAvailablePort();
        return server.publishServer(MinecraftServer.MultiplayerScope.LAN, port) ? port : -1;
    }

    static boolean modLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }
}
