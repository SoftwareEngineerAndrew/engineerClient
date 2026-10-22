package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    /** Ticks between the host's join and a timed start: the terrain loads in first. */
    private static final int START_DELAY = 20;
    /** How long a finished timer stays on the action bar (green) before the health and mana line comes back. */
    private static final int RESULT_TICKS = 100;

    final PracticeSpec spec;
    /** The seed this session's map is built from (FULL_CLEAR), or null. */
    private Long seed;
    /** The run the last build installed. */
    private DungeonRun run;
    /** Chat lines for the host once they're in, from the last build. */
    private final List<Component> greeting = new ArrayList<>();
    /** Whether the host has been set up for the last build ([onJoin]). */
    private boolean joined;
    /** Blood rush: the run ticks the blood door opened at, or -1 while the clock runs. */
    private long rushTicks = -1;

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
        rushTicks = -1;
        switch (spec.kind()) {
            case FULL_CLEAR -> buildFullClear(fresh);
            case ROOM -> buildRoom(s, fresh);
            case BLOOD_RUSH -> buildBloodRush(fresh);
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
        if (spec.kind() == PracticeSpec.Kind.BLOOD_RUSH) {
            DungeonRun rush = run;
            rush.schedule(START_DELAY, () -> {
                rush.grantBloodKey();
                rush.startNow(false);
            });
        }
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

    /**
     * From the entrance to the blood door against the clock, the blood key already in hand. No Mort
     * to ready at: the run starts a second after the host is in, with Mort's first line (run
     * splits key on it), and the clock stops as the blood door opens.
     */
    private void buildBloodRush(boolean fresh) throws Planner.PlanException {
        installSeeded(fresh);
        run.holdStart(true);
        greeting.add(EngineItems.text("§cBlood rush on " + spec.floor().code + ": §7you hold the blood key; the clock starts in a second."));
        greeting.add(seedLine("§a[Retry]", "§e[New seed]"));
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

    // ---------------------------------------------------------------- the run's hooks

    @Override
    public boolean skipMort() {
        return spec.kind() == PracticeSpec.Kind.BLOOD_RUSH;
    }

    @Override
    public boolean ownsActionBar() {
        return spec.kind() == PracticeSpec.Kind.BLOOD_RUSH
            && (rushTicks < 0 || run == null || run.runTicks() - rushTicks < RESULT_TICKS);
    }

    @Override
    public void onTick(DungeonRun at, long runTicks) {
        if (at != run || !ownsActionBar()) {
            return;
        }
        // Practice.Route's timer: gold while running, green once done.
        String text = rushTicks < 0 ? String.format(Locale.ROOT, "§6%.2fs", runTicks * 0.05)
            : String.format(Locale.ROOT, "§a%.2fs", rushTicks * 0.05);
        for (ServerPlayer player : at.level.players()) {
            player.sendOverlayMessage(EngineItems.text(text));
        }
    }

    @Override
    public void onBloodDoor(DungeonRun at, long runTicks) {
        if (at != run || spec.kind() != PracticeSpec.Kind.BLOOD_RUSH || rushTicks >= 0) {
            return;
        }
        rushTicks = runTicks;
        Component line = EngineItems.styled(String.format(Locale.ROOT, "§cBlood rush §a%.2fs §7(%d ticks) · ", runTicks * 0.05, runTicks))
            .append(seedLine("§a[Retry]", "§e[New seed]"));
        at.forEachPlayer(p -> p.sendSystemMessage(line));
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
