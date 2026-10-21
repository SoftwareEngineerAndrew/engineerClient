package net.rustclear.mod.online;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.rustclear.mod.Protocol;
import net.rustclear.mod.RustclearMod;
import net.rustclear.mod.engine.KitItems;
import net.rustclear.mod.engine.SkyblockImport;
import net.rustclear.mod.engine.SkyblockProfile;

/**
 * Reads this player's Hypixel profile through the lobby (LobbyClient.hypixelProfile) for a party
 * whose leader picked "each player's Hypixel stats", and sends it to the host with the settings.
 * Kept in the saved settings, so it's there next time without asking again; read again at most
 * every ten minutes unless asked.
 *
 * The profile's inventory, Ender Chest, backpacks and pets (SkyblockImport) become this player's
 * kit and storage in that party: sent to the host (Protocol.Skyblock) after each read and on
 * joining. Kept in memory only, so a new game reads it again.
 */
public final class HypixelProfile {
    private static final long FRESH_MS = 10 * 60 * 1000;
    private static volatile boolean busy;
    private static volatile String error;
    private static volatile long readAt;
    private static volatile long triedAt;
    /** The inventory last read, or null. */
    private static volatile SkyblockImport.Result imported;
    /** Whether this world's host has the inventory last read. */
    private static volatile boolean sent;

    private HypixelProfile() {
    }

    public static boolean busy() {
        return busy;
    }

    /** Why the last read failed, or null. */
    public static String status() {
        return error;
    }

    /** Reads the profile ([force]: even if it was read lately); when it arrives, the host gets it. */
    public static void fetch(boolean force) {
        long now = System.currentTimeMillis();
        boolean fresh = RustclearMod.settings != null && RustclearMod.settings.hypixel != null && now - readAt < FRESH_MS;
        // After a failure, wait a minute before trying again on its own.
        boolean failedLately = error != null && now - triedAt < 60_000;
        if (busy || (!force && (fresh || failedLately))) {
            return;
        }
        busy = true;
        triedAt = now;
        LobbyClient.hypixelProfile().whenComplete((body, e) -> Minecraft.getInstance().execute(() -> {
            busy = false;
            SkyblockProfile profile = null;
            Throwable failed = e;
            if (failed == null && body != null) {
                try {
                    profile = Protocol.GSON.fromJson(body, SkyblockProfile.class);
                } catch (RuntimeException bad) {
                    failed = bad;
                }
            }
            if (failed != null || profile == null) {
                Throwable cause = failed != null && failed.getCause() != null ? failed.getCause() : failed;
                error = cause == null ? "Couldn't read your Hypixel profile." : cause.getMessage();
                RustclearMod.LOGGER.info("Couldn't read the Hypixel profile: {}", error);
                return;
            }
            error = null;
            readAt = System.currentTimeMillis();
            try {
                imported = SkyblockImport.read(body, KitItems::kind, profile.cataLevel());
            } catch (RuntimeException bad) {
                RustclearMod.LOGGER.warn("Couldn't read the Hypixel inventory: {}", bad.toString());
                imported = null;
            }
            if (RustclearMod.settings != null) {
                RustclearMod.settings.hypixel = profile;
                RustclearMod.send("join");
            }
            sent = false;
            sendInventory();
        }));
    }

    /** What the inventory read gave, for the settings screen; null before a read. */
    public static String inventorySummary() {
        SkyblockImport.Result r = imported;
        if (r == null) {
            return null;
        }
        if (r.kit() == null) {
            return "Your profile's Inventory API is off on Hypixel: your kit stays the Kit editor's.";
        }
        int missing = r.missingCount();
        String top = r.missing().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(3)
            .map(Map.Entry::getKey).collect(Collectors.joining(", "));
        return String.format(java.util.Locale.ROOT, "Kit from your SkyBlock inventory: %d of %d items%s.",
            r.items() - missing, r.items(), missing == 0 ? "" : " (not in Rustclear yet: " + top + (r.missing().size() > 3 ? "..." : "") + ")");
    }

    /** A new Rustclear world: its host doesn't have the inventory yet. */
    public static void joined() {
        sent = false;
    }

    /** Sends the inventory last read to the Rustclear world this client is in, once: the kit, then each storage page. */
    private static void sendInventory() {
        SkyblockImport.Result r = imported;
        if (sent || r == null || !ClientPlayNetworking.canSend(Protocol.Skyblock.TYPE)) {
            return;
        }
        sent = true;
        JsonObject kit = new JsonObject();
        kit.addProperty("part", "kit");
        kit.add("kit", r.kit() == null ? null : Protocol.GSON.toJsonTree(r.kit()));
        kit.addProperty("pages", r.storage().size());
        send(kit);
        for (SkyblockImport.Page page : r.storage()) {
            JsonObject m = new JsonObject();
            m.addProperty("part", "page");
            m.add("page", Protocol.GSON.toJsonTree(page));
            send(m);
        }
    }

    private static void send(JsonObject m) {
        String json = m.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > Protocol.Skyblock.MAX) {
            RustclearMod.LOGGER.warn("A SkyBlock inventory part was too big to send ({} chars)", json.length());
            return;
        }
        ClientPlayNetworking.send(new Protocol.Skyblock(json));
    }

    /** On joining a Rustclear world: reads the profile when the party plays Hypixel stats. */
    public static void onJoin(boolean hypixelLevels) {
        if (hypixelLevels && LobbyClient.configured()) {
            fetch(false);
            // Read lately (no new read): this world's host may still need it.
            sendInventory();
        }
    }
}
