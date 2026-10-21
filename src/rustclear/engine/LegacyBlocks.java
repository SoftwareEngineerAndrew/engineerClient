package net.rustclear.mod.engine;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.rustclear.mod.RustclearMod;

/**
 * Turns 1.8 block ids (id << 4 | meta), which is what all of Rustclear's room data is, into
 * today's blocks, with the same tables Minecraft uses to upgrade a 1.12 world: the 1.13
 * flattening, then every block fix since. Each id is worked out once.
 *
 * One instance is shared ([shared]) and warmed on a background thread while the Rustclear world
 * is being created ([warm]). The cache is written without locking: two threads converting the
 * same id get the same BlockState, so the worst a race costs is converting it twice.
 */
public final class LegacyBlocks {
    /** The data version the flattening's output is in (ChunkPalettedStorageFix). */
    private static final int FLATTENED_VERSION = 1466;

    private static LegacyBlocks shared;

    private final HolderGetter<Block> blocks;
    private boolean warmed;
    /** Converted states, per [Facing] (index 0 is unturned, which is what [get] returns). */
    private final BlockState[][] cache = new BlockState[4][1 << 16];

    private LegacyBlocks(HolderGetter<Block> blocks) {
        this.blocks = blocks;
    }

    /** Blocks are built in, not data-driven, so one converter serves every world. */
    public static synchronized LegacyBlocks shared() {
        if (shared == null) {
            shared = new LegacyBlocks(BuiltInRegistries.BLOCK);
        }
        return shared;
    }

    public BlockState get(int legacy) {
        legacy &= 0xFFFF;
        BlockState state = cache[0][legacy];
        if (state == null) {
            state = convert(legacy);
            cache[0][legacy] = state;
        }
        return state;
    }

    /** [get] turned by [facing], worked out once per id and facing. */
    public BlockState get(int legacy, Facing facing) {
        legacy &= 0xFFFF;
        BlockState[] turned = cache[facing.ordinal()];
        BlockState state = turned[legacy];
        if (state == null) {
            state = get(legacy).rotate(facing.rotation());
            turned[legacy] = state;
        }
        return state;
    }

    /**
     * Converts every id the rooms and doors use, once, so the first dungeon doesn't pay for the
     * data fixer block by block. A second caller waits for the first to finish.
     */
    public synchronized void warm(RoomStore store) {
        if (warmed) {
            return;
        }
        for (RoomData room : store.rooms) {
            for (short id : room.blocks()) {
                get(id);
            }
        }
        for (short[] pattern : store.doorPatterns) {
            for (short id : pattern) {
                get(id);
            }
        }
        warmed = true;
    }

    private BlockState convert(int legacy) {
        if (legacy == 0) {
            return Blocks.AIR.defaultBlockState();
        }
        if (legacy >> 4 == SKULL) {
            return skull(legacy & 7);
        }
        try {
            Dynamic<?> flattened = BlockStateData.getTag(legacy);
            int current = SharedConstants.getCurrentVersion().dataVersion().version();
            Dynamic<?> upgraded = DataFixers.getDataFixer().update(References.BLOCK_STATE, flattened, FLATTENED_VERSION, current);
            if (toNbt(upgraded) instanceof CompoundTag tag) {
                return NbtUtils.readBlockState(blocks, tag);
            }
        } catch (RuntimeException e) {
            RustclearMod.LOGGER.warn("Can't convert 1.8 block {}:{}: {}", legacy >> 4, legacy & 15, e.toString());
        }
        return Blocks.AIR.defaultBlockState();
    }

    /** 1.8's skull block id. */
    private static final int SKULL = 144;

    /**
     * A skull's kind lives in its tile entity, which the flattening table can't see (it names
     * them all "%%FILTER_ME%%", so they'd turn to air). Hypixel's are player heads. Meta 1 is a
     * floor skull, 2-5 a wall skull facing north, south, west, east.
     */
    private static BlockState skull(int meta) {
        Direction facing = switch (meta) {
            case 2 -> Direction.NORTH;
            case 3 -> Direction.SOUTH;
            case 4 -> Direction.WEST;
            case 5 -> Direction.EAST;
            default -> null;
        };
        if (facing == null) {
            return Blocks.PLAYER_HEAD.defaultBlockState();
        }
        return Blocks.PLAYER_WALL_HEAD.defaultBlockState().setValue(WallSkullBlock.FACING, facing);
    }

    private static <T> Tag toNbt(Dynamic<T> dynamic) {
        return dynamic.convert(NbtOps.INSTANCE).getValue();
    }
}
