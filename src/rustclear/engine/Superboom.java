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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;

/**
 * Superboom TNT (Smalldungeons Dungeon::superboom_at and Room::explode_*_near): blows open the
 * crypts, King Midas's golden crypt and the Superboom walls near where it is used. A crypt lets out
 * a Crypt Undead, which counts as a crypt once it is killed; King Midas's crypt lets him out.
 */
public final class Superboom {
    private static final String FILE = "/assets/rustclear/engine/breakables.json";
    private static final int FLAGS = 2;
    private static JsonObject data;

    /** A room's breakables in world coordinates, built the first time something in the room is blown up. */
    private static final class Patterns {
        final List<List<BlockPos>> crypts = new ArrayList<>();
        final List<List<BlockPos>> midas = new ArrayList<>();
        final List<List<BlockPos>> walls = new ArrayList<>();
    }

    private final DungeonRun run;
    private final ServerLevel level;
    private final Map<Integer, Patterns> rooms = new HashMap<>();

    Superboom(DungeonRun run) {
        this.run = run;
        this.level = run.level;
    }

    static synchronized JsonObject data() {
        if (data == null) {
            try (InputStream in = Superboom.class.getResourceAsStream(FILE)) {
                if (in == null) {
                    throw new IOException("No " + FILE);
                }
                data = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException e) {
                throw new UncheckedIOException("Rustclear's dungeon data is missing from the jar", e);
            }
        }
        return data;
    }

    /** The item's use: centred on [target], or the player's feet when used at the air. */
    void use(ServerPlayer player, BlockPos target) {
        BlockPos at = target != null ? target : player.blockPosition();
        // Facing south or east reaches 3 blocks, north or west 2, as the server has it.
        int dir = (int) Math.floor((((player.getYRot() % 360f) + 360f) % 360f + 45f) / 90f) % 4;
        int radius = dir == 0 || dir == 3 ? 3 : 2;
        explode(at, radius);
    }

    /** superboom_at. */
    void explode(BlockPos center, int radius) {
        int index = run.roomAt(center.getX(), center.getZ());
        if (index < 0) {
            return;
        }
        RoomState room = run.rooms.get(index);
        Patterns patterns = rooms.computeIfAbsent(index, i -> build(room));
        List<BlockPos> crypts = blowAll(patterns.crypts, center, radius);
        List<BlockPos> midas = blowAll(patterns.midas, center, radius);
        boolean wall = blowFirst(patterns.walls, center, radius);
        if (!crypts.isEmpty() || !midas.isEmpty() || wall) {
            level.playSound(null, center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5,
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1f, 1f);
        }
        for (BlockPos pos : crypts) {
            run.mobs().spawnFromCrypt(room, MobSystem.Archetype.CRYPT_UNDEAD, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                () -> run.score.crypts++);
        }
        for (BlockPos pos : midas) {
            run.mobs().spawnFromCrypt(room, MobSystem.Archetype.KING_MIDAS, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, null);
        }
    }

    /** explode_crypt_near: every pattern with a block in reach goes; returns each one's middle. */
    private List<BlockPos> blowAll(List<List<BlockPos>> patterns, BlockPos center, int radius) {
        List<BlockPos> middles = new ArrayList<>();
        for (Iterator<List<BlockPos>> it = patterns.iterator(); it.hasNext(); ) {
            List<BlockPos> pattern = it.next();
            if (!inReach(pattern, center, radius)) {
                continue;
            }
            it.remove();
            int x = 0;
            int y = 0;
            int z = 0;
            for (BlockPos pos : pattern) {
                x += pos.getX();
                y += pos.getY();
                z += pos.getZ();
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
            }
            int n = pattern.size();
            // Rust's integer division rounds toward zero.
            middles.add(new BlockPos(x / n, y / n, z / n));
        }
        return middles;
    }

    /** explode_superboomwall_near: only the first wall in reach goes. */
    private boolean blowFirst(List<List<BlockPos>> patterns, BlockPos center, int radius) {
        for (Iterator<List<BlockPos>> it = patterns.iterator(); it.hasNext(); ) {
            List<BlockPos> pattern = it.next();
            if (inReach(pattern, center, radius)) {
                it.remove();
                for (BlockPos pos : pattern) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Where a Superboom aimed at [target] from [eye] is placed: the block in front of the face that looks toward the
     * eye (the TNT goes there, as any placed block does).
     */
    static BlockPos placeAt(BlockPos target, net.minecraft.world.phys.Vec3 eye) {
        double dx = eye.x - (target.getX() + 0.5);
        double dy = eye.y - (target.getY() + 0.5);
        double dz = eye.z - (target.getZ() + 0.5);
        if (Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz)) {
            return target.offset(dx > 0 ? 1 : -1, 0, 0);
        }
        if (Math.abs(dz) >= Math.abs(dy)) {
            return target.offset(0, 0, dz > 0 ? 1 : -1);
        }
        return target.offset(0, dy > 0 ? 1 : -1, 0);
    }

    /** The player stands in the block its Superboom would be placed in (the TNT can't go inside the player). */
    static boolean blocked(net.minecraft.world.entity.player.Player player, BlockPos target) {
        BlockPos at = placeAt(target, player.getEyePosition());
        return player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(at));
    }

    /**
     * The blocks of room [index]'s closed crypt whose middle block is [middle] (as crypts() gives it) that have a face
     * open to the room (no block of the crypt and nothing solid beside it), nearest the middle first, at most [max]:
     * a Superboom on any of them blows the whole crypt, and a stand can see them (the middle sits inside: batch 55
     * skipped ~79 crypts a run for "no spot", knob cryptFaces).
     */
    List<BlockPos> cryptFaces(int index, BlockPos middle, int max) {
        List<BlockPos> out = new ArrayList<>();
        if (index < 0) {
            return out;
        }
        for (List<BlockPos> pattern : rooms.computeIfAbsent(index, i -> build(run.rooms.get(i))).crypts) {
            if (!pattern.contains(middle)) {
                continue;
            }
            java.util.Set<BlockPos> in = new java.util.HashSet<>(pattern);
            for (BlockPos b : pattern) {
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    BlockPos n = b.relative(d);
                    if (!in.contains(n) && level.getBlockState(n).getCollisionShape(level, n).isEmpty()) {
                        out.add(b);
                        break;
                    }
                }
            }
            out.sort(java.util.Comparator.comparingDouble(b -> b.distSqr(middle)));
            break;
        }
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /**
     * Blocks a Superboom hit on would blow room [index]'s closed crypt whose middle block is [middle] (knob cryptReach):
     * solid blocks with a face open to the air within the blast's smallest reach of one of the crypt's blocks (2
     * across, 1 up or down: [inReach] at radius 2, whichever way the member faces), the crypt's own included, nearest
     * [from] first, at most [max]. Players throw it at the floor or wall beside a crypt; the crypt's own blocks are
     * mostly buried (batch 61: ~70 "no spot" skips a run, 0.3 crypts a run blown by the crypt step).
     */
    List<BlockPos> cryptAims(int index, BlockPos middle, net.minecraft.world.phys.Vec3 from, int max) {
        List<BlockPos> out = new ArrayList<>();
        if (index < 0) {
            return out;
        }
        for (List<BlockPos> pattern : rooms.computeIfAbsent(index, i -> build(run.rooms.get(i))).crypts) {
            if (!pattern.contains(middle)) {
                continue;
            }
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (BlockPos b : pattern) {
                x0 = Math.min(x0, b.getX());
                y0 = Math.min(y0, b.getY());
                z0 = Math.min(z0, b.getZ());
                x1 = Math.max(x1, b.getX());
                y1 = Math.max(y1, b.getY());
                z1 = Math.max(z1, b.getZ());
            }
            for (BlockPos b : BlockPos.betweenClosed(x0 - 2, y0 - 1, z0 - 2, x1 + 2, y1 + 1, z1 + 2)) {
                if (run.roomAt(b.getX(), b.getZ()) != index || !inReach(pattern, b, 2)
                    || level.getBlockState(b).getCollisionShape(level, b).isEmpty()) {
                    continue;
                }
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    BlockPos n = b.relative(d);
                    if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) {
                        out.add(b.immutable());
                        break;
                    }
                }
            }
            out.sort(java.util.Comparator.comparingDouble(b -> net.minecraft.world.phys.Vec3.atCenterOf(b).distanceToSqr(from)));
            break;
        }
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    List<BlockPos> crypts(int index) {
        List<BlockPos> out = new ArrayList<>();
        if (index < 0) {
            return out;
        }
        for (List<BlockPos> pattern : rooms.computeIfAbsent(index, i -> build(run.rooms.get(i))).crypts) {
            double x = 0;
            double y = 0;
            double z = 0;
            for (BlockPos b : pattern) {
                x += b.getX();
                y += b.getY();
                z += b.getZ();
            }
            int n = pattern.size();
            BlockPos best = pattern.get(0);
            for (BlockPos b : pattern) {
                if (b.distToCenterSqr(x / n, y / n, z / n) < best.distToCenterSqr(x / n, y / n, z / n)) {
                    best = b;
                }
            }
            out.add(best);
        }
        return out;
    }

    boolean isWall(BlockPos pos) {
        int index = run.roomAt(pos.getX(), pos.getZ());
        if (index < 0) {
            return false;
        }
        for (List<BlockPos> pattern : rooms.computeIfAbsent(index, i -> build(run.rooms.get(i))).walls) {
            if (pattern.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** A block of the first weak wall still standing that a blast at [center] would reach (as [explode]), or null. */
    BlockPos wallNear(BlockPos center, int radius) {
        int index = run.roomAt(center.getX(), center.getZ());
        if (index < 0) {
            return null;
        }
        for (List<BlockPos> pattern : rooms.computeIfAbsent(index, i -> build(run.rooms.get(i))).walls) {
            if (inReach(pattern, center, radius)) {
                return clickable(pattern, center);
            }
        }
        return null;
    }

    /**
     * The block of [pos]'s weak wall a player would click the TNT on, nearest [near]: a cracked stone brick (the
     * legs blow only those), else the nearest block of the wall; null if [pos] is no standing wall's.
     */
    BlockPos wallBlock(BlockPos pos, BlockPos near) {
        int index = run.roomAt(pos.getX(), pos.getZ());
        if (index < 0) {
            return null;
        }
        for (List<BlockPos> pattern : rooms.computeIfAbsent(index, i -> build(run.rooms.get(i))).walls) {
            if (pattern.contains(pos)) {
                return clickable(pattern, near);
            }
        }
        return null;
    }

    private BlockPos clickable(List<BlockPos> pattern, BlockPos near) {
        BlockPos best = null;
        boolean bestCracked = false;
        for (BlockPos b : pattern) {
            boolean cracked = level.getBlockState(b).is(Blocks.CRACKED_STONE_BRICKS);
            if (best == null || cracked && !bestCracked
                || cracked == bestCracked && b.distManhattan(near) < best.distManhattan(near)) {
                best = b;
                bestCracked = cracked;
            }
        }
        return best;
    }

    /** Within [radius] across and one less up and down. */
    private static boolean inReach(List<BlockPos> pattern, BlockPos center, int radius) {
        for (BlockPos pos : pattern) {
            int dx = Math.abs(pos.getX() - center.getX());
            int dy = Math.abs(pos.getY() - center.getY());
            int dz = Math.abs(pos.getZ() - center.getZ());
            if (Math.max(dx, dz) <= radius && dy <= radius - 1) {
                return true;
            }
        }
        return false;
    }

    private static Patterns build(RoomState room) {
        Patterns patterns = new Patterns();
        JsonElement entry = data().get(room.data().name());
        if (entry == null) {
            return patterns;
        }
        JsonObject o = entry.getAsJsonObject();
        read(o.getAsJsonArray("crypts"), room, patterns.crypts);
        read(o.getAsJsonArray("midas"), room, patterns.midas);
        read(o.getAsJsonArray("walls"), room, patterns.walls);
        return patterns;
    }

    private static void read(JsonArray array, RoomState room, List<List<BlockPos>> into) {
        if (array == null) {
            return;
        }
        for (JsonElement pattern : array) {
            List<BlockPos> blocks = new ArrayList<>();
            for (JsonElement b : pattern.getAsJsonArray()) {
                JsonArray xyz = b.getAsJsonArray();
                blocks.add(room.placed.toWorld(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()));
            }
            if (!blocks.isEmpty()) {
                into.add(blocks);
            }
        }
    }
}
