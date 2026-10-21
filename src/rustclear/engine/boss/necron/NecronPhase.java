package net.rustclear.mod.engine.boss.necron;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.engine.EngineItems;
import net.rustclear.mod.engine.SimLog;
import net.rustclear.mod.engine.boss.BossChat;
import net.rustclear.mod.engine.boss.BossContext;
import net.rustclear.mod.engine.boss.BossFight;
import net.rustclear.mod.engine.boss.BossFrames;
import net.rustclear.mod.engine.boss.BossPhase;
import net.rustclear.mod.mixin.ArmorStandAccessor;
import net.rustclear.mod.mixin.LivingEntityAccessor;

/**
 * P4, Necron (boss-p4-necron.md, the new script). N = 0 is his first line, 10 after Goldor's
 * "Necron, forgive me."; the floor under the core fell away just before it.
 * - Lines at 0/42/84/126, a taunt at 188 (a second at 394 in a third of runs), "ARGH!" at 270,
 *   "Let's make some space!" at 332.
 * - He stands at (54, 66, 76) with two short excursions (N+78, N+235) and snaps back. His synced
 *   health stays 1.0; the bar "§c§lNecron" is his real health, stepping through 0.8 / 0.25 / 0.05 / 0
 *   as gates open (OPEN 52) and the party hits him.
 * - Fireballs (two volleys of 8 at 1.07 a tick), 30 lightning bolts, Nuclear Frenzy pulses, two
 *   skulls, the lantern columns flashing; the south platform falls at N+59 and a random corner at
 *   N+275; 10 Wither TNT at ARGH + 100, he is removed 20 later and the run is won 44 after the TNT.
 */
public final class NecronPhase implements BossPhase {
    private final Runnable removeGoldor;
    private BossFight fight;
    private BossContext ctx;
    private Random random;
    private WitherBoss necron;
    private ArmorStand nameTag;
    private ArmorStand bossTag;
    private Vec3 pos = NecronData.HOME;
    private Vec3 heading;
    private float bar;
    private boolean damageable;
    private boolean over;
    /** M7: hand over to the Wither King. */
    private boolean toKing;
    private final List<Fireball> fireballs = new ArrayList<>();
    private final List<PrimedTnt> tnt = new ArrayList<>();
    /** The lantern columns' blocks: per column, the 3x3 rings from y 59 up whose blocks are iron or lantern. */
    private final List<List<BlockPos[]>> columns = new ArrayList<>();
    private final List<Integer> columnBottom = new ArrayList<>();

    private record Fireball(LargeFireball entity, Vec3 velocity, long born) {}

    /** [removeGoldor] takes Goldor's body away when Necron's goes (the same tick since the update). */
    public NecronPhase(Runnable removeGoldor) {
        this.removeGoldor = removeGoldor;
    }

    @Override
    public String name() {
        return "necron";
    }

    @Override
    public void start(BossFight fight) {
        this.fight = fight;
        this.ctx = fight.ctx;
        this.random = ctx.random();
        ctx.chat().reset();
        spawn();
        ctx.bar().boss("Necron", 0f);
        for (int i = 0; i < NecronData.INTRO.size(); i++) {
            String line = NecronData.INTRO.get(i);
            fight.clock.at(NecronData.INTRO_AT[i], () -> say(line));
        }
        boolean two = random.nextDouble() < NecronData.SECOND_TAUNT;
        String first = random.nextDouble() < NecronData.FIRST_IS_SOMETIMES ? NecronData.SOMETIMES : NecronData.WITNESS;
        fight.clock.at(NecronData.TAUNT_1, () -> say(first));
        if (two) {
            String second = random.nextBoolean() ? NecronData.SOMETIMES : NecronData.WITNESS;
            fight.clock.at(NecronData.TAUNT_2, () -> say(second));
        }
        fight.clock.at(NecronData.ARGH, () -> say(NecronData.ARGH_LINE));
        fight.clock.at(NecronData.SPACE, () -> say(NecronData.SPACE_LINE));
        // Excursions and snaps.
        fight.clock.at(NecronData.MOVE_1, () -> heading = excursion(true));
        int snap1 = NecronData.SNAP_1_MIN + random.nextInt(NecronData.SNAP_1_MAX - NecronData.SNAP_1_MIN + 1);
        fight.clock.at(snap1, this::snap);
        fight.clock.at(NecronData.MOVE_2, () -> heading = excursion(false));
        fight.clock.at(NecronData.SNAP_2, this::snap);
        fight.clock.at(NecronData.DAMAGEABLE, () -> {
            damageable = true;
            setBar(1f);
        });
        // Attacks.
        for (int v : new int[] {NecronData.VOLLEY_1, NecronData.VOLLEY_2}) {
            for (int i = 0; i < NecronData.VOLLEY_SIZE; i++) {
                fight.clock.at(v + i * NecronData.VOLLEY_GAP, this::fireball);
            }
        }
        for (int t = NecronData.BOLT_FIRST; t <= NecronData.BOLT_LAST; t += 10) {
            fight.clock.at(t, this::bolt);
        }
        for (int t : NecronData.FRENZY) {
            fight.clock.at(t, () -> {
                ctx.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 30f, 0.49f);
                ctx.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 30f, 0.7f);
            });
        }
        fight.clock.at(NecronData.SKULLS_MIN + random.nextInt(NecronData.SKULLS_MAX - NecronData.SKULLS_MIN + 1), this::skulls);
        if (ctx.profile().master) {
            // M7: 4-10 skulls (alpha): two at N+104, more at 119-149 and 240-312.
            fight.clock.at(104, this::skulls);
            fight.clock.at(119 + random.nextInt(31), this::skulls);
            for (int i = random.nextInt(3); i > 0; i--) {
                fight.clock.at(240 + random.nextInt(73), this::skulls);
            }
        }
        fight.clock.at(NecronData.TNT, this::tnt);
        fight.clock.at(NecronData.REMOVED, this::remove);
        if (ctx.profile().master) {
            // M7: Necron's removal starts P5 (the relics, the dragons); the run is won there.
            fight.clock.at(NecronData.REMOVED, () -> toKing = true);
        } else {
            fight.clock.at(NecronData.DEFEATED, this::defeated);
        }
        for (int k = 0; k < 24; k++) {
            int t = NecronData.THROES + 5 * k;
            fight.clock.at(t, () -> ctx.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_HURT, SoundSource.HOSTILE, 15f, 1f));
            if (k % 2 == 0) {
                fight.clock.at(t, () -> ctx.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(),
                    SoundSource.BLOCKS, 15f, 0.49f));
            }
        }
        // The arena.
        BossFrames.play(fight.clock, ctx.blocks(), "p4_blocks.json", "south", NecronData.SOUTH, null);
        fight.clock.at(NecronData.CORNER, () -> {
            String corner = NecronData.CORNERS[pick(NecronData.CORNER_WEIGHTS)];
            BossFrames.play(fight.clock, ctx.blocks(), "p4_blocks.json", corner, 0, null);
            SimLog.event("necron_corner", "corner", corner);
        });
        findColumns();
        SimLog.event("necron_start");
    }

    private void say(String line) {
        ctx.chat().say(BossChat.boss("Necron", line), 1);
        ctx.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_AMBIENT, SoundSource.MASTER, 5f, 1.19f);
    }

    private int pick(int[] weights) {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int r = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    private void spawn() {
        necron = EntityTypes.WITHER.create(ctx.level(), EntitySpawnReason.TRIGGERED);
        necron.setNoAi(true);
        necron.setNoGravity(true);
        necron.setPersistenceRequired();
        var max = necron.getAttribute(Attributes.MAX_HEALTH);
        if (max != null) {
            max.setBaseValue(ctx.profile().bossMaxHealth);
        }
        necron.snapTo(pos.x, pos.y, pos.z, 0f, 0f);
        ctx.level().addFreshEntity(necron);
        ctx.entities().add(necron);
        // Synced health 1.0 all fight (the armour look); the bar is his real health.
        necron.getEntityData().set(LivingEntityAccessor.rustclear$healthId(), 1f);
        ctx.registerBoss(necron, this::damaged);
        nameTag = label(" §cNecron", 3.69);
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

    /** An excursion: off-centre in a random direction and a little down. */
    private Vec3 excursion(boolean first) {
        double a = random.nextDouble() * Math.PI * 2;
        double drop = first ? -0.125 : -0.06;
        return new Vec3(Math.cos(a) * NecronData.MOVE_SPEED, drop, Math.sin(a) * NecronData.MOVE_SPEED);
    }

    private void snap() {
        heading = null;
        pos = NecronData.HOME;
        ctx.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 10f, 0.49f);
    }

    // ---------------------------------------------------------------- health

    /** The bar's floor now: the next plateau isn't reachable until its gate opens. */
    private float floor() {
        long n = fight.clock.now();
        float floor = 1f;
        for (int i = 0; i < NecronData.GATE_AT.length; i++) {
            if (n >= NecronData.GATE_AT[i]) {
                floor = NecronData.PLATEAUS[i];
            }
        }
        return floor;
    }

    private void damaged(long amount) {
        if (!damageable || necron == null) {
            return;
        }
        ctx.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_DEATH, SoundSource.HOSTILE, 0.5f,
            0.87f + random.nextFloat() * 0.26f);
        float drop = (float) (amount / ctx.profile().bossMaxHealth);
        float to = Math.max(floor(), bar - drop);
        if (to < bar) {
            ramp(bar, to);
        }
    }

    /** A step drawn as a 3-tick ramp (e.g. 0.168, 0.568, 0.8 on the way to 0.8). */
    private void ramp(float from, float to) {
        bar = to;
        for (int i = 1; i <= 3; i++) {
            float v = from + (to - from) * i / 3f;
            fight.clock.after(i - 1, () -> ctx.bar().progress(v));
        }
    }

    private void setBar(float v) {
        bar = v;
        ctx.bar().progress(v);
    }

    // ---------------------------------------------------------------- ticking

    @Override
    public void tick() {
        long n = fight.clock.now();
        if (necron != null) {
            if (heading != null) {
                pos = pos.add(heading);
            }
            ServerPlayer near = nearest(64);
            float yaw = near == null ? 0f : (float) (Math.toDegrees(Math.atan2(near.getZ() - pos.z, near.getX() - pos.x)) - 90.0);
            necron.snapTo(pos.x, pos.y, pos.z, yaw, 0f);
            necron.setYHeadRot(yaw);
            nameTag.snapTo(pos.x, pos.y + 3.69, pos.z, 0f, 0f);
            bossTag.snapTo(pos.x, pos.y + 4.0, pos.z, 0f, 0f);
        }
        // Each gate lets the bar down to its plateau on the party's next hit; F7 hits are far over what a step needs.
        moveFireballs(n);
        flash(n);
    }

    private ServerPlayer nearest(double within) {
        ServerPlayer best = null;
        double bestD = within * within;
        for (ServerPlayer p : ctx.players()) {
            if (ctx.isGhost(p) || p.isSpectator()) {
                continue;
            }
            double d = p.position().distanceToSqr(pos);
            if (d <= bestD) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- attacks

    private void fireball() {
        if (necron == null) {
            return;
        }
        float yaw = NecronData.FIRE_YAWS[pick(NecronData.FIRE_YAW_WEIGHTS)];
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(NecronData.FIRE_PITCH);
        Vec3 dir = new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
        Vec3 from = pos.add(0, NecronData.FIRE_Y, 0);
        LargeFireball f = new LargeFireball(ctx.level(), necron, dir.scale(0.1), 0);
        f.snapTo(from.x, from.y, from.z, yaw, (float) NecronData.FIRE_PITCH);
        Vec3 v = dir.scale(NecronData.FIRE_SPEED);
        f.setDeltaMovement(v);
        ctx.level().addFreshEntity(f);
        ctx.entities().add(f);
        fireballs.add(new Fireball(f, v, fight.clock.now()));
    }

    /** Straight at 1.07 a tick; at the end of its life (or on hitting something) a radius-0 explosion: sound and smoke only. */
    private void moveFireballs(long n) {
        for (int i = fireballs.size() - 1; i >= 0; i--) {
            Fireball fb = fireballs.get(i);
            LargeFireball f = fb.entity;
            if (!f.isRemoved() && n - fb.born < NecronData.FIRE_LIFE) {
                f.setDeltaMovement(fb.velocity);
                continue;
            }
            Vec3 at = f.position();
            f.discard();
            fireballs.remove(i);
            ctx.level().sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0, 0, 0, 0);
            ctx.level().playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4f, 0.7f);
        }
    }

    /** A bolt round the arena's edge (x 16-30 or 79-90), now and then at Necron himself. */
    private void bolt() {
        Vec3 at;
        if (random.nextInt(10) == 0) {
            at = NecronData.HOME;
        } else {
            double x = random.nextBoolean() ? 16 + random.nextDouble() * 14 : 79 + random.nextDouble() * 11;
            at = new Vec3(x, 59 + random.nextInt(5), 30 + random.nextDouble() * 90);
        }
        LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(ctx.level(), EntitySpawnReason.TRIGGERED);
        if (bolt != null) {
            bolt.setVisualOnly(true);
            bolt.snapTo(at.x, at.y, at.z, 0f, 0f);
            ctx.level().addFreshEntity(bolt);
        }
    }

    private void skulls() {
        ServerPlayer target = nearest(80);
        if (target == null || necron == null) {
            return;
        }
        for (int i = 0; i < 2; i++) {
            Vec3 from = pos.add(i == 0 ? -1.3 : 1.3, 2.0, 0);
            Vec3 dir = target.getBoundingBox().getCenter().subtract(from).normalize();
            WitherSkull skull = new WitherSkull(ctx.level(), necron, dir.scale(0.1));
            skull.snapTo(from.x, from.y, from.z, 0f, 0f);
            ctx.level().addFreshEntity(skull);
            ctx.entities().add(skull);
        }
        ctx.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 0.2f, 1.49f);
    }

    /** 10 Wither TNT at him, 0.2 up, fuse 62: the run ends (and they go) before any goes off. */
    private void tnt() {
        for (int i = 0; i < NecronData.TNT_COUNT; i++) {
            PrimedTnt t = new PrimedTnt(ctx.level(), pos.x, pos.y, pos.z, null);
            t.setFuse(NecronData.TNT_FUSE);
            t.setDeltaMovement(0, 0.2, 0);
            ctx.level().addFreshEntity(t);
            ctx.entities().add(t);
            tnt.add(t);
        }
        if (bar > 0) {
            ramp(bar, 0f);
        }
        SimLog.event("necron_tnt");
    }

    private void remove() {
        if (necron != null) {
            ctx.unregisterBoss(necron);
            ctx.entities().discard(necron);
            ctx.entities().discard(nameTag);
            ctx.entities().discard(bossTag);
            necron = null;
        }
        if (removeGoldor != null) {
            removeGoldor.run();
        }
        SimLog.event("necron_removed");
    }

    private void defeated() {
        for (PrimedTnt t : tnt) {
            ctx.entities().discard(t);
        }
        tnt.clear();
        over = true;
        ctx.defeated();
    }

    // ---------------------------------------------------------------- lantern columns

    private void findColumns() {
        for (int[] c : NecronData.LANTERN_COLUMNS) {
            List<BlockPos[]> rings = new ArrayList<>();
            int bottom = -1;
            for (int y = 50; y <= 90; y++) {
                BlockPos[] ring = new BlockPos[9];
                boolean any = false;
                for (int i = 0; i < 9; i++) {
                    BlockPos p = new BlockPos(c[0] + i % 3, y, c[1] + i / 3);
                    BlockState b = ctx.blocks().get(p);
                    if (b.is(Blocks.IRON_BLOCK) || b.is(Blocks.SEA_LANTERN)) {
                        ring[i] = p;
                        any = true;
                    }
                }
                if (any) {
                    if (bottom < 0) {
                        bottom = y;
                    }
                    rings.add(ring);
                } else if (bottom >= 0) {
                    break;
                }
            }
            columns.add(rings);
            columnBottom.add(bottom);
        }
    }

    /**
     * Each column: rings 7 levels apart light up (sea lantern) and move up a level every 2 ticks,
     * each lit for 5 ticks (iron block again), from N+21 to N+320 (OPEN: shape from two recorded columns).
     */
    private void flash(long n) {
        if (n < NecronData.FLASH_FROM || n > NecronData.FLASH_TO + NecronData.RING_LIT || (n - NecronData.FLASH_FROM) % 1 != 0) {
            return;
        }
        long t = n - NecronData.FLASH_FROM;
        for (List<BlockPos[]> rings : columns) {
            int h = rings.size();
            if (h == 0) {
                continue;
            }
            if (t % NecronData.RING_STEP == 0 && n <= NecronData.FLASH_TO) {
                int k = (int) (t / NecronData.RING_STEP);
                for (int level = k % NecronData.RING_GAP; level < h; level += NecronData.RING_GAP) {
                    set(rings.get(level), Blocks.SEA_LANTERN.defaultBlockState());
                }
            }
            long off = t - NecronData.RING_LIT;
            if (off >= 0 && off % NecronData.RING_STEP == 0) {
                int k = (int) (off / NecronData.RING_STEP);
                for (int level = k % NecronData.RING_GAP; level < h; level += NecronData.RING_GAP) {
                    set(rings.get(level), Blocks.IRON_BLOCK.defaultBlockState());
                }
            }
        }
    }

    private void set(BlockPos[] ring, BlockState state) {
        for (BlockPos p : ring) {
            if (p != null) {
                ctx.blocks().set(p, state);
            }
        }
    }

    // ---------------------------------------------------------------- the phase

    @Override
    public BossPhase next() {
        return toKing ? new net.rustclear.mod.engine.boss.witherking.WitherKingPhase() : null;
    }

    @Override
    public boolean ended() {
        return over;
    }

    @Override
    public void dispose() {
        for (Fireball f : fireballs) {
            f.entity.discard();
        }
        fireballs.clear();
        for (PrimedTnt t : tnt) {
            t.discard();
        }
        tnt.clear();
        if (necron != null) {
            ctx.unregisterBoss(necron);
        }
    }

}
