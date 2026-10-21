package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Ice Path (Smalldungeons dungeon/room/ice_path.rs): a silverfish on an ice floor slides, when
 * punched or shot, the way the hit came from until a block stops it. On the goal tiles it blows
 * the gate open and the blessing chest behind it becomes openable; opening that solves the puzzle.
 */
final class IcePathPuzzle extends Puzzle {
    private static final double BLOCKS_PER_TICK = 5.0 / 10.0;
    private static final int[] SPAWN = {8, 67, 9};
    /**
     * The gap under the ice wall (room-local x, z; the wall spans x 14-16 over it, one block high at the fish's height):
     * the fish solves it only by sliding into an open cell of it straight from the tile in front, toward the wall (the
     * user, 2026-10-20: a slide along the row in front of the wall used to count). The sim's maze has no stop in line with
     * the gap from the spawn, so that row may be built wrong: to check against the room.
     */
    private static final int[] GAP = {15, 25};
    private static final int[] GATE_MIN = {14, 68, 25};
    private static final int[] GATE_MAX = {16, 71, 25};
    private static final int[] CHEST = {15, 67, 28};
    private static final float TURN_DEGREES_PER_TICK = 30f;
    /** The TNT hat's armour stand below the fish's feet, so a full-size stand's head sits just above it. */
    private static final double TNT_Y_OFFSET = -1.15;
    private static final double FISH_HALF_WIDTH = 0.2;
    private static final double FISH_HEIGHT = 0.3;
    private static final int BLESSING_LIFETIME_TICKS = Blessings.CHEST_DELAY;
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
    private final List<BlockPos> gateWall = new ArrayList<>();
    private Mob fish;
    private ArmorStand hat;
    private BlockPos chest;
    private boolean chestOpenable;
    private boolean chestOpened;
    // SilverfishSlider
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float targetYaw;
    private boolean sliding;
    private double vx;
    private double vz;
    private boolean solved;

    IcePathPuzzle(DungeonRun run, RoomState room) {
        super(run, room);
    }

    /** setup: the silverfish with its TNT hat, and the reward chest's block (not yet openable). */
    @Override
    void entered() {
        BlockPos spawn = world(SPAWN[0], SPAWN[1], SPAWN[2]);
        x = spawn.getX() + 0.5;
        y = spawn.getY();
        z = spawn.getZ() + 0.5;
        for (int gx = GATE_MIN[0]; gx <= GATE_MAX[0]; gx++) {
            for (int gy = GATE_MIN[1]; gy <= GATE_MAX[1]; gy++) {
                for (int gz = GATE_MIN[2]; gz <= GATE_MAX[2]; gz++) {
                    gateWall.add(world(gx, gy, gz));
                }
            }
        }

        hat = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        hat.snapTo(x, y + TNT_Y_OFFSET, z, 0f, 0f);
        hat.setInvisible(true);
        hat.setNoGravity(true);
        hat.setPermanentlyInvulnerable(true);
        hat.setSilent(true);
        hat.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TNT));
        track(hat);

        fish = EntityTypes.SILVERFISH.create(level, EntitySpawnReason.TRIGGERED);
        if (fish != null) {
            fish.setNoAi(true);
            fish.setPersistenceRequired();
            fish.setNoGravity(true);
            fish.setPermanentlyInvulnerable(true);
            fish.setSilent(true);
            fish.snapTo(x, y, z, 0f, 0f);
            track(fish);
        }

        chest = world(CHEST[0], CHEST[1], CHEST[2]);
        setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, turn(Direction.NORTH)));
    }

    /** SilverfishSlider::tick, plus the blessing's EssenceEntityImpl::tick. */
    @Override
    void tick() {
        tickBlessings();
        if (fish == null || solved) {
            return;
        }
        catchArrows();
        if (sliding) {
            yaw = stepYawTowards(yaw, targetYaw, TURN_DEGREES_PER_TICK);
            double nx = x + vx;
            double nz = z + vz;
            int feetY = (int) Math.floor(y);
            BlockPos target = new BlockPos((int) Math.floor(nx), feetY, (int) Math.floor(nz));
            int into = slideInto(new BlockPos((int) Math.floor(x), feetY, (int) Math.floor(z)), target);
            if (into == 2) {
                x = nx;
                z = nz;
                triggerWin();
                return;
            }
            if (into == 0) {
                x = nx;
                z = nz;
            } else {
                // Hit a wall: stop in the middle of the block it was still in.
                sliding = false;
                vx = 0;
                vz = 0;
                x = Math.floor(x) + 0.5;
                z = Math.floor(z) + 0.5;
            }
        }
        fish.setPos(x, y, z);
        fish.setYRot(yaw);
        fish.setYHeadRot(yaw);
        fish.setYBodyRot(yaw);
        hat.setPos(x, y + TNT_Y_OFFSET, z);
    }

    /** SilverfishSlider::interact: a punch (on the fish or its hat) slides it the way the puncher faces. */
    @Override
    boolean attackEntity(ServerPlayer player, Entity entity) {
        if (!isOurs(entity)) {
            return false;
        }
        if (!solved && !sliding) {
            int dir = cardinalFromYaw(player.getYRot());
            startSliding(dir);
        }
        return true;
    }

    @Override
    boolean useEntity(ServerPlayer player, Entity entity) {
        return isOurs(entity) || isBlessing(entity);
    }

    /** The reward chest: a plain no-op until the gate is blown, then the blessing chest that solves it. */
    @Override
    boolean useBlock(ServerPlayer player, BlockPos pos) {
        if (chest == null || !pos.equals(chest)) {
            return false;
        }
        if (chestOpenable && !chestOpened) {
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
        blessings.clear();
    }

    private boolean isOurs(Entity entity) {
        return entity != null && (entity == fish || entity == hat);
    }

    private boolean isBlessing(Entity entity) {
        for (Blessing blessing : blessings) {
            if (blessing.stand == entity) {
                return true;
            }
        }
        return false;
    }

    /** start_sliding: [dir] is 0 south, 1 west, 2 north, 3 east. */
    private void startSliding(int dir) {
        int dx = dir == 1 ? -1 : dir == 3 ? 1 : 0;
        int dz = dir == 0 ? 1 : dir == 2 ? -1 : 0;
        vx = dx * BLOCKS_PER_TICK;
        vz = dz * BLOCKS_PER_TICK;
        targetYaw = yawTowards(dx, dz);
        sliding = true;
    }

    /**
     * SilverfishSlider::on_projectile_hit: a player's arrow about to hit the fish this tick is
     * taken and slides it along the arrow's flight (taken while sliding too, without redirecting).
     */
    private void catchArrows() {
        AABB near = new AABB(x - 4, y - 4, z - 4, x + 4, y + 4, z + 4);
        for (Arrow arrow : level.getEntities(EntityTypes.ARROW, near, a -> a.getOwner() instanceof ServerPlayer)) {
            Vec3 v = arrow.getDeltaMovement();
            if (v.x * v.x + v.z * v.z < 0.01 || !segmentHitsFish(arrow.getX(), arrow.getY(), arrow.getZ(), v)) {
                continue;
            }
            arrow.discard();
            if (!sliding) {
                int dir = cardinalFromHorizontal(v.x, v.z);
                startSliding(dir);
            }
        }
    }

    /** Does the move from (ax, ay, az) by [v] pass through the fish's box (a slab test)? */
    private boolean segmentHitsFish(double ax, double ay, double az, Vec3 v) {
        double pad = 0.3;
        double[] min = {x - FISH_HALF_WIDTH - pad, y - pad, z - FISH_HALF_WIDTH - pad};
        double[] max = {x + FISH_HALF_WIDTH + pad, y + FISH_HEIGHT + pad, z + FISH_HALF_WIDTH + pad};
        double[] from = {ax, ay, az};
        double[] d = {v.x, v.y, v.z};
        double t0 = 0;
        double t1 = 1;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (from[i] < min[i] || from[i] > max[i]) {
                    return false;
                }
                continue;
            }
            double ta = (min[i] - from[i]) / d[i];
            double tb = (max[i] - from[i]) / d[i];
            t0 = Math.max(t0, Math.min(ta, tb));
            t1 = Math.min(t1, Math.max(ta, tb));
            if (t0 > t1) {
                return false;
            }
        }
        return true;
    }

    /** trigger_win: the fish and its hat go, the gate wall blows away and the chest becomes openable. */
    /**
     * The fish's moves in the room's own frame, for learning the room's one solution from a player (the user,
     * 2026-10-20): each punch or shot (the cell it went from, the way it slid, where the player stood), each stop, the win.
     */

    private void triggerWin() {
        solved = true;
        sliding = false;
        fish.discard();
        hat.discard();
        for (BlockPos pos : gateWall) {
            setBlock(pos, Blocks.AIR.defaultBlockState());
        }
        level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0, 0, 0, 0);
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1f, 1.286f);

        // spawn_reward_chest: DungeonSecret::spawn_into_world for a chest.
        setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, turn(Direction.NORTH)));
        level.sendParticles(ParticleTypes.FLAME, chest.getX() + 0.5, chest.getY() + 1.0, chest.getZ() + 0.5, 1, 0, 0, 0, 0);
        chestOpenable = true;
    }

    // ---------------------------------------------------------------- reward chest

    /** The Chest interact handler for a puzzle_room_index blessing chest. */
    private void openChest(ServerPlayer player) {
        level.blockEvent(chest, level.getBlockState(chest).getBlock(), 1, 1);
        level.playSound(null, chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.975f);
        solvedBy(player, room.data().name());

        for (int i = 0; i < HARP.length; i++) {
            float pitch = HARP[i];
            schedule(i * 5, () -> level.playSound(null, chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5,
                SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, 1f, pitch));
        }
        Pickup.Kind kind = Blessings.puzzleKind(random);
        ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        stand.snapTo(chest.getX() + 0.5, chest.getY() - 1.4, chest.getZ() + 0.5, 0f, 0f);
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

    // ---------------------------------------------------------------- directions

    /** cardinal_from_yaw: 0 south, 1 west, 2 north, 3 east. */
    private static int cardinalFromYaw(float yaw) {
        float normalized = ((yaw % 360f) + 360f) % 360f;
        return ((int) ((normalized + 45f) / 90f)) % 4;
    }

    /** cardinal_from_horizontal, in cardinalFromYaw's numbering. */
    private static int cardinalFromHorizontal(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? 3 : 1;
        }
        return dz > 0 ? 0 : 2;
    }

    /** movement.rs yaw_towards. */
    private static float yawTowards(double dx, double dz) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return yaw < 0 ? yaw + 360f : yaw;
    }

    /** step_yaw_towards: at most [maxDelta] degrees the short way round. */
    private static float stepYawTowards(float current, float target, float maxDelta) {
        float diff = (target - current) % 360f;
        if (diff > 180f) {
            diff -= 360f;
        }
        if (diff < -180f) {
            diff += 360f;
        }
        float step = Math.max(-maxDelta, Math.min(maxDelta, diff));
        float result = (current + step) % 360f;
        return result < 0 ? result + 360f : result;
    }
    private int slideInto(BlockPos cur, BlockPos next) {
        if (cur.equals(next)) {
            return 0;
        }
        int[] c = local(cur);
        int[] n = local(next);
        if (n[2] >= GAP[1]) {
            // Through the gap only straight from the tile in front of it, into an open cell under the ice wall.
            boolean open = !level.getBlockState(next).isSolid();
            return open && Math.abs(n[0] - GAP[0]) <= 1 && n[0] == c[0] && c[2] == GAP[1] - 1 && n[2] == GAP[1] ? 2 : 1;
        }
        return level.getBlockState(next).isSolid() ? 1 : 0;
    }
}
