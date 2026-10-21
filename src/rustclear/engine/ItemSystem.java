package net.rustclear.mod.engine;

import it.unimi.dsi.fastutil.ints.IntList;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Rotations;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.rustclear.mod.mixin.ArmorStandAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What the items do (Smalldungeons items/): Aspect of the Void's Instant and Ether Transmission,
 * Hyperion's Wither Impact, Tactical Insertion, Ender Pearls that never run out, and the
 * Dungeonbreaker, Spirit Sceptre, Bonzo's Staff and the Jerry-chine Gun. Shortbows, bows and the
 * other dungeon items' abilities are GearSystem's, which uses the pieces here.
 */
public final class ItemSystem {
    public static final int MAX_CHARGES = 20;
    private static final int REGEN_TICKS = 221;
    private static final int WINDOW_RESTORE_TICKS = 41;
    private static final double MAX_REACH = 5.2;
    private static final int MESSAGE_THROTTLE_TICKS = 20;
    private static final String THAT_BLOCK = "§cA mystical force prevents you from digging that block!";
    /**
     * The refusal on a room's outer wall and past it (2026-10-19, 60 of 61 clear-phase lines in 60 recordings were
     * dug on the room's outer wall or the cell beyond it; "that block" is for the unbreakable blocks inside).
     */
    private static final String THERE = "§cA mystical force prevents you digging there!";
    /** The refusal anywhere in the Blood and Fairy rooms (the same recordings: every such line was dug in one). */
    private static final String THIS_ROOM = "§cA mystical force prevents you digging in this room!";
    private static final String NO_CHARGES = "§cYou don't have enough charges to break this block right now!";
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

    /** One player's Dungeonbreaker (BreakerState). */
    private static final class Breaker {
        int charges = MAX_CHARGES;
        long refillAt = -1;
        final ArrayDeque<BlockPos> window = new ArrayDeque<>();
        long lastMessage = Long.MIN_VALUE;
    }

    private record Broken(BlockPos pos, BlockState state, long brokenAt, long[] restoreAt) {}

    private final DungeonRun run;
    private final ServerLevel level;
    private final Random random = new Random();
    private final Map<UUID, Breaker> breakers = new HashMap<>();
    private final List<Broken> broken = new ArrayList<>();
    private final Map<UUID, Long> hyperionUsed = new HashMap<>();
    /** When each player's Wither Shield last went up, and Tactical Insertion was last used. */
    private final Map<UUID, Long> shieldUsed = new HashMap<>();
    private final Map<UUID, Long> tacticalUsed = new HashMap<>();
    /** The AOTV's Instant / Ether Transmission bases and the unexplained factor its measured costs need. */
    private static final double AOTV_BLINK = 45;
    private static final double AOTV_ETHER = 178;
    private static final double AOTV_FACTOR = 0.913;

    /** What an AOTV cast would cost [player] now (Instant or Ether Transmission), as the cast below charges it. */
    static double aotvCost(DungeonRun run, ServerPlayer player, net.minecraft.world.item.ItemStack aotv, boolean ether) {
        return run.combat().manaCost(player, (ether ? AOTV_ETHER : AOTV_BLINK) * AOTV_FACTOR, aotv);
    }
    /** Tactical Insertion's cooldown, 20 s (measured 19.85-20.85 s), shortened by a Mage's cooldown reduction. */
    private static final int TACTICAL_COOLDOWN = 400;
    private final List<SceptreBat> sceptreBats = new ArrayList<>();

    private record SceptreBat(Bat bat, UUID shooter) {}

    /**
     * A Bonzo balloon or Jerry-chine bullet: flies straight from [origin] along [dir], drawn by an invisible
     * marker stand, then bursts and boosts its shooter if they're close to where it burst.
     */
    private static final class Shot {
        final UUID shooter;
        final boolean bonzo;
        final Vec3 origin;
        final Vec3 dir;
        final ArmorStand stand;
        final boolean startsInside;
        int ticks;

        Shot(UUID shooter, boolean bonzo, Vec3 origin, Vec3 dir, ArmorStand stand, boolean startsInside) {
            this.shooter = shooter;
            this.bonzo = bonzo;
            this.origin = origin;
            this.dir = dir;
            this.stand = stand;
            this.startsInside = startsInside;
        }
    }

    private final List<Shot> shots = new ArrayList<>();
    private final Map<UUID, Long> bonzoShot = new HashMap<>();
    private final Map<UUID, long[]> jerryShots = new HashMap<>();
    private final Map<UUID, long[]> rapidFire = new HashMap<>();
    private static final double BONZO_SPEED = 0.75;
    /** About 127 blocks with no block in the way: the balloon goes without bursting. */
    private static final int BONZO_STEPS = 170;
    private static final int JERRY_LIFE = 30;
    private static final String[] BALLOON_HASHES = {
        "f1f99e7c394e13d199d1d22cb082d209068cd7a4de6ca09edff3fc61660ae5cf", "52dd11da04252f76b6934bc26612f54f264f30eed74df89941209e191bebc0a2",
        "7c6cc5aed49056977d89348978c0aaaaa54c968a3b411bbaba5c9970b405297", "7399ff9c40fdfb7b115addddff59c344eaa11acb0c64c6839b5f8407e36d239d",
        "a2dd1389a97fd4c86b11a1a2abad61389404d837d2caf097c91d27768363dc5a", "ac96d34a3cced4e4bba25119f74ea31f50f43f90cca2fbcbc6bae8625d60dede",
        "28c544b288f899d494f953ace7743a15fdb22d24f58f9ca298f35429fdc27b63", "cc5ec2dfbf2cf4e21bd011c784d9fc4ad7a88182bed27afd91b53b4513ce0aba",
        "3491c8fb426979da2d6327b3705a4cb9c91c2d5536223233addde21d5f58c940"};
    private static final String VILLAGER_HASH = "41b830eb4082acec836bc835e40a11282bb51193315f91184337e8d3555583";

    private static final int SCEPTRE_LIFETIME_TICKS = 40;

    ItemSystem(DungeonRun run) {
        this.run = run;
        this.level = run.level;
    }

    // ---------------------------------------------------------------- right clicks

    /** A right click holding [item]. Returns true when the item did its thing (nothing vanilla happens). */
    boolean rightClick(ServerPlayer player, EngineItem item) {
        switch (item) {
            case ASPECT_OF_THE_VOID -> {
                if (noItemTeleport(player)) {
                    return true;
                }
                // Instant Transmission 45 and Ether Transmission 178 Mana before Ultimate Wise; OPEN: the measured
                // costs (21/18 and 81/73 with Ultimate Wise V) also need a ~0.913 factor nobody has explained.
                boolean ether = player.isShiftKeyDown();
                double cost = run.combat().manaCost(player, (ether ? AOTV_ETHER : AOTV_BLINK) * AOTV_FACTOR, player.getMainHandItem());
                if (!run.combat().spend(player, ether ? "Ether Transmission" : "Instant Transmission", cost, null, null)) {
                    return true;
                }
                if (ether) {
                    etherWarp(player);
                } else {
                    Vec3 to = transmission(player, 12.0);
                    if (to != null) {
                        teleport(player, to.x, to.y, to.z);
                        // enderman.teleport HOSTILE 1.0/1.0 a tick later (abilities-items.md §10.3).
                        double x = to.x, y = to.y, z = to.z;
                        run.schedule(1, () -> level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1f, 1f));
                    }
                }
                return true;
            }
            case HYPERION -> {
                // Wither Impact's 300 Mana, 10% less outside combat (abilities-items.md §10.1).
                if (!run.combat().spend(player, "Wither Impact", run.combat().manaCost(player, 300, player.getMainHandItem()), null, null)) {
                    return true;
                }
                wither(player);
                return true;
            }
            case TACTICAL_INSERTION -> {
                if (run.inBoss()) {
                    player.sendSystemMessage(EngineItems.text("§cYou can't use the tactical insertion here!"));
                    return true;
                }
                if (!tacticalReady(player)) {
                    return true;
                }
                if (!run.combat().spend(player, "Gorilla Tactics", run.combat().manaCost(player, 200, player.getMainHandItem()), null, null)) {
                    return true;
                }
                tacticalUsed.put(player.getUUID(), level.getGameTime());
                tacticalInsertion(player);
                return true;
            }
            case ENDER_PEARL -> {
                if (run.inBoss()) {
                    // Refused in every boss phase, and one pearl is used up (pearls-teleports.md §6).
                    player.sendSystemMessage(EngineItems.text("§cA mystical force in this room prevents you from doing that!"));
                    player.getMainHandItem().shrink(1);
                    player.inventoryMenu.sendAllDataToRemote();
                    run.schedule(1, () -> refill(player, EngineItem.ENDER_PEARL));
                    return true;
                }
                if (noTeleportRoom(player)) {
                    // Cancelled before the pearl is thrown; resend the stack the client already took one from.
                    player.inventoryMenu.sendAllDataToRemote();
                    return true;
                }
                // The vanilla pearl flies; EnginePearls lands it the SkyBlock way. It never runs out or cools down.
                run.schedule(1, () -> refill(player, EngineItem.ENDER_PEARL));
                return false;
            }
            case DIAMOND_PICKAXE, DUNGEONBREAKER, GOLDEN_AXE, DEPTH_STRIDER_BOOTS -> {
                return false;
            }
            case SPIRIT_SCEPTRE -> {
                if (!run.combat().spend(player, "Guided Bat", run.combat().manaCost(player, 200, player.getMainHandItem()), null, null)) {
                    return true;
                }
                spiritSceptre(player);
                return true;
            }
            case TERMINATOR -> {
                run.gear().shoot(player, ItemDefs.get().def("TERMINATOR"), false);
                return true;
            }
            case SUPERBOOM_TNT -> {
                run.superboom().use(player, null);
                return true;
            }
            case BONZO_STAFF -> {
                if (!run.combat().spend(player, "Showtime", run.combat().manaCost(player, 90, player.getMainHandItem()), null, null)) {
                    return true;
                }
                bonzo(player);
                return true;
            }
            case JERRY_CHINE_GUN -> {
                // Each shot of a burst costs one more step than the last (lore: "+14 mana more than the
                // previous, resetting after 4s of not firing"): shot k is k x the 30 base's price.
                if (!run.combat().spend(player, "Rapid-fire", rapidFireCost(player), null, null)) {
                    return true;
                }
                jerry(player);
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    /** Whether Tactical Insertion is off cooldown; if not, Hypixel's line with the whole seconds left, rounded up. */
    private boolean tacticalReady(ServerPlayer player) {
        Long used = tacticalUsed.get(player.getUUID());
        double cdr = PlayerStats.of(player).classBonus.cooldownReduction();
        long left = used == null ? 0 : used + Math.round(TACTICAL_COOLDOWN * (1 - cdr)) - level.getGameTime();
        if (left <= 0) {
            return true;
        }
        player.sendSystemMessage(EngineItems.text("§cThis ability is on cooldown for " + (long) Math.ceil(left / 20.0) + "s."));
        return false;
    }

    /** As on Hypixel, teleport items don't work in the Teleport Maze and Boulder rooms. */
    boolean noTeleportRoom(ServerPlayer player) {
        int index = run.roomAt(player.getX(), player.getZ());
        if (index < 0) {
            return false;
        }
        String name = run.rooms.get(index).data().name();
        if (!name.equals("Teleport Maze") && !name.equals("Boulder")) {
            return false;
        }
        player.sendSystemMessage(EngineItems.styled("§cYou cannot teleport in this room!"));
        return true;
    }

    /**
     * The item teleports (AOTV/AOTE blinks and etherwarps, the Hyperion's Wither Impact jump, Sinrecall) are also
     * refused in the trap rooms, where the meta is ender pearls, which still fly there (the user, 2026-10-19).
     */
    boolean noItemTeleport(ServerPlayer player) {
        if (noTeleportRoom(player)) {
            return true;
        }
        int index = run.roomAt(player.getX(), player.getZ());
        if (index < 0 || run.rooms.get(index).type() != RoomType.TRAP) {
            return false;
        }
        // The recorded line (175 times in the recordings, red).
        player.sendSystemMessage(EngineItems.styled("§cYou cannot use abilities in this room!"));
        return true;
    }

    private void refill(ServerPlayer player, EngineItem item) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (EngineItem.of(stack) == item) {
                stack.setCount(item.count);
                player.getCooldowns().removeCooldown(player.getCooldowns().getCooldownGroup(stack));
            }
        }
        player.inventoryMenu.broadcastChanges();
    }

    /**
     * handle_teleport: steps a quarter block at a time along the look direction and stops in
     * front of the first spot that isn't two blocks of passable. Returns where the feet land, or null. A spot whose
     * hitbox would be inside a block (over a fence, wall, closed gate or iron bars) stands on top of it if that is
     * clear, and is skipped otherwise, so the player never ends up inside one.
     */
    Vec3 transmission(ServerPlayer player, double distance) {
        return transmissionFrom(level, player.getEyePosition(), player.getLookAngle(), distance);
    }

    static Vec3 transmissionFrom(net.minecraft.world.level.Level level, Vec3 eye, Vec3 look, double distance) {
        Vec3 dir = look.normalize();
        Vec3 current = eye;
        Vec3 step = dir.scale(0.25);
        int steps = (int) Math.ceil(distance / 0.25);
        Vec3 last = null;
        for (int i = 0; i < steps; i++) {
            current = current.add(step);
            BlockPos pos = BlockPos.containing(current);
            boolean bars = i == 0 && level.getBlockState(pos).is(Blocks.IRON_BARS);
            if (!bars && !(BlockRules.transmissionPassable(level.getBlockState(pos))
                && BlockRules.transmissionPassable(level.getBlockState(pos.above())))) {
                break;
            }
            double feet = BlockRules.standY(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.5);
            if (!Double.isNaN(feet)) {
                last = new Vec3(pos.getX() + 0.5, feet, pos.getZ() + 0.5);
            }
        }
        return last;
    }

    /** handle_ether_warp: a voxel walk up to 61 blocks; lands on the first solid block with two passable above it (not a head). */
    private void etherWarp(ServerPlayer player) {
        BlockPos land = etherLanding(level, player.getEyePosition(), player.getLookAngle());
        if (land != null) {
            double x = land.getX() + 0.5;
            double y = etherFeet(level, land);
            double z = land.getZ() + 0.5;
            level.sendParticles(ParticleTypes.WITCH, player.getX(), player.getY(), player.getZ(), 25, 0.25, 1.0, 0.25, 0);
            teleport(player, x, y, z);
            level.playSound(null, x, y, z, SoundEvents.ENDER_DRAGON_HURT, SoundSource.HOSTILE, 1f, 0.53968257f);
        }
    }

    /**
     * The feet height an etherwarp onto [land] puts you at: 1.05 over the block, or on top of a fence, wall or closed
     * gate (1.5 tall), never inside it; NaN if the player wouldn't fit there (a head or ceiling in the way).
     */
    static double etherFeet(net.minecraft.world.level.Level level, BlockPos land) {
        return BlockRules.standY(level, land.getX() + 0.5, land.getY() + 1.05, land.getZ() + 0.5, 0.5);
    }

    static BlockPos etherLanding(net.minecraft.world.level.Level level, Vec3 start, Vec3 look) {
        Vec3 end = start.add(look.normalize().scale(61.0));
        int x = (int) Math.floor(start.x);
        int y = (int) Math.floor(start.y);
        int z = (int) Math.floor(start.z);
        int endX = (int) Math.floor(end.x);
        int endY = (int) Math.floor(end.y);
        int endZ = (int) Math.floor(end.z);
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        int sx = (int) Math.signum(dx);
        int sy = (int) Math.signum(dy);
        int sz = (int) Math.signum(dz);
        double ix = dx != 0 ? 1.0 / dx : Double.MAX_VALUE;
        double iy = dy != 0 ? 1.0 / dy : Double.MAX_VALUE;
        double iz = dz != 0 ? 1.0 / dz : Double.MAX_VALUE;
        double tdx = Math.abs(ix * sx);
        double tdy = Math.abs(iy * sy);
        double tdz = Math.abs(iz * sz);
        double tmx = Math.abs((x + (sx > 0 ? 1 : 0) - start.x) * ix);
        double tmy = Math.abs((y + (sy > 0 ? 1 : 0) - start.y) * iy);
        double tmz = Math.abs((z + (sz > 0 ? 1 : 0) - start.z) * iz);
        for (int i = 0; i < 1000; i++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!BlockRules.etherPassable(level.getBlockState(pos))) {
                // As on SkyBlock now, a block with a head on it can't be warped onto.
                BlockState above = level.getBlockState(pos.above());
                if (BlockRules.etherPassable(above) && !(above.getBlock() instanceof AbstractSkullBlock)
                    && BlockRules.etherPassable(level.getBlockState(pos.above(2)))
                    && !Double.isNaN(etherFeet(level, pos))) {
                    return pos;
                }
                return null;
            }
            if (x == endX && y == endY && z == endZ) {
                return null;
            }
            if (tmx <= tmy && tmx <= tmz) {
                tmx += tdx;
                x += sx;
            } else if (tmy <= tmz) {
                tmy += tdy;
                y += sy;
            } else {
                tmz += tdz;
                z += sz;
            }
        }
        return null;
    }

    /** Hyperion's Wither Impact: a 10 block transmission, then an implosion that kills secret bats (mobs later). */
    void wither(ServerPlayer player) {
        long now = level.getGameTime();
        Long last = hyperionUsed.get(player.getUUID());
        if (last != null && now - last < 1) {
            return;
        }
        hyperionUsed.put(player.getUUID(), now);
        witherShield(player, now);
        // It never teleports in the boss (3 of 2,885 clicks: abilities-items.md §3): the implosion only.
        if (run.inBoss() || noItemTeleport(player)) {
            implode(player, player.getX(), player.getY(), player.getZ());
            return;
        }
        Vec3 to = transmission(player, 10.0);
        if (to == null) {
            player.sendSystemMessage(EngineItems.text("§cThere are blocks in the way!"));
            implode(player, player.getX(), player.getY(), player.getZ());
            return;
        }
        double x = to.x;
        double y = to.y;
        double z = to.z;
        teleport(player, x, y, z);
        run.schedule(1, () -> level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1f, 1f));
        implode(player, x, y, z);
    }

    /**
     * The Wither Shield scroll: 10% less damage for 5 seconds, at most once every 100 ticks (the cure
     * sound's gate), and each one costs about 50 Vitality though the lore doesn't say so
     * (abilities-items.md §10.2: -50 in 2,724 of 4,010 shield casts), and the absorption shield and its heal
     * (Combat.witherShield).
     */
    private void witherShield(ServerPlayer player, long now) {
        Long last = shieldUsed.get(player.getUUID());
        if (last != null && now - last < 100) {
            return;
        }
        shieldUsed.put(player.getUUID(), now);
        run.combat().drainVitality(player, 50);
        run.combat().protect(player, 100, 0.9);
        run.combat().witherShield(player);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.HOSTILE, 1f, 0.698f);
    }

    /** The implosion, then (a tick later) the hit sounds and "Your Implosion hit N enemies for X damage." when it hit any. */
    private void implode(ServerPlayer player, double x, double y, double z) {
        MobSystem.Hits hits = implode(x, y, z);
        if (hits.count() > 0) {
            player.sendSystemMessage(EngineItems.text(String.format(java.util.Locale.ROOT,
                "§7Your Implosion hit §c%d §7%s for §c%,.1f §7damage.", hits.count(), hits.count() == 1 ? "enemy" : "enemies", hits.damage())));
            run.schedule(1, () -> {
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1.492f);
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 0.2f, 1.492f);
            });
        }
    }

    /** handle_hyperion_explosion: kills every mob and secret bat within 7 blocks; explode BLOCKS 1.0/1.0 a tick later. */
    MobSystem.Hits implode(double x, double y, double z) {
        level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0, 0, 0, 0);
        run.schedule(1, () -> level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1f, 1f));
        AABB impact = new AABB(x - 7, y - 7, z - 7, x + 7, y + 7, z + 7);
        run.secrets().killBats(impact);
        return run.mobs().killInside(impact);
    }

    /** Guided Bat: a bat that flies a block a tick along its shooter's aim and implodes on what it hits. */
    void spiritSceptre(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Bat bat = new Bat(EntityTypes.BAT, level);
        // The bat spawns at feet + 0.88 (measured), with ghast.shoot HOSTILE 0.3/1.0 a tick later.
        bat.snapTo(eye.x, player.getY() + 0.88, eye.z, player.getYRot(), player.getXRot());
        bat.setNoAi(true);
        bat.setNoGravity(true);
        bat.setPermanentlyInvulnerable(true);
        bat.setResting(false);
        level.addFreshEntity(bat);
        run.schedule(1, () -> level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.GHAST_SHOOT, SoundSource.HOSTILE, 0.3f, 1f));
        sceptreBats.add(new SceptreBat(bat, player.getUUID()));
    }

    /**
     * Bonzo's Staff as measured on Hypixel: a balloon flies from feet+1.0 (+0.2 along the look) at 0.75 a
     * tick and bursts on the first 0.75 step whose block isn't passable, k+1 ticks after the click. Hard
     * 4-tick gate; a refused click does nothing.
     */
    void bonzo(ServerPlayer player) {
        long now = level.getGameTime();
        Long last = bonzoShot.get(player.getUUID());
        long gap = last == null ? Long.MAX_VALUE : now - last;
        if (gap < 4) {
            return;
        }
        bonzoShot.put(player.getUUID(), now);
        // Hypixel resends the held item on most accepted clicks (more often the longer since the last balloon).
        double resend = gap < 8 ? 0.5 : gap > 10 ? 0.81 : 0.65;
        if (random.nextDouble() < resend) {
            int slot = player.getInventory().getSelectedSlot();
            player.connection.send(new ClientboundContainerSetSlotPacket(0, player.inventoryMenu.incrementStateId(), 36 + slot,
                player.getMainHandItem().copy()));
        }
        Vec3 pos = player.position();
        Vec3 dir = look(player.getYRot(), player.getXRot());
        Vec3 origin = pos.add(0, 1.0, 0).add(dir.scale(0.2));
        ArmorStand stand = shotStand(origin.add(0, -1.5, 0), player, balloonHead());
        // Hypixel plays no sound for the shot (abilities-items.md §10.3: none within 12 ticks).
        shots.add(new Shot(player.getUUID(), true, origin, dir, stand, bonzoSolid(origin)));
    }

    /**
     * Jerry-chine Gun as measured on Hypixel: a bullet from feet+1.1 straight along your look, at
     * 0.25 + 0.75k blocks on tick k, bursting at the first point inside a block (or tick 30). At most
     * 3 bullets a tick.
     */
    void jerry(ServerPlayer player) {
        long now = level.getGameTime();
        long[] tick = jerryShots.computeIfAbsent(player.getUUID(), u -> new long[] {-1, 0});
        if (tick[0] != now) {
            tick[0] = now;
            tick[1] = 0;
        }
        if (++tick[1] > 3) {
            return;
        }
        Vec3 pos = player.position();
        Vec3 dir = look(player.getYRot(), player.getXRot());
        Vec3 origin = pos.add(0, 1.1, 0);
        ArmorStand stand = shotStand(pos.add(dir).add(0, -0.5, 0), player, EngineItems.head(skin(VILLAGER_HASH), null));
        stand.setHeadPose(new Rotations(player.getXRot(), 0f, 0f));
        rapidFire(player, now);
        shots.add(new Shot(player.getUUID(), false, origin, dir, stand, false));
    }

    /** What [player]'s next Rapid-fire shot costs: the k-th shot of a burst is k x the 30 base's price. */
    double rapidFireCost(ServerPlayer player) {
        long[] r = rapidFire.computeIfAbsent(player.getUUID(), u -> new long[] {-1000, 0});
        long k = level.getGameTime() - r[0] > 80 ? 1 : r[1] + 1;
        return k * run.combat().manaCost(player, 30, player.getMainHandItem());
    }

    /** The Rapid-fire action bar: this shot's cost in the bar's middle, the burst reset after 4 seconds idle. */
    private void rapidFire(ServerPlayer player, long now) {
        long[] r = rapidFire.computeIfAbsent(player.getUUID(), u -> new long[] {-1000, 0});
        if (now - r[0] > 80) {
            r[1] = 0;
        }
        r[1]++;
        r[0] = now;
        long cost = (long) (r[1] * run.combat().manaCost(player, 30, player.getMainHandItem()));
        run.combat().castLine(player, "§b-" + cost + " Mana (§6Rapid-fire§b)");
    }

    static Vec3 look(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    /** The invisible marker stand wearing [head] that draws a shot. */
    private ArmorStand shotStand(Vec3 at, ServerPlayer player, ItemStack head) {
        ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        stand.snapTo(at.x, at.y, at.z, player.getYRot(), player.getXRot());
        stand.setInvisible(true);
        stand.setSilent(true);
        stand.setNoGravity(true);
        stand.setPermanentlyInvulnerable(true);
        ((ArmorStandAccessor) stand).rustclear$setMarker(true);
        stand.setItemSlot(EquipmentSlot.HEAD, head);
        level.addFreshEntity(stand);
        return stand;
    }

    private void tickShots() {
        for (Iterator<Shot> it = shots.iterator(); it.hasNext(); ) {
            Shot shot = it.next();
            ServerPlayer shooter = level.getServer().getPlayerList().getPlayer(shot.shooter);
            if (shooter == null || shooter.level() != level) {
                shot.stand.discard();
                it.remove();
                continue;
            }
            shot.ticks++;
            if (shot.bonzo ? tickBonzo(shot, shooter) : tickJerry(shot, shooter)) {
                it.remove();
            }
        }
    }

    /** One flight tick of a balloon; true when it's done. Step k bursts in tick k+1. */
    private boolean tickBonzo(Shot shot, ServerPlayer shooter) {
        int k = shot.ticks - 1;
        if (k >= 1 && k <= BONZO_STEPS) {
            Vec3 at = shot.origin.add(shot.dir.scale(BONZO_SPEED * k));
            // Fired from inside a block, it bursts on the second step.
            if (shot.startsInside ? k == 2 : bonzoSolid(at)) {
                bonzoBurst(shooter, at);
                shot.stand.discard();
                return true;
            }
        }
        if (shot.ticks > BONZO_STEPS) {
            shot.stand.discard();
            return true;
        }
        Vec3 q = shot.origin.add(shot.dir.scale(BONZO_SPEED * shot.ticks));
        shot.stand.setPos(q.x, q.y - 1.5, q.z);
        return false;
    }

    /** One flight tick of a Jerry bullet (check, then move); true when it's done. */
    private boolean tickJerry(Shot shot, ServerPlayer shooter) {
        int k = shot.ticks;
        Vec3 b = shot.origin.add(shot.dir.scale(0.25 + 0.75 * k));
        if (k < JERRY_LIFE && !jerrySolid(b)) {
            Vec3 next = shot.origin.add(shot.dir.scale(0.25 + 0.75 * (k + 1)));
            shot.stand.setPos(next.x, next.y - 1.6, next.z);
            return false;
        }
        jerryBurst(shooter, b);
        run.schedule(1, shot.stand::discard);
        return true;
    }

    /**
     * The balloon bursts: a firework only you see, and, if it's within 3.5 of your feet+1.5, your motion
     * is replaced by 1.5 flat away from it and 0.5 up.
     */
    private void bonzoBurst(ServerPlayer player, Vec3 at) {
        ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
        rocket.set(DataComponents.FIREWORKS, new Fireworks(0, List.of(new FireworkExplosion(FireworkExplosion.Shape.SMALL_BALL,
            IntList.of(random.nextInt(0x1000000), random.nextInt(0x1000000)), IntList.of(), false, false))));
        // Packets to the shooter only, never a level entity (a ticking rocket flies off and explodes by itself).
        FireworkRocketEntity fw = new FireworkRocketEntity(level, at.x, at.y, at.z, rocket);
        var conn = player.connection;
        conn.send(new ClientboundAddEntityPacket(fw.getId(), fw.getUUID(), at.x, at.y, at.z, 0f, 0f, EntityTypes.FIREWORK_ROCKET, 0, Vec3.ZERO, 0.0));
        var data = fw.getEntityData().getNonDefaultValues();
        if (data != null) {
            conn.send(new ClientboundSetEntityDataPacket(fw.getId(), data));
        }
        conn.send(new ClientboundEntityEventPacket(fw, (byte) 17));
        run.schedule(1, () -> conn.send(new ClientboundRemoveEntitiesPacket(fw.getId())));
        Vec3 pos = player.position();
        if (at.distanceTo(pos.add(0, 1.5, 0)) > 3.5) {
            return;
        }
        double dx = pos.x - at.x;
        double dz = pos.z - at.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        push(player, h > 0 ? new Vec3(1.5 * dx / h, 0.5, 1.5 * dz / h) : new Vec3(0, 0.5, 0));
    }

    /**
     * A bullet bursts: villager.yes (straight to you, so far bursts aren't silent), then, if it's within 4
     * of your feet+1.62, your motion is replaced by 0.6 up and 0.5 times the flat part of the way to your eye.
     */
    private void jerryBurst(ServerPlayer player, Vec3 b) {
        player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.VILLAGER_YES), SoundSource.MASTER,
            Math.floor(b.x) + 0.5, Math.floor(b.y) + 0.5, Math.floor(b.z) + 0.5, 0.35f, 1f, random.nextLong()));
        Vec3 d = player.position().add(0, 1.62, 0).subtract(b);
        double len = d.length();
        if (len <= 4.0 && len > 0) {
            push(player, new Vec3(0.5 * d.x / len, 0.6, 0.5 * d.z / len));
        }
        level.sendParticles(ParticleTypes.POOF, true, true, b.x, b.y, b.z, 1, 0, 0, 0, 0);
    }

    /** Sets the player's motion, sent to them this tick (not on the tracker's next pass). */
    static void push(ServerPlayer player, Vec3 v) {
        player.setDeltaMovement(v);
        player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), v));
    }

    /**
     * A balloon cell that stops it, cell level (stairs, slabs and walls are solid). Passable: air, liquids,
     * fire, levers, buttons, signs, torches, tripwire, pressure plates, carpet, heads, grass, light,
     * ladders, vines and glass panes.
     */
    private boolean bonzoSolid(Vec3 at) {
        BlockState s = level.getBlockState(BlockPos.containing(at));
        Block b = s.getBlock();
        if (s.isAir() || b instanceof LiquidBlock || b instanceof BaseFireBlock || b instanceof LeverBlock || b instanceof ButtonBlock
            || b instanceof SignBlock || b instanceof BaseTorchBlock || b instanceof TripWireBlock || b instanceof TripWireHookBlock
            || b instanceof BasePressurePlateBlock || b instanceof CarpetBlock || b instanceof AbstractSkullBlock
            || b instanceof TallGrassBlock || b instanceof LightBlock || b instanceof LadderBlock || b instanceof VineBlock) {
            return false;
        }
        return !(b instanceof IronBarsBlock && BuiltInRegistries.BLOCK.getKey(b).getPath().endsWith("glass_pane"));
    }

    /** A bullet point inside a block's collision shape. */
    private boolean jerrySolid(Vec3 b) {
        BlockPos pos = BlockPos.containing(b);
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.empty());
        if (shape.isEmpty()) {
            return false;
        }
        for (AABB box : shape.toAabbs()) {
            if (box.move(pos).contains(b)) {
                return true;
            }
        }
        return false;
    }

    private static String skin(String hash) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private ItemStack balloonHead() {
        return EngineItems.head(skin(BALLOON_HASHES[random.nextInt(BALLOON_HASHES.length)]), null);
    }

    /** Gorilla Tactics: back to where you used it after 3 seconds, with the server's sound cues. */
    private void tacticalInsertion(ServerPlayer player) {
        // ghast.hurt HOSTILE 1.0/0.492 a tick after the use (abilities-items.md §10.3).
        run.schedule(1, () -> level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GHAST_HURT,
            SoundSource.HOSTILE, 1f, 0.492f));
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        UUID id = player.getUUID();
        float[][] hats = {{10, 0.8f, 1.21f}, {20, 0.85f, 1.33f}, {30, 0.9f, 1.44f}, {40, 0.95f, 1.57f}, {50, 1f, 1.7f}};
        sound(player, SoundEvents.FLINTANDSTEEL_USE, 1f, 0.75f);
        run.schedule(1, () -> sound(player, SoundEvents.FLINTANDSTEEL_USE, 1f, 1.1f));
        for (float[] hat : hats) {
            run.schedule((int) hat[0], () -> sound(player, SoundEvents.NOTE_BLOCK_HAT.value(), hat[1], hat[2]));
        }
        run.schedule(60, () -> {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p != null && p.level() == level) {
                p.teleportTo(level, x, y, z, Set.of(), yaw, pitch, true);
                p.resetFallDistance();
                sound(p, SoundEvents.ZOMBIE_VILLAGER_CURE, 0.7f, 1.89f);
            }
        });
        run.schedule(62, () -> sound(player, SoundEvents.ZOMBIE_VILLAGER_CURE, 0.6f, 1.73f));
        run.schedule(66, () -> sound(player, SoundEvents.ZOMBIE_VILLAGER_CURE, 0.5f, 1.57f));
        run.schedule(1, () -> refill(player, EngineItem.TACTICAL_INSERTION));
    }

    void sound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
    }

    /** server_teleport with relative look: position moves, the view doesn't. */
    static void teleport(ServerPlayer player, double x, double y, double z) {
        player.teleportTo(player.level(), x, y, z, Relative.ROTATION, 0f, 0f, false);
        player.resetFallDistance();
    }

    // ---------------------------------------------------------------- Dungeonbreaker

    int breakerCharges(ServerPlayer player) {
        Breaker breaker = breakers.get(player.getUUID());
        return breaker == null ? MAX_CHARGES : breaker.charges;
    }

    /** dungeonbreaker::on_hit: a left click on a block with it in hand. Returns true when it was handled. */
    boolean hitBlock(ServerPlayer player, BlockPos pos) {
        if (EngineItem.of(player.getMainHandItem()) != EngineItem.DUNGEONBREAKER) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        Breaker breaker = breakers.computeIfAbsent(player.getUUID(), u -> new Breaker());
        long now = level.getGameTime();
        String why = refusal(player, pos, state);
        if (why != null || breaker.charges == 0) {
            String text = why != null ? why : NO_CHARGES;
            SimLog.event("breaker_refused", "who", player == null ? "" : player.getName().getString(), "charges", breaker.charges,
                "why", why == null ? "charges" : why.isEmpty() ? "silent" : "block", "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(),
                "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());

            if (!text.isEmpty() && now >= breaker.lastMessage + MESSAGE_THROTTLE_TICKS) {
                breaker.lastMessage = now;
                player.sendSystemMessage(EngineItems.text(text));
            }
            return true;
        }
        breaker.charges--;
        level.destroyBlock(pos, false, player, 512);
        broken.add(new Broken(pos.immutable(), state, now, new long[] {Long.MAX_VALUE}));
        breaker.window.addLast(pos.immutable());
        if (breaker.window.size() > MAX_CHARGES) {
            BlockPos oldest = breaker.window.pollFirst();
            for (Broken b : broken) {
                if (b.pos().equals(oldest)) {
                    b.restoreAt()[0] = Math.min(b.restoreAt()[0], now + WINDOW_RESTORE_TICKS);
                }
            }
        }
        refreshLore(player, breaker.charges);
        return true;
    }

    boolean breakable(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || refusal(null, pos, state) != null) {
            return false;
        }

        return true;
    }

    /**
     * holdsSomething without the swap, for planning: a neighbour that hangs on this block (a floor torch, sign, banner,
     * carpet, rail, plate, plant or door on top; a wall torch, sign, banner, ladder or vine against it; a lever or button
     * on that face; a skull as holdsSomething counts it).
     */
    private boolean holdsAttached(BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState n = level.getBlockState(pos.relative(d));
            Block b = n.getBlock();
            if (n.isAir() || b instanceof LiquidBlock) {
                continue;
            }
            if (b instanceof AbstractSkullBlock) {
                if (d == Direction.UP && !(b instanceof WallSkullBlock)
                    || b instanceof WallSkullBlock && n.getValue(BlockStateProperties.HORIZONTAL_FACING) == d) {
                    return true;
                }
                continue;
            }
            if (b instanceof net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock) {
                var face = n.getValue(BlockStateProperties.ATTACH_FACE);
                if (face == net.minecraft.world.level.block.state.properties.AttachFace.FLOOR ? d == Direction.UP
                    : face == net.minecraft.world.level.block.state.properties.AttachFace.CEILING ? d == Direction.DOWN
                    : n.getValue(BlockStateProperties.HORIZONTAL_FACING) == d) {
                    return true;
                }
                continue;
            }
            boolean wall = b instanceof net.minecraft.world.level.block.WallTorchBlock
                || b instanceof net.minecraft.world.level.block.WallSignBlock
                || b instanceof net.minecraft.world.level.block.WallBannerBlock
                || b instanceof net.minecraft.world.level.block.LadderBlock;
            if (wall) {
                if (n.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && n.getValue(BlockStateProperties.HORIZONTAL_FACING) == d) {
                    return true;
                }
                continue;
            }
            if (b instanceof net.minecraft.world.level.block.VineBlock) {
                return true;
            }
            boolean onTop = b instanceof net.minecraft.world.level.block.TorchBlock
                || b instanceof net.minecraft.world.level.block.StandingSignBlock
                || b instanceof net.minecraft.world.level.block.BannerBlock
                || b instanceof net.minecraft.world.level.block.CarpetBlock
                || b instanceof net.minecraft.world.level.block.BaseRailBlock
                || b instanceof net.minecraft.world.level.block.BasePressurePlateBlock
                || b == Blocks.REDSTONE_WIRE
                || b instanceof net.minecraft.world.level.block.DoorBlock
                || b instanceof net.minecraft.world.level.block.VegetationBlock;
            if (onTop && d == Direction.UP) {
                return true;
            }
        }
        return false;
    }

    /** Why a block can't be broken: a message, "" for silently, or null when it can. */
    private String refusal(ServerPlayer player, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof LiquidBlock) {
            return "";
        }
        // Nothing breaks before the run starts (the entrance doors are still shut then).
        if (run.state() != DungeonRun.State.STARTED) {
            return THAT_BLOCK;
        }
        if (run.isInteractable(pos) || inDoor(pos)) {
            return THAT_BLOCK;
        }
        if (leadsOutOfMap(pos) || roomShell(pos)) {
            return THERE;
        }
        int roomIndex = run.inBoss() ? -1 : run.roomAt(pos.getX(), pos.getZ());
        RoomType roomType = roomIndex < 0 ? null : run.rooms.get(roomIndex).type();
        if (roomType == RoomType.BLOOD || roomType == RoomType.FAIRY || noDigPuzzle(roomIndex)) {
            return THIS_ROOM;
        }
        Block b = state.getBlock();
        boolean refused = b == Blocks.BARRIER || b == Blocks.BEDROCK || b == Blocks.COMMAND_BLOCK
            || b == Blocks.CHAIN_COMMAND_BLOCK || b == Blocks.REPEATING_COMMAND_BLOCK || b == Blocks.LEVER
            || state.is(BlockTags.BUTTONS) || b == Blocks.PISTON || b == Blocks.STICKY_PISTON
            || b == Blocks.PISTON_HEAD || b == Blocks.MOVING_PISTON || b == Blocks.GRANITE || b == Blocks.POLISHED_GRANITE
            || b == Blocks.EMERALD_BLOCK || b == Blocks.REDSTONE_LAMP || b == Blocks.GOLD_BLOCK || b == Blocks.CHEST
            || b == Blocks.TRAPPED_CHEST || b == Blocks.ENDER_CHEST || b instanceof AbstractSkullBlock || b == Blocks.SPAWNER;
        // Anything with a block entity (signs, banners, heads...) would lose it when it comes back.
        // Planning (no player) leaves out the support test: it swaps the block out and back to see.
        if (refused || state.hasBlockEntity() || player != null && holdsSomething(pos, state)) {
            return THAT_BLOCK;
        }
        if (player == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        if (eye.distanceTo(Vec3.atCenterOf(pos)) > MAX_REACH) {
            return "";
        }
        return null;
    }

    /** The puzzles nothing is dug in on Hypixel (the user, 2026-10-20): Teleport Maze, Water Board, Boulder, Blaze, Ice Fill, Ice Path. */
    private static final Set<String> NO_DIG_PUZZLES = Set.of("Teleport Maze", "Water Board", "Boulder", "Blaze", "Ice Fill", "Ice Path");

    private boolean noDigPuzzle(int roomIndex) {
        return roomIndex >= 0 && noDig(run.rooms.get(roomIndex));
    }

    static boolean noDig(RoomState room) {
        return room != null && room.type() == RoomType.PUZZLE && NO_DIG_PUZZLES.contains(room.data().name());
    }

    /** Part of a door that is still shut, or its frame. */
    private boolean inDoor(BlockPos pos) {
        for (DoorState door : run.doors) {
            if (!door.opened && door.contains(pos.getX(), pos.getY(), pos.getZ())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Would breaking this let a player out of the dungeon: a block within two of the grid's edge or
     * of an empty cell (outer walls), or a floor with nothing below it.
     */
    private boolean leadsOutOfMap(BlockPos pos) {
        for (int[] d : new int[][] {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
            if (!inGrid(pos.getX() + d[0], pos.getZ() + d[1])) {
                return true;
            }
        }
        for (int dy = 1; dy <= 64; dy++) {
            if (!level.getBlockState(pos.below(dy)).isAir()) {
                return false;
            }
        }
        return true;
    }

    /**
     * A room's outermost layer (the user, 2026-10-19: no digging out of a room): its roof's top layer (every
     * room's stored blocks end in a full roof: every column's highest block is on that layer), and the last
     * column of its outer wall where the next cell is another room or none, the 1-block seam to it included. The
     * seam inside a room that spans cells is not a wall. Thick walls and roofs can still be dug into, not through.
     */
    private boolean roomShell(BlockPos pos) {
        if (run.inBoss()) {
            return false;
        }
        int index = run.roomAt(pos.getX(), pos.getZ());
        if (index < 0) {
            return false;
        }
        RoomData data = run.rooms.get(index).data();
        int layers = data.width() * data.length() == 0 ? 0 : data.blocks().length / (data.width() * data.length());
        if (layers > 0 && pos.getY() >= data.bottom() + layers - 1) {
            return true;
        }
        int cx = Math.floorMod(pos.getX() - Layout.ORIGIN_X, Layout.CELL);
        int cz = Math.floorMod(pos.getZ() - Layout.ORIGIN_Z, Layout.CELL);
        return cx >= Layout.CELL - 2 && run.roomAt(pos.getX() + Layout.CELL - cx, pos.getZ()) != index
            || cx == 0 && run.roomAt(pos.getX() - 1, pos.getZ()) != index
            || cz >= Layout.CELL - 2 && run.roomAt(pos.getX(), pos.getZ() + Layout.CELL - cz) != index
            || cz == 0 && run.roomAt(pos.getX(), pos.getZ() - 1) != index;
    }

    private boolean inGrid(int x, int z) {
        int gx = Math.floorDiv(x - Layout.ORIGIN_X, Layout.CELL);
        int gz = Math.floorDiv(z - Layout.ORIGIN_Z, Layout.CELL);
        return gx >= 0 && gz >= 0 && gx < Layout.GRID && gz < Layout.GRID && run.roomAt(x, z) >= 0;
    }

    /**
     * holds_something: would taking this block away leave something attached to it hanging (a
     * torch, lever, sign, ladder, carpet, plant, door...)? Checked by asking each neighbour whether
     * it could still stand without the block.
     */
    private boolean holdsSomething(BlockPos pos, BlockState state) {
        for (Direction d : Direction.values()) {
            BlockPos np = pos.relative(d);
            BlockState n = level.getBlockState(np);
            if (n.isAir() || n.getBlock() instanceof LiquidBlock) {
                continue;
            }
            // Skulls never fall in vanilla, but the server counts one sitting on or hung from the block.
            if (n.getBlock() instanceof AbstractSkullBlock) {
                if (d == Direction.UP && !(n.getBlock() instanceof WallSkullBlock)) {
                    return true;
                }
                if (n.getBlock() instanceof WallSkullBlock && n.getValue(BlockStateProperties.HORIZONTAL_FACING) == d) {
                    return true;
                }
                continue;
            }
            if (!n.canSurvive(level, np)) {
                continue;
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
            boolean survives = n.canSurvive(level, np);
            level.setBlock(pos, state, FLAGS);
            if (!survives) {
                return true;
            }
        }
        return false;
    }

    /** Charges come back 2 (sometimes 1) at a time every second or so; broken blocks come back after 11 seconds. */
    void tick() {
        long now = level.getGameTime();
        tickSceptreBats();
        tickShots();
        for (ServerPlayer player : level.players()) {
            Breaker breaker = breakers.get(player.getUUID());
            if (breaker == null) {
                continue;
            }
            if (breaker.charges >= MAX_CHARGES) {
                breaker.refillAt = -1;
                continue;
            }
            if (breaker.refillAt < 0) {
                breaker.refillAt = now + 1 + random.nextInt(20);
            }
            if (now < breaker.refillAt) {
                continue;
            }
            breaker.charges = Math.min(MAX_CHARGES, breaker.charges + (random.nextInt(5) == 0 ? 1 : 2));
            breaker.refillAt = -1;
            refreshLore(player, breaker.charges);
        }
        for (Iterator<Broken> it = broken.iterator(); it.hasNext(); ) {
            Broken b = it.next();
            if (now >= b.brokenAt() + REGEN_TICKS || now >= b.restoreAt()[0]) {
                it.remove();
                if (level.getBlockState(b.pos()).isAir()) {
                    level.setBlock(b.pos(), b.state(), FLAGS);
                }
            }
        }
    }

    private void tickSceptreBats() {
        for (Iterator<SceptreBat> it = sceptreBats.iterator(); it.hasNext(); ) {
            SceptreBat s = it.next();
            ServerPlayer shooter = level.getServer().getPlayerList().getPlayer(s.shooter());
            if (s.bat().isRemoved() || shooter == null || s.bat().tickCount > SCEPTRE_LIFETIME_TICKS) {
                s.bat().discard();
                it.remove();
                continue;
            }
            Vec3 at = s.bat().position().add(shooter.getLookAngle().normalize());
            s.bat().snapTo(at.x, at.y, at.z, shooter.getYRot(), shooter.getXRot());
            AABB box = new AABB(at.x - 0.25, at.y, at.z - 0.25, at.x + 0.25, at.y + 0.9, at.z + 0.25);
            if (!level.noCollision(box) || run.mobs().touches(box)) {
                implode(at.x, at.y, at.z);
                s.bat().discard();
                it.remove();
            }
        }
    }

    /** Puts every broken block back (a new dungeon is coming). */
    void dispose() {
        sceptreBats.forEach(s -> s.bat().discard());
        sceptreBats.clear();
        shots.forEach(s -> s.stand.discard());
        shots.clear();
        for (Broken b : broken) {
            if (level.getBlockState(b.pos()).isAir()) {
                level.setBlock(b.pos(), b.state(), FLAGS);
            }
        }
        broken.clear();
    }

    private static void refreshLore(ServerPlayer player, int charges) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (EngineItem.of(stack) == EngineItem.DUNGEONBREAKER) {
                stack.set(DataComponents.LORE, new ItemLore(EngineItems.lines(dungeonbreakerLore(charges))));
            }
        }
        player.inventoryMenu.broadcastChanges();
    }

    private static String[] dungeonbreakerLore(int charges) {
        return new String[] {
            "§7Speed: §f+20", "", "§6Ability: Dungeon Breaker §e§lDIG",
            "§7While in §cThe Catacombs§7, consume §e1§c⸕", "§7charge to break a block. §320§7 blocks can",
            "§7be broken at a time, and re-appear after", "§a10s§7. §e2§c⸕§7 charges are regenerated each", "§7second.", "",
            "§7Charges: §e" + charges + "§7/§e" + MAX_CHARGES + "§c⸕", "", "§8§l* §8Co-op Soulbound §8§l*",
            "§c§lSPECIAL DUNGEON PICKAXE"};
    }

    static ItemStack dungeonbreakerStack(int charges) {
        ItemStack stack = EngineItem.DUNGEONBREAKER.stack();
        stack.set(DataComponents.LORE, new ItemLore(EngineItems.lines(dungeonbreakerLore(charges))));
        return stack;
    }
}
