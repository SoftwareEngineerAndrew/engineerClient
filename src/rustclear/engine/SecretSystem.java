package net.rustclear.mod.engine;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Secrets, lever-locked chests and wall levers for one run (secrets.rs, secrets_loader.rs,
 * locked_chests.rs, levers.rs and their block_interact_action.rs handlers).
 *
 * Chests and wither essence appear as blocks once a player comes near (or, for the "secret"
 * kinds, as soon as the room is entered); bats and the Spirit Leap item appear as entities. Each
 * one found counts toward its room's secrets, as on the server.
 */
public final class SecretSystem {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
    static final String WITHER_ESSENCE_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTYwMzYxMDQ0MzU4MywKICAicHJvZmlsZUlkIiA6ICIzM2ViZDMyYmIzMzk0YWQ5YWM2NzBjOTZjNTQ5YmE3ZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJEYW5ub0JhbmFubm9YRCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9lNDllYzdkODJiMTQxNWFjYWUyMDU5Zjc4Y2QxZDE3NTRiOWRlOWIxOGNhNTlmNjA5MDI0YzRhZjg0M2Q0ZDI0IgogICAgfQogIH0KfQ==";
    /** The Note Block harp run played when essence is collected, 5 ticks apart. */
    private static final float[] HARP = {0.7936508f, 0.8888889f, 1.0f, 1.0952381f, 1.1904762f};

    /** One secret (DungeonSecret). */
    static final class Secret {
        final RoomExtras.SecretKind kind;
        final RoomState room;
        final BlockPos pos;
        final Direction facing;
        final AABB box;
        boolean spawned;
        boolean obtained;
        Entity entity;
        long spawnTick;

        Secret(RoomExtras.SecretKind kind, RoomState room, BlockPos pos, Direction facing, double radius) {
            this.kind = kind;
            this.room = room;
            this.pos = pos;
            this.facing = facing;
            this.box = new AABB(pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius);
        }
    }

    /** A chest that only opens once its lever is pulled (LockedChestState). Counts as a secret. */
    private static final class LockedChest {
        final RoomState room;
        final BlockPos lever;
        boolean locked;
        boolean opened;

        LockedChest(RoomState room, BlockPos lever, boolean locked) {
            this.room = room;
            this.lever = lever;
            this.locked = locked;
        }
    }

    /** A lever that knocks out a wall (LeverData, in world positions). */
    private static final class WallLever {
        final List<BlockPos> blocks;
        boolean used;

        WallLever(List<BlockPos> blocks) {
            this.blocks = blocks;
        }
    }

    /** The floating, rising head left behind when essence is collected (EssenceEntityImpl). */
    private static final class Essence {
        final ArmorStand stand;
        int age;

        Essence(ArmorStand stand) {
            this.stand = stand;
        }
    }

    private final DungeonRun run;
    private final ServerLevel level;
    private final List<Secret> secrets = new ArrayList<>();
    private final Map<BlockPos, Secret> secretBlocks = new HashMap<>();
    private final Map<BlockPos, LockedChest> lockedChests = new HashMap<>();
    private final Map<BlockPos, WallLever> levers = new HashMap<>();
    private final List<Essence> essences = new ArrayList<>();
    private BlockPos mimicChest;
    private RoomState mimicRoom;
    /** Blocks this system placed, cleared again when the run is thrown away. */
    private final LongArrayList placed = new LongArrayList();

    /**
     * How near a player must be for a chest or essence head to appear (measured, 7 main runs, 124
     * chests: the nearest player, recorder or teammate, was a median 21 blocks away when one
     * appeared, p90 39, almost never past 44; checks run on the 20-tick grid from Mort). Chests
     * therefore load as you come up to their room, not when you reach them.
     */
    static final double APPEAR_RADIUS = 40.0;

    private static double radius(RoomExtras.SecretEntry e) {
        return switch (e.kind()) {
            case CHEST, ESSENCE -> APPEAR_RADIUS;
            default -> e.radius();
        };
    }

    SecretSystem(DungeonRun run, Random random) {
        this.run = run;
        this.level = run.level;
        RoomExtras extras = RoomExtras.get();
        for (RoomState room : run.rooms) {
            PlacedRoom placedRoom = room.placed;
            Facing turn = placedRoom.facing();
            for (RoomExtras.SecretEntry e : extras.secrets(room.data().name())) {
                BlockPos pos = placedRoom.toWorld(e.x(), e.y(), e.z());
                secrets.add(new Secret(e.kind(), room, pos, direction(e.facing().rotate(turn)), radius(e)));
            }
            for (RoomExtras.Lever l : extras.levers(room.data().name())) {
                List<BlockPos> blocks = new ArrayList<>();
                for (int[] b : l.blocks()) {
                    blocks.add(placedRoom.toWorld(b[0], b[1], b[2]));
                }
                levers.put(placedRoom.toWorld(l.x(), l.y(), l.z()), new WallLever(blocks));
            }
        }
        // Room::spawn_locked_chests: these are there from the start, each locked half the time.
        for (RoomState room : run.rooms) {
            for (RoomExtras.LockedChest c : extras.lockedChests(room.data().name())) {
                BlockPos pos = room.placed.toWorld(c.x(), c.y(), c.z());
                BlockPos lever = room.placed.toWorld(c.leverX(), c.leverY(), c.leverZ());
                placeChest(pos, direction(c.facing().rotate(room.placed.facing())));
                lockedChests.put(pos, new LockedChest(room, lever, random.nextDouble() < 0.5));
            }
        }
        // main.rs: one locked chest outside the trap rooms becomes the mimic's trapped chest.
        List<BlockPos> eligible = new ArrayList<>();
        for (Map.Entry<BlockPos, LockedChest> e : lockedChests.entrySet()) {
            if (e.getValue().room.type() != RoomType.TRAP) {
                eligible.add(e.getKey());
            }
        }
        eligible.sort(Comparator.comparingLong(BlockPos::asLong));
        if (!eligible.isEmpty()) {
            BlockPos pos = eligible.get(random.nextInt(eligible.size()));
            mimicRoom = lockedChests.remove(pos).room;
            BlockState chest = level.getBlockState(pos);
            Direction facing = chest.hasProperty(ChestBlock.FACING) ? chest.getValue(ChestBlock.FACING) : Direction.NORTH;
            level.setBlock(pos, Blocks.TRAPPED_CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), FLAGS);
            mimicChest = pos;
        }
    }

    // ---------------------------------------------------------------- appearing

    /** Dungeon::tick's room-entry hook: the "secret" chest and essence appear as the room is entered. */
    void roomEntered(RoomState room) {
        for (Secret secret : secrets) {
            if (secret.room == room && !secret.spawned && secret.kind.onEntry) {
                spawn(secret);
            }
        }
    }

    /**
     * Mort's first line: the rooms that load with the run (the entrance and the rooms next to it)
     * get their chests and heads at once, as Hypixel places a batch of chests, and every lever, on
     * that tick (92d73f: 20 chests and 13 levers at Mort +0, up to 105 blocks away).
     */
    void runStarted(RoomState entrance) {
        java.util.Set<RoomState> loaded = new java.util.HashSet<>();
        loaded.add(entrance);
        for (List<RoomState.Neighbour> list : entrance.neighbours) {
            for (RoomState.Neighbour n : list) {
                loaded.add(run.rooms.get(n.roomIndex()));
            }
        }
        for (Secret secret : secrets) {
            if (!secret.spawned && loaded.contains(secret.room) && secret.kind.isChestOrEssence()) {
                spawn(secret);
                secret.spawnTick = 0;
            }
        }
    }

    void tick(long runTicks) {
        // Every 20 ticks from Mort, what is near any player appears, whichever room they stand in.
        if (runTicks % 20 == 0) {
            for (ServerPlayer player : level.players()) {
                for (Secret secret : secrets) {
                    if (!secret.spawned && !secret.kind.onEntry
                        && player.getBoundingBox().intersects(secret.box)) {
                        spawn(secret);
                        secret.spawnTick = runTicks;
                    }
                }
            }
        }
        for (Secret secret : secrets) {
            if (secret.entity == null) {
                continue;
            }
            switch (secret.kind) {
                case BAT_DIE -> {
                    // A bat that dies on its own a moment after appearing.
                    if (runTicks >= secret.spawnTick + 5) {
                        level.playSound(null, secret.entity.getX(), secret.entity.getY(), secret.entity.getZ(),
                            SoundEvents.BAT_DEATH, SoundSource.NEUTRAL, 0.1f, 1f);
                        level.sendParticles(ParticleTypes.POOF, secret.entity.getX(), secret.entity.getY(), secret.entity.getZ(), 5, 0.1, 0.1, 0.1, 0.02);
                        dieQuietly(secret.entity);
                        secret.entity = null;
                        found(secret);
                    }
                }
                case BAT_SPAWN -> {
                    // Counts once a player kills it.
                    if (!secret.entity.isAlive()) {
                        secret.entity = null;
                        found(secret);
                        run.batSlain();
                    }
                }
                case ITEM -> tickItem(secret, runTicks);
                default -> {
                }
            }
        }
        for (Iterator<Essence> it = essences.iterator(); it.hasNext(); ) {
            Essence essence = it.next();
            ArmorStand stand = essence.stand;
            essence.age++;
            if (essence.age < 20) {
                stand.setPos(stand.getX(), stand.getY() + 0.04, stand.getZ());
            }
            stand.setYRot(stand.getYRot() + 15f);
            if (essence.age % 5 == 0) {
                level.sendParticles(ParticleTypes.CLOUD, stand.getX(), stand.getY() + 1.5, stand.getZ(), 5, 0, 0, 0, 0.06);
            }
            if (essence.age >= 20) {
                level.playSound(null, stand.getX(), stand.getY(), stand.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1.5f);
                stand.discard();
                it.remove();
            }
        }
    }

    private void spawn(Secret secret) {
        secret.spawned = true;
        BlockPos pos = secret.pos;
        switch (secret.kind) {
            case CHEST, SECRET_CHEST -> {
                placeChest(pos, secret.facing);
                secretBlocks.put(pos, secret);
                level.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 1, 0, 0, 0, 0);
            }
            case ESSENCE, SECRET_ESSENCE -> {
                placeSkull(pos, WITHER_ESSENCE_TEXTURE);
                secretBlocks.put(pos, secret);
            }
            case BAT_SPAWN, BAT_DIE -> {
                Bat bat = new Bat(EntityTypes.BAT, level);
                bat.snapTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0f, 0f);
                // The server's bats hover where they appear; one hit kills them.
                bat.setNoAi(true);
                bat.setNoGravity(true);
                // Hypixel's secret bats have 100 max health; Devonian tells them from other bats by it.
                bat.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
                bat.setHealth(1f);
                level.addFreshEntity(bat);
                secret.entity = bat;
            }
            case ITEM -> {
                ItemEntity item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, spiritLeap(), 0, 0, 0);
                item.setNeverPickUp();
                item.setUnlimitedLifetime();
                level.addFreshEntity(item);
                secret.entity = item;
            }
        }
    }

    /** SecretItemEntityImpl: walking within 3 blocks of it collects it, 5 ticks after it appears (SkyBlock 0.27.2; was 11). */
    private void tickItem(Secret secret, long runTicks) {
        ItemEntity item = (ItemEntity) secret.entity;
        // Timed from the spawn tick: setUnlimitedLifetime() freezes the item's own age at -32768.
        if (runTicks < secret.spawnTick + 5) {
            return;
        }
        AABB area = item.getBoundingBox().inflate(3.0);
        for (ServerPlayer player : level.players()) {
            if (Ghosts.isGhost(player)) {
                continue; // a ghost picks nothing up
            }
            if (player.getBoundingBox().intersects(area)) {
                player.take(item, 1);
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f, 1.7619047f);
                item.discard();
                secret.entity = null;
                found(secret);
                return;
            }
        }
    }

    /** A lever-locked chest: where, its lever, and whether it is still locked. */
    record Locked(BlockPos chest, BlockPos lever, boolean locked) {}

    List<Locked> lockedLeft(RoomState room) {
        List<Locked> out = new ArrayList<>();
        for (Map.Entry<BlockPos, LockedChest> e : lockedChests.entrySet()) {
            LockedChest c = e.getValue();
            if (c.room == room && !c.opened) {
                out.add(new Locked(e.getKey(), c.lever, c.locked));
            }
        }
        return out;
    }

    List<Secret> left(RoomState room) {
        List<Secret> out = new ArrayList<>();
        for (Secret s : secrets) {
            if (s.room == room && !s.obtained) {
                out.add(s);
            }
        }
        return out;
    }

    /** Hyperion's implosion: secret bats inside [box] die and count (handle_hyperion_explosion). */
    void killBats(AABB box) {
        for (Secret secret : secrets) {
            if (secret.entity instanceof Bat bat && bat.isAlive() && box.intersects(bat.getBoundingBox())) {
                level.playSound(null, bat.getX(), bat.getY(), bat.getZ(), SoundEvents.BAT_DEATH, SoundSource.NEUTRAL, 0.1f, 1f);
                dieQuietly(bat);
                secret.entity = null;
                found(secret);
            }
        }
    }

    /** Is [pos] one of the blocks this system answers clicks on (the Dungeonbreaker leaves them alone)? */
    boolean isInteractable(BlockPos pos) {
        return secretBlocks.containsKey(pos) || lockedChests.containsKey(pos) || isLever(pos) || pos.equals(mimicChest);
    }

    // ---------------------------------------------------------------- right clicks

    /** A right click on a block. Returns true when it was one of ours (handled, nothing vanilla happens). */
    boolean useBlock(ServerPlayer player, BlockPos pos) {
        if (pos.equals(mimicChest)) {
            // BlockInteractAction::MimicChest: the chest goes, the Mimic comes out, and it counts as a secret.
            mimicChest = null;
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
            run.mobs().spawnMimic(mimicRoom, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            run.foundSecret(mimicRoom);
            return true;
        }
        LockedChest locked = lockedChests.get(pos);
        if (locked != null) {
            if (locked.locked) {
                player.sendSystemMessage(EngineItems.text("§cThat chest is locked!"));
            } else if (!locked.opened) {
                locked.opened = true;
                openChest(pos);
                run.foundSecret(locked.room);
                chestBlessing(player);
            }
            return true;
        }
        Secret secret = secretBlocks.get(pos);
        if (secret != null) {
            if (secret.kind.isChest()) {
                if (!secret.obtained) {
                    openChest(pos);
                    found(secret);
                    chestBlessing(player);
                    if (isTrapCompletion(secret)) {
                        secret.room.trapCompleted = true;
                        run.map.drawRoom(run.rooms, run.doors, secret.room.index);
                    }
                }
            } else if (!secret.obtained) {
                collectEssence(secret);
            }
            return true;
        }
        if (isLever(pos)) {
            pullLever(player, pos);
            return true;
        }
        return false;
    }

    private boolean isLever(BlockPos pos) {
        if (levers.containsKey(pos)) {
            return true;
        }
        for (LockedChest chest : lockedChests.values()) {
            if (chest.lever.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    private void pullLever(ServerPlayer player, BlockPos pos) {
        boolean unlocked = false;
        for (LockedChest chest : lockedChests.values()) {
            if (chest.lever.equals(pos) && chest.locked) {
                chest.locked = false;
                unlocked = true;
            }
        }
        WallLever lever = levers.get(pos);
        if (lever == null || lever.used) {
            if (unlocked) {
                level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3f, 0.8f);
            } else {
                level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3f, 0.49f);
                player.sendSystemMessage(EngineItems.text("§cThis lever has already been used."));
            }
            return;
        }
        lever.used = true;
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3f, 0.8f);
        run.sound(player, SoundEvents.ANVIL_BREAK, 1f, 1.7f);
        player.sendSystemMessage(EngineItems.text("§cYou hear the sound of something opening..."));
        for (BlockPos block : lever.blocks) {
            if (level.getBlockState(block).isAir()) {
                continue;
            }
            for (int i = 0; i < 8; i++) {
                run.schedule(i * 5, () -> level.playSound(null, block, SoundEvents.WOODEN_BUTTON_CLICK_ON, SoundSource.BLOCKS, 2f, 0.49f));
            }
            level.setBlock(block, Blocks.BARRIER.defaultBlockState(), FLAGS);
            run.schedule(20, () -> level.setBlock(block, Blocks.AIR.defaultBlockState(), FLAGS));
        }
    }

    /** A secret chest's blessing, if it holds one (Blessings: 39%, I or II, 23 ticks after the lid). */
    private void chestBlessing(ServerPlayer player) {
        java.util.Random random = run.random();
        if (random.nextDouble() >= Blessings.CHEST_CHANCE) {
            return;
        }
        Pickup.Kind kind = Blessings.chestKind(random);
        int lvl = Blessings.chestLevel(random);
        run.schedule(Blessings.CHEST_DELAY, () -> Blessings.grant(run, kind, lvl, player, -1));
    }

    private void openChest(BlockPos pos) {
        // BlockAction 1/1: the lid opens and stays open.
        level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, 1);
        level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.975f);
    }

    private void collectEssence(Secret secret) {
        BlockPos pos = secret.pos;
        for (int i = 0; i < HARP.length; i++) {
            float pitch = HARP[i];
            run.schedule(i * 5, () -> level.playSound(null, pos, SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, 1f, pitch));
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
        secretBlocks.remove(pos);

        ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        stand.snapTo(pos.getX() + 0.5, pos.getY() - 1.4, pos.getZ() + 0.5, 0f, 0f);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setPermanentlyInvulnerable(true);
        stand.setSilent(true);
        stand.setItemSlot(EquipmentSlot.HEAD, EngineItems.head(WITHER_ESSENCE_TEXTURE, null));
        level.addFreshEntity(stand);
        essences.add(new Essence(stand));

        run.broadcast("§fYou found a §r§dWither Essence§r§f! Everyone gains an extra essence!");
        found(secret);
    }

    private void found(Secret secret) {
        if (secret.obtained) {
            return;
        }
        secret.obtained = true;
        run.foundSecret(secret.room);
        // Who got it: the nearest player (scripted runs score each role's secrets).
        ServerPlayer nearest = null;
        net.minecraft.world.phys.Vec3 at = net.minecraft.world.phys.Vec3.atCenterOf(secret.pos);
        for (ServerPlayer player : level.players()) {
            if (nearest == null || player.distanceToSqr(at) < nearest.distanceToSqr(at)) {
                nearest = player;
            }
        }
        // checked: the room already had its checkmark (the party rule: checkmark before secrets); ghost: a ghost took it.
        SimLog.event("secret_found", "room", secret.room.data().name(), "kind", secret.kind.name(),
            "who", nearest == null ? "" : nearest.getName().getString(), "checked", secret.room.cleared() || isTrapCompletion(secret),
            "ghost", nearest != null && Ghosts.isGhost(nearest));
    }

    /** Old Trap's and New Trap's one chest whose opening marks the room cleared (trap_completion_secret_relative_pos). */
    private static boolean isTrapCompletion(Secret secret) {
        PlacedRoom room = secret.room.placed;
        return switch (room.data().name()) {
            case "Old Trap" -> secret.pos.equals(room.toWorld(4, 71, 9));
            case "New Trap" -> secret.pos.equals(room.toWorld(26, 90, 14));
            default -> false;
        };
    }

    // ---------------------------------------------------------------- blocks and items

    private void placeChest(BlockPos pos, Direction facing) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), FLAGS);
        placed.add(pos.asLong());
    }

    /** Plays a bat's death on clients (entity event 3, which mods watch for) and removes it, with no drops. */
    private void dieQuietly(Entity entity) {
        level.broadcastEntityEvent(entity, (byte) 3);
        entity.discard();
    }

    /** The owner Hypixel's Wither Essence skulls carry, which secret trackers match on. */
    private static final UUID ESSENCE_SKULL_ID = UUID.fromString("2865274b-3097-394e-8149-ec629c72d850");

    private void placeSkull(BlockPos pos, String texture) {
        BlockState state = Blocks.PLAYER_HEAD.defaultBlockState().setValue(SkullBlock.ROTATION, 0);
        level.setBlock(pos, state, FLAGS);
        placed.add(pos.asLong());
        if (level.getBlockEntity(pos) instanceof SkullBlockEntity skull) {
            ResolvableProfile profile = ResolvableProfile.createResolved(EngineItems.profile(ESSENCE_SKULL_ID, "essence", texture, null));
            skull.applyComponents(DataComponentMap.builder().set(DataComponents.PROFILE, profile).build(), DataComponentPatch.EMPTY);
            skull.setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** create_spirit_leap_item. */
    private static ItemStack spiritLeap() {
        ItemStack stack = EngineItems.named(Items.ENDER_PEARL, "§aSpirit Leap",
            "§8Brewing Ingredient", "", "§7Teleports you forward in the", "§7direction you are looking.", "",
            "§cDungeons only!", "", "§a§lUNCOMMON DUNGEON ITEM");
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "SPIRIT_LEAP");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static Direction direction(Facing facing) {
        return switch (facing) {
            case NORTH -> Direction.NORTH;
            case EAST -> Direction.EAST;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
        };
    }

    /** Removes everything this system put in the world. */
    void dispose() {
        for (Secret secret : secrets) {
            if (secret.entity != null) {
                secret.entity.discard();
            }
        }
        essences.forEach(e -> e.stand.discard());
        essences.clear();
        for (int i = 0; i < placed.size(); i++) {
            level.setBlock(BlockPos.of(placed.getLong(i)), Blocks.AIR.defaultBlockState(), FLAGS);
        }
        placed.clear();
    }
}
