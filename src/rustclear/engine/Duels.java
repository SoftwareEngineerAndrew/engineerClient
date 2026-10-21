package net.rustclear.mod.engine;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.rustclear.mod.DungeonSettings;
import net.rustclear.mod.Protocol;

/**
 * An NvN duel (1v1, 2v2, 3v3, ...): two teams each race their own copy of one dungeon. The dungeon
 * is planned once and the same plan is built in each team's dimension, so the race is fair; both
 * teams' countdowns start on the same server tick; the first team whose score reaches [target]
 * (270 or 300) wins, as does the last team with anyone left. Only uses [EngineRun], so a boss run
 * races the same way.
 */
final class Duels {
    static final String[] TEAMS = {"A", "B"};

    private final MinecraftServer server;
    private final Party.Mode mode;
    /** The score that wins. */
    private final int target;
    /** Each player's team, in party order. */
    private final Map<UUID, String> teams = new LinkedHashMap<>();
    private final Map<String, Instance> instances = new LinkedHashMap<>();
    /** The run tick each team reached [target] on, so their clock stops there. */
    private final Map<String, Long> reachedAt = new LinkedHashMap<>();
    private String winner;
    /** Why the winner won ("finished first", "forfeit"). */
    private String reason;

    private Duels(MinecraftServer server, Party.Mode mode, int target) {
        this.server = server;
        this.mode = mode;
        this.target = target;
    }

    /**
     * Starts a duel to [target] for [members] (exactly [mode].size, leader first): teams in
     * order, unless [preferred] (a name to team, from matchmaking's premade duos) says otherwise.
     * Returns null and tells [members] why when it can't.
     */
    static Duels begin(MinecraftServer server, Party.Mode mode, int target, List<ServerPlayer> members,
                       Map<String, String> preferred, DungeonSettings settings, Random random) {
        Duels duels = new Duels(server, mode, target);
        ServerLevel a = server.getLevel(TeamDimensions.TEAM_A);
        ServerLevel b = server.getLevel(TeamDimensions.TEAM_B);
        if (a == null || b == null) {
            tell(members, "§cThis Rustclear world was made without duel dimensions. Make a new party to duel.");
            return null;
        }
        duels.instances.put("A", new Instance(a, "A"));
        duels.instances.put("B", new Instance(b, "B"));
        duels.assign(members, preferred);
        try {
            duels.build(settings, random);
        } catch (Planner.PlanException e) {
            tell(members, "§c" + e.getMessage());
            duels.instances.values().forEach(Instance::dispose);
            return null;
        }
        return duels;
    }

    private void assign(List<ServerPlayer> members, Map<String, String> preferred) {
        int perTeam = mode.teamSize();
        int[] counts = new int[TEAMS.length];
        List<ServerPlayer> unplaced = new ArrayList<>();
        for (ServerPlayer p : members) {
            String want = preferred.get(p.getName().getString());
            int index = "B".equals(want) ? 1 : "A".equals(want) ? 0 : -1;
            if (index >= 0 && counts[index] < perTeam) {
                teams.put(p.getUUID(), TEAMS[index]);
                counts[index]++;
            } else {
                unplaced.add(p);
            }
        }
        for (ServerPlayer p : unplaced) {
            int index = counts[0] <= counts[1] ? 0 : 1;
            teams.put(p.getUUID(), TEAMS[index]);
            counts[index]++;
        }
    }

    /**
     * Plans one dungeon and builds it for every team, moves everyone to their team's start, and
     * starts every countdown on this tick.
     */
    private void build(DungeonSettings settings, Random random) throws Planner.PlanException {
        RoomStore store = RoomStore.get();
        DungeonPlan plan = Planner.plan(store, settings, random);
        winner = null;
        reason = null;
        reachedAt.clear();
        for (Instance instance : instances.values()) {
            BuildTimer timer = new BuildTimer();
            instance.install(plan, store, null, -1, false, random, timer);
            instance.run().holdStart(true);
        }
        for (Map.Entry<UUID, String> entry : teams.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                moveTo(player, instances.get(entry.getValue()));
            }
        }
        for (Instance instance : instances.values()) {
            instance.run().beginCountdown();
        }
        broadcast("§6" + label() + "! §eSame dungeon for every team. First to §6" + target + " score §ewins.");
    }

    /** Sends [player] into [instance]'s dungeon, as a new dungeon to their client mods. */
    private static void moveTo(ServerPlayer player, Instance instance) {
        player.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
        instance.toSpawn(player);
        PlayerSpeed.apply(player);
        instance.run().equip(player);
        HypixelSignals.newDungeon(player, LongSets.EMPTY_SET);
    }

    /** Builds a fresh dungeon (the same for all teams) and starts again, with the same teams. */
    void rematch(DungeonSettings settings, Random random) {
        try {
            build(settings, random);
        } catch (Planner.PlanException e) {
            broadcast("§c" + e.getMessage());
        }
    }

    void tick() {
        for (Instance instance : instances.values()) {
            if (instance.run() != null) {
                instance.run().tick();
            }
        }
        for (Map.Entry<String, Instance> e : instances.entrySet()) {
            EngineRun.Progress p = e.getValue().run().progress();
            if (p.score() >= target) {
                reachedAt.putIfAbsent(e.getKey(), p.ticks());
            }
        }
        if (winner != null) {
            return;
        }
        // The first team to reach the target; on the same tick, the higher score.
        String best = null;
        EngineRun.Progress bestProgress = null;
        for (Map.Entry<String, Instance> e : instances.entrySet()) {
            EngineRun.Progress p = e.getValue().run().progress();
            if (p.score() >= target && (bestProgress == null || p.score() > bestProgress.score())) {
                best = e.getKey();
                bestProgress = p;
            }
        }
        if (best != null) {
            finish(best, "reached " + bestProgress.score() + " score in " + formatTime(bestProgress.ticks()));
            return;
        }
        // A team with nobody left forfeits once the race is on.
        List<String> present = new ArrayList<>();
        for (Map.Entry<String, Instance> e : instances.entrySet()) {
            if (!e.getValue().players().isEmpty()) {
                present.add(e.getKey());
            }
        }
        if (present.size() == 1 && server.getTickCount() % 20 == 0) {
            finish(present.get(0), "won by forfeit");
        }
    }

    private void finish(String team, String why) {
        winner = team;
        reason = why;
        broadcast("§6§lTEAM " + team + " WINS! §e" + names(team) + " " + why + ".");
    }

    // ---------------------------------------------------------------- lookups

    String teamOf(ServerPlayer player) {
        return teams.get(player.getUUID());
    }

    /** The instance [player] races in, or null if they aren't in this duel. */
    Instance instanceOf(ServerPlayer player) {
        String team = teams.get(player.getUUID());
        return team == null ? null : instances.get(team);
    }

    /** The instance whose level is [level], or null. */
    Instance instanceAt(ServerLevel level) {
        for (Instance instance : instances.values()) {
            if (instance.level == level) {
                return instance;
            }
        }
        return null;
    }

    EngineRun runOf(ServerPlayer player) {
        Instance instance = instanceOf(player);
        return instance == null ? null : instance.run();
    }

    String winner() {
        return winner;
    }

    String phase() {
        if (winner != null) {
            return "finished";
        }
        boolean counting = instances.values().stream().anyMatch(i -> i.run() != null && i.run().phase() == EngineRun.Phase.COUNTDOWN);
        return counting ? "countdown" : "running";
    }

    /** Each team's progress, for every duel player's HUD and results. */
    List<Protocol.TeamProgress> progress() {
        List<Protocol.TeamProgress> out = new ArrayList<>();
        for (Map.Entry<String, Instance> e : instances.entrySet()) {
            Protocol.TeamProgress t = new Protocol.TeamProgress();
            t.team = e.getKey();
            t.players = namesList(e.getKey());
            EngineRun run = e.getValue().run();
            if (run != null) {
                EngineRun.Progress p = run.progress();
                t.roomsCleared = p.roomsCleared();
                t.totalRooms = p.totalRooms();
                t.secretsFound = p.secretsFound();
                t.totalSecrets = p.totalSecrets();
                Long reached = reachedAt.get(e.getKey());
                t.ticks = reached != null ? reached : p.ticks();
                t.score = p.score();
                t.finished = reached != null;
            }
            out.add(t);
        }
        return out;
    }

    String reason() {
        return reason;
    }

    String label() {
        return mode.label();
    }

    int target() {
        return target;
    }

    /** Every team's sidebar and tab list again (after client mods reset). */
    void resendHud() {
        for (Instance instance : instances.values()) {
            if (instance.run() != null) {
                instance.run().resendHud();
            }
        }
    }

    /** Ends the duel: every team's run is removed. The caller sends the players back to the party. */
    void dispose() {
        instances.values().forEach(Instance::dispose);
    }

    private List<String> namesList(String team) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<UUID, String> e : teams.entrySet()) {
            if (e.getValue().equals(team)) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                names.add(p == null ? "(left)" : p.getName().getString());
            }
        }
        return names;
    }

    private String names(String team) {
        return String.join(" & ", namesList(team));
    }

    private void broadcast(String message) {
        Component text = EngineItems.text(message);
        for (UUID id : teams.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                p.sendSystemMessage(text);
            }
        }
    }

    /** m:ss.cc from game ticks, as the leaderboard shows times. */
    private static String formatTime(long ticks) {
        long centis = ticks * 5;
        return String.format(java.util.Locale.ROOT, "%d:%02d.%02d", centis / 6000, centis / 100 % 60, centis % 100);
    }

    private static void tell(List<ServerPlayer> players, String message) {
        Component text = EngineItems.text(message);
        players.forEach(p -> p.sendSystemMessage(text));
    }
}
