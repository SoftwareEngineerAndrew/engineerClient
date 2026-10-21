package net.rustclear.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.rustclear.mod.engine.EngineServer;
import net.rustclear.mod.engine.EngineWorld;
import net.rustclear.mod.online.LobbyClient;
import net.rustclear.mod.online.Relays;

/**
 * Everything the Rustclear menu needs from the online side, behind one door so the menu reads the
 * same whether or not it works: relay sharing of the host's world, the party listing on the lobby,
 * and the 1v1 / 2v2 queue up to the moment a match's players are in the host's world. Solo,
 * private parties, LAN and joining by address never need the lobby. Client thread only.
 */
public final class Online {
    /** Whether the online options can be used now, and what to show on them if not. */
    public record Status(boolean ready, String note) {
        /** Whether the lobby is still being checked. */
        public boolean checking() {
            return this == CHECKING;
        }
    }

    private static final Status READY = new Status(true, null);
    private static final Status CHECKING = new Status(false, "Checking…");
    private static final Status UNAVAILABLE = new Status(false, "Online features unavailable");

    /** How often a public party's listing is renewed; the lobby drops it after 75 s without. */
    private static final int HEARTBEAT_TICKS = 30 * 20;
    /** How long a matched guest waits for the host's world before giving up. */
    private static final long MATCH_WAIT_MS = 120_000;

    // Hosting.
    private static boolean shareOnJoin;
    private static boolean listPublic;
    /** Whether the listing has gone out at least once (until then it is retried every 2 s). */
    private static boolean listed;
    /** Why sharing didn't start, for the Party screen, or null. */
    public static volatile String shareProblem;
    /** Whether sharing is under way (waiting for the relay's address). */
    public static volatile boolean sharing;
    private static int ticks;

    // The queue.
    public enum QueuePhase { IDLE, CONNECTING, QUEUED, HOSTING, WAITING_FOR_HOST, JOINING, FAILED }

    /** The scores a duel can race to; the queue for each is separate. */
    public static final int[] TARGETS = {270, 300};
    /** How often the queue screen asks the lobby how many are searching. */
    private static final long COUNTS_EVERY_MS = 4_000;

    private static QueuePhase phase = QueuePhase.IDLE;
    private static String queueMode;
    private static int queueTarget = TARGETS[0];
    private static long queueSince;
    private static String queueMessage;
    private static String matchId;
    private static String matchHost;
    private static long matchDeadline;
    private static List<String> matchPlayers = List.of();
    private static Map<String, String> matchTeams = Map.of();
    /** Players searching per queue ("DUEL_1V1:270" to a count), as the lobby last said. */
    private static volatile Map<String, Integer> queueCounts = Map.of();
    private static long countsAsked;

    private Online() {
    }

    static void register() {
        LobbyClient.onMessage(Online::onLobbyMessage);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    // ---------------------------------------------------------------- status

    public static Status status() {
        if (!LobbyClient.configured()) {
            return UNAVAILABLE;
        }
        return switch (LobbyClient.health()) {
            case UP -> READY;
            case CHECKING, UNKNOWN -> CHECKING;
            case DOWN -> UNAVAILABLE;
        };
    }

    /** Checks the lobby again (the menu does this as it opens). */
    public static void refresh() {
        LobbyClient.checkHealth();
    }

    public static void openBrowser() {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new PartyBrowserScreen(mc.gui.screen()));
    }

    public static void openQueue(String mode) {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new QueueScreen(mc.gui.screen(), mode));
    }

    // ---------------------------------------------------------------- hosting

    /** The next Rustclear world this game opens is a party, open to anyone or only to invites. */
    public static void hostParty(boolean open) {
        EngineServer.openOnStart(open);
        shareOnJoin = true;
        listPublic = open;
    }

    /** The host is in their newly opened world: share it through the relay. */
    public static void onHostJoined() {
        if (shareOnJoin) {
            shareOnJoin = false;
            share();
        }
    }

    /** Starts (or retries) sharing the host's world; the address lands in RustclearMod.shareAddress. */
    public static void share() {
        shareProblem = null;
        sharing = true;
        Relays.start().whenComplete((address, error) -> Minecraft.getInstance().execute(() -> {
            sharing = false;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                shareProblem = cause instanceof java.util.concurrent.TimeoutException
                    ? "The relay didn't answer. Try again from Party > Invite." : cause.getMessage();
            } else {
                onAddressReady();
            }
        }));
    }

    /** The world has a join address (relay or pasted tunnel): list it, or hand it to the match. */
    public static void onAddressReady() {
        String address = RustclearMod.shareAddress;
        if (address == null) {
            return;
        }
        if (matchId != null && phase == QueuePhase.HOSTING) {
            JsonObject hosting = new JsonObject();
            hosting.addProperty("type", "hosting");
            hosting.addProperty("id", matchId);
            hosting.addProperty("address", address);
            LobbyClient.send(hosting);
            phase = QueuePhase.IDLE;
            matchId = null;
        } else if (listPublic && LobbyClient.configured()) {
            heartbeat();
        }
    }

    /** The host left their world: nothing is shared or listed any more. */
    public static void onLeftWorld() {
        if (listPublic && LobbyClient.connected()) {
            JsonObject unhost = new JsonObject();
            unhost.addProperty("type", "unhost");
            LobbyClient.send(unhost);
        }
        listPublic = false;
        listed = false;
        RustclearMod.shareAddress = null;
        shareProblem = null;
        sharing = false;
        if (phase == QueuePhase.IDLE || phase == QueuePhase.JOINING) {
            LobbyClient.close();
        }
    }

    /** A public party's listing: only what PRIVACY.md lists, the address a host name. */
    private static void heartbeat() {
        Protocol.PartyState party = RustclearMod.party;
        String address = RustclearMod.shareAddress;
        if (address == null || party == null || !party.open || !party.you) {
            return;
        }
        JsonObject p = new JsonObject();
        JsonArray members = new JsonArray();
        party.members.forEach(m -> members.add(m.name));
        p.add("members", members);
        p.addProperty("max", party.max);
        p.addProperty("mode", party.mode);
        p.addProperty("version", RustclearMod.version());
        p.addProperty("address", address);
        JsonObject host = new JsonObject();
        host.addProperty("type", "host");
        host.add("party", p);
        listed = true;
        LobbyClient.send(host).exceptionally(e -> null);
    }

    private static void tick() {
        ticks++;
        if (listPublic && (ticks % HEARTBEAT_TICKS == 0 || (!listed && ticks % 40 == 0))) {
            heartbeat();
        }
        if (ticks % (25 * 20) == 0 && LobbyClient.connected()) {
            JsonObject ping = new JsonObject();
            ping.addProperty("type", "ping");
            LobbyClient.send(ping);
        }
        if (phase == QueuePhase.WAITING_FOR_HOST && System.currentTimeMillis() > matchDeadline) {
            fail("The match's host didn't open the dungeon. Queue again.");
        }
    }

    // ---------------------------------------------------------------- the queue

    public static QueuePhase phase() {
        return phase;
    }

    public static String queueMode() {
        return queueMode;
    }

    public static int queueTarget() {
        return queueTarget;
    }

    /** How many players are searching for [mode] first to [target], or -1 before the lobby says. */
    public static int searching(String mode, int target) {
        return queueCounts.getOrDefault(mode + ":" + target, queueCounts.isEmpty() ? -1 : 0);
    }

    /** Asks the lobby again how many are in each queue, at most every few seconds. */
    public static void refreshCounts() {
        long now = System.currentTimeMillis();
        if (now - countsAsked < COUNTS_EVERY_MS || !LobbyClient.configured()) {
            return;
        }
        countsAsked = now;
        LobbyClient.queues().whenComplete((counts, e) -> {
            if (e == null) {
                queueCounts = counts;
            }
        });
    }

    public static long queueSince() {
        return queueSince;
    }

    public static String queueMessage() {
        return queueMessage;
    }

    public static String matchHost() {
        return matchHost;
    }

    public static List<String> matchPlayers() {
        return matchPlayers;
    }

    public static Map<String, String> matchTeams() {
        return matchTeams;
    }

    /**
     * Queues for [mode] ("DUEL_1V1" or "DUEL_2V2") first to [target] score, with [partner] for a
     * 2v2 duo (or null).
     */
    public static void queue(String mode, int target, String partner) {
        queueMode = mode;
        queueTarget = target;
        countsAsked = 0;
        queueMessage = null;
        queueSince = System.currentTimeMillis();
        phase = QueuePhase.CONNECTING;
        JsonObject q = new JsonObject();
        q.addProperty("type", "queue");
        q.addProperty("mode", mode);
        q.addProperty("target", target);
        q.addProperty("version", RustclearMod.version());
        if (partner != null && !partner.isBlank()) {
            q.addProperty("with", partner.trim());
        }
        LobbyClient.send(q).whenComplete((v, e) -> {
            if (e != null) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                Minecraft.getInstance().execute(() -> fail(cause.getMessage() != null ? cause.getMessage() : "Online features unavailable."));
            }
        });
    }

    public static void cancelQueue() {
        if (phase != QueuePhase.IDLE && LobbyClient.connected()) {
            JsonObject cancel = new JsonObject();
            cancel.addProperty("type", "cancel");
            LobbyClient.send(cancel);
        }
        phase = QueuePhase.IDLE;
        queueMessage = null;
        matchId = null;
    }

    private static void fail(String message) {
        phase = QueuePhase.FAILED;
        queueMessage = message;
        matchId = null;
    }

    private static void onLobbyMessage(JsonObject message) {
        String type = message.has("type") ? message.get("type").getAsString() : "";
        switch (type) {
            case "queued" -> {
                if (phase == QueuePhase.CONNECTING || phase == QueuePhase.QUEUED) {
                    phase = QueuePhase.QUEUED;
                }
            }
            case "match" -> onMatch(message);
            case "join" -> {
                String address = Relays.cleanAddress(message.get("address").getAsString());
                if (address != null && phase == QueuePhase.WAITING_FOR_HOST) {
                    phase = QueuePhase.JOINING;
                    join(address);
                }
            }
            case "cancelled" -> {
                if (message.has("message")) {
                    fail(message.get("message").getAsString());
                }
            }
            case "error" -> queueMessage = message.has("message") ? message.get("message").getAsString() : null;
            case "closed" -> {
                if (phase == QueuePhase.QUEUED || phase == QueuePhase.CONNECTING || phase == QueuePhase.WAITING_FOR_HOST) {
                    fail("Lost the connection to the lobby.");
                }
            }
            default -> {
            }
        }
    }

    /**
     * A match: the host's game opens a private duel world for exactly the matched players and
     * shares it; everyone else's waits for its address and then connects by itself.
     */
    private static void onMatch(JsonObject message) {
        if (phase != QueuePhase.QUEUED && phase != QueuePhase.CONNECTING) {
            return;
        }
        matchId = message.get("id").getAsString();
        matchHost = message.get("host").getAsString();
        List<String> players = new ArrayList<>();
        for (JsonElement e : message.getAsJsonArray("players")) {
            players.add(e.getAsString());
        }
        Map<String, String> teams = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : message.getAsJsonObject("teams").entrySet()) {
            teams.put(e.getKey(), e.getValue().getAsString());
        }
        matchPlayers = players;
        matchTeams = teams;
        int target = message.has("target") ? message.get("target").getAsInt() : queueTarget;
        String me = Minecraft.getInstance().getUser().getName();
        if ("host".equals(message.get("role").getAsString())) {
            phase = QueuePhase.HOSTING;
            List<String> guests = players.stream().filter(p -> !p.equalsIgnoreCase(me)).toList();
            EngineServer.prepareMatch(new EngineServer.Match(queueMode, target, guests, teams));
            hostParty(false);
            listPublic = false;
            EngineWorld.open();
        } else {
            phase = QueuePhase.WAITING_FOR_HOST;
            matchDeadline = System.currentTimeMillis() + MATCH_WAIT_MS;
        }
    }

    /** Connects to a party's join address (never a raw IP). Works without the lobby. */
    public static void join(String address) {
        String clean = Relays.cleanAddress(address);
        if (clean == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Screen back = new RustclearMenuScreen(new TitleScreen());
        ConnectScreen.startConnecting(back, mc, ServerAddress.parseString(clean),
            new ServerData("Rustclear party", clean, ServerData.Type.OTHER), false, null);
    }
}
