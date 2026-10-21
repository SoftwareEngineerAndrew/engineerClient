package net.rustclear.mod.engine.blood;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.engine.EngineItems;
import net.rustclear.mod.engine.SimLog;
import net.rustclear.mod.engine.boss.Attacks;
import net.rustclear.mod.engine.boss.BossContext;
import net.rustclear.mod.engine.boss.Scheduler;
import net.rustclear.mod.mixin.ArmorStandAccessor;
import net.rustclear.mod.mixin.LivingEntityAccessor;

/**
 * The Blood Room camp (blood-watcher.md): the Watcher and his Watchful Eye, the 30 wall heads,
 * the 15 mobs he throws, the kill count on the 10-tick loop, his FAST/SLOW departures, his chat,
 * and the portal into the boss. Its clock starts on the blood door's tick (door = 0).
 * - The Watcher (an invisible zombie wearing his head) is added at the room's centre on the door
 *   tick, his Eye 2.5 above; their HP shows one tick later. His HP is the kill counter.
 * - The opening throws the giant (t0 + 115..121), the revived boss, then two normals; their heads
 *   fly 80 ticks. After door + 420 he leaves only on beats: FAST (door + 40k) when nothing is
 *   alive, SLOW (door + 60k) when something is; later heads fly 40.
 * - A death on tick d counts on the first loop tick (door + 10k) after d (Rule K). The 15th count
 *   ends the camp: "proven" (on that tick, or when the chat queue frees up within 20), the portal
 *   80 or 90 ticks after the count, the Watcher gone about 78 after proven.
 */
public final class BloodCamp {
    private final BossContext ctx;
    /** Ticks since the blood door opened (the camp's t, door = 0). */
    final Scheduler clock = new Scheduler();
    private boolean started;
    private boolean done;

    private BossContext.Room room;
    private Vec3 centre;
    /** Unit steps towards the portal (away from the door), and across. */
    private int px;
    private int pz;
    private int qx;
    private int qz;
    private BloodData.Modifier modifier;
    private double watcherMax;

    private Zombie watcher;
    private Zombie eye;
    private ArmorStand watcherTag;
    private ArmorStand eyeTag;
    private Vec3 eyeOffset = Vec3.ZERO;

    /** What a wall head releases. */
    private enum Kind { NORMAL, BOSS, GIANT, DECOY }

    private static final class Head {
        final ArmorStand stand;
        final Vec3 at;
        final Kind kind;
        final String name;

        Head(ArmorStand stand, Vec3 at, Kind kind, String name) {
            this.stand = stand;
            this.at = at;
            this.kind = kind;
            this.name = name;
        }
    }

    /** A head in the air: from, to, the ticks it takes and how far it is. */
    private static final class Flight {
        final Head head;
        final Vec3 from;
        final Vec3 to;
        final int ticks;
        int t;

        Flight(Head head, Vec3 to, int ticks) {
            this.head = head;
            this.from = head.at;
            this.to = to;
            this.ticks = ticks;
        }
    }

    private final List<Head> wall = new ArrayList<>();
    /** The heads this camp throws, in order: the giant, the boss, two normals, then 11 normals in any order. */
    private final List<Head> picks = new ArrayList<>();
    private final List<Flight> flights = new ArrayList<>();
    private int thrown;

    /** The Watcher's flight: where he is, where he's going and how fast. */
    private Vec3 pos;
    private Vec3 goal;
    private double speed;
    private Runnable onArrive;
    /** Later phase: ready to leave on the next beat (home or at a head he has thrown). */
    private boolean idle;

    /** Counted mobs: added so far, and each one's death tick. */
    private int landed;
    private final List<Long> deaths = new ArrayList<>();
    private final Map<Entity, Long> landedAt = new HashMap<>();
    private final Map<Entity, CampMob> campMobs = new HashMap<>();
    private int counted;
    private long finalCount = -1;
    private long proven = -1;
    private long portalAt = -1;
    private long lastAttackLine = Long.MIN_VALUE;
    /** Beats the first FAST move has waited on his talking. */
    private int talkHolds;

    public static volatile boolean talkGate = true;

    public BloodCamp(BossContext ctx) {
        this.ctx = ctx;
    }

    /** The blood door opened this tick. */
    public void start() {
        if (started) {
            return;
        }
        room = ctx.bloodRoom();
        if (room == null) {
            return;
        }
        started = true;
        clock.reset();
        centre = room.centre();
        px = -room.doorDx();
        pz = -room.doorDz();
        qx = -pz;
        qz = px;
        Random random = ctx.random();
        modifier = BloodData.Modifier.values()[random.nextInt(BloodData.Modifier.values().length)];
        watcherMax = ctx.profile().watcherHealth;
        spawnWatcher(random);
        buildWall(random);
        pos = centre;
        SimLog.event("camp_start", "modifier", modifier.word);
        clock.at(1, () -> {
            setHealth(watcher, watcherMax);
            setHealth(eye, ctx.profile().eyeHealth);
            ctx.bar().bossGated("The Watcher", 1f);
        });
        clock.at(5, () -> {
            ctx.level().addFreshEntity(watcherTag);
            ctx.level().addFreshEntity(eyeTag);
            ctx.entities().add(watcherTag);
            ctx.entities().add(eyeTag);
        });
        clock.at(BloodData.T0, () -> ctx.chat().say(BloodData.line(BloodData.INTRO), BloodData.GAP_SPAWN));
        scheduleOpening(random);
    }

    public boolean started() {
        return started;
    }

    public java.util.List<Entity> mobs() {
        return new java.util.ArrayList<>(campMobs.keySet());
    }

    /** A live camp mob's starting HP (0 if it isn't one): the camper takes the giant and the boss first. */
    public double hpOf(Entity e) {
        CampMob m = campMobs.get(e);
        return m == null ? 0 : m.health();
    }

    public java.util.List<Entity> flyingHeads() {
        java.util.List<Entity> out = new java.util.ArrayList<>();
        for (Flight f : flights) {
            out.add(f.head.stand);
        }
        return out;
    }

    /** The camp is over and the portal is open. */
    public boolean done() {
        return done;
    }

    /** The portal's middle block (null before it opens). */
    public BlockPos portal() {
        if (portalAt < 0) {
            return null;
        }
        Vec3 mid = local(BloodData.PORTAL_OUT, 0);
        return BlockPos.containing(mid.x, room.floorY(), mid.z);
    }

    /** Whether [entity] is the Watcher or his Eye (players' hits on them only make him talk). */
    public boolean isWatcher(Entity entity) {
        return entity != null && (entity == watcher || entity == eye);
    }

    // ---------------------------------------------------------------- setup

    private void spawnWatcher(Random random) {
        BloodSkins skins = BloodSkins.get();
        // Hypixel's ids: the Watcher, his tag (+1), the Eye (+2), its tag (+3); the tags come 5 ticks later.
        watcher = zombie(centre, skins.watcher);
        watcherTag = tag(" §cThe Watcher");
        double angle = random.nextDouble() * Math.PI * 2;
        double reach = 0.6 + random.nextDouble() * 1.9;
        eyeOffset = new Vec3(Math.cos(angle) * reach, 2.5, Math.sin(angle) * reach);
        eye = zombie(centre.add(eyeOffset), skins.eye);
        eyeTag = tag(" §3§lWatchful Eye");
        placeTags();
    }

    private Zombie zombie(Vec3 at, String texture) {
        ServerLevel level = ctx.level();
        Zombie z = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.TRIGGERED);
        z.setNoAi(true);
        z.setNoGravity(true);
        z.setSilent(true);
        z.setPermanentlyInvulnerable(true);
        z.setPersistenceRequired();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            z.setDropChance(slot, 0f);
        }
        if (texture != null) {
            z.setItemSlot(EquipmentSlot.HEAD, EngineItems.head(texture, null));
        }
        z.snapTo(at.x, at.y, at.z, 0f, 0f);
        level.addFreshEntity(z);
        ctx.entities().add(z);
        return z;
    }

    private ArmorStand tag(String legacy) {
        ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, ctx.level());
        ((ArmorStandAccessor) stand).rustclear$setMarker(true);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setPermanentlyInvulnerable(true);
        stand.setCustomNameVisible(true);
        stand.setCustomName(EngineItems.styled(legacy));
        return stand;
    }

    /** Max health attribute and synced health (data index 9), as Hypixel sets both a tick after the add. */
    private static void setHealth(LivingEntity entity, double hp) {
        if (entity == null || entity.isRemoved()) {
            return;
        }
        var max = entity.getAttribute(Attributes.MAX_HEALTH);
        if (max != null && max.getBaseValue() < hp) {
            max.setBaseValue(hp);
        }
        entity.setInvisible(true);
        entity.getEntityData().set(LivingEntityAccessor.rustclear$healthId(), (float) hp);
    }

    /**
     * The 30 wall heads: 17 normals, the 4 revived bosses, the giant and 8 others, at random
     * places (they differ per camp), each wearing the head of what it releases. Then the picks:
     * the giant, one boss, and 13 different normals.
     */
    private void buildWall(Random random) {
        BloodSkins skins = BloodSkins.get();
        List<Head> kinds = new ArrayList<>();
        for (String n : BloodData.NORMALS) {
            kinds.add(new Head(null, null, Kind.NORMAL, n));
        }
        for (String b : BloodData.BOSSES) {
            kinds.add(new Head(null, null, Kind.BOSS, b));
        }
        kinds.add(new Head(null, null, Kind.GIANT, "Giant"));
        int slots = BloodData.ROWS.length * BloodData.ROW.length;
        for (int i = 0; kinds.size() < slots; i++) {
            kinds.add(new Head(null, null, Kind.DECOY,
                skins.decoys.isEmpty() ? null : skins.decoys.get(i % skins.decoys.size())));
        }
        Collections.shuffle(kinds, random);
        int i = 0;
        for (double dy : BloodData.ROWS) {
            for (double[] p : BloodData.ROW) {
                Head k = kinds.get(i++);
                Vec3 at = local(p[0], p[1]).add(0, dy, 0);
                ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, ctx.level());
                stand.setInvisible(true);
                stand.setNoGravity(true);
                stand.setPermanentlyInvulnerable(true);
                String texture = switch (k.kind) {
                    case GIANT -> skins.giant;
                    case DECOY -> k.name;
                    default -> skins.npc(k.name) == null ? null : skins.npc(k.name).value();
                };
                if (texture != null) {
                    stand.setItemSlot(EquipmentSlot.HEAD, EngineItems.head(texture, null));
                }
                float yaw = yawTo(at, centre);
                stand.snapTo(at.x, at.y, at.z, yaw, 0f);
                stand.setYHeadRot(yaw);
                ctx.level().addFreshEntity(stand);
                ctx.entities().add(stand);
                wall.add(new Head(stand, at, k.kind, k.kind == Kind.DECOY ? null : k.name));
            }
        }
        List<Head> normals = new ArrayList<>();
        List<Head> bosses = new ArrayList<>();
        for (Head h : wall) {
            switch (h.kind) {
                case GIANT -> picks.add(h);
                case BOSS -> bosses.add(h);
                case NORMAL -> normals.add(h);
                default -> {
                }
            }
        }
        picks.add(bosses.get(random.nextInt(bosses.size())));
        Collections.shuffle(normals, random);
        picks.addAll(normals.subList(0, BloodData.MOBS - 2));
    }

    /** A point [x] towards the portal and [z] across from the centre, at the centre's height. */
    private Vec3 local(double x, double z) {
        return centre.add(px * x + qx * z, 0, pz * x + qz * z);
    }

    // ---------------------------------------------------------------- the opening

    private void scheduleOpening(Random random) {
        int[] throwsAt = new int[4];
        throwsAt[0] = BloodData.FIRST_THROW_MIN + random.nextInt(BloodData.FIRST_THROW_SPREAD);
        for (int i = 0; i < 3; i++) {
            int jitter = random.nextInt(2 * BloodData.OPENING_JITTER + 1) - BloodData.OPENING_JITTER;
            throwsAt[i + 1] = Math.max(throwsAt[i] + 20, BloodData.OPENING_THROWS[i] + jitter);
        }
        for (int i = 0; i < 4; i++) {
            Head head = picks.get(i);
            int at = throwsAt[i];
            boolean last = i == 3;
            Vec3 stop = before(head);
            // He hops to the head and is there DWELL ticks before he throws it.
            int prevThrow = i == 0 ? BloodData.T0 : throwsAt[i - 1];
            clock.at(Math.max(prevThrow + 1, at - BloodData.DWELL - 40), () -> fly(stop, BloodData.HOP_SPEED, null));
            clock.at(at, () -> {
                throwHead(head, BloodData.FLIGHT_OPENING);
                if (last) {
                    fly(centre, BloodData.HOME_SPEED, () -> idle = true);
                }
            });
        }
    }

    /** Where the Watcher stops to throw [head]: STAND_OFF in front of it. */
    private Vec3 before(Head head) {
        Vec3 in = new Vec3(centre.x - head.at.x, 0, centre.z - head.at.z);
        return head.at.add(in.normalize().scale(BloodData.STAND_OFF)).add(0, 0.5, 0);
    }

    // ---------------------------------------------------------------- ticking

    public void tick() {
        if (!started) {
            return;
        }
        long now = clock.now();
        moveWatcher();
        flyHeads(now);
        departures(now);
        if (now % BloodData.LOOP == 0) {
            count(now);
        }
        if ((now - 1) % 20 == 0) {
            for (ServerPlayer p : ctx.players()) {
                if (!ctx.bar().admitted(p) && ctx.inBloodRoom(p)) {
                    ctx.bar().admit(p);
                }
            }
        }
        attacks(now);
        if (portalAt >= 0) {
            for (ServerPlayer p : ctx.players()) {
                // Vanilla's Nether trip never starts; walking into the portal takes the party into the boss.
                p.setPortalCooldown();
                if (ctx.level().getBlockState(p.blockPosition()).is(Blocks.NETHER_PORTAL)
                    || ctx.level().getBlockState(p.blockPosition().above()).is(Blocks.NETHER_PORTAL)) {
                    ctx.enterBoss();
                }
            }
        }
        clock.tick();
    }

    private void fly(Vec3 to, double blocksPerTick, Runnable arrive) {
        goal = to;
        speed = blocksPerTick;
        onArrive = arrive;
        idle = false;
    }

    private void moveWatcher() {
        if (watcher == null || watcher.isRemoved()) {
            return;
        }
        if (goal != null) {
            Vec3 d = goal.subtract(pos);
            double len = d.length();
            if (len <= speed) {
                pos = goal;
                goal = null;
                Runnable r = onArrive;
                onArrive = null;
                if (r != null) {
                    r.run();
                }
            } else {
                pos = pos.add(d.scale(speed / len));
            }
        }
        ServerPlayer near = nearest(pos, 64);
        float yaw = near == null ? watcher.getYRot() : yawTo(pos, near.position());
        watcher.snapTo(pos.x, pos.y, pos.z, yaw, 0f);
        watcher.setYHeadRot(yaw);
        Vec3 e = pos.add(eyeOffset);
        eye.snapTo(e.x, e.y, e.z, yaw, 0f);
        eye.setYHeadRot(yaw);
        placeTags();
    }

    private void placeTags() {
        watcherTag.snapTo(watcher.getX(), watcher.getY() + 2.0, watcher.getZ(), 0f, 0f);
        eyeTag.snapTo(eye.getX(), eye.getY() + 2.0, eye.getZ(), 0f, 0f);
    }

    /** After the opening: leave on a beat for the next head (blood-watcher.md §6). */
    private void departures(long now) {
        if (!idle || now < BloodData.FIRST_DEPARTURE || thrown >= picks.size()) {
            return;
        }
        boolean alive = alive(now);
        boolean fast = now % BloodData.FAST_BEAT == 0 && !alive;
        boolean slow = now % BloodData.SLOW_BEAT == 0 && alive;
        if (fast && thrown == 4 && talkGate && talkHolds < BloodData.TALK_MAX_HOLDS && talking(now)) {
            // The opening's mobs all died early and he is still talking: he waits for a later beat (two at most).
            talkHolds++;
            SimLog.event("camp_talk_hold", "t", now, "holds", talkHolds);
            return;
        }
        if (!fast && !slow) {
            return;
        }
        Head head = picks.get(thrown);
        SimLog.event("camp_depart", "beat", fast ? "FAST" : "SLOW", "alive", alive, "t", now);
        // He throws on the way: DWELL ticks after leaving, or on arrival if that's sooner (341 later
        // throws: a median 6.2 blocks short of the head, mostly beat + 10), then flies on to it.
        boolean[] thrownIt = {false};
        Runnable toss = () -> {
            if (thrownIt[0]) {
                return;
            }
            thrownIt[0] = true;
            throwHead(head, BloodData.FLIGHT_LATER);
        };
        clock.after(BloodData.DWELL, toss);
        fly(before(head), fast ? BloodData.FAST_SPEED : BloodData.SLOW_SPEED, () -> {
            toss.run();
            if (thrown >= picks.size()) {
                fly(centre, BloodData.HOME_SPEED, null);
            } else {
                idle = true;
            }
        });
    }

    /** The Watcher is talking at [now]: a line printed under TALK_AFTER ago, or the next one due within TALK_BEFORE. */
    private boolean talking(long now) {
        var chat = ctx.chat();
        long sent = chat.sent();
        if (sent != Long.MIN_VALUE && now - sent < BloodData.TALK_AFTER) {
            return true;
        }
        return chat.pending() > 0 && chat.waitTicks() <= BloodData.TALK_BEFORE;
    }

    /** Ticks since the blood door opened (the Odin-style dialogue-skip timer a camping Mage reads), -1 before. */
    public long t() {
        return started ? clock.now() : -1;
    }

    /** All four opening heads are down (the wave the dialogue skip is about). */
    public boolean openingDown() {
        return thrown >= 4 && landed >= 4;
    }

    /**
     * A point in the room's frame for the camper: [x] blocks towards the blood door from the centre (the portal is at
     * -14) and [z] across, on the floor; null before the camp starts.
     */
    public Vec3 doorFrame(double x, double z) {
        return started ? local(-x, z).with(net.minecraft.core.Direction.Axis.Y, room.floorY()) : null;
    }

    /** Any counted mob added and not dead by [now] (a death this tick still counts as alive). */
    private boolean alive(long now) {
        int deadBefore = 0;
        for (long d : deaths) {
            if (d < now) {
                deadBefore++;
            }
        }
        return landed > deadBefore;
    }

    private void throwHead(Head head, int ticks) {
        thrown++;
        Random random = ctx.random();
        double angle = random.nextDouble() * Math.PI * 2;
        double r = 0.3 + random.nextDouble() * 1.7;
        Vec3 to = new Vec3(centre.x + Math.cos(angle) * r, centre.y, centre.z + Math.sin(angle) * r);
        flights.add(new Flight(head, to, ticks));
        SimLog.event("camp_throw", "kind", head.kind.name(), "name", head.name, "flight", ticks, "t", clock.now());
        if (random.nextDouble() < BloodData.SPAWN_P && finalCount < 0) {
            ctx.chat().say(BloodData.line(BloodData.SPAWN.get(random.nextInt(BloodData.SPAWN.size()))), BloodData.GAP_SPAWN);
        }
        if (thrown == 4) {
            // OPEN: what triggers "Let's see how you can handle this." (main median t0 + 300).
            ctx.chat().say(BloodData.line(BloodData.HANDLE), BloodData.GAP_SPAWN);
        }
    }

    private void flyHeads(long now) {
        for (int i = flights.size() - 1; i >= 0; i--) {
            Flight f = flights.get(i);
            f.t++;
            double s = Math.min(1.0, (double) f.t / f.ticks);
            Vec3 at = f.from.add(f.to.subtract(f.from).scale(s));
            f.head.stand.snapTo(at.x, at.y, at.z, f.head.stand.getYRot(), 0f);
            if (f.t >= f.ticks) {
                flights.remove(i);
                ctx.entities().discard(f.head.stand);
                land(f.head, f.to, now);
            }
        }
    }

    /** The head is down: its mob appears on the floor near where it came down. */
    private void land(Head head, Vec3 at, long now) {
        Random random = ctx.random();
        double angle = random.nextDouble() * Math.PI * 2;
        double r = 1 + random.nextDouble() * 3.5;
        // In the air, 5-8 blocks above the floor where the head came down, and it falls (blood-watcher.md "Mob
        // landing": add y ~74-76; real giants were killed on their add tick); it was put on the floor before.
        Vec3 spot = new Vec3(at.x + Math.cos(angle) * r, room.floorY() + 5 + random.nextDouble() * 3,
            at.z + Math.sin(angle) * r);
        CampMob mob = mobFor(head, random);
        ServerPlayer near = nearest(spot, 64);
        float yaw = near == null ? 0f : yawTo(spot, near.position());
        LivingEntity[] made = new LivingEntity[1];
        made[0] = ctx.spawnCampMob(mob, spot, yaw, () -> died(made[0], mob));
        if (made[0] == null) {
            // Counted anyway, so the camp can still end.
            landed++;
            deaths.add(now);
            return;
        }
        landed++;
        landedAt.put(made[0], now);
        campMobs.put(made[0], mob);
        SimLog.event("camp_land", "kind", head.kind.name(), "name", head.name, "hp", mob.health(), "t", now);
    }

    /** One of the 15 died (on this tick). */
    private void died(LivingEntity entity, CampMob mob) {
        long now = clock.now();
        deaths.add(now);
        landedAt.remove(entity);
        campMobs.remove(entity);
        SimLog.event("camp_death", "name", mob.killer(), "t", now);
        if (entity == null) {
            return;
        }
        Vec3 at = entity.position();
        if (modifier == BloodData.Modifier.BOOMER) {
            boom(at, mob);
        }
        if (mob.killer().endsWith("Parasite")) {
            for (int i = 0; i < BloodData.PARASITE_SILVERFISH; i++) {
                silverfish(at, i);
            }
        }
    }

    // ---------------------------------------------------------------- the count, proven and the portal

    private void count(long now) {
        int k = 0;
        for (long d : deaths) {
            if (d < now) {
                k++;
            }
        }
        if (k <= counted) {
            return;
        }
        Random random = ctx.random();
        for (int i = counted; i < k && k < BloodData.MOBS; i++) {
            if (random.nextDouble() < BloodData.REACT_P) {
                ctx.chat().say(BloodData.line(BloodData.REACT.get(random.nextInt(BloodData.REACT.size()))), BloodData.GAP_REACT);
            }
        }
        counted = Math.min(k, BloodData.MOBS);
        double hp = counted >= BloodData.MOBS ? BloodData.LAST_HP : watcherMax - counted * watcherMax / BloodData.MOBS;
        setHealth(watcher, hp);
        ctx.bar().progress(Math.round((BloodData.MOBS - counted) * 100f / BloodData.MOBS) / 100f);
        SimLog.event("camp_count", "count", counted, "hp", hp, "t", now);
        if (counted >= BloodData.MOBS && finalCount < 0) {
            finalCount = now;
            end(now, random);
        }
    }

    private void end(long now, Random random) {
        // Rule P: pending lines are dropped; "proven" waits for the queue only if it frees up within 20 ticks.
        ctx.chat().clear();
        if (ctx.chat().waitTicks() > BloodData.PROVEN_MAX_WAIT) {
            ctx.chat().release();
        }
        ctx.chat().say(BloodData.line(BloodData.PROVEN), BloodData.GAP_SPAWN, () -> {
            proven = clock.now();
            ctx.bar().progress(0f);
            SimLog.event("camp_proven", "after_count", proven - finalCount);
            clock.after(BloodData.REMOVE_AFTER_PROVEN, this::removeWatcher);
        });
        int delay = random.nextDouble() < BloodData.PORTAL_90_SHARE ? 90 : 80;
        clock.after(delay, () -> openPortal(delay));
    }

    private void removeWatcher() {
        for (Entity e : new Entity[] {watcher, eye, watcherTag, eyeTag}) {
            if (e != null) {
                ctx.entities().discard(e);
            }
        }
    }

    /** Nether portal blocks, 3 wide and 4 high, 14 out on the side opposite the blood door, in one update. */
    private void openPortal(int delay) {
        Vec3 mid = local(BloodData.PORTAL_OUT, 0);
        Direction.Axis axis = qx != 0 ? Direction.Axis.X : Direction.Axis.Z;
        var state = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis);
        for (int o = -1; o <= 1; o++) {
            for (int y = 0; y < 4; y++) {
                BlockPos p = BlockPos.containing(mid.x + qx * o, room.floorY() + y, mid.z + qz * o);
                // No neighbour updates: there is no obsidian frame to hold vanilla's portal shape.
                ctx.level().setBlock(p, state, 2 | 16);
            }
        }
        portalAt = clock.now();
        done = true;
        SimLog.event("camp_portal", "after_count", delay);
    }

    // ---------------------------------------------------------------- attacks

    /** A player hit the Watcher or his Eye: now and then he answers (blood-watcher.md §7, ATTACK). */
    public void attacked(ServerPlayer player, Entity entity) {
        long now = clock.now();
        if (!started || finalCount >= 0 || now - lastAttackLine < BloodData.ATTACK_COOLDOWN) {
            return;
        }
        lastAttackLine = now;
        Random random = ctx.random();
        ctx.chat().say(BloodData.line(BloodData.ATTACK.get(random.nextInt(BloodData.ATTACK.size()))), BloodData.GAP_REACT);
    }

    /** The modifier's and the bosses' attacks on players near the live mobs. OPEN: their rates and ranges. */
    private void attacks(long now) {
        for (Map.Entry<Entity, Long> e : new ArrayList<>(landedAt.entrySet())) {
            Entity mob = e.getKey();
            CampMob spec = campMobs.get(mob);
            if (mob.isRemoved() || spec == null) {
                continue;
            }
            long age = now - e.getValue();
            if (modifier == BloodData.Modifier.STORMY && age > 0 && age % BloodData.STORMY_PERIOD == 0) {
                ServerPlayer p = nearest(mob.position(), BloodData.STORMY_RANGE);
                if (p != null) {
                    double dmg = ctx.profile().master ? BloodData.STORMY_M7 : BloodData.STORMY_F7;
                    ctx.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER,
                        SoundSource.HOSTILE, 1f, 1f);
                    // "§1Stormy Flamer§c struck you for 5,000 damage!" (blood camp, measured).
                    ctx.campHit(p, spec.label() + "§c struck you for " + Attacks.number(dmg) + " damage!", dmg, false,
                        spec.killer());
                }
            }
            if (spec.type() == CampMob.Type.BOSS && age > 20 && age % BloodData.BOSS_ATTACK_PERIOD == 0) {
                bossAttack(mob, spec);
            }
        }
    }

    private void bossAttack(Entity mob, CampMob spec) {
        ServerPlayer p = nearest(mob.position(), BloodData.BOSS_ATTACK_RANGE);
        if (p == null) {
            return;
        }
        String boss = spec.killer().substring(spec.killer().indexOf(' ') + 1);
        switch (boss) {
            case "Scarf" -> {
                double dmg = BloodData.SCARF_F7 * ctx.profile().scale("Scarf's Soul Sand Blizzard");
                ctx.level().sendParticles(ParticleTypes.SNOWFLAKE, p.getX(), p.getY() + 1, p.getZ(), 30, 1, 1, 1, 0.05);
                ctx.campHit(p, "§7Scarf's Soul Sand Blizzard hit you for §c" + Attacks.number(dmg) + "§7 damage.", dmg, false, boss);
            }
            case "Bonzo" -> {
                double dmg = BloodData.BONZO_F7 * ctx.profile().scale("Bonzo's Balloon");
                ctx.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.FIREWORK_ROCKET_BLAST,
                    SoundSource.HOSTILE, 1f, 1f);
                // "§7Bonzo's Balloon hit you for §c6,480§7 damage." (measured)
                ctx.campHit(p, "§7Bonzo's Balloon hit you for §c" + Attacks.number(dmg) + "§7 damage.", dmg, false, boss);
            }
            case "Livid" -> {
                double dmg = BloodData.LIVID_MIN + ctx.random().nextDouble() * (BloodData.LIVID_MAX - BloodData.LIVID_MIN);
                ctx.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SPLASH_POTION_BREAK,
                    SoundSource.HOSTILE, 1f, 1f);
                ctx.campHit(p, "§7Livid's Damage Potion hit you for §c" + Attacks.number(dmg) + "§7 true damage.", dmg, true, boss);
            }
            default -> {
            }
        }
    }

    /** A Boomer mob blows up on death (19,440 on F7; M7 never recorded: x10 like the other attacks). */
    private void boom(Vec3 at, CampMob mob) {
        ServerLevel level = ctx.level();
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2f, 1f);
        double dmg = BloodData.BOOMER_F7 * (ctx.profile().master ? 10 : 1);
        for (ServerPlayer p : ctx.players()) {
            if (p.position().distanceTo(at) <= BloodData.BOOMER_RADIUS && !ctx.isGhost(p)) {
                // OPEN: the line's colours ("Boomer X 'sploded on you for N!" is from chat text only).
                ctx.campHit(p, mob.label() + "§c 'sploded on you for " + Attacks.number(dmg) + "!", dmg, false, mob.killer());
            }
        }
    }

    private void silverfish(Vec3 at, int i) {
        boolean m7 = ctx.profile().master;
        CampMob fish = new CampMob(CampMob.Type.SILVERFISH, "§8[§7Lv" + (m7 ? 255 : 79) + "§8] §cParasite", "Parasite",
            m7 ? 1_000_000 : 33_333, null, null, null, null, false, 1.0, m7 ? 255 : 79);
        double a = i * Math.PI * 2 / BloodData.PARASITE_SILVERFISH;
        ctx.spawnCampMob(fish, at.add(Math.cos(a) * 0.5, 0, Math.sin(a) * 0.5), 0f, null);
    }

    /** The CampMob a head releases, with the camp's modifier and a measured HP. */
    private CampMob mobFor(Head head, Random random) {
        boolean m7 = ctx.profile().master;
        double healthy = modifier == BloodData.Modifier.HEALTHY ? BloodData.HEALTHY : 1;
        String label = modifier.colour + modifier.word + " " + head.name;
        String killer = modifier.word + " " + head.name;
        boolean stealth = modifier == BloodData.Modifier.STEALTH;
        // OPEN: what Speedy does to camp mobs (the clear's x1.5 for zombie-like mobs).
        double speed = modifier == BloodData.Modifier.SPEEDY ? 1.5 : 1.0;
        BloodSkins.Skin skin = BloodSkins.get().npc(head.name);
        return switch (head.kind) {
            case GIANT -> new CampMob(CampMob.Type.GIANT, label, killer,
                (m7 ? BloodData.GIANT_M7 : BloodData.GIANT_F7) * step(random) * healthy, null, null, null,
                random.nextInt(124) < 111 ? "minecraft:leather_boots" : "minecraft:diamond_boots", stealth, speed, 0);
            case BOSS -> new CampMob(CampMob.Type.BOSS, label, killer,
                (m7 ? BloodData.BOSS_M7 : BloodData.BOSS_F7) * step(random) * healthy,
                skin == null ? null : skin.value(), skin == null ? null : skin.signature(),
                switch (head.name) {
                    case "Bonzo" -> "minecraft:blaze_rod";
                    case "Scarf" -> "minecraft:nether_star";
                    default -> null;
                }, null, stealth, speed, 0);
            default -> {
                double base = m7 ? BloodData.NORMAL_M7
                    : 1000.0 * (BloodData.NORMAL_MIN_K + random.nextInt(BloodData.NORMAL_MAX_K - BloodData.NORMAL_MIN_K + 1));
                double hp = base * ("Ooze".equals(head.name) ? BloodData.OOZE : 1) * healthy;
                yield new CampMob(CampMob.Type.NORMAL, label, killer, Math.round(hp / 500) * 500.0,
                    skin == null ? null : skin.value(), skin == null ? null : skin.signature(),
                    WEAPONS[random.nextInt(WEAPONS.length)], null, stealth, speed, 0);
            }
        };
    }

    /** What a normal holds: nothing, or a stone/iron/gold/diamond sword or an iron/gold/diamond axe (about evenly, 1,800 normals). */
    private static final String[] WEAPONS = {null, "minecraft:stone_sword", "minecraft:iron_sword", "minecraft:golden_sword",
        "minecraft:diamond_sword", "minecraft:iron_axe", "minecraft:golden_axe", "minecraft:diamond_axe"};

    private static double step(Random random) {
        double r = random.nextDouble();
        double f = 1;
        for (double w : BloodData.STEP_WEIGHTS) {
            if ((r -= w) < 0) {
                return f;
            }
            f *= BloodData.STEP;
        }
        return f / BloodData.STEP;
    }

    // ---------------------------------------------------------------- helpers

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

    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) (Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90.0);
    }

    public void dispose() {
        clock.reset();
        flights.clear();
        landedAt.clear();
        campMobs.clear();
    }

    BossContext ctx() {
        return ctx;
    }
}
