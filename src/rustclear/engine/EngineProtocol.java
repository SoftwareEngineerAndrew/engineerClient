package net.rustclear.mod.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.rustclear.mod.DungeonSettings;
import net.rustclear.mod.Protocol;
import net.rustclear.mod.RustclearMod;

/**
 * The engine's side of the mod handshake (Smalldungeons rustclear_mod.rs). The singleplayer server
 * speaks the same rustclear:hello / settings / status / board messages the Rust server does, so the
 * settings screen and pause menu buttons work the same in the Rustclear world without changes.
 */
public final class EngineProtocol {
    private EngineProtocol() {
    }

    /** Settings messages that rebuild the dungeon ("generate") or change it ("apply"): one a second each. */
    private static final Throttle SETTINGS = new Throttle(20);
    /** Practice rebuilds the dungeon, and Save Hotbar writes a file: one a second each. */
    private static final Throttle ACTIONS = new Throttle(20);
    /** Party requests: four a second. */
    private static final Throttle PARTY = new Throttle(5);
    /** A guest's left clicks (shortbows): ten a second at most, dropped quietly past that. */
    private static final Throttle LEFT_CLICKS = new Throttle(2);
    /** Kit editor saves (each one hands the kit out again): one a second. */
    private static final Throttle KITS = new Throttle(20);
    /** Longest Minecraft player name. */
    private static final int MAX_PLAYER_NAME = 16;
    /** Longest room name, guaranteed room list and puzzle list a settings message may carry. */
    private static final int MAX_NAME = 64;
    private static final int MAX_ROOMS = 64;
    private static final int MAX_PUZZLES = 32;
    private static final int MAX_SECRETS = 255;

    static void register() {
        ServerPlayNetworking.registerGlobalReceiver(Protocol.Settings.TYPE, (payload, context) -> {
            if (context.server() == EngineServer.server()) {
                onSettings(context.server(), context.player(), payload.json());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Protocol.Board.TYPE, (payload, context) -> {
            if (context.server() == EngineServer.server()) {
                sendBoard(context.player());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Protocol.Hit.TYPE, (payload, context) -> {
            if (context.server() != EngineServer.server()) {
                return;
            }
            try {
                Protocol.HitMessage hit = Protocol.GSON.fromJson(payload.json(), Protocol.HitMessage.class);
                EngineServer.onGuestHit(context.player(), new BlockPos(hit.x(), hit.y(), hit.z()));
            } catch (RuntimeException e) {
                RustclearMod.LOGGER.debug("Bad Rustclear hit: {}", e.toString());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Protocol.Action.TYPE, (payload, context) -> {
            if (context.server() == EngineServer.server()) {
                onAction(context.server(), context.player(), payload.json());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Protocol.Party.TYPE, (payload, context) -> {
            if (context.server() == EngineServer.server()) {
                onParty(context.server(), context.player(), payload.json());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Protocol.Kit.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (context.server() != EngineServer.server() || !EngineServer.isEngineLevel(player.level())) {
                return;
            }
            if (!KITS.allow(player)) {
                player.sendSystemMessage(EngineItems.text("§cSlow down a little: your kit wasn't sent."));
                return;
            }
            KitItems.receive(player, payload.json());
        });
        ServerPlayNetworking.registerGlobalReceiver(Protocol.Skyblock.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (context.server() == EngineServer.server() && EngineServer.isEngineLevel(player.level())) {
                SkyblockData.receive(player, payload.json());
            }
        });
    }

    /**
     * rustclear:party from a member: "state" and "ready" from anyone; invite, kick, leader,
     * open, close, mode and start from the leader only. Names must be real members' (or, for an
     * invite, a valid player name).
     */
    private static void onParty(MinecraftServer server, ServerPlayer player, String json) {
        Party party = EngineServer.party();
        if (party == null || !EngineServer.isEngineLevel(player.level())) {
            return;
        }
        Protocol.PartyRequest request;
        try {
            request = Protocol.GSON.fromJson(json, Protocol.PartyRequest.class);
        } catch (RuntimeException e) {
            RustclearMod.LOGGER.debug("Bad Rustclear party request: {}", e.toString());
            return;
        }
        if (request == null || request.action == null || !PARTY.allow(player)) {
            return;
        }
        if (request.target != null && (request.target.length() > MAX_PLAYER_NAME || !request.target.matches("[A-Za-z0-9_]{1,16}"))) {
            return;
        }
        EngineRun run = EngineServer.run();
        switch (request.action) {
            case "state" -> party.sync(run, EngineServer.duels(), true);
            case "ready" -> {
                // Ready-up happens at the party's Mort; a duel's runs are already under way.
                if (run != null && EngineServer.duels() == null) {
                    run.toggleReady(player);
                }
            }
            case "class" -> pickClass(player, run, DungeonClass.of(request.dungeonClass));
            default -> {
                if (!party.isLeader(player)) {
                    player.sendSystemMessage(EngineItems.text("§cOnly the party leader can do that."));
                    return;
                }
                leaderAction(server, player, party, run, request);
            }
        }
        party.sync(EngineServer.run(), EngineServer.duels(), false);
    }

    private static void leaderAction(MinecraftServer server, ServerPlayer leader, Party party, EngineRun run, Protocol.PartyRequest request) {
        ServerPlayer target = request.target == null ? null : server.getPlayerList().getPlayerByName(request.target);
        switch (request.action) {
            case "invite" -> {
                if (request.target == null) {
                    return;
                }
                party.invite(request.target);
                leader.sendSystemMessage(EngineItems.text("§aInvited §e" + request.target + "§a. They can join while the party is open to them."));
            }
            case "kick" -> {
                if (target == null || target == leader || EngineServer.isHost(target)) {
                    return;
                }
                party.broadcast("§e" + target.getName().getString() + " §cwas removed from the party.");
                Protocol.PartyState closed = new Protocol.PartyState();
                closed.closed = "The party leader removed you from the party.";
                if (ServerPlayNetworking.canSend(target, Protocol.Party.TYPE)) {
                    ServerPlayNetworking.send(target, new Protocol.Party(Protocol.GSON.toJson(closed)));
                }
                target.connection.disconnect(Component.literal(closed.closed));
            }
            case "leader" -> {
                if (target != null && target != leader) {
                    party.transfer(target);
                    // Both of their settings screens change between editable and read-only.
                    sendStatus(leader, true, "");
                    sendStatus(target, true, "You lead the party now: you pick the dungeon.");
                }
            }
            case "open", "close" -> {
                party.setOpen(request.action.equals("open"));
                leader.sendSystemMessage(EngineItems.text(party.isOpen()
                    ? "§aThe party is open: anyone with the address can join."
                    : "§aThe party is private: only invited players can join."));
            }
            case "mode" -> {
                Party.Mode mode = Party.Mode.parse(request.mode);
                if (mode != null && EngineServer.duels() == null && (run == null || run.phase() == EngineRun.Phase.LOBBY)) {
                    party.setMode(mode);
                    if (run != null) {
                        // A duel's Mort waits for the duel to start everyone together.
                        run.holdStart(mode.duel());
                    }
                    party.broadcast("§aMode: §e" + mode.label() + (mode.duel()
                        ? "§a, first to §e" + party.target() + " score§a. Ready up at Mort to start the duel." : ""));
                }
            }
            case "size" -> {
                if (request.value >= 1 && request.value <= Party.MAX_SIZE && request.value != party.size()) {
                    party.setSize(request.value);
                    party.broadcast("§aThe party now holds up to §e" + party.size() + "§a players.");
                }
            }
            case "target" -> {
                if (Party.target(request.value) == request.value && request.value != party.target()
                    && EngineServer.duels() == null) {
                    party.setTarget(request.value);
                    party.broadcast("§aDuels are now first to §e" + party.target() + " score§a.");
                }
            }
            case "start" -> {
                if (run != null && EngineServer.duels() == null && run.phase() == EngineRun.Phase.LOBBY) {
                    assignClasses(party);
                    run.forceStart();
                }
            }
            case "rematch" -> EngineServer.rematch();
            case "lobby" -> EngineServer.endDuel(server);
            default -> RustclearMod.LOGGER.debug("Unknown Rustclear party action {}", request.action);
        }
    }

    /**
     * A class picked on the party screen, before the run: it readies the player. Several players
     * may pick the same class. Picking the class you're ready with again un-readies you.
     */
    private static void pickClass(ServerPlayer player, EngineRun run, DungeonClass c) {
        if (c == null || run == null || EngineServer.duels() != null || run.phase() != EngineRun.Phase.LOBBY) {
            return;
        }
        boolean same = PlayerStats.picked(player) == c;
        boolean ready = run.isReady(player.getUUID());
        if (!same) {
            PlayerStats.pick(player, c);
            EngineServer.party().broadcast("§e" + player.getName().getString() + " §7is playing " + c.color + c.title + "§7.");
            refreshOrbs();
        }
        if (!ready || same) {
            run.toggleReady(player);
        }
    }

    /**
     * Force start: everyone who hasn't picked a class gets one at random, never one somebody
     * already plays while any of the five is free (so duplicates only past five players).
     */
    private static void assignClasses(Party party) {
        List<ServerPlayer> members = party.members();
        Map<DungeonClass, Integer> taken = new EnumMap<>(DungeonClass.class);
        for (DungeonClass c : DungeonClass.values()) {
            taken.put(c, 0);
        }
        List<ServerPlayer> unpicked = new ArrayList<>();
        for (ServerPlayer p : members) {
            DungeonClass c = PlayerStats.picked(p);
            if (c == null) {
                unpicked.add(p);
            } else {
                taken.merge(c, 1, Integer::sum);
            }
        }
        if (unpicked.isEmpty()) {
            return;
        }
        Collections.shuffle(unpicked);
        for (ServerPlayer p : unpicked) {
            int fewest = Collections.min(taken.values());
            List<DungeonClass> free = new ArrayList<>();
            taken.forEach((c, n) -> {
                if (n == fewest) {
                    free.add(c);
                }
            });
            DungeonClass c = free.get(ThreadLocalRandom.current().nextInt(free.size()));
            taken.merge(c, 1, Integer::sum);
            PlayerStats.pick(p, c);
            party.broadcast("§e" + p.getName().getString() + " §7was given " + c.color + c.title + "§7.");
        }
        refreshOrbs();
    }

    /** Who's solo in their class can change with anyone's pick, and the orbs' cooldowns with it. */
    private static void refreshOrbs() {
        EngineServer.players().forEach(p -> {
            PlayerStats.forgetCached(p.getUUID());
            DungeonOrb.refresh(p);
        });
    }

    static void forget(UUID id) {
        SETTINGS.forget(id);
        ACTIONS.forget(id);
        PARTY.forget(id);
        LEFT_CLICKS.forget(id);
        KITS.forget(id);
    }

    static void reset() {
        SETTINGS.clear();
        ACTIONS.clear();
        PARTY.clear();
        LEFT_CLICKS.clear();
        KITS.clear();
    }

    /** rustclear:action: practice a room (the leader only) or save / reset your own hotbar. */
    private static void onAction(MinecraftServer server, ServerPlayer player, String json) {
        Protocol.ActionMessage message;
        try {
            message = Protocol.GSON.fromJson(json, Protocol.ActionMessage.class);
        } catch (RuntimeException e) {
            RustclearMod.LOGGER.debug("Bad Rustclear action: {}", e.toString());
            return;
        }
        if (message == null || message.action == null || !EngineServer.isEngineLevel(player.level())) {
            return;
        }
        if (message.action.equals("leftclick")) {
            if (LEFT_CLICKS.allow(player)) {
                EngineServer.onLeftClick(player);
            }
            return;
        }
        if (message.action.equals("class")) {
            if (LEFT_CLICKS.allow(player)) {
                EngineServer.onClassAbility(player, message.ultimate);
            }
            return;
        }
        if (!ACTIONS.allow(player)) {
            player.sendSystemMessage(EngineItems.text("§cSlow down a little."));
            return;
        }
        switch (message.action) {
            case "hotbar" -> player.sendSystemMessage(EngineItems.text(
                message.save ? HotbarLayout.save(player) : HotbarLayout.reset(player)));
            case "practice" -> {
                if (!EngineServer.isLeader(player)) {
                    player.sendSystemMessage(EngineItems.text("§cOnly the party leader can start practice."));
                    return;
                }
                if (message.room == null || message.room.isBlank() || message.room.length() > MAX_NAME) {
                    return;
                }
                String reply;
                try {
                    EngineServer.setPracticeMobs(message.mobs);
                    reply = EngineServer.practice(server, practiceName(message.room), null, null);
                } catch (IllegalArgumentException e) {
                    reply = "§c" + e.getMessage();
                }
                Component text = EngineItems.text(reply);
                EngineServer.players().forEach(p -> p.sendSystemMessage(text));
            }
            default -> RustclearMod.LOGGER.debug("Unknown Rustclear action {}", message.action);
        }
    }

    /** The practice name for a room as the settings screen lists it; "Blaze" stands for either blaze room. */
    private static String practiceName(String roomName) {
        if (roomName.equals("Blaze")) {
            return ThreadLocalRandom.current().nextBoolean() ? "higher_blaze" : "lower_blaze";
        }
        return roomName.replace(' ', '_');
    }

    /** send_hello: the room catalog. The client answers with a "join" settings message. */
    static void sendHello(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, Protocol.Hello.TYPE)) {
            return;
        }
        RoomStore store = RoomStore.get();
        TreeMap<String, Protocol.Room> rooms = new TreeMap<>();
        TreeMap<String, Protocol.Room> puzzles = new TreeMap<>();
        for (RoomData data : store.rooms) {
            TreeMap<String, Protocol.Room> target = switch (data.type()) {
                case NORMAL, TRAP, YELLOW -> rooms;
                case PUZZLE -> puzzles;
                default -> null;
            };
            if (target == null || data.name().isEmpty() || data.name().equals("Default")) {
                continue;
            }
            target.computeIfAbsent(data.name(), n -> {
                Protocol.Room room = new Protocol.Room();
                room.name = n;
                room.type = data.type().name().toLowerCase();
                room.shape = shapeName(data.shape());
                room.secrets = data.secrets();
                return room;
            });
        }
        Protocol.Catalog catalog = new Protocol.Catalog();
        catalog.version = Protocol.VERSION;
        catalog.required = false;
        catalog.rooms = new ArrayList<>(rooms.values());
        catalog.puzzles = new ArrayList<>(puzzles.values());
        catalog.current = EngineServer.settings().copy();
        catalog.secretHistogram = Planner.secretHistogram(store);
        catalog.engine = true;
        catalog.modVersion = RustclearMod.version();
        ServerPlayNetworking.send(player, new Protocol.Hello(Protocol.GSON.toJson(catalog)));
    }

    /**
     * handle_settings. Speed is always the sender's own. Only the party leader changes the dungeon:
     * a guest's "join" never touches it, and their "apply" or "generate" is answered with why not.
     */
    private static void onSettings(MinecraftServer server, ServerPlayer player, String json) {
        String action;
        DungeonSettings settings;
        try {
            JsonObject message = JsonParser.parseString(json).getAsJsonObject();
            int version = message.has("version") ? message.get("version").getAsInt() : 0;
            String modVersion = message.has("mod_version") && !message.get("mod_version").isJsonNull()
                ? message.get("mod_version").getAsString() : null;
            if (version != Protocol.VERSION || !RustclearMod.version().equals(modVersion)) {
                // A guest on another build: tell them on their own screen and let them go cleanly.
                player.connection.disconnect(Component.literal("This Rustclear party runs Rustclear "
                    + RustclearMod.version() + " (protocol " + Protocol.VERSION + ").\nYou have "
                    + (modVersion == null ? "an older version" : modVersion) + " (protocol " + version
                    + "). Install the same version as the host to join."));
                return;
            }
            action = message.get("action").getAsString();
            settings = message.has("settings") && message.get("settings").isJsonObject()
                ? Protocol.GSON.fromJson(message.get("settings"), DungeonSettings.class) : new DungeonSettings();
        } catch (RuntimeException e) {
            RustclearMod.LOGGER.warn("Bad Rustclear settings message: {}", e.toString());
            return;
        }
        if (settings == null || !inRange(settings)) {
            sendStatus(player, false, "Those settings are out of range.");
            return;
        }
        settings = Planner.normalized(settings);
        boolean leader = EngineServer.isLeader(player);
        boolean dungeonChange = action.equals("apply") || action.equals("generate");
        if (dungeonChange && !SETTINGS.allow(player)) {
            sendStatus(player, false, "Slow down a little.");
            return;
        }
        EngineServer.setSpeed(player, settings.speed);
        EngineServer.setStats(player, settings);
        if (leader) {
            EngineServer.setPracticeMobs(settings.practiceMobs());
        }
        switch (action) {
            case "join" -> {
                // The leader's saved settings seed a fresh world; a guest's never change it.
                if (leader && EngineServer.settings().isFullyRandom()) {
                    EngineServer.setSettings(settings);
                }
                sendStatus(player, true, leader ? "" : "The party leader picks the dungeon. Your speed is your own.");
            }
            case "apply" -> {
                if (!leader) {
                    sendStatus(player, true, "Speed saved. Only the party leader can change the dungeon.");
                    return;
                }
                EngineServer.setSettings(settings);
                String text = "Next dungeon: " + Planner.describe(settings);
                broadcastStatus(player, text);
                player.sendSystemMessage(EngineItems.text("§a" + text));
            }
            case "generate" -> {
                if (!leader) {
                    sendStatus(player, false, "Only the party leader can start a new dungeon. Your speed was saved.");
                    return;
                }
                DungeonSettings previous = EngineServer.settings();
                EngineServer.setSettings(settings);
                try {
                    EngineServer.newDungeon(server);
                } catch (Planner.PlanException e) {
                    // The dungeon stays as it was; the new speed still applies.
                    EngineServer.setSettings(previous);
                    sendStatus(player, false, e.getMessage());
                    player.sendSystemMessage(EngineItems.text("§c" + e.getMessage()));
                    return;
                }
                String text = "New dungeon: " + EngineServer.totalSecrets() + " secrets.";
                // Only the party's own dungeon was rebuilt, so only its players are told.
                for (ServerPlayer other : EngineServer.players()) {
                    sendStatus(other, true, text);
                    other.sendSystemMessage(EngineItems.text("§a" + text));
                }
            }
            default -> RustclearMod.LOGGER.warn("Unknown Rustclear settings action {}", action);
        }
    }

    /** Are a settings message's lists and numbers within what any real dungeon could ask for? */
    private static boolean inRange(DungeonSettings s) {
        if (s.rooms != null && (s.rooms.size() > MAX_ROOMS || s.rooms.stream().anyMatch(r -> r != null && r.length() > MAX_NAME))) {
            return false;
        }
        if (s.puzzles != null && (s.puzzles.size() > MAX_PUZZLES || s.puzzles.stream().anyMatch(p -> p != null && p.length() > MAX_NAME))) {
            return false;
        }
        return s.secrets == null || (s.secrets.length == 2 && s.secrets[0] >= 0 && s.secrets[1] >= 0
            && s.secrets[0] <= MAX_SECRETS && s.secrets[1] <= MAX_SECRETS);
    }

    /** The new settings to everyone in the dungeon (so guests' read-only screens follow); [message] to [sender] only. */
    private static void broadcastStatus(ServerPlayer sender, String message) {
        for (ServerPlayer other : EngineServer.players()) {
            if (other != sender) {
                sendStatus(other, true, "");
            }
        }
        sendStatus(sender, true, message);
    }

    static void sendStatus(ServerPlayer player, boolean ok, String message) {
        if (!ServerPlayNetworking.canSend(player, Protocol.Status.TYPE)) {
            return;
        }
        Protocol.StatusMessage status = new Protocol.StatusMessage();
        status.ok = ok;
        status.message = message;
        // Nothing is recorded in singleplayer yet, so no run here is ranked.
        status.ranked = false;
        // The world's dungeon, with this player's own speed and the leader's practice toggle.
        status.settings = EngineServer.settings().copy();
        int speed = EngineServer.speed(player.getUUID());
        status.settings.speed = speed == DungeonSettings.DEFAULT_SPEED ? null : speed;
        status.settings.practiceMobs = EngineServer.practiceMobs() ? Boolean.TRUE : null;
        // The floor is the world's; Health, Defense and damage are the player's own (settings keeps them).
        status.totalSecrets = EngineServer.totalSecrets();
        status.leader = EngineServer.isLeader(player);
        ServerPlayNetworking.send(player, new Protocol.Status(Protocol.GSON.toJson(status)));
    }

    /** An empty board: runs in the singleplayer world aren't recorded yet. */
    private static void sendBoard(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, Protocol.Board.TYPE)) {
            return;
        }
        Protocol.BoardMessage board = new Protocol.BoardMessage();
        board.version = Protocol.VERSION;
        board.categories = List.of("solo");
        board.currentRunRanked = false;
        board.currentRunReason = "Singleplayer runs aren't recorded yet.";
        ServerPlayNetworking.send(player, new Protocol.Board(Protocol.GSON.toJson(board)));
    }

    /** RoomShape's Rust name (its Debug form), which the settings screen reads. */
    private static String shapeName(RoomShape shape) {
        return switch (shape) {
            case ONE_BY_ONE -> "OneByOne";
            case ONE_BY_ONE_END -> "OneByOneEnd";
            case ONE_BY_ONE_CROSS -> "OneByOneCross";
            case ONE_BY_ONE_STRAIGHT -> "OneByOneStraight";
            case ONE_BY_ONE_BEND -> "OneByOneBend";
            case ONE_BY_ONE_TRIPLE -> "OneByOneTriple";
            case ONE_BY_TWO -> "OneByTwo";
            case ONE_BY_THREE -> "OneByThree";
            case ONE_BY_FOUR -> "OneByFour";
            case TWO_BY_TWO -> "TwoByTwo";
            case FOUR_BY_FOUR -> "FourByFour";
            case L -> "L";
            case EMPTY -> "Empty";
        };
    }
}
