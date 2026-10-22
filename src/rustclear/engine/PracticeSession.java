package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.rustclear.mod.DungeonSettings;
import net.rustclear.mod.Floor;

/**
 * A practice the ADS Practice page asked for (PracticeSpec), in the party's dungeon: it builds the
 * dungeon for it, keeps its seed for /rs, and hears the run's hooks for its timers. EngineServer
 * holds the one in progress; a new dungeon, a duel or the world closing ends it. When the world
 * opens with one nobody is in it yet, so what needs a player waits for the host's join ([onJoin]),
 * and a rebuild with the host already there runs that at once. Solo only: it never shares the
 * world. Server thread only.
 */
final class PracticeSession implements DungeonRun.PracticeHooks {
    private static final Random RANDOM = new Random();

    final PracticeSpec spec;
    /** The seed this session's map is built from (FULL_CLEAR), or null. */
    private Long seed;
    /** The run the last build installed. */
    private DungeonRun run;
    /** Chat lines for the host once they're in, from the last build. */
    private final List<Component> greeting = new ArrayList<>();
    /** Whether the host has been set up for the last build ([onJoin]). */
    private boolean joined;

    PracticeSession(PracticeSpec spec) {
        this.spec = spec;
        this.seed = spec.seed();
    }

    /** The seed of the map being practised, or null when the mode has none. */
    Long seed() {
        return seed;
    }

    /**
     * Builds the party's dungeon for the spec, everyone present sent to its start: the same map
     * (and seed) again, or with [fresh] a new one. Throws with the reason when it can't.
     */
    void build(MinecraftServer s, boolean fresh) throws Planner.PlanException {
        joined = false;
        greeting.clear();
        switch (spec.kind()) {
            case FULL_CLEAR -> buildFullClear(fresh);
            case ROOM -> buildRoom(s, fresh);
            default -> throw new Planner.PlanException(spec.kind() + " practice isn't available yet.");
        }
    }

    /** The host is in this build's world (they joined, or the build ran with them there). Once per build. */
    void onJoin(ServerPlayer host) {
        if (joined) {
            return;
        }
        joined = true;
        greeting.forEach(host::sendSystemMessage);
    }

    /** [onJoin] for the host, if they're already in the world. */
    void joinPresent(MinecraftServer s) {
        for (ServerPlayer player : s.getPlayerList().getPlayers()) {
            if (EngineServer.isHost(player)) {
                onJoin(player);
            }
        }
    }

    // ---------------------------------------------------------------- the modes

    /**
     * A whole run of a seeded map, Mort and the countdown as normal (the solo Ready starts it, and
     * run timers key on Mort's first line). Planned fully random on the spec's floor: the leader's
     * room, puzzle and secret filters would make a seed mean a different map.
     */
    private void buildFullClear(boolean fresh) throws Planner.PlanException {
        installSeeded(fresh);
        greeting.add(EngineItems.text("§aFull clear practice on " + spec.floor().code + "."));
        greeting.add(seedLine("§a[Retry]", "§e[New map]"));
    }

    /** The named room alone, entering through the spec's door; /rs keeps the door, /rs new rolls a random one again. */
    private void buildRoom(MinecraftServer s, boolean fresh) {
        Instance in = EngineServer.partyInstance();
        if (!fresh && in.practicePick != null && in.practiceRoute != null) {
            EngineServer.restartPractice(s);
        } else {
            greeting.add(EngineItems.text(EngineServer.practiceRoom(s, spec.room(), spec.door(), null, spec.floor())));
        }
        run = in.dungeon();
    }

    /** Plans and installs this session's seeded map ([fresh]: a new seed first). */
    private void installSeeded(boolean fresh) throws Planner.PlanException {
        if (fresh || seed == null) {
            seed = RANDOM.nextLong();
        }
        EngineServer.setLastSeed(seed);
        BuildTimer timer = new BuildTimer();
        RoomStore store = RoomStore.get();
        timer.lap("data");
        Random random = new Random(seed);
        DungeonPlan plan = Planner.plan(store, settings(spec.floor()), random);
        timer.lap("plan");
        Instance in = EngineServer.partyInstance();
        in.practicePick = null;
        in.practiceRoute = null;
        in.install(plan, store, null, -1, false, random, timer, spec.floor(), seed, null, this);
        run = in.dungeon();
        run.holdStart(false);
    }

    /** Fully random dungeon settings on [floor]: what a seed is a seed of. */
    private static DungeonSettings settings(Floor floor) {
        DungeonSettings settings = new DungeonSettings();
        settings.floor = floor.code;
        return settings;
    }

    // ---------------------------------------------------------------- chat

    /** "Seed: N" (click to copy) with a [retry] (/rs) and a [fresh] (/rs new) button. */
    private Component seedLine(String retry, String fresh) {
        String value = String.valueOf(seed);
        MutableComponent line = EngineItems.styled("§7Seed: ");
        line.append(EngineItems.styled("§e" + value).withStyle(st -> st.withClickEvent(new ClickEvent.CopyToClipboard(value))
            .withHoverEvent(new HoverEvent.ShowText(EngineItems.styled("§eClick to copy the seed")))));
        line.append(EngineItems.styled("  "));
        line.append(button(retry, "/rs", "§7The same map again"));
        line.append(EngineItems.styled(" "));
        line.append(button(fresh, "/rs new", "§7A new map"));
        return line;
    }

    private static MutableComponent button(String label, String command, String hover) {
        return EngineItems.styled(label).withStyle(st -> st.withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(EngineItems.styled(hover))));
    }
}
