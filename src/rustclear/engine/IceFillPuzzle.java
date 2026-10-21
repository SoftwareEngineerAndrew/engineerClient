package net.rustclear.mod.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ice Fill (Smalldungeons dungeon/room/ice_fill.rs): walk every free ice tile of each of three
 * floors exactly once, no diagonals, no jumping. A wrong step breaks the floor for two seconds.
 * Clearing all three opens the gate; opening both blessing chests behind it solves the puzzle.
 */
final class IceFillPuzzle extends Puzzle {
    private static final String FILE = "/assets/rustclear/engine/puzzles/icefill.json";
    private static final int FAIL_BREAK_TICKS = 40;
    private static final double JUMP_Y_TOLERANCE = 0.1;
    private static final int[] FLOOR_Y = {69, 70, 71};
    private static final int[][][] FOOTPRINTS = {
        {{14, 7}, {14, 8}, {14, 9}, {15, 7}, {15, 8}, {15, 9}, {15, 10}, {16, 7}, {16, 8}, {16, 9}},
        {{13, 12}, {13, 13}, {13, 14}, {13, 15}, {13, 16}, {14, 12}, {14, 13}, {14, 14}, {14, 15}, {14, 16},
            {15, 12}, {15, 13}, {15, 14}, {15, 15}, {15, 16}, {15, 17}, {16, 12}, {16, 13}, {16, 14}, {16, 15},
            {16, 16}, {17, 12}, {17, 13}, {17, 14}, {17, 15}, {17, 16}},
        {{12, 19}, {12, 20}, {12, 21}, {12, 22}, {12, 23}, {12, 24}, {12, 25}, {13, 19}, {13, 20}, {13, 21},
            {13, 22}, {13, 23}, {13, 24}, {13, 25}, {14, 19}, {14, 20}, {14, 21}, {14, 22}, {14, 23}, {14, 24},
            {14, 25}, {15, 19}, {15, 20}, {15, 21}, {15, 22}, {15, 23}, {15, 24}, {15, 25}, {15, 26}, {16, 19},
            {16, 20}, {16, 21}, {16, 22}, {16, 23}, {16, 24}, {16, 25}, {17, 19}, {17, 20}, {17, 21}, {17, 22},
            {17, 23}, {17, 24}, {17, 25}, {18, 19}, {18, 20}, {18, 21}, {18, 22}, {18, 23}, {18, 24}, {18, 25}},
    };
    private static final int[] GATE_MIN = {13, 74, 27};
    private static final int[] GATE_MAX = {17, 79, 27};
    private static final int[][] CHESTS = {{16, 75, 29}, {14, 75, 29}};
    private static final int BLESSING_LIFETIME_TICKS = Blessings.CHEST_DELAY;
    private static final float[] HARP = {0.7936508f, 0.8888889f, 1.0f, 1.0952381f, 1.1904762f};
    /** Per floor, its obstacle patterns, each a list of (x, z). */
    private static List<List<int[][]>> patterns;

    private enum Layer { RESPAWNING, ACTIVE, DONE }

    /** A chest's floating blessing (EssenceEntityImpl with a blessing). */
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

    private final int[] obstacleChoice = new int[3];
    private final List<BlockPos> chests = new ArrayList<>();
    private final Set<BlockPos> openedChests = new HashSet<>();
    private final List<Blessing> blessings = new ArrayList<>();
    /** Obstacle blocks put in, cleared again with the dungeon. */
    private final Set<BlockPos> obstacles = new HashSet<>();
    private int currentFloor;
    private Layer layer = Layer.RESPAWNING;
    private long resumeAt;
    private long ticks;
    // LayerState::Active
    private final Set<BlockPos> footprint = new HashSet<>();
    private final Set<BlockPos> remaining = new HashSet<>();
    private final Map<UUID, BlockPos> tracked = new HashMap<>();
    private boolean chestsOpenable;

    IceFillPuzzle(DungeonRun run, RoomState room) {
        super(run, room);
    }

    /** setup: one pattern per floor, all three placed at once, and floor 1 active. */
    @Override
    void setup() {
        List<List<int[][]>> all = patterns();
        for (int floor = 0; floor < 3; floor++) {
            obstacleChoice[floor] = random.nextInt(all.get(floor).size());
            placeObstacles(floor);
        }
        for (int[] c : CHESTS) {
            chests.add(world(c[0], c[1], c[2]));
        }
        activateFloor(false);
    }

    /** tick: the post-fail restore, then every player's step check. */
    @Override
    void tick() {
        ticks++;
        tickBlessings();
        if (layer == Layer.DONE) {
            return;
        }
        if (layer == Layer.RESPAWNING) {
            if (ticks >= resumeAt) {
                activateFloor(true);
            }
            return;
        }
        for (ServerPlayer player : List.copyOf(level.players())) {
            if (layer != Layer.ACTIVE) {
                return;
            }
            tickPlayer(player);
        }
    }

    /** The two reward chests: nothing until the gate opens, then each is a blessing chest. */
    @Override
    boolean useBlock(ServerPlayer player, BlockPos pos) {
        if (!chests.contains(pos)) {
            return false;
        }
        if (chestsOpenable && openedChests.add(pos)) {
            openChest(player, pos);
        }
        return true;
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
        return chests.contains(pos);
    }

    @Override
    void dispose() {
        for (BlockPos pos : obstacles) {
            setBlock(pos, Blocks.AIR.defaultBlockState());
        }
        obstacles.clear();
        blessings.clear();
    }

    // ---------------------------------------------------------------- floors

    /** activate_floor: [restoreIce] puts the broken floor's ice back first. */
    private void activateFloor(boolean restoreIce) {
        int y = FLOOR_Y[currentFloor];
        int[][] blocked = patterns().get(currentFloor).get(obstacleChoice[currentFloor]);
        footprint.clear();
        remaining.clear();
        tracked.clear();
        for (int[] tile : FOOTPRINTS[currentFloor]) {
            BlockPos pos = world(tile[0], y, tile[1]);
            if (restoreIce) {
                setBlock(pos, Blocks.ICE.defaultBlockState());
            }
            footprint.add(pos);
            if (!contains(blocked, tile[0], tile[1])) {
                remaining.add(pos);
            }
        }
        placeObstacles(currentFloor);
        layer = Layer.ACTIVE;
    }

    /** place_obstacles: polished andesite one above each blocked tile. */
    private void placeObstacles(int floor) {
        int y = FLOOR_Y[floor];
        for (int[] tile : patterns().get(floor).get(obstacleChoice[floor])) {
            BlockPos pos = world(tile[0], y + 1, tile[1]);
            setBlock(pos, Blocks.POLISHED_ANDESITE.defaultBlockState());
            obstacles.add(pos);
        }
    }

    /** break_floor: every tile and obstacle of the floor to air, glass breaking on the ice. */
    private void breakFloor() {
        int y = FLOOR_Y[currentFloor];
        for (int[] tile : FOOTPRINTS[currentFloor]) {
            BlockPos pos = world(tile[0], y, tile[1]);
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)) {
                sound(pos, SoundEvents.GLASS_BREAK, 1f, 0.8f);
            }
            setBlock(pos, Blocks.AIR.defaultBlockState());
        }
        for (int[] tile : patterns().get(currentFloor).get(obstacleChoice[currentFloor])) {
            setBlock(world(tile[0], y + 1, tile[1]), Blocks.AIR.defaultBlockState());
        }
    }

    /** tick_player: one player's step on the active floor. */
    private void tickPlayer(ServerPlayer player) {
        double playerY = player.getY();
        BlockPos feet = new BlockPos((int) Math.floor(player.getX()), (int) Math.floor(playerY - 0.1), (int) Math.floor(player.getZ()));
        UUID id = player.getUUID();
        BlockPos last = tracked.get(id);
        List<BlockPos> walked = new ArrayList<>();
        String fail = null;

        if (last != null) {
            if (playerY > FLOOR_Y[currentFloor] + 1.0 + JUMP_Y_TOLERANCE) {
                fail = "§cDon't jump!";
            } else if (!footprint.contains(feet) || last.equals(feet)) {
                return;
            } else {
                int dx = feet.getX() - last.getX();
                int dz = feet.getZ() - last.getZ();
                if (dx != 0 && dz != 0) {
                    fail = "§cDon't move diagonally! Bad!";
                } else {
                    // A fast straight move may cross several tiles in one tick: each one counts.
                    int steps = Math.max(Math.abs(dx), Math.abs(dz));
                    for (int i = 1; i <= steps; i++) {
                        BlockPos pos = new BlockPos(last.getX() + Integer.signum(dx) * i, last.getY(), last.getZ() + Integer.signum(dz) * i);
                        if (!footprint.contains(pos) || !remaining.remove(pos)) {
                            fail = "§cOops! You stepped on the wrong block!";
                            break;
                        }
                        walked.add(pos);
                    }
                    if (fail == null) {
                        tracked.put(id, feet);
                    }
                }
            }
        } else if (!footprint.contains(feet)) {
            return;
        } else if (!remaining.remove(feet)) {
            fail = "§cOops! You stepped on the wrong block!";
        } else {
            tracked.put(id, feet);
            walked.add(feet);
        }

        if (fail != null) {
            player.sendSystemMessage(EngineItems.text(fail));
            onFloorFailed();
            return;
        }
        for (BlockPos pos : walked) {
            if (level.getBlockState(pos).is(Blocks.ICE)) {
                setBlock(pos, Blocks.PACKED_ICE.defaultBlockState());
                sound(pos, SoundEvents.WOOL_BREAK, 1f, 0.8f);
            }
        }
        if (remaining.isEmpty()) {
            onFloorCleared();
        }
    }

    /** on_floor_cleared: a rising harp chime at every player, then the next floor or the gate. */
    private void onFloorCleared() {
        float pitchBase = currentFloor * 0.2f;
        float[] extras = {0f, 0.1f, 0.2f};
        for (int i = 0; i < extras.length; i++) {
            float pitch = 1.2f + pitchBase + extras[i];
            for (ServerPlayer player : level.players()) {
                double px = player.getX();
                double py = player.getY();
                double pz = player.getZ();
                schedule(i * 5, () -> level.playSound(null, px, py, pz, SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, 1f, pitch));
            }
        }
        if (currentFloor + 1 == 3) {
            layer = Layer.DONE;
            openRewardChests();
        } else {
            currentFloor++;
            activateFloor(false);
        }
    }

    /** on_floor_failed: the floor breaks and comes back, same pattern, two seconds later. */
    private void onFloorFailed() {
        breakFloor();
        resumeAt = ticks + FAIL_BREAK_TICKS;
        layer = Layer.RESPAWNING;
    }

    /** open_reward_chests: the gate clears and both chests become openable blessing chests. */
    private void openRewardChests() {
        for (int x = GATE_MIN[0]; x <= GATE_MAX[0]; x++) {
            for (int y = GATE_MIN[1]; y <= GATE_MAX[1]; y++) {
                setBlock(world(x, y, GATE_MIN[2]), Blocks.AIR.defaultBlockState());
            }
        }
        BlockPos door = world(14, 74, 27);
        level.playSound(null, door.getX() + 0.5, door.getY(), door.getZ() + 0.5, SoundEvents.WOODEN_BUTTON_CLICK_ON, SoundSource.BLOCKS, 2f, 0.5f);

        // DungeonSecret::spawn_into_world for each chest.
        for (BlockPos chest : chests) {
            setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, turn(Direction.NORTH)));
            level.sendParticles(ParticleTypes.FLAME, chest.getX() + 0.5, chest.getY() + 1.0, chest.getZ() + 0.5, 1, 0, 0, 0, 0);
        }
        chestsOpenable = true;
    }

    // ---------------------------------------------------------------- reward chests

    /** The Chest interact handler for a blessing chest in a puzzle_chest_group: the last one solves it. */
    private void openChest(ServerPlayer player, BlockPos chest) {
        level.blockEvent(chest, level.getBlockState(chest).getBlock(), 1, 1);
        level.playSound(null, chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.975f);
        if (openedChests.size() == chests.size()) {
            solvedBy(player, room.data().name());
        }

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

    // ---------------------------------------------------------------- data

    private static boolean contains(int[][] tiles, int x, int z) {
        for (int[] t : tiles) {
            if (t[0] == x && t[1] == z) {
                return true;
            }
        }
        return false;
    }

    /** OBSTACLE_PATTERNS, from icefillpatterns.json. */
    static synchronized List<List<int[][]>> patterns() {
        if (patterns == null) {
            try (InputStream in = IceFillPuzzle.class.getResourceAsStream(FILE)) {
                if (in == null) {
                    throw new IOException("No " + FILE);
                }
                JsonArray floors = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("patterns");
                List<List<int[][]>> out = new ArrayList<>();
                for (JsonElement floor : floors) {
                    List<int[][]> list = new ArrayList<>();
                    for (JsonElement pattern : floor.getAsJsonArray()) {
                        JsonArray tiles = pattern.getAsJsonArray();
                        int[][] arr = new int[tiles.size()][];
                        for (int i = 0; i < tiles.size(); i++) {
                            JsonArray t = tiles.get(i).getAsJsonArray();
                            arr[i] = new int[] {t.get(0).getAsInt(), t.get(1).getAsInt()};
                        }
                        list.add(arr);
                    }
                    out.add(list);
                }
                patterns = out;
            } catch (IOException e) {
                throw new UncheckedIOException("Rustclear's Ice Fill patterns are missing from the jar", e);
            }
        }
        return patterns;
    }
}
