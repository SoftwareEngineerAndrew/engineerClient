package net.rustclear.mod.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

/**
 * Tic Tac Toe (tic_tac_toe.rs): a perfect-play bot (X) against the players (O) on 9 wall buttons.
 * A played cell's button turns into an item frame holding the captured X or O map. A win or tie
 * drops the iron wall behind the board and reveals a blessing chest; a loss fails the puzzle.
 */
final class TicTacToePuzzle extends Puzzle {
    private static final int BLANK = 0;
    private static final int X = 1;
    private static final int O = 2;

    /** CELL_POSITIONS: row-major, row = i / 3, column = i % 3. */
    private static final int[][] CELLS = {
        {8, 72, 15}, {8, 72, 16}, {8, 72, 17},
        {8, 71, 15}, {8, 71, 16}, {8, 71, 17},
        {8, 70, 15}, {8, 70, 16}, {8, 70, 17},
    };
    private static final int[] CORNERS = {0, 2, 6, 8};
    private static final int[][] LINES = {
        {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
        {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
        {0, 4, 8}, {2, 4, 6},
    };
    /** REWARD_CHEST_POS. */
    private static final int[] CHEST = {2, 70, 16};
    /** BOT_MOVE_DELAY_TICKS. */
    private static final int BOT_MOVE_DELAY_TICKS = 3 * 20;
    /** drop_wall's FALL_TICKS. */
    private static final int FALL_TICKS = 20;
    /** EssenceEntityImpl lifetime for a blessing chest. */
    private static final int BLESSING_TICKS = Blessings.CHEST_DELAY;
    private static final float[] HARP = {0.7936508f, 0.8888889f, 1.0f, 1.0952381f, 1.1904762f};

    private final BlockPos[] cellBlocks = new BlockPos[9];
    /** WALL_POSITIONS: x 7, y 69..73, z 15..17, row-major from the bottom (index 7 is the middle). */
    private final BlockPos[] wall = new BlockPos[15];
    private final ItemFrame[] frames = new ItemFrame[9];
    private final int[] cells = new int[9];
    private BlockPos chestPos;
    private Direction facing;
    private MapId xMap;
    private MapId oMap;
    private boolean gameOver;
    private boolean won;
    private boolean botPending;
    private boolean chestRevealed;
    private boolean chestOpened;
    private boolean disposed;
    private ArmorStand blessing;
    private Pickup.Kind blessingKind;
    private ServerPlayer blessingFinder;
    private int blessingAge;

    TicTacToePuzzle(DungeonRun run, RoomState room) {
        super(run, room);
    }

    /** setup: maps, positions, frame facing read back from the placed buttons, then the bot's corner opening. */
    @Override
    void setup() {
        xMap = makeMap("map_30876.bin");
        oMap = makeMap("map_30877.bin");
        for (int i = 0; i < 9; i++) {
            cellBlocks[i] = world(CELLS[i][0], CELLS[i][1], CELLS[i][2]);
        }
        for (int i = 0; i < 15; i++) {
            wall[i] = world(7, 69 + i / 3, 15 + i % 3);
        }
        chestPos = world(CHEST[0], CHEST[1], CHEST[2]);

        BlockState button = level.getBlockState(cellBlocks[0]);
        facing = button.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            ? button.getValue(BlockStateProperties.HORIZONTAL_FACING)
            : turn(Direction.EAST);

        setCell(CORNERS[random.nextInt(CORNERS.length)], X);
        sendMaps(level.players());
    }

    /** send_map_definitions_to: whoever walks in has the X and O designs. */
    @Override
    void entered() {
        sendMaps(playersInRoom());
        spawnMissingFrames();
    }

    /** tick: the frames of cells played while their chunk wasn't loaded, and the blessing's float. */
    @Override
    void tick() {
        spawnMissingFrames();
        tickBlessing();
    }

    @Override
    boolean useBlock(ServerPlayer player, BlockPos pos) {
        if (pos.equals(chestPos)) {
            if (chestRevealed && !chestOpened) {
                openRewardChest(player);
            }
            return true;
        }
        int index = cellAt(pos);
        if (index < 0) {
            return false;
        }
        interactCell(player, index);
        return true;
    }

    @Override
    boolean useEntity(ServerPlayer player, Entity entity) {
        return isFrame(entity);
    }

    @Override
    boolean attackEntity(ServerPlayer player, Entity entity) {
        return isFrame(entity);
    }

    @Override
    boolean isInteractable(BlockPos pos) {
        if (pos.equals(chestPos)) {
            return chestRevealed;
        }
        int index = cellAt(pos);
        return index >= 0 && cells[index] == BLANK;
    }

    @Override
    void dispose() {
        disposed = true;
        clearFrames();
    }

    // ---------------------------------------------------------------- the game

    /** interact_cell: the player's O, then the bot's answer 3 seconds later. */
    private void interactCell(ServerPlayer player, int index) {
        if (gameOver || botPending || cells[index] != BLANK) {
            return;
        }
        playButtonClick(cellBlocks[index]);
        setCell(index, O);
        if (finishIfOver(player)) {
            return;
        }
        int move = bestBotMove(cells);
        botPending = true;
        schedule(BOT_MOVE_DELAY_TICKS, () -> {
            if (disposed) {
                return;
            }
            botPending = false;
            setCell(move, X);
            finishIfOver(player);
        });
    }

    /** set_cell: the button goes and an item frame with the mark's map takes its place. */
    private void setCell(int index, int mark) {
        cells[index] = mark;
        setBlock(cellBlocks[index], Blocks.AIR.defaultBlockState());
        if (level.isPositionEntityTicking(cellBlocks[index])) {
            spawnFrame(index);
        }
    }

    private void spawnMissingFrames() {
        if (won) {
            return;
        }
        for (int i = 0; i < 9; i++) {
            if (cells[i] != BLANK && frames[i] == null && level.isPositionEntityTicking(cellBlocks[i])) {
                spawnFrame(i);
            }
        }
    }

    private void spawnFrame(int index) {
        if (frames[index] != null || won) {
            return;
        }
        ItemFrame frame = new ItemFrame(level, cellBlocks[index], facing);
        frame.setSilent(true);
        frame.setPermanentlyInvulnerable(true);
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, cells[index] == X ? xMap : oMap);
        frame.setItem(map);
        frames[index] = track(frame);
    }

    /** finish_if_over: a win or a tie solves it (wall, sounds, chest, frames gone), a loss fails it. */
    private boolean finishIfOver(ServerPlayer player) {
        int winner = winner(cells);
        if (winner == BLANK && !isFull(cells)) {
            return false;
        }
        gameOver = true;
        if (winner == X) {
            broadcast("§c§lPUZZLE FAIL! " + DungeonRun.nameColour(player) + " §elost Tic Tac Toe! " + YIKES);
            room.failedBy = player.getName().getString();
            finish(true);
            return true;
        }
        won = true;
        broadcast("§a§lPUZZLE SOLVED! " + DungeonRun.nameColour(player) + " §etied Tic Tac Toe! " + GOOD_JOB);
        solvedBy(player, "Tic Tac Toe");
        dropWall();
        playCompleteSound();
        revealRewardChest();
        clearFrames();
        return true;
    }

    private static int winner(int[] board) {
        for (int[] line : LINES) {
            int a = board[line[0]];
            if (a != BLANK && a == board[line[1]] && a == board[line[2]]) {
                return a;
            }
        }
        return BLANK;
    }

    private static boolean isFull(int[] board) {
        for (int cell : board) {
            if (cell == BLANK) {
                return false;
            }
        }
        return true;
    }

    /** minimax: +10 for an X win, -10 for an O win, 0 for a tie, no depth shaping. */
    private static int minimax(int[] board, boolean maximizing) {
        int win = winner(board);
        if (win != BLANK) {
            return win == X ? 10 : -10;
        }
        if (isFull(board)) {
            return 0;
        }
        int mover = maximizing ? X : O;
        int best = maximizing ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            if (board[i] != BLANK) {
                continue;
            }
            board[i] = mover;
            int score = minimax(board, !maximizing);
            board[i] = BLANK;
            best = maximizing ? Math.max(best, score) : Math.min(best, score);
        }
        return best;
    }

    /** best_bot_move: a random one of the equally best cells for X. */
    private int bestBotMove(int[] current) {
        int[] board = current.clone();
        int bestScore = Integer.MIN_VALUE;
        List<Integer> best = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (board[i] != BLANK) {
                continue;
            }
            board[i] = X;
            int score = minimax(board, false);
            board[i] = BLANK;
            if (score > bestScore) {
                bestScore = score;
                best.clear();
                best.add(i);
            } else if (score == bestScore) {
                best.add(i);
            }
        }
        return best.get(random.nextInt(best.size()));
    }

    // ---------------------------------------------------------------- effects and reward

    /** play_button_click_sound: random.click (pitch byte 38) and note.pling (pitch byte 255, so 2.0). */
    private void playButtonClick(BlockPos pos) {
        sound(pos, SoundEvents.STONE_BUTTON_CLICK_ON, 0.3f, 38 / 63f);
        sound(pos, SoundEvents.NOTE_BLOCK_PLING.value(), 8f, 2f);
    }

    /** drop_wall: barrier at once, air when the fall is over (the door's falling blocks aren't shown). */
    private void dropWall() {
        for (BlockPos pos : wall) {
            setBlock(pos, Blocks.BARRIER.defaultBlockState());
        }
        schedule(FALL_TICKS, () -> {
            if (disposed) {
                return;
            }
            for (BlockPos pos : wall) {
                setBlock(pos, Blocks.AIR.defaultBlockState());
            }
        });
    }

    /** play_puzzle_complete_sound: 9 clicks 5 ticks apart from the wall's middle. */
    private void playCompleteSound() {
        BlockPos middle = wall[7];
        for (int i = 0; i < 9; i++) {
            schedule(i * 5, () -> sound(middle, SoundEvents.WOODEN_PRESSURE_PLATE_CLICK_ON, 0.6f, 25 / 63f));
        }
    }

    /** reveal_reward_chest: an east-facing chest with the chest flame; not one of the room's secrets. */
    private void revealRewardChest() {
        setBlock(chestPos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, turn(Direction.EAST)));
        level.sendParticles(ParticleTypes.FLAME, chestPos.getX() + 0.5, chestPos.getY() + 1.0, chestPos.getZ() + 0.5, 1, 0, 0, 0, 0);
        chestRevealed = true;
    }

    /** The blessing chest's open (block_interact_action.rs Chest with blessing_texture). */
    private void openRewardChest(ServerPlayer player) {
        blessingFinder = player;
        chestOpened = true;
        BlockPos pos = chestPos;
        level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, 1);
        level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.975f);
        for (int i = 0; i < HARP.length; i++) {
            float pitch = HARP[i];
            schedule(i * 5, () -> level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, 1f, pitch));
        }
        blessingKind = Blessings.puzzleKind(random);
        ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        stand.snapTo(pos.getX() + 0.5, pos.getY() - 1.4, pos.getZ() + 0.5, 0f, 0f);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setPermanentlyInvulnerable(true);
        stand.setSilent(true);
        stand.setItemSlot(EquipmentSlot.HEAD, blessingKind.item());
        stand.setCustomName(EngineItems.text(blessingKind.name));
        stand.setCustomNameVisible(true);
        blessing = track(stand);
        blessingAge = 0;
    }

    /** EssenceEntityImpl::tick: rise for a second, spin, puff, then grant the blessing 23 ticks after the lid (Blessings). */
    private void tickBlessing() {
        if (blessing == null) {
            return;
        }
        ArmorStand stand = blessing;
        if (blessingAge < 20) {
            stand.setPos(stand.getX(), stand.getY() + 0.04, stand.getZ());
        }
        stand.setYRot(stand.getYRot() + 15f);
        if (blessingAge % 5 == 0) {
            level.sendParticles(ParticleTypes.CLOUD, stand.getX(), stand.getY() + 1.5, stand.getZ(), 5, 0, 0, 0, 0.06);
        }
        if (blessingAge == BLESSING_TICKS) {
            Blessings.grant(run, blessingKind, 5, blessingFinder, -1);
            for (int i = 0; i < 2; i++) {
                level.playSound(null, stand.getX(), stand.getY(), stand.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1.5f);
            }
            stand.discard();
            blessing = null;
            return;
        }
        blessingAge++;
    }

    // ---------------------------------------------------------------- frames and maps

    private int cellAt(BlockPos pos) {
        for (int i = 0; i < 9; i++) {
            if (cellBlocks[i] != null && cellBlocks[i].equals(pos)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isFrame(Entity entity) {
        return entity instanceof ItemFrame && entity.getBoundingBox().intersects(boardBox());
    }

    private AABB boardBox() {
        BlockPos a = cellBlocks[0];
        BlockPos b = cellBlocks[8];
        return new AABB(Math.min(a.getX(), b.getX()) - 1, Math.min(a.getY(), b.getY()) - 1, Math.min(a.getZ(), b.getZ()) - 1,
            Math.max(a.getX(), b.getX()) + 2, Math.max(a.getY(), b.getY()) + 2, Math.max(a.getZ(), b.getZ()) + 2);
    }

    /** Removes the board's frames, including any copy reloaded from disk since it was spawned. */
    private void clearFrames() {
        for (int i = 0; i < 9; i++) {
            if (frames[i] != null) {
                frames[i].discard();
            }
        }
        if (cellBlocks[0] != null) {
            for (Entity frame : level.getEntities((Entity) null, boardBox(), e -> e instanceof ItemFrame)) {
                frame.discard();
            }
        }
    }

    /** make_x_map / make_o_map: a locked map holding the captured 128x128 design. */
    private MapId makeMap(String file) {
        byte[] pixels = readMap(file);
        MapId id = level.getFreeMapId();
        MapItemSavedData data = MapItemSavedData.createFresh(1_000_000, 1_000_000, (byte) 0, false, false, Level.OVERWORLD).locked();
        for (int i = 0; i < pixels.length; i++) {
            data.setColor(i % 128, i / 128, pixels[i]);
        }
        level.setMapData(id, data);
        return id;
    }

    private static byte[] readMap(String file) {
        String path = "/assets/rustclear/engine/puzzles/" + file;
        try (InputStream in = TicTacToePuzzle.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("No " + path);
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length != 128 * 128) {
                throw new IOException(path + " isn't a 128x128 map");
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("Rustclear's dungeon data is missing from the jar", e);
        }
    }

    private void sendMaps(List<ServerPlayer> players) {
        for (MapId id : new MapId[] {xMap, oMap}) {
            MapItemSavedData data = level.getMapData(id);
            if (data == null) {
                continue;
            }
            var patch = new MapItemSavedData.MapPatch(0, 0, 128, 128, data.colors.clone());
            var packet = new ClientboundMapItemDataPacket(id, (byte) 0, true, Optional.of(List.of()), Optional.of(patch));
            for (ServerPlayer player : players) {
                player.connection.send(packet);
            }
        }
    }
}
