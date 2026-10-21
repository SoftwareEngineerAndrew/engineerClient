package net.rustclear.mod.engine;

import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Which blocks the movement abilities pass through, as the server's 1.8 block lists say, restated
 * for modern blocks (the rooms are 1.8 blocks turned modern, so these cover what can appear).
 */
public final class BlockRules {
    private BlockRules() {
    }

    /** Ether warp's VALID_ETHER_WARP_BLOCK_IDS: what the warp ray passes through and needs two of above the target. */
    public static boolean etherPassable(BlockState s) {
        if (s.isAir()) {
            return true;
        }
        Block b = s.getBlock();
        if (b == Blocks.WATER || b == Blocks.LAVA) {
            // Only still water (9) and lava (11) are on the list, not flowing (8, 10).
            return s.getFluidState().isSource();
        }
        return s.is(BlockTags.SAPLINGS) || s.is(BlockTags.SMALL_FLOWERS) || s.is(BlockTags.WOODEN_BUTTONS)
            || s.is(BlockTags.FLOWER_POTS) || s.is(BlockTags.WOOL_CARPETS)
            || b instanceof AbstractSkullBlock
            || b == Blocks.COBWEB || b == Blocks.SHORT_GRASS || b == Blocks.FERN || b == Blocks.DEAD_BUSH
            || b == Blocks.MOVING_PISTON || b == Blocks.BROWN_MUSHROOM || b == Blocks.RED_MUSHROOM
            || b == Blocks.TORCH || b == Blocks.WALL_TORCH || b == Blocks.FIRE || b == Blocks.REDSTONE_WIRE
            || b == Blocks.WHEAT || b == Blocks.LADDER || b == Blocks.RAIL || b == Blocks.LEVER
            || b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_WALL_TORCH || b == Blocks.STONE_BUTTON
            || b == Blocks.SNOW || b == Blocks.REPEATER || b == Blocks.PUMPKIN_STEM || b == Blocks.MELON_STEM
            || b == Blocks.ATTACHED_PUMPKIN_STEM || b == Blocks.ATTACHED_MELON_STEM || b == Blocks.VINE
            || b == Blocks.LILY_PAD || b == Blocks.NETHER_WART || b == Blocks.TRIPWIRE_HOOK || b == Blocks.TRIPWIRE
            || b == Blocks.CARROTS || b == Blocks.POTATOES || b == Blocks.COMPARATOR || b == Blocks.ACTIVATOR_RAIL
            || b == Blocks.SUNFLOWER || b == Blocks.LILAC || b == Blocks.TALL_GRASS || b == Blocks.LARGE_FERN
            || b == Blocks.ROSE_BUSH || b == Blocks.PEONY;
    }

    /**
     * Where a player's feet can be at ([x], [z]) starting from [y]: there if the player's hitbox is clear, else on top
     * of the block hitboxes it would be inside if that is at most [rise] higher and clear (a fence, wall or closed
     * gate is 1.5 tall), else NaN (a head, a ceiling). On SkyBlock 26.3 no teleport puts you inside a block any more
     * (the 1.8-era etherwarp onto fences, walls and skulls, and stair clips, are gone; the user, 2026-10-19).
     */
    public static double standY(Level level, double x, double y, double z, double rise) {
        double feet = y;
        for (int i = 0; i < 4; i++) {
            AABB box = new AABB(x - 0.3, feet, z - 0.3, x + 0.3, feet + 1.8, z + 0.3).deflate(1.0E-6);
            double top = Double.NEGATIVE_INFINITY;
            for (VoxelShape shape : level.getBlockCollisions(null, box)) {
                top = Math.max(top, shape.max(Direction.Axis.Y));
            }
            if (top == Double.NEGATIVE_INFINITY) {
                return feet;
            }
            if (top <= feet || top - y > rise + 1.0E-6) {
                return Double.NaN;
            }
            feet = top;
        }
        return Double.NaN;
    }

    /** is_passable_for_transmission: what Ether Transmission and Wither Impact travel through. */
    public static boolean transmissionPassable(BlockState s) {
        if (s.isAir()) {
            return true;
        }
        Block b = s.getBlock();
        if (s.is(BlockTags.TRAPDOORS)) {
            return s.getValue(BlockStateProperties.OPEN);
        }
        if (s.is(BlockTags.FENCE_GATES)) {
            // 1.8 only had oak as plain "fence_gate", which isn't on the list.
            return b != Blocks.OAK_FENCE_GATE && s.getValue(BlockStateProperties.OPEN);
        }
        return b == Blocks.WATER || b == Blocks.LAVA || b == Blocks.SHORT_GRASS || b == Blocks.FERN
            || b == Blocks.DEAD_BUSH || b == Blocks.TORCH || b == Blocks.WALL_TORCH || b == Blocks.REDSTONE_TORCH
            || b == Blocks.REDSTONE_WALL_TORCH || b == Blocks.REDSTONE_WIRE || s.is(BlockTags.SMALL_FLOWERS)
            || b == Blocks.VINE || b == Blocks.FIRE || b == Blocks.LILY_PAD || s.is(BlockTags.WOOL_CARPETS)
            || b == Blocks.SNOW || b instanceof AbstractSkullBlock || s.is(BlockTags.FLOWER_POTS)
            || b == Blocks.COMPARATOR || b == Blocks.REPEATER || b == Blocks.RAIL || b == Blocks.POWERED_RAIL
            || b == Blocks.DETECTOR_RAIL || b == Blocks.DAYLIGHT_DETECTOR || b == Blocks.LADDER;
    }
}
