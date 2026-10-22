package net.rustclear.mod.engine.boss.storm;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.engine.EngineItems;
import net.rustclear.mod.engine.SimLog;
import net.rustclear.mod.engine.boss.Attacks;
import net.rustclear.mod.engine.boss.BossChat;
import net.rustclear.mod.engine.boss.BossContext;
import net.rustclear.mod.engine.boss.BossFight;
import net.rustclear.mod.engine.boss.BossPhase;
import net.rustclear.mod.engine.boss.goldor.GoldorPhase;
import net.rustclear.mod.mixin.ArmorStandAccessor;
import net.rustclear.mod.mixin.LivingEntityAccessor;

/**
 * P2, Storm (boss-p2-storm.md). t = 0 is his first line.
 * - The script: lines at 0/62/124/186/424/486, the lightning line at 548; Lightning Fireballs every
 *   40 ticks from 78 to 398 along his route (each seeding a Static Field), parked at
 *   (102.375, 183, 52.375) by 424, Giga Lightning at 519 and 529 on everyone not hiding under a
 *   pillar, leaving the park at 646.
 * - Pads press the pillars (Pillar); every 20 ticks (t = 19 mod 20) an armed pillar over him crushes
 *   him: "Ouch, that hurt!" / "Oof", pinned; the pillar collapses 20 ticks later. After crush 1 the
 *   party takes him to 45% and he is enraged (skull pairs every 5 ticks); crush 2 kills him: the
 *   death line ~6 ticks later, "At least my son..." +42, Goldor +62.
 */
public final class StormPhase implements BossPhase {
    private BossFight fight;
    private BossContext ctx;
    private Random random;

    private WitherBoss storm;
    private ArmorStand nameTag;
    private ArmorStand bossTag;
    private Vec3 pos = StormData.SPAWN;
    private int waypoint;
    private boolean parked;
    private boolean free;
    private Vec3 goal;

    private final Pillar[] pillars = new Pillar[4];
    private int crushes;
    private boolean pinned;
    private boolean enraged;
    private boolean dying;
    private long deathAt = -1;
    private boolean over;
    private double maxHealth;
    private double health;
    private long nextTaunt = StormData.FIRST_TAUNT;
    private long nextSkull = Long.MAX_VALUE;

    private final List<LargeFireball> fireballs = new ArrayList<>();
    private final List<Vec3> fireballLast = new ArrayList<>();

    /** The four pillars' colours, by index (Purple, Yellow, Green, Red). */
    private static final net.minecraft.world.level.block.Block[] PILLAR_GLASS = {
        net.minecraft.world.level.block.Blocks.STAINED_GLASS.purple(), net.minecraft.world.level.block.Blocks.STAINED_GLASS.yellow(),
        net.minecraft.world.level.block.Blocks.STAINED_GLASS.lime(), net.minecraft.world.level.block.Blocks.STAINED_GLASS.red()};
    private static final net.minecraft.world.level.block.Block[] PILLAR_TERRACOTTA = {
        net.minecraft.world.level.block.Blocks.DYED_TERRACOTTA.purple(), net.minecraft.world.level.block.Blocks.DYED_TERRACOTTA.yellow(),
        net.minecraft.world.level.block.Blocks.DYED_TERRACOTTA.lime(), net.minecraft.world.level.block.Blocks.DYED_TERRACOTTA.red()};

    /** Pillar [index]'s block: terracotta in its colour. */
    static net.minecraft.world.level.block.state.BlockState pillarBlock(int index) {
        return PILLAR_TERRACOTTA[index].defaultBlockState();
    }

    /**
     * The arena as BossArena builds it: the Bossroom data has Storm's four pillars as stained
     * glass; on Hypixel they're terracotta in the same colours. Every other block passes through.
     */
    public static net.minecraft.world.level.block.state.BlockState arenaBlock(int x, int y, int z,
                                                                             net.minecraft.world.level.block.state.BlockState state) {
        if (y < StormData.FLOOR || y > StormData.PILLAR_TOP) {
            return state;
        }
        for (int i = 0; i < 4; i++) {
            int[] c = StormData.PILLAR_CORNERS[i];
            if (x >= c[0] && x < c[0] + 7 && z >= c[1] && z < c[1] + 7) {
                return state.is(PILLAR_GLASS[i]) ? pillarBlock(i) : state;
            }
        }
        return state;
    }

    /** Where boss practice starts the party for this phase alone (StormData.PRACTICE_ENTRY). */
    public static Vec3 practiceEntry() {
        return StormData.PRACTICE_ENTRY;
    }

    @Override
    public String name() {
        return "storm";
    }

    @Override
    public void start(BossFight fight) {
        this.fight = fight;
        this.ctx = fight.ctx;
        this.random = ctx.random();
        maxHealth = ctx.profile().master ? StormData.HEALTH_M7 : StormData.HEALTH_F7;
        health = maxHealth;
        ctx.chat().reset();
        for (int i = 0; i < StormData.LINES.size(); i++) {
            String line = StormData.LINES.get(i);
            fight.clock.at(StormData.LINE_AT[i], () -> say(line));
        }
        fight.clock.at(StormData.LINE_AT[6],
            () -> say(StormData.LIGHTNING_LINES.get(random.nextInt(StormData.LIGHTNING_LINES.size()))));
        for (int t = StormData.FIREBALL_FIRST; t <= StormData.FIREBALL_LAST; t += StormData.FIREBALL_GAP) {
            fight.clock.at(t, this::fireball);
        }
        for (int t : StormData.GIGA) {
            fight.clock.at(t, this::giga);
        }
        if (ctx.profile().master) {
            // Its own line at the Giga tick, outside the queue: it doesn't hold the script back (the lightning line stays at 548 on M7).
            fight.clock.at(StormData.GIGA[0], () -> ctx.broadcast(BossChat.boss("Storm", StormData.FOOL)));
        }
        fight.clock.at(StormData.LEAVE, () -> free = true);
        ctx.bar().boss("Storm", 1f);
        spawn();
        for (int i = 0; i < 4; i++) {
            pillars[i] = new Pillar(ctx, i);
        }
        SimLog.event("storm_start");
    }

    private void say(String line) {
        ctx.chat().say(BossChat.boss("Storm", line), StormData.LINE_GAP);
    }

    private void spawn() {
        ServerLevel level = ctx.level();
        storm = EntityTypes.WITHER.create(level, EntitySpawnReason.TRIGGERED);
        storm.setNoAi(true);
        storm.setNoGravity(true);
        storm.setPersistenceRequired();
        storm.setInvulnerableTicks(200);
        var max = storm.getAttribute(Attributes.MAX_HEALTH);
        if (max != null) {
            max.setBaseValue(ctx.profile().bossMaxHealth);
        }
        storm.snapTo(pos.x, pos.y, pos.z, 0f, 0f);
        level.addFreshEntity(storm);
        ctx.entities().add(storm);
        flag(1f);
        ctx.registerBoss(storm, this::damaged);
        nameTag = label(" §cStorm", 3.69);
        bossTag = label("§c§lBOSS", 4.0);
    }

    private ArmorStand label(String legacy, double dy) {
        ArmorStand s = new ArmorStand(EntityTypes.ARMOR_STAND, ctx.level());
        ((ArmorStandAccessor) s).rustclear$setMarker(true);
        s.setInvisible(true);
        s.setNoGravity(true);
        s.setPermanentlyInvulnerable(true);
        s.setCustomNameVisible(true);
        s.setCustomName(EngineItems.styled(legacy));
        s.snapTo(pos.x, pos.y + dy, pos.z, 0f, 0f);
        ctx.level().addFreshEntity(s);
        ctx.entities().add(s);
        return s;
    }

    private void flag(float v) {
        if (storm != null && !storm.isRemoved()) {
            storm.getEntityData().set(LivingEntityAccessor.rustclear$healthId(), v);
        }
    }

    // ---------------------------------------------------------------- ticking

    @Override
    public void tick() {
        long now = fight.clock.now();
        if (storm == null) {
            return;
        }
        for (Pillar p : pillars) {
            p.tick(now);
        }
        pads(now);
        move();
        if (!dying && now % StormData.CHECK_PERIOD == StormData.CHECK_RESIDUE) {
            check(now);
        }
        if (enraged && !dying && now >= nextSkull) {
            skulls();
            nextSkull = now + StormData.SKULL_GAP;
        }
        if (free && !pinned && !dying && now >= nextTaunt) {
            say(StormData.TAUNTS.get(random.nextInt(StormData.TAUNTS.size())));
            nextTaunt = now + StormData.TAUNT_GAP;
        }
        fireballs();
    }

    /** A player on a pad presses its pillar on the next check tick (ADS's pad boxes; OPEN: Q2). */
    private void pads(long now) {
        for (ServerPlayer p : ctx.players()) {
            if (ctx.isGhost(p)) {
                continue;
            }
            for (int i = 0; i < StormData.PADS.length; i++) {
                int[] b = StormData.PADS[i];
                if (p.getX() >= b[0] && p.getX() <= b[1] + 1 && p.getZ() >= b[2] && p.getZ() <= b[3] + 1
                    && p.getY() >= StormData.PAD_MIN_Y - 0.5 && p.getY() <= StormData.PAD_MAX_Y && pillars[i].resting()) {
                    long next = now + ((StormData.CHECK_RESIDUE - now % StormData.CHECK_PERIOD) + StormData.CHECK_PERIOD)
                        % StormData.CHECK_PERIOD;
                    pillars[i].press(next);
                    SimLog.event("storm_press", "pillar", StormData.PILLAR_NAMES[i], "at", next);
                }
            }
        }
    }

    private void move() {
        if (!pinned && !dying) {
            double speed = StormData.ROUTE_SPEED;
            Vec3 target;
            if (!free) {
                target = waypoint < StormData.ROUTE.length ? StormData.ROUTE[waypoint] : null;
            } else {
                // Free: over the next pillar the party can still drop (Purple first, then Yellow, then Green).
                speed = StormData.FREE_SPEED;
                target = goal == null ? freeGoal() : goal;
                goal = target;
            }
            if (target != null) {
                Vec3 d = target.subtract(pos);
                double len = d.length();
                if (len <= speed) {
                    pos = target;
                    if (!free) {
                        waypoint++;
                        parked = waypoint >= StormData.ROUTE.length;
                    }
                } else {
                    pos = pos.add(d.scale(speed / len));
                }
            }
        }
        if (dying) {
            return; // the corpse turns him now
        }
        ServerPlayer near = nearest(pos, 64);
        float yaw = near == null ? storm.getYRot()
            : (float) (Math.toDegrees(Math.atan2(near.getZ() - pos.z, near.getX() - pos.x)) - 90.0);
        storm.snapTo(pos.x, pos.y, pos.z, yaw, 0f);
        storm.setYHeadRot(yaw);
        nameTag.snapTo(pos.x, pos.y + 3.69, pos.z, 0f, 0f);
        bossTag.snapTo(pos.x, pos.y + 4.0, pos.z, 0f, 0f);
    }

    /** Where he heads when free: the middle of the first unspent pillar's zone (Purple, Yellow, Green). */
    private Vec3 freeGoal() {
        for (int i = 0; i < 3; i++) {
            if (!pillars[i].spent()) {
                Pillar p = pillars[i];
                return new Vec3(p.minX + 3.5, i == 0 ? 179.3 : 173.0, p.minZ + 3.5);
            }
        }
        return pos;
    }

    private void check(long now) {
        if (pinned) {
            return;
        }
        for (Pillar p : pillars) {
            if (p.crushes(pos, now)) {
                crush(p, now);
                return;
            }
        }
    }

    private void crush(Pillar pillar, long now) {
        crushes++;
        pinned = true;
        goal = null;
        // He is pinned under the pillar: his feet a head below its bottom.
        pos = new Vec3(pos.x, Math.max(StormData.FLOOR, pillar.bottom - StormData.HEAD), pos.z);
        boolean silent = crushes >= 2 && random.nextDouble() < StormData.SILENT_CRUSH_2;
        if (!silent) {
            ctx.chat().release();
            say(StormData.CRUSH_LINES.get(random.nextInt(StormData.CRUSH_LINES.size())));
        }
        flag(1000f);
        fight.clock.after(2, () -> flag(1f));
        ctx.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_HURT, SoundSource.HOSTILE, 4f, 1f);
        fight.clock.after(StormData.COLLAPSE, pillar::collapse);
        SimLog.event("storm_crush", "pillar", StormData.PILLAR_NAMES[pillar.index], "crush", crushes, "silent", silent);
        if (crushes >= 2 || enraged) {
            fight.clock.after(StormData.DEATH_AFTER_CRUSH2, this::die);
        }
    }

    /** Party damage while he is pinned after crush 1, to 45%: then he is enraged and free. */
    private void damaged(long amount) {
        if (!pinned || enraged || dying) {
            return;
        }
        health -= amount;
        if (health <= maxHealth * StormData.ENRAGE_AT) {
            health = maxHealth * StormData.ENRAGE_AT;
            ctx.bar().progress((float) StormData.ENRAGE_AT);
            enrage();
            return;
        }
        ctx.bar().progress((float) (health / maxHealth));
    }

    private void enrage() {
        enraged = true;
        pinned = false;
        ctx.broadcast("§c⚠ Storm is enraged! ⚠");
        for (ServerPlayer p : ctx.players()) {
            net.rustclear.mod.engine.boss.Titles.send(p, "", "§c⚠ Storm is enraged! ⚠", net.rustclear.mod.engine.boss.Titles.PROGRESS);
        }
        nextSkull = fight.clock.now() + StormData.SKULLS_AFTER_ENRAGE;
        SimLog.event("storm_enrage");
    }

    private void die() {
        if (dying) {
            return;
        }
        dying = true;
        deathAt = fight.clock.now();
        ctx.chat().release();
        ctx.chat().say(BossChat.boss("Storm", StormData.DEATH), StormData.SON_AFTER);
        fight.clock.after(2 + random.nextInt(18), () -> ctx.bar().progress(0f));
        fight.clock.after(StormData.SON_AFTER, () -> ctx.chat().say(BossChat.boss("Storm", StormData.SON), 20));
        fight.clock.after(StormData.GOLDOR_AFTER, () -> over = true);
        java.util.function.BooleanSupplier corpse = corpse();
        corpse.getAsBoolean(); // its tick 0 is the death line's
        fight.linger(corpse);
        SimLog.event("storm_death");
    }

    /**
     * His body after the death line, into P3: it spins where the crush pinned it with a wither.hurt
     * every 9-11 ticks, two explosions and a door break; its health goes to 0 (the death fall) at
     * +60 and it is gone at +80; 24 lightning spirals walk out from it until +366.
     */
    private java.util.function.BooleanSupplier corpse() {
        Vec3 at = pos;
        java.util.Map<Integer, List<Vec3>> bolts = new java.util.HashMap<>();
        for (int start : StormData.SPIRALS) {
            double a0 = random.nextDouble() * 360.0;
            for (int j = 0; j < StormData.SPIRAL_BOLTS; j++) {
                double a = Math.toRadians(a0 + 90.0 * j);
                double r = StormData.SPIRAL_SPACING * j;
                bolts.computeIfAbsent(start + j * StormData.SPIRAL_STEP, k -> new ArrayList<>())
                    .add(new Vec3(at.x + r * Math.cos(a), at.y, at.z + r * Math.sin(a)));
            }
        }
        int last = StormData.SPIRALS[StormData.SPIRALS.length - 1] + (StormData.SPIRAL_BOLTS - 1) * StormData.SPIRAL_STEP;
        int[] n = {0};
        int[] nextHurt = {0};
        float[] yaw = {storm.getYRot()};
        return () -> {
            int t = n[0]++;
            if (t < StormData.HEALTH_ZERO_AFTER && !storm.isRemoved()) {
                yaw[0] += StormData.CORPSE_SPIN;
                storm.snapTo(at.x, at.y, at.z, yaw[0], 0f);
                storm.setYHeadRot(yaw[0]);
                if (t == nextHurt[0]) {
                    ctx.level().playSound(null, at.x, at.y, at.z, SoundEvents.WITHER_HURT, SoundSource.HOSTILE, 15f, 1f);
                    nextHurt[0] += StormData.CORPSE_HURT_MIN + random.nextInt(StormData.CORPSE_HURT_MAX - StormData.CORPSE_HURT_MIN + 1);
                }
            }
            if (t == 0 || t == 4) {
                ctx.level().playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 2f, 0.6f);
            } else if (t == 12) {
                ctx.level().playSound(null, at.x, at.y, at.z, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 3f, 0.9f);
            } else if (t == StormData.HEALTH_ZERO_AFTER) {
                flag(0f);
            } else if (t == StormData.CORPSE_GONE) {
                ctx.entities().discard(storm);
                ctx.entities().discard(nameTag);
                ctx.entities().discard(bossTag);
            }
            List<Vec3> due = bolts.get(t);
            if (due != null) {
                for (Vec3 b : due) {
                    bolt(b);
                }
            }
            return t < last;
        };
    }

    // ---------------------------------------------------------------- attacks

    private void fireball() {
        ServerPlayer target = nearest(pos, 80);
        if (target == null || dying) {
            return;
        }
        Vec3 from = pos.add(0, 0.8, 0);
        Vec3 dir = target.position().subtract(from).normalize();
        LargeFireball f = new LargeFireball(ctx.level(), storm, dir.scale(0.1), 0);
        f.snapTo(from.x, from.y, from.z, 0f, 0f);
        ctx.level().addFreshEntity(f);
        fireballs.add(f);
        fireballLast.add(from);
    }

    /** An impact: the fireball's hit, then its Static Field's seven strikes. */
    private void fireballs() {
        for (int i = fireballs.size() - 1; i >= 0; i--) {
            LargeFireball f = fireballs.get(i);
            if (!f.isRemoved()) {
                fireballLast.set(i, f.position());
                if (f.tickCount > 300) {
                    f.discard();
                }
                continue;
            }
            Vec3 at = fireballLast.get(i);
            fireballs.remove(i);
            fireballLast.remove(i);
            double dmg = StormData.FIREBALL_F7 * ctx.profile().scale("Storm's Lightning Fireball");
            Attacks.Attack hit = new Attacks.Attack("Storm", "Lightning Fireball", dmg, false);
            for (ServerPlayer p : ctx.players()) {
                if (p.position().distanceTo(at) <= StormData.FIREBALL_RADIUS) {
                    ctx.hit(p, hit, dmg);
                }
            }
            java.util.Set<java.util.UUID> hitOnce = new java.util.HashSet<>();
            for (int s : StormData.FIELD_STRIKES) {
                fight.clock.after(s, () -> field(at, hitOnce));
            }
        }
    }

    /** One strike of a field; a player takes a field's hit once (OPEN: how often a field hits). */
    private void field(Vec3 at, java.util.Set<java.util.UUID> hitOnce) {
        double dmg = StormData.FIELD_F7 * ctx.profile().scale("Storm's Static Field");
        Attacks.Attack hit = new Attacks.Attack("Storm", "Static Field", dmg, false);
        for (int b = 0; b < StormData.FIELD_BOLTS; b++) {
            Vec3 bolt = at.add(random.nextGaussian() * 1.5, 0, random.nextGaussian() * 1.5);
            bolt(bolt);
            for (ServerPlayer p : ctx.players()) {
                if (p.position().distanceTo(bolt) <= StormData.FIELD_RADIUS && hitOnce.add(p.getUUID())) {
                    ctx.hit(p, hit, dmg);
                }
            }
        }
    }

    /** Giga Lightning: true damage on every player not hiding under a pillar; 31 bolts round each exposed player. */
    private void giga() {
        boolean m7 = ctx.profile().master;
        for (ServerPlayer p : ctx.players()) {
            if (ctx.isGhost(p)) {
                continue;
            }
            boolean hidden = false;
            for (Pillar pillar : pillars) {
                hidden |= pillar.hides(p.position());
            }
            if (hidden) {
                continue;
            }
            for (int i = 0; i < 31; i++) {
                bolt(p.position().add(random.nextGaussian() * 3, 0, random.nextGaussian() * 3));
            }
            double dmg = m7 ? StormData.GIGA_M7
                : StormData.GIGA_MIN + random.nextDouble() * (StormData.GIGA_MAX - StormData.GIGA_MIN);
            ctx.hit(p, new Attacks.Attack("Storm", "Giga Lightning", dmg, true), dmg);
        }
        SimLog.event("storm_giga");
    }

    private void bolt(Vec3 at) {
        LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(ctx.level(), EntitySpawnReason.TRIGGERED);
        if (bolt != null) {
            bolt.setVisualOnly(true);
            bolt.snapTo(at.x, at.y, at.z, 0f, 0f);
            ctx.level().addFreshEntity(bolt);
        }
    }

    /** A pair of wither skulls at the nearest player. */
    private void skulls() {
        ServerPlayer target = nearest(pos, 48);
        if (target == null) {
            return;
        }
        for (int i = 0; i < 2; i++) {
            Vec3 from = pos.add(i == 0 ? -1.3 : 1.3, 2.0, 0);
            Vec3 dir = target.getBoundingBox().getCenter().subtract(from).normalize();
            WitherSkull skull = new WitherSkull(ctx.level(), storm, dir.scale(0.1));
            skull.snapTo(from.x, from.y, from.z, 0f, 0f);
            ctx.level().addFreshEntity(skull);
            ctx.entities().add(skull);
        }
        ctx.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1f, 1f);
        ctx.level().sendParticles(ParticleTypes.SMOKE, pos.x, pos.y + 2, pos.z, 4, 0.5, 0.5, 0.5, 0);
    }

    private ServerPlayer nearest(Vec3 at, double within) {
        ServerPlayer best = null;
        double bestD = within * within;
        for (ServerPlayer p : ctx.players()) {
            if (ctx.isGhost(p) || p.isSpectator()) {
                continue;
            }
            double d = p.position().distanceToSqr(at);
            if (d <= bestD) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    @Override
    public BossPhase next() {
        return over ? new GoldorPhase() : null;
    }

    @Override
    public void dispose() {
        if (storm != null) {
            ctx.unregisterBoss(storm);
        }
        for (LargeFireball f : fireballs) {
            f.discard();
        }
        fireballs.clear();
    }

    public boolean parked() {
        return parked;
    }

    long deathAt() {
        return deathAt;
    }
}
