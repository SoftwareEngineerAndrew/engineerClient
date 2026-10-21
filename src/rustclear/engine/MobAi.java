package net.rustclear.mod.engine;

import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import java.util.Random;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One dungeon mob's brain (Smalldungeons entity/dungeon_mobs/ai run_mob_ai): it notices a player
 * it can see, calls nearby mobs in, follows a room-scoped A* path ({@link MobPath}) after them in
 * its archetype's movement style and swings or shoots. Physics (gravity, per-axis wall/player
 * collision, auto-step, path-gated ledge jumps, fall safety) is ai/physics.rs. As on the server,
 * a melee swing that reaches its target and an arrow that hits deal the mob's floor damage (Combat).
 */
final class MobAi {
    /** MovementStyle. [preferred] is the hold distance or, for the strafes, the orbit radius. */
    enum Movement { APPROACH, MAINTAIN_DISTANCE, CIRCLE_STRAFE, LIGHT_STRAFE, HYBRID }

    /** ActivationState. */
    private enum Activation { DORMANT, IDLE, COMBAT }

    /** AiProfile: what profile_for gives each archetype. */
    record Profile(boolean implemented, double vision, double leash, Movement movement, double preferred, double tolerance,
                   double meleeRange, int meleeCooldown, double rangedRange, int rangedCooldown, double arrowSpeedBps,
                   double speed, boolean hops, boolean roomConfined, boolean ambush) {

        static Profile of(MobSystem.Archetype a) {
            if (a == null) {
                return new Profile(false, 0, 0, Movement.APPROACH, 0, 0, 0, 0, 0, 0, 0, 1, false, false, false);
            }
            return switch (a) {
                case SUPER_TANK_ZOMBIE, ZOMBIE_SOLDIER, ZOMBIE_KNIGHT, CRYPT_LURKER ->
                    melee(32, 1.0);
                case ZOMBIE_LORD -> melee(32, 1.2);
                // Arrows leave at 1.59 b/t median, vanilla's 1.6 (mobs-spawns-pathing.md §5); fire spacing median 26-32.
                case SKELETON_SOLDIER, SKELETON_MASTER, SKELETON_LORD, SUPER_ARCHER ->
                    new Profile(true, 32, LEASH, Movement.MAINTAIN_DISTANCE, 15, 2, 0, 0, 17, 30, ARROW_BPS, 1, false, false, false);
                case SNIPER -> new Profile(false, 128, LEASH, Movement.APPROACH, 0, 0, 0, 0, 0, 0, 0, 1, false, false, false);
                case CRYPT_DREADLORD ->
                    new Profile(true, 16, LEASH, Movement.CIRCLE_STRAFE, 2.5, 0, 2.5, MELEE_COOLDOWN, 0, 0, 0, 1, false, false, false);
                // Its wither skulls 20 ticks apart (121 of 158 gaps) at about 0.5 b/t.
                case CRYPT_SOULEATER ->
                    new Profile(true, 16, LEASH, Movement.HYBRID, 10, 6, 5, MELEE_COOLDOWN, 16, 20, SKULL_BPS, 1, false, false, false);
                case CRYPT_UNDEAD, KING_MIDAS -> melee(16, 1.0);
                case WITHERMANCER -> melee(16, 1.5);
                case FELS -> new Profile(true, 32, 0, Movement.APPROACH, 0, 0, 2.5, MELEE_COOLDOWN, 0, 0, 0, 3.0, false, false, false);
                case ZOMBIE_COMMANDER ->
                    new Profile(true, 16, LEASH, Movement.MAINTAIN_DISTANCE, 6, 1, WHIP_RANGE, WHIP_COOLDOWN, 0, 0, 0, 1, false, false, false);
                // The Lost Adventurer's arrows: 3.0 b/t, 10 ticks apart.
                case LOST_ADVENTURER, ANGRY_ARCHAEOLOGIST, FROZEN_ADVENTURER ->
                    new Profile(true, 24, LEASH, Movement.LIGHT_STRAFE, 1, 0, 2.5, MELEE_COOLDOWN, 12, 10, 60, 1, true, true, false);
                case MIMIC -> melee(32, 2.5);
                case SHADOW_ASSASSIN ->
                    new Profile(true, 16, LEASH, Movement.CIRCLE_STRAFE, ORBIT_RADIUS, 0, 2.5, 6, 0, 0, 0, 1.5, false, true, true);
                // The Watcher's mobs chase the camper (their AI wasn't measured: blood-watcher.md "What a simulator needs").
                case BLOOD_UNDEAD, BLOOD_BOSS, PARASITE -> melee(32, 1.0);
                // A giant stands where it lands and hits what comes in reach. OPEN: the giant's attacks
                // P1's adds hold their ledges and shoot wither skulls (their movement wasn't measured).
                case WITHER_MINER, WITHER_GUARD -> new Profile(true, 32, 0, Movement.APPROACH, 0, 0, 2.5, MELEE_COOLDOWN, 0, 0, 0, 0.0,
                    false, true, false);
                case BOSS -> new Profile(false, 0, 0, Movement.APPROACH, 0, 0, 0, 0, 0, 0, 0, 0, false, false, false);
                case BLOOD_GIANT -> new Profile(true, 32, 0, Movement.APPROACH, 0, 0, 5.0, 30, 0, 0, 0, 0.0, false, true, false);
            };
        }

        private static Profile melee(double vision, double speed) {
            return new Profile(true, vision, LEASH, Movement.APPROACH, 0, 0, 2.5, MELEE_COOLDOWN, 0, 0, 0, speed, false, false, false);
        }

        /** AttackModule::HybridMeleeRanged (melee inside meleeRange, ranged beyond). */
        boolean hybridAttack() {
            return meleeRange > 0 && rangedRange > 0;
        }
    }

    /** Mob arrows: 1.6 blocks a tick (32 b/s). */
    static final double ARROW_BPS = 32.0;
    /** Wither skulls fly about 0.45-0.6 b/t: 0.5 (10 b/s). */
    static final double SKULL_BPS = 10.0;
    /**
     * Chase speeds measured on Hypixel (mobs-spawns-pathing.md §4: the 90th percentile of 10-tick
     * horizontal speed, median across mobs, F7), in blocks a second. Mobs without a measurement
     * keep the AI's own speed.
     */
    private static final java.util.Map<MobSystem.Archetype, Double> MEASURED_BPS = java.util.Map.ofEntries(
        java.util.Map.entry(MobSystem.Archetype.ZOMBIE_KNIGHT, 4.9),
        java.util.Map.entry(MobSystem.Archetype.ZOMBIE_SOLDIER, 4.9),
        java.util.Map.entry(MobSystem.Archetype.WITHERMANCER, 4.6),
        java.util.Map.entry(MobSystem.Archetype.CRYPT_DREADLORD, 3.7),
        java.util.Map.entry(MobSystem.Archetype.CRYPT_LURKER, 3.5),
        java.util.Map.entry(MobSystem.Archetype.SUPER_TANK_ZOMBIE, 3.2),
        java.util.Map.entry(MobSystem.Archetype.SKELETON_SOLDIER, 2.7),
        java.util.Map.entry(MobSystem.Archetype.SKELETON_MASTER, 2.7),
        java.util.Map.entry(MobSystem.Archetype.SKELETON_LORD, 2.7),
        java.util.Map.entry(MobSystem.Archetype.SUPER_ARCHER, 2.7),
        java.util.Map.entry(MobSystem.Archetype.SNIPER, 2.7),
        java.util.Map.entry(MobSystem.Archetype.CRYPT_SOULEATER, 2.5),
        java.util.Map.entry(MobSystem.Archetype.FELS, 7.0),
        java.util.Map.entry(MobSystem.Archetype.LOST_ADVENTURER, 6.8),
        java.util.Map.entry(MobSystem.Archetype.ANGRY_ARCHAEOLOGIST, 6.1));

    static final double LEASH = 20.0;
    static final int MELEE_COOLDOWN = 20;
    static final double AGGRO_RADIUS = 8.0;
    // Weapons the mobs use like players do: the Zombie Commander's whip lashes from where it holds,
    // the Crypt Dreadlord shoots its sword's skull, and a mob with a bone throws it (Bonemerangs).
    private static final double WHIP_RANGE = 7.0;
    private static final int WHIP_COOLDOWN = 30;
    private static final double SKULL_RANGE = 16.0;
    /** Crypt Dreadlord skulls come 7 ticks apart (229 of 263 gaps). */
    private static final int SKULL_COOLDOWN = 7;
    /**
     * Withermancers fire 2-4 skulls in the same tick. How often isn't measured.
     * OPEN: 67 (the Withermancer's skull volley cadence)
     */
    private static final int VOLLEY_SKULL_COOLDOWN = 40;
    private static final int BONE_COOLDOWN = 40;
    /**
     * P1's adds: a skull every 60-100 ticks each at a player within 24 (73 of 89 skulls in 672ef8's
     * P1 were theirs; their own rate isn't measured). OPEN: add skull rate
     */
    private static final int ADD_SKULL_COOLDOWN = 60;
    private static final double ADD_SKULL_RANGE = 24.0;

    // ai/mod.rs
    private static final double TPS = 20.0;
    private static final double BASE_WALK_SPEED_BPS = 3.75;
    private static final double FIRST_SIGHT_SPEED_BPS = 5.25;
    private static final double IDLE_WALK_SPEED_BPS = 2.25;
    private static final double IDLE_WANDER_ARRIVAL_DISTANCE = 0.3;
    private static final double PLAYER_SPRINT_SPEED_BPS = 5.612;
    private static final double HUMANOID_SPRINT_MULTIPLIER = 1.5;
    private static final int LOS_CHECK_INTERVAL_TICKS = 10;
    private static final int LOS_FORGET_TICKS = 100;
    private static final double LASSO_RETURN_SPEED_MULTIPLIER = 2.0;
    private static final double ORBIT_RADIUS = 1.8;
    private static final int WEAVE_CYCLE_TICKS = 10;
    private static final double VOLLEY_CHANCE = 0.1;
    private static final double ORBIT_SPEED_MULTIPLIER = 0.35;
    private static final int VOLLEY_ARROW_COUNT = 5;
    private static final int VOLLEY_SHOT_INTERVAL_TICKS = 10;
    private static final double VOLLEY_ARROW_SPEED_BPS = 24.0;
    private static final int HOP_INTERVAL_TICKS = 12;
    private static final int NO_INTERACTION_RETURN_TICKS = 100;
    // profile.rs / leash.rs
    private static final double IDLE_ACTIVATION_RANGE = 47.0;
    private static final double RETURN_ARRIVAL_DISTANCE = 2.0;
    private static final int AMBUSH_STUN_TICKS = 40;
    private static final int AMBUSH_REENGAGE_INTERVAL = 120;
    private static final double AMBUSH_REENGAGE_DISTANCE = 20.0;
    // movement.rs
    private static final int STRAFE_FLIP_INTERVAL_TICKS = 40;
    private static final double RANGED_STRAFE_SPEED_BPS = 1.5;
    private static final double RANGED_STRAFE_PERIOD_TICKS = 50.0;
    private static final double[] DEFLECTION_ANGLES_DEG = {30, -30, 60, -60, 90, -90};
    // attack.rs
    private static final double SHOOTER_HAND_HEIGHT = 1.4;
    private static final double TARGET_AIM_HEIGHT = 1.5;
    private static final int RANGED_COOLDOWN_JITTER_TICKS = 4;
    private static final int BOW_DRAW_TICKS = 12;
    // physics.rs
    private static final double GRAVITY = 0.08;
    private static final double TERMINAL = -3.92;
    private static final double LIQUID_GRAVITY = 0.02;
    private static final double LIQUID_BUOYANCY = 0.03;
    private static final double LIQUID_VERTICAL_CAP = 0.2;
    private static final double STEP_HEIGHT = 0.6;
    private static final double STAIR_STEP_HEIGHT = 1.05;
    private static final double JUMP = 0.42;
    private static final double MAX_SAFE_FALL = 3.0;

    final Profile profile;
    /** What this mob is, for the damage its hits deal. */
    private final MobSystem.Archetype archetype;
    /** The run ticking this mob (set each tick). */
    private DungeonRun run;
    final Vec3 origin;
    final int roomIndex;
    private final boolean humanoid;
    private final boolean zombieModel;
    private final boolean skullShooter;
    private final MobPath path = new MobPath();
    ServerPlayer target;
    /** Hit by a player at least once (frees a room-confined mob). */
    boolean damaged;
    private Activation activation = Activation.DORMANT;
    private boolean leashedOut;
    private boolean firstSightPending;
    private int losCheckCooldown;
    private int ticksSinceLos;
    private int meleeCooldown;
    private int rangedCooldown;
    private int bowDrawTicks;
    private ItemStack heldBeforeBow;
    private boolean armsRaised;
    private boolean sprinting;
    private int noInteractionTicks;
    private Vec3 wanderTarget;
    private int wanderCooldown;
    private Float spawnYaw;
    // TeleportAmbush
    private int stunTicks;
    /** Ticks left of a stun from outside (stun). */
    private int stunnedTicks;
    private int teleportCooldown;
    private boolean orbitClockwise;
    private int volleyArrowsLeft;
    private int volleyShotCooldown;
    private int weaveCooldown;
    // physics
    private double vy;
    private boolean onGround;
    /** Dropped in from the air (a Watcher camp mob): only falls until it first touches the ground. */
    boolean holdUntilLanded;

    /** The mob's modifier's speed (Speedy: about x1.5 for zombies, x2 for skeletons). */
    private final double speedFactor;

    MobAi(MobSystem.Archetype archetype, Vec3 origin, int roomIndex, boolean humanoid, double speedFactor) {
        this.archetype = archetype;
        this.speedFactor = speedFactor;
        this.profile = Profile.of(archetype);
        this.origin = origin;
        this.roomIndex = roomIndex;
        this.humanoid = humanoid;
        this.zombieModel = archetype != null && archetype.kind == MobSystem.Kind.ZOMBIE;
        this.skullShooter = archetype == MobSystem.Archetype.CRYPT_SOULEATER;
    }

    /** physics::is_in_lava: the block at the mob's feet is lava (MobSystem kills it, as run_mob_ai does). */
    static boolean inLava(LivingEntity mob) {
        return mob.level().getBlockState(mob.blockPosition()).is(Blocks.LAVA);
    }

    /** One tick of run_mob_ai. Returns a player it newly noticed (for aggro propagation), or null. */
    ServerPlayer tick(LivingEntity mob, DungeonRun run, Random random) {
        this.run = run;
        ServerLevel level = (ServerLevel) mob.level();
        if (spawnYaw == null) {
            spawnYaw = mob.getYRot();
        }
        meleeCooldown = Math.max(0, meleeCooldown - 1);
        // Physics applies to every dungeon mob, implemented or not.
        applyGravity(mob, level);
        if (!profile.implemented) {
            return null;
        }
        if (holdUntilLanded) {
            if (!onGround) {
                return null;
            }
            holdUntilLanded = false;
        }
        if (stunnedTicks > 0) {
            // Stunned (a Tank ghost's Stun Potion): stands still and does nothing.
            stunnedTicks--;
            return null;
        }
        Vec3 mobPos = mob.position();
        ServerPlayer noticed = null;

        if (target != null && !valid(target, level)) {
            // Target disconnected mid-fight: just drop it.
            target = null;
            activation = Activation.IDLE;
        }

        // Released room-confined miniboss: home after 100 ticks without sight of any player.
        if (profile.roomConfined && damaged) {
            if (visionTarget(mob, level) != null) {
                noInteractionTicks = 0;
            } else {
                noInteractionTicks++;
            }
            if (noInteractionTicks >= NO_INTERACTION_RETURN_TICKS) {
                mob.teleportTo(origin.x, origin.y, origin.z);
                vy = 0;
                noInteractionTicks = 0;
                target = null;
                leashedOut = false;
                activation = Activation.IDLE;
                return null;
            }
        }

        // Activation, then perception (skipped while committed to a lasso return).
        if (target == null && !leashedOut) {
            double nearest = Double.POSITIVE_INFINITY;
            for (ServerPlayer player : level.players()) {
                if (!Ghosts.absent(player)) {
                    nearest = Math.min(nearest, player.position().distanceTo(mobPos));
                }
            }
            if (!roomEntered(run) || nearest > IDLE_ACTIVATION_RANGE) {
                activation = Activation.DORMANT;
                wanderTarget = null;
            } else {
                if (activation == Activation.DORMANT) {
                    activation = Activation.IDLE;
                }
                ServerPlayer found = visionTarget(mob, level);
                if (found != null) {
                    target = found;
                    activation = Activation.COMBAT;
                    firstSightPending = true;
                    leashedOut = false;
                    cancelDraw(mob);
                    ticksSinceLos = 0;
                    losCheckCooldown = LOS_CHECK_INTERVAL_TICKS;
                    if (profile.ambush) {
                        stunTicks = AMBUSH_STUN_TICKS;
                        teleportCooldown = 0;
                    }
                    noticed = found;
                }
            }
        }

        // Lost sight long enough: give up and lasso home.
        if (target != null) {
            losCheckCooldown = Math.max(0, losCheckCooldown - 1);
            if (losCheckCooldown == 0) {
                losCheckCooldown = LOS_CHECK_INTERVAL_TICKS;
                ticksSinceLos = mob.hasLineOfSight(target) ? 0 : ticksSinceLos + LOS_CHECK_INTERVAL_TICKS;
            }
            if (ticksSinceLos >= LOS_FORGET_TICKS) {
                disengage();
            }
        }
        tickLeash(mobPos);

        // Persistent visuals: a zombie's raised arms and an NPC's sprint while in combat.
        boolean active = activation == Activation.COMBAT;
        if (zombieModel && active != armsRaised && mob instanceof Mob m) {
            armsRaised = active;
            m.setAggressive(active);
        }
        boolean shouldSprint = active && humanoid;
        if (shouldSprint != sprinting) {
            sprinting = shouldSprint;
            mob.setSprinting(shouldSprint);
        }

        if (leashedOut) {
            steerToward(mob, level, origin, BASE_WALK_SPEED_BPS * LASSO_RETURN_SPEED_MULTIPLIER * profile.speed, false);
        } else if (target != null) {
            combat(mob, level, run, random);
        } else if (activation == Activation.IDLE) {
            idle(mob, level, random);
        }
        return noticed;
    }

    private static boolean valid(ServerPlayer player, ServerLevel level) {
        return !player.isRemoved() && player.level() == level && !Ghosts.absent(player);
    }

    /** Stands the mob still for [ticks]. */
    void stun(int ticks) {
        stunnedTicks = Math.max(stunnedTicks, ticks);
    }

    private boolean roomEntered(DungeonRun run) {
        return roomIndex >= 0 && roomIndex < run.rooms.size() && run.rooms.get(roomIndex).entered;
    }

    /** perception::find_vision_target: the nearest player within vision that it can see. */
    private ServerPlayer visionTarget(LivingEntity mob, ServerLevel level) {
        ServerPlayer best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (Ghosts.absent(player)) {
                continue;
            }
            double d = mob.distanceTo(player);
            if (d <= profile.vision && d < bestDistance && mob.hasLineOfSight(player)) {
                best = player;
                bestDistance = d;
            }
        }
        return best;
    }

    /** aggro::acquire_or_propagate_target: a neighbour calling it in (only if it has no target). */
    void aggro(ServerPlayer player) {
        if (profile.implemented && target == null) {
            target = player;
            activation = Activation.COMBAT;
            firstSightPending = true;
        }
    }

    /** aggro::on_mob_attacked + combat::on_player_damaged_mob: the attacker becomes its target. */
    void attacked(ServerPlayer player) {
        damaged = true;
        if (profile.implemented) {
            target = player;
            activation = Activation.COMBAT;
            firstSightPending = true;
        }
    }

    /** leash::disengage. */
    private void disengage() {
        target = null;
        leashedOut = true;
        activation = Activation.IDLE;
    }

    /** leash::tick_leash. */
    private void tickLeash(Vec3 pos) {
        if (leashedOut) {
            if (pos.distanceTo(origin) <= RETURN_ARRIVAL_DISTANCE) {
                leashedOut = false;
                activation = Activation.IDLE;
            }
            return;
        }
        if (profile.leash > 0 && pos.distanceTo(origin) > profile.leash) {
            disengage();
        }
    }

    // ---------------------------------------------------------------- combat

    private void combat(LivingEntity mob, ServerLevel level, DungeonRun run, Random random) {
        Vec3 targetPos = target.position();
        Double measured = archetype == null ? null : MEASURED_BPS.get(archetype);
        double combatSpeed = (measured != null ? measured : humanoid
            ? PLAYER_SPRINT_SPEED_BPS * HUMANOID_SPRINT_MULTIPLIER * profile.speed
            : BASE_WALK_SPEED_BPS * profile.speed) * speedFactor;

        // Room-confined and not yet hit: won't engage anyone outside its room, just watches.
        if (profile.roomConfined && !damaged && !targetInRoom(run, targetPos)) {
            face(mob, targetPos);
            return;
        }

        if (profile.ambush) {
            ambushCombat(mob, level, targetPos, combatSpeed, random);
            return;
        }

        if (bowDrawTicks > 0) {
            // Plants and aims while drawing.
            face(mob, targetPos);
        } else if (firstSightPending) {
            steerToward(mob, level, targetPos, FIRST_SIGHT_SPEED_BPS * profile.speed, false);
            firstSightPending = false;
        } else {
            MobPath.Steer steer = path.next(level, run, mob.position(), targetPos);
            applyMovementStyle(mob, level, targetPos, steer.point(), combatSpeed, steer.onPath());
        }

        if (profile.hops && bowDrawTicks == 0) {
            double distance = mob.position().distanceTo(targetPos);
            boolean stillClosing = switch (profile.movement) {
                case HYBRID -> distance > profile.preferred + profile.tolerance;
                case CIRCLE_STRAFE, LIGHT_STRAFE -> distance > profile.meleeRange;
                case APPROACH -> true;
                default -> false;
            };
            if (stillClosing && mob.tickCount % HOP_INTERVAL_TICKS == 0 && onGround) {
                vy = JUMP;
            }
        }

        double distance = mob.position().distanceTo(targetPos);
        boolean inStrikeRange = profile.meleeRange > 0 && distance <= profile.meleeRange;
        if (inStrikeRange) {
            face(mob, targetPos);
        }

        rangedCooldown = Math.max(0, rangedCooldown - 1);
        attack(mob, level, targetPos, random);
    }

    /** room_confined: the target's x/z is inside the mob's home room (true if the room is unknown). */
    private boolean targetInRoom(DungeonRun run, Vec3 pos) {
        MobPath.Bounds b = MobPath.bounds(run, roomIndex);
        if (b == null) {
            return true;
        }
        return pos.x >= b.minX() && pos.x <= b.maxX() + 1 && pos.z >= b.minZ() && pos.z <= b.maxZ() + 1;
    }

    /** MovementStyle dispatch (apply_movement_style); distances against the real target, walking to [steer]. */
    private void applyMovementStyle(LivingEntity mob, ServerLevel level, Vec3 targetPos, Vec3 steer, double speed, boolean allowJump) {
        double distance = mob.position().distanceTo(targetPos);
        switch (profile.movement) {
            case APPROACH -> steerToward(mob, level, steer, speed, allowJump);
            case MAINTAIN_DISTANCE -> {
                // Approaches when too far; never backs off for being too close.
                if (distance > profile.preferred + profile.tolerance) {
                    steerToward(mob, level, steer, speed, allowJump);
                } else {
                    face(mob, targetPos);
                }
            }
            case HYBRID -> {
                if (distance < profile.meleeRange || distance > profile.preferred + profile.tolerance) {
                    steerToward(mob, level, steer, speed, allowJump);
                } else {
                    strafeLightly(mob, level, targetPos);
                    face(mob, targetPos);
                }
            }
            case CIRCLE_STRAFE -> {
                if (distance > profile.meleeRange) {
                    steerToward(mob, level, steer, speed, allowJump);
                } else {
                    strafeAroundTarget(mob, level, targetPos, profile.preferred, speed);
                }
            }
            case LIGHT_STRAFE -> {
                if (distance > profile.meleeRange) {
                    steerToward(mob, level, steer, speed, allowJump);
                } else {
                    strafeLightlyTowardRadius(mob, level, targetPos, profile.preferred);
                }
            }
        }
    }

    /** attack::try_attack. */
    private void attack(LivingEntity mob, ServerLevel level, Vec3 targetPos, Random random) {
        double distance = mob.position().distanceTo(targetPos);
        if (distance > profile.meleeRange) {
            weaponRanged(mob, level, distance, targetPos);
        }
        if (profile.hybridAttack()) {
            if (distance < profile.meleeRange) {
                cancelDraw(mob);
                tryMelee(mob, distance, profile.meleeRange, MELEE_COOLDOWN);
            } else {
                tryRanged(mob, level, distance, targetPos, profile.rangedRange, profile.rangedCooldown, profile.arrowSpeedBps,
                    !skullShooter, random);
            }
        } else if (profile.rangedRange > 0) {
            tryRanged(mob, level, distance, targetPos, profile.rangedRange, profile.rangedCooldown, profile.arrowSpeedBps, true, random);
        } else if (profile.meleeRange > 0) {
            bowDrawTicks = 0;
            tryMelee(mob, distance, profile.meleeRange, profile.meleeCooldown);
        }
    }

    /** Out of melee reach: the Dreadlord Sword's skull, or the bone thrown as a Bonemerang. */
    private void weaponRanged(LivingEntity mob, ServerLevel level, double distance, Vec3 targetPos) {
        boolean add = archetype == MobSystem.Archetype.WITHER_MINER || archetype == MobSystem.Archetype.WITHER_GUARD;
        boolean skull = (archetype == MobSystem.Archetype.CRYPT_DREADLORD || archetype == MobSystem.Archetype.WITHERMANCER || add)
            && distance <= (add ? ADD_SKULL_RANGE : SKULL_RANGE);
        boolean bone = !skull && distance <= Bonemerangs.RANGE && Bonemerangs.mobCanThrow(mob.getMainHandItem());
        if (!skull && !bone || rangedCooldown > 0 || run == null || target == null || !mob.hasLineOfSight(target)) {
            return;
        }
        if (skull) {
            boolean volley = archetype == MobSystem.Archetype.WITHERMANCER;
            rangedCooldown = volley ? VOLLEY_SKULL_COOLDOWN : add ? ADD_SKULL_COOLDOWN + run.randomInt(40) : SKULL_COOLDOWN;
            face(mob, targetPos);
            mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
            Vec3 from = mob.position().add(0, SHOOTER_HAND_HEIGHT, 0);
            // A Withermancer's 2-4 skulls leave together; one skull's hit lands (the player's
            // invulnerability window covers the rest).
            int skulls = volley ? 2 + run.randomInt(3) : 1;
            for (int i = 0; i < skulls; i++) {
                line(level, from, target.getBoundingBox().getCenter().add((i - skulls / 2.0) * 0.3, 0, 0), ParticleTypes.SMOKE, 0.6);
            }
            level.playSound(null, from.x, from.y, from.z, SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1f, 1f);
            if (add) {
                run.combat().cryptSkull(target);
            } else {
                run.combat().attackedBy(target, mob);
                run.combat().mobHit(target, archetype, run.mobs().killerName(mob));
            }
        } else {
            rangedCooldown = BONE_COOLDOWN;
            face(mob, targetPos);
            mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
            run.gear().bones().throwFrom(mob, archetype, targetPos);
        }
    }

    private static void line(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions particle, double step) {
        Vec3 d = to.subtract(from);
        int n = Math.max(1, (int) (d.length() / step));
        for (int i = 0; i <= n; i++) {
            Vec3 at = from.add(d.scale((double) i / n));
            level.sendParticles(particle, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
    }

    private void tryMelee(LivingEntity mob, double distance, double range, int cooldown) {
        if (distance > range || meleeCooldown > 0) {
            return;
        }
        meleeCooldown = cooldown;
        mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
        if (archetype == MobSystem.Archetype.ZOMBIE_COMMANDER && target != null && mob.level() instanceof ServerLevel level) {
            // The whip's lash, out to where it holds.
            Vec3 from = mob.position().add(0, SHOOTER_HAND_HEIGHT, 0);
            line(level, from, target.getBoundingBox().getCenter(), ParticleTypes.CRIT, 0.4);
            level.playSound(null, from.x, from.y, from.z, SoundEvents.FISHING_BOBBER_THROW, SoundSource.HOSTILE, 1f, 1.6f);
        }
        // The swing lands on its target: the floor's damage, less their Defense (Combat).
        if (run != null && target != null && target.level() == mob.level()) {
            run.combat().attackedBy(target, mob);
            run.combat().mobHit(target, archetype, run.mobs().killerName(mob));
        }
    }

    /** try_ranged: a 12-tick draw (planted, bow raised), then the shot; out of range lowers the bow. */
    private void tryRanged(LivingEntity mob, ServerLevel level, double distance, Vec3 targetPos, double range, int cooldown,
                           double speedBps, boolean usesBow, Random random) {
        if (distance > range) {
            if (usesBow && bowDrawTicks > 0) {
                lowerBow(mob);
            }
            bowDrawTicks = 0;
            return;
        }
        if (bowDrawTicks > 0) {
            bowDrawTicks--;
            if (bowDrawTicks == 0) {
                if (usesBow) {
                    lowerBow(mob);
                }
                fireArrow(mob, level, targetPos, speedBps);
                int jitter = random.nextInt(2 * RANGED_COOLDOWN_JITTER_TICKS + 1) - RANGED_COOLDOWN_JITTER_TICKS;
                rangedCooldown = Math.max(1, cooldown + jitter);
            }
            return;
        }
        if (rangedCooldown > 0) {
            return;
        }
        bowDrawTicks = BOW_DRAW_TICKS;
        if (usesBow) {
            raiseBow(mob);
        }
    }

    private void cancelDraw(LivingEntity mob) {
        if (bowDrawTicks > 0 || heldBeforeBow != null) {
            lowerBow(mob);
        }
        bowDrawTicks = 0;
    }

    /** raise_bow + set_drawing_bow: an NPC swaps to a bow; everyone takes the using-item pose. */
    private void raiseBow(LivingEntity mob) {
        if (humanoid && heldBeforeBow == null) {
            heldBeforeBow = mob.getMainHandItem();
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        }
        mob.startUsingItem(InteractionHand.MAIN_HAND);
    }

    private void lowerBow(LivingEntity mob) {
        mob.stopUsingItem();
        if (heldBeforeBow != null) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, heldBeforeBow);
            heldBeforeBow = null;
        }
    }

    /**
     * fire_arrow_at_player: a real arrow from hand height straight at the target's upper body
     * (it does no damage here). Crypt Souleater's wither skull is an arrow too.
     */
    private static void fireArrow(LivingEntity mob, ServerLevel level, Vec3 targetPos, double speedBps) {
        Vec3 from = mob.position().add(0, SHOOTER_HAND_HEIGHT, 0);
        Vec3 aim = targetPos.add(0, TARGET_AIM_HEIGHT, 0).subtract(from);
        Arrow arrow = new Arrow(level, mob, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        arrow.setPos(from.x, from.y, from.z);
        arrow.shoot(aim.x, aim.y, aim.z, (float) (speedBps / TPS), 0f);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        level.addFreshEntity(arrow);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1f, 1f);
    }

    /** The teleport_ambush branch of run_mob_ai (Shadow Assassin). */
    private void ambushCombat(LivingEntity mob, ServerLevel level, Vec3 targetPos, double combatSpeed, Random random) {
        firstSightPending = false;
        if (stunTicks > 0) {
            stunTicks--;
            return;
        }
        double distance = mob.position().distanceTo(targetPos);
        boolean firstEngage = mob.isInvisible();
        if (firstEngage || teleportCooldown == 0 || distance >= AMBUSH_REENGAGE_DISTANCE) {
            // teleport_behind: ORBIT_RADIUS behind the way the target is facing.
            double yaw = Math.toRadians(target.getYRot());
            double forwardX = -Math.sin(yaw);
            double forwardZ = Math.cos(yaw);
            mob.teleportTo(targetPos.x - forwardX * ORBIT_RADIUS, targetPos.y, targetPos.z - forwardZ * ORBIT_RADIUS);
            if (firstEngage) {
                mob.setInvisible(false);
                orbitClockwise = random.nextBoolean();
            }
            teleportCooldown = AMBUSH_REENGAGE_INTERVAL;
            if (volleyArrowsLeft > 0) {
                lowerBow(mob);
            }
            volleyArrowsLeft = 0;
            weaveCooldown = WEAVE_CYCLE_TICKS;
        } else {
            teleportCooldown = Math.max(0, teleportCooldown - 1);
        }

        if (volleyArrowsLeft > 0) {
            // Mid-volley: stands still and fires on a fixed cadence.
            face(mob, targetPos);
            volleyShotCooldown = Math.max(0, volleyShotCooldown - 1);
            if (volleyShotCooldown == 0) {
                fireArrow(mob, level, targetPos, VOLLEY_ARROW_SPEED_BPS);
                volleyArrowsLeft--;
                if (volleyArrowsLeft > 0) {
                    volleyShotCooldown = VOLLEY_SHOT_INTERVAL_TICKS;
                } else {
                    lowerBow(mob);
                    weaveCooldown = WEAVE_CYCLE_TICKS;
                }
            }
        } else {
            double current = mob.position().distanceTo(targetPos);
            double radialBias = clamp((ORBIT_RADIUS - current) / ORBIT_RADIUS, -1, 1);
            orbitAndWeave(mob, level, targetPos, orbitClockwise, radialBias, combatSpeed * ORBIT_SPEED_MULTIPLIER);
            face(mob, targetPos);
            rangedCooldown = Math.max(0, rangedCooldown - 1);
            tryMelee(mob, mob.position().distanceTo(targetPos), profile.meleeRange, profile.meleeCooldown);

            weaveCooldown = Math.max(0, weaveCooldown - 1);
            if (weaveCooldown == 0) {
                weaveCooldown = WEAVE_CYCLE_TICKS;
                if (random.nextDouble() < VOLLEY_CHANCE) {
                    raiseBow(mob);
                    volleyArrowsLeft = VOLLEY_ARROW_COUNT;
                    volleyShotCooldown = 0;
                }
            }
        }
    }

    // ---------------------------------------------------------------- idle

    /** Idle wandering within 3 blocks of spawn, every 60-120 ticks; otherwise faces its spawn direction. */
    private void idle(LivingEntity mob, ServerLevel level, Random random) {
        wanderCooldown = Math.max(0, wanderCooldown - 1);
        if (wanderTarget == null && wanderCooldown == 0) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = random.nextDouble() * 3.0;
            wanderTarget = new Vec3(origin.x + radius * Math.cos(angle), origin.y, origin.z + radius * Math.sin(angle));
            wanderCooldown = 60 + random.nextInt(61);
        }
        if (wanderTarget != null) {
            if (mob.position().distanceTo(wanderTarget) < IDLE_WANDER_ARRIVAL_DISTANCE) {
                wanderTarget = null;
            } else {
                steerToward(mob, level, wanderTarget, IDLE_WALK_SPEED_BPS, false);
            }
        } else {
            setRotation(mob, spawnYaw, 0f);
        }
    }

    // ---------------------------------------------------------------- movement.rs

    /** steer_toward: one tick straight at [to] (x/z), facing it; deflected angles if fully blocked. */
    private void steerToward(LivingEntity mob, ServerLevel level, Vec3 to, double speedBps, boolean allowJump) {
        double dx = to.x - mob.getX();
        double dz = to.z - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        setRotation(mob, yawTowards(dx, dz), pitchTowards(to.y - mob.getY(), horizontal));
        if (horizontal < 1e-4) {
            return;
        }
        double step = Math.min(speedBps / TPS, horizontal);
        double moveX = dx / horizontal * step;
        double moveZ = dz / horizontal * step;
        if (moveHorizontal(mob, level, moveX, moveZ, allowJump)) {
            return;
        }
        for (double angle : DEFLECTION_ANGLES_DEG) {
            double r = Math.toRadians(angle);
            double c = Math.cos(r);
            double s = Math.sin(r);
            if (moveHorizontal(mob, level, moveX * c - moveZ * s, moveX * s + moveZ * c, allowJump)) {
                break;
            }
        }
    }

    /** strafe_around_target: orbit, flipping direction every 40 ticks, pulled toward [orbitRadius]. */
    private void strafeAroundTarget(LivingEntity mob, ServerLevel level, Vec3 targetPos, double orbitRadius, double speedBps) {
        double dx = mob.getX() - targetPos.x;
        double dz = mob.getZ() - targetPos.z;
        double radius = Math.max(Math.sqrt(dx * dx + dz * dz), 0.5);
        double direction = (mob.tickCount / STRAFE_FLIP_INTERVAL_TICKS) % 2 == 0 ? 1.0 : -1.0;
        double radialBias = clamp((orbitRadius - radius) / Math.max(orbitRadius, 0.5), -1, 1);
        double cx = -dz / radius * direction + dx / radius * radialBias;
        double cz = dx / radius * direction + dz / radius * radialBias;
        double len = Math.max(Math.sqrt(cx * cx + cz * cz), 1e-6);
        double step = speedBps / TPS;
        moveHorizontal(mob, level, cx / len * step, cz / len * step, false);
    }

    /** orbit_and_weave: orbit one fixed way with a radial in/out component. */
    private void orbitAndWeave(LivingEntity mob, ServerLevel level, Vec3 targetPos, boolean clockwise, double radialBias, double speedBps) {
        double dx = mob.getX() - targetPos.x;
        double dz = mob.getZ() - targetPos.z;
        double radius = Math.max(Math.sqrt(dx * dx + dz * dz), 0.5);
        double direction = clockwise ? 1.0 : -1.0;
        double cx = -dz / radius * direction + dx / radius * radialBias;
        double cz = dx / radius * direction + dz / radius * radialBias;
        double len = Math.sqrt(cx * cx + cz * cz);
        if (len < 1e-6) {
            return;
        }
        double step = speedBps / TPS;
        moveHorizontal(mob, level, cx / len * step, cz / len * step, false);
    }

    /** strafe_lightly: a slow sine side-step while holding at range. */
    private void strafeLightly(LivingEntity mob, ServerLevel level, Vec3 targetPos) {
        double dx = mob.getX() - targetPos.x;
        double dz = mob.getZ() - targetPos.z;
        double radius = Math.max(Math.sqrt(dx * dx + dz * dz), 0.5);
        double direction = Math.sin(mob.tickCount / RANGED_STRAFE_PERIOD_TICKS * Math.PI * 2);
        double step = RANGED_STRAFE_SPEED_BPS / TPS * direction;
        moveHorizontal(mob, level, -dz / radius * step, dx / radius * step, false);
    }

    /** strafe_lightly_toward_radius: the same wiggle, pulled toward [orbitRadius]. */
    private void strafeLightlyTowardRadius(LivingEntity mob, ServerLevel level, Vec3 targetPos, double orbitRadius) {
        double dx = mob.getX() - targetPos.x;
        double dz = mob.getZ() - targetPos.z;
        double radius = Math.max(Math.sqrt(dx * dx + dz * dz), 0.5);
        double tangential = Math.sin(mob.tickCount / RANGED_STRAFE_PERIOD_TICKS * Math.PI * 2);
        double radialBias = clamp((orbitRadius - radius) / Math.max(orbitRadius, 0.5), -1, 1);
        double cx = -dz / radius * tangential + dx / radius * radialBias;
        double cz = dx / radius * tangential + dz / radius * radialBias;
        double len = Math.sqrt(cx * cx + cz * cz);
        if (len < 1e-6) {
            return;
        }
        double step = RANGED_STRAFE_SPEED_BPS / TPS;
        moveHorizontal(mob, level, cx / len * step, cz / len * step, false);
    }

    /** face_toward. */
    private static void face(LivingEntity mob, Vec3 at) {
        double dx = at.x - mob.getX();
        double dz = at.z - mob.getZ();
        setRotation(mob, yawTowards(dx, dz), pitchTowards(at.y - mob.getY(), Math.sqrt(dx * dx + dz * dz)));
    }

    private static void setRotation(LivingEntity mob, float yaw, float pitch) {
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
        mob.setXRot(pitch);
    }

    private static float yawTowards(double dx, double dz) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return yaw < 0 ? yaw + 360f : yaw;
    }

    private static float pitchTowards(double dy, double horizontal) {
        return (float) -Math.toDegrees(Math.atan2(dy, horizontal));
    }

    // ---------------------------------------------------------------- physics.rs

    /** apply_gravity: fall when not grounded, buoyancy in liquid. */
    private void applyGravity(LivingEntity mob, ServerLevel level) {
        boolean submerged = mob.isInWater() || mob.isInLava();
        Vec3 pos = mob.position();
        double half = mob.getBbWidth() / 2;
        boolean grounded = !submerged
            && !level.noCollision(mob, new AABB(pos.x - half, pos.y - 0.1, pos.z - half, pos.x + half, pos.y, pos.z + half));
        onGround = grounded;
        mob.setOnGround(grounded);
        if (submerged) {
            vy = clamp(vy - LIQUID_GRAVITY + LIQUID_BUOYANCY, -LIQUID_VERTICAL_CAP, LIQUID_VERTICAL_CAP);
        } else if (grounded && vy < 0) {
            vy = 0;
        } else if (!grounded) {
            vy = Math.max(TERMINAL, vy - GRAVITY);
        }
        if (vy == 0) {
            return;
        }
        // Vanilla collision resolves the vertical move (lands flush instead of stopping short).
        mob.move(MoverType.SELF, new Vec3(0, vy, 0));
        if (mob.verticalCollision) {
            vy = 0;
        }
    }

    /** move_horizontal: each axis on its own. True if either moved. */
    private boolean moveHorizontal(LivingEntity mob, ServerLevel level, double dx, double dz, boolean allowJump) {
        boolean movedX = dx != 0 && tryMoveAxis(mob, level, dx, 0, allowJump);
        boolean movedZ = dz != 0 && tryMoveAxis(mob, level, 0, dz, allowJump);
        return movedX || movedZ;
    }

    /** try_move_axis: flat move, else auto-step (taller onto partial blocks), else a path-called jump. */
    private boolean tryMoveAxis(LivingEntity mob, ServerLevel level, double dx, double dz, boolean allowJump) {
        Vec3 pos = mob.position();
        Vec3 flat = new Vec3(pos.x + dx, pos.y, pos.z + dz);
        if (!blocked(mob, level, flat) && safeToStep(mob, level, flat)) {
            mob.setPos(flat.x, flat.y, flat.z);
            return true;
        }
        boolean partial = partialHeight(mob, level, flat);
        Vec3 stepped = new Vec3(flat.x, pos.y + (partial ? STAIR_STEP_HEIGHT : STEP_HEIGHT), flat.z);
        if (!blocked(mob, level, stepped) && safeToStep(mob, level, stepped)) {
            mob.setPos(stepped.x, stepped.y, stepped.z);
            return true;
        }
        if (allowJump && onGround && !partial) {
            vy = JUMP;
        }
        return false;
    }

    private static AABB box(LivingEntity mob, Vec3 at) {
        double half = mob.getBbWidth() / 2;
        return new AABB(at.x - half, at.y, at.z - half, at.x + half, at.y + mob.getBbHeight(), at.z + half);
    }

    /** is_blocked: blocks, or any player's hitbox. */
    private static boolean blocked(LivingEntity mob, ServerLevel level, Vec3 at) {
        AABB box = box(mob, at);
        if (!level.noCollision(mob, box)) {
            return true;
        }
        for (ServerPlayer player : level.players()) {
            if (!Ghosts.absent(player) && player.getBoundingBox().intersects(box)) {
                return true;
            }
        }
        return false;
    }

    /** safe_to_step: while grounded, only onto spots with ground within 3 blocks below. */
    private boolean safeToStep(LivingEntity mob, ServerLevel level, Vec3 at) {
        if (!onGround) {
            return true;
        }
        double half = mob.getBbWidth() / 2;
        return !level.noCollision(mob, new AABB(at.x - half, at.y - MAX_SAFE_FALL, at.z - half, at.x + half, at.y, at.z + half));
    }

    /** obstruction_is_partial_height: the block at [at]'s feet collides, but not in its top slice. */
    private static boolean partialHeight(LivingEntity mob, ServerLevel level, Vec3 at) {
        int x = (int) Math.floor(at.x);
        int y = (int) Math.floor(at.y);
        int z = (int) Math.floor(at.z);
        boolean any = !level.noCollision(mob, new AABB(x + 0.001, y + 0.001, z + 0.001, x + 0.999, y + 0.999, z + 0.999));
        boolean top = !level.noCollision(mob, new AABB(x + 0.001, y + 0.95, z + 0.001, x + 0.999, y + 0.999, z + 0.999));
        return any && !top;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
