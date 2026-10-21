package net.rustclear.mod.engine.boss;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.rustclear.mod.RustclearMod;

/**
 * Scripted block changes re-derived from the recordings' block updates: named sets of frames
 * (tick, block, new state) in a resource file. P3's gates, doors and core (p3_blocks.json), P4's
 * floor drop, platforms and corners (p4_blocks.json). Each set is what at least half of the
 * recorded runs changed at the same offset; Rustclear's arena has those blocks as Hypixel's does
 * before the change. The scripts that measured them are noted in PROGRESS.md (Phases 6 and 7).
 */
public final class BossFrames {
    public record Frame(int dt, BlockPos pos, BlockState state) {}

    private static final Map<String, Map<String, List<Frame>>> FILES = new HashMap<>();

    private BossFrames() {
    }

    /** The frames of [name] in [file] (under assets/rustclear/engine/), or none. */
    public static synchronized List<Frame> get(String file, String name) {
        return FILES.computeIfAbsent(file, BossFrames::load).getOrDefault(name, List.of());
    }

    private static Map<String, List<Frame>> load(String file) {
        Map<String, List<Frame>> out = new HashMap<>();
        try (InputStream in = BossFrames.class.getResourceAsStream("/assets/rustclear/engine/" + file)) {
            if (in == null) {
                throw new IOException("no " + file);
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, BlockState> states = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                List<Frame> frames = new ArrayList<>();
                for (JsonElement f : e.getValue().getAsJsonArray()) {
                    JsonArray a = f.getAsJsonArray();
                    BlockState state = states.computeIfAbsent(a.get(4).getAsString(), BossFrames::parse);
                    if (state != null) {
                        frames.add(new Frame(a.get(0).getAsInt(),
                            new BlockPos(a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt()), state));
                    }
                }
                out.put(e.getKey(), List.copyOf(frames));
            }
        } catch (IOException | RuntimeException e) {
            RustclearMod.LOGGER.warn("Boss block frames {} unavailable: {}", file, e.toString());
        }
        return out;
    }

    private static BlockState parse(String s) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState();
        } catch (Exception e) {
            RustclearMod.LOGGER.warn("Boss block state {}: {}", s, e.toString());
            return null;
        }
    }

    /**
     * Plays [name]'s frames on [clock]: a frame recorded at dt goes in dt + [delay] ticks from now
     * (never before now), skipping frames [skip] accepts.
     */
    public static void play(Scheduler clock, BossBlocks blocks, String file, String name, int delay, Predicate<Frame> skip) {
        for (Frame f : get(file, name)) {
            if (skip != null && skip.test(f)) {
                continue;
            }
            clock.after(Math.max(0, f.dt() + delay), () -> blocks.set(f.pos(), f.state()));
        }
    }
}
