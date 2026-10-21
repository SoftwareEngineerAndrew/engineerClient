package net.rustclear.mod.engine;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The Redstone Key secret in the Redstone Key, Golden Oasis and Redstone Crypt rooms (room.rs
 * load_into_world's skull placement, block_interact_action.rs RedstoneKeySkull and the redstone
 * block handling in packet_handling.rs's block placement).
 *
 * A key skull sits in each of those rooms. Clicking it gives the player the key (a flag, not an
 * inventory item, as on the server). Right-clicking the room's redstone block while holding it puts
 * the skull on the clicked face, uses the key up, counts a secret and opens the room's hidden part:
 * a wall and a secret chest (Redstone Key), a wall and two secret chests (Golden Oasis), or a wall
 * (Redstone Crypt, whose placed skull can be picked up again).
 */
final class RedstoneKey {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
    /** DungeonSecret::create_redstone_key_skull_nbt. */
    private static final String TEXTURE = "eyJ0aW1lc3RhbXAiOjE1NTk1ODAzNjI1NTMsInByb2ZpbGVJZCI6ImU3NmYwZDlhZjc4MjQyYzM5NDY2ZDY3MjE3MzBmNDUzIiwicHJvZmlsZU5hbWUiOiJLbGxscmFoIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yZjI0ZWQ2ODc1MzA0ZmE0YTFmMGM3ODViMmNiNmE2YTcyNTYzZTlmM2UyNGVhNTVlMTgxNzg0NTIxMTlhYTY2In19fQ==";
    private static final String SIGNATURE = "dYEJC8GTGdDESqHrQn22ShF5sJWO7u3jpG2hKPSD9Yords2BsESC3RdrImpeMMyD9oS4INbtsDPAPOoev9wrQS3JTkJWjHdgrwd33UqL9IHvQOmqKLAX5gLIeNNzJ3djG23oVsQ6JuW/OfnhbwpSxFNNzfwwtOjzDaiS4LLCqvkdQziUCTdfuBbvSaI6Jae0HBk2qXIHJ9Kjr9sSmcFhaDXLXj/lhkdxXCCGD+5XAxhR47ORnBT2qhHlZdK3bvNts41Kk6qC6Gzz7JdpGZPhnGCkK4FZzr/MDYObQuWmCOJQtI4QavjKOqO97AcY8IKyyMgkAJHRyqlO+8Y1sCwA7Fl5vB1lF9gUscVKLNRrT46Skg9lPWjIl3xAfEHdWt0HyU4GJb6tBPP/b2qm5vOAQ9JnaJuMiJm3ISfocz+NxlpmUli/vrsG79wwB4hT1wJAUfLwoi+Z0y0oG+FP45yAnIO3ORA/WjiqfnWu76kPaSenUEMV81IQtAJB835fzV7VLZWR5EkN/knuMWPuAGz0kdG1Raevi7yJC3wkmIRs1B523IB7Reuq14IgFXGw1J1i4Df00ULTkdWgMtPSWOlKGoT7iEBmYtnvPKQ3ZkzkoG9HZOq+JN5UpacfipmR5kI16vKYjRGKThDokifn6PL9Lfo0DYtJb+96/sp2gf6VD4o=";

    /** A chest the key opened up (a DungeonSecret chest spawned already, not yet obtained). */
    private static final class KeyChest {
        final RoomState room;
        boolean obtained;

        KeyChest(RoomState room) {
            this.room = room;
        }
    }

    private final DungeonRun run;
    private final ServerLevel level;
    /** Players holding a key (Player::has_redstone_key); a new run starts with none (dungeon_switch.rs). */
    private final Set<UUID> holders = new HashSet<>();
    /** Key skulls that can be picked up (BlockInteractAction::RedstoneKeySkull). */
    private final Map<BlockPos, RoomState> skulls = new HashMap<>();
    private final Map<BlockPos, KeyChest> chests = new HashMap<>();
    /** Blocks this placed, cleared again when the run is thrown away. */
    private final LongArrayList placed = new LongArrayList();

    RedstoneKey(DungeonRun run) {
        this.run = run;
        this.level = run.level;
    }

    /** load_into_world's special placements: the key skull in each room that has one. */
    void setup() {
        for (RoomState room : run.rooms) {
            BlockPos pos = originalSkull(room);
            if (pos != null) {
                placeSkull(pos, Direction.UP);
                skulls.put(pos, room);
            }
        }
    }

    /** Where [room]'s key skull starts, or null when the room has none. */
    private static BlockPos originalSkull(RoomState room) {
        PlacedRoom placedRoom = room.placed;
        return switch (room.data().name()) {
            // Facing north/south it is at 19/66/7, east/west at 10/70/26 (both then turned with the room).
            case "Redstone Key" -> placedRoom.facing() == Facing.NORTH || placedRoom.facing() == Facing.SOUTH
                ? placedRoom.toWorld(19, 66, 7) : placedRoom.toWorld(10, 70, 26);
            // Not turned with the room, as on the server.
            case "Golden Oasis" -> unturned(placedRoom, 12, 71, 8);
            case "Redstone Crypt" -> placedRoom.toWorld(4, 71, 4);
            default -> null;
        };
    }

    boolean isInteractable(BlockPos pos) {
        return skulls.containsKey(pos) || chests.containsKey(pos);
    }

    /** A right click on [pos]'s [face]. Returns true when it was ours. */
    boolean useBlock(ServerPlayer player, BlockPos pos, Direction face) {
        if (skulls.containsKey(pos)) {
            pickUp(player, pos);
            return true;
        }
        KeyChest chest = chests.get(pos);
        if (chest != null) {
            if (!chest.obtained) {
                chest.obtained = true;
                level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, 1);
                level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.975f);
                run.foundSecret(chest.room);
            }
            return true;
        }
        if (!holders.contains(player.getUUID()) || face == null) {
            return false;
        }
        int index = run.roomAt(pos.getX(), pos.getZ());
        if (index < 0) {
            return false;
        }
        RoomState room = run.rooms.get(index);
        PlacedRoom placedRoom = room.placed;
        BlockPos redstone = switch (room.data().name()) {
            case "Redstone Key" -> placedRoom.toWorld(27, 68, 7);
            case "Golden Oasis" -> unturned(placedRoom, 10, 71, 3);
            case "Redstone Crypt" -> placedRoom.toWorld(26, 72, 6);
            default -> null;
        };
        if (redstone == null || !redstone.equals(pos) || !level.getBlockState(pos).is(Blocks.REDSTONE_BLOCK)) {
            return false;
        }
        insert(player, room, pos, face);
        return true;
    }

    /** BlockInteractAction::RedstoneKeySkull. */
    private void pickUp(ServerPlayer player, BlockPos pos) {
        if (holders.contains(player.getUUID())) {
            return; // one key at a time
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
        skulls.remove(pos);
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 1f, 1f);
        holders.add(player.getUUID());
        player.sendSystemMessage(EngineItems.text("§r§aYou found a Secret Redstone Key!§r"));
    }

    /** packet_handling.rs: the key goes onto the redstone block and the room opens up. */
    private void insert(ServerPlayer player, RoomState room, BlockPos redstone, Direction face) {
        PlacedRoom placedRoom = room.placed;
        level.playSound(null, redstone.getX() + 0.5, redstone.getY() + 0.5, redstone.getZ() + 0.5,
            SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1f, 1f);
        BlockPos skull = redstone.relative(face);
        placeSkull(skull, face);
        switch (room.data().name()) {
            case "Redstone Key" -> {
                for (int x = 18; x <= 21; x++) {
                    air(placedRoom.toWorld(x, 68, 23));
                }
                BlockPos chest = placedRoom.toWorld(28, 62, 23);
                placeChest(chest, direction(Facing.WEST.rotate(placedRoom.facing())), room);
                run.foundSecret(room);
            }
            case "Golden Oasis" -> {
                for (int x = 26; x <= 28; x++) {
                    air(unturned(placedRoom, x, 70, 25));
                }
                for (int[] c : new int[][] {{13, 63, 25}, {12, 63, 23}}) {
                    BlockPos chest = unturned(placedRoom, c[0], c[1], c[2]);
                    int chestRoom = run.roomAt(chest.getX(), chest.getZ());
                    RoomState owner = chestRoom >= 0 ? run.rooms.get(chestRoom) : room;
                    placeChest(chest, Direction.EAST, owner);
                    if (chestRoom >= 0) {
                        run.foundSecret(owner);
                    }
                }
            }
            case "Redstone Crypt" -> {
                // This one's placed skull is a key skull again.
                skulls.put(skull, room);
                int[][] wall = {{12, 70, 15}, {13, 70, 15}, {14, 70, 15}, {15, 70, 15}, {13, 69, 15}, {14, 69, 15}, {15, 69, 15}};
                for (int[] b : wall) {
                    air(placedRoom.toWorld(b[0], b[1], b[2]));
                }
                run.foundSecret(room);
            }
            default -> {
            }
        }
        holders.remove(player.getUUID());
        // The room's original skull can't be picked up any more.
        BlockPos original = originalSkull(room);
        if (original != null && !original.equals(skull)) {
            skulls.remove(original);
        }
        player.sendSystemMessage(EngineItems.text("§r§7§oYou hear something open...§r"));
    }

    // ---------------------------------------------------------------- blocks

    private static BlockPos unturned(PlacedRoom room, int x, int y, int z) {
        return new BlockPos(room.cornerX() + x, y, room.cornerZ() + z);
    }

    private void air(BlockPos pos) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
    }

    private void placeChest(BlockPos pos, Direction facing, RoomState room) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), FLAGS);
        placed.add(pos.asLong());
        chests.put(pos, new KeyChest(room));
    }

    /** Blocks::Skull with the key's texture: on the floor for up (and down), on the wall otherwise. */
    private void placeSkull(BlockPos pos, Direction face) {
        BlockState state = face.getAxis().isHorizontal()
            ? Blocks.PLAYER_WALL_HEAD.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, face)
            : Blocks.PLAYER_HEAD.defaultBlockState().setValue(SkullBlock.ROTATION, 0);
        level.setBlock(pos, state, FLAGS);
        placed.add(pos.asLong());
        if (level.getBlockEntity(pos) instanceof SkullBlockEntity skull) {
            ResolvableProfile profile = ResolvableProfile.createResolved(EngineItems.profile(UUID.fromString("fed95410-aba1-39df-9b95-1d4f361eb66e"), "RedstoneKey", TEXTURE, SIGNATURE));
            skull.applyComponents(DataComponentMap.builder().set(DataComponents.PROFILE, profile).build(), DataComponentPatch.EMPTY);
            skull.setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    private static Direction direction(Facing facing) {
        return switch (facing) {
            case NORTH -> Direction.NORTH;
            case EAST -> Direction.EAST;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
        };
    }

    /** Removes what this put in the world. */
    void dispose() {
        for (int i = 0; i < placed.size(); i++) {
            level.setBlock(BlockPos.of(placed.getLong(i)), Blocks.AIR.defaultBlockState(), FLAGS);
        }
        placed.clear();
        skulls.clear();
        chests.clear();
        holders.clear();
    }
}
