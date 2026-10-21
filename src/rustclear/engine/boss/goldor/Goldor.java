package net.rustclear.mod.engine.boss.goldor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Giant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.engine.EngineItems;
import net.rustclear.mod.engine.boss.BossContext;
import net.rustclear.mod.mixin.ArmorStandAccessor;
import net.rustclear.mod.mixin.LivingEntityAccessor;

/**
 * Goldor on his track (boss-p3-goldor.md §8): a no-AI wither that walks the loop round P3's four
 * lines from (80, 119, 40), 0.06 a tick, slowed by hits to 0.06 x (1 - 0.1k); sprints 0.6 to the
 * section in progress when a door opened [SPRINT_LAG] ticks ago and he is still in the finished
 * section's segment; flies 0.8 to the core once everyone is in. Four invisible giants with golden
 * swords orbit under him (his "greatswords"). His synced health is 1 (armoured) on the track in
 * 46 of 55 runs, else full.
 */
final class Goldor {
    private final BossContext ctx;
    private final Random random;
    private WitherBoss boss;
    private ArmorStand nameTag;
    private ArmorStand bossTag;
    private final List<Giant> giants = new ArrayList<>();
    private double giantAngle;

    /** Distance along the loop from the S4/S1 corner. */
    double s = GoldorData.START_S;
    /** Still on the S4 line from his start (counts as before S1 for death ticks). */
    boolean firstLap = true;
    private double sprintTo = -1;
    private long sprintAt = -1;
    private int sprintSection;
    boolean flying;
    boolean arrived;
    boolean dead;
    private Vec3 pos = GoldorData.SPAWN;
    private final boolean armouredRun;
    private final int armourAt;
    /** The giants appear at n 11-19. */
    private final int giantsAt;
    private long armourOffAt = Long.MAX_VALUE;
    private long nextHurt;
    /** Ticks of the recent hits (the slowing ladder). */
    private final ArrayDeque<Long> hits = new ArrayDeque<>();

    Goldor(BossContext ctx) {
        this.ctx = ctx;
        this.random = ctx.random();
        this.armouredRun = random.nextDouble() < GoldorData.ARMOURED;
        this.armourAt = 1 + random.nextInt(7);
        this.giantsAt = GoldorData.GIANTS_FROM + random.nextInt(9);
    }

    Vec3 position() {
        return pos;
    }

    WitherBoss entity() {
        return boss;
    }

    void spawn() {
        ServerLevel level = ctx.level();
        boss = EntityTypes.WITHER.create(level, EntitySpawnReason.TRIGGERED);
        boss.setNoAi(true);
        boss.setNoGravity(true);
        boss.setPersistenceRequired();
        boss.setInvulnerableTicks(0);
        var max = boss.getAttribute(Attributes.MAX_HEALTH);
        if (max != null) {
            max.setBaseValue(ctx.profile().bossMaxHealth);
        }
        pos = trackPos(s);
        boss.snapTo(pos.x, pos.y, pos.z, -90f, 0f);
        level.addFreshEntity(boss);
        ctx.entities().add(boss);
        health(1f);
        nameTag = label(" §6Goldor", 3.69);
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

    /** The synced health: 1 shows his armour; full (the max attribute) without. */
    private void health(float flag) {
        if (boss != null && !boss.isRemoved()) {
            boss.getEntityData().set(LivingEntityAccessor.rustclear$healthId(),
                flag == 1f && armouredRun ? 1f : (float) ctx.profile().bossMaxHealth);
        }
    }

    /** A player hit him (any weapon): it slows his walk for a while. */
    void hit(long n) {
        hits.add(n);
    }

    /** His walking speed now: 0.06 x (1 - 0.1k), k up the ladder with the hits of the last [SLOW_WINDOW] ticks. */
    double walkSpeed(long n) {
        while (!hits.isEmpty() && hits.peekFirst() < n - GoldorData.SLOW_WINDOW) {
            hits.pollFirst();
        }
        int count = hits.size();
        if (count == 0) {
            return GoldorData.WALK;
        }
        int k = GoldorData.SLOW_K[Math.min(count, GoldorData.SLOW_K.length) - 1];
        return GoldorData.WALK * (1 - 0.1 * k);
    }

    /** Section [section]'s door opened at [n]: if he is still in its segment, he sprints on [SPRINT_LAG] later. */
    void sectionEnded(int section, long n) {
        if (flying || section < 1 || section > 3) {
            return;
        }
        if (inSegment(section) && sprintAt < 0) {
            sprintAt = n + GoldorData.SPRINT_LAG;
            sprintSection = section;
        }
    }

    private boolean inSegment(int section) {
        return section == 1 ? s >= GoldorData.S1_ENTRY || s < GoldorData.BOUNDS[1] : segment(s) == section - 1;
    }

    void fly(long n) {
        if (flying) {
            return;
        }
        flying = true;
        armourOffAt = n + GoldorData.KILL_AFTER_FLY - 4 - random.nextInt(11);
    }

    /** "....": he stops where he is (no death animation) and stays into P4. */
    void die() {
        dead = true;
    }

    /** Back on after "Necron, forgive me." in an armoured run. */
    void reArmour() {
        health(1f);
    }

    void tick(long n) {
        if (boss == null || dead) {
            return;
        }
        aura();
        if (n == armourAt) {
            health(1f);
        }
        if (n >= armourOffAt) {
            armourOffAt = Long.MAX_VALUE;
            if (boss != null) {
                boss.getEntityData().set(LivingEntityAccessor.rustclear$healthId(), (float) ctx.profile().bossMaxHealth);
            }
        }
        if (n >= giantsAt && giants.isEmpty()) {
            spawnGiants();
        }
        if (flying) {
            if (n >= nextHurt) {
                // The party hits him from the moment he leaves: the red hurt flash every few ticks.
                nextHurt = n + 2 + random.nextInt(11);
                for (ServerPlayer p : ctx.players()) {
                    p.connection.send(new ClientboundHurtAnimationPacket(boss));
                }
            }
            if (!arrived) {
                Vec3 to = GoldorData.CORE_POINT.subtract(pos);
                double d = Math.sqrt(to.x * to.x + to.z * to.z);
                double step = Math.min(GoldorData.FLY, d);
                double k = d < 1e-6 ? 0 : step / d;
                pos = new Vec3(pos.x + to.x * k, Math.max(GoldorData.CORE_POINT.y, pos.y - 0.03), pos.z + to.z * k);
                arrived = d <= GoldorData.FLY;
            } else {
                double v = pos.z < GoldorData.CORE_IN_Z ? 0.4 : 0.07;
                double dx = Math.max(-0.1, Math.min(0.1, GoldorData.CORE_IN_X - pos.x));
                pos = new Vec3(pos.x + dx, Math.max(116.75, pos.y - 0.006), pos.z + v);
            }
        } else {
            if (sprintAt >= 0 && n >= sprintAt && sprintTo < 0) {
                if (inSegment(sprintSection)) {
                    sprintTo = GoldorData.SPRINT_TO[sprintSection - 1];
                }
                sprintAt = -1;
            }
            if (sprintTo >= 0) {
                double left = ((sprintTo - s) % GoldorData.LOOP + GoldorData.LOOP) % GoldorData.LOOP;
                if (left <= GoldorData.SPRINT) {
                    s = sprintTo;
                    sprintTo = -1;
                } else {
                    s += GoldorData.SPRINT;
                }
            } else {
                s += walkSpeed(n);
            }
            if (s >= GoldorData.LOOP) {
                firstLap = false;
            }
            s %= GoldorData.LOOP;
            pos = trackPos(s);
        }
        float yaw;
        if (arrived) {
            yaw = 0f;
        } else if (flying) {
            yaw = (float) Math.toDegrees(Math.atan2(-(GoldorData.CORE_POINT.x - pos.x), GoldorData.CORE_POINT.z - pos.z));
        } else {
            // Walking: S1 +z, S2 -x, S3 -z, S4 +x.
            yaw = switch (segment(s)) {
                case 0 -> 0f;
                case 1 -> 90f;
                case 2 -> 180f;
                default -> -90f;
            };
        }
        boss.snapTo(pos.x, pos.y, pos.z, yaw, 0f);
        boss.setYHeadRot(yaw);
        nameTag.snapTo(pos.x, pos.y + 3.69, pos.z, 0f, 0f);
        bossTag.snapTo(pos.x, pos.y + 4.0, pos.z, 0f, 0f);
        orbit();
    }

    /** Main's aura: one or two angry-villager particles a tick, 3-6 blocks off him, half a block up (~1.34 a tick). */
    private void aura() {
        int k = random.nextDouble() < 0.34 ? 2 : 1;
        for (int i = 0; i < k; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = 3 + random.nextDouble() * 3;
            ctx.level().sendParticles(ParticleTypes.ANGRY_VILLAGER, pos.x + Math.cos(a) * r, pos.y + 0.5,
                pos.z + Math.sin(a) * r, 1, 0, 0, 0, 1.0);
        }
    }

    private void spawnGiants() {
        for (int i = 0; i < 4; i++) {
            Giant g = EntityTypes.GIANT.create(ctx.level(), EntitySpawnReason.TRIGGERED);
            if (g == null) {
                continue;
            }
            g.setNoAi(true);
            g.setSilent(true);
            g.setPermanentlyInvulnerable(true);
            g.setNoGravity(true);
            g.setInvisible(true);
            g.setPersistenceRequired();
            // The effect keeps it invisible (vanilla clears the flag without one); no particles.
            g.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
            g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD));
            Vec3 at = giantPos(i);
            g.snapTo(at.x, at.y, at.z, 0f, 0f);
            ctx.level().addFreshEntity(g);
            ctx.entities().add(g);
            giants.add(g);
        }
    }

    private Vec3 giantPos(int i) {
        double a = Math.toRadians(giantAngle + 90.0 * i);
        Vec3 c = pos.add(GoldorData.GIANT_OFFSET);
        return new Vec3(c.x + GoldorData.GIANT_RADIUS * Math.cos(a), c.y, c.z + GoldorData.GIANT_RADIUS * Math.sin(a));
    }

    private void orbit() {
        if (giants.isEmpty()) {
            return;
        }
        giantAngle += GoldorData.GIANT_STEP;
        for (int i = 0; i < giants.size(); i++) {
            Vec3 g = giantPos(i);
            giants.get(i).snapTo(g.x, g.y, g.z, (float) (giantAngle + 90.0 * i), 0f);
        }
    }

    /**
     * Where each giant's golden sword is: a giant (12 tall) holds it about 7.5 up and 2.5 to its
     * right (its yaw), so the blades sweep at Goldor's height.
     */
    List<Vec3> swords() {
        List<Vec3> out = new ArrayList<>(giants.size());
        for (Giant g : giants) {
            double yaw = Math.toRadians(g.getYRot());
            out.add(new Vec3(g.getX() - Math.cos(yaw) * 2.5, g.getY() + 7.5, g.getZ() - Math.sin(yaw) * 2.5));
        }
        return out;
    }

    void remove() {
        if (boss != null) {
            ctx.unregisterBoss(boss);
            ctx.entities().discard(boss);
            boss = null;
        }
        for (Giant g : giants) {
            ctx.entities().discard(g);
        }
        giants.clear();
        if (nameTag != null) {
            ctx.entities().discard(nameTag);
            ctx.entities().discard(bossTag);
        }
    }

    // ---------------------------------------------------------------- the track

    /** The segment (0-3: S1-S4) [s] is in. */
    static int segment(double s) {
        int i = 0;
        while (i < 3 && s >= GoldorData.BOUNDS[i + 1]) {
            i++;
        }
        return i;
    }

    static Vec3 trackPos(double s) {
        int i = segment(s);
        double f = Math.max(0, Math.min(1, (s - GoldorData.BOUNDS[i]) / (GoldorData.BOUNDS[i + 1] - GoldorData.BOUNDS[i])));
        Vec3 a = GoldorData.CORNERS[i];
        Vec3 b = GoldorData.CORNERS[(i + 1) % 4];
        return new Vec3(a.x + (b.x - a.x) * f, GoldorData.Y_FROM[i] + (GoldorData.Y_TO[i] - GoldorData.Y_FROM[i]) * f,
            a.z + (b.z - a.z) * f);
    }
}
