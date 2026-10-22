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
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.DungeonSettings;
import net.rustclear.mod.Floor;
import net.rustclear.mod.engine.boss.necron.NecronPhase;
import net.rustclear.mod.engine.boss.storm.StormPhase;
import net.rustclear.mod.engine.boss.witherking.WitherKingPhase;

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
    /** Boss practice: from the teleport to a phase's first tick (Maxor's comes TELEPORT_BEFORE after his). */
    private static final int PHASE_DELAY = 10;
    /** Facing Storm's side of the room (+x, where he appears) from his practice start. */
    private static final float STORM_YAW = 270f;
    /** Facing north (-z) from Necron's practice start, toward where he stands. */
    private static final float NECRON_YAW = 180f;
    /**
     * The blessings a boss-only practice starts with, as a clear would have found them (no clear
     * runs before it). OPEN: a typical S+ clear's totals, not measured: Power XX (four level V
     * drops), Life, Stone and Wisdom X, and the Quiz's Time V.
     */
    private static final java.util.Map<String, Integer> BOSS_BLESSINGS = java.util.Map.of(
        "power", 20, "life", 10, "stone", 10, "wisdom", 10, "time", 5);

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
    /** Blood camp: the Blood room practised (kept for /rs), and the run ticks its door opened at (-1: not yet). */
    private RoomData bloodRoom;
    private long doorTicks = -1;
    /** Room: whether this session has built its room (until then /practice's last room isn't this one). */
    private boolean roomBuilt;
    /** Boss practice: the phase's name in the fight ("maxor"...), and the run ticks it began at (-1: not yet). */
    private String bossPhase;
    private long phaseStart = -1;

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
        doorTicks = -1;
        phaseStart = -1;
        switch (spec.kind()) {
            case FULL_CLEAR -> buildFullClear(fresh);
            case ROOM -> buildRoom(s, fresh);
            case BLOOD_RUSH -> buildBloodRush(fresh);
            case BLOOD_CAMP -> buildBloodCamp(fresh);
            case BOSS -> buildBoss(fresh);
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
        DungeonRun at = run;
        switch (spec.kind()) {
            case BLOOD_RUSH -> at.schedule(START_DELAY, () -> {
                at.grantBloodKey();
                at.startNow(false);
            });
            // The host "opens" the blood door: the camp starts as on Hypixel, the boss arena builds behind it.
            case BLOOD_CAMP -> at.schedule(START_DELAY, () -> {
                at.startNow(true);
                at.openBloodDoorForTest(host);
            });
            case BOSS -> at.schedule(START_DELAY, () -> {
                // Combat and lava only tick in a started run.
                at.startNow(true);
                startBoss(at);
            });
            default -> {
            }
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

    /**
     * One of the Blood rooms alone, its door a blood door, the host just inside: a second after
     * they're in the door opens and the Watcher's camp runs as in a run; the clock is door to
     * portal, and the run stops at the portal rather than going on into the boss.
     */
    private void buildBloodCamp(boolean fresh) throws Planner.PlanException {
        Practice.Built built = bloodPlan(fresh);
        Practice.DoorSpawn door = built.spawns().get(0);
        installAlone(built, spec.floor(), new DungeonBuilder.Spawn(door.x(), door.y(), door.z(), door.yaw()));
        greeting.add(EngineItems.text("§cBlood camp on " + spec.floor().code + ": §7the blood door opens in a second; the clock runs to the portal."));
    }

    /**
     * Straight into one boss phase: P1 (Maxor) to his death, P2 (Storm) to his, P4 (Necron) to the
     * end of the run on F7 or his on M7, P5 (the Wither King, always M7) to the end. The host's
     * world is the one-room Blood plan (the boss arena sits beside any dungeon), and the arena is
     * finished here: when the world opens with this practice that second goes behind its loading
     * screen. A second after the host is in the run starts and the phase begins.
     */
    private void buildBoss(boolean fresh) throws Planner.PlanException {
        String phase = spec.phase() == null ? "" : spec.phase().toLowerCase(Locale.ROOT);
        Floor floor = phase.equals("p5") ? Floor.M7 : spec.floor();
        Vec3 entry;
        float yaw;
        switch (phase) {
            case "p1" -> {
                bossPhase = "maxor";
                entry = net.rustclear.mod.engine.boss.maxor.MaxorPhase.entry();
                yaw = 360f;
            }
            case "p2" -> {
                bossPhase = "storm";
                entry = StormPhase.practiceEntry();
                yaw = STORM_YAW;
            }
            case "p4" -> {
                bossPhase = "necron";
                entry = NecronPhase.practiceEntry();
                yaw = NECRON_YAW;
            }
            case "p5" -> {
                bossPhase = "wither_king";
                entry = WitherKingPhase.practiceEntry();
                yaw = 180f;
            }
            default -> throw new Planner.PlanException("Unknown boss phase '" + spec.phase() + "': p1, p2, p4 or p5.");
        }
        installAlone(bloodPlan(fresh), floor, new DungeonBuilder.Spawn(entry.x, entry.y, entry.z, yaw));
        BossArena.of(run.level).finishNow();
        BOSS_BLESSINGS.forEach(run::addBlessing);
        // Necron ends an F7 run and the Wither King an M7 one (onDefeated); the others stop where the next would begin.
        boolean endsRun = bossPhase.equals("wither_king") || bossPhase.equals("necron") && !floor.master();
        if (!endsRun) {
            run.bossFight.stopAfter(bossPhase, this::phaseOver);
        }
        greeting.add(EngineItems.text("§cBoss practice: " + bossLabel() + " on " + floor.code + ". §7It starts in a second."));
    }

    /** The boss phase begins, a second after the host is in (the run already started). */
    private void startBoss(DungeonRun at) {
        switch (bossPhase) {
            // The blood portal's own way in: the teleport, Maxor's bar, then his phase.
            case "maxor" -> at.bossContext().enterBoss();
            case "storm" -> at.beginBossPractice(new StormPhase(), StormPhase.practiceEntry(), STORM_YAW, PHASE_DELAY);
            case "necron" -> {
                // P3's end as Goldor leaves it: the floor under the core's south end gone, the party falling into P4.
                NecronPhase.dropFloorNow(at.bossContext());
                at.beginBossPractice(new NecronPhase(null), NecronPhase.practiceEntry(), NECRON_YAW, NecronPhase.practiceDropLead());
            }
            // It puts the party on its floor itself.
            case "wither_king" -> at.beginBossPractice(new WitherKingPhase(), null, 0f, PHASE_DELAY);
            default -> {
            }
        }
    }

    private String bossLabel() {
        return switch (bossPhase) {
            case "maxor" -> "Maxor";
            case "storm" -> "Storm";
            case "necron" -> "Necron";
            case "wither_king" -> "the Wither King";
            default -> bossPhase;
        };
    }

    /** The practised phase is over (the next would begin): its time, and the run stops. */
    private void phaseOver() {
        reportPhase(run);
        run.stop();
    }

    private void reportPhase(DungeonRun at) {
        long ticks = phaseStart < 0 ? 0 : at.runTicks() - phaseStart;
        String label = bossLabel();
        Component line = EngineItems.styled("§c" + Character.toUpperCase(label.charAt(0)) + label.substring(1)
                + " §a" + time(ticks) + " §7(" + ticks + " ticks)  ")
            .append(button("§a[Retry]", "/rs", "§7The same phase again"));
        at.forEachPlayer(p -> p.sendSystemMessage(line));
    }

    /** The one-room Blood plan: the same Blood room again, or with [fresh] (or the first time) a random one. */
    private Practice.Built bloodPlan(boolean fresh) throws Planner.PlanException {
        if (fresh || bloodRoom == null) {
            List<RoomData> bloods = new ArrayList<>();
            for (RoomData data : RoomStore.get().rooms) {
                // Practice.buildBlood places the door as a dead end's (the Blood rooms' shape).
                if (data.type() == RoomType.BLOOD && data.shape() == RoomShape.ONE_BY_ONE_END) {
                    bloods.add(data);
                }
            }
            if (bloods.isEmpty()) {
                throw new Planner.PlanException("The dungeon data has no Blood room.");
            }
            bloodRoom = bloods.get(RANDOM.nextInt(bloods.size()));
        }
        return Practice.buildBlood(bloodRoom);
    }

    /** Installs a one-room [built] plan on [floor], unseeded, the start at [spawn]; the run waits for the session to start it. */
    private void installAlone(Practice.Built built, Floor floor, DungeonBuilder.Spawn spawn) {
        BuildTimer timer = new BuildTimer();
        Instance in = EngineServer.partyInstance();
        in.practicePick = null;
        in.practiceRoute = null;
        in.install(built.plan(), RoomStore.get(), null, -1, false, RANDOM, timer, floor, null, spawn, this);
        run = in.dungeon();
        run.holdStart(true);
    }

    /** The named room alone, entering through the spec's door; /rs keeps the door, /rs new rolls a random one again. */
    private void buildRoom(MinecraftServer s, boolean fresh) {
        Instance in = EngineServer.partyInstance();
        if (!fresh && roomBuilt && in.practicePick != null && in.practiceRoute != null) {
            EngineServer.restartPractice(s);
        } else {
            greeting.add(EngineItems.text(EngineServer.practiceRoom(s, spec.room(), spec.door(), null, spec.floor())));
            roomBuilt = true;
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
        return spec.kind() != PracticeSpec.Kind.FULL_CLEAR;
    }

    @Override
    public boolean ownsActionBar() {
        return spec.kind() == PracticeSpec.Kind.BLOOD_RUSH
            && (rushTicks < 0 || run == null || run.runTicks() - rushTicks < RESULT_TICKS);
    }

    @Override
    public void onTick(DungeonRun at, long runTicks) {
        if (at == run && bossPhase != null && phaseStart < 0 && at.bossFight.phase() != null
            && at.bossFight.phase().name().equals(bossPhase)) {
            phaseStart = runTicks;
        }
        if (at != run || !ownsActionBar()) {
            return;
        }
        // Practice.Route's timer: gold while running, green once done.
        String text = rushTicks < 0 ? "§6" + time(runTicks) : "§a" + time(rushTicks);
        for (ServerPlayer player : at.level.players()) {
            player.sendOverlayMessage(EngineItems.text(text));
        }
    }

    @Override
    public void onBloodDoor(DungeonRun at, long runTicks) {
        if (at != run) {
            return;
        }
        if (spec.kind() == PracticeSpec.Kind.BLOOD_CAMP) {
            doorTicks = runTicks;
            return;
        }
        if (spec.kind() != PracticeSpec.Kind.BLOOD_RUSH || rushTicks >= 0) {
            return;
        }
        rushTicks = runTicks;
        Component line = EngineItems.styled("§cBlood rush §a" + time(runTicks) + " §7(" + runTicks + " ticks) · ")
            .append(seedLine("§a[Retry]", "§e[New seed]"));
        at.forEachPlayer(p -> p.sendSystemMessage(line));
    }

    @Override
    public void onDefeated(DungeonRun at) {
        if (at == run && spec.kind() == PracticeSpec.Kind.BOSS) {
            reportPhase(at);
        }
    }

    @Override
    public boolean onEnterBoss(DungeonRun at) {
        if (at != run || spec.kind() != PracticeSpec.Kind.BLOOD_CAMP) {
            return false;
        }
        long ticks = doorTicks < 0 ? at.runTicks() : at.runTicks() - doorTicks;
        Component line = EngineItems.styled("§cBlood camp §a" + time(ticks) + " §7(" + ticks + " ticks, door to portal)  ")
            .append(button("§a[Retry]", "/rs", "§7Another camp in the same Blood room"))
            .append(EngineItems.styled(" "))
            .append(button("§e[New room]", "/rs new", "§7Another camp in a random Blood room"));
        at.forEachPlayer(p -> p.sendSystemMessage(line));
        at.stop();
        return true;
    }

    // ---------------------------------------------------------------- chat

    /** [ticks] as a practice time: "34.55s", or "1:02.35" from a minute. */
    static String time(long ticks) {
        double seconds = ticks * 0.05;
        if (seconds < 60) {
            return String.format(Locale.ROOT, "%.2fs", seconds);
        }
        return String.format(Locale.ROOT, "%d:%05.2f", (long) (seconds / 60), seconds % 60);
    }

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
