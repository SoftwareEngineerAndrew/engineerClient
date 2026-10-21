package net.rustclear.mod.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.RustclearMod;

/**
 * Boulder, the box-pushing puzzle (Smalldungeons dungeon/room/boulder.rs): 3x3x3 boxes on a 7x6
 * grid, each pushed one cell by a button or face-centre sign on its side. A box of the same wood
 * blocks a push, a different one is crushed. Opening the blessing chest in the back solves it.
 */
final class BoulderPuzzle extends Puzzle {
    private static final String FILE = "/assets/rustclear/engine/puzzles/boulder.json";
    private static final int[] GRID_XS = {6, 9, 12, 15, 18, 21, 24};
    private static final int[] GRID_ZS = {9, 12, 15, 18, 21, 24};
    private static final int BOX_RADIUS = 1;
    private static final int BOX_Y_MIN = 64;
    private static final int CENTER_Y = BOX_Y_MIN + BOX_RADIUS;
    private static final int FLOOR_GLASS_LIFETIME_TICKS = 10;
    private static final int LEGACY_WHITE_GLASS = 95 << 4;
    private static final int BLESSING_LIFETIME_TICKS = Blessings.CHEST_DELAY;
    private static final float[] HARP = {0.7936508f, 0.8888889f, 1.0f, 1.0952381f, 1.1904762f};
    private static List<List<Box>> patterns;

    /** A sign or button on a box (Attachment); [pushX]/[pushZ] is where a trigger sends the box. */
    private record Attachment(int dx, int dy, int dz, int block, boolean trigger, int pushX, int pushZ) {}

    /** BoxTemplate: 27 core 1.8 ids (dx fastest, then dz, then dy), its wood and its attachments. */
    private record Template(int[] cells, int material, List<Attachment> attachments) {}

    private record Box(int gridX, int gridZ, Template template) {}

    /** BlockInteractAction::BoulderTrigger. */
    private record Trigger(int gridX, int gridZ, int pushX, int pushZ) {}

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

    private final LegacyBlocks legacy;
    private final BlockState glass;
    private final Map<Integer, Box> boxes = new LinkedHashMap<>();
    private final Map<BlockPos, Trigger> triggers = new HashMap<>();
    /** Lit floor tiles and the tick they turn back to barrier (active_floor_tiles). */
    private final Map<BlockPos, Long> floorTiles = new HashMap<>();
    private final List<Blessing> blessings = new ArrayList<>();
    private BlockPos chest;
    private boolean chestOpened;
    private long ticks;
    private int minX;
    private int maxX;
    private int minZ;
    private int maxZ;

    BoulderPuzzle(DungeonRun run, RoomState room) {
        super(run, room);
        this.legacy = LegacyBlocks.shared();
        this.glass = legacy.get(LEGACY_WHITE_GLASS);
    }

    /** setup: a random layout's boxes, and the room's own reward chest as a blessing chest. */
    @Override
    void setup() {
        List<List<Box>> all = patterns();
        for (Box box : all.get(random.nextInt(all.size()))) {
            boxes.put(key(box.gridX, box.gridZ), box);
        }
        rebuildAll();

        RoomData data = room.data();
        int[][] corners = {{0, 0}, {data.width() - 1, 0}, {0, data.length() - 1}, {data.width() - 1, data.length() - 1}};
        minX = Integer.MAX_VALUE;
        maxX = Integer.MIN_VALUE;
        minZ = Integer.MAX_VALUE;
        maxZ = Integer.MIN_VALUE;
        for (int[] c : corners) {
            BlockPos pos = world(c[0], 0, c[1]);
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }

                chest = world(15, 66, 29);
        setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, turn(Direction.NORTH)));
        level.sendParticles(ParticleTypes.FLAME, chest.getX() + 0.5, chest.getY() + 1.0, chest.getZ() + 0.5, 1, 0, 0, 0, 0);
    }

    /** tick: barrier floor near a player's feet lights up as white glass for half a second. */
    @Override
    void tick() {
        ticks++;
        for (ServerPlayer player : level.players()) {
            int feetX = (int) Math.floor(player.getX());
            int feetY = (int) Math.floor(player.getY() - 0.1);
            int feetZ = (int) Math.floor(player.getZ());
            RoomData data = room.data();
            if (feetX < minX || feetX > maxX || feetZ < minZ || feetZ > maxZ
                || feetY < data.bottom() || feetY > data.bottom() + data.levels() - 1) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = new BlockPos(feetX + dx, feetY, feetZ + dz);
                    BlockState current = level.getBlockState(pos);
                    if (current.is(Blocks.BARRIER)) {
                        setBlock(pos, glass);
                    } else if (current != glass) {
                        continue;
                    }
                    floorTiles.put(pos, ticks + FLOOR_GLASS_LIFETIME_TICKS);
                }
            }
        }
        for (Iterator<Map.Entry<BlockPos, Long>> it = floorTiles.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Long> tile = it.next();
            if (ticks >= tile.getValue()) {
                setBlock(tile.getKey(), Blocks.BARRIER.defaultBlockState());
                it.remove();
            }
        }
        tickBlessings();
    }

    @Override
    boolean useBlock(ServerPlayer player, BlockPos pos) {
        if (pos.equals(chest)) {
            if (!chestOpened) {
                chestOpened = true;
                openChest(player);
            }
            return true;
        }
        Trigger trigger = triggers.get(pos);
        if (trigger == null) {
            return false;
        }
        push(trigger);
        return true;
    }

    @Override
    boolean isInteractable(BlockPos pos) {
        return pos.equals(chest) || triggers.containsKey(pos);
    }

    @Override
    void dispose() {
        teardownAll();
        // Air rather than barrier: the builder clears the room's own barriers either way.
        for (BlockPos pos : floorTiles.keySet()) {
            setBlock(pos, Blocks.AIR.defaultBlockState());
        }
        floorTiles.clear();
        blessings.clear();
    }

    // ---------------------------------------------------------------- pushing

    /** handle_push: one cell along, unless off the grid or into a box of the same wood. */
    private void push(Trigger trigger) {
        int destX = trigger.gridX + trigger.pushX * 3;
        int destZ = trigger.gridZ + trigger.pushZ * 3;
        if (!isValidCell(destX, destZ)) {
            return;
        }
        Box moving = boxes.get(key(trigger.gridX, trigger.gridZ));
        if (moving == null) {
            return;
        }
        Box blocker = boxes.get(key(destX, destZ));
        if (blocker != null && blocker.template.material == moving.template.material) {
            return;
        }
        teardownAll();
        boxes.remove(key(destX, destZ));
        boxes.remove(key(trigger.gridX, trigger.gridZ));
        boxes.put(key(destX, destZ), new Box(destX, destZ, moving.template));
        rebuildAll();
    }

    /** teardown_all: every box's core and attachments to air, and no triggers. */
    private void teardownAll() {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (Box box : boxes.values()) {
            for (int dy = -BOX_RADIUS; dy <= BOX_RADIUS; dy++) {
                for (int dz = -BOX_RADIUS; dz <= BOX_RADIUS; dz++) {
                    for (int dx = -BOX_RADIUS; dx <= BOX_RADIUS; dx++) {
                        setBlock(world(box.gridX + dx, CENTER_Y + dy, box.gridZ + dz), air);
                    }
                }
            }
            for (Attachment a : box.template.attachments) {
                setBlock(world(box.gridX + a.dx, CENTER_Y + a.dy, box.gridZ + a.dz), air);
            }
        }
        triggers.clear();
    }

    /** rebuild_all: cores first, then every attachment no core covers (only those can be clicked). */
    private void rebuildAll() {
        Set<BlockPos> solid = new HashSet<>();
        for (Box box : boxes.values()) {
            for (int dy = -BOX_RADIUS; dy <= BOX_RADIUS; dy++) {
                for (int dz = -BOX_RADIUS; dz <= BOX_RADIUS; dz++) {
                    for (int dx = -BOX_RADIUS; dx <= BOX_RADIUS; dx++) {
                        int id = box.template.cells[index(dx, dy, dz)];
                        if (id != 0) {
                            solid.add(new BlockPos(box.gridX + dx, CENTER_Y + dy, box.gridZ + dz));
                        }
                        setBlock(world(box.gridX + dx, CENTER_Y + dy, box.gridZ + dz), turned(id));
                    }
                }
            }
        }
        for (Box box : boxes.values()) {
            for (Attachment a : box.template.attachments) {
                int x = box.gridX + a.dx;
                int y = CENTER_Y + a.dy;
                int z = box.gridZ + a.dz;
                if (solid.contains(new BlockPos(x, y, z))) {
                    continue;
                }
                BlockPos pos = world(x, y, z);
                setBlock(pos, turned(a.block));
                if (a.trigger) {
                    triggers.put(pos, new Trigger(box.gridX, box.gridZ, a.pushX, a.pushZ));
                }
            }
        }
    }

    private BlockState turned(int legacyId) {
        return legacy.get(legacyId).rotate(facing().rotation());
    }

    private static int index(int dx, int dy, int dz) {
        return (dx + BOX_RADIUS) + (dz + BOX_RADIUS) * 3 + (dy + BOX_RADIUS) * 9;
    }

    private static int key(int gridX, int gridZ) {
        return gridX * 64 + gridZ;
    }

    private static boolean isValidCell(int x, int z) {
        boolean validX = false;
        for (int gx : GRID_XS) {
            validX |= gx == x;
        }
        boolean validZ = false;
        for (int gz : GRID_ZS) {
            validZ |= gz == z;
        }
        return validX && validZ;
    }

    // ---------------------------------------------------------------- reward chest

    /** The Chest interact handler for a puzzle_room_index blessing chest. */
    private void openChest(ServerPlayer player) {
        level.blockEvent(chest, level.getBlockState(chest).getBlock(), 1, 1);
        level.playSound(null, chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.975f);
        solvedBy(player, "Boulder");

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

    /** load_patterns: the 8 captured layouts (patterns.json). */
    static synchronized List<List<Box>> patterns() {
        if (patterns == null) {
            JsonArray root;
            try (InputStream in = BoulderPuzzle.class.getResourceAsStream(FILE)) {
                if (in == null) {
                    throw new IOException("No " + FILE);
                }
                root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            } catch (IOException e) {
                throw new UncheckedIOException("Rustclear's dungeon data is missing from the jar", e);
            }
            List<List<Box>> all = new ArrayList<>();
            for (JsonElement pattern : root) {
                List<Box> list = new ArrayList<>();
                for (JsonElement element : pattern.getAsJsonObject().getAsJsonArray("boxes")) {
                    list.add(readBox(element.getAsJsonObject()));
                }
                all.add(list);
            }
            patterns = all;
        }
        return patterns;
    }

    private static Box readBox(JsonObject o) {
        String hex = o.get("block_data").getAsString();
        int[] cells = new int[27];
        for (int i = 0; i < 27 && i * 4 + 4 <= hex.length(); i++) {
            cells[i] = Integer.parseInt(hex.substring(i * 4, i * 4 + 4), 16);
        }
        List<Attachment> attachments = new ArrayList<>();
        for (JsonElement element : o.getAsJsonArray("attachments")) {
            JsonObject a = element.getAsJsonObject();
            // The push goes away from the face the trigger is on: the opposite of its facing.
            int[] push = switch (a.get("direction").getAsString()) {
                case "north" -> new int[] {0, 1};
                case "south" -> new int[] {0, -1};
                case "east" -> new int[] {-1, 0};
                case "west" -> new int[] {1, 0};
                default -> new int[] {0, 0};
            };
            attachments.add(new Attachment(a.get("dx").getAsInt(), a.get("dy").getAsInt(), a.get("dz").getAsInt(),
                a.get("block").getAsInt(), a.get("is_trigger").getAsBoolean(), push[0], push[1]));
        }
        Template template = new Template(cells, o.get("material").getAsInt(), List.copyOf(attachments));
        return new Box(o.get("grid_x").getAsInt(), o.get("grid_z").getAsInt(), template);
    }
}
