package net.rustclear.mod.engine.boss.goldor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.engine.EngineItems;
import net.rustclear.mod.engine.SimLog;
import net.rustclear.mod.engine.boss.Attacks;
import net.rustclear.mod.engine.boss.BossChat;
import net.rustclear.mod.engine.boss.BossContext;
import net.rustclear.mod.engine.boss.BossFight;
import net.rustclear.mod.engine.boss.BossFrames;
import net.rustclear.mod.engine.boss.BossPhase;
import net.rustclear.mod.engine.boss.Titles;
import net.rustclear.mod.engine.boss.goldor.device.Device;
import net.rustclear.mod.engine.boss.goldor.device.Devices;
import net.rustclear.mod.engine.boss.goldor.terminal.Terminals;
import net.rustclear.mod.mixin.ArmorStandAccessor;

/**
 * P3, Goldor (boss-p3-goldor.md). n = 0 is "Who dares trespass into my domain?", 62 ticks after
 * Storm's death line; the floor under Red opens into P3 at +25.
 * - Four sections of terminals, levers and a device (7/8/7/7, "(x/y)" in chat with a subtitle and
 *   a pling). A later section's device done early prints the current count and counts silently
 *   when its section starts.
 * - Gate k goes with a Superboom only while section k runs; a section ending first prints "The
 *   gate will open in 5 seconds!" and the gate goes 100 later. Section k+1 starts (its door opens)
 *   at the later of the last completion and the gate. S4's last completion opens the core.
 * - Goldor's lines are one FIFO, 62 apart: the intro (0/62/124/186), taunts from 248, a section
 *   line per door, "....". Death ticks on n mod 60 = 59 (or 58) hit anyone in a section zone that
 *   is lethal then.
 * - Goldor walks his track (Goldor), flies into the core once everyone is in and dies 44 later;
 *   "Necron, forgive me." 52 after "....", Necron's first line 10 after that.
 */
public final class GoldorPhase implements BossPhase {
    /** The floor hole down to P3 opens 25 ticks after Goldor's first line (x 96-105, z 36-45, y 167-168; 110/112 runs). */
    static final int HOLE = 25;

    private BossFight fight;
    private BossContext ctx;
    private Random random;
    private final List<Station> stations = Station.all();
    private List<Device> devices = List.of();
    private Goldor goldor;

    /** The section in progress (1-4), 5 once the core is open. */
    private int section = 1;
    private final long[] sectionStart = new long[6];
    private final long[] sectionEnd = {-1, -1, -1, -1, -1, -1};
    private final boolean[] gateDown = new boolean[5];
    private final long[] gateAt = {-1, -1, -1, -1, -1};
    private final boolean[] doorOpen = new boolean[5];
    private final long[] autoGateAt = {-1, -1, -1, -1, -1};
    /** Whether Goldor has walked into each section's segment since it started (its zone turns lethal once he walks on). */
    private final boolean[] reached = new boolean[6];
    private long coreAt = -1;
    private long everyoneInAt = -1;
    private long killAt = Long.MAX_VALUE;
    private long deadAt = -1;
    private long forgiveAt = -1;
    private long necronAt = -1;
    private boolean arrivalSaid;
    private boolean over;
    private boolean handedOver;
    private boolean barZeroed;

    private String lastTaunt;
    private final boolean dtEarlyRun;
    private boolean dtEarly;
    private final Map<UUID, Integer> frenzyStreak = new HashMap<>();
    private final Map<UUID, Long> swordReady = new HashMap<>();
    private final Map<UUID, Vec3> lastPos = new HashMap<>();
    private final Map<UUID, Long> chamberLine = new HashMap<>();

    public GoldorPhase() {
        this.dtEarlyRun = Math.random() < GoldorData.DT_EARLY_RUN;
    }

    @Override
    public String name() {
        return "goldor";
    }

    BossContext ctx() {
        return ctx;
    }

    long n() {
        return fight.clock.now();
    }

    void later(long delay, Runnable action) {
        fight.clock.after(delay, action);
    }

    @Override
    public void start(BossFight fight) {
        this.fight = fight;
        this.ctx = fight.ctx;
        this.random = ctx.random();
        ctx.chat().reset();
        say(GoldorData.WHO_DARES);
        for (String line : GoldorData.INTRO) {
            say(line);
        }
        // Taunts queued during S1 follow the intro from 248 on.
        int k = pick(GoldorData.S1_TAUNTS);
        for (int i = 0; i < k; i++) {
            fight.clock.at(20 + random.nextInt(220), () -> {
                if (section <= 1) {
                    say(taunt());
                }
            });
        }
        ctx.bar().boss("Goldor", 1f);
        fight.clock.at(HOLE, this::hole);
        goldor = new Goldor(ctx);
        goldor.spawn();
        ctx.registerBoss(goldor.entity(), amount -> goldor.hit(n()));
        for (Station st : stations) {
            if (st.kind == Station.Kind.TERMINAL) {
                st.type = Terminals.random(random);
            }
            spawnStands(st);
        }
        devices = Devices.create(new Host());
        for (Device d : devices) {
            d.start();
        }
        sectionStart[1] = 0;
        SimLog.event("goldor_start");
    }

    private void hole() {
        for (int x = 96; x <= 105; x++) {
            for (int z = 36; z <= 45; z++) {
                for (int y = 167; y <= 168; y++) {
                    ctx.blocks().set(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        SimLog.event("goldor_hole");
    }

    // ---------------------------------------------------------------- lines

    /** One of Goldor's lines into the FIFO (62 after the one before). */
    private void say(String line) {
        ctx.chat().say(BossChat.boss("Goldor", line), GoldorData.LINE_GAP);
    }

    private static int pick(int[] weights) {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int r = (int) (Math.random() * total);
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    /** A taunt by the measured weights, never the last one again. */
    private String taunt() {
        String t;
        do {
            t = GoldorData.TAUNTS.get(pick(GoldorData.TAUNT_WEIGHTS));
        } while (t.equals(lastTaunt));
        lastTaunt = t;
        return t;
    }

    // ---------------------------------------------------------------- stands

    private void spawnStands(Station st) {
        switch (st.kind) {
            case TERMINAL -> {
                st.top = stand(st.at.add(0, Station.TOP_DY, 0), "§cInactive Terminal", false);
                st.bottom = stand(st.at.add(0, Station.BOTTOM_DY, 0), "§e§lCLICK HERE", false);
            }
            case DEVICE -> {
                st.top = stand(st.at, "§cInactive", false);
                st.bottom = stand(st.at.add(0, -0.375, 0), "§cDevice", false);
            }
            case LEVER -> st.top = stand(st.at, "§cNot Activated", true);
        }
    }

    private ArmorStand stand(Vec3 at, String name, boolean marker) {
        ArmorStand s = new ArmorStand(EntityTypes.ARMOR_STAND, ctx.level());
        if (marker) {
            ((ArmorStandAccessor) s).rustclear$setMarker(true);
        }
        s.setInvisible(true);
        s.setNoGravity(true);
        s.setPermanentlyInvulnerable(true);
        s.setSilent(true);
        s.setCustomName(EngineItems.styled(name));
        s.setCustomNameVisible(true);
        s.snapTo(at.x, at.y, at.z, 0f, 0f);
        ctx.level().addFreshEntity(s);
        ctx.entities().add(s);
        return s;
    }

    private static void name(ArmorStand s, String legacy) {
        if (s == null) {
            return;
        }
        String now = s.getCustomName() == null ? "" : s.getCustomName().getString();
        String want = legacy.isEmpty() ? "" : EngineItems.styled(legacy).getString();
        if (!now.equals(want) || s.isCustomNameVisible() == legacy.isEmpty()) {
            s.setCustomName(legacy.isEmpty() ? null : EngineItems.styled(legacy));
            s.setCustomNameVisible(!legacy.isEmpty());
        }
    }

    /**
     * The stands' names as they read now. Terminals and devices go blank while nobody is within 25
     * blocks (Hypixel sends their names near players only); a done terminal's top stand drops 0.375.
     */
    private void refresh(Station st) {
        boolean far = st.kind != Station.Kind.LEVER;
        if (far) {
            for (ServerPlayer p : ctx.players()) {
                if (p.position().distanceTo(st.at) < 25) {
                    far = false;
                    break;
                }
            }
        }
        if (far) {
            name(st.top, "");
            name(st.bottom, "");
            return;
        }
        switch (st.kind) {
            case TERMINAL -> {
                double y = st.at.y + (st.done ? Station.BOTTOM_DY : Station.TOP_DY);
                if (st.top != null && Math.abs(st.top.getY() - y) > 1e-3) {
                    st.top.snapTo(st.at.x, y, st.at.z, 0f, 0f);
                }
                name(st.top, st.done ? "§aTerminal Active" : "§cInactive Terminal");
                name(st.bottom, st.done ? "" : "§e§lCLICK HERE");
            }
            case DEVICE -> {
                name(st.top, st.done ? "§aDevice" : "§cInactive");
                name(st.bottom, st.done ? "§aActive" : "§cDevice");
            }
            case LEVER -> name(st.top, st.done ? "§aActivated" : "§cNot Activated");
        }
    }

    // ---------------------------------------------------------------- ticking

    @Override
    public void tick() {
        long n = n();
        for (Device d : devices) {
            d.tick();
        }
        // Names refresh on a 20-tick grid, 1-2 ticks before each multiple of 20.
        if (n % 20 == 18) {
            for (Station st : stations) {
                if (st.kind != Station.Kind.LEVER) {
                    refresh(st);
                }
            }
        }
        for (int s = 1; s <= 3; s++) {
            if (autoGateAt[s] >= 0 && n >= autoGateAt[s] && !gateDown[s]) {
                blowGate(s, null);
            }
        }
        if (section <= 4) {
            if (n % 60 == 58) {
                dtEarly = dtEarlyRun ^ (random.nextDouble() < GoldorData.DT_FLIP);
            }
            if ((n % 60 == 58 && dtEarly) || (n % 60 == 59 && !dtEarly)) {
                deathTick();
            }
        }
        goldor.tick(n);
        int seg = Goldor.segment(goldor.s) + 1;
        if (section <= 4 && seg == section && !(section == 4 && goldor.firstLap)) {
            reached[section] = true;
        }
        attacks(n);
        chamber(n);
        if (section == 5) {
            core(n);
        }
        if (forgiveAt >= 0 && n >= forgiveAt) {
            forgiveAt = -1;
            ctx.broadcast(BossChat.boss("Goldor", GoldorData.FORGIVE));
            SimLog.event("goldor_forgive");
            later(12, goldor::reArmour);
            necronAt = n + GoldorData.NECRON_AFTER_FORGIVE;
            // The floor under the core falls into P4: ~770 blocks in two frames at N-6 and N-2 (p4_blocks.json).
            BossFrames.play(fight.clock, ctx.blocks(), "p4_blocks.json", "p3end", GoldorData.NECRON_AFTER_FORGIVE - 6, null);
        }
        if (necronAt >= 0 && n >= necronAt && !over) {
            over = true;
            SimLog.event("goldor_end");
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean use(ServerPlayer player, Entity entity) {
        for (Station st : stations) {
            if (st.kind == Station.Kind.TERMINAL && st.owns(entity)) {
                useTerminal(player, st);
                return true;
            }
            if (st.owns(entity)) {
                return true;
            }
        }
        for (Device d : devices) {
            if (d.useEntity(player, entity)) {
                return true;
            }
        }
        return ctx.entities().contains(entity);
    }

    @Override
    public boolean attack(ServerPlayer player, Entity entity) {
        for (Device d : devices) {
            if (d.attackEntity(player, entity)) {
                return true;
            }
        }
        for (Station st : stations) {
            if (st.owns(entity)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean useBlock(ServerPlayer player, BlockPos pos) {
        for (Station st : stations) {
            if (pos.equals(st.lever)) {
                pullLever(player, st, false);
                return true;
            }
        }
        for (Device d : devices) {
            if (d.useBlock(player, pos)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hitBlock(ServerPlayer player, BlockPos pos) {
        for (Station st : stations) {
            if (pos.equals(st.lever)) {
                pullLever(player, st, true);
                return true;
            }
        }
        for (Device d : devices) {
            if (d.hitBlock(player, pos)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean boom(ServerPlayer player, BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        for (int s = 1; s <= 3; s++) {
            if (GoldorData.GATE_BOXES[s].inflate(GoldorData.BOOM_REACH).contains(c)) {
                return blowGate(s, player);
            }
        }
        return false;
    }

    /** A click on a terminal's stand: its window a tick later, or Hypixel's refusal (red, a tick later). */
    private void useTerminal(ServerPlayer player, Station st) {
        if (st.done) {
            later(1, () -> player.sendSystemMessage(EngineItems.styled("§cThis Terminal has already been completed!")));
            return;
        }
        if (st.section != section) {
            later(1, () -> player.sendSystemMessage(EngineItems.styled("§cThis Terminal doesn't seem to be responsive at the moment.")));
            return;
        }
        later(1, () -> {
            if (!st.done && st.section == section) {
                Terminals.open(player, st, st.type, random, () -> complete(st, player, false));
            }
        });
    }

    /**
     * A lever: a right click flips it (vanilla's toggle, click sound 0.3 at 0.587 on / 0.492 off) and
     * credits it; a left click credits it without moving it, and Hypixel handles it twice, so the
     * refusal follows in the same tick. A done lever refuses; another section's lever just flips.
     */
    private void pullLever(ServerPlayer player, Station st, boolean left) {
        BlockState state = ctx.blocks().get(st.lever);
        boolean isLever = state.hasProperty(LeverBlock.POWERED);
        if (st.done) {
            refuseLever(player, st, left);
            return;
        }
        boolean on = isLever && !state.getValue(LeverBlock.POWERED);
        if (st.section != section) {
            if (!left && isLever) {
                ctx.blocks().set(st.lever, state.setValue(LeverBlock.POWERED, on));
                leverClick(st.lever, on);
            }
            return;
        }
        if (!left && isLever) {
            ctx.blocks().set(st.lever, state.setValue(LeverBlock.POWERED, on));
        }
        complete(st, player, false);
        if (!left) {
            leverClick(st.lever, on);
        } else {
            refuseLever(player, st, true);
        }
        later(1, () -> refresh(st));
    }

    private void leverClick(BlockPos lever, boolean on) {
        ctx.level().playSound(null, lever, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3f, on ? 0.5873016f : 0.4920635f);
    }

    /** "Someone has already activated this lever!", then enderman.teleport (hostile 8/0) at you. */
    private void refuseLever(ServerPlayer player, Station st, boolean left) {
        player.sendSystemMessage(EngineItems.styled("§cSomeone has already activated this lever!"));
        send(player, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 8f, 0f);
        if (left) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(ctx.level(), st.lever));
        }
    }

    // ---------------------------------------------------------------- progress

    private int count(int s) {
        int k = 0;
        for (Station st : stations) {
            if (st.section == s && st.done) {
                k++;
            }
        }
        return k;
    }

    /** [st] done by [by] ([twice]: announced twice in the tick, counted once). */
    void complete(Station st, ServerPlayer by, boolean twice) {
        if (st.done || section > 4) {
            return;
        }
        boolean inProgress = st.section == section;
        boolean early = st.kind == Station.Kind.DEVICE && st.section > section;
        if (!inProgress && !early) {
            return;
        }
        st.done = true;
        st.doneAt = n();
        String what = switch (st.kind) {
            case TERMINAL -> "activated a terminal!";
            case LEVER -> "activated a lever!";
            case DEVICE -> "completed a device!";
        };
        int k = count(section);
        int total = Station.total(section);
        if (st.kind == Station.Kind.TERMINAL) {
            Terminals.closeFor(st, by);
        }
        String line = ctx.nameOf(by) + "§r§a " + what + " (§r§c" + k + "§r§a/" + total + ")";
        for (int i = 0; i < (twice ? 2 : 1); i++) {
            announce(line);
        }
        SimLog.event("p3_done", "section", section, "station", st.id(), "count", k, "total", total, "early", early);
        if (inProgress && count(section) >= total) {
            sectionDone(section);
        }
    }

    /** A progress line as Hypixel sends it: chat, then the same line as a subtitle (0/40/0), then the pling. */
    private void announce(String line) {
        for (ServerPlayer p : ctx.players()) {
            p.sendSystemMessage(EngineItems.styled(line));
            Titles.send(p, "", line.replace("§r", ""), Titles.PROGRESS);
            pling(p);
        }
    }

    /** The progress pling: BLOCKS 8 at 4.047619 at the player (Odin keys its terminal sounds on it). */
    private void pling(ServerPlayer p) {
        send(p, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 8f, 4.047619f);
    }

    private static void send(ServerPlayer p, net.minecraft.sounds.SoundEvent event, SoundSource source, float volume, float pitch) {
        p.connection.send(new ClientboundSoundPacket(net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event),
            source, p.getX(), p.getY(), p.getZ(), volume, pitch, p.getRandom().nextLong()));
    }

    private void everyone(String legacy) {
        for (ServerPlayer p : ctx.players()) {
            p.sendSystemMessage(EngineItems.styled(legacy));
            Titles.send(p, "", legacy, Titles.PROGRESS);
            pling(p);
        }
    }

    private void sectionDone(int s) {
        sectionEnd[s] = n();
        SimLog.event("p3_section", "section", s, "length", n() - sectionStart[s]);
        if (s == 4) {
            openCore();
            return;
        }
        if (gateDown[s]) {
            openDoor(s);
            return;
        }
        everyone("§aThe gate will open in 5 seconds!");
        autoGateAt[s] = n() + GoldorData.GATE_AUTO;
        // The door's iron blocks and stairs above y 118 go a tick later; the rest waits for the gate.
        BossFrames.play(fight.clock, ctx.blocks(), "p3_blocks.json", "door" + s, 0, f -> f.dt() != 1 || f.pos().getY() < 118 || !topBlock(f.pos()));
        SimLog.event("p3_gate_will_open", "gate", s);
    }

    private boolean topBlock(BlockPos pos) {
        BlockState b = ctx.blocks().get(pos);
        return b.is(Blocks.IRON_BLOCK) || b.getBlock() instanceof StairBlock;
    }

    private void openDoor(int s) {
        if (doorOpen[s]) {
            return;
        }
        doorOpen[s] = true;
        BossFrames.play(fight.clock, ctx.blocks(), "p3_blocks.json", "door" + s, 0, null);
        // His section line queues with the door: spoken at max(door + 1, the previous line + 62).
        later(1, () -> say(GoldorData.SECTION_LINES.get(random.nextInt(GoldorData.SECTION_LINES.size()))));
        section = s + 1;
        sectionStart[section] = n();
        if (section <= 4 && !goldor.firstLap && Goldor.segment(goldor.s) + 1 > section) {
            reached[section] = true;
        }
        if (section == 4 && random.nextDouble() < GoldorData.LATE_TAUNT_RUN) {
            int k = 1 + random.nextInt(2);
            for (int i = 0; i < k; i++) {
                later(20 + random.nextInt(150), () -> {
                    if (section == 4) {
                        say(taunt());
                    }
                });
            }
        }
        goldor.sectionEnded(s, n());
        SimLog.event("p3_door", "door", s);
        if (count(section) >= Station.total(section)) {
            sectionDone(section);
        }
    }

    /** Blows gate [s] if it can go now ([by] null: it goes by itself). */
    private boolean blowGate(int s, ServerPlayer by) {
        if (s < 1 || s > 3 || gateDown[s]) {
            return false;
        }
        if (by != null && section < s) {
            return false;
        }
        gateDown[s] = true;
        gateAt[s] = n();
        everyone("§aThe gate has been destroyed!");
        Vec3 c = GoldorData.GATE_CENTRES[s];
        ctx.level().sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 6, 2, 2, 2, 0);
        ctx.level().playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.5f, 0.492f);
        BossFrames.play(fight.clock, ctx.blocks(), "p3_blocks.json", "gate" + s, 0, null);
        SimLog.event("p3_gate", "gate", s, "auto", by == null);
        if (sectionEnd[s] >= 0) {
            openDoor(s);
        }
        return true;
    }

    // ---------------------------------------------------------------- death ticks

    private void deathTick() {
        for (ServerPlayer p : ctx.players()) {
            if (p.isSpectator() || p.isCreative() || ctx.isGhost(p)) {
                continue;
            }
            Vec3 at = p.position();
            if (GoldorData.CORE_BOX.contains(at) || GoldorData.STRIP.contains(at)) {
                continue;
            }
            int zone = zone(at);
            if (zone < 0 || zone < section) {
                continue;
            }
            // The section in progress is lethal only once Goldor has walked into and out of its segment.
            if (zone == section && !(reached[section] && Goldor.segment(goldor.s) + 1 != section)) {
                continue;
            }
            ctx.kill(p, null);
            p.sendSystemMessage(EngineItems.styled(BossChat.boss("Goldor", GoldorData.DEATH_TICK)));
            send(p, SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 1f, 1f);
            SimLog.event("p3_death_tick", "zone", zone, "section", section);
        }
    }

    static int zone(Vec3 v) {
        for (int s = 1; s <= 4; s++) {
            if (GoldorData.DT_ZONES[s].contains(v)) {
                return s;
            }
        }
        return -1;
    }

    /**
     * From S4's start until Goldor flies, nothing gets you out of the core once in: a move from
     * inside to outside puts you back, with the line and the enderman sound once per 20 ticks.
     */
    private void chamber(long n) {
        for (ServerPlayer p : ctx.players()) {
            Vec3 now = p.position();
            Vec3 was = lastPos.put(p.getUUID(), now);
            if (section < 4 || goldor.flying || was == null || !GoldorData.CORE_BOX.contains(was)
                || GoldorData.CORE_BOX.contains(now)) {
                continue;
            }
            Vec3 back = GoldorData.CHAMBER_BACK;
            p.teleportTo(back.x, back.y, back.z);
            lastPos.put(p.getUUID(), back);
            if (n - chamberLine.getOrDefault(p.getUUID(), -100L) >= 20) {
                chamberLine.put(p.getUUID(), n);
                send(p, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 8f, 0f);
                p.sendSystemMessage(EngineItems.styled("§cA mystical force prevents you from leaving the inner chamber!"));
            }
        }
    }

    // ---------------------------------------------------------------- attacks

    private void attacks(long n) {
        if (deadAt >= 0) {
            return;
        }
        Vec3 g = goldor.position();
        if (n % 10 == GoldorData.FRENZY_RESIDUE) {
            for (ServerPlayer p : ctx.players()) {
                boolean near = !ctx.isGhost(p) && !p.isSpectator() && p.position().distanceTo(g) <= GoldorData.FRENZY_RANGE;
                if (!near) {
                    frenzyStreak.remove(p.getUUID());
                    continue;
                }
                int hit = frenzyStreak.merge(p.getUUID(), 1, Integer::sum);
                double raw = hit == 1
                    ? GoldorData.FRENZY_FIRST_MIN + random.nextDouble() * (GoldorData.FRENZY_FIRST_MAX - GoldorData.FRENZY_FIRST_MIN)
                    : GoldorData.FRENZY_MIN + random.nextDouble() * (GoldorData.FRENZY_MAX - GoldorData.FRENZY_MIN);
                raw *= ctx.profile().scale("Goldor's Frenzy");
                ctx.hit(p, new Attacks.Attack("Goldor", "Frenzy", raw, false), raw);
                send(p, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 0.5f, 0.49f);
            }
        }
        List<Vec3> swords = goldor.swords();
        if (swords.isEmpty()) {
            return;
        }
        Attacks.Attack sword = new Attacks.Attack("Goldor", Attacks.GREATSWORD, GoldorData.GREATSWORD, false);
        for (ServerPlayer p : ctx.players()) {
            if (ctx.isGhost(p) || p.isSpectator() || n < swordReady.getOrDefault(p.getUUID(), 0L)) {
                continue;
            }
            Vec3 at = p.position().add(0, 1, 0);
            for (Vec3 s : swords) {
                if (s.distanceTo(at) <= GoldorData.SWORD_REACH) {
                    swordReady.put(p.getUUID(), n + GoldorData.SWORD_GAP);
                    ctx.hit(p, sword);
                    break;
                }
            }
        }
    }

    // ---------------------------------------------------------------- the core

    private void openCore() {
        section = 5;
        coreAt = n();
        everyone("§aThe Core entrance is opening!");
        BossFrames.play(fight.clock, ctx.blocks(), "p3_blocks.json", "core", 0, null);
        Terminals.closeAll();
        SimLog.event("p3_core");
    }

    private boolean everyoneIn() {
        List<ServerPlayer> ps = ctx.players();
        if (ps.isEmpty()) {
            return false;
        }
        for (ServerPlayer p : ps) {
            // OPEN 51: a ghost outside isn't waited for.
            if (!ctx.isGhost(p) && !p.isSpectator() && !GoldorData.CORE_BOX.contains(p.position())) {
                return false;
            }
        }
        return true;
    }

    private void core(long n) {
        if (everyoneInAt < 0 && n >= coreAt + GoldorData.FLY_MIN && everyoneIn()) {
            everyoneInAt = n;
            goldor.fly(n);
            killAt = n + GoldorData.KILL_AFTER_FLY;
            SimLog.event("goldor_fly");
        }
        if (deadAt < 0 && goldor.flying) {
            if (goldor.arrived && !arrivalSaid) {
                arrivalSaid = true;
                for (String line : GoldorData.ARRIVAL) {
                    say(line);
                }
            }
            if (n >= killAt) {
                deadAt = n;
                goldor.die();
                ctx.chat().say(BossChat.boss("Goldor", GoldorData.DEATH), GoldorData.LINE_GAP,
                    // The chat prints after this tick's clock moved on: n() is already the line's tick + 1.
                    () -> forgiveAt = n() - 1 + GoldorData.FORGIVE_AFTER);
                SimLog.event("goldor_death");
            }
        }
        bar(n);
    }

    /** The bar: full until ~3 before the kill, then down every 4 ticks, 0 nine after it (OPEN: shape from one run). */
    private void bar(long n) {
        if (killAt == Long.MAX_VALUE || barZeroed) {
            return;
        }
        long drop = killAt - 3;
        long zero = killAt + 9;
        if (n < drop) {
            return;
        }
        if (n >= zero) {
            barZeroed = true;
            ctx.bar().progress(0f);
        } else if ((n - drop) % 4 == 0) {
            ctx.bar().progress(0.35f * (zero - n) / (zero - drop));
        }
    }

    // ---------------------------------------------------------------- the phase

    @Override
    public BossPhase next() {
        if (!over) {
            return null;
        }
        // Goldor's body stays where he died until Necron's goes.
        handedOver = true;
        Goldor body = goldor;
        return new net.rustclear.mod.engine.boss.necron.NecronPhase(body::remove);
    }

    @Override
    public void dispose() {
        Terminals.closeAll();
        for (Device d : devices) {
            d.dispose();
        }
        if (goldor != null && !handedOver) {
            goldor.remove();
        }
        for (Station st : stations) {
            if (st.top != null) {
                ctx.entities().discard(st.top);
            }
            if (st.bottom != null) {
                ctx.entities().discard(st.bottom);
            }
        }
    }

    /** What the devices see of the phase. */
    private final class Host implements Device.Host {
        @Override
        public BossContext ctx() {
            return ctx;
        }

        @Override
        public long n() {
            return GoldorPhase.this.n();
        }

        @Override
        public Random random() {
            return random;
        }

        @Override
        public void later(long delay, Runnable action) {
            GoldorPhase.this.later(delay, action);
        }

        @Override
        public void completed(Device device, ServerPlayer by, boolean twice) {
            for (Station st : stations) {
                if (st.kind == Station.Kind.DEVICE && st.label.equals(device.label())) {
                    complete(st, by, twice);
                    later(0, () -> refresh(st));
                }
            }
        }

        @Override
        public boolean live() {
            return section <= 4;
        }
    }

    /** For the LAN check: the 30 stations, the devices, and whether gate [s] (1-3) is down. */
    public List<Station> stations() {
        return stations;
    }

    public List<Device> devices() {
        return devices;
    }

    public boolean gateDown(int s) {
        return gateDown[s];
    }

    public int section() {
        return section;
    }
}
