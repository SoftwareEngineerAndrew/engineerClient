package net.rustclear.mod.engine;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.rustclear.mod.RustclearMod;

/**
 * Light checks for a whole build, one light-thread task per chunk. ThreadedLevelLightEngine.checkBlock
 * queues every position as its own task through the chunk task dispatcher, which for a dungeon's
 * million-odd changed blocks costs the server thread more than placing them did. Each task here
 * does what that per-block task would (LevelLightEngine.checkBlock: the block, then the sky light
 * engine), in the same PRE_UPDATE queue, so the light comes out the same.
 *
 * ThreadedLevelLightEngine's task queue is private, so it is reached by reflection, looked up
 * once; if that ever fails, every position goes through checkBlock as before.
 */
final class LightBatch {
    private static boolean loaded;
    private static MethodHandle addTask;
    private static Object preUpdate;
    private static Field blockEngine;
    private static Field skyEngine;

    private LightBatch() {
    }

    /** Rechecks the light at [positions] (BlockPos longs). */
    static void check(LevelLightEngine light, LongArrayList positions) {
        if (positions.isEmpty()) {
            return;
        }
        if (light instanceof ThreadedLevelLightEngine threaded && load()) {
            try {
                LightEngine<?, ?> block = (LightEngine<?, ?>) blockEngine.get(threaded);
                LightEngine<?, ?> sky = (LightEngine<?, ?>) skyEngine.get(threaded);
                Long2ObjectOpenHashMap<LongArrayList> byChunk = new Long2ObjectOpenHashMap<>();
                for (int i = 0; i < positions.size(); i++) {
                    long pos = positions.getLong(i);
                    byChunk.computeIfAbsent(ChunkPos.pack(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4),
                        k -> new LongArrayList()).add(pos);
                }
                for (Long2ObjectMap.Entry<LongArrayList> entry : byChunk.long2ObjectEntrySet()) {
                    long[] chunkPositions = entry.getValue().toLongArray();
                    Runnable task = () -> {
                        for (long pos : chunkPositions) {
                            BlockPos at = BlockPos.of(pos);
                            if (block != null) {
                                block.checkBlock(at);
                            }
                            if (sky != null) {
                                sky.checkBlock(at);
                            }
                        }
                    };
                    addTask.invoke(threaded, ChunkPos.getX(entry.getLongKey()), ChunkPos.getZ(entry.getLongKey()), preUpdate, task);
                }
                return;
            } catch (Throwable t) {
                // Some chunks may be queued already; checking those positions twice is harmless,
                // so fall through and do them all the slow way.
                RustclearMod.LOGGER.warn("Batched light checks failed, checking block by block", t);
                loaded = true;
                addTask = null;
            }
        }
        for (int i = 0; i < positions.size(); i++) {
            light.checkBlock(BlockPos.of(positions.getLong(i)));
        }
    }

    private static synchronized boolean load() {
        if (!loaded) {
            loaded = true;
            try {
                Class<?> taskType = Class.forName(ThreadedLevelLightEngine.class.getName() + "$TaskType");
                Method method = ThreadedLevelLightEngine.class.getDeclaredMethod("addTask", int.class, int.class, taskType, Runnable.class);
                method.setAccessible(true);
                addTask = MethodHandles.lookup().unreflect(method);
                for (Object constant : taskType.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals("PRE_UPDATE")) {
                        preUpdate = constant;
                    }
                }
                blockEngine = LevelLightEngine.class.getDeclaredField("blockEngine");
                skyEngine = LevelLightEngine.class.getDeclaredField("skyEngine");
                blockEngine.setAccessible(true);
                skyEngine.setAccessible(true);
                if (preUpdate == null) {
                    throw new NoSuchFieldException("PRE_UPDATE");
                }
            } catch (Throwable t) {
                RustclearMod.LOGGER.warn("Batched light checks unavailable, checking block by block", t);
                addTask = null;
            }
        }
        return addTask != null;
    }
}
