package net.rustclear.mod.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Each class's own abilities in a run: Ctrl+Q (dropping the stack) is the class ability, Q (one
 * item) the ultimate, as on Hypixel (the drop key never drops anything in the Rustclear world:
 * EngineDropMixin). Plus the passives that work on hits rather than stats.
 *
 * Mage and Archer are engineerClient's P3 sim's, as it measured them on Hypixel
 * (github.com/SoftwareEngineerAndrew/engineerClient, p3sim/Mage.kt and SimItems.volley):
 * - Mage Beam: a left click with anything in hand but a bow or a pickaxe (engineerClient: swords
 *   only) fires a beam along your look (firework sparks, up to 40 blocks, stopped by blocks), at
 *   most once every 4 ticks, hitting the first mob for a Mage's melee hit (MobSystem: a share of
 *   the hit by Intelligence).
 * - Guided Sheep: Ctrl+Q, or three beam clicks 2-5 ticks apart. A sheep flies from your feet 1
 *   block a tick along your look (2 in its ticks 4-6), exploding on a block or mob or after 60
 *   ticks. Its cooldown is 28 s less Efficient Spells (139 ticks measured on a solo Mage 50).
 * - Explosive Shot: three arrows at your look and +-5.5 degrees that explode where they land.
 *   Its cooldown is 40 s, 2 s less every 5 Archer levels (20 s at 50; the wiki's Archer page).
 *
 * The rest come from the wiki's class pages (Oct 2026); numbers it doesn't give are marked
 * "assumed" where they're set.
 * - Rapid Fire (Archer ultimate): an arrow every 2 ticks for 3 s, +1 s every 10 levels.
 * - Thunderstorm (Mage ultimate): lightning on the mobs around you once a second for 5 s.
 * - Throwing Axe (Berserk): your highest hit of the last minute, +1% a level, at the first mob ahead.
 * - Ragnarok (Berserk ultimate): +100 Attack Speed, +400 Speed and 50% more damage for 15 s, and 3 Zombie minions.
 * - Bloodlust and Lust for Blood (Berserk passives): the hit after a kill, and each hit on the same mob.
 * - Seismic Wave (Tank): a ground wave hitting the mobs in front of you.
 * - Castle of Stone (Tank ultimate): 70% less damage taken for 20 s.
 * - Healing Circle (Healer): a circle at your feet healing everyone in it each second.
 * - Wish (Healer ultimate): the whole party to full health, with an absorption shield.
 */
final class ClassAbilities {
    /** Item types whose left click is their own (bows shoot or draw, pickaxes mine): no beam. */
    private static final Set<String> NO_BEAM_TYPES = Set.of("BOW", "SHORTBOW", "PICKAXE");
    private static final double BEAM_RANGE = 40;
    private static final int BEAM_GAP = 4;
    private static final int SHEEP_LIFE = 60;
    private static final double SHEEP_BLAST = 4;
    private static final double ARROW_RANGE = 60;
    private static final double EXPLOSION_RADIUS = 3;

    private final DungeonRun run;
    private final ServerLevel level;
    private long ticks;
    /** "player:key" to the tick it's ready again. */
    private final Map<String, Long> readyAt = new HashMap<>();
    private final Map<UUID, Deque<Long>> beamClicks = new HashMap<>();
    private final Map<UUID, Long> ragnarokUntil = new HashMap<>();
    /** Timed flat buffs per player: {strength, speed, until tick}. Only the latest of each kind holds. */
    private final Map<UUID, double[]> strengthBuff = new HashMap<>();
    private final Map<UUID, double[]> speedBuff = new HashMap<>();
    /** Support Orb buffs: the tick each type (SupportOrbs.Type order) runs out, per player. */
    private final Map<UUID, long[]> orbBuff = new HashMap<>();
    /** Berserk: whether the next hit has Bloodlust, the mob being hit in a row and how many times. */
    private final Map<UUID, Boolean> bloodlust = new HashMap<>();
    private final Map<UUID, Entity> lustTarget = new HashMap<>();
    private final Map<UUID, Integer> lustHits = new HashMap<>();
    /** Each player's hits of the last minute: {tick, damage}. */
    private final Map<UUID, Deque<long[]>> recentHits = new HashMap<>();

    private record FlyingSheep(ServerPlayer owner, Sheep sheep, long born) {}

    private final List<FlyingSheep> sheep = new ArrayList<>();

    private static final int RAGNAROK_TICKS = 300;
    private static final double MINION_SPEED = 0.3;
    private static final double MINION_SHARE = 0.25;

    /** A Ragnarok minion: who summoned it, until when, its fall speed and when it may hit next. */
    private static final class Minion {
        final ServerPlayer owner;
        final Zombie zombie;
        final long until;
        double vy;
        long nextHit;

        Minion(ServerPlayer owner, Zombie zombie, long until) {
            this.owner = owner;
            this.zombie = zombie;
            this.until = until;
        }
    }

    private final List<Minion> minions = new ArrayList<>();

    /** A timed effect run each tick until it says it's done. */
    private interface Effect {
        boolean tick(long age);
    }

    private record Running(Effect effect, long start) {}

    private final List<Running> effects = new ArrayList<>();

    ClassAbilities(DungeonRun run) {
        this.run = run;
        this.level = run.level;
    }

    // ---------------------------------------------------------------- what other systems ask

    /** [c]'s ability (or [ultimate]) cooldown in ticks at class [level] with [cdr] cooldown reduction (Efficient Spells). */
    static long cooldownTicks(DungeonClass c, boolean ultimate, int level, double cdr) {
        // Measured "Used X!" -> "X is now available!" (abilities-items.md §2): whole seconds of
        // base x (1 - Mage cooldown reduction), rounded down. Thunderstorm 255 / 130 s at 49% / 74%,
        // Guided Sheep 300 / 140 ticks; Ragnarok 60 s, Castle of Stone 150, Rapid Fire 100,
        // Throwing Axe 10, Seismic Wave 15, Explosive Shot 22 at Archer 47.
        double off = 1 - cdr;
        if (ultimate) {
            return 20L * (long) Math.floor(switch (c) {
                case MAGE -> 500;
                case ARCHER -> 100;
                case BERSERK -> 60;
                case TANK -> 150;
                // OPEN: never recorded (the recorder is never Healer); the wiki's 120 s.
                case HEALER -> 120;
            } * off + 1e-9);
        }
        return switch (c) {
            case MAGE -> 20L * (long) Math.floor(30 * off + 1e-9);
            // Explosive Shot: 40 s, 2 s less every 5 Archer levels, never under 20 s (22 s measured at 47).
            case ARCHER -> Math.max(20, 40 - 2 * (level / 5)) * 20L;
            case BERSERK -> 20L * (long) Math.floor(10 * off + 1e-9);
            case TANK -> 20L * (long) Math.floor(15 * off + 1e-9);
            // Healing Circle: one at a time, so as long as a circle lasts (5 s, +1 s every 5 levels).
            case HEALER -> (5 + level / 5) * 20L;
        };
    }

    /**
     * The ultimate's reminder: "<Ult> is ready to use! Press DROP to activate it!" every 600 ticks
     * on a clock that starts 420 after Mort's first line (419-421 in 204 runs, 439-441 in 79: OPEN,
     * the median taken), printed only while the ultimate is ready; a cast doesn't move the clock.
     */
    static final int REMINDER_FIRST = 420;
    static final int REMINDER_EVERY = 600;

    /** [runTicks] after Mort's first line: remind every player whose ultimate is ready. */
    void remind(long runTicks, Iterable<ServerPlayer> players) {
        if (runTicks < REMINDER_FIRST || (runTicks - REMINDER_FIRST) % REMINDER_EVERY != 0) {
            return;
        }
        for (ServerPlayer p : players) {
            DungeonClass c = PlayerStats.classOf(p);
            if (c == null || !isReady(p, "ultimate") || run.combat().isGhost(p) || p.isSpectator()) {
                continue;
            }
            p.sendSystemMessage(EngineItems.text("§6" + c.ultimate + "§a is ready to use! Press §6§lDROP§a to activate it!"));
        }
    }

    /** Ticks until [player]'s ultimate (or class ability) is ready; 0 when it is. */
    long ticksLeft(ServerPlayer player, boolean ultimate) {
        Long at = readyAt.get(player.getUUID() + ":" + (ultimate ? "ultimate" : "ability"));
        return at == null || ticks >= at ? 0 : at - ticks;
    }

    StatBuilder.Buffs buffs(ServerPlayer player) {
        double[] strength = strengthBuff.get(player.getUUID());
        double[] speed = speedBuff.get(player.getUUID());
        long[] orb = orbBuff.get(player.getUUID());
        return new StatBuilder.Buffs(ticks < ragnarokUntil.getOrDefault(player.getUUID(), -1L),
            strength != null && ticks < strength[1] ? strength[0] : 0,
            speed != null && ticks < speed[1] ? speed[0] : 0,
            orbActive(orb, SupportOrbs.Type.DAMAGE), orbActive(orb, SupportOrbs.Type.DEFENSE),
            orbActive(orb, SupportOrbs.Type.ABILITY_DAMAGE));
    }

    private double orbActive(long[] until, SupportOrbs.Type type) {
        return until != null && ticks < until[type.ordinal()] ? SupportOrbs.BUFF : 0;
    }

    /** A Support Orb's +20% [type] for [duration] ticks (a second orb of the type restarts the clock). */
    void buffOrb(ServerPlayer player, SupportOrbs.Type type, int duration) {
        orbBuff.computeIfAbsent(player.getUUID(), k -> new long[SupportOrbs.Type.values().length])[type.ordinal()] =
            ticks + duration;
    }

    /** +[amount] Strength for [duration] ticks. */
    void buffStrength(ServerPlayer player, double amount, int duration) {
        strengthBuff.put(player.getUUID(), new double[] {amount, ticks + duration});
    }

    /** +[amount] Speed for [duration] ticks. */
    void buffSpeed(ServerPlayer player, double amount, int duration) {
        speedBuff.put(player.getUUID(), new double[] {amount, ticks + duration});
        PlayerSpeed.apply(player);
        run.schedule(duration + 1, () -> PlayerSpeed.apply(player));
    }

    /** A Berserk's melee multiplier on [entity]: Bloodlust after a kill, Lust for Blood on the same mob. Heals 3% a hit. */
    double meleeBonus(ServerPlayer player, Entity entity, StatSheet s) {
        if (s.dungeonClass != DungeonClass.BERSERK) {
            return 1;
        }
        DungeonClass.Bonus b = s.classBonus;
        UUID id = player.getUUID();
        double multiplier = 1;
        if (Boolean.TRUE.equals(bloodlust.remove(id))) {
            multiplier *= 1 + b.bloodlust();
        }
        int hits = lustTarget.get(id) == entity ? lustHits.getOrDefault(id, 0) : 0;
        multiplier *= 1 + Math.min(b.lustCap(), hits * b.lustPerHit());
        lustTarget.put(id, entity);
        lustHits.put(id, hits + 1);
        // Bloodlust's heal: 3% of max Health a hit (Jun 10 2026).
        run.combat().heal(player, s.get(Stat.HEALTH) * 0.03);
        return multiplier;
    }

    /** [player] hit something for [damage]; [killed] it. */
    void recordHit(ServerPlayer player, long damage, boolean killed) {
        Deque<long[]> hits = recentHits.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        hits.addLast(new long[] {ticks, damage});
        while (!hits.isEmpty() && ticks - hits.peekFirst()[0] > 1200) {
            hits.removeFirst();
        }
        if (killed && PlayerStats.classOf(player) == DungeonClass.BERSERK) {
            bloodlust.put(player.getUUID(), true);
        }
    }

    // ---------------------------------------------------------------- input

    /** A left click (air, block or mob): a Mage's beam, and three quick ones a Guided Sheep. */
    void leftClick(ServerPlayer player) {
        DungeonClass c = PlayerStats.classOf(player);
        if (c != DungeonClass.MAGE || !beamItem(player.getMainHandItem())) {
            net.rustclear.mod.RustclearMod.LOGGER.debug("No Mage Beam for {}: class {}, holding {}",
                player.getName().getString(), c, player.getMainHandItem());
            return;
        }
        if (ready(player, "beam", BEAM_GAP)) {
            beam(player);
        }
        Deque<Long> clicks = beamClicks.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        clicks.addLast(ticks);
        while (clicks.size() > 3) {
            clicks.removeFirst();
        }
        if (clicks.size() == 3) {
            Long[] at = clicks.toArray(Long[]::new);
            if (at[1] - at[0] >= 2 && at[1] - at[0] <= 5 && at[2] - at[1] >= 2 && at[2] - at[1] <= 5
                && isReady(player, "ability")) {
                clicks.clear();
                run.schedule(1, () -> guidedSheep(player, false));
            }
        }
    }

    /** Whether a Mage's left click with [stack] is a beam: anything held but bows, pickaxes, the 9th slot's map and pets. */
    static boolean beamItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.is(Items.BOW) || stack.is(Items.FILLED_MAP) || stack.is(Items.NETHER_STAR)
            || stack.getItem().toString().endsWith("pickaxe") || Pets.ofItem(stack) != null) {
            return false;
        }
        EngineItem engine = EngineItem.of(stack);
        if (engine == EngineItem.TERMINATOR || engine == EngineItem.DUNGEONBREAKER || engine == EngineItem.DIAMOND_PICKAXE) {
            return false;
        }
        ItemDefs.Instance item = PlayerStats.instance(stack);
        return item == null || item.def().type == null || !NO_BEAM_TYPES.contains(item.def().type);
    }

    /** The drop key: Ctrl+Q ([ultimate] false) the class ability, Q the ultimate. */
    void use(ServerPlayer player, boolean ultimate) {
        if (run.combat().isGhost(player) || player.isSpectator()) {
            return;
        }
        StatSheet s = PlayerStats.of(player);
        DungeonClass c = s.dungeonClass;
        if (c == null) {
            return;
        }
        int level = s.classLevel;
        double cdr = s.classBonus.cooldownReduction();
        String key = ultimate ? "ultimate" : "ability";
        if (!isReady(player, key)) {
            long left = (readyAt.get(player.getUUID() + ":" + key) - ticks + 19) / 20;
            player.sendSystemMessage(EngineItems.text(ultimate
                ? "§cYour Ultimate is currently on cooldown for " + left + " more seconds."
                : "§cYour Regular Ability is currently on cooldown for " + left + " more seconds."));
            return;
        }
        if (!ultimate) {
            switch (c) {
                case MAGE -> guidedSheep(player, true);
                case ARCHER -> explosiveShot(player, level);
                case BERSERK -> throwingAxe(player, level, cdr);
                case TANK -> seismicWave(player, s, cdr);
                case HEALER -> healingCircle(player, level, cdr);
            }
            return;
        }
        switch (c) {
            case MAGE -> thunderstorm(player, level, cdr);
            case ARCHER -> rapidFire(player, level, cdr);
            case BERSERK -> ragnarok(player, level, cdr);
            case TANK -> castleOfStone(player, cdr);
            case HEALER -> wish(player, level, cdr);
        }
    }

    // ---------------------------------------------------------------- Mage (engineerClient's)

    private void beam(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 dir = player.getLookAngle();
        Vec3 end = blockStop(player, eye, eye.add(dir.scale(BEAM_RANGE)));
        List<Entity> along = run.mobs().mobsAlong(eye, end, 0.3);
        Entity mob = along.isEmpty() ? null : along.get(0);
        if (mob != null) {
            end = mob.getBoundingBox().getCenter();
        }
        // Firework sparks every half block, from just in front of you to where it stops.
        line(eye.add(0, -0.2, 0).add(dir.scale(0.6)), end, ParticleTypes.FIREWORK, 0.5);
        if (mob != null) {
            run.mobs().hit(player, mob, 1.0, MobSystem.Hit.MELEE, false);
        }
    }

    private void guidedSheep(ServerPlayer player, boolean announce) {
        StatSheet s = PlayerStats.of(player);
        if (!ready(player, "ability", (int) cooldownTicks(DungeonClass.MAGE, false, s.classLevel, s.classBonus.cooldownReduction()))) {
            return;
        }
        if (announce) {
            player.sendSystemMessage(EngineItems.text("§aUsed §6Guided Sheep§a!"));
        }
        Sheep e = new Sheep(EntityTypes.SHEEP, level);
        e.setNoAi(true);
        e.setNoGravity(true);
        e.setPermanentlyInvulnerable(true);
        e.setSilent(true);
        e.snapTo(player.getX(), player.getY() + 0.781, player.getZ(), player.getYRot(), 0f);
        level.addFreshEntity(e);
        sheep.add(new FlyingSheep(player, e, ticks));
        sound(player.position(), SoundEvents.GHAST_SHOOT, 0.3f, 1f);
    }

    private void tickSheep() {
        for (Iterator<FlyingSheep> it = sheep.iterator(); it.hasNext(); ) {
            FlyingSheep f = it.next();
            long age = ticks - f.born;
            if (f.sheep.isRemoved() || f.owner.isRemoved()) {
                f.sheep.discard();
                it.remove();
                continue;
            }
            // Steered along the owner's look, a block a tick (two in its surge).
            Vec3 v = f.owner.getLookAngle().scale(age >= 4 && age <= 6 ? 2 : 1);
            Vec3 from = f.sheep.position().add(0, 0.4, 0);
            Vec3 to = from.add(v);
            Vec3 stop = blockStop(f.sheep, from, to);
            List<Entity> mobs = run.mobs().mobsAlong(from, stop, 0.3);
            boolean hitBlock = stop.distanceToSqr(to) > 1e-6;
            Vec3 at = (mobs.isEmpty() ? stop : mobs.get(0).position().add(0, 0.4, 0)).subtract(0, 0.4, 0);
            f.sheep.snapTo(at.x, at.y, at.z, (float) Math.toDegrees(Math.atan2(-v.x, v.z)), 0f);
            if (!mobs.isEmpty() || hitBlock || age >= SHEEP_LIFE) {
                explodeSheep(f.owner, at);
                f.sheep.discard();
                it.remove();
            }
        }
    }

    /** The sheep's blast: every mob within 4 blocks, for 2,000 a Mage level (the wiki's top of its 100-2,000 a level), x Ability Damage. */
    private void explodeSheep(ServerPlayer owner, Vec3 at) {
        StatSheet s = PlayerStats.of(owner);
        double damage = CombatMath.abilityHit(s, 2000.0 * Math.max(1, s.classLevel), 0);
        int hit = blast(owner, at, SHEEP_BLAST, damage);
        sound(at, SoundEvents.GENERIC_EXPLODE.value(), 1f, 0.8f + level.getRandom().nextFloat() * 0.18f);
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
        if (hit > 0) {
            hitLine(owner, "Guided Sheep", hit, damage * hit);
        }
    }

    /**
     * Thunderstorm: lightning on every mob within 10 blocks once a second (assumed: 1,000 a level a
     * strike) for 15 s. Hypixel prints one "Your Thunderstorm hit N enemies for X damage." 304-305
     * ticks after the cast (12 of 12), so the storm lasts about that long (OPEN: strike cadence).
     */
    private static final int THUNDERSTORM_TICKS = 300;
    private static final int THUNDERSTORM_LINE = 304;

    private void thunderstorm(ServerPlayer player, int level, double cdr) {
        startCooldown(player, "ultimate", cooldownTicks(DungeonClass.MAGE, true, level, cdr));
        player.sendSystemMessage(EngineItems.text("§aUsed §6Thunderstorm§a!"));
        Set<Entity> struck = new java.util.HashSet<>();
        double[] total = {0};
        effects.add(new Running(age -> {
            if (age % 20 == 0 && age < THUNDERSTORM_TICKS) {
                StatSheet s = PlayerStats.of(player);
                double damage = CombatMath.abilityHit(s, 1000.0 * Math.max(1, level), 0);
                for (Entity mob : run.mobs().mobsIn(player.getBoundingBox().inflate(10))) {
                    Vec3 p = mob.position();
                    this.level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y + 1, p.z, 20, 0.3, 1, 0.3, 0.1);
                    run.mobs().damage(mob, Math.max(1, Math.round(damage)), player);
                    struck.add(mob);
                    total[0] += damage;
                }
                sound(player.position(), SoundEvents.LIGHTNING_BOLT_THUNDER, 0.6f, 1.2f);
            }
            if (age == THUNDERSTORM_LINE && !struck.isEmpty()) {
                hitLine(player, "Thunderstorm", struck.size(), total[0]);
            }
            return age >= THUNDERSTORM_LINE;
        }, ticks));
    }

    /** "Your X hit N enemy/enemies for D damage." (gray, the numbers red), as Hypixel prints it. */
    private static void hitLine(ServerPlayer player, String ability, int hit, double damage) {
        player.sendSystemMessage(EngineItems.text("§7Your " + ability + " hit §c" + hit + " §7" + (hit == 1 ? "enemy" : "enemies")
            + " for §c" + Stat.number(damage) + " §7damage."));
    }

    // ---------------------------------------------------------------- Archer (engineerClient's)

    /**
     * Explosive Shot: three arrows at your look and +-5.5 degrees, each exploding where it lands.
     * Hypixel's one "Your Explosive Shot hit N enemies for X damage." comes 1 tick after the cast
     * (52 of 56), so the explosions land then, whatever the distance.
     */
    private void explosiveShot(ServerPlayer player, int level) {
        startCooldown(player, "ability", cooldownTicks(DungeonClass.ARCHER, false, level, 0));
        player.sendSystemMessage(EngineItems.text("§aUsed §6Explosive Shot§a!"));
        double[] blasts = {0, 0};
        for (float dy : new float[] {-5.5f, 0f, 5.5f}) {
            Vec3 dir = ItemSystem.look(player.getYRot() + dy, player.getXRot());
            shootRay(player, dir, blasts);
        }
        run.schedule(1, () -> {
            if (blasts[0] > 0) {
                hitLine(player, "Explosive Shot", (int) blasts[0], blasts[1]);
            }
        });
        sound(player.position(), SoundEvents.ARROW_SHOOT, 1f, 0.8f);
    }

    /** Rapid Fire: an arrow every 2 ticks along your look for 3 s, +1 s every 10 levels. */
    private void rapidFire(ServerPlayer player, int level, double cdr) {
        startCooldown(player, "ultimate", cooldownTicks(DungeonClass.ARCHER, true, level, cdr));
        int duration = (3 + level / 10) * 20;
        player.sendSystemMessage(EngineItems.text("§aUsed §6Rapid Fire§a!"));
        effects.add(new Running(age -> {
            if (age % 2 == 0 && !player.isRemoved()) {
                shootRay(player, player.getLookAngle(), null);
                sound(player.position(), SoundEvents.ARROW_SHOOT, 0.6f, 1.2f);
            }
            return age >= duration;
        }, ticks));
    }

    /**
     * An arrow along [dir]: a real arrow to see, with its hit worked out now along its path (the
     * first mob, else the first block), landing a tick for every 3 blocks it flies. With [blasts]
     * it explodes 1 tick after the shot, adding {enemies hit, damage} there.
     */
    private void shootRay(ServerPlayer player, Vec3 dir, double[] blasts) {
        boolean explode = blasts != null;
        Vec3 from = new Vec3(player.getX(), player.getY() + player.getBbHeight() / 2, player.getZ());
        Vec3 to = blockStop(player, from, from.add(dir.scale(ARROW_RANGE)));
        List<Entity> mobs = run.mobs().mobsAlong(from, to, 0.3);
        Entity mob = mobs.isEmpty() ? null : mobs.get(0);
        Vec3 land = mob == null ? to : mob.getBoundingBox().getCenter();
        Arrow arrow = new Arrow(level, player, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        arrow.setPos(from.x, from.y, from.z);
        arrow.shoot(dir.x, dir.y, dir.z, 3f, 0f);
        arrow.setCritArrow(true);
        arrow.setNoGravity(true);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        // Never hits anything itself: the hit below is the arrow's.
        arrow.noPhysics = true;
        level.addFreshEntity(arrow);
        int flight = explode ? 1 : Math.max(1, (int) Math.ceil(from.distanceTo(land) / 3));
        run.schedule(flight, () -> {
            arrow.discard();
            if (mob != null && !mob.isRemoved()) {
                run.mobs().arrow(player, mob, 1.0, false);
            }
            if (explode) {
                StatSheet s = PlayerStats.of(player);
                double damage = CombatMath.expectedHit(s, CombatMath.Kind.ARROW, 1);
                int hit = blast(player, land, EXPLOSION_RADIUS, damage);
                blasts[0] += hit;
                blasts[1] += hit * damage;
                sound(land, SoundEvents.GENERIC_EXPLODE.value(), 1f, 0.8f + level.getRandom().nextFloat() * 0.18f);
                level.sendParticles(ParticleTypes.EXPLOSION, land.x, land.y, land.z, 1, 0, 0, 0, 0);
            }
        });
    }

    // ---------------------------------------------------------------- Berserk

    /** Throwing Axe: your highest hit of the last minute, +1% a Berserk level, at the first mob within 20 blocks. 10 s. */
    private void throwingAxe(ServerPlayer player, int level, double cdr) {
        startCooldown(player, "ability", cooldownTicks(DungeonClass.BERSERK, false, level, cdr));
        long best = 0;
        for (long[] h : recentHits.getOrDefault(player.getUUID(), new ArrayDeque<>())) {
            if (ticks - h[0] <= 1200) {
                best = Math.max(best, h[1]);
            }
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = blockStop(player, eye, eye.add(player.getLookAngle().scale(20)));
        List<Entity> mobs = run.mobs().mobsAlong(eye, end, 0.5);
        line(eye, mobs.isEmpty() ? end : mobs.get(0).getBoundingBox().getCenter(), ParticleTypes.CRIT, 0.5);
        sound(player.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1f, 0.8f);
        player.sendSystemMessage(EngineItems.text("§aUsed §6Throwing Axe§a!"));
        if (!mobs.isEmpty() && best > 0) {
            run.mobs().damage(mobs.get(0), Math.round(best * (1 + 0.01 * level)), player);
        }
    }

    /**
     * Ragnarok: +100 Attack Speed, +400 Speed and 50% more damage for 15 s (StatBuilder.Buffs),
     * and 3 Zombie minions that fight beside you for as long. 60 s.
     */
    private void ragnarok(ServerPlayer player, int level, double cdr) {
        startCooldown(player, "ultimate", cooldownTicks(DungeonClass.BERSERK, true, level, cdr));
        ragnarokUntil.put(player.getUUID(), ticks + RAGNAROK_TICKS);
        // Last cast's minions make way for the new ones.
        minions.removeIf(m -> {
            if (m.owner == player) {
                m.zombie.discard();
                return true;
            }
            return false;
        });
        for (int i = 0; i < 3; i++) {
            double a = player.getYRot() * Math.PI / 180 + Math.PI * 2 * i / 3;
            spawnMinion(player, player.getX() - Math.sin(a) * 1.5, player.getY(), player.getZ() + Math.cos(a) * 1.5);
        }
        player.sendSystemMessage(EngineItems.text("§aUsed §6Ragnarok§a!"));
        sound(player.position(), SoundEvents.WITHER_SPAWN, 0.6f, 1.4f);
    }

    private void spawnMinion(ServerPlayer owner, double x, double y, double z) {
        Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.TRIGGERED);
        if (zombie == null) {
            return;
        }
        // Moved here each tick (tickMinions), like the dungeon's own mobs: vanilla AI would go for players.
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.setPermanentlyInvulnerable(true);
        zombie.setSilent(true);
        zombie.setCanPickUpLoot(false);
        // The helmet also keeps it from burning in daylight.
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        zombie.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            zombie.setDropChance(slot, 0f);
        }
        zombie.setCustomName(EngineItems.text("§c" + owner.getName().getString() + "'s Ragnarok Minion"));
        zombie.setCustomNameVisible(true);
        zombie.snapTo(x, y, z, owner.getYRot(), 0f);
        zombie.setYHeadRot(owner.getYRot());
        zombie.setYBodyRot(owner.getYRot());
        level.addFreshEntity(zombie);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 1, z, 12, 0.3, 0.6, 0.3, 0.02);
        minions.add(new Minion(owner, zombie, ticks + RAGNAROK_TICKS));
    }

    /**
     * Each minion walks to the nearest mob within 12 blocks and hits it once a second for a
     * quarter of its owner's average melee hit (assumed: the wiki only gives their health), or
     * follows its owner when there's none. Gone after 15 s.
     */
    private void tickMinions() {
        for (Iterator<Minion> it = minions.iterator(); it.hasNext(); ) {
            Minion m = it.next();
            Zombie z = m.zombie;
            if (z.isRemoved() || m.owner.isRemoved() || ticks >= m.until || z.level() != m.owner.level()) {
                Vec3 p = z.position();
                level.sendParticles(ParticleTypes.POOF, p.x, p.y + 1, p.z, 8, 0.3, 0.5, 0.3, 0.02);
                z.discard();
                it.remove();
                continue;
            }
            if (z.distanceTo(m.owner) > 20) {
                z.snapTo(m.owner.getX(), m.owner.getY(), m.owner.getZ(), z.getYRot(), 0f);
                m.vy = 0;
                continue;
            }
            Entity target = null;
            double best = Double.MAX_VALUE;
            for (Entity mob : run.mobs().mobsIn(z.getBoundingBox().inflate(12))) {
                double d = mob.distanceToSqr(z);
                if (d < best) {
                    best = d;
                    target = mob;
                }
            }
            Vec3 goal = target != null ? target.position() : m.owner.position();
            double stopAt = target != null ? 1.6 : 3;
            double dx = goal.x - z.getX();
            double dz = goal.z - z.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 1e-3) {
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                z.setYRot(yaw);
                z.setYHeadRot(yaw);
                z.setYBodyRot(yaw);
            }
            double step = dist > stopAt ? Math.min(MINION_SPEED, dist - stopAt) / dist : 0;
            if (z.onGround()) {
                m.vy = z.horizontalCollision && step > 0 ? 0.42 : 0;
            } else {
                m.vy = Math.max(-3.9, m.vy - 0.08);
            }
            z.move(MoverType.SELF, new Vec3(dx * step, m.vy, dz * step));
            if (target != null && Math.sqrt(best) <= 2.5 && ticks >= m.nextHit) {
                m.nextHit = ticks + 20;
                z.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
                StatSheet s = PlayerStats.of(m.owner);
                run.mobs().damage(target, Math.max(1, Math.round(CombatMath.expectedHit(s, CombatMath.Kind.MELEE, 1) * MINION_SHARE)), m.owner);
            }
        }
    }

    // ---------------------------------------------------------------- Tank

    /**
     * Seismic Wave: every mob within 8 blocks in front of you (a 90 degree cone), for 50,000 a
     * Tank level on Master Mode and 35,000 on Floor VII (the wiki's 20,000-50,000 a level by
     * floor), +100,000 at 50. 15 s (assumed).
     */
    private void seismicWave(ServerPlayer player, StatSheet s, double cdr) {
        int tank = s.classLevel;
        startCooldown(player, "ability", cooldownTicks(DungeonClass.TANK, false, tank, cdr));
        double damage = (run.floor.master() ? 50_000.0 : 35_000.0) * tank + (tank >= DungeonClass.MAX_LEVEL ? 100_000 : 0);
        Vec3 eye = player.position();
        Vec3 aim = player.getLookAngle().multiply(1, 0, 1).normalize();
        int hit = 0;
        double each = CombatMath.abilityHit(s, damage, 0);
        for (Entity mob : run.mobs().mobsIn(player.getBoundingBox().inflate(8))) {
            Vec3 to = mob.position().subtract(eye).multiply(1, 0, 1);
            if (to.length() <= 8 && to.normalize().dot(aim) >= Math.cos(Math.toRadians(45))) {
                run.mobs().damage(mob, Math.max(1, Math.round(each)), player);
                hit++;
            }
        }
        // Hypixel's line came 11 ticks after the cast (one recorded cast, OPEN).
        if (hit > 0) {
            int n = hit;
            run.schedule(11, () -> hitLine(player, "Seismic Wave", n, each * n));
        }
        for (int i = 1; i <= 8; i++) {
            Vec3 at = eye.add(aim.scale(i));
            level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.1, at.z, 6, i * 0.2, 0.05, i * 0.2, 0.02);
        }
        sound(eye, SoundEvents.GENERIC_EXPLODE.value(), 0.8f, 0.6f);
        player.sendSystemMessage(EngineItems.text("§aUsed §6Seismic Wave§a!"));
    }

    /** Castle of Stone: 70% less damage taken for 20 s. 150 s. */
    private void castleOfStone(ServerPlayer player, double cdr) {
        startCooldown(player, "ultimate", cooldownTicks(DungeonClass.TANK, true, 0, cdr));
        run.combat().protect(player, 400, 0.3);
        player.sendSystemMessage(EngineItems.text("§aUsed §6Castle of Stone§a!"));
        sound(player.position(), SoundEvents.ANVIL_LAND, 1f, 0.6f);
    }

    // ---------------------------------------------------------------- Healer

    /**
     * Healing Circle: a circle at your feet for 5 s (+1 s every 5 levels) and 5 blocks across
     * (+0.5 every 5), healing everyone in it 1% of their max Health a second (+0.5% every 5
     * levels; 1% since the Jun 10 2026 nerf). 2 s cooldown, one circle at a time.
     */
    private void healingCircle(ServerPlayer player, int level, double cdr) {
        int fives = level / 5;
        int duration = (5 + fives) * 20;
        double radius = 2.5 + 0.25 * fives;
        double share = (1 + 0.5 * fives) / 100;
        startCooldown(player, "ability", cooldownTicks(DungeonClass.HEALER, false, level, cdr));
        Vec3 at = player.position();
        player.sendSystemMessage(EngineItems.text("§aUsed §6Healing Circle§a!"));
        effects.add(new Running(age -> {
            for (int i = 0; i < 16; i++) {
                double a = Math.PI * 2 * i / 16;
                this.level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x + Math.cos(a) * radius, at.y + 0.2,
                    at.z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
            }
            if (age % 20 == 0 && !player.isRemoved()) {
                for (ServerPlayer other : this.level.players()) {
                    if (other.position().distanceTo(at) <= radius + 0.5) {
                        run.combat().heal(other, PlayerStats.of(other).get(Stat.HEALTH) * share, player);
                    }
                }
            }
            return age >= duration;
        }, ticks));
    }

    /**
     * Wish: the whole party to full health and an absorption shield of max Health x (0.6 + 0.008 x
     * the Healer's level), 100% at 50 (59 of 60 recorded Wishes within 1%, session 8). 120 s.
     */
    private void wish(ServerPlayer player, int level, double cdr) {
        startCooldown(player, "ultimate", cooldownTicks(DungeonClass.HEALER, true, level, cdr));
        double share = 0.6 + 0.008 * level;
        // OPEN: never recorded from the Healer's side; every other class prints "Used X!".
        player.sendSystemMessage(EngineItems.text("§aUsed §6Wish§a!"));
        for (ServerPlayer other : this.level.players()) {
            if (run.combat().isGhost(other)) {
                continue;
            }
            double before = run.combat().health(other);
            run.combat().healFull(other);
            double healed = Math.max(0, run.combat().health(other) - before);
            double shield = PlayerStats.of(other).get(Stat.HEALTH) * share;
            run.combat().shield(other, shield);
            // Hypixel's line (51 recorded on teammates' casts): heal to one decimal, shield whole.
            other.sendSystemMessage(EngineItems.text(DungeonRun.nameColour(player) + "'s §6Wish §ehealed you for §a"
                + TabList.trimmed(healed) + " §ehealth and granted you an absorption shield with §a"
                + String.format(java.util.Locale.ROOT, "%,d", Math.round(shield)) + " §ehealth!"));
        }
        sound(player.position(), SoundEvents.PLAYER_LEVELUP, 1f, 1.5f);
    }

    // ---------------------------------------------------------------- the rest

    void tick() {
        ticks++;
        announceAvailable();
        tickSheep();
        tickMinions();
        for (Iterator<Running> it = effects.iterator(); it.hasNext(); ) {
            Running r = it.next();
            if (r.effect.tick(ticks - r.start)) {
                it.remove();
            }
        }
    }

    void dispose() {
        sheep.forEach(f -> f.sheep.discard());
        sheep.clear();
        minions.forEach(m -> m.zombie.discard());
        minions.clear();
        effects.clear();
        available.clear();
    }

    /** Every mob within [radius] of [at] takes [damage]; how many. */
    private int blast(ServerPlayer owner, Vec3 at, double radius, double damage) {
        int n = 0;
        for (Entity mob : run.mobs().mobsIn(new net.minecraft.world.phys.AABB(at, at).inflate(radius))) {
            if (mob.position().distanceTo(at) <= radius + 1) {
                run.mobs().damage(mob, Math.max(1, Math.round(damage)), owner);
                n++;
            }
        }
        if (n > 0) {
            recordHit(owner, Math.round(damage), false);
        }
        return n;
    }

    private boolean isReady(ServerPlayer player, String key) {
        Long at = readyAt.get(player.getUUID() + ":" + key);
        return at == null || ticks >= at;
    }

    private boolean ready(ServerPlayer player, String key, int cooldown) {
        if (!isReady(player, key)) {
            return false;
        }
        startCooldown(player, key, cooldown);
        return true;
    }

    /** A cast's cooldown, with "X is now available!" (the class ability or ultimate) when it ends. */
    private void startCooldown(ServerPlayer player, String key, long cooldown) {
        readyAt.put(player.getUUID() + ":" + key, ticks + cooldown);
        if (key.equals("ability") || key.equals("ultimate")) {
            available.removeIf(a -> a.player == player && a.key.equals(key));
            available.add(new Available(player, key, ticks + cooldown));
        }
    }

    private record Available(ServerPlayer player, String key, long at) {}

    private final List<Available> available = new ArrayList<>();

    /** The recorded lines: "Guided Sheep is now available!", "Your Berserk ULTIMATE Ragnarok is now available!". */
    private void announceAvailable() {
        for (Iterator<Available> it = available.iterator(); it.hasNext(); ) {
            Available a = it.next();
            if (ticks < a.at) {
                continue;
            }
            it.remove();
            DungeonClass c = PlayerStats.classOf(a.player);
            if (c == null || a.player.isRemoved()) {
                continue;
            }
            a.player.sendSystemMessage(EngineItems.text(a.key.equals("ultimate")
                ? "§aYour " + c.title + " §e§lULTIMATE§a §6" + c.ultimate + " §ais now available!"
                : "§6" + c.ability + " §ais now available!"));
        }
    }

    private Vec3 blockStop(Entity by, Vec3 from, Vec3 to) {
        var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, by));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : to;
    }

    private void line(Vec3 from, Vec3 to, ParticleOptions particle, double step) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        Vec3 dir = len < 1e-6 ? Vec3.ZERO : d.scale(1 / len);
        for (double t = 0; t <= len; t += step) {
            Vec3 at = from.add(dir.scale(t));
            level.sendParticles(particle, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
    }

    private void sound(Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
    }
}
