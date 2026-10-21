package net.rustclear.mod.engine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Creeper Beams (Smalldungeons dungeon/room/creeper_beams.rs): a creeper floats in the middle of
 * the room and the player pairs sea lanterns by right-clicking them or hitting them with an arrow.
 * A pair turns to prismarine joined by a guardian beam; it is correct when the beam passes the
 * creeper. Correct pairs lock (the third charges the creeper, the fourth solves it), wrong ones
 * reset when either end is hit again.
 */
final class CreeperBeamsPuzzle extends Puzzle {
    private static final int[] CREEPER_ANCHOR = {15, 77, 15};
    private static final int[][] LANTERN_POSITIONS = {
        {23, 66, 5}, {15, 66, 26}, {15, 67, 15}, {10, 69, 27}, {6, 70, 5}, {21, 70, 27}, {18, 71, 2},
        {2, 71, 13}, {4, 72, 8}, {25, 72, 23}, {24, 74, 6}, {15, 74, 15}, {2, 75, 16}, {3, 76, 18},
        {25, 76, 23}, {5, 76, 24}, {24, 77, 7}, {26, 78, 12}, {27, 78, 14}, {18, 82, 8}, {9, 83, 17},
        {15, 84, 13},
    };
    private static final int[] CHEST_REVEAL_POS = {15, 69, 15};
    /** The centre pillar (room data: stairs, stone bricks and the lantern cap round a hollow), blown up on solve. */
    private static final int[] PILLAR_MIN = {13, 69, 14};
    private static final int[] PILLAR_MAX = {17, 74, 16};
    private static final double BEAM_HIT_TOLERANCE = 1.5;
    private static final int CORRECT_PAIRS_TO_CHARGE = 3;
    private static final int CORRECT_PAIRS_TO_SOLVE = 4;
    private static final int CLOUD_INTERVAL_TICKS = 10;
    private static final long HIT_DEBOUNCE_TICKS = 5;
    /** How finely an arrow's next step is walked, and how far past it the walk looks. */
    private static final double ARROW_STEP = 0.1;
    private static final double ARROW_LOOKAHEAD = 0.3;
    /** Projectile hit margin plus half an arrow: how close an arrow may pass a prop before vanilla hits it. */
    private static final double PROP_MARGIN = 0.6;
    private static final int BEAM_PARTICLE_INTERVAL_TICKS = 5;
    /**
     * OdinClient's solution pairs (odtheking/Odin, assets/odin/puzzles/creeper-beams-solutions.json, used by its
     * BeamsSolver; the user, 2026-10-20): room-local {x1, y1, z1, x2, y2, z2}, in its order. A pair counts while both
     * ends are sea lanterns here. Five fit this room's lanterns, all disjoint and all through the creeper by the beam
     * test (15,74,15-15,84,13; 24,77,7-5,76,24; 27,78,14-2,75,16; 26,78,12-3,76,18; 18,82,8-10,69,27).
     */
    private static final int[][] ODIN_PAIRS = {
        {15, 74, 15, 15, 84, 13}, {15, 78, 3, 15, 76, 27}, {22, 80, 22, 4, 72, 8}, {24, 77, 7, 5, 76, 24},
        {27, 78, 14, 2, 75, 16}, {26, 78, 12, 3, 76, 18}, {25, 79, 21, 4, 75, 9}, {25, 76, 23, 6, 74, 5},
        {18, 82, 8, 10, 69, 27}, {18, 81, 21, 9, 69, 3}, {9, 81, 20, 26, 70, 7}, {18, 81, 21, 9, 69, 3},
        {3, 76, 18, 26, 78, 12},
    };
    /** Odin's pair colours (gold, green, light purple, dark aqua, yellow, dark red, white, dark purple) as glass. */
    private static final int[] PAIR_RGB = {0xFFAA00, 0x55FF55, 0xFF55FF, 0x00AAAA, 0xFFFF55, 0xAA0000, 0xFFFFFF, 0xAA00AA};
    private static final net.minecraft.world.item.DyeColor[] PAIR_GLASS = {
        net.minecraft.world.item.DyeColor.ORANGE, net.minecraft.world.item.DyeColor.LIME, net.minecraft.world.item.DyeColor.MAGENTA,
        net.minecraft.world.item.DyeColor.CYAN, net.minecraft.world.item.DyeColor.YELLOW, net.minecraft.world.item.DyeColor.RED,
        net.minecraft.world.item.DyeColor.WHITE, net.minecraft.world.item.DyeColor.PURPLE,
    };

    private enum Status { AVAILABLE, SELECTED, PAIRED }

    /** LanternStatus. */
    private static final class Lantern {
        Status status = Status.AVAILABLE;
        BlockPos partner;
        boolean correct;
        Mob guardian;
        Mob squid;
        /** The guardian beam couldn't be set up; a particle line stands in for it. */
        boolean particleBeam;
    }

    private final Map<BlockPos, Lantern> lanterns = new LinkedHashMap<>();
    private final Map<BlockPos, Long> lastHitTick = new HashMap<>();
    private Vec3 creeperPos;
    private Mob creeper;
    private BlockPos selected;
    private BlockPos chest;
    private boolean chestOpened;
    private static final float[] HARP = {0.7936508f, 0.8888889f, 1.0f, 1.0952381f, 1.1904762f};

    /** The chest's floating blessing (EssenceEntityImpl with a blessing). */
    private static final class Blessing {
        final ArmorStand stand;
        final Pickup.Kind kind;
        final ServerPlayer finder;
        int age;

        Blessing(ArmorStand stand, Pickup.Kind kind, ServerPlayer finder) {
            this.stand = stand;
            this.kind = kind;
            this.finder = finder;
        }
    }

    private final List<Blessing> blessings = new ArrayList<>();
    private AABB roomBox;
    private int correctPairs;
    private boolean resolved;
    private int ticks;
    /** Odin's pairs that fit this room (both ends lanterns, through the creeper), with their colour index. */
    private record Solution(BlockPos a, BlockPos b, int colour) {}
    private final List<Solution> solutions = new ArrayList<>();
    /** The glowing glass over each end of a solution pair still unpaired (Odin's boxes), by lantern. */
    private final Map<BlockPos, net.minecraft.world.entity.Display.BlockDisplay> overlays = new HashMap<>();

    CreeperBeamsPuzzle(DungeonRun run, RoomState room) {
        super(run, room);
    }

    /** setup: the floating creeper and the 22 lanterns. */
    @Override
    void setup() {
        BlockPos anchor = world(CREEPER_ANCHOR[0], CREEPER_ANCHOR[1], CREEPER_ANCHOR[2]);
        creeperPos = new Vec3(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
        for (int[] p : LANTERN_POSITIONS) {
            lanterns.put(world(p[0], p[1], p[2]), new Lantern());
        }
        BlockPos a = world(0, 0, 0);
        BlockPos b = world(30, 0, 30);
        roomBox = new AABB(Math.min(a.getX(), b.getX()), 0, Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()) + 1, 320, Math.max(a.getZ(), b.getZ()) + 1);

        Creeper spawned = EntityTypes.CREEPER.create(level, EntitySpawnReason.TRIGGERED);
        if (spawned != null) {
            spawned.snapTo(creeperPos.x, creeperPos.y, creeperPos.z, 0f, 0f);
            prop(spawned);
            creeper = track(spawned);
        }
        java.util.Set<BlockPos> taken = new java.util.HashSet<>();
        for (int i = 0; i < ODIN_PAIRS.length; i++) {
            int[] s = ODIN_PAIRS[i];
            BlockPos pa = world(s[0], s[1], s[2]);
            BlockPos pb = world(s[3], s[4], s[5]);
            // Odin's list has two pairs twice: each lantern in one pair only.
            if (lanterns.containsKey(pa) && lanterns.containsKey(pb) && !taken.contains(pa) && !taken.contains(pb)
                && beamIntersectsCreeper(pa, pb)) {
                solutions.add(new Solution(pa, pb, i % PAIR_RGB.length));
                taken.add(pa);
                taken.add(pb);
            }
        }
        updateOverlays();
    }

    /**
     * Odin's solver boxes (the user, 2026-10-20: colour overlays on the blocks of matching pairs, as Odin does): each end
     * of a solution pair not yet paired under glowing stained glass of the pair's colour, gone once it pairs.
     */
    private void updateOverlays() {
        java.util.Set<BlockPos> want = new java.util.HashSet<>();
        if (!resolved) {
            for (Solution s : solutions) {
                if (lanterns.get(s.a()).status != Status.PAIRED && lanterns.get(s.b()).status != Status.PAIRED) {
                    for (BlockPos pos : new BlockPos[] {s.a(), s.b()}) {
                        want.add(pos);
                        if (!overlays.containsKey(pos)) {
                            overlays.put(pos, overlay(pos, s.colour()));
                        }
                    }
                }
            }
        }
        for (var it = overlays.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!want.contains(e.getKey())) {
                e.getValue().discard();
                it.remove();
            }
        }
    }

    /** A glowing, slightly larger glass box over [pos] in colour [colour] (no hitbox: clicks and arrows pass it). */
    private net.minecraft.world.entity.Display.BlockDisplay overlay(BlockPos pos, int colour) {
        var display = new net.minecraft.world.entity.Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
        display.setBlockState(Blocks.STAINED_GLASS.pick(PAIR_GLASS[colour]).defaultBlockState());
        display.setTransformation(new com.mojang.math.Transformation(new org.joml.Vector3f(-0.02f, -0.02f, -0.02f), null,
            new org.joml.Vector3f(1.04f, 1.04f, 1.04f), null));
        display.setBrightnessOverride(new net.minecraft.util.Brightness(15, 15));
        display.setGlowColorOverride(PAIR_RGB[colour]);
        display.setGlowingTag(true);
        display.snapTo(pos.getX(), pos.getY(), pos.getZ(), 0f, 0f);
        display.setNoGravity(true);
        display.setPermanentlyInvulnerable(true);
        return track(display);
    }

    /** CreeperImpl::tick (the cloud puff), the arrow hits (MobProjectileImpl::on_block_hit) and the beams' stand-in. */
    @Override
    void tick() {
        ticks++;
        if (creeper != null && ticks % CLOUD_INTERVAL_TICKS == 0) {
            level.sendParticles(ParticleTypes.CLOUD, creeper.getX(), creeper.getY() - 1.0, creeper.getZ(), 3, 0.3, 0.05, 0.3, 0.02);
        }
        tickArrows();
        tickBlessings();
        if (ticks % BEAM_PARTICLE_INTERVAL_TICKS == 0) {
            drawParticleBeams();
        }
    }

    @Override
    boolean useBlock(ServerPlayer player, BlockPos pos) {
        if (lanterns.containsKey(pos)) {
            interactLantern(player, pos);
            return true;
        }
        if (chest != null && pos.equals(chest)) {
            if (!chestOpened) {
                chestOpened = true;
                openChest(player);
            }
            return true;
        }
        return false;
    }

    @Override
    boolean useEntity(ServerPlayer player, Entity entity) {
        for (Blessing blessing : blessings) {
            if (blessing.stand == entity) {
                return true;
            }
        }
        return false;
    }

    @Override
    boolean isInteractable(BlockPos pos) {
        return lanterns.containsKey(pos) || (chest != null && pos.equals(chest));
    }

    @Override
    void dispose() {
        for (var display : overlays.values()) {
            display.discard();
        }
        overlays.clear();
        if (chest != null) {
            setBlock(chest, Blocks.AIR.defaultBlockState());
        }
        blessings.clear();
    }

    // ---------------------------------------------------------------- lanterns

    /** interact_lantern. */
    private void interactLantern(ServerPlayer player, BlockPos pos) {
        if (resolved) {
            return;
        }
        Lantern lantern = lanterns.get(pos);
        if (lantern == null) {
            return;
        }
        long now = level.getGameTime();
        Long last = lastHitTick.get(pos);
        if (last != null && now - last < HIT_DEBOUNCE_TICKS) {
            return;
        }
        lastHitTick.put(pos, now);

        switch (lantern.status) {
            case SELECTED -> {
            }
            case PAIRED -> {
                if (!lantern.correct) {
                    resetPair(pos, lantern);
                }
            }
            case AVAILABLE -> {
                playHitSound(player);
                if (selected == null) {
                    lantern.status = Status.SELECTED;
                    selected = pos;
                } else {
                    BlockPos first = selected;
                    selected = null;
                    formPair(player, first, pos);
                }
            }
        }
    }

    /** play_hit_sound: the XP pickup sound, lowered, at the player. */
    private void playHitSound(ServerPlayer player) {
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 0.7f);
    }

    /** form_pair. */
    private void formPair(ServerPlayer player, BlockPos a, BlockPos b) {
        playHitSound(player);
        lastHitTick.put(a, level.getGameTime());

        boolean correct = beamIntersectsCreeper(a, b);
        setBlock(a, Blocks.PRISMARINE.defaultBlockState());
        setBlock(b, Blocks.PRISMARINE.defaultBlockState());

        Lantern la = lanterns.get(a);
        Lantern lb = lanterns.get(b);
        spawnGuardianPair(la, a, b);
        lb.guardian = la.guardian;
        lb.squid = la.squid;
        lb.particleBeam = false;
        for (Lantern l : new Lantern[] {la, lb}) {
            l.status = Status.PAIRED;
            l.correct = correct;
        }
        la.partner = b;
        lb.partner = a;
        updateOverlays();

        if (!correct) {
            return;
        }
        correctPairs++;
        if (correctPairs == CORRECT_PAIRS_TO_CHARGE && creeper != null) {
            setSynced(creeper, Creeper.class, "DATA_IS_POWERED", Boolean.TRUE);
        }
        if (correctPairs >= CORRECT_PAIRS_TO_SOLVE) {
            completePuzzle(player);
        }
    }

    /**
     * spawn_guardian_pair: an invisible guardian at [a] aiming its laser at an invisible squid at
     * [b]. Stored on [owner] (the [a] end); a particle line stands in if the laser can't be set.
     */
    private void spawnGuardianPair(Lantern owner, BlockPos a, BlockPos b) {
        Squid squid = EntityTypes.SQUID.create(level, EntitySpawnReason.TRIGGERED);
        Guardian guardian = EntityTypes.GUARDIAN.create(level, EntitySpawnReason.TRIGGERED);
        owner.particleBeam = true;
        if (squid == null || guardian == null) {
            return;
        }
        squid.snapTo(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 0f, 0f);
        guardian.snapTo(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5, 0f, 0f);
        prop(squid);
        prop(guardian);
        squid.setInvisible(true);
        guardian.setInvisible(true);
        owner.particleBeam = !setSynced(guardian, Guardian.class, "DATA_ID_ATTACK_TARGET", squid.getId());
        owner.squid = track(squid);
        owner.guardian = track(guardian);
    }

    /** The paired state of an incorrect pair is undone (reset_pair). */
    private void resetPair(BlockPos a, Lantern la) {
        BlockPos b = la.partner;
        Lantern lb = lanterns.get(b);
        if (la.guardian != null) {
            la.guardian.discard();
        }
        if (la.squid != null) {
            la.squid.discard();
        }
        setBlock(a, Blocks.SEA_LANTERN.defaultBlockState());
        setBlock(b, Blocks.SEA_LANTERN.defaultBlockState());
        for (Lantern l : new Lantern[] {la, lb}) {
            l.status = Status.AVAILABLE;
            l.partner = null;
            l.correct = false;
            l.guardian = null;
            l.squid = null;
            l.particleBeam = false;
        }
        updateOverlays();
    }

    /** beam_intersects_creeper: the creeper is within tolerance of the segment between the two block centres. */
    private boolean beamIntersectsCreeper(BlockPos a, BlockPos b) {
        Vec3 pa = center(a);
        Vec3 pb = center(b);
        double abx = pb.x - pa.x;
        double aby = pb.y - pa.y;
        double abz = pb.z - pa.z;
        double lenSq = abx * abx + aby * aby + abz * abz;
        if (lenSq < 1e-9) {
            return distance(pa, creeperPos) < BEAM_HIT_TOLERANCE;
        }
        double t = ((creeperPos.x - pa.x) * abx + (creeperPos.y - pa.y) * aby + (creeperPos.z - pa.z) * abz) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        Vec3 closest = new Vec3(pa.x + abx * t, pa.y + aby * t, pa.z + abz * t);
        return distance(closest, creeperPos) < BEAM_HIT_TOLERANCE;
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static double distance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * complete_puzzle: the creeper goes off, blowing up the pillar it floats over (the user, 2026-10-20: the chest is in
     * its hollow centre at the bottom), the chest appears shut and the room is solved.
     */
    private void completePuzzle(ServerPlayer player) {
        resolved = true;
        if (creeper != null) {
            creeper.discard();
            creeper = null;
        }
        for (int y = PILLAR_MIN[1]; y <= PILLAR_MAX[1]; y++) {
            for (int x = PILLAR_MIN[0]; x <= PILLAR_MAX[0]; x++) {
                for (int z = PILLAR_MIN[2]; z <= PILLAR_MAX[2]; z++) {
                    BlockPos pos = world(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        setBlock(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        chest = world(CHEST_REVEAL_POS[0], CHEST_REVEAL_POS[1], CHEST_REVEAL_POS[2]);
        setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, turn(Direction.NORTH)));
        Vec3 blast = Vec3.atCenterOf(world(15, 72, 15));
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, blast.x, blast.y, blast.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.EXPLOSION, creeperPos.x, creeperPos.y, creeperPos.z, 1, 0, 0, 0, 0);
        level.playSound(null, creeperPos.x, creeperPos.y, creeperPos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1f, 1f);
        updateOverlays();
        solvedBy(player, "Creeper Beams");
    }

    /**
     * The Chest interact handler for the revealed blessing chest (the user, 2026-10-20: it stays shut until a player opens
     * it for the blessing; the room is solved already).
     */
    private void openChest(ServerPlayer player) {
        level.blockEvent(chest, level.getBlockState(chest).getBlock(), 1, 1);
        level.playSound(null, chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.975f);
        BlockPos at = chest;
        for (int i = 0; i < HARP.length; i++) {
            float pitch = HARP[i];
            schedule(i * 5, () -> level.playSound(null, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5,
                SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, 1f, pitch));
        }
        Pickup.Kind kind = Blessings.puzzleKind(random);
        ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        stand.snapTo(at.getX() + 0.5, at.getY() - 1.4, at.getZ() + 0.5, 0f, 0f);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setPermanentlyInvulnerable(true);
        stand.setSilent(true);
        stand.setCustomName(EngineItems.text(kind.name));
        stand.setCustomNameVisible(true);
        stand.setItemSlot(EquipmentSlot.HEAD, EngineItems.head(kind.texture, null));
        blessings.add(new Blessing(track(stand), kind, player));
    }

    /** EssenceEntityImpl::tick: rises, spins, and grants the blessing 23 ticks after the lid (Blessings). */
    private void tickBlessings() {
        for (Iterator<Blessing> it = blessings.iterator(); it.hasNext(); ) {
            Blessing blessing = it.next();
            ArmorStand stand = blessing.stand;
            blessing.age++;
            if (blessing.age < 20) {
                stand.setPos(stand.getX(), stand.getY() + 0.04, stand.getZ());
            }
            stand.setYRot(stand.getYRot() + 15f);
            if (blessing.age % 5 == 0) {
                level.sendParticles(ParticleTypes.CLOUD, stand.getX(), stand.getY() + 1.5, stand.getZ(), 5, 0, 0, 0, 0.06);
            }
            if (blessing.age >= Blessings.CHEST_DELAY) {
                Blessings.grant(run, blessing.kind, 5, blessing.finder, -1);
                for (int i = 0; i < 2; i++) {
                    level.playSound(null, stand.getX(), stand.getY(), stand.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1.5f);
                }
                stand.discard();
                it.remove();
            }
        }
    }

    // ---------------------------------------------------------------- arrows

    /**
     * MobProjectileImpl::on_block_hit for Terminator arrows: walks each player arrow's next step
     * (the run ticks after the entities do) and treats reaching a lantern as a hit on it. Arrows
     * about to clip one of the puzzle's props are moved past it, since on the server they fly through.
     */
    private void tickArrows() {
        if (roomBox == null) {
            return;
        }
        List<Entity> props = new ArrayList<>();
        if (creeper != null) {
            props.add(creeper);
        }
        for (Lantern l : lanterns.values()) {
            if (l.guardian != null) {
                props.add(l.guardian);
                props.add(l.squid);
            }
        }
        for (Entity entity : level.getEntities((Entity) null, roomBox, e -> e instanceof AbstractArrow)) {
            AbstractArrow arrow = (AbstractArrow) entity;
            if (!(arrow.getOwner() instanceof ServerPlayer shooter)) {
                continue;
            }
            Vec3 velocity = arrow.getDeltaMovement();
            double speed = Math.sqrt(velocity.x * velocity.x + velocity.y * velocity.y + velocity.z * velocity.z);
            if (speed < 1e-4) {
                continue;
            }
            Vec3 start = arrow.position();
            Vec3 dir = new Vec3(velocity.x / speed, velocity.y / speed, velocity.z / speed);
            double end = speed + ARROW_LOOKAHEAD;
            double shift = -1;
            for (double d = 0; d <= end && d < 64; d += ARROW_STEP) {
                Vec3 p = new Vec3(start.x + dir.x * d, start.y + dir.y * d, start.z + dir.z * d);
                BlockPos block = BlockPos.containing(p);
                if (lanterns.containsKey(block)) {
                    interactLantern(shooter, block);
                    arrow.discard();
                    shift = -1;
                    break;
                }
                if (level.getBlockState(block).isSolid()) {
                    break;
                }
                AABB at = new AABB(p.x - 0.25, p.y - 0.25, p.z - 0.25, p.x + 0.25, p.y + 0.25, p.z + 0.25);
                for (Entity prop : props) {
                    if (prop.getBoundingBox().inflate(PROP_MARGIN - 0.25).intersects(at)) {
                        shift = d + ARROW_STEP;
                        end = Math.max(end, shift + speed + ARROW_LOOKAHEAD);
                        break;
                    }
                }
            }
            if (shift > 0 && !arrow.isRemoved()) {
                arrow.setPos(start.x + dir.x * shift, start.y + dir.y * shift, start.z + dir.z * shift);
            }
        }
    }

    // ---------------------------------------------------------------- props

    /** The motionless, untouchable props: no AI, no gravity, no sounds, kept loaded. */
    private static void prop(Mob mob) {
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.setPermanentlyInvulnerable(true);
        mob.setSilent(true);
        mob.setPersistenceRequired();
    }

    /** Where the guardian laser can't be shown, a line of particles between the pair. */
    private void drawParticleBeams() {
        for (Map.Entry<BlockPos, Lantern> e : lanterns.entrySet()) {
            Lantern l = e.getValue();
            if (l.status != Status.PAIRED || !l.particleBeam) {
                continue;
            }
            Vec3 a = center(e.getKey());
            Vec3 b = center(l.partner);
            int steps = Math.max(1, (int) (distance(a, b) * 2));
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                level.sendParticles(ParticleTypes.END_ROD, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, 1, 0, 0, 0, 0);
            }
        }
    }

    /**
     * Sets one of an entity's synced values that has no public setter (the guardian's laser target,
     * the creeper's charge), by its field name in [owner]. False when that isn't possible.
     */
    private static boolean setSynced(Entity entity, Class<?> owner, String field, Object value) {
        try {
            Field key = owner.getDeclaredField(field);
            key.setAccessible(true);
            Object data = entity.getEntityData();
            for (Method method : data.getClass().getMethods()) {
                if (method.getName().equals("set") && method.getParameterCount() == 2) {
                    method.invoke(data, key.get(null), value);
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
        return false;
    }
}
