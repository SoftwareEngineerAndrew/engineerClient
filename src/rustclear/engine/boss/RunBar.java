package net.rustclear.mod.engine.boss;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.rustclear.mod.engine.EngineItems;
import net.rustclear.mod.engine.SimLog;

/**
 * The one boss bar of a run (menus-ui.md "Boss bars"): one id from Mort's first line to the end,
 * added once, then only name + progress + style triples on that id.
 * - In the clear it is the yellow compass, each viewer's own name, re-sent every 4 ticks (3-5
 *   measured) until the blood door opens; then it holds.
 * - From the Watcher on it is a red PROGRESS bar named "§c§lThe Watcher", "§c§lMaxor", ..., whose
 *   values the server re-sends every 20 ticks (they only change on those ticks; the smooth motion
 *   players see is the client's lerp).
 */
public final class RunBar {
    /** Compass re-send period (gaps 4: 66, 3: 32, 5: 32 of 135 in 408abb). */
    public static final int COMPASS_PERIOD = 4;
    /** Boss re-send period. */
    public static final int BOSS_PERIOD = 20;

    private enum Mode { HIDDEN, COMPASS, HOLD, BOSS }

    private final UUID id = UUID.randomUUID();
    private final Bar state = new Bar(id);
    private final Set<UUID> viewers = new HashSet<>();
    private Mode mode = Mode.HIDDEN;
    private Function<ServerPlayer, String> compassName;
    private long ticks;
    /** The tick the boss mode's 20-tick grid counts from. */
    private long bossSince;
    /**
     * The Watcher's bar is each player's own: it replaces their held compass on the first 20-tick
     * re-send after they walk into the Blood room (166 camps: 0-20 ticks after entering, on the
     * door + 1 + 20k grid). Null: everyone sees the boss bar.
     */
    private Set<UUID> admitted;

    private static final class Bar extends BossEvent {
        Bar(UUID id) {
            super(id, Component.empty(), BossBarColor.YELLOW, BossBarOverlay.PROGRESS);
        }
    }

    /** The compass, from now (Mort's first line): [nameFor] draws each viewer's own. */
    public void compass(Function<ServerPlayer, String> nameFor) {
        compassName = nameFor;
        mode = Mode.COMPASS;
        state.setColor(BossEvent.BossBarColor.YELLOW);
        state.setProgress(1f);
    }

    /** The blood door opened: the compass stops updating and the bar stays as it was. */
    public void hold() {
        if (mode == Mode.COMPASS) {
            mode = Mode.HOLD;
        }
    }

    /**
     * A boss's bar from now: "§c§l" + [name], red. The name goes out at once; progress follows on
     * the 20-tick grid counted from here.
     */
    public void boss(String name, float progress) {
        admitted = null;
        boolean renamed = mode != Mode.BOSS || !state.getName().getString().equals(name);
        state.setName(EngineItems.styled("§c§l" + name));
        state.setColor(BossEvent.BossBarColor.RED);
        state.setProgress(clamp(progress));
        mode = Mode.BOSS;
        if (renamed) {
            bossSince = ticks;
            for (ServerPlayer p : viewers()) {
                triple(p, state.getName());
            }
        }
    }

    /**
     * The Watcher's bar from now (the tick after the blood door): red "§c§l" + [name], but each
     * player keeps the held compass until [admit]ted. Re-sent every 20 ticks from this tick on.
     */
    public void bossGated(String name, float progress) {
        state.setName(EngineItems.styled("§c§l" + name));
        state.setColor(BossEvent.BossBarColor.RED);
        state.setProgress(clamp(progress));
        mode = Mode.BOSS;
        bossSince = ticks;
        admitted = new HashSet<>();
    }

    /** [player] sees the gated boss bar from the next re-send (this tick, when it is one). */
    public void admit(ServerPlayer player) {
        if (admitted != null) {
            admitted.add(player.getUUID());
        }
    }

    public boolean admitted(ServerPlayer player) {
        return admitted == null || admitted.contains(player.getUUID());
    }

    /** The boss's health fraction (or mobs left), shown from the next 20-tick re-send. */
    public void progress(float progress) {
        state.setProgress(clamp(progress));
    }

    public float progress() {
        return state.getProgress();
    }

    private List<ServerPlayer> players = List.of();

    private List<ServerPlayer> viewers() {
        return players;
    }

    /** Each tick with the run's players: newcomers get the bar, and the mode's re-sends go out. */
    public void tick(List<ServerPlayer> now) {
        players = now;
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer p : now) {
            present.add(p.getUUID());
            if (mode != Mode.HIDDEN && viewers.add(p.getUUID())) {
                Bar first = new Bar(id);
                first.setName(nameFor(p));
                boolean held = mode == Mode.BOSS && !admitted(p);
                first.setProgress(held ? 1f : state.getProgress());
                first.setColor(held ? BossEvent.BossBarColor.YELLOW : state.getColor());
                p.connection.send(ClientboundBossEventPacket.createAddPacket(first));
                log("add");
            }
        }
        viewers.retainAll(present);
        switch (mode) {
            case COMPASS -> {
                if (ticks % COMPASS_PERIOD == 0) {
                    for (ServerPlayer p : now) {
                        triple(p, nameFor(p));
                    }
                }
            }
            case BOSS -> {
                if ((ticks - bossSince) % BOSS_PERIOD == 0 && (ticks != bossSince || admitted != null)) {
                    for (ServerPlayer p : now) {
                        if (admitted == null || admitted.contains(p.getUUID())) {
                            triple(p, state.getName());
                        }
                    }
                }
            }
            default -> {
            }
        }
        ticks++;
    }

    private Component nameFor(ServerPlayer p) {
        boolean compass = mode == Mode.COMPASS || mode == Mode.HOLD
            || mode == Mode.BOSS && admitted != null && !admitted.contains(p.getUUID());
        return compass && compassName != null ? EngineItems.styled(compassName.apply(p)) : state.getName();
    }

    /** Name, progress and style, in Hypixel's order. */
    private void triple(ServerPlayer p, Component name) {
        Bar one = new Bar(id);
        one.setName(name);
        one.setProgress(state.getProgress());
        one.setColor(state.getColor());
        p.connection.send(ClientboundBossEventPacket.createUpdateNamePacket(one));
        p.connection.send(ClientboundBossEventPacket.createUpdateProgressPacket(one));
        p.connection.send(ClientboundBossEventPacket.createUpdateStylePacket(one));
    }

    private void log(String op) {
        if (SimLog.enabled()) {
            SimLog.event("bar_" + op, "mode", mode.name());
        }
    }

    /** Takes the bar off every viewer's screen. */
    public void remove(List<ServerPlayer> now) {
        for (ServerPlayer p : now) {
            if (viewers.contains(p.getUUID())) {
                p.connection.send(ClientboundBossEventPacket.createRemovePacket(id));
            }
        }
        viewers.clear();
        mode = Mode.HIDDEN;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
