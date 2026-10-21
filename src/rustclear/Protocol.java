package net.rustclear.mod;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.function.Function;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The messages shared with the Rustclear server (its src/dungeon/settings.rs). Each payload is one
 * string of JSON. Channel names stay under the 20 characters the 1.8 server allows.
 *
 * Version 2 added the engine world's channels (hit, signals, action, party) and the hello's
 * {@code engine} and {@code mod_version} fields. The Smalldungeons server never sees any of them:
 * to a server that says it speaks an older version, the client answers in that version
 * ({@link #wireVersion}), so it gets exactly the messages it got before.
 */
public final class Protocol {
    public static final int VERSION = 2;
    public static final Gson GSON = new Gson();
    private static final int MAX_JSON = 1 << 20;
    /** Serverbound messages from other players are small; anything bigger is refused unread. */
    private static final int MAX_SERVERBOUND_JSON = 1 << 14;

    private Protocol() {}

    /** The version to put in messages to the server we are on: ours, or the server's if it is older. */
    public static int wireVersion() {
        Catalog catalog = RustclearMod.catalog;
        return catalog != null && !catalog.engine && catalog.version > 0 ? Math.min(VERSION, catalog.version) : VERSION;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rustclear", path);
    }

    /** One JSON string each way, up to [max] characters. */
    private static <T> StreamCodec<RegistryFriendlyByteBuf, T> jsonCodec(Function<String, T> make, Function<T, String> body, int max) {
        return StreamCodec.of((buf, msg) -> buf.writeUtf(body.apply(msg), max), buf -> make.apply(buf.readUtf(max)));
    }

    /** Server to client on join: the room catalog. Answer with a {@code join} settings message. */
    public record Hello(String json) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(id("hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = jsonCodec(Hello::new, Hello::json, MAX_JSON);

        @Override
        public Type<Hello> type() {
            return TYPE;
        }
    }

    /** Server to client: the result of applying settings. */
    public record Status(String json) implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(id("status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Status> CODEC = jsonCodec(Status::new, Status::json, MAX_JSON);

        @Override
        public Type<Status> type() {
            return TYPE;
        }
    }

    /**
     * The leaderboard, both ways on {@code rustclear:board}: the client sends {@link BoardRequest}, the
     * server answers with a {@link BoardMessage}, and pushes one to everyone when a run is recorded.
     */
    public record Board(String json) implements CustomPacketPayload {
        public static final Type<Board> TYPE = new Type<>(id("board"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Board> CODEC = jsonCodec(Board::new, Board::json, MAX_JSON);

        @Override
        public Type<Board> type() {
            return TYPE;
        }

        /** Asks for a category's board ("solo", "2 players", ...), or the player's party size when null. */
        public static Board request(String category) {
            return new Board(GSON.toJson(new BoardRequest(wireVersion(), category)));
        }
    }

    /** Client to server: settings and what to do with them. */
    public record Settings(String json) implements CustomPacketPayload {
        public static final Type<Settings> TYPE = new Type<>(id("settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Settings> CODEC = jsonCodec(Settings::new, Settings::json, MAX_JSON);

        @Override
        public Type<Settings> type() {
            return TYPE;
        }

        /** {@code action}: "join" (answer to the hello), "apply" (next dungeon) or "generate" (now). */
        public static Settings of(String action, DungeonSettings settings) {
            Catalog catalog = RustclearMod.catalog;
            // The Rust server gets exactly the fields it always did.
            DungeonSettings body = catalog != null && catalog.engine ? settings : settings.forRustServer();
            return new Settings(GSON.toJson(new Message(wireVersion(), action, RustclearMod.version(), body)));
        }
    }

    // ---------------------------------------------------------------- engine world only (version 2)

    /**
     * Client to the engine world's host: a left click on a block ({@link HitMessage}). The host's own
     * hits skip the wire (EngineServer.clientHit); guests' adventure-mode hits never reach the server
     * any other way.
     */
    public record Hit(String json) implements CustomPacketPayload {
        public static final Type<Hit> TYPE = new Type<>(id("hit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hit> CODEC = jsonCodec(Hit::new, Hit::json, MAX_SERVERBOUND_JSON);

        @Override
        public Type<Hit> type() {
            return TYPE;
        }

        public static Hit of(int x, int y, int z) {
            return new Hit(GSON.toJson(new HitMessage(x, y, z)));
        }
    }

    /**
     * Engine host to a guest: replay a Hypixel signal on your own client ({@link SignalMessage}).
     * Only the kind crosses the wire; the guest's mod builds the Hypixel Mod API packets itself, as
     * those can never be encoded (see HypixelPacketGuard).
     */
    public record Signals(String json) implements CustomPacketPayload {
        public static final Type<Signals> TYPE = new Type<>(id("signals"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Signals> CODEC = jsonCodec(Signals::new, Signals::json, MAX_SERVERBOUND_JSON);

        @Override
        public Type<Signals> type() {
            return TYPE;
        }

        public static Signals of(String kind) {
            return new Signals(GSON.toJson(new SignalMessage(kind)));
        }
    }

    /** Client to the engine world's host: practice a room, or save / reset the hotbar ({@link ActionMessage}). */
    public record Action(String json) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(id("action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = jsonCodec(Action::new, Action::json, MAX_SERVERBOUND_JSON);

        @Override
        public Type<Action> type() {
            return TYPE;
        }

        public static Action practice(String room, boolean mobs) {
            ActionMessage m = new ActionMessage();
            m.action = "practice";
            m.room = room;
            m.mobs = mobs;
            return new Action(GSON.toJson(m));
        }

        /** A left click holding a shortbow (guests' only way to say so; a click on air sends nothing else). */
        public static Action leftClick() {
            ActionMessage m = new ActionMessage();
            m.action = "leftclick";
            return new Action(GSON.toJson(m));
        }

        /** The drop key in the Rustclear world: the class ability (Ctrl+Q) or, [ultimate], the ultimate (Q). */
        public static Action classAbility(boolean ultimate) {
            ActionMessage m = new ActionMessage();
            m.action = "class";
            m.ultimate = ultimate;
            return new Action(GSON.toJson(m));
        }

        public static Action hotbar(boolean save) {
            ActionMessage m = new ActionMessage();
            m.action = "hotbar";
            m.save = save;
            return new Action(GSON.toJson(m));
        }
    }

    /**
     * Client to the engine world's host: the player's Kit editor kit (engine.Kit's JSON), sent on
     * joining and on every save. The host checks it against its own item list.
     */
    public record Kit(String json) implements CustomPacketPayload {
        public static final Type<Kit> TYPE = new Type<>(id("kit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Kit> CODEC = jsonCodec(Kit::new, Kit::json, MAX_SERVERBOUND_JSON);

        @Override
        public Type<Kit> type() {
            return TYPE;
        }
    }

    /**
     * Client to the engine world's host: the player's real SkyBlock inventory (engine.SkyblockImport),
     * as one {"part":"kit","kit":..,"pages":n} and then n {"part":"page","page":..}, each under the
     * vanilla serverbound size. Sent after reading their Hypixel profile and on joining.
     */
    public record Skyblock(String json) implements CustomPacketPayload {
        public static final Type<Skyblock> TYPE = new Type<>(id("skyblock"));
        /** Under the 32 KiB vanilla serverbound payload limit, with room for the length prefix. */
        public static final int MAX = 30_000;
        public static final StreamCodec<RegistryFriendlyByteBuf, Skyblock> CODEC = jsonCodec(Skyblock::new, Skyblock::json, MAX);

        @Override
        public Type<Skyblock> type() {
            return TYPE;
        }
    }

    /**
     * The party, both ways on {@code rustclear:party}: the client sends {@link PartyRequest}, the
     * host answers everyone with a {@link PartyState} whenever it changes.
     */
    public record Party(String json) implements CustomPacketPayload {
        public static final Type<Party> TYPE = new Type<>(id("party"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Party> CODEC = jsonCodec(Party::new, Party::json, MAX_JSON);

        @Override
        public Type<Party> type() {
            return TYPE;
        }

        public static Party request(String action, String target) {
            PartyRequest r = new PartyRequest();
            r.action = action;
            r.target = target;
            return new Party(GSON.toJson(r));
        }

        public static Party pickClass(String dungeonClass) {
            PartyRequest r = new PartyRequest();
            r.action = "class";
            r.dungeonClass = dungeonClass;
            return new Party(GSON.toJson(r));
        }

        public static Party request(String action, int value) {
            PartyRequest r = new PartyRequest();
            r.action = action;
            r.value = value;
            return new Party(GSON.toJson(r));
        }

        public static Party request(String action, String target, String mode) {
            PartyRequest r = new PartyRequest();
            r.action = action;
            r.target = target;
            r.mode = mode;
            return new Party(GSON.toJson(r));
        }
    }

    record Message(int version, String action, @SerializedName("mod_version") String modVersion, DungeonSettings settings) {}

    record BoardRequest(int version, String category) {}

    public record HitMessage(int x, int y, int z) {}

    public record SignalMessage(String kind) {}

    public static final class ActionMessage {
        public int version = VERSION;
        /** "practice", "hotbar", "leftclick" or "class". */
        public String action;
        public String room;
        public boolean mobs;
        public boolean save;
        public boolean ultimate;
    }

    public static final class PartyRequest {
        public int version = VERSION;
        /**
         * "state", "ready", "class"; leader only: "invite", "kick", "leader", "open", "close",
         * "mode", "size", "target", "start", "rematch", "lobby".
         */
        public String action;
        /** The player the action is about (a name), or null. */
        public String target;
        /** For "mode": PARTY or DUEL_NVN (DUEL_1V1, DUEL_3V3, ...). */
        public String mode;
        /** For "size": the party's player limit; for "target": the duel's score. */
        public int value;
        /** For "class": the class picked (DungeonClass's name). */
        @SerializedName("dungeon_class")
        public String dungeonClass;
    }

    public static final class PartyMember {
        public String name;
        public boolean leader;
        public boolean ready;
        /** The class they picked (DungeonClass's name), or null before they pick. */
        @SerializedName("dungeon_class")
        public String dungeonClass;
        /** Which duel team the member races on, or null in a party. */
        public String team;
    }

    public static final class TeamProgress {
        public String team;
        public List<String> players = List.of();
        @SerializedName("rooms_cleared")
        public int roomsCleared;
        @SerializedName("total_rooms")
        public int totalRooms;
        @SerializedName("secrets_found")
        public int secretsFound;
        @SerializedName("total_secrets")
        public int totalSecrets;
        public long ticks;
        /** The team's dungeon score now. */
        public int score;
        /** Whether the team reached the duel's target score ([ticks] is then when). */
        public boolean finished;
    }

    public static final class PartyState {
        public int version = VERSION;
        public String leader;
        public List<PartyMember> members = List.of();
        public int max;
        /** How many players the party (not a duel) may hold: 5 by default, up to 50. */
        public int size = 5;
        /** The score a duel races to (270 or 300). */
        public int target = 270;
        public boolean open;
        public String mode = "PARTY";
        /** "lobby", "countdown", "running" or "finished". */
        public String phase = "lobby";
        /** Whether the player this was sent to leads the party. */
        public boolean you;
        /** Duel progress, one entry per team, or empty in a party. */
        public List<TeamProgress> teams = List.of();
        /** The winning team once a duel is over, or null. */
        public String winner;
        /** How the winner won ("reached 271 score in 4:12.35", "won by forfeit"), or null. */
        @SerializedName("win_reason")
        public String winReason;
        /** Set when the party is ending (the host left), with why. */
        public String closed;
    }

    public static final class Room {
        public String name;
        public String type;
        public String shape;
        public int secrets;
    }

    public static final class Catalog {
        public int version;
        public boolean required;
        public List<Room> rooms = List.of();
        public List<Room> puzzles = List.of();
        public DungeonSettings current;
        /** How common each secret total is in random dungeons (index = secrets, peak = 1). */
        @SerializedName("secret_histogram")
        public float[] secretHistogram;
        /** True from the mod's own singleplayer world (the host's engine), never from the Rust server. */
        public boolean engine;
        /** The host's mod version, so a guest with another build can be told before anything breaks. */
        @SerializedName("mod_version")
        public String modVersion;
    }

    public static final class StatusMessage {
        public boolean ok;
        public String message = "";
        public boolean ranked;
        public DungeonSettings settings;
        @SerializedName("total_secrets")
        public Integer totalSecrets;
        /**
         * Whether this player may change the dungeon (engine world: the party leader). Null from the
         * Rust server, where everyone sets their own.
         */
        public Boolean leader;
    }

    /** One ranked run. */
    public static final class Entry {
        public int rank;
        public List<String> players = List.of();
        /** Game ticks (50 ms each) from the start to the blood door opening. */
        public long ticks;
        public int score;
        @SerializedName("secrets_found")
        public int secretsFound;
        @SerializedName("total_secrets")
        public int totalSecrets;
        /** Unix seconds. */
        @SerializedName("finished_at")
        public long finishedAt;
    }

    public static final class BoardMessage {
        public int version;
        public String category = "solo";
        public List<String> categories = List.of();
        public List<Entry> entries = List.of();
        /** The player's own best run in this category, or null. */
        @SerializedName("personal_best")
        public Entry personalBest;
        /** Whether the dungeon being played now can make the board, and why not. */
        @SerializedName("current_run_ranked")
        public boolean currentRunRanked;
        @SerializedName("current_run_reason")
        public String currentRunReason;
        /** Set when the server pushes the board because this run was just recorded. */
        @SerializedName("just_finished")
        public Entry justFinished;
    }
}
