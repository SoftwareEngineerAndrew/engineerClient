package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Higher or Lower (Smalldungeons dungeon/room/blaze.rs): 10 blazes with shuffled health, killed in
 * health order (lowest first in Higher Blaze, highest first in Lower Blaze). Any hit or Terminator
 * arrow kills one; a kill out of order fails the puzzle. Each correct kill moves the reward chest
 * 5 blocks along its shaft, and it turns into a blessing chest at the end.
 */
final class BlazePuzzle extends Puzzle {
    private static final int HIGHER_BLAZE_BOTTOM = 65;
    private static final int LOWER_BLAZE_BOTTOM = 15;
    private static final int[][] HIGHER_BLAZE_POSITIONS = {
        {15, 77, 13}, {17, 105, 15}, {15, 85, 17}, {13, 113, 15}, {17, 89, 15},
        {13, 81, 15}, {15, 101, 17}, {13, 97, 15}, {15, 109, 13}, {15, 93, 13},
    };
    private static final int[][] LOWER_BLAZE_POSITIONS = {
        {17, 39, 15}, {13, 63, 15}, {15, 51, 17}, {17, 55, 15}, {15, 27, 13},
        {13, 31, 15}, {15, 59, 13}, {15, 35, 17}, {13, 47, 15}, {15, 43, 13},
    };
    private static final int CHEST_STEP_TICKS = 5;
    private static final int CHEST_BLOCKS_PER_KILL = 5;
    private static final int CHEST_X = 15;
    private static final int CHEST_Z = 15;
    private static final int LEGACY_IRON_BARS = 101 << 4;
    private static final int BLESSING_LIFETIME_TICKS = Blessings.CHEST_DELAY;
    private static final float[] HARP = {0.7936508f, 0.8888889f, 1.0f, 1.0952381f, 1.1904762f};
    /** sweep_hit's step and ARROW_COLLISION_EXPAND. */
    private static final double SWEEP_STEP = 0.2;
    private static final double ARROW_COLLISION_EXPAND = 0.3;
    /** So the spot vanilla stops a bounced arrow at, right on that box's face, still counts. */
    private static final double FACE_EPSILON = 0.01;
    /** Three ticks of arrow flight (3 blocks a tick): its course is known before it can reach a blaze to be turned round. */
    private static final double ARROW_SCAN_MARGIN = 9.0;

    private enum Outcome { PROGRESS, SOLVED, WRONG_ORDER, IGNORED }

    /** An arrow's flight as of the last tick, to undo the bounce vanilla gives it off a blaze. */
    private record Flight(Vec3 velocity, float yRot, float xRot) {}

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

    private final BlockState ironBars;
    /** BlazeState.correct_order: the blazes in the order they must die. */
    private final List<Mob> correctOrder = new ArrayList<>();
    private final Set<Mob> alive = new HashSet<>();
    private int nextIndex;
    private boolean failed;
    private final Map<AbstractArrow, Flight> flights = new HashMap<>();
    private final Map<AbstractArrow, Set<Mob>> pierced = new HashMap<>();
    private final List<Blessing> blessings = new ArrayList<>();

    /** ChestShaftState; [chest] is null when this variant has no shaft. */
    private BlockPos chest;
    private int direction;
    private int endY;
    private int currentY;
    private int targetY;
    private int nextStepIn;
    private boolean arrived;
    private boolean chestOpened;

    BlazePuzzle(DungeonRun run, RoomState room) {
        super(run, room);
        this.ironBars = LegacyBlocks.shared().get(LEGACY_IRON_BARS);
    }

    /** setup: health rolled and shuffled over the 10 fixed spots, then the chest shaft. */
    @Override
    void setup() {
        int bottom = room.data().bottom();
        boolean descending = bottom == LOWER_BLAZE_BOTTOM;
        int[][] positions = bottom == HIGHER_BLAZE_BOTTOM ? HIGHER_BLAZE_POSITIONS : LOWER_BLAZE_POSITIONS;

        List<Integer> hps = new ArrayList<>();
        int hp = random.nextInt(1001) + 1000;
        hps.add(hp);
        for (int i = 1; i < 10; i++) {
            hp += random.nextInt(501) + 1;
            hps.add(hp);
        }
        Collections.shuffle(hps, random);

        Map<Mob, Integer> spawned = new LinkedHashMap<>();
        for (int i = 0; i < positions.length; i++) {
            BlockPos pos = world(positions[i][0], positions[i][1], positions[i][2]);
            Mob blaze = spawnBlaze(pos, hps.get(i));
            if (blaze != null) {
                spawned.put(blaze, hps.get(i));
            }
        }
        correctOrder.addAll(spawned.keySet());
        correctOrder.sort((a, b) -> descending
            ? Integer.compare(spawned.get(b), spawned.get(a))
            : Integer.compare(spawned.get(a), spawned.get(b)));
        alive.addAll(correctOrder);

        setupChestShaft(bottom);
    }

    private Mob spawnBlaze(BlockPos pos, int maxHp) {
        Mob blaze = EntityTypes.BLAZE.create(level, EntitySpawnReason.TRIGGERED);
        if (blaze == null) {
            return null;
        }
        blaze.setNoAi(true);
        blaze.setNoGravity(true);
        blaze.setPersistenceRequired();
        blaze.setPermanentlyInvulnerable(true);
        blaze.setSilent(true);
        blaze.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0f, 0f);
        String health = String.format(Locale.ROOT, "%,d", maxHp);
        blaze.setCustomName(EngineItems.text("[Lv15] Blaze " + health + "/" + health + "❤"));
        blaze.setCustomNameVisible(true);
        return track(blaze);
    }

    /** setup_chest_shaft. */
    private void setupChestShaft(int bottom) {
        int startY;
        if (bottom == LOWER_BLAZE_BOTTOM) {
            startY = 69;
            endY = 20;
        } else if (bottom == HIGHER_BLAZE_BOTTOM) {
            startY = 70;
            endY = 119;
        } else {
            return;
        }
        chest = world(CHEST_X, startY, CHEST_Z);
        setBlock(chest, chestState());
        direction = endY < startY ? -1 : 1;
        currentY = startY;
        targetY = startY;
        nextStepIn = CHEST_STEP_TICKS;
    }

    private BlockState chestState() {
        return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, turn(Direction.NORTH));
    }

    @Override
    void tick() {
        tickArrows();
        tickChest();
        tickBlessings();
    }

    /** BlazeMobImpl::interact: any left click kills a blaze. */
    @Override
    boolean attackEntity(ServerPlayer player, Entity entity) {
        if (!(entity instanceof Mob mob) || !correctOrder.contains(mob)) {
            return false;
        }
        if (alive.contains(mob)) {
            handleKill(player, mob, "hit", null, 0);
        }
        return true;
    }

    @Override
    boolean useBlock(ServerPlayer player, BlockPos pos) {
        if (chest == null || !pos.equals(chest)) {
            return false;
        }
        if (arrived && !chestOpened) {
            chestOpened = true;
            openChest(player);
        }
        return true;
    }

    @Override
    boolean isInteractable(BlockPos pos) {
        return chest != null && pos.equals(chest);
    }

    @Override
    void dispose() {
        if (chest != null) {
            setBlock(chest, Blocks.AIR.defaultBlockState());
        }
        flights.clear();
        pierced.clear();
        blessings.clear();
    }

    // ---------------------------------------------------------------- kills

    /**
     * BlazeMobImpl::on_projectile_hit: a player's arrow passing through a blaze kills it and flies
     * on (Projectile::tick's piercing sweep), in the order it reaches them.
     */
    private void tickArrows() {
        flights.keySet().removeIf(Entity::isRemoved);
        pierced.keySet().removeIf(Entity::isRemoved);
        if (alive.isEmpty()) {
            return;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (Mob blaze : alive) {
            minX = Math.min(minX, blaze.getX());
            minY = Math.min(minY, blaze.getY());
            minZ = Math.min(minZ, blaze.getZ());
            maxX = Math.max(maxX, blaze.getX());
            maxY = Math.max(maxY, blaze.getY() + blaze.getBbHeight());
            maxZ = Math.max(maxZ, blaze.getZ());
        }
        AABB area = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(ARROW_SCAN_MARGIN);
        for (Entity entity : level.getEntities((Entity) null, area, e -> e instanceof AbstractArrow)) {
            AbstractArrow arrow = (AbstractArrow) entity;
            if (!(arrow.getOwner() instanceof ServerPlayer shooter)) {
                continue;
            }
            Set<Mob> already = pierced.computeIfAbsent(arrow, a -> new HashSet<>());
            List<Mob> hits = sweep(new Vec3(arrow.xo, arrow.yo, arrow.zo), arrow.position(), already);
            for (Mob blaze : hits) {
                already.add(blaze);
                if (alive.contains(blaze)) {
                    handleKill(shooter, blaze, "arrow", arrow, already.size() - 1);
                }
            }
            Flight flight = flights.get(arrow);
            // Vanilla's hit is the arrow's 0.5-wide box against the blaze's, wider than the sweep: a graze the sweep
            // misses still turns it round (batches 60-61: half the wrong kills were arrows flying back at ~178 degrees
            // off the look, killing a blaze nearer the shooter). Hypixel's arrows fly on, so it goes back on course.
            boolean turned = flight != null && arrow.getDeltaMovement().dot(flight.velocity) < 0;
            if ((!hits.isEmpty() || turned) && flight != null) {
                // Vanilla bounced it off the (invulnerable) blaze; put it back on course.
                Vec3 v = flight.velocity;
                arrow.setDeltaMovement(v.x * 0.99, v.y * 0.99 - 0.05, v.z * 0.99);
                arrow.setYRot(flight.yRot);
                arrow.setXRot(flight.xRot);
                arrow.syncVelocity = true;
            }
            flights.put(arrow, new Flight(arrow.getDeltaMovement(), arrow.getYRot(), arrow.getXRot()));
        }
    }

    /** sweep_hit: the live blazes along [from] -> [to], nearest first. */
    private List<Mob> sweep(Vec3 from, Vec3 to, Set<Mob> exclude) {
        List<Mob> hits = new ArrayList<>();
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / SWEEP_STEP));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Vec3 p = new Vec3(from.x + dx * t, from.y + dy * t, from.z + dz * t);
            for (Mob blaze : alive) {
                if (!exclude.contains(blaze) && !hits.contains(blaze)
                    && blaze.getBoundingBox().inflate(ARROW_COLLISION_EXPAND + FACE_EPSILON).contains(p)) {
                    hits.add(blaze);
                }
            }
        }
        return hits;
    }

    /** handle_kill: the order check, its outcome, then kill_mob. */
    private void handleKill(ServerPlayer player, Mob blaze, String how, AbstractArrow arrow, int pierced) {
        Outcome outcome;
        List<Mob> remaining = List.of();
        if (failed || correctOrder.subList(0, nextIndex).contains(blaze)) {
            outcome = Outcome.IGNORED;
        } else if (correctOrder.get(nextIndex) == blaze) {
            nextIndex++;
            outcome = nextIndex == correctOrder.size() ? Outcome.SOLVED : Outcome.PROGRESS;
        } else {
            failed = true;
            remaining = new ArrayList<>(correctOrder.subList(nextIndex, correctOrder.size()));
            remaining.remove(blaze);
            outcome = Outcome.WRONG_ORDER;
        }

        switch (outcome) {
            case PROGRESS, SOLVED -> {
                run.sound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 8f, 2f);
                run.sound(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.492f);
                advanceChestShaft();
                if (outcome == Outcome.SOLVED) {
                    // No chat on Hypixel: the tab's [✔] only.
                    finish(false);
                }
            }
            case WRONG_ORDER -> {
                // Record why the order broke: by a hit or an
                // arrow, the blazes that arrow went through first, how far the arrow's line was off the shooter's look (a
                // Terminator's side arrows fly ~5 degrees out), and where the wanted blaze was against the one killed.
                Mob wanted = correctOrder.get(nextIndex);
                double off = -1;
                if (arrow != null) {
                    Vec3 v = arrow.getDeltaMovement().normalize();
                    off = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, v.dot(player.getLookAngle())))));
                }
                SimLog.event("puzzle_blaze_wrong", "how", how, "pierced", pierced, "offDeg", Math.round(off * 10) / 10.0,
                    "index", correctOrder.indexOf(blaze), "next", nextIndex, "apart", Math.round(blaze.distanceTo(wanted) * 10) / 10.0,
                    "wantedDist", Math.round(player.distanceTo(wanted) * 10) / 10.0, "killedDist", Math.round(player.distanceTo(blaze) * 10) / 10.0,
                    "who", String.valueOf(player == null ? "" : player.getName().getString()));
                broadcast("§c§lPUZZLE FAIL! " + DungeonRun.nameColour(player) + " §ekilled a Blaze in the wrong order! " + YIKES);
                for (Mob other : remaining) {
                    alive.remove(other);
                    other.discard();
                }
                room.failedBy = player.getName().getString();
                finish(true);
            }
            default -> {
            }
        }
        kill(blaze);
    }

    /** kill_mob for a blaze: the death animation, its death sounds, gone a second later. */
    private void kill(Mob blaze) {
        if (!alive.remove(blaze)) {
            return;
        }
        Vec3 at = blaze.position();
        level.broadcastEntityEvent(blaze, (byte) 3);
        blaze.setCustomNameVisible(false);
        // Dead, so arrows fly through it to the blazes behind while the animation plays.
        blaze.setHealth(0f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.BLAZE_DEATH, SoundSource.HOSTILE, 1f, 1f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.HOSTILE, 1f, 1.492f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.HOSTILE, 0.5f, 1.73f);
        schedule(20, () -> {
            if (!blaze.isRemoved()) {
                level.sendParticles(ParticleTypes.POOF, at.x, at.y + blaze.getBbHeight() / 2, at.z, 12, 0.3, 0.5, 0.3, 0.02);
                blaze.discard();
            }
        });
    }

    // ---------------------------------------------------------------- reward chest

    /** advance_chest_shaft: 5 blocks further along, up to the end. */
    private void advanceChestShaft() {
        if (chest == null) {
            return;
        }
        targetY = direction < 0
            ? Math.max(targetY - CHEST_BLOCKS_PER_KILL, endY)
            : Math.min(targetY + CHEST_BLOCKS_PER_KILL, endY);
    }

    /** tick: one block toward the target every 5 ticks, iron bars left above it on the way down. */
    private void tickChest() {
        if (chest == null || currentY == targetY) {
            return;
        }
        if (nextStepIn > 0) {
            nextStepIn--;
            return;
        }
        setBlock(chest, direction < 0 ? ironBars : Blocks.AIR.defaultBlockState());
        currentY += direction;
        chest = world(CHEST_X, currentY, CHEST_Z);
        setBlock(chest, chestState());
        nextStepIn = CHEST_STEP_TICKS;

        if (currentY == endY && !arrived) {
            arrived = true;
            level.sendParticles(ParticleTypes.FLAME, chest.getX() + 0.5, chest.getY() + 1.0, chest.getZ() + 0.5, 1, 0, 0, 0, 0);
        }
    }

    /** The Chest interact handler for a blessing chest that is not a secret and solves nothing. */
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
            if (blessing.age >= BLESSING_LIFETIME_TICKS) {
                Blessings.grant(run, blessing.kind, 5, blessing.finder, -1);
                for (int i = 0; i < 2; i++) {
                    level.playSound(null, stand.getX(), stand.getY(), stand.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1.5f);
                }
                stand.discard();
                it.remove();
            }
        }
    }
}
