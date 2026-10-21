package net.rustclear.mod.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A door's breaking animation as Hypixel plays it (clear-rooms-doors.md §4): on the "opened"
 * line's tick each block of the 3x3x4 core gets a falling-block copy riding an invisible bat,
 * 0.656 below it, which holds for 7 ticks, sinks 0.9375 every 3 ticks and is gone at 23
 * (Debris.DOOR). The real blocks go barrier a tick later and air 14 after that (DungeonRun.openDoor).
 */
final class DoorAnimation {
    private final ServerLevel level;
    private final Debris debris;

    DoorAnimation(ServerLevel level) {
        this.level = level;
        this.debris = new Debris(level);
    }

    /** Call before the door's middle is turned to barrier: a falling copy of each of its blocks. */
    void open(DoorState door) {
        int x = door.door.x();
        int z = door.door.z();
        for (int bx = x - 1; bx <= x + 1; bx++) {
            for (int y = 69; y <= 72; y++) {
                for (int bz = z - 1; bz <= z + 1; bz++) {
                    BlockPos pos = new BlockPos(bx, y, bz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || state.is(Blocks.BARRIER)) {
                        state = coreBlock(door.type());
                    }
                    if (state != null) {
                        debris.spawn(pos, state, Debris.DOOR_DROP, Debris.DOOR);
                    }
                }
            }
        }
    }

    /** DoorType::get_block: what the middle of each kind of door is made of. */
    private static BlockState coreBlock(Layout.DoorType type) {
        return switch (type) {
            case NORMAL -> null;
            case ENTRANCE -> Blocks.INFESTED_CHISELED_STONE_BRICKS.defaultBlockState();
            case WITHER -> Blocks.COAL_BLOCK.defaultBlockState();
            case BLOOD -> Blocks.DYED_TERRACOTTA.red().defaultBlockState();
        };
    }

    void tick() {
        debris.tick();
    }

    void dispose() {
        debris.dispose();
    }
}
