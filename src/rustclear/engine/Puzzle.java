package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One puzzle room's logic (Smalldungeons dungeon/room/&lt;puzzle&gt;.rs). The run makes one per puzzle
 * room (see {@link Puzzles#create}) and forwards it the events it can react to. Coordinates in the
 * room data's own frame go through {@link #world(int, int, int)}; anything the puzzle spawns goes
 * through {@link #track(Entity)} so it is removed with the dungeon.
 */
abstract class Puzzle {
    /** Block flags for puzzle edits: tell clients, don't run neighbour updates or block drops. */
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

    protected final DungeonRun run;
    protected final RoomState room;
    protected final ServerLevel level;
    protected final Random random = new Random();
    private final List<Entity> spawned = new ArrayList<>();

    Puzzle(DungeonRun run, RoomState room) {
        this.run = run;
        this.room = room;
        this.level = run.level;
    }

    // ---------------------------------------------------------------- events (override what's needed)

    /** Once, right after the dungeon's blocks are placed (load_into_world). */
    void setup() {
    }

    /** Once, the first time a player walks into the room (the entry-time setups in dungeon.rs). */
    void entered() {
    }

    /** Every server tick once the run has started (Room::tick). */
    void tick() {
    }

    /** A right click on a block. Return true when the puzzle used it (nothing vanilla then happens). */
    boolean useBlock(ServerPlayer player, BlockPos pos) {
        return false;
    }

    /** A right click on an entity. Return true when the puzzle used it. */
    boolean useEntity(ServerPlayer player, Entity entity) {
        return false;
    }

    /** A left click on an entity that isn't a dungeon mob. Return true when the puzzle used it. */
    boolean attackEntity(ServerPlayer player, Entity entity) {
        return false;
    }

    /** A left click on a block (before the Dungeonbreaker gets it). Return true when the puzzle used it. */
    boolean hitBlock(ServerPlayer player, BlockPos pos) {
        return false;
    }

    /** Whether [pos] is a block of this puzzle a player can use (so the Dungeonbreaker leaves it alone). */
    boolean isInteractable(BlockPos pos) {
        return false;
    }

    /** The dungeon is being torn down. Tracked entities are removed after this. */
    void dispose() {
    }

    // ---------------------------------------------------------------- helpers

    /** A spot in the room data's own coordinates (Room::get_world_block_pos), in the world. */
    final BlockPos world(int x, int y, int z) {
        return room.placed.toWorld(x, y, z);
    }

    /** A world spot back in the room data's own coordinates: {x, y, z}. */
    final int[] local(BlockPos pos) {
        return room.toLocal(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Which way the room is turned. */
    final Facing facing() {
        return room.placed.facing();
    }

    /** A direction in the room data's frame (as recorded, NORTH = unturned) turned to the world's. */
    final Direction turn(Direction recorded) {
        return facing().rotation().rotate(recorded);
    }

    /** A yaw in the room data's frame turned to the world's. */
    final float turnYaw(float yaw) {
        return facing().rotateYaw(yaw);
    }

    final void setBlock(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, FLAGS);
    }

    /** Adds [entity] to the world and removes it with the dungeon. */
    final <T extends Entity> T track(T entity) {
        level.addFreshEntity(entity);
        spawned.add(entity);
        return entity;
    }

    final void schedule(int delayTicks, Runnable task) {
        run.schedule(delayTicks, task);
    }

    final void broadcast(String message) {
        run.broadcast(message);
    }

    final void sound(BlockPos at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, sound, SoundSource.BLOCKS, volume, pitch);
    }

    /** Players currently standing in this room. */
    final List<ServerPlayer> playersInRoom() {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (run.roomAt(player.getX(), player.getZ()) == room.index) {
                out.add(player);
            }
        }
        return out;
    }

    final boolean done() {
        return room.puzzleCompleted;
    }

    /**
     * The puzzle is solved, or failed. Solved: room.puzzle_completed plus the map redraw. Failed:
     * as on Hypixel it isn't over (score-secrets-puzzles.md §6): the tab shows [✖], the live score
     * drops while it does, and [failResetTicks] later the puzzle is back as new and can be redone,
     * with no cost at the end. Announcements, chests and sounds are the puzzle's own to do.
     */
    final void finish(boolean failed) {
        if (room.puzzleCompleted || room.puzzleFailed) {
            return;
        }
        if (!failed) {
            room.puzzleCompleted = true;
            run.redrawRoom(room);
            return;
        }
        room.puzzleFailed = true;
        run.score.failedPuzzles++;
        run.score.puzzlesInFail++;
        run.redrawRoom(room);
        run.resetPuzzle(room, failResetTicks());
    }

    /** A failed puzzle comes back after 40 ticks (20-60, n = 21; Higher Or Lower). */
    int failResetTicks() {
        return 40;
    }

    /**
     * Solved by [player]. Most puzzles say nothing in chat on Hypixel (only the tab's [✔]); the
     * three that do print their own line.
     */
    final void solvedBy(ServerPlayer player, String name) {
        finish(false);
    }

    /** Hypixel's rainbow "Good job!" and "Yikes!" (73f3a2, fe890a, 447992). */
    static final String GOOD_JOB = "§4G§co§6o§ed§a §2j§bo§3b§5!";
    static final String YIKES = "§4Y§ci§6k§ee§as§2!";

    final void removeTracked() {
        spawned.forEach(Entity::discard);
        spawned.clear();
    }
}
