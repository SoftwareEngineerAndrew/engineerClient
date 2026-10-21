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
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Water Board (Smalldungeons dungeon/room/waterboard.rs). One of four maze patterns is picked when
 * the dungeon is built; on entry 3 of the 5 gates close. The WATER lever pulls the lapis dam and
 * the server's own water flow ({@link WaterBoardSim}) runs down the maze, toggling any gate whose
 * sensor it newly reaches; the six coloured levers move their pieces of the maze. Every gate open,
 * or every click of the recorded answer key made on time, reveals the blessing chest; opening it
 * solves the puzzle.
 *
 * Every block here is set without neighbour updates or on-place reactions, so vanilla never
 * schedules a fluid tick for this water: the simulation is the only thing that moves it.
 */
final class WaterBoardPuzzle extends Puzzle {
    private static final String FILE = "/assets/rustclear/engine/puzzles/waterboard.json";
    private static final int CLICK_TOLERANCE_TICKS = 20;
    private static final int GATE_CLOSE_DELAY_TICKS = 1;
    private static final int LEVER_DEBOUNCE_TICKS = 4;
    private static final int SOLVE_WATER_EFFECT_TICKS = 60;
    private static final int BLESSING_LIFETIME_TICKS = Blessings.CHEST_DELAY;
    private static final float[] HARP = {0.7936508f, 0.8888889f, 1.0f, 1.0952381f, 1.1904762f};

    private static final int RETRACTED_Z = 27;
    private static final int EXTENDED_Z = 26;
    private static final int BASE_Z = 28;
    private static final int DAM_X = 15;
    private static final int DAM_Y = 82;
    private static final int DAM_Z = 26;

    /** 1.8 block ids, and 1.8 facing metas for pistons and heads. */
    private static final int AIR = 0;
    private static final int STONE = 1 << 4;
    private static final int LAPIS = 22 << 4;
    private static final int STICKY_PISTON = 29 << 4;
    private static final int PISTON_HEAD = 34 << 4;
    private static final int WOOL = 35 << 4;
    private static final int LEVER = 69 << 4;
    private static final int REDSTONE_BLOCK = 152 << 4;
    private static final int UP = 1;
    private static final int NORTH = 2;
    private static final int WEST = 4;
    private static final int EAST = 5;
    private static final int EXTENDED_OR_STICKY = 8;

    private static JsonObject data;

    /** LeverKind: where it is, its 1.8 lever orientation, its answer-key name, block and switch panel. */
    private enum Lever {
        COAL(20, 61, 10, 2, "coal_block", 173 << 4, new int[][] {{-6, 45, -14}, {4, 44, -14}, {6, 55, -14}, {-2, 58, -14}}),
        GOLD(20, 61, 15, 2, "gold_block", 41 << 4, new int[][] {{3, 45, -14}, {-2, 46, -14}, {-3, 57, -14}, {5, 52, -14}, {8, 56, -14}}),
        QUARTZ(20, 61, 20, 2, "quartz_block", 155 << 4, new int[][] {{9, 42, -14}, {-2, 56, -14}, {1, 59, -14}}),
        DIAMOND(10, 61, 20, 1, "diamond_block", 57 << 4, new int[][] {{-2, 44, -14}, {0, 46, -14}, {2, 58, -14}}),
        EMERALD(10, 61, 15, 1, "emerald_block", 133 << 4, new int[][] {{-1, 59, -14}, {-1, 57, -14}, {-8, 45, -14}}),
        CLAY(10, 61, 10, 1, "hardened_clay", 172 << 4, new int[][] {{5, 42, -14}, {-3, 45, -14}, {-3, 48, -14}, {0, 56, -14}, {7, 52, -14}}),
        WATER(15, 60, 5, 5, "water", AIR, new int[][] {{0, 63, -14}});

        final int x;
        final int y;
        final int z;
        final int orientation;
        final String key;
        final int material;
        final int[][] panel;

        Lever(int x, int y, int z, int orientation, String key, int material, int[][] panel) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.orientation = orientation;
            this.key = key;
            this.material = material;
            this.panel = panel;
        }
    }

    /** GateColor: its row (z), its sensor's x, its wool colour and its digit in the answer key's combo. */
    private enum Gate {
        PURPLE(19, 24, 10), ORANGE(18, 20, 1), BLUE(17, 15, 11), GREEN(16, 10, 5), RED(15, 6, 14);

        final int z;
        final int sensorX;
        final int wool;

        Gate(int z, int sensorX, int wool) {
            this.z = z;
            this.sensorX = sensorX;
            this.wool = wool;
        }
    }

    /** gate_pistons: {x, y, facing meta, dx, dy, dz} at the gate's own z. */
    private static final int[][] GATE_PISTONS = {
        {18, 57, WEST, -1, 0, 0}, {18, 56, WEST, -1, 0, 0}, {12, 57, EAST, 1, 0, 0}, {12, 56, EAST, 1, 0, 0}, {15, 54, UP, 0, 1, 0},
    };

    /** MaterialBlockSpec. */
    private record Piece(Lever lever, int x, int y, boolean extended) {}

    private static Piece p(Lever lever, int x, int y, boolean extended) {
        return new Piece(lever, x, y, extended);
    }

    /** MATERIAL_BLOCKS_ORIGINAL, which is also pattern 2. */
    private static final Piece[] PIECES_ORIGINAL = {
        p(Lever.COAL, 15, 63, true), p(Lever.COAL, 16, 68, true), p(Lever.COAL, 20, 71, true), p(Lever.COAL, 15, 76, true),
        p(Lever.GOLD, 17, 63, true), p(Lever.GOLD, 7, 68, false), p(Lever.GOLD, 16, 77, true),
        p(Lever.DIAMOND, 10, 62, false), p(Lever.DIAMOND, 22, 66, true), p(Lever.DIAMOND, 11, 68, false),
        p(Lever.DIAMOND, 17, 70, false), p(Lever.DIAMOND, 9, 72, false), p(Lever.DIAMOND, 15, 73, false),
        p(Lever.EMERALD, 6, 63, false), p(Lever.EMERALD, 11, 63, false), p(Lever.EMERALD, 20, 66, true), p(Lever.EMERALD, 14, 68, false),
        p(Lever.QUARTZ, 20, 62, false), p(Lever.QUARTZ, 7, 64, false), p(Lever.QUARTZ, 9, 68, false),
        p(Lever.QUARTZ, 22, 71, true), p(Lever.QUARTZ, 16, 74, false),
        p(Lever.CLAY, 10, 67, true), p(Lever.CLAY, 14, 77, false), p(Lever.CLAY, 16, 62, true), p(Lever.CLAY, 21, 70, true),
    };

    private static final Piece[] PIECES_0 = {
        p(Lever.QUARTZ, 6, 61, true), p(Lever.GOLD, 7, 75, false), p(Lever.CLAY, 8, 71, false), p(Lever.COAL, 9, 74, false),
        p(Lever.CLAY, 10, 61, false), p(Lever.GOLD, 10, 71, true), p(Lever.COAL, 11, 63, false), p(Lever.GOLD, 12, 64, true),
        p(Lever.DIAMOND, 13, 77, true), p(Lever.QUARTZ, 14, 78, true), p(Lever.DIAMOND, 15, 65, true), p(Lever.CLAY, 15, 75, false),
        p(Lever.EMERALD, 16, 76, true), p(Lever.EMERALD, 16, 78, false), p(Lever.DIAMOND, 17, 63, false), p(Lever.GOLD, 17, 65, true),
        p(Lever.QUARTZ, 17, 75, false), p(Lever.COAL, 17, 77, false), p(Lever.CLAY, 18, 64, false), p(Lever.CLAY, 18, 67, false),
        p(Lever.GOLD, 18, 76, true), p(Lever.COAL, 21, 64, false), p(Lever.EMERALD, 23, 64, false),
    };

    private static final Piece[] PIECES_1 = {
        p(Lever.EMERALD, 6, 62, true), p(Lever.GOLD, 6, 66, true), p(Lever.EMERALD, 8, 66, false), p(Lever.DIAMOND, 9, 69, true),
        p(Lever.EMERALD, 9, 78, true), p(Lever.DIAMOND, 10, 62, false), p(Lever.GOLD, 11, 77, false), p(Lever.EMERALD, 14, 72, false),
        p(Lever.DIAMOND, 14, 78, false), p(Lever.EMERALD, 16, 64, false), p(Lever.QUARTZ, 16, 78, true), p(Lever.QUARTZ, 18, 77, false),
        p(Lever.DIAMOND, 19, 66, false), p(Lever.GOLD, 20, 77, false), p(Lever.GOLD, 21, 70, false), p(Lever.QUARTZ, 22, 66, true),
        p(Lever.DIAMOND, 22, 73, false), p(Lever.EMERALD, 22, 77, false),
    };

    private static final Piece[] PIECES_3 = {
        p(Lever.EMERALD, 6, 62, true), p(Lever.COAL, 6, 68, false), p(Lever.QUARTZ, 9, 63, false), p(Lever.DIAMOND, 9, 69, false),
        p(Lever.COAL, 10, 62, false), p(Lever.GOLD, 10, 74, true), p(Lever.EMERALD, 10, 76, true), p(Lever.QUARTZ, 11, 69, false),
        p(Lever.CLAY, 11, 73, false), p(Lever.DIAMOND, 12, 74, false), p(Lever.EMERALD, 13, 71, true), p(Lever.GOLD, 14, 64, true),
        p(Lever.CLAY, 14, 66, false), p(Lever.QUARTZ, 14, 78, false), p(Lever.CLAY, 15, 63, true), p(Lever.COAL, 15, 71, false),
        p(Lever.EMERALD, 16, 62, true), p(Lever.DIAMOND, 16, 64, true), p(Lever.GOLD, 16, 66, true), p(Lever.EMERALD, 16, 74, true),
        p(Lever.GOLD, 16, 78, true), p(Lever.GOLD, 17, 71, true), p(Lever.EMERALD, 19, 71, true), p(Lever.QUARTZ, 20, 70, true),
        p(Lever.COAL, 20, 77, true), p(Lever.DIAMOND, 21, 64, false), p(Lever.CLAY, 21, 71, false), p(Lever.CLAY, 21, 74, false),
        p(Lever.EMERALD, 22, 63, false), p(Lever.DIAMOND, 22, 73, false), p(Lever.GOLD, 22, 75, true), p(Lever.COAL, 23, 64, false),
        p(Lever.EMERALD, 23, 74, true),
    };

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
    private final Map<BlockPos, Lever> levers = new HashMap<>();
    private final BlockPos chest;
    /** The 1.8 id of every water block this puzzle wrote, so still and flowing water stay apart. */
    private final Map<BlockPos, Integer> written = new HashMap<>();
    /** The picked pattern's blocks where it differs from the recorded room, in room coordinates. */
    private final Map<BlockPos, Integer> patternBlocks = new HashMap<>();
    /** Everything this puzzle set, emptied again with the dungeon. */
    private final Set<BlockPos> touched = new HashSet<>();
    private final List<Blessing> blessings = new ArrayList<>();

    private Piece[] pieces = PIECES_ORIGINAL;
    private String answerKeyPattern = "0";
    private long now;

    // WaterboardState, from entry on.
    private boolean active;
    private List<Gate> closedGates = List.of();
    private Map<String, List<Double>> solution;
    private final Map<String, Integer> consumed = new HashMap<>();
    private boolean waterOn;
    private long waterStartTick = -1;
    private boolean solved;
    private final Map<Lever, Boolean> leverPowered = new EnumMap<>(Lever.class);
    private WaterBoardSim sim;
    private final Map<Gate, Boolean> gateClosed = new EnumMap<>(Gate.class);
    private final Map<Gate, Boolean> gateSensorWet = new EnumMap<>(Gate.class);
    private final Map<Lever, Long> lastClickTick = new EnumMap<>(Lever.class);
    private boolean chestSpawned;
    private boolean chestOpened;

    WaterBoardPuzzle(DungeonRun run, RoomState room) {
        super(run, room);
        this.legacy = LegacyBlocks.shared();
        for (Lever lever : Lever.values()) {
            levers.put(world(lever.x, lever.y, lever.z), lever);
        }
        this.chest = world(15, 56, 22);
    }

    // ---------------------------------------------------------------- events

    /** pick_pattern / apply_pattern, at load_into_world: one of the four mazes. */
    @Override
    void setup() {
        // Practice mode's water_board_N picks the maze (practice.rs find_room_data / apply_pattern).
        int pattern = run.forcedWaterPattern >= 0 && run.forcedWaterPattern < 4 ? run.forcedWaterPattern : random.nextInt(4);
        switch (pattern) {
            case 0 -> {
                pieces = PIECES_0;
                answerKeyPattern = "1";
            }
            case 1 -> {
                pieces = PIECES_1;
                answerKeyPattern = "2";
            }
            case 3 -> {
                pieces = PIECES_3;
                answerKeyPattern = "3";
            }
            default -> {
                pieces = PIECES_ORIGINAL;
                answerKeyPattern = "0";
            }
        }
        JsonArray diff = data().getAsJsonObject("patterns").getAsJsonArray(Integer.toString(pattern));
        if (diff == null) {
            return;
        }
        List<BlockPos> changed = new ArrayList<>();
        for (int i = 0; i + 3 < diff.size(); i += 4) {
            int x = diff.get(i).getAsInt();
            int y = diff.get(i + 1).getAsInt();
            int z = diff.get(i + 2).getAsInt();
            int id = diff.get(i + 3).getAsInt();
            patternBlocks.put(new BlockPos(x, y, z), id);
            BlockPos pos = world(x, y, z);
            place(pos, id);
            changed.add(pos);
        }
        // The builder's settleShapes ran before the swap: stairs re-join their new neighbours.
        for (BlockPos pos : changed) {
            settle(pos);
            settle(pos.above());
            settle(pos.below());
            settle(new BlockPos(pos.getX() + 1, pos.getY(), pos.getZ()));
            settle(new BlockPos(pos.getX() - 1, pos.getY(), pos.getZ()));
            settle(new BlockPos(pos.getX(), pos.getY(), pos.getZ() + 1));
            settle(new BlockPos(pos.getX(), pos.getY(), pos.getZ() - 1));
        }
    }

    /** setup: the dam shut, the panel off, 3 gates closing a tick later and the levers live. */
    @Override
    void entered() {
        setDamPiston(true);
        for (Lever lever : Lever.values()) {
            leverPowered.put(lever, false);
            setSwitchPanel(lever, false);
        }

        List<Gate> gates = new ArrayList<>(Arrays.asList(Gate.values()));
        Collections.shuffle(gates, random);
        closedGates = List.copyOf(gates.subList(0, 3));
        for (Gate gate : Gate.values()) {
            if (!closedGates.contains(gate)) {
                applyGateState(gate, false);
            }
        }
        List<Gate> closing = closedGates;
        schedule(GATE_CLOSE_DELAY_TICKS, () -> {
            for (Gate gate : closing) {
                applyGateState(gate, true);
                playGateSound(gate, true);
            }
        });
        for (Gate gate : Gate.values()) {
            gateClosed.put(gate, closedGates.contains(gate));
            gateSensorWet.put(gate, false);
        }

        solution = readSolution(answerKeyPattern, comboKey(closedGates));

        sim = new WaterBoardSim(validWaterCells(), this::cell, this::place);
        for (BlockPos pos : outputRowPositions()) {
            sim.addTerminal(pos);
        }

        for (Lever lever : Lever.values()) {
            place(world(lever.x, lever.y, lever.z), LEVER | lever.orientation);
        }
        active = true;
    }

    /** waterboard::tick: the water flow, then the gate sensors; and the blessing's float. */
    @Override
    void tick() {
        now++;
        tickBlessings();
        if (sim == null || !sim.isActive()) {
            return;
        }
        sim.tick(now);
        updateGateSensors();
    }

    @Override
    boolean useBlock(ServerPlayer player, BlockPos pos) {
        if (pos.equals(chest)) {
            // Until it is revealed the recorded chest is only scenery, as on the server.
            if (chestSpawned && !chestOpened) {
                chestOpened = true;
                openChest(player);
            }
            return true;
        }
        Lever lever = levers.get(pos);
        if (lever == null) {
            return false;
        }
        if (active) {
            handleLever(lever);
        }
        return true;
    }

    @Override
    boolean isInteractable(BlockPos pos) {
        return pos.equals(chest) || levers.containsKey(pos);
    }

    @Override
    void dispose() {
        blessings.clear();
        BlockState air = Blocks.AIR.defaultBlockState();
        // Piston bases first, so no head is ever removed in front of an extended base.
        Block piston = legacy.get(STICKY_PISTON).getBlock();
        for (BlockPos pos : touched) {
            if (level.getBlockState(pos).is(piston)) {
                setBlock(pos, air);
            }
        }
        for (BlockPos pos : touched) {
            setBlock(pos, air);
        }
        touched.clear();
        written.clear();
    }

    private final Map<Lever, Integer> clicks = new EnumMap<>(Lever.class);
    private long waterOpened = -1;

    // ---------------------------------------------------------------- levers

    /** handle_lever. */
    private void handleLever(Lever lever) {
        Long last = lastClickTick.get(lever);
        if (last != null && now - last < LEVER_DEBOUNCE_TICKS) {
            return;
        }
        lastClickTick.put(lever, now);

        if (lever == Lever.WATER) {
            waterOn = !waterOn;
            clicks.merge(lever, 1, Integer::sum);
            if (waterOpened < 0) {
                waterOpened = now;
            }
            if (waterOn) {
                waterStartTick = now;
            } else {
                waterStartTick = -1;
            }
            setWaterFlow(waterOn);
            checkKey();
            return;
        }

        boolean powered = !leverPowered.get(lever);
        leverPowered.put(lever, powered);
        clicks.merge(lever, 1, Integer::sum);
        place(world(lever.x, lever.y, lever.z), LEVER | lever.orientation | (powered ? 8 : 0));
        if (solution != null && solution.get(lever.key) != null && clicks.get(lever) <= solution.get(lever.key).size()) {
            SimLog.event("puzzle_waterboard_click", "lever", lever.name(), "n", clicks.get(lever),
                "due", Math.round(solution.get(lever.key).get(clicks.get(lever) - 1) * 20), "at", waterOpened < 0 ? -1 : now - waterOpened);
        }
        setSwitchPanel(lever, powered);
        setMaterialGroup(lever, powered);

        if (solved || solution == null) {
            return;
        }
        List<Double> times = solution.get(lever.key);
        if (times == null) {
            return;
        }
        int next = consumed.getOrDefault(lever.key, 0);
        if (next >= times.size()) {
            return;
        }
        // The answer key's times count from the water lever's first click, as the players' solvers read them (Odin's
        // WaterSolver): a 0 s click is made before the water (or within the tolerance after it); a later one within the
        // tolerance of its time. The sim used to count only clicks after the water and from its latest opening, so the
        // 0 s levers everyone flips first never counted and the key could not be finished.
        long expected = Math.round(times.get(next) * 20.0);
        boolean onTime = expected == 0 ? waterOpened < 0 || now - waterOpened <= CLICK_TOLERANCE_TICKS
            : waterOpened >= 0 && Math.abs(now - waterOpened - expected) <= CLICK_TOLERANCE_TICKS;
        if (!onTime) {
            return;
        }
        consumed.put(lever.key, next + 1);
        checkKey();
    }

    /** Every click of the answer key made on time (the water lever's own aside): solved. */
    private void checkKey() {
        if (solved || solution == null || waterOpened < 0) {
            return;
        }
        for (Map.Entry<String, List<Double>> e : solution.entrySet()) {
            if (!e.getKey().equals("water") && consumed.getOrDefault(e.getKey(), 0) < e.getValue().size()) {
                return;
            }
        }
        solved = true;
        solve();
    }

    /** set_water_flow: the lever, the dam piston, the panel and the source at the dam. */
    private void setWaterFlow(boolean on) {
        place(world(Lever.WATER.x, Lever.WATER.y, Lever.WATER.z), LEVER | Lever.WATER.orientation | (on ? 8 : 0));
        BlockPos dam = world(DAM_X, DAM_Y, DAM_Z);
        setDamPiston(!on);
        setSwitchPanel(Lever.WATER, on);
        // As on the server, removing the source after the dam closes leaves the dam's spot as air.
        if (on) {
            sim.addSource(dam, now);
        } else {
            sim.removeSource(dam, now);
        }
    }

    /** set_dam_piston: the sticky piston behind the lapis dam. */
    private void setDamPiston(boolean closed) {
        stickyPiston(DAM_X, DAM_Y, BASE_Z, NORTH, 0, 0, -1, LAPIS, closed);
    }

    /** set_switch_panel: redstone blocks while pulled, stone otherwise. */
    private void setSwitchPanel(Lever lever, boolean powered) {
        int block = powered ? REDSTONE_BLOCK : STONE;
        for (int[] raw : lever.panel) {
            place(world(DAM_X + raw[0], DAM_Y + raw[1] - 63, DAM_Z + raw[2] + 17), block);
        }
    }

    /** set_material_group: every piece of the lever's block, flipped from where it was recorded. */
    private void setMaterialGroup(Lever lever, boolean powered) {
        for (Piece piece : pieces) {
            if (piece.lever != lever) {
                continue;
            }
            stickyPiston(piece.x, piece.y, BASE_Z, NORTH, 0, 0, -1, lever.material, piece.extended != powered);
            sim.notifyNeighbors(world(piece.x, piece.y, BASE_Z), now);
            sim.notifyNeighbors(world(piece.x, piece.y, RETRACTED_Z), now);
            sim.notifyNeighbors(world(piece.x, piece.y, EXTENDED_Z), now);
        }
    }

    /** A sticky piston at (x, y, z) facing [facing] (offset dx/dy/dz) with [block] one or two along. */
    private void stickyPiston(int x, int y, int z, int facing, int dx, int dy, int dz, int block, boolean extended) {
        place(world(x, y, z), STICKY_PISTON | facing | (extended ? EXTENDED_OR_STICKY : 0));
        BlockPos near = world(x + dx, y + dy, z + dz);
        BlockPos far = world(x + dx * 2, y + dy * 2, z + dz * 2);
        if (extended) {
            place(near, PISTON_HEAD | facing | EXTENDED_OR_STICKY);
            place(far, block);
        } else {
            place(near, block);
            place(far, AIR);
        }
    }

    // ---------------------------------------------------------------- gates

    /** apply_gate_state: the hand-placed top middle wool and the five pistons. */
    private void applyGateState(Gate gate, boolean closed) {
        place(world(15, 57, gate.z), closed ? WOOL | gate.wool : AIR);
        for (int[] piston : GATE_PISTONS) {
            stickyPiston(piston[0], piston[1], gate.z, piston[2], piston[3], piston[4], piston[5], WOOL | gate.wool, closed);
        }
    }

    /** play_gate_sound: tile.piston.in closing, tile.piston.out opening. */
    private void playGateSound(Gate gate, boolean closed) {
        BlockPos pos = world(15, 56, gate.z);
        level.playSound(null, pos.getX(), pos.getY(), pos.getZ(),
            closed ? SoundEvents.PISTON_CONTRACT : SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1f, 1f);
    }

    /** set_gate_state. */
    private void setGateState(Gate gate, boolean closed) {
        applyGateState(gate, closed);
        gateClosed.put(gate, closed);
        playGateSound(gate, closed);
        sim.notifyNeighbors(world(15, 57, gate.z), now);
        for (int[] piston : GATE_PISTONS) {
            sim.notifyNeighbors(world(piston[0] + piston[3], piston[1] + piston[4], gate.z + piston[5]), now);
            sim.notifyNeighbors(world(piston[0] + piston[3] * 2, piston[1] + piston[4] * 2, gate.z + piston[5] * 2), now);
        }
    }

    private BlockPos sensor(Gate gate) {
        return world(gate.sensorX, 58, 26);
    }

    /** update_gate_sensors: water newly at a sensor toggles its gate; all five open solves it. */
    private void updateGateSensors() {
        if (solved) {
            return;
        }
        boolean anyToggled = false;
        for (Gate gate : Gate.values()) {
            boolean wet = level.getBlockState(sensor(gate)).is(Blocks.WATER);
            Boolean was = gateSensorWet.put(gate, wet);
            if (wet && !Boolean.TRUE.equals(was)) {
                setGateState(gate, !gateClosed.get(gate));
                anyToggled = true;
            }
        }
        if (!anyToggled) {
            return;
        }
        for (Gate gate : Gate.values()) {
            if (gateClosed.get(gate)) {
                return;
            }
        }
        solved = true;
        solve();
    }

    /** solve: every gate open, a splash at the three closed ones' sensors, and the chest. */
    private void solve() {
        for (Gate gate : Gate.values()) {
            if (gateClosed.get(gate)) {
                setGateState(gate, false);
            }
        }
        for (Gate gate : closedGates) {
            BlockPos pos = sensor(gate);
            place(pos, WaterBoardSim.STILL);
            gateSensorWet.put(gate, true);
            schedule(SOLVE_WATER_EFFECT_TICKS, () -> place(pos, AIR));
        }
        setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, turn(Direction.NORTH)));
        touched.add(chest);
        written.remove(chest);
        level.sendParticles(ParticleTypes.FLAME, chest.getX() + 0.5, chest.getY() + 1.0, chest.getZ() + 0.5, 1, 0, 0, 0, 0);
        chestSpawned = true;
    }

    // ---------------------------------------------------------------- water cells

    /** channel_reference_corners: {minX, minY, maxX, maxY} over the dam, sensors and pieces. */
    private int[] channelCorners() {
        int minX = DAM_X;
        int maxX = DAM_X;
        int minY = DAM_Y;
        int maxY = DAM_Y;
        for (Gate gate : Gate.values()) {
            minX = Math.min(minX, gate.sensorX);
            maxX = Math.max(maxX, gate.sensorX);
            minY = Math.min(minY, 58);
            maxY = Math.max(maxY, 58);
        }
        for (Piece piece : pieces) {
            minX = Math.min(minX, piece.x);
            maxX = Math.max(maxX, piece.x);
            minY = Math.min(minY, piece.y);
            maxY = Math.max(maxY, piece.y);
        }
        return new int[] {minX, minY, maxX, maxY};
    }

    /** valid_water_cells: the flow plane and the pieces' rest plane, minus the open shared layer. */
    private Set<BlockPos> validWaterCells() {
        int[] c = channelCorners();
        int shaftY = c[1] + 1;
        int pieceMinY = Integer.MAX_VALUE;
        int pieceMaxY = Integer.MIN_VALUE;
        for (Piece piece : pieces) {
            pieceMinY = Math.min(pieceMinY, piece.y);
            pieceMaxY = Math.max(pieceMaxY, piece.y);
        }
        if (pieces.length == 0) {
            pieceMinY = c[1];
            pieceMaxY = c[3];
        }
        Set<Integer> sensorXs = new HashSet<>();
        for (Gate gate : Gate.values()) {
            sensorXs.add(gate.sensorX);
        }
        Set<BlockPos> cells = new HashSet<>();
        for (int x = c[0]; x <= c[2]; x++) {
            for (int y = c[1]; y <= c[3] + 1; y++) {
                for (int z : new int[] {EXTENDED_Z, RETRACTED_Z}) {
                    if (y == shaftY && z == EXTENDED_Z && !sensorXs.contains(x)) {
                        continue;
                    }
                    if (z == RETRACTED_Z && (y < pieceMinY || y > pieceMaxY)) {
                        continue;
                    }
                    cells.add(world(x, y, z));
                }
            }
        }
        return cells;
    }

    /** output_row_positions. */
    private List<BlockPos> outputRowPositions() {
        int[] c = channelCorners();
        List<BlockPos> row = new ArrayList<>();
        for (int x = c[0]; x <= c[2]; x++) {
            row.add(world(x, c[1], EXTENDED_Z));
        }
        return row;
    }

    /** What the simulation sees at [pos], as a 1.8 id (see {@link WaterBoardSim}). */
    private int cell(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return AIR;
        }
        if (!state.is(Blocks.WATER)) {
            return WaterBoardSim.SOLID;
        }
        Integer id = written.get(pos);
        if (id != null) {
            return id;
        }
        int recorded = recorded(pos);
        return WaterBoardSim.isWater(recorded) ? recorded : WaterBoardSim.STILL;
    }

    /** The room's recorded 1.8 id at a world spot, with the picked pattern applied. */
    private int recorded(BlockPos pos) {
        int[] l = local(pos);
        Integer swapped = patternBlocks.get(new BlockPos(l[0], l[1], l[2]));
        if (swapped != null) {
            return swapped;
        }
        RoomData data = room.data();
        if (l[0] < 0 || l[0] >= data.width() || l[2] < 0 || l[2] >= data.length() || l[1] < data.bottom()) {
            return AIR;
        }
        int index = ((l[1] - data.bottom()) * data.length() + l[2]) * data.width() + l[0];
        return index < data.blocks().length ? data.blocks()[index] & 0xFFFF : AIR;
    }

    /** World::set_block_at with a 1.8 block, turned with the room like its recorded blocks. */
    private void place(BlockPos pos, int legacyId) {
        setBlock(pos, legacy.get(legacyId).rotate(facing().rotation()));
        touched.add(pos);
        if (WaterBoardSim.isWater(legacyId)) {
            written.put(pos, legacyId);
        } else {
            written.remove(pos);
        }
    }

    private void settle(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof StairBlock) {
            BlockState settled = Block.updateFromNeighbourShapes(state, level, pos);
            if (settled != state) {
                setBlock(pos, settled);
            }
        }
    }

    // ---------------------------------------------------------------- reward chest

    /** The Chest interact handler for a puzzle_room_index blessing chest. */
    private void openChest(ServerPlayer player) {
        level.blockEvent(chest, level.getBlockState(chest).getBlock(), 1, 1);
        level.playSound(null, chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.975f);
        solvedBy(player, "Water Board");

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

    /** combo_key: the closed gates' digits, ascending ("013"). */
    private static String comboKey(List<Gate> closed) {
        StringBuilder key = new StringBuilder();
        for (Gate gate : Gate.values()) {
            if (closed.contains(gate)) {
                key.append(gate.ordinal());
            }
        }
        return key.toString();
    }

    /** WATER_SOLUTIONS["false"][pattern][combo], or null when the table has no such entry. */
    private static Map<String, List<Double>> readSolution(String pattern, String combo) {
        JsonObject byPattern = data().getAsJsonObject("solutions").getAsJsonObject(pattern);
        if (byPattern == null || !byPattern.has(combo)) {
            return null;
        }
        Map<String, List<Double>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : byPattern.getAsJsonObject(combo).entrySet()) {
            List<Double> times = new ArrayList<>();
            for (JsonElement t : e.getValue().getAsJsonArray()) {
                times.add(t.getAsDouble());
            }
            out.put(e.getKey(), times);
        }
        return out;
    }

    /** waterboard.json: the answer key (waterSolutions.json's "false" half) and each pattern's changed blocks. */
    static synchronized JsonObject data() {
        if (data == null) {
            try (InputStream in = WaterBoardPuzzle.class.getResourceAsStream(FILE)) {
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
}
