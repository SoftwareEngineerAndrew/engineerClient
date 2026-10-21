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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Falling floors (fallingblocks.rs and Room's check_fallingblocks_collision /
 * begin_fallingblock_animation / finalize_fallingblock_drop): stepping on a tile of a room's
 * pattern turns the whole pattern to barrier at once, with a fizz and a copy of each block sinking
 * 5 blocks over 20 ticks; 5 ticks after the step the barrier turns to air and the floor is open.
 * A pattern drops once per run.
 *
 * The server's falling-block-riding-a-bat is a block display here, moved down each tick like
 * {@link DoorAnimation}'s.
 */
final class FallingBlocks {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
    private static final String FILE = "/assets/rustclear/engine/fallingblocks.json";
    /** check_fallingblocks_collision's FALL_DELAY_TICKS. */
    private static final int FALL_DELAY_TICKS = 5;
    /** FallingFloorEntityImpl::new(block, 5.0, 20). */
    private static final double FALL_DISTANCE = 5.0;
    private static final int FALL_TICKS = 20;

    /** Room name -> patterns, each a list of {x, y, z} (x/z from the room's corner, y absolute). */
    private static Map<String, List<List<int[]>>> data;

    private static final class Sinking {
        final Display.BlockDisplay display;
        int ticksLeft = FALL_TICKS;

        Sinking(Display.BlockDisplay display) {
            this.display = display;
        }
    }

    private final DungeonRun run;
    private final ServerLevel level;
    /** Room index -> its patterns in world positions (fallingblock_patterns). */
    private final Map<Integer, List<List<BlockPos>>> patterns = new HashMap<>();
    /** Room index -> pattern indices already stepped on (pending_fallingblock_triggers). */
    private final Map<Integer, Set<Integer>> triggered = new HashMap<>();
    private final List<Sinking> sinking = new ArrayList<>();

    FallingBlocks(DungeonRun run) {
        this.run = run;
        this.level = run.level;
        for (RoomState room : run.rooms) {
            List<List<int[]>> found = lookup(room.data().name(), room.data().shape().code);
            if (found.isEmpty()) {
                continue;
            }
            List<List<BlockPos>> world = new ArrayList<>();
            for (List<int[]> pattern : found) {
                List<BlockPos> blocks = new ArrayList<>();
                for (int[] b : pattern) {
                    // rotate_fallingblock_pos is BlockPos::rotate; y stays absolute.
                    blocks.add(room.placed.toWorld(b[0], b[1], b[2]));
                }
                world.add(blocks);
            }
            patterns.put(room.index, world);
        }
    }

    /** Player movement's check_fallingblocks_collision, for every player each tick. */
    void tick() {
        tickSinking();
        if (patterns.isEmpty()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            int roomIndex = run.roomAt(player.getX(), player.getZ());
            List<List<BlockPos>> roomPatterns = patterns.get(roomIndex);
            if (roomPatterns == null) {
                continue;
            }
            // The block actually holding the player's feet: an epsilon under the feet so a full
            // block's top maps to the cell below while a slab's top stays in its own cell.
            BlockPos feet = new BlockPos((int) Math.floor(player.getX()), (int) Math.floor(player.getY() - 1e-4), (int) Math.floor(player.getZ()));
            Set<Integer> done = triggered.computeIfAbsent(roomIndex, k -> new HashSet<>());
            for (int i = 0; i < roomPatterns.size(); i++) {
                if (done.contains(i) || !roomPatterns.get(i).contains(feet)) {
                    continue;
                }
                done.add(i);
                List<BlockPos> pattern = roomPatterns.get(i);
                begin(pattern);
                run.schedule(FALL_DELAY_TICKS, () -> finish(pattern));
                break;
            }
        }
    }

    /** begin_fallingblock_animation: fizz, barrier in place of every block, a sinking copy of each. */
    private void begin(List<BlockPos> pattern) {
        BlockPos first = pattern.get(0);
        level.playSound(null, first.getX() + 0.5, first.getY() + 0.5, first.getZ() + 0.5,
            SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 5f, 0.49f);
        for (BlockPos pos : pattern) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), FLAGS);
            if (state.is(Blocks.BARRIER)) {
                continue; // nothing to show falling
            }
            Display.BlockDisplay display = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
            display.setBlockState(state);
            display.setPosRotInterpolationDuration(1);
            display.snapTo(pos.getX(), pos.getY(), pos.getZ(), 0f, 0f);
            display.setNoGravity(true);
            display.setPermanentlyInvulnerable(true);
            level.addFreshEntity(display);
            sinking.add(new Sinking(display));
        }
    }

    /** finalize_fallingblock_drop: the barrier left by [begin] becomes air. */
    private void finish(List<BlockPos> pattern) {
        for (BlockPos pos : pattern) {
            if (level.getBlockState(pos).is(Blocks.BARRIER)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }
    }

    /** FallingFloorEntityImpl::tick: down by distance / ticks, gone once the ticks run out. */
    private void tickSinking() {
        if (sinking.isEmpty()) {
            return;
        }
        double step = FALL_DISTANCE / FALL_TICKS;
        for (Iterator<Sinking> it = sinking.iterator(); it.hasNext(); ) {
            Sinking s = it.next();
            Display.BlockDisplay display = s.display;
            display.setPos(display.getX(), display.getY() - step, display.getZ());
            s.ticksLeft--;
            if (s.ticksLeft <= 0 || display.isRemoved()) {
                display.discard();
                it.remove();
            }
        }
    }

    void dispose() {
        for (Sinking s : sinking) {
            s.display.discard();
        }
        sinking.clear();
    }

    // ---------------------------------------------------------------- data

    /**
     * get_room_fallingblocks: the entry under the room's name, under "&lt;shape key&gt; &lt;name&gt;" and
     * under the lower-cased name. (The server also adds the plain-name entry a second time, which
     * only doubles the fizz on the next step; that copy is left out.)
     */
    private static List<List<int[]>> lookup(String name, String shape) {
        Map<String, List<List<int[]>>> all = load();
        List<List<int[]>> out = new ArrayList<>();
        String shapeKey = shape.startsWith("1x1") || shape.equals("1x2") ? shape : "rest"; // room.rs shape_key
        Set<String> keys = new LinkedHashSet<>(List.of(name, shapeKey + " " + name, name.toLowerCase()));
        for (String key : keys) {
            List<List<int[]>> entry = all.get(key);
            if (entry != null) {
                out.addAll(entry);
            }
        }
        return out;
    }

    static synchronized Map<String, List<List<int[]>>> load() {
        if (data != null) {
            return data;
        }
        Map<String, List<List<int[]>>> out = new HashMap<>();
        try (InputStream in = FallingBlocks.class.getResourceAsStream(FILE)) {
            if (in == null) {
                throw new IOException("No " + FILE);
            }
            JsonObject rooms = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("rooms");
            for (Map.Entry<String, JsonElement> room : rooms.entrySet()) {
                List<List<int[]>> list = new ArrayList<>();
                for (JsonElement p : room.getValue().getAsJsonObject().getAsJsonArray("crypts")) {
                    List<int[]> blocks = new ArrayList<>();
                    for (JsonElement b : (JsonArray) p) {
                        JsonObject o = b.getAsJsonObject();
                        blocks.add(new int[] {o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()});
                    }
                    if (!blocks.isEmpty()) {
                        list.add(blocks);
                    }
                }
                out.put(room.getKey(), list);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Rustclear's dungeon data is missing from the jar", e);
        }
        data = out;
        return data;
    }
}
