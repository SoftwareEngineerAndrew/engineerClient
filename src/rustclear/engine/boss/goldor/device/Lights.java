package net.rustclear.mod.engine.boss.goldor.device;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * Lights, section 2 (boss-p3-goldor.md §6.2): 20 levers over 20 redstone lamps ([LightsBoard]).
 * Parties do it before P3, so it starts with every lamp lit (36 of 36 audit runs) and one lever
 * click finishes it. A right click plays the lever's click at once; a tick later the lever flips,
 * lamps it lights come on with it and lamps it darkens go out 3 ticks after, and the device is
 * done if every lamp was lit before the flip or is after it (so a click that turns a lever off
 * still counts). A left click flips nothing but is processed twice: done, announced twice.
 */
final class Lights extends Device {
    /** Click to the lever's flip and the credit line (one recorded S2 click: sound 212, lever and chat 213). */
    static final int FLIP_DELAY = 1;
    /** A lamp goes dark this long after its lever (3 usually, 4 in about 1 of 6: the median). */
    static final int LAMP_OFF_DELAY = 3;

    private final LightsBoard board = new LightsBoard();

    Lights(Host host) {
        super(host);
    }

    @Override
    public String label() {
        return "Lights";
    }

    @Override
    public int section() {
        return 2;
    }

    @Override
    public void start() {
        board.solve();
        for (int x = LightsBoard.X0; x <= LightsBoard.X1; x++) {
            for (int y = LightsBoard.Y0; y <= LightsBoard.Y1; y++) {
                setLever(x, y);
                setLamp(x, y, board.lamp(x, y));
            }
        }
    }

    @Override
    public boolean useBlock(ServerPlayer player, BlockPos pos) {
        if (!LightsBoard.isLever(pos.getX(), pos.getY(), pos.getZ()) || !host.live()) {
            return false;
        }
        if (done()) {
            return true;
        }
        int x = pos.getX();
        int y = pos.getY();
        // The click is heard at once (lever.click, BLOCKS, 0.3; pitch 37/63 turning on, 31/63 off).
        boolean turningOn = !board.lever(x, y);
        host.ctx().level().playSound(null, x + 0.5, y + 0.5, LightsBoard.Z_LEVER + 0.5, SoundEvents.LEVER_CLICK,
            SoundSource.BLOCKS, 0.3f, turningOn ? 0.5873016f : 0.4920635f);
        host.later(FLIP_DELAY, () -> flip(player, x, y));
        return true;
    }

    @Override
    public boolean hitBlock(ServerPlayer player, BlockPos pos) {
        if (!LightsBoard.isLever(pos.getX(), pos.getY(), pos.getZ()) || !host.live()) {
            return false;
        }
        // OPEN: what a left click does to the lever itself isn't recorded; two flips leave it as it was.
        host.later(FLIP_DELAY, () -> {
            if (!done() && board.allLit()) {
                complete(player, true);
            }
        });
        return true;
    }

    private void flip(ServerPlayer player, int x, int y) {
        if (done()) {
            return;
        }
        boolean wasLit = board.allLit();
        boolean[][] before = lamps();
        board.toggle(x, y);
        setLever(x, y);
        for (int lx = LightsBoard.X0; lx <= LightsBoard.X1; lx++) {
            for (int ly = LightsBoard.Y0; ly <= LightsBoard.Y1; ly++) {
                boolean now = board.lamp(lx, ly);
                if (now == before[lx - LightsBoard.X0][ly - LightsBoard.Y0]) {
                    continue;
                }
                if (now) {
                    setLamp(lx, ly, true);
                } else {
                    int fx = lx;
                    int fy = ly;
                    host.later(LAMP_OFF_DELAY, () -> {
                        if (!board.lamp(fx, fy)) {
                            setLamp(fx, fy, false);
                        }
                    });
                }
            }
        }
        if (wasLit || board.allLit()) {
            complete(player, false);
        }
    }

    private boolean[][] lamps() {
        boolean[][] out = new boolean[LightsBoard.X1 - LightsBoard.X0 + 1][LightsBoard.Y1 - LightsBoard.Y0 + 1];
        for (int x = LightsBoard.X0; x <= LightsBoard.X1; x++) {
            for (int y = LightsBoard.Y0; y <= LightsBoard.Y1; y++) {
                out[x - LightsBoard.X0][y - LightsBoard.Y0] = board.lamp(x, y);
            }
        }
        return out;
    }

    private void setLever(int x, int y) {
        BlockPos pos = new BlockPos(x, y, LightsBoard.Z_LEVER);
        BlockState st = host.ctx().blocks().get(pos);
        if (!st.is(Blocks.LEVER)) {
            // Hypixel's: lever[face=wall, facing=north].
            st = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL)
                .setValue(LeverBlock.FACING, Direction.NORTH);
        }
        host.ctx().blocks().set(pos, st.setValue(LeverBlock.POWERED, board.lever(x, y)));
    }

    private void setLamp(int x, int y, boolean on) {
        host.ctx().blocks().set(new BlockPos(x, y, LightsBoard.Z_LAMP),
            Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, on));
    }

    /** Light every lamp (levers toward [LightsBoard.SOLVED]), then one more click: a click every 4 ticks. */
    // OPEN: the 4-tick pace is a guess.

}
