package net.rustclear.mod.engine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.engine.boss.Titles;

/**
 * Death the Hypixel way, in every phase (docs/f7-sim/boss-p4-necron.md F9, abilities-items.md §3): a player at zero
 * health becomes a ghost where they stand, in their own game mode (no spectator, so walls still stop them), flying,
 * invisible, at walking speed 0.55. Their inventory is put away and the ghost kit given: Haunt, two class ghost
 * abilities, the Magical Map and 10 Ghost Arrows (measured over 62 own deaths on main). A teammate (themself when
 * alone) starts reviving them in the death tick; "BEING REVIVED" counts down from death + 20, and 120 ticks after
 * "is reviving" (measured 119-121) they are back where the reviver stands, with their inventory, effects and speed.
 */
final class Ghosts {
    /** Hypixel's ghost walking speed (player_abilities walkingSpeed 0.55). */
    static final float SPEED = 0.55f;
    /** "is reviving" to "was revived by": 119 x19, 120 x17, 121 x9 (median). */
    private static final int REVIVE_TICKS = 120;
    /** The first "You will be revived in 5s" title comes at death + 20, then one a second. */
    private static final int COUNTDOWN_START = 20;
    private static final String KIT_TAG = "rustclear_ghost";

    /** Every ghost in any run, for code that has no run at hand (mob targeting, PlayerSpeed). */
    private static final Set<UUID> ALL = ConcurrentHashMap.newKeySet();

    private record Ghost(UUID id, List<ItemStack> inventory, UUID reviver, long diedAt) {}

    private final DungeonRun run;
    private final ServerLevel level;
    private final Map<UUID, Ghost> ghosts = new HashMap<>();
    /** "player:ability" to the tick it can be used again. */
    private final Map<String, Long> readyAt = new HashMap<>();
    private long ticks;

    Ghosts(DungeonRun run) {
        this.run = run;
        this.level = run.level;
    }

    /** A ghost, or a spectator: someone mobs ignore and hits pass through. */
    static boolean absent(ServerPlayer player) {
        return player.isSpectator() || ALL.contains(player.getUUID());
    }

    static boolean isGhost(ServerPlayer player) {
        return ALL.contains(player.getUUID());
    }

    boolean contains(ServerPlayer player) {
        return ghosts.containsKey(player.getUUID());
    }

    boolean any() {
        return !ghosts.isEmpty();
    }

    /** The ghost that died first, for the tab's Downed rows; null when nobody is. */
    UUID firstDowned() {
        return ghosts.values().stream().min(java.util.Comparator.comparingLong(Ghost::diedAt)).map(Ghost::id).orElse(null);
    }

    /** Whole seconds since [id] died (0 if they're not a ghost). */
    long downedSeconds(UUID id) {
        Ghost g = ghosts.get(id);
        return g == null ? 0 : (ticks - g.diedAt) / 20;
    }

    // ---------------------------------------------------------------- death

    /**
     * [player] dies: [killer] names what killed them ("Golden Flamer"), or null for the plain "died" line
     * (Goldor's death ticks, untyped deaths).
     */
    void kill(ServerPlayer player, String killer) {
        UUID id = player.getUUID();
        // Hypixel's death lines, which death counters (Skyblocker, Odin) read: your own, and everyone else's.
        String how = killer == null ? "died" : "was killed by §6" + killer + "§7";
        for (ServerPlayer p : level.players()) {
            String who = p == player ? "You" : player.getName().getString();
            String verb = p == player ? (killer == null ? "died" : "were killed by §6" + killer + "§7") : how;
            p.sendSystemMessage(EngineItems.text("§c ☠ §7" + who + " " + verb + " and became a ghost§7."));
        }
        if (consumeReviveStone(player)) {
            // The stone breaks in the death tick, but the ghost time is the same with or without it (F9).
            player.sendSystemMessage(EngineItems.text("§aYour Revive Stone revived you and broke!"));
        }
        List<ItemStack> saved = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            saved.add(inv.getItem(i).copy());
        }
        ServerPlayer reviver = reviverFor(player);
        ghosts.put(id, new Ghost(id, saved, reviver.getUUID(), ticks));
        ALL.add(id);

        // The ghost state, in Hypixel's order: speed, flight, effects, title, a teleport in place, the hurt sound.
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        PlayerSpeed.apply(player);
        player.onUpdateAbilities();
        player.removeEffect(MobEffects.REGENERATION);
        player.removeEffect(MobEffects.STRENGTH);
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        Titles.send(player, "§eYou became a ghost!", "§7Hopefully your teammates will be able to revive you!", Titles.GHOST);
        player.teleportTo(level, player.getX(), player.getY(), player.getZ(), Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GENERIC_HURT, SoundSource.NEUTRAL, 1f, 0.889f);
        run.broadcast("§a ❣ " + DungeonRun.nameColour(reviver) + " §7is reviving " + DungeonRun.nameColour(player) + "§7!");

        // The inventory is cleared, then the kit given, 1-4 ticks later.
        inv.clearContent();
        run.schedule(2, () -> {
            if (ghosts.containsKey(id)) {
                giveKit(player);
            }
        });
        for (int s = 5; s >= 1; s--) {
            int secondsLeft = s;
            run.schedule(COUNTDOWN_START + (5 - s) * 20, () -> {
                if (ghosts.containsKey(id)) {
                    Titles.send(player, "§e§lBEING REVIVED", "§aYou will be revived in " + secondsLeft + "s", Titles.REVIVING);
                }
            });
        }
        run.schedule(REVIVE_TICKS, () -> revive(id));
    }

    /** The nearest living teammate, or [player] themself when there's nobody (self-revives happen: 40 on main). */
    private ServerPlayer reviverFor(ServerPlayer player) {
        ServerPlayer best = player;
        double bestDistance = Double.MAX_VALUE;
        for (ServerPlayer other : level.players()) {
            if (other != player && !absent(other) && other.distanceToSqr(player) < bestDistance) {
                best = other;
                bestDistance = other.distanceToSqr(player);
            }
        }
        return best;
    }

    private static boolean consumeReviveStone(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemDefs.Instance item = DataItems.of(inv.getItem(i));
            if (item != null && item.def().id.equals("REVIVE_STONE")) {
                inv.getItem(i).shrink(1);
                return true;
            }
        }
        return false;
    }

    /** Back to life where the reviver stands, with what they had. */
    private void revive(UUID id) {
        Ghost ghost = ghosts.remove(id);
        ALL.remove(id);
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
        if (ghost == null || player == null || player.level() != level) {
            return;
        }
        ServerPlayer reviver = level.getServer().getPlayerList().getPlayer(ghost.reviver());
        run.broadcast("§a ❣ " + DungeonRun.nameColour(player) + " §awas revived by "
            + DungeonRun.nameColour(reviver != null ? reviver : player) + "§a!");
        // Autopet's On Enter Island fires again on a revive (menus-skyblock.md B §3).
        Pets.trigger(player, Pets.Trigger.ENTER_ISLAND, "Dungeon");
        player.removeEffect(MobEffects.INVISIBILITY);
        player.getAbilities().mayfly = false;
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        Inventory inv = player.getInventory();
        inv.clearContent();
        for (int i = 0; i < ghost.inventory().size() && i < inv.getContainerSize(); i++) {
            inv.setItem(i, ghost.inventory().get(i));
        }
        player.inventoryMenu.sendAllDataToRemote();
        PlayerSpeed.apply(player);
        if (reviver != null && reviver != player && reviver.level() == level && !absent(reviver)) {
            player.teleportTo(level, reviver.getX(), reviver.getY(), reviver.getZ(), Set.<Relative>of(),
                player.getYRot(), player.getXRot(), false);
        }
        player.resetFallDistance();
        run.combat().revived(player);
    }

    /** Ends every ghost at once (the run is over or rebuilt): inventories back, nothing announced. */
    void clear() {
        for (UUID id : List.copyOf(ghosts.keySet())) {
            Ghost ghost = ghosts.remove(id);
            ALL.remove(id);
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player != null) {
                player.removeEffect(MobEffects.INVISIBILITY);
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
                Inventory inv = player.getInventory();
                for (int i = 0; i < ghost.inventory().size() && i < inv.getContainerSize(); i++) {
                    inv.setItem(i, ghost.inventory().get(i));
                }
                PlayerSpeed.apply(player);
            }
        }
    }

    void tick() {
        ticks++;
    }

    // ---------------------------------------------------------------- the kit

    private static final String HAUNT_SKIN = "f1d26142909bde6d9ab136fe8928dd67b662843d6c1813e108842c23f1c793bb";
    private static final String STRENGTH_SKIN = "c570fc81d5410949bfa6073156d7d78029f1332d4cf6479926252b371f5a32a4";
    private static final String STUN_SKIN = "c041d3da1b8a7a02840666d8a5e130aa058afda6a3116552d93223bdfa7d7b63";
    private static final String ABSORPTION_SKIN = "7d608d337ede3ac62ebe675dbbc69c7d53c3e5ac8b647f8c9188aad89911fd17";

    /** Hotbar 1 Haunt, 4 and 6 the class's two ghost abilities, 9 the map (kept), and 10 Ghost Arrows in the bag. */
    private void giveKit(ServerPlayer player) {
        Inventory inv = player.getInventory();
        inv.setItem(0, kitStack("HAUNT_ABILITY"));
        switch (PlayerStats.classOf(player)) {
            case MAGE -> {
                inv.setItem(3, kitStack("MAGE_DUNGEON_ABILITY_1"));
                inv.setItem(5, kitStack("MAGE_DUNGEON_ABILITY_3"));
            }
            case BERSERK -> {
                inv.setItem(3, kitStack("WARRIOR_DUNGEON_ABILITY_1"));
                inv.setItem(5, kitStack("GHOST_THROWING_AXE"));
            }
            case TANK -> {
                inv.setItem(3, kitStack("TANK_DUNGEON_ABILITY_1"));
                inv.setItem(5, kitStack("TANK_DUNGEON_ABILITY_2"));
            }
            case ARCHER -> {
                // Recorded (10 own Archer deaths, e.g. 2026-10-03_02-25-42_F7_9d5ac3): two shortbows.
                inv.setItem(3, kitStack("ARCHER_DUNGEON_ABILITY_2"));
                inv.setItem(5, kitStack("ARCHER_DUNGEON_ABILITY_3"));
            }
            default -> {
                // The Healer ghost kit was never recorded (the recorder never plays Healer): Haunt, map and arrows only.
            }
        }
        // Only the map comes back: the loadout stays put away until the revive (it used to be handed out
        // again here, so a ghost still held its Hyperion and kept clearing).
        run.giveMap(player);
        ItemStack arrows = EngineItems.named(Items.ARROW, "§8Ghost Arrow");
        arrows.setCount(10);
        inv.setItem(9, arrows);
        player.inventoryMenu.sendAllDataToRemote();
    }

    /** The ghost kit's item with SkyBlock id [id] (Haunt, the class abilities, the Ghost Axe), or null for another id. */
    static ItemStack kitStack(String id) {
        return switch (id) {
            case "HAUNT_ABILITY" -> kitItem(head(HAUNT_SKIN), id, "§aHaunt", "Haunt", "§7Teleport to an alive player!", "2s",
                "§a§lUNCOMMON DUNGEON ITEM");
            case "MAGE_DUNGEON_ABILITY_1" -> kitItem(new ItemStack(Items.BRICKS), id, "§aPop-up Wall", "Instant Wall",
                "§7Create a 5x3 wall at the block you are looking\n§7at which lasts for §a10§7 seconds.", "20s", "§a§lUNCOMMON DUNGEON ITEM");
            case "MAGE_DUNGEON_ABILITY_3" -> kitItem(new ItemStack(Items.BLAZE_POWDER), id, "§aFireball", "Fireball",
                "§7Shoots a fireball, dealing damage based on\n§7your Mage level!", "15s", "§a§lUNCOMMON DUNGEON ITEM");
            case "WARRIOR_DUNGEON_ABILITY_1" -> kitItem(head(STRENGTH_SKIN), id, "§aStrength Potion", "Buff Potion",
                "§7Throw a potion which temporarily gives all\n§7teammates in a §a10§7 block radius §c+30❁ Strength§7.", "20s",
                "§a§lUNCOMMON DUNGEON ITEM");
            case "GHOST_THROWING_AXE" -> kitItem(new ItemStack(Items.IRON_AXE), id, "§aGhost Axe", "Ghost Axe",
                "§7Throw an Axe, dealing damage based on your\n§7Berserk level!", "10s", "§8This item can be reforged!\n§a§lUNCOMMON DUNGEON AXE");
            case "TANK_DUNGEON_ABILITY_1" -> kitItem(head(STUN_SKIN), id, "§aStun Potion", "Stun Potion",
                "§7Throw a potion which temporarily stuns all\n§7monsters in a §a10§7 block radius.", "20s", "§a§lUNCOMMON DUNGEON ITEM");
            case "TANK_DUNGEON_ABILITY_2" -> kitItem(head(ABSORPTION_SKIN), id, "§aAbsorption Potion", "Absorption Potion",
                "§7Throw a potion which gives all teammates in a\n§a10§7 block radius §a200§7 HP worth of absorption\n§7for §a3§7 seconds.",
                "20s", "§a§lUNCOMMON DUNGEON ITEM");
            case "ARCHER_DUNGEON_ABILITY_2" -> bowItem(id, "§aStun Bow", "§7Temporarily stuns any non-boss\n"
                + "§7dungeon monster for a short period\n§7of time when shot.", "§a§lUNCOMMON DUNGEON BOW");
            case "ARCHER_DUNGEON_ABILITY_3" -> bowItem(id, "§9Healing Bow", "§7Shoot a teammate to heal them for\n"
                + "§a10% §7of their max HP and grant them\n§c+50❁ Strength §7for §a10 §7seconds.", "§9§lRARE DUNGEON BOW");
            default -> null;
        };
    }

    private static ItemStack head(String skin) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + skin + "\"}}}";
        return EngineItems.head(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)), null);
    }

    /** A kit item with Hypixel's name and lore ("Ability: X  RIGHT CLICK", the text, "Cooldown: Ns", the rarity). */
    private static ItemStack kitItem(ItemStack base, String id, String name, String ability, String text, String cooldown,
                                     String footer) {
        List<String> lore = new ArrayList<>();
        lore.add("§6Ability: " + ability + "  §e§lRIGHT CLICK");
        lore.addAll(List.of(text.split("\n")));
        lore.add("§8Cooldown: §a" + cooldown);
        lore.add("");
        lore.addAll(List.of(footer.split("\n")));
        ItemStack stack = EngineItems.named(base.getItem(), name, lore.toArray(String[]::new));
        if (base.has(DataComponents.PROFILE)) {
            stack.set(DataComponents.PROFILE, base.get(DataComponents.PROFILE));
        }
        CompoundTag tag = new CompoundTag();
        tag.putString(KIT_TAG, id);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** A ghost shortbow with Hypixel's lore ("Shot Cooldown: 10s", the text, "Shortbow: Instantly shoots!"). */
    private static ItemStack bowItem(String id, String name, String text, String footer) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Shot Cooldown: §a10s");
        lore.add("");
        lore.addAll(List.of(text.split("\n")));
        lore.add("§8Cooldown: §a10s");
        lore.add("");
        lore.add("§5Shortbow: Instantly shoots!");
        lore.add("");
        lore.add("§8This item can be reforged!");
        lore.add(footer);
        ItemStack stack = EngineItems.named(Items.BOW, name, lore.toArray(String[]::new));
        CompoundTag tag = new CompoundTag();
        tag.putString(KIT_TAG, id);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static String kitId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag().getStringOr(KIT_TAG, null);
    }

    // ---------------------------------------------------------------- what a ghost's items do

    /** A ghost's right click. True: handled (a ghost's other items do nothing). */
    boolean rightClick(ServerPlayer player) {
        String id = kitId(player.getMainHandItem());
        if (id == null) {
            return false;
        }
        switch (id) {
            case "HAUNT_ABILITY" -> {
                if (ready(player, id, 40)) {
                    HauntMenu.open(player, this);
                }
            }
            case "MAGE_DUNGEON_ABILITY_1" -> {
                if (ready(player, id, 400)) {
                    wall(player);
                }
            }
            case "MAGE_DUNGEON_ABILITY_3" -> {
                if (ready(player, id, 300)) {
                    fireball(player);
                }
            }
            case "GHOST_THROWING_AXE" -> {
                if (ready(player, id, 200)) {
                    axe(player);
                }
            }
            case "WARRIOR_DUNGEON_ABILITY_1" -> {
                if (ready(player, id, 400)) {
                    potion(player, ParticleTypes.ANGRY_VILLAGER);
                    for (ServerPlayer mate : teammatesNear(player.position(), 10)) {
                        // "temporarily": how long isn't recorded (open question 72); 10 s.
                        run.abilities().buffStrength(mate, 30, 200);
                    }
                }
            }
            case "TANK_DUNGEON_ABILITY_1" -> {
                if (ready(player, id, 400)) {
                    potion(player, ParticleTypes.CRIT);
                    // How long the stun lasts isn't recorded (open question 72); 5 s.
                    run.mobs().stun(player.position(), 10, 100);
                }
            }
            case "ARCHER_DUNGEON_ABILITY_2" -> {
                if (ready(player, id, 200)) {
                    level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1f, 1f);
                    Entity mob = shotAt(player, false);
                    if (mob != null) {
                        // "a short period": how long isn't recorded (open question 72); 3 s.
                        run.mobs().stun(mob.position(), 1.0, 60);
                    }
                }
            }
            case "ARCHER_DUNGEON_ABILITY_3" -> {
                if (ready(player, id, 200)) {
                    level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1f, 1f);
                    if (shotAt(player, true) instanceof ServerPlayer mate) {
                        run.combat().heal(mate, run.combat().max(mate) * 0.1, player);
                        run.abilities().buffStrength(mate, 50, 200);
                    }
                }
            }
            case "TANK_DUNGEON_ABILITY_2" -> {
                if (ready(player, id, 400)) {
                    potion(player, ParticleTypes.HEART);
                    for (ServerPlayer mate : teammatesNear(player.position(), 10)) {
                        run.combat().shieldFor(mate, 200, 60);
                    }
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** What a ghost bow's instant shot hits first within 40 blocks: a living teammate, or a dungeon mob. */
    private Entity shotAt(ServerPlayer player, boolean teammate) {
        Vec3 from = player.getEyePosition();
        Vec3 to = from.add(player.getLookAngle().scale(40));
        var block = level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) {
            to = block.getLocation();
        }
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : teammate ? new ArrayList<Entity>(level.players()) : run.mobs().mobsAlong(from, to, 0.5)) {
            if (e == player || teammate && absent((ServerPlayer) e)) {
                continue;
            }
            var hit = e.getBoundingBox().inflate(0.3).clip(from, to);
            if (hit.isPresent() && from.distanceTo(hit.get()) < bestD) {
                bestD = from.distanceTo(hit.get());
                best = e;
            }
        }
        return best;
    }

    /** Cooldowns as on Hypixel: "This ability is on cooldown for Ns." and no cast. */
    private boolean ready(ServerPlayer player, String ability, int cooldown) {
        String key = player.getUUID() + ":" + ability;
        long at = readyAt.getOrDefault(key, 0L);
        if (ticks < at) {
            player.sendSystemMessage(EngineItems.text("§cThis ability is on cooldown for " + (int) Math.ceil((at - ticks) / 20.0) + "s."));
            return false;
        }
        readyAt.put(key, ticks + cooldown);
        return true;
    }

    private List<ServerPlayer> teammatesNear(Vec3 at, double radius) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (!absent(p) && p.position().distanceTo(at) <= radius) {
                out.add(p);
            }
        }
        return out;
    }

    private void potion(ServerPlayer player, net.minecraft.core.particles.ParticleOptions particle) {
        level.sendParticles(particle, player.getX(), player.getY() + 1, player.getZ(), 30, 3, 1, 3, 0);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SPLASH_POTION_BREAK, SoundSource.PLAYERS, 1f, 1f);
    }

    /** Pop-up Wall: a 5x3 wall across your view on the block you look at, for 10 s. */
    private void wall(ServerPlayer player) {
        HitResult hit = player.pick(20, 0, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos base = block.getBlockPos().relative(block.getDirection());
        Direction across = player.getDirection().getClockWise();
        List<BlockPos> placed = new ArrayList<>();
        for (int w = -2; w <= 2; w++) {
            for (int h = 0; h < 3; h++) {
                BlockPos at = base.relative(across, w).above(h);
                if (level.getBlockState(at).isAir()) {
                    level.setBlockAndUpdate(at, Blocks.BRICKS.defaultBlockState());
                    placed.add(at);
                }
            }
        }
        level.playSound(null, base, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1.3f, 1.492f);
        run.schedule(200, () -> {
            for (BlockPos at : placed) {
                if (level.getBlockState(at).is(Blocks.BRICKS)) {
                    level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                }
            }
        });
    }

    /** What a ghost's attack does: a share by class level. Hypixel's formula isn't recorded (open question 72). */
    private static double ghostDamage(ServerPlayer player) {
        return 10_000.0 * Math.max(1, PlayerStats.of(player).classLevel);
    }

    /** Fireball: flies to the first block or mob in sight and bursts on everything within 3 blocks. */
    private void fireball(ServerPlayer player) {
        Vec3 from = player.getEyePosition();
        Vec3 end = from.add(player.getLookAngle().scale(30));
        BlockHitResult wallHit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 to = wallHit.getType() == HitResult.Type.MISS ? end : wallHit.getLocation();
        for (Entity mob : run.mobs().mobsIn(new AABB(from, to).inflate(1))) {
            var clip = mob.getBoundingBox().inflate(0.5).clip(from, to);
            if (clip.isPresent() && clip.get().distanceTo(from) < to.distanceTo(from)) {
                to = clip.get();
            }
        }
        level.playSound(null, from.x, from.y, from.z, SoundEvents.GHAST_SHOOT, SoundSource.HOSTILE, 0.3f, 1f);
        Vec3 step = to.subtract(from);
        for (double d = 0; d <= 1; d += 0.05) {
            Vec3 p = from.add(step.scale(d));
            level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        level.sendParticles(ParticleTypes.EXPLOSION, to.x, to.y, to.z, 1, 0, 0, 0, 0);
        int count = 0;
        long total = 0;
        for (Entity mob : run.mobs().mobsIn(new AABB(to, to).inflate(3))) {
            total += run.mobs().hitFlat(player, mob, ghostDamage(player));
            count++;
        }
        player.sendSystemMessage(EngineItems.text("§7Your Fireball hit §c" + count + " §7" + (count == 1 ? "enemy" : "enemies")
            + " for §c" + String.format("%,d", total) + " §7damage."));
    }

    /** Ghost Axe: thrown 12 blocks through everything in its way. */
    private void axe(ServerPlayer player) {
        Vec3 from = player.getEyePosition();
        Vec3 end = from.add(player.getLookAngle().scale(12));
        BlockHitResult wallHit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 to = wallHit.getType() == HitResult.Type.MISS ? end : wallHit.getLocation();
        Vec3 step = to.subtract(from);
        for (double d = 0; d <= 1; d += 0.08) {
            Vec3 p = from.add(step.scale(d));
            level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        level.playSound(null, from.x, from.y, from.z, SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1f, 1f);
        for (Entity mob : run.mobs().mobsIn(new AABB(from, to).inflate(1))) {
            if (mob.getBoundingBox().inflate(0.6).clip(from, to).isPresent()) {
                run.mobs().hitFlat(player, mob, ghostDamage(player));
            }
        }
    }

    // ---------------------------------------------------------------- Haunt's menu

    /**
     * "Teleport to Player" (menus-ui.md §1, ghost variant): 9x4, the living teammates' heads packed from slot 11, Close
     * at 31, nothing else (no filler). A click teleports there with the enderman sound (pitch ~1.095).
     */
    static final class HauntMenu extends ChestGui {
        private final Ghosts ghosts;
        private final List<UUID> targets = new ArrayList<>();

        static void open(ServerPlayer player, Ghosts ghosts) {
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new HauntMenu(id, inventory, player, ghosts),
                EngineItems.text("Teleport to Player")));
        }

        @Override
        boolean perSlotFill() {
            return false;
        }

        @Override
        int resyncDelay() {
            return 2;
        }

        private HauntMenu(int id, Inventory inventory, ServerPlayer player, Ghosts ghosts) {
            super(id, inventory, player, new SimpleContainer(36), 4);
            this.ghosts = ghosts;
            fill();
        }

        @Override
        void fill() {
            targets.clear();
            for (int i = 0; i < 36; i++) {
                box.setItem(i, ItemStack.EMPTY);
            }
            int slot = 11;
            for (ServerPlayer mate : ghosts.level.players()) {
                if (mate == player || absent(mate) || slot > 15) {
                    continue;
                }
                double pct = 100 * ghosts.run.combat().health(mate) / Math.max(1, PlayerStats.of(mate).get(Stat.HEALTH));
                box.setItem(slot, EngineItems.playerHead(mate.getGameProfile(), DungeonRun.nameColour(mate),
                    "§eClick to teleport to this player!", "", "§7Health: §a" + Math.round(pct) + "%"));
                targets.add(mate.getUUID());
                slot++;
            }
            box.setItem(closeSlot, EngineItems.named(Items.BARRIER, "§cClose"));
        }

        @Override
        void click(int slot) {
            int index = slot - 11;
            if (index < 0 || index >= targets.size()) {
                return;
            }
            ServerPlayer target = ghosts.level.getServer().getPlayerList().getPlayer(targets.get(index));
            player.closeContainer();
            if (target == null || target.level() != ghosts.level || absent(target)) {
                return;
            }
            player.teleportTo(ghosts.level, target.getX(), target.getY(), target.getZ(), Set.<Relative>of(),
                target.getYRot(), target.getXRot(), false);
            ghosts.level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.HOSTILE, 1f, 1.095f);
            player.sendSystemMessage(EngineItems.text("§aTeleported you to " + DungeonRun.nameColour(target) + "§a!"));
        }
    }
}
