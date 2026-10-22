package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.rustclear.mod.Protocol;

/**
 * The party in the host's Rustclear world: everyone connected to it, at most [max] (by default
 * [DEFAULT_SIZE]; the leader can raise it to [MAX_SIZE]). The host leads at first; the leader can
 * hand that over, invite, kick, open or close the party, pick the mode (a party, or an NvN duel and
 * the score it races to) and start the run. Only the leader changes the dungeon
 * (EngineServer.isLeader). Lives on the host's server thread; guests learn of it through
 * rustclear:party ({@link Protocol.PartyState}), sent whenever what they would see changes.
 */
final class Party {
    static final int DEFAULT_SIZE = 5;
    /** The most players a party can be set to (and so the host's server allows). */
    static final int MAX_SIZE = 50;
    /** The most players per team in a duel (20v20). */
    static final int MAX_TEAM = 20;
    /** The scores a duel can race to: Hypixel's S and S+. */
    static final int[] TARGETS = {270, 300};

    /** A party (teamSize 0), or a duel of two teams of [teamSize]: "PARTY" or "DUEL_3V3" on the wire. */
    record Mode(int teamSize) {
        static final Mode PARTY = new Mode(0);
        static final Mode DUEL_1V1 = new Mode(1);
        private static final Pattern DUEL = Pattern.compile("DUEL_(\\d{1,2})V(\\d{1,2})");

        boolean duel() {
            return teamSize > 0;
        }

        /** How many players a duel is for. */
        int size() {
            return teamSize * 2;
        }

        String name() {
            return duel() ? "DUEL_" + teamSize + "V" + teamSize : "PARTY";
        }

        /** "Party", "1v1", "3v3". */
        String label() {
            return duel() ? teamSize + "v" + teamSize : "Party";
        }

        static Mode parse(String name) {
            if (name == null) {
                return null;
            }
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.equals("PARTY")) {
                return PARTY;
            }
            Matcher m = DUEL.matcher(upper);
            if (!m.matches() || !m.group(1).equals(m.group(2))) {
                return null;
            }
            int n = Integer.parseInt(m.group(1));
            return n >= 1 && n <= MAX_TEAM ? new Mode(n) : null;
        }
    }

    /** [score] made a valid duel target, or the first one. */
    static int target(int score) {
        for (int t : TARGETS) {
            if (t == score) {
                return t;
            }
        }
        return TARGETS[0];
    }

    private final MinecraftServer server;
    private UUID leader;
    private boolean open;
    private Mode mode = Mode.PARTY;
    /** How many players a party (not a duel) may hold. */
    private int size = DEFAULT_SIZE;
    /** The score a duel races to. */
    private int target = TARGETS[0];
    /** Lower-case names the leader invited into a closed party. */
    private final Set<String> invited = new LinkedHashSet<>();
    /** What each member was last sent, so the state only goes out when it changes. */
    private String lastSent = "";

    Party(MinecraftServer server, UUID host) {
        this.server = server;
        this.leader = host;
    }

    boolean isLeader(ServerPlayer player) {
        return player.getUUID().equals(leader);
    }

    UUID leader() {
        return leader;
    }

    Mode mode() {
        return mode;
    }

    void setMode(Mode next) {
        mode = next;
    }

    int size() {
        return size;
    }

    void setSize(int next) {
        size = Math.max(1, Math.min(MAX_SIZE, next));
    }

    int target() {
        return target;
    }

    void setTarget(int next) {
        target = target(next);
    }

    /** How many players may be in the party now: a duel's exact count, else the party's size. */
    int max() {
        return mode.duel() ? mode.size() : size;
    }

    boolean isOpen() {
        return open;
    }

    void setOpen(boolean next) {
        open = next;
    }

    /** Everyone in the party: the players on the host's server, the leader first. */
    List<ServerPlayer> members() {
        List<ServerPlayer> out = new ArrayList<>();
        ServerPlayer lead = server.getPlayerList().getPlayer(leader);
        if (lead != null) {
            out.add(lead);
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p != lead) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * May [player] join? The host always; anyone else while there is room, and in a closed
     * party only if invited. Returns why not, or null.
     */
    String refusal(ServerPlayer player, boolean host) {
        if (host) {
            return null;
        }
        // The joining player is already on the list by the time JOIN fires.
        if (server.getPlayerList().getPlayerCount() > max()) {
            return "This Rustclear party is full (" + max() + " players).";
        }
        if (!open && !invited.contains(player.getName().getString().toLowerCase(Locale.ROOT))) {
            return "This Rustclear party is private. Ask the leader to invite you.";
        }
        return null;
    }

    /** Adds [name] to the invite list (a closed party lets them in). */
    void invite(String name) {
        invited.add(name.toLowerCase(Locale.ROOT));
    }

    List<String> invited() {
        return List.copyOf(invited);
    }

    /** Makes [next] the leader. */
    void transfer(ServerPlayer next) {
        leader = next.getUUID();
        broadcast("§e" + next.getName().getString() + " §ais now the party leader.");
    }

    /** The leader left (a guest leader, as the host leaving ends everything): the host takes over. */
    void leaderLeft(UUID host) {
        leader = host;
    }

    void broadcast(String message) {
        Component text = EngineItems.text(message);
        members().forEach(p -> p.sendSystemMessage(text));
    }

    /** Sends everyone their view of the party if anything in it changed, or to all when [force]. */
    void sync(EngineRun run, Duels duels, boolean force) {
        List<ServerPlayer> members = members();
        Protocol.PartyState base = state(members, run, duels, null);
        String signature = Protocol.GSON.toJson(base) + leader;
        if (!force && signature.equals(lastSent)) {
            return;
        }
        lastSent = signature;
        for (ServerPlayer p : members) {
            send(p, state(members, run, duels, p));
        }
    }

    /** Tells everyone the party is over and why, ahead of their disconnect. */
    void close(String why) {
        Protocol.PartyState state = new Protocol.PartyState();
        state.closed = why;
        for (ServerPlayer p : members()) {
            // The host is the one leaving; only guests need telling.
            if (!server.isSingleplayerOwner(p.nameAndId())) {
                send(p, state);
            }
        }
    }

    private static void send(ServerPlayer player, Protocol.PartyState state) {
        if (ServerPlayNetworking.canSend(player, Protocol.Party.TYPE)) {
            ServerPlayNetworking.send(player, new Protocol.Party(Protocol.GSON.toJson(state)));
        }
    }

    private Protocol.PartyState state(List<ServerPlayer> members, EngineRun run, Duels duels, ServerPlayer viewer) {
        Protocol.PartyState state = new Protocol.PartyState();
        ServerPlayer lead = server.getPlayerList().getPlayer(leader);
        state.leader = lead == null ? null : lead.getName().getString();
        state.max = max();
        state.size = size;
        state.target = target;
        state.open = open;
        state.mode = mode.name();
        state.you = viewer != null && isLeader(viewer);
        PracticeSession practice = EngineServer.practice();
        state.practiceFloor = practice == null ? null : practice.floor().code;
        List<Protocol.PartyMember> list = new ArrayList<>();
        for (ServerPlayer p : members) {
            Protocol.PartyMember m = new Protocol.PartyMember();
            m.name = p.getName().getString();
            m.leader = p.getUUID().equals(leader);
            EngineRun theirs = duels != null && duels.runOf(p) != null ? duels.runOf(p) : run;
            m.ready = theirs != null && theirs.isReady(p.getUUID());
            m.team = duels == null ? null : duels.teamOf(p);
            DungeonClass picked = PlayerStats.picked(p);
            m.dungeonClass = picked == null ? null : picked.name();
            list.add(m);
        }
        state.members = list;
        if (duels != null) {
            state.phase = duels.phase();
            state.teams = duels.progress();
            state.winner = duels.winner();
            state.winReason = duels.reason();
        } else if (run != null) {
            state.phase = switch (run.phase()) {
                case LOBBY -> "lobby";
                case COUNTDOWN -> "countdown";
                case RUNNING -> "running";
                case FINISHED -> "finished";
            };
        }
        return state;
    }
}
