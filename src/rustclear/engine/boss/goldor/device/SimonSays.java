package net.rustclear.mod.engine.boss.goldor.device;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * Simon Says, section 1 (boss-p3-goldor.md §6.1). The start button (110,121,91), a 4x4 of lamps at
 * x 111, y 120-123, z 92-95 (obsidian off, sea lantern on) and the stone buttons Hypixel puts in
 * front of them at x 110 (face wall, facing west) while the device takes presses. Cells are 0-15:
 * row from the top times 4 plus the column (z 92 to 95).
 *
 * The start button is a start/reset toggle. An accepted press while stopped starts it: a new
 * sequence, and a round task 5 ticks later. An accepted press while started resets it: round 0,
 * lamps and buttons cleared a tick later; the round tasks already due still run. A round task adds
 * a round and shows that many lamps; past round 4 (since Hypixel's 10-06 update, 5 before) it
 * completes the device, credited to the presser whose press it came from. Each player's start
 * presses pass a ~50 ms limiter.
 *
 * A show: lamps and buttons cleared, lamp j at +8j (the sequence's cell j as it is then) for
 * 8 ticks, its button gone while lit; every button back at +8n+10 (n lamps; first lamp +18 for a
 * one-lamp show, 134 of 134). Shows are never cancelled, so overlapping ones keep lighting.
 * Pressing the buttons in order: the round's last right press brings the next show 6 ticks later.
 * A wrong press takes the buttons away 3 ticks later and shows a new sequence about 26 later.
 */
final class SimonSays extends Device {
    static final BlockPos START = new BlockPos(110, 121, 91);
    /** Rounds since Hypixel's update (between 10-04 and 10-06); 5 before. */
    static final int ROUNDS = 4;
    /** An accepted start press acts 5 ticks later (133 of 134 lamp-on ticks during the spam). */
    static final int PRESS_DELAY = 5;
    /** Lamp j of a show lights at +8j and stays 8 ticks. */
    static final int LAMP_GAP = 8;
    /** The buttons come back 8n + 10 after an n-lamp show starts (first lamp +18 for n = 1). */
    static final int BUTTONS_AFTER = 10;
    /** A round's last right press to the next show. */
    static final int NEXT_ROUND = 6;
    /** A pressed grid button stays down 3 ticks. */
    static final int PRESS_DOWN = 3;
    /** A wrong press: buttons gone 3 ticks later. */
    static final int WRONG_CLEAR = 3;
    /**
     * ...and a new sequence's round-2 show this long after that (one recorded wrong press: 26, with a
     * stray one-lamp show 5 ticks before it, which isn't drawn here; the KB says ~25).
     */
    // OPEN: one measured wrong press; the stray lamp and the round shown afterwards are thin.
    static final int WRONG_RESHOW = 26;
    /**
     * A player's start press one tick after their last accepted one is dropped this often (the
     * ~50 ms limiter drops ~45% of same-player presses 1 tick apart; 2+ ticks never).
     */
    static final double LIMIT_DROP = 0.45;

    private boolean started;
    private int round;
    private final int[] sequence = new int[ROUNDS];
    private boolean accepting;
    private int next;
    private final boolean[] lit = new boolean[16];
    private final boolean[] up = new boolean[16];
    private final long[] downUntil = new long[16];
    private final Map<UUID, Long> lastStart = new HashMap<>();

    SimonSays(Host host) {
        super(host);
    }

    @Override
    public String label() {
        return "SS";
    }

    @Override
    public int section() {
        return 1;
    }

    static BlockPos lampAt(int cell) {
        return new BlockPos(111, 123 - cell / 4, 92 + cell % 4);
    }

    static BlockPos buttonAt(int cell) {
        return new BlockPos(110, 123 - cell / 4, 92 + cell % 4);
    }

    static int cellAt(BlockPos pos) {
        if (pos.getX() != 110 || pos.getY() < 120 || pos.getY() > 123 || pos.getZ() < 92 || pos.getZ() > 95) {
            return -1;
        }
        return (123 - pos.getY()) * 4 + pos.getZ() - 92;
    }

    private static BlockState button(boolean pressed) {
        return Blocks.STONE_BUTTON.defaultBlockState()
            .setValue(ButtonBlock.FACE, AttachFace.WALL)
            .setValue(ButtonBlock.FACING, Direction.WEST)
            .setValue(ButtonBlock.POWERED, pressed);
    }

    @Override
    public void start() {
        for (int c = 0; c < 16; c++) {
            lit[c] = true;
            setLamp(c, false);
            up[c] = true;
            setButton(c, false);
        }
    }

    @Override
    public boolean busy() {
        return started;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean useBlock(ServerPlayer player, BlockPos pos) {
        if (pos.equals(START)) {
            if (!host.live()) {
                return false;
            }
            startPress(player);
            return true;
        }
        int cell = cellAt(pos);
        if (cell < 0 || !up[cell] || !host.live()) {
            return false;
        }
        gridPress(player, cell);
        return true;
    }

    /** A left click on the start button counts as a press too (completions by left clicks seen). */
    @Override
    public boolean hitBlock(ServerPlayer player, BlockPos pos) {
        if (!pos.equals(START) || !host.live()) {
            return false;
        }
        startPress(player);
        return true;
    }

    private void startPress(ServerPlayer player) {
        net.rustclear.mod.engine.SimLog.event("ss_start_press", "started", started, "done", done());
        if (done()) {
            return;
        }
        long n = host.n();
        Long last = lastStart.get(player.getUUID());
        if (last != null && (n == last || n == last + 1 && host.random().nextDouble() < LIMIT_DROP)) {
            return;
        }
        lastStart.put(player.getUUID(), n);
        if (started) {
            // A reset: round 0 now, the board cleared a tick later. Tasks already due still run.
            started = false;
            round = 0;
            accepting = false;
            next = 0;
            host.later(1, this::clearBoard);
            host.ctx().level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 8f, 0f);
            return;
        }
        started = true;
        newSequence();
        host.later(PRESS_DELAY, () -> roundTask(player));
    }

    private void gridPress(ServerPlayer player, int cell) {
        long n = host.n();
        if (done() || downUntil[cell] > n) {
            return;
        }
        // Each press: a pling at the presser (BLOCKS, volume 8, pitch 4.047619), no button click.
        host.ctx().level().playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 8f, 4.047619f);
        downUntil[cell] = n + PRESS_DOWN;
        host.ctx().blocks().set(buttonAt(cell), button(true));
        host.later(PRESS_DOWN, () -> {
            if (up[cell]) {
                host.ctx().blocks().set(buttonAt(cell), button(false));
            }
        });
        if (!accepting) {
            return;
        }
        if (cell == sequence[next]) {
            next++;
            if (next >= round) {
                accepting = false;
                host.later(NEXT_ROUND, () -> roundTask(player));
            }
            return;
        }
        // Wrong: back to round 0 and stopped; the buttons go 3 ticks later, a new show after that.
        accepting = false;
        next = 0;
        started = false;
        round = 0;
        host.later(WRONG_CLEAR, () -> {
            for (int c = 0; c < 16; c++) {
                setButton(c, false);
            }
        });
        host.later(WRONG_CLEAR + WRONG_RESHOW, () -> {
            if (done()) {
                return;
            }
            newSequence();
            round = 1;
            roundTask(player);
        });
    }

    // ---------------------------------------------------------------- rounds and shows

    private void newSequence() {
        int[] cells = new int[16];
        for (int i = 0; i < 16; i++) {
            cells[i] = i;
        }
        for (int i = 0; i < ROUNDS; i++) {
            int j = i + host.random().nextInt(16 - i);
            int t = cells[i];
            cells[i] = cells[j];
            cells[j] = t;
            sequence[i] = cells[i];
        }
    }

    /** A round task: one more round shown, or past the last round the device is done. */
    private void roundTask(ServerPlayer by) {
        if (done()) {
            return;
        }
        round++;
        if (round > ROUNDS) {
            // Lamps and buttons cleared with the completion; shows already running carry on.
            clearBoard();
            complete(by, false);
            return;
        }
        show(round);
    }

    private void show(int lamps) {
        clearBoard();
        accepting = false;
        next = 0;
        for (int j = 0; j < lamps; j++) {
            int index = j;
            host.later((long) LAMP_GAP * j, () -> lightStep(index));
        }
        host.later((long) LAMP_GAP * lamps + BUTTONS_AFTER, this::buttonsBack);
    }

    /** Lamp [j] of a show: the sequence's cell j as it is now. A lamp already lit keeps its first timer. */
    private void lightStep(int j) {
        int cell = sequence[j];
        setButton(cell, false);
        if (lit[cell]) {
            return;
        }
        setLamp(cell, true);
        host.later(LAMP_GAP, () -> setLamp(cell, false));
    }

    /** Every button back but under lit lamps; with a round shown, presses count from now. */
    private void buttonsBack() {
        for (int c = 0; c < 16; c++) {
            if (!lit[c]) {
                setButton(c, true);
            }
        }
        if (round >= 1 && !accepting && !done()) {
            accepting = true;
            next = 0;
        }
    }

    private void clearBoard() {
        for (int c = 0; c < 16; c++) {
            setLamp(c, false);
            setButton(c, false);
        }
    }

    private void setLamp(int cell, boolean on) {
        if (lit[cell] == on) {
            return;
        }
        lit[cell] = on;
        host.ctx().blocks().set(lampAt(cell), on ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.OBSIDIAN.defaultBlockState());
    }

    private void setButton(int cell, boolean show) {
        if (up[cell] == show) {
            return;
        }
        up[cell] = show;
        host.ctx().blocks().set(buttonAt(cell), show ? button(false) : Blocks.AIR.defaultBlockState());
    }

    /** Start it, then press each round's lamps in order, a press every 5 ticks. */
    // OPEN: the press pace (5 ticks) and the wait before restarting (40) are guesses, not measured.

}
