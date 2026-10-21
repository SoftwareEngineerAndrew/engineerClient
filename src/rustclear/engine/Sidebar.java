package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * The SkyBlock-style sidebar (main.rs's per-tick sidebar lines), for the players of one dungeon.
 *
 * Laid out the way Hypixel (and Smalldungeons scoreboard.rs) does it, because that is what client
 * mods read: every line is a team "team_N" whose prefix holds the text, with one invisible member
 * ("§1§r", "§2§r", ...) that has a score on the "SBScoreboard" objective. Skyblocker, Firmament,
 * SkyHanni and Odin all take the sidebar text from the teams' prefix and suffix (Odin straight off
 * the team packets), not from score display names, and Odin calls the world SkyBlock once it sees
 * an objective named "SBScoreboard".
 *
 * The lines live on a scoreboard of the sidebar's own, not the server's, and go only to the
 * players in [level], so two duel teams in two dimensions each see their own. A player new to the
 * level first has those names cleared (whatever another dungeon left), then gets every line.
 */
public final class Sidebar {
    private static final String OBJECTIVE = "SBScoreboard";
    /** More teams than the sidebar ever has lines, so stale ones from another dungeon are cleared. */
    private static final int MAX_LINES = 32;

    private final ServerLevel level;
    /** The one player this sidebar is for (Hypixel's lists your teammates, not you), or null: everyone in [level]. */
    private final UUID viewer;
    private final Scoreboard scoreboard = new Scoreboard();
    private final Objective objective;
    private List<String> shown = List.of();
    /** Players who have this sidebar on their screen. */
    private final Set<UUID> viewers = new HashSet<>();

    public Sidebar(ServerLevel level) {
        this(level, null);
    }

    /** A sidebar only [viewer] sees. */
    public Sidebar(ServerLevel level, UUID viewer) {
        this.level = level;
        this.viewer = viewer;
        this.objective = scoreboard.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY, EngineItems.text("§e§lSKYBLOCK"),
            ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
        scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
    }

    /** Line 0 is the title; the rest are shown top to bottom. */
    private List<ServerPlayer> players() {
        if (viewer == null) {
            return level.players();
        }
        List<ServerPlayer> out = new ArrayList<>(1);
        for (ServerPlayer p : level.players()) {
            if (p.getUUID().equals(viewer)) {
                out.add(p);
            }
        }
        return out;
    }

    public void show(List<String> lines) {
        List<ServerPlayer> players = players();
        syncViewers(players);
        if (lines.equals(shown)) {
            return;
        }
        List<Packet<?>> out = new ArrayList<>();
        if (!lines.isEmpty() && (shown.isEmpty() || !lines.get(0).equals(shown.get(0)))) {
            objective.setDisplayName(EngineItems.text(lines.get(0)));
            out.add(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_CHANGE));
        }
        int count = Math.min(lines.size() - 1, MAX_LINES);
        int oldCount = Math.max(0, Math.min(shown.size() - 1, MAX_LINES));
        boolean resized = count != oldCount;
        if (resized) {
            // scoreboard.rs write_update: on a size change every line is taken down and put back.
            for (int i = 0; i < oldCount; i++) {
                out.add(new ClientboundResetScorePacket(holder(i).getScoreboardName(), OBJECTIVE));
                PlayerTeam team = scoreboard.getPlayerTeam(teamName(oldCount - i));
                if (team != null) {
                    out.add(ClientboundSetPlayerTeamPacket.createRemovePacket(team));
                    scoreboard.removePlayerTeam(team);
                }
            }
        }
        for (int i = 0; i < count; i++) {
            String line = lines.get(i + 1);
            if (!resized && line.equals(shown.get(i + 1))) {
                continue;
            }
            String name = teamName(count - i);
            PlayerTeam team = scoreboard.getPlayerTeam(name);
            boolean created = team == null;
            if (created) {
                team = scoreboard.addPlayerTeam(name);
            }
            team.setPlayerPrefix(EngineItems.text(line));
            team.setPlayerSuffix(Component.empty());
            String member = holder(i).getScoreboardName();
            if (scoreboard.getPlayersTeam(member) != team) {
                scoreboard.addPlayerToTeam(member, team);
            }
            out.add(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, created));
            out.add(new ClientboundSetScorePacket(member, OBJECTIVE, count - i, Optional.empty(), Optional.empty()));
        }
        shown = new ArrayList<>(lines);
        SimLog.sidebar(level, viewer, lines);
        for (ServerPlayer player : players) {
            out.forEach(player.connection::send);
        }
    }

    /** Gives players new to the level the whole sidebar, and forgets ones who left it. */
    private void syncViewers(List<ServerPlayer> players) {
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer player : players) {
            present.add(player.getUUID());
            if (viewers.add(player.getUUID())) {
                clear(player);
                snapshot(player);
            }
        }
        viewers.retainAll(present);
    }

    /** Takes the sidebar's names off [player]'s screen; removing what isn't there does nothing. */
    private void clear(ServerPlayer player) {
        player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
        Scoreboard scratch = new Scoreboard();
        for (int i = 0; i <= MAX_LINES; i++) {
            PlayerTeam existing = scoreboard.getPlayerTeam(teamName(i));
            player.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(
                existing != null ? existing : scratch.addPlayerTeam(teamName(i))));
        }
    }

    /** Every line as it stands, as one player's join snapshot. */
    private void snapshot(ServerPlayer player) {
        player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
        int count = Math.max(0, Math.min(shown.size() - 1, MAX_LINES));
        for (int i = 0; i < count; i++) {
            PlayerTeam team = scoreboard.getPlayerTeam(teamName(count - i));
            if (team == null) {
                continue;
            }
            player.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
            player.connection.send(new ClientboundSetScorePacket(holder(i).getScoreboardName(), OBJECTIVE, count - i,
                Optional.empty(), Optional.empty()));
        }
    }

    /** scoreboard.rs hide_name: every character behind a §, then §r, so the member's name draws as nothing. */
    private static ScoreHolder holder(int line) {
        StringBuilder name = new StringBuilder();
        for (char c : Integer.toString(line + 1).toCharArray()) {
            name.append('§').append(c);
        }
        return ScoreHolder.forNameOnly(name.append("§r").toString());
    }

    private static String teamName(int index) {
        return "team_" + index;
    }

    /**
     * Sends every line again, without the "⏣ The Catacombs" line until the next [show]: some mods
     * (SkyHanni) only learn the floor from a line that newly appears, as after a server switch.
     */
    public void resend() {
        List<String> lines = new ArrayList<>(shown);
        lines.removeIf(line -> line.contains("⏣"));
        shown = List.of();
        show(lines);
    }

    /** Takes the sidebar off its players' screens. */
    public void remove() {
        for (ServerPlayer player : players()) {
            if (viewers.contains(player.getUUID())) {
                clear(player);
            }
        }
        viewers.clear();
    }
}
