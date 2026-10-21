package net.rustclear.mod.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.rustclear.mod.RustclearMod;

/**
 * Crushers (crushers.rs): a width x height wall of polished granite that grows one block every
 * tick_per_block ticks out to max_length from its start, then (after pause_duration ticks, if any)
 * pulls back the same way, over and over, with a redstone block under its start. A room's crushers
 * only move while a player is in the room (dungeon.rs ticks them once per player there).
 *
 * The data is the room json's "crushers" list ({position, direction, width, height, max_length,
 * tick_per_block, pause_duration}); none of the current room data has any. Players in the way are
 * not pushed, as on the server (Crusher::is_in_way is unfinished there and always false).
 */
final class Crushers {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

    private static final class Crusher {
        final RoomState room;
        final BlockPos pos;
        final Facing direction;
        final int width;
        final int height;
        final int maxLength;
        final int tickPerBlock;
        final int pauseDuration;
        int currentLength;
        int tick;
        boolean paused;
        boolean reversed;

        Crusher(RoomState room, BlockPos pos, Facing direction, int width, int height, int maxLength, int tickPerBlock, int pauseDuration) {
            this.room = room;
            this.pos = pos;
            this.direction = direction;
            this.width = width;
            this.height = height;
            this.maxLength = maxLength;
            this.tickPerBlock = tickPerBlock;
            this.pauseDuration = pauseDuration;
        }
    }

    private final DungeonRun run;
    private final ServerLevel level;
    private final List<Crusher> crushers = new ArrayList<>();
    /** Every block a crusher set, cleared when the run is thrown away. */
    private final LongOpenHashSet touched = new LongOpenHashSet();

    Crushers(DungeonRun run) {
        this.run = run;
        this.level = run.level;
        for (RoomState room : run.rooms) {
            String json = room.data().crushersJson();
            if (json == null || json.isBlank() || json.equals("[]")) {
                continue;
            }
            try {
                for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
                    crushers.add(fromJson(room, e.getAsJsonObject()));
                }
            } catch (RuntimeException ex) {
                RustclearMod.LOGGER.warn("Bad crusher data in room {}: {}", room.data().name(), ex.toString());
            }
        }
    }

    /** Crusher::from_json plus Room::new's turn: direction and position go with the room. */
    private static Crusher fromJson(RoomState room, JsonObject o) {
        JsonArray p = o.getAsJsonArray("position");
        Facing facing = room.placed.facing();
        Facing direction = Facing.values()[o.get("direction").getAsInt()].rotate(facing);
        // Room::new also works out a width correction for north and south rooms, but drops the
        // result, so the start is just the turned position.
        BlockPos pos = room.placed.toWorld(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt());
        return new Crusher(room, pos, direction, o.get("width").getAsInt(), o.get("height").getAsInt(),
            o.get("max_length").getAsInt(), o.get("tick_per_block").getAsInt(), o.get("pause_duration").getAsInt());
    }

    /** Dungeon::tick (started runs only): each player's room's crushers tick once for that player. */
    void tick() {
        if (crushers.isEmpty()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            int index = run.roomAt(player.getX(), player.getZ());
            if (index < 0) {
                continue;
            }
            for (Crusher crusher : crushers) {
                if (crusher.room.index == index) {
                    tick(crusher);
                }
            }
        }
    }

    /** Crusher::tick. */
    private void tick(Crusher c) {
        c.tick++;
        set(c.pos.below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        if (c.paused) {
            if (c.tick == c.pauseDuration) {
                c.reversed = !c.reversed;
                c.paused = false;
                c.tick = 0;
            }
            return;
        }
        if (c.tickPerBlock > 0 && c.tick % c.tickPerBlock == 0) {
            int dx = dx(c.direction);
            int dz = dz(c.direction);
            if (!c.reversed) {
                BlockPos at = c.pos.offset(c.currentLength * dx, 0, c.currentLength * dz);
                setBlocks(c, Blocks.POLISHED_GRANITE.defaultBlockState(), at, dx, dz);
            } else {
                int back = c.maxLength - c.currentLength;
                BlockPos at = c.pos.offset(back * dx, 0, back * dz);
                setBlocks(c, Blocks.AIR.defaultBlockState(), at, dx, dz);
            }
            c.currentLength++;
        }
        if ((c.reversed && c.currentLength == c.maxLength) || (!c.reversed && c.currentLength > c.maxLength)) {
            if (c.pauseDuration != 0) {
                c.paused = true;
            } else {
                c.reversed = !c.reversed;
            }
            c.tick = 0;
            c.currentLength = 0;
        }
    }

    /** Crusher::set_blocks: the width runs across the direction of travel (x + w*dz, z + w*dx). */
    private void setBlocks(Crusher c, BlockState state, BlockPos at, int dx, int dz) {
        for (int w = 0; w < c.width; w++) {
            for (int h = 0; h < c.height; h++) {
                set(at.offset(w * dz, h, w * dx), state);
            }
        }
    }

    private void set(BlockPos pos, BlockState state) {
        if (level.getBlockState(pos) != state) {
            level.setBlock(pos, state, FLAGS);
        }
        touched.add(pos.asLong());
    }

    private static int dx(Facing f) {
        return f == Facing.EAST ? 1 : f == Facing.WEST ? -1 : 0;
    }

    private static int dz(Facing f) {
        return f == Facing.SOUTH ? 1 : f == Facing.NORTH ? -1 : 0;
    }

    void dispose() {
        var it = touched.iterator();
        while (it.hasNext()) {
            level.setBlock(BlockPos.of(it.nextLong()), Blocks.AIR.defaultBlockState(), FLAGS);
        }
        touched.clear();
    }
}
