package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.rustclear.mod.mixin.ArmorStandAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.rustclear.mod.Floor;
import net.rustclear.mod.mixin.MannequinAccessor;

/**
 * Dungeon mobs (Smalldungeons entity/dungeon_mobs spawner.rs and mob_type.rs): each room spawns one
 * recorded visit's mobs with their gear, nametag and skin. They stand still for now (the AI comes
 * later). Each has its floor's health (Archetype, F7 or M7): any melee hit, arrow or item ability
 * takes the player's damage off it (GearSystem), and killing a room's last starred mob clears it
 * and drops its keys.
 */
public final class MobSystem {
    /** A mob's level, health and melee damage on one floor (Hypixel SkyBlock 0.27.2). */
    record Stats(int level, long health, long damage) {}

    /**
     * DungeonMobType: the archetypes a mob's fullName names, with their Floor VII and Master Mode
     * Floor VII stats. From the Hypixel SkyBlock wiki's mob pages as of 0.27.2 (6 Oct 2026, the
     * Minister update, which changed levels only); health and damage are 0.26.1's (22 Jul 2026).
     * Lost Adventurer uses the Young / Holy / Unstable variants' numbers; Crypt Undead's F7 level
     * is the wiki's single Catacombs row.
     */
    enum Archetype {
        // Health: the measured spawn HP (mobs-spawns-pathing.md §2, entity data index 9, main F7 and
        // alpha M7); it matched the wiki for every normal mob. Damage: the measured raw hit where one
        // exists (§5, estimated from the action bar, F7 medians; M7 alpha), else the wiki's.
        SUPER_TANK_ZOMBIE("Super Tank Zombie", Kind.ZOMBIE, new Stats(152, 90_000, 7_500), new Stats(277, 1_050_000, 48_825)),
        ZOMBIE_SOLDIER("Zombie Soldier", Kind.ZOMBIE, new Stats(223, 1_200_000, 7_300), new Stats(463, 36_000_000, 111_600)),
        ZOMBIE_KNIGHT("Zombie Knight", Kind.ZOMBIE, new Stats(230, 1_280_000, 14_000), new Stats(477, 40_000_000, 133_200)),
        ZOMBIE_LORD("Zombie Lord", Kind.ZOMBIE, new Stats(298, 5_000_000, 18_000), new Stats(630, 300_000_000, 288_000)),
        ZOMBIE_COMMANDER("Zombie Commander", Kind.PLAYER, new Stats(278, 3_500_000, 14_720), new Stats(613, 250_000_000, 256_000)),
        CRYPT_LURKER("Crypt Lurker", Kind.ZOMBIE, new Stats(191, 420_000, 7_000), new Stats(395, 13_000_000, 72_000)),
        CRYPT_DREADLORD("Crypt Dreadlord", Kind.PLAYER, new Stats(219, 840_000, 15_400), new Stats(431, 20_000_000, 157_000)),
        CRYPT_SOULEATER("Crypt Souleater", Kind.PLAYER, new Stats(213, 800_000, 9_360), new Stats(429, 20_000_000, 99_000)),
        CRYPT_UNDEAD("Crypt Undead", Kind.PLAYER, new Stats(89, 512_578, 10_500), new Stats(220, 9_112_500, 11_700)),
        // Bow skeletons: their arrows hit for 16.5k (F7) / 85k (M7).
        SKELETON_SOLDIER("Skeleton Soldier", Kind.SKELETON, new Stats(215, 750_000, 16_500), new Stats(449, 28_000_000, 85_000)),
        SKELETON_MASTER("Skeleton Master", Kind.SKELETON, new Stats(240, 900_000, 16_500), new Stats(496, 42_000_000, 85_000)),
        SKELETON_LORD("Skeleton Lord", Kind.SKELETON, new Stats(271, 2_000_000, 16_500), new Stats(573, 120_000_000, 85_000)),
        SUPER_ARCHER("Super Archer", Kind.SKELETON, new Stats(254, 1_600_000, 16_500), new Stats(578, 200_000_000, 85_000)),
        SNIPER("Sniper", Kind.SKELETON, new Stats(246, 500_000, 41_760), new Stats(515, 36_000_000, 306_000)),
        WITHERMANCER("Withermancer", Kind.SKELETON, new Stats(244, 1_250_000, 16_900), new Stats(573, 150_000_000, 184_000)),
        FELS("Fels", Kind.ENDERMAN, new Stats(246, 1_500_000, 22_200), new Stats(563, 160_000_000, 145_000)),
        // Minibosses: the measured spawn HP (§2, the mode: LA 140 of 182, AA 72 of 87, SA 212 of 238).
        // The wiki's 6.6M etc. look like already-scaled snapshots (2.6M x 2.5).
        ANGRY_ARCHAEOLOGIST("Angry Archaeologist", Kind.PLAYER, new Stats(356, 420_000, 17_600), new Stats(673, 12_000_000, 165_600)),
        LOST_ADVENTURER("Lost Adventurer", Kind.PLAYER, new Stats(451, 2_600_000, 14_400), new Stats(860, 104_000_000, 216_000)),
        FROZEN_ADVENTURER("Frozen Adventurer", Kind.PLAYER, new Stats(446, 2_600_000, 13_200), new Stats(850, 134_000_000, 180_000)),
        KING_MIDAS("King Midas", Kind.PLAYER, new Stats(441, 4_300_000, 18_720), new Stats(716, 35_000_000, 152_000)),
        // 10M (140M on M7) also occurs, 16 of 238. OPEN: 8 (what separates the two)
        SHADOW_ASSASSIN("Shadow Assassin", Kind.PLAYER, new Stats(454, 4_200_000, 10_080), new Stats(852, 124_000_000, 210_000)),
        // No M7 Mimic was seen; the wiki's M7 value stays.
        MIMIC("Mimic", Kind.ZOMBIE, new Stats(278, 1_500_000, 17_280), new Stats(568, 180_000_000, 162_000)),
        // The Watcher's camp (blood-watcher.md §4): each camp mob's HP comes with it (CampMob); these
        // are the medians. Their melee leaves no chat line: the camper's HP drops ~1% (F7) / ~4.5% (M7)
        // a hit, which these raw hits give a cata-50 Mage. OPEN: blood melee damage
        BLOOD_UNDEAD("Blood Undead", Kind.PLAYER, new Stats(0, 252_000, 8_000), new Stats(0, 6_000_000, 80_000)),
        BLOOD_BOSS("Blood Boss", Kind.PLAYER, new Stats(0, 13_000_000, 12_000), new Stats(0, 214_500_000, 120_000)),
        BLOOD_GIANT("Blood Giant", Kind.GIANT, new Stats(0, 19_500_000, 15_000), new Stats(0, 400_000_000, 150_000)),
        PARASITE("Parasite", Kind.SILVERFISH, new Stats(79, 33_333, 3_000), new Stats(255, 1_000_000, 30_000)),
        // P1's adds (boss-p1-maxor.md §4, m7-differences.md): their skulls hit for 9,000 (120,000 on M7)
        // with Hypixel's own line (Combat.cryptSkull), so their melee number is unused.
        WITHER_MINER("Wither Miner", Kind.SKELETON, new Stats(0, 10_000_000, 9_000), new Stats(0, 300_000_000, 120_000)),
        WITHER_GUARD("Wither Guard", Kind.SKELETON, new Stats(0, 3_500_000, 9_000), new Stats(0, 300_000_000, 120_000)),
        /** A boss registered for its damage only (registerBoss): its hits go to the phase, it never dies here. */
        BOSS("Boss", Kind.ZOMBIE, new Stats(0, 1, 0), new Stats(0, 1, 0));

        final String fullName;
        final Kind kind;
        private final Stats f7;
        private final Stats m7;

        Archetype(String fullName, Kind kind, Stats f7, Stats m7) {
            this.fullName = fullName;
            this.kind = kind;
            this.f7 = f7;
            this.m7 = m7;
        }

        Stats stats(Floor floor) {
            return floor.master() ? m7 : f7;
        }

        static Archetype of(String fullName) {
            for (Archetype a : values()) {
                if (a.fullName.equals(fullName)) {
                    return a;
                }
            }
            return null;
        }

        /** skin_override: the skin constant this archetype always wears, or null. */
        String skinConstant() {
            return switch (this) {
                case CRYPT_DREADLORD, CRYPT_SOULEATER -> "CRYPT_DREADLORD_SOULEATER_SKIN";
                case ZOMBIE_COMMANDER -> "ZOMBIE_COMMANDER_SKIN";
                case CRYPT_UNDEAD -> "CRYPT_UNDEAD_SKIN";
                case FROZEN_ADVENTURER -> "FROZEN_ADVENTURER_SKIN";
                case ANGRY_ARCHAEOLOGIST -> "ANGRY_ARCHAEOLOGIST_SKIN";
                case KING_MIDAS -> "KING_MIDAS_SKIN";
                case SHADOW_ASSASSIN -> "SHADOW_ASSASSIN_SKIN";
                default -> null;
            };
        }
    }

    /** MobBaseKind: the model a mob is spawned as. */
    enum Kind {
        ZOMBIE, SKELETON, ENDERMAN, PLAYER, GIANT, SILVERFISH;

        static Kind of(String json) {
            return switch (json) {
                case "skeleton" -> SKELETON;
                case "enderman" -> ENDERMAN;
                case "player" -> PLAYER;
                default -> ZOMBIE;
            };
        }
    }

    /**
     * Clear-mob modifiers (mobs-spawns-pathing.md §2): about 28% of mobs carry one. Shares of those:
     * Healthy 30%, Speedy 25%, Fortified, Flaming and Healing 13% each, Stormy 6%. Each colours the
     * tag's name and HP its own way (e12827 tags). Healthy is x1.6 HP exactly; Speedy about x1.5
     * speed for zombies and x2 for skeletons. What the other four do isn't measured.
     * OPEN: 68 (what Fortified, Flaming, Healing and Stormy do)
     */
    enum Modifier {
        HEALTHY("Healthy", "§2", "§a", 0.30),
        SPEEDY("Speedy", "§3", "§b", 0.25),
        FORTIFIED("Fortified", "§8", "§7", 0.13),
        FLAMING("Flaming", "§6", "§e", 0.13),
        HEALING("Healing", "§2", "§a", 0.13),
        STORMY("Stormy", "§1", "§9", 0.06);

        /** The share of clear mobs with a modifier. */
        static final double RATE = 0.28;

        final String word;
        final String nameColour;
        final String hpColour;
        final double share;

        Modifier(String word, String nameColour, String hpColour, double share) {
            this.word = word;
            this.nameColour = nameColour;
            this.hpColour = hpColour;
            this.share = share;
        }

        static Modifier roll(Random random) {
            if (random.nextDouble() >= RATE) {
                return null;
            }
            double r = random.nextDouble();
            for (Modifier m : values()) {
                if ((r -= m.share) < 0) {
                    return m;
                }
            }
            return STORMY;
        }

        double healthFactor() {
            return this == HEALTHY ? 1.6 : 1.0;
        }

        double speedFactor(Kind kind) {
            return this != SPEEDY ? 1.0 : kind == Kind.SKELETON ? 2.0 : 1.5;
        }
    }

    /** Minibosses (and the crypt and Mimic mobs) never carry a modifier. */
    private static boolean takesModifier(Archetype a) {
        return a != null && switch (a) {
            case ANGRY_ARCHAEOLOGIST, LOST_ADVENTURER, FROZEN_ADVENTURER, KING_MIDAS, SHADOW_ASSASSIN, MIMIC, CRYPT_UNDEAD -> false;
            default -> true;
        };
    }

    /** A spawned mob the run tracks. */
    private static final class Tracked {
        final RoomState room;
        final Archetype archetype;
        final boolean counts;
        final ArmorStand tag;
        /** Run once it dies (a crypt's Crypt Undead credits the crypt). */
        Runnable onDeath;
        /** King Midas's hits so far. */
        int hits;
        MobAi ai;
        /** The nametag before its health ("✯ Zombie Lord"), and the health left and at full. */
        String label;
        long health;
        long maxHealth;
        Modifier modifier;
        /** Its room's entry scaling was applied (or it spawned already scaled). */
        boolean scaled;
        /** A Watcher camp mob: its tag's HP is always §a (§c0 when dead), and it falls for 20 ticks when killed. */
        boolean camp;
        /** A levelled tag ("§8[§7Lv79§8] §cParasite §a33,333§f/§a33,333§c❤"). */
        boolean levelled;
        /** The name a death line uses ("Golden Flamer"), when not the archetype's. */
        String killer;
        /** A boss: every hit goes here instead of to its health (registerBoss). */
        java.util.function.LongConsumer onDamage;

        Tracked(RoomState room, Archetype archetype, boolean counts, ArmorStand tag) {
            this.room = room;
            this.archetype = archetype;
            this.counts = counts;
            this.tag = tag;
        }
    }

    private static final int KING_MIDAS_HITS_TO_KILL = 5;
    private static final int REMOVE_TICKS = 2;
    /** Camp mobs are removed 21 ticks after their death event (e.g. 461 -> 482, 18aa3f). */
    private static final int CAMP_REMOVE_TICKS = 21;
    /** A dead clear mob's bone lies this long (40 in 29 of 70; shorter ones left tracking range). */
    private static final int BONE_TICKS = 40;

    /**
     * Every clear mob drops a vanilla bone as it's removed (mobs-spawns-pathing.md §6: within 3
     * blocks and mostly 2 ticks after the death), tossed up 0.2 and ~0.075 sideways. Nobody can
     * pick it up (none of 70 was taken).
     */
    private void dropBone(net.minecraft.world.phys.Vec3 at) {
        double a = level.getRandom().nextDouble() * Math.PI * 2;
        var bone = new net.minecraft.world.entity.item.ItemEntity(level, at.x, at.y, at.z,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BONE),
            Math.cos(a) * 0.075, 0.2, Math.sin(a) * 0.075);
        bone.setNeverPickUp();
        level.addFreshEntity(bone);
        bones.add(bone);
        run.schedule(BONE_TICKS, () -> {
            bones.remove(bone);
            bone.discard();
        });
    }

    private final List<Entity> bones = new ArrayList<>();

    /** Fels lying dormant as an Enderman head until a player stands close (FelsMarkerImpl). */
    private record FelsMarker(ArmorStand marker, Vec3 at, float yaw, RoomState room, boolean counts,
                              MobStore.Spawn spawn, String nametag) {}

    /** SkyBlock 0.27.2 raised Fels' activation range by 5 blocks (was 5). */
    private static final double FELS_ACTIVATION_RADIUS = 10.0;
    private static final double FELS_MARKER_Y_OFFSET = -1.5;
    private static final double IMPACT_RADIUS = 7.0;

    private final DungeonRun run;
    private final AccessoryHits accessoryHits = new AccessoryHits();
    private final ServerLevel level;
    private final Random random;
    private final MobStore store = MobStore.get();
    private final Map<Entity, Tracked> mobs = new HashMap<>();
    /** Each live mob's nametag stand, back to the mob. */
    private final Map<Entity, Entity> tagOwners = new HashMap<>();
    /** "tag id:player" for stands a player's client already has (see [startedTracking]). */
    private final Set<String> tagsSeen = new HashSet<>();
    private final Set<Entity> retrackQueued = new HashSet<>();
    private final List<FelsMarker> fels = new ArrayList<>();
    /** Killed mobs still playing their death animation. */
    private final List<Entity> dying = new ArrayList<>();
    final SupportOrbs orbs;

    MobSystem(DungeonRun run, Random random) {
        this.run = run;
        this.level = run.level;
        this.random = random;
        this.orbs = new SupportOrbs(run, level, random);
    }

    // ---------------------------------------------------------------- spawning

    /** spawn_room_mobs: one recorded visit's mobs, picked at random. */
    void spawnRoom(RoomState room) {
        if (room.type() == RoomType.ENTRANCE || room.type() == RoomType.FAIRY) {
            // Hypixel never spawns mobs in the entrance or the fairy room; the one recorded entrance visit with
            // mobs caught a Withermancer and a Skeleton Soldier that had followed the player in.
            return;
        }
        // The room's own rolls, from the dungeon's seed: a replayed map spawns the same mobs.
        Random rolls = run.derived("mobs", room.index);
        MobStore.Spots spots = store.spots(room.data().name());
        List<List<MobStore.Spawn>> runs = new ArrayList<>();
        Float miniYaw = null;
        for (List<MobStore.Spawn> visit : store.runs(room.data().name())) {
            List<MobStore.Spawn> kept = new ArrayList<>();
            for (MobStore.Spawn spawn : visit) {
                if (!spots.minis().isEmpty() && isMiniboss(spawn)) {
                    miniYaw = spawn.yaw(); // the miniboss is rolled below, at Hypixel's spot
                } else if (!spots.spawns().isEmpty() ? onRecordedSpawn(spawn, spots)
                    : !spawn.starred() || onHypixelSpot(room, spawn, spots.points())) {
                    kept.add(spawn);
                }
            }
            runs.add(kept);
        }
        if (!runs.isEmpty()) {
            // Many recordings were made after some starred mobs were already dead (most visits to the
            // one-miniboss rooms have no starred mob at all), and a room with none clears the moment
            // it's entered. Pick among the visits with close to the most starred mobs.
            int most = 0;
            for (List<MobStore.Spawn> visit : runs) {
                most = Math.max(most, starred(visit));
            }
            List<List<MobStore.Spawn>> full = new ArrayList<>();
            for (List<MobStore.Spawn> visit : runs) {
                if (starred(visit) * 4 >= most * 3) {
                    full.add(visit);
                }
            }
            for (MobStore.Spawn spawn : full.get(rolls.nextInt(full.size()))) {
                spawnOne(room, spawn, rolls);
            }
        }
        // Hypixel's normal rooms with a miniboss always have one at the same block, rolled from the four kinds.
        for (int[] spot : spots.minis()) {
            BlockPos at = room.placed.toWorld(spot[0], spot[1], spot[2]);
            float yaw = room.placed.facing().rotateYaw(miniYaw != null ? miniYaw : 0f);
            spawnMiniboss(room, rollMiniboss(rolls), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, rolls);
        }
    }

    private static boolean isMiniboss(MobStore.Spawn spawn) {
        Archetype a = Archetype.of(spawn.fullName());
        return a == Archetype.LOST_ADVENTURER || a == Archetype.FROZEN_ADVENTURER
            || a == Archetype.ANGRY_ARCHAEOLOGIST || a == Archetype.SHADOW_ASSASSIN;
    }

    /**
     * Whether a stored mob (any kind, starred or not) stands within the room's radius (5 blocks, 7 for rooms seen in
     * only 2-3 recordings; 4 up or down) of a block the Hypixel recordings saw one of the room's mobs spawn on. The
     * stored visits caught mobs that had walked in from the next room before they were recorded (2.2% of them, e.g.
     * Withermancers in Lava Ravine's doorways); Hypixel never spawns any there. Rooms the recordings saw too rarely
     * fall back to the site's starred spots below.
     */
    private static boolean onRecordedSpawn(MobStore.Spawn spawn, MobStore.Spots spots) {
        double r2 = spots.spawnRadius() * spots.spawnRadius();
        for (int[] p : spots.spawns()) {
            double dx = spawn.x() - p[0];
            double dz = spawn.z() - p[2];
            if (dx * dx + dz * dz <= r2 && Math.abs(spawn.y() - p[1]) <= 4) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a recorded starred spawn is within 3 blocks (and 4 up or down) of a block Hypixel spawns starred mobs on.
     * The recordings caught mobs that had followed a player in from the next room; Hypixel never spawns any there.
     * (The points are starred spawns only, so unstarred mobs can't be checked and are kept.)
     * No points (a room seen too rarely): keep everything.
     */
    private static boolean onHypixelSpot(RoomState room, MobStore.Spawn spawn, List<int[]> points) {
        if (points.isEmpty()) {
            return true;
        }
        double y = room.data().bottom() + spawn.y();
        for (int[] p : points) {
            double dx = spawn.x() - (p[0] + 0.5);
            double dz = spawn.z() - (p[2] + 0.5);
            if (dx * dx + dz * dz <= 9 && Math.abs(y - p[1]) <= 4) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which miniboss stands at a normal room's spot: Shadow Assassin 36%, Lost Adventurer 34%, Frozen Adventurer 14%,
     * Angry Archaeologist 16% (the BR roles site's 1,032 sightings, Lost/Frozen split 2.4:1 by the measured HP
     * samples in mobs-spawns-pathing.md §2). OPEN: 71
     */
    private static Archetype rollMiniboss(Random rolls) {
        int r = rolls.nextInt(100);
        if (r < 36) {
            return Archetype.SHADOW_ASSASSIN;
        }
        if (r < 70) {
            return Archetype.LOST_ADVENTURER;
        }
        return r < 84 ? Archetype.FROZEN_ADVENTURER : Archetype.ANGRY_ARCHAEOLOGIST;
    }

    private static int starred(List<MobStore.Spawn> visit) {
        int n = 0;
        for (MobStore.Spawn spawn : visit) {
            if (spawn.starred() && Archetype.of(spawn.fullName()) != null) {
                n++;
            }
        }
        return n;
    }

    private void spawnOne(RoomState room, MobStore.Spawn spawn, Random rolls) {
        Archetype archetype = Archetype.of(spawn.fullName());
        PlacedRoom placed = room.placed;
        Facing facing = placed.facing();
        // X/Z are offsets from the room's corner, Y from the room data's floor.
        double x = placed.cornerX() + rotateX(facing, spawn.x(), spawn.z());
        double z = placed.cornerZ() + rotateZ(facing, spawn.x(), spawn.z());
        double y = room.data().bottom() + spawn.y();
        float yaw = facing.rotateYaw(spawn.yaw());

        String star = spawn.starred() ? "§6✯ " : "";
        String nametag = star + "§c" + spawn.fullName();
        // Only a recognised archetype can be killed, so only those count toward clearing the room.
        boolean counts = spawn.starred() && archetype != null;
        if (counts) {
            room.starredMobsRemaining++;
        }
        if (archetype == Archetype.FELS) {
            ArmorStand marker = new ArmorStand(EntityTypes.ARMOR_STAND, level);
            marker.snapTo(x, y + FELS_MARKER_Y_OFFSET, z, yaw, 0f);
            marker.setInvisible(true);
            marker.setNoGravity(true);
            marker.setPermanentlyInvulnerable(true);
            marker.setItemSlot(EquipmentSlot.HEAD, EngineItems.head(store.skin("MHF_ENDERMAN_SKIN_TEXTURE"), null));
            level.addFreshEntity(marker);
            fels.add(new FelsMarker(marker, new Vec3(x, y, z), yaw, room, counts, spawn, nametag));
            return;
        }
        spawnActive(room, archetype, spawn, x, y, z, yaw, nametag, counts, rolls);
    }

    /** spawn_active_mob: the entity itself, dressed, named and tracked; its modifier rolled from [rolls]. */
    private Tracked spawnActive(RoomState room, Archetype archetype, MobStore.Spawn spawn, double x, double y, double z,
                                float yaw, String nametag, boolean counts, Random rolls) {
        Kind kind = archetype != null ? archetype.kind : Kind.of(spawn.type());
        LivingEntity living;
        if (kind == Kind.PLAYER && archetype != null) {
            Mannequin npc = new Mannequin(EntityTypes.MANNEQUIN, level);
            npc.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(
                EngineItems.profile(npcName(archetype), skinOf(archetype, spawn)[0], skinOf(archetype, spawn)[1])));
            npc.setNoGravity(true);
            ((MannequinAccessor) npc).rustclear$setImmovable(true);
            living = npc;
        } else {
            EntityType<? extends Mob> type = switch (kind) {
                case SKELETON -> archetype == Archetype.WITHERMANCER || archetype == Archetype.WITHER_MINER
                    || archetype == Archetype.WITHER_GUARD ? EntityTypes.WITHER_SKELETON : EntityTypes.SKELETON;
                case ENDERMAN -> EntityTypes.ENDERMAN;
                case GIANT -> EntityTypes.GIANT;
                case SILVERFISH -> EntityTypes.SILVERFISH;
                default -> EntityTypes.ZOMBIE;
            };
            Mob mob = type.create(level, EntitySpawnReason.TRIGGERED);
            if (mob == null) {
                return null;
            }
            mob.setNoAi(true);
            mob.setPersistenceRequired();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                mob.setDropChance(slot, 0f);
            }
            living = mob;
        }
        // Hypixel names a mob with an invisible armour stand created right after it, so the stand's
        // entity id is the mob's + 1 (+ 3 for a Withermancer, whose two wither skulls come between).
        // Odin, NoammAddons and the other star-mob highlights find the mob from that stand.
        if (archetype == Archetype.WITHERMANCER) {
            new ArmorStand(EntityTypes.ARMOR_STAND, level);
            new ArmorStand(EntityTypes.ARMOR_STAND, level);
        }
        ArmorStand tag = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        living.snapTo(x, y, z, yaw, 0f);
        if (kind != Kind.GIANT) {
            Vec3 free = freeSpot(living);
            x = free.x;
            y = free.y;
            z = free.z;
            living.snapTo(x, y, z, yaw, 0f);
        }
        living.setYHeadRot(yaw);
        living.setYBodyRot(yaw);
        living.setPermanentlyInvulnerable(true);
        living.setSilent(true);
        dress(living, spawn, archetype);
        if (archetype == Archetype.FELS) {
            // Upside down like the Dinnerbone trick.
            living.setCustomName(EngineItems.text("Dinnerbone"));
            living.setCustomNameVisible(false);
        }
        ((ArmorStandAccessor) tag).rustclear$setMarker(true);
        tag.setInvisible(true);
        tag.setNoGravity(true);
        tag.setPermanentlyInvulnerable(true);
        tag.setCustomNameVisible(true);
        tag.snapTo(x, y + living.getBbHeight(), z, 0f, 0f);
        level.addFreshEntity(living);
        level.addFreshEntity(tag);
        Tracked tracked = new Tracked(room, archetype, counts, tag);
        Modifier modifier = takesModifier(archetype) ? Modifier.roll(rolls) : null;
        tracked.modifier = modifier;
        tracked.ai = new MobAi(archetype, new Vec3(x, y, z), room == null ? -1 : room.index, kind == Kind.PLAYER,
            modifier == null ? 1.0 : modifier.speedFactor(kind));
        // The floor's health, shown after the name and taken down by every hit.
        tracked.label = modifier == null ? nametag : nametag.replace("§c" + spawn.fullName(),
            modifier.nameColour + modifier.word + " " + spawn.fullName());
        double health = archetype == null ? 0 : archetype.stats(run.floor).health() * (modifier == null ? 1 : modifier.healthFactor());
        // A room a player already entered spawns its mobs already scaled (x1.55 at a tab count of 10 in cce77b).
        double factor = room == null ? 1.0 : roomFactors.getOrDefault(room.index, 1.0);
        tracked.scaled = room == null || roomFactors.containsKey(room.index);
        tracked.maxHealth = Math.round(health * factor);
        tracked.health = tracked.maxHealth;
        tag.setCustomName(tagName(nametagOf(tracked)));
        syncHealth(living, tracked);
        mobs.put(living, tracked);
        tagOwners.put(tag, living);
        return tracked;
    }

    /**
     * Where [living] (already placed) can stand without its hitbox in a block. About 2% of the recordings caught a mob
     * inside one (mid-jump, or pushed into a wall), and MobAi's moves never leave a colliding box, so it would stay
     * stuck. Tries its own spot raised up to a block and a half, then each neighbouring block's centre at its height
     * and one up; its own spot if none is free.
     */
    private Vec3 freeSpot(LivingEntity living) {
        Vec3 at = living.position();
        if (level.noCollision(living, living.getBoundingBox())) {
            return at;
        }
        List<Vec3> tries = new ArrayList<>();
        for (double up = 0.5; up <= 1.5; up += 0.5) {
            tries.add(at.add(0, up, 0));
        }
        double cx = Math.floor(at.x) + 0.5;
        double cz = Math.floor(at.z) + 0.5;
        for (double up = 0; up <= 1; up++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    tries.add(new Vec3(cx + dx, Math.floor(at.y) + up, cz + dz));
                }
            }
        }
        double half = living.getBbWidth() / 2;
        for (Vec3 t : tries) {
            AABB box = new AABB(t.x - half, t.y, t.z - half, t.x + half, t.y + living.getBbHeight(), t.z + half);
            if (level.noCollision(living, box)) {
                return t;
            }
        }
        return at;
    }

    /**
     * A mob's name as the star-mob highlights read it: styled text (Odin matches the plain string
     * against "✯ ... 1.2M❤"; NoammAddons rebuilds the codes and wants it to end in "§c❤"), led by a
     * space because Devonian ignores a name whose ✯ is the very first character.
     */
    private static Component tagName(String legacy) {
        return EngineItems.styled(" " + legacy);
    }

    /** [tracked]'s nametag text: its label and the health it has left (none for an unknown mob). */
    private static String nametagOf(Tracked tracked) {
        if (tracked.archetype == null) {
            return tracked.label;
        }
        if (tracked.levelled) {
            String now = tracked.health > 0 ? "§a" + Stat.number(tracked.health) : "§c0";
            return tracked.label + " " + now + "§f/§a" + Stat.number(tracked.maxHealth) + "§c❤";
        }
        if (tracked.camp) {
            return tracked.label + " " + (tracked.health > 0 ? "§a" + formatHealth(tracked.health) : "§c0") + "§c❤";
        }
        String hp = tracked.modifier == null ? "§a" : tracked.modifier.hpColour;
        return tracked.label + " " + hp + formatHealth(Math.max(0, tracked.health)) + "§c❤";
    }

    /** Hypixel puts a mob's real HP in its health data (index 9), where mods read it. */
    private static void syncHealth(Entity entity, Tracked tracked) {
        if (entity instanceof LivingEntity living && tracked.archetype != null) {
            living.getEntityData().set(net.rustclear.mod.mixin.LivingEntityAccessor.rustclear$healthId(),
                (float) Math.max(0, tracked.health));
        }
    }

    // ---------------------------------------------------------------- room-entry scaling

    /** Each entered room's HP factor, 1 + 0.05 k, by room index. */
    private final Map<Integer, Double> roomFactors = new HashMap<>();
    /** Scaling lands a median 8 ticks after a player walks in (5 on M7). */
    private static final int SCALE_DELAY_F7 = 8;
    private static final int SCALE_DELAY_M7 = 5;

    /**
     * A player walked into [room]: a moment later every undamaged mob of the room has its HP
     * multiplied by 1 + 0.05 k (mobs-spawns-pathing.md §2; factors 1.05-2.5 on F7, up to 3.09 on
     * M7), and the room's later mobs spawn at that factor.
     * OPEN: 5 (what k counts); the simplest model that fits: k = the rooms cleared so far, which
     * the measured k tracks within -1..+2 most of the time.
     */
    void scaleOnEntry(RoomState room) {
        if (!EngineServer.settings().roomHpScalingOrDefault() || roomFactors.containsKey(room.index)) {
            return;
        }
        int delay = run.floor.master() ? SCALE_DELAY_M7 : SCALE_DELAY_F7;
        run.schedule(delay, () -> {
            double factor = 1 + 0.05 * run.score.clearedRooms;
            roomFactors.put(room.index, factor);
            for (Map.Entry<Entity, Tracked> e : mobs.entrySet()) {
                Tracked t = e.getValue();
                if (t.room == null || t.room != room || t.scaled || t.archetype == null) {
                    continue;
                }
                t.scaled = true;
                if (t.health < t.maxHealth) {
                    // Only undamaged mobs are scaled.
                    continue;
                }
                t.maxHealth = Math.round(t.maxHealth * factor);
                t.health = t.maxHealth;
                t.tag.setCustomName(tagName(nametagOf(t)));
                syncHealth(e.getKey(), t);
            }
            SimLog.event("room_scale", "room", room.data().name(), "factor", factor);
        });
    }

    /** Keeps a mob's nametag stand just above its head, inside the box a block above it where the mods look. */
    private static void placeTag(ArmorStand tag, LivingEntity living) {
        tag.teleportTo(living.getX(), living.getY() + living.getBbHeight(), living.getZ());
    }

    /** convert_equipment: the recorded armour and held item, plus the Sniper's head. */
    private void dress(LivingEntity living, MobStore.Spawn spawn, Archetype archetype) {
        for (Map.Entry<String, MobStore.Piece> e : spawn.equipment().entrySet()) {
            EquipmentSlot slot = switch (e.getKey()) {
                case "mainhand" -> EquipmentSlot.MAINHAND;
                case "head" -> EquipmentSlot.HEAD;
                case "chest" -> EquipmentSlot.CHEST;
                case "legs" -> EquipmentSlot.LEGS;
                case "feet" -> EquipmentSlot.FEET;
                default -> null;
            };
            if (slot != null) {
                living.setItemSlot(slot, piece(e.getValue()));
            }
        }
        if (archetype == Archetype.SNIPER) {
            living.setItemSlot(EquipmentSlot.HEAD, EngineItems.head(store.skin("SNIPER_SKULL_TEXTURE"), null));
        }
        // The weapons they fight with (MobAi) are the players' own items.
        ItemStack held = living.getMainHandItem();
        String weapon = held.is(Items.BONE) ? "BONE_BOOMERANG"
            : archetype == Archetype.ZOMBIE_COMMANDER && held.is(Items.FISHING_ROD) ? "ZOMBIE_COMMANDER_WHIP"
            : archetype == Archetype.CRYPT_DREADLORD && held.is(Items.IRON_SWORD) ? "CRYPT_DREADLORD_SWORD"
            : null;
        ItemDefs.Def def = weapon == null ? null : ItemDefs.get().def(weapon);
        if (def != null) {
            living.setItemSlot(EquipmentSlot.MAINHAND, DataItems.stack(new ItemDefs.Instance(def, 0, 0, 7, 0, List.of()), 0, false));
        }
    }

    private static ItemStack piece(MobStore.Piece piece) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(piece.id()));
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        if (item == Items.PLAYER_HEAD && piece.skull() != null) {
            return EngineItems.head(piece.skull(), piece.name());
        }
        ItemStack stack = new ItemStack(item);
        if (piece.name() != null) {
            stack.set(DataComponents.CUSTOM_NAME, EngineItems.text(piece.name()));
        }
        if (piece.color() != null && piece.color() != 0) {
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(piece.color()));
        }
        return stack;
    }

    /** {textures, signature}: the archetype's own skin, a Lost Adventurer's by helmet, else the recorded one. */
    private String[] skinOf(Archetype archetype, MobStore.Spawn spawn) {
        String constant = archetype.skinConstant();
        if (archetype == Archetype.LOST_ADVENTURER) {
            MobStore.Piece head = spawn.equipment().get("head");
            String helmet = head == null || head.name() == null ? "" : head.name().replaceAll("§.", "");
            constant = switch (helmet) {
                case "Young Dragon Helmet", "Superior Dragon Helmet" -> "LOST_ADVENTURER_YOUNG_SUPERIOR_SKIN";
                case "Holy Dragon Helmet" -> "LOST_ADVENTURER_HOLY_SKIN";
                case "Unstable Dragon Helmet" -> "LOST_ADVENTURER_UNSTABLE_SKIN";
                default -> null;
            };
        }
        if (constant != null && store.skin(constant) != null) {
            return new String[] {store.skin(constant), store.skin(constant + "_SIGNATURE")};
        }
        return new String[] {spawn.skin() == null ? "" : spawn.skin(), spawn.signature()};
    }

    private static String npcName(Archetype archetype) {
        String name = archetype.name().toLowerCase(Locale.ROOT);
        return name.length() > 16 ? name.substring(0, 16) : name;
    }

    // ---------------------------------------------------------------- ticking and killing

    void tick() {
        orbs.tick();
        for (Map.Entry<Entity, Tracked> e : new ArrayList<>(mobs.entrySet())) {
            Tracked tracked = e.getValue();
            if (!e.getKey().isRemoved() && tracked.room != null && !inMap(e.getKey())) {
                // Pushed or knocked out of the dungeon: it would fall forever and keep its room
                // from clearing, so it dies where it is.
                kill(e.getKey());
                continue;
            }
            if (tracked.ai == null || !(e.getKey() instanceof LivingEntity living) || living.isRemoved()) {
                continue;
            }
            if (MobAi.inLava(living)) {
                // run_mob_ai: touching lava kills a dungeon mob outright.
                kill(living);
                continue;
            }
            ServerPlayer noticed = tracked.ai.tick(living, run, random);
            placeTag(tracked.tag, living);
            if (noticed != null) {
                // aggro.rs: a mob that spots a player calls in the others close by.
                for (Map.Entry<Entity, Tracked> other : mobs.entrySet()) {
                    if (other.getValue().ai != null && other.getKey().distanceTo(living) <= MobAi.AGGRO_RADIUS) {
                        other.getValue().ai.aggro(noticed);
                    }
                }
            }
        }
        for (Iterator<FelsMarker> it = fels.iterator(); it.hasNext(); ) {
            FelsMarker f = it.next();
            boolean near = false;
            for (ServerPlayer player : level.players()) {
                if (player.position().distanceTo(f.at) <= FELS_ACTIVATION_RADIUS) {
                    near = true;
                    break;
                }
            }
            if (near) {
                it.remove();
                f.marker.discard();
                spawnActive(f.room, Archetype.FELS, f.spawn, f.at.x, f.at.y, f.at.z, f.yaw, f.nametag, f.counts, random);
            }
        }
    }

    /**
     * A player's client started tracking [entity]. NoammAddons (and others) look up a starred mob
     * once, when its nametag stand's name arrives, at the stand's id - 1; if the client got the
     * stand before the mob (the server sends newly tracked entities in no set order), that lookup
     * fails for good. So when a mob reaches a client that already has its stand, the stand is
     * removed and sent again, after the mob.
     */
    void startedTracking(Entity entity, ServerPlayer player) {
        if (tagOwners.containsKey(entity)) {
            tagsSeen.add(entity.getId() + ":" + player.getUUID());
            return;
        }
        Tracked tracked = mobs.get(entity);
        if (tracked != null && tagsSeen.contains(tracked.tag.getId() + ":" + player.getUUID())
            && retrackQueued.add(tracked.tag)) {
            ArmorStand tag = tracked.tag;
            // Not now: this runs while the server walks its tracked entities.
            run.schedule(1, () -> {
                retrackQueued.remove(tag);
                if (!tag.isRemoved()) {
                    level.getChunkSource().removeEntity(tag);
                    level.getChunkSource().addEntity(tag);
                }
            });
        }
    }

    void stoppedTracking(Entity entity, ServerPlayer player) {
        tagsSeen.remove(entity.getId() + ":" + player.getUUID());
    }

    /** Whether [entity] is one of this run's mobs. */
    boolean isMob(Entity entity) {
        return mobs.containsKey(entity);
    }

    /** How a hit lands: a melee swing, an arrow, or an item ability that hits like a swing. */
    enum Hit { MELEE, ARROW, ABILITY }

    /** A melee hit by [player], with anything in hand (or nothing): their hit from their stats. */
    void attack(ServerPlayer player, Entity entity) {
        hit(player, entity, 1.0, Hit.MELEE, false);
        knockBack(player, entity);
    }

    /** Mobs move only a little when hit: a median 0.094 b/t (mobs-spawns-pathing.md §7). */
    private static final double KNOCKBACK = 0.094;

    private void knockBack(ServerPlayer player, Entity entity) {
        if (!(entity instanceof Mob mob) || entity.isRemoved() || !mobs.containsKey(entity)) {
            return;
        }
        Vec3 away = entity.position().subtract(player.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1.0e-6) {
            return;
        }
        mob.move(net.minecraft.world.entity.MoverType.SELF, away.normalize().scale(KNOCKBACK));
    }

    /** One of [player]'s arrows: an Archer's arrow multiplier and Doubleshot; the Terminator crits a quarter as often. */
    void arrow(ServerPlayer player, Entity entity, double multiplier, boolean terminator) {
        hit(player, entity, multiplier, Hit.ARROW, terminator);
    }

    /** An item ability's hit: [multiplier] times the player's hit. */
    void hit(ServerPlayer player, Entity entity, double multiplier) {
        hit(player, entity, multiplier, Hit.ABILITY, false);
    }

    /**
     * [player] hits [entity] for [multiplier] times their hit (CombatMath: weapon Damage, Strength,
     * additive damage, a crit rolled on their Crit Chance). The mob turns on them and calls in the
     * others close by; King Midas only loses a piece.
     */
    void hit(ServerPlayer player, Entity entity, double multiplier, Hit how, boolean terminator) {
        Tracked hit = mobs.get(entity);
        if (hit == null) {
            return;
        }
        if (hit.ai != null) {
            // aggro::on_mob_attacked: the attacker becomes its target and nearby mobs are called in.
            hit.ai.attacked(player);
            for (Map.Entry<Entity, Tracked> other : mobs.entrySet()) {
                if (other.getValue().ai != null && other.getKey().distanceTo(entity) <= MobAi.AGGRO_RADIUS) {
                    other.getValue().ai.aggro(player);
                }
            }
        }
        if (kingMidasHit(entity)) {
            return;
        }
        StatSheet stats = PlayerStats.of(player);
        CombatMath.Kind kind = how == Hit.ARROW ? CombatMath.Kind.ARROW : CombatMath.Kind.MELEE;
        double roll = random.nextDouble();
        boolean crit = roll < CombatMath.critChance(stats, terminator ? 0.25 : 1);
        double amount = CombatMath.rolledHit(stats, kind, terminator ? 0.25 : 1, roll) * multiplier;
        int extra = 0;
        if (how == Hit.MELEE) {
            if (stats.dungeonClass == DungeonClass.MAGE) {
                // Mage Staff: a Mage's melee is a beam, a share of the hit by Intelligence.
                amount *= CombatMath.beamShare(stats);
            }
            amount *= run.abilities().meleeBonus(player, entity, stats);
            extra = CombatMath.ferocityHits(stats, random.nextDouble());
        } else if (how == Hit.ARROW) {
            // Doubleshot: an extra arrow's hit, as a chance (over 100%, one sure and the rest a chance).
            double chance = stats.classBonus.extraArrowChance();
            extra = (int) chance + (random.nextDouble() < chance % 1 ? 1 : 0);
        }
        amount *= accessoryHits.factor(player, entity, stats, how == Hit.MELEE, how != Hit.ABILITY, run.ticks(), random);
        long each = Math.max(1, Math.round(amount));
        creditTo = player;
        creditRanged = how == Hit.ARROW;
        creditCrit = crit;
        try {
            damage(entity, each);
            for (int i = 0; i < extra && mobs.containsKey(entity); i++) {
                damage(entity, each);
            }
        } finally {
            creditTo = null;
            creditCrit = false;
        }
        if (!mobs.containsKey(entity)) {
            accessoryHits.killed(player, run.ticks());
        }
        run.abilities().recordHit(player, each, !mobs.containsKey(entity));
    }

    /** The damage [player]'s held item's ability does: its base (with the Catacombs bonus) by Intelligence, else Wither Impact's. */
    static double abilityDamage(ServerPlayer player) {
        StatSheet stats = PlayerStats.of(player);
        ItemDefs.Instance held = PlayerStats.instance(player.getMainHandItem());
        if (held != null && held.def().abilityDamage != null) {
            ItemDefs.Def def = held.def();
            boolean dungeon = def.nativeDungeon || held.stars() > 0;
            double base = def.abilityDamage * (dungeon ? 1 + ItemStats.cataBonus(stats.cata) / 100.0 : 1);
            return CombatMath.abilityHit(stats, base, def.abilityScaling == null ? 0 : def.abilityScaling);
        }
        return CombatMath.witherImpact(stats);
    }

    /**
     * A fixed [amount] from [player] that doesn't come from their stats (a ghost's Fireball or Ghost Axe): the mob
     * turns on them as for any hit. Returns the damage dealt (0 for a non-run entity).
     */
    long hitFlat(ServerPlayer player, Entity entity, double amount) {
        Tracked hit = mobs.get(entity);
        if (hit == null) {
            return 0;
        }
        if (hit.ai != null) {
            hit.ai.attacked(player);
        }
        if (kingMidasHit(entity)) {
            return 0;
        }
        long each = Math.max(1, Math.round(amount));
        damage(entity, each);
        return each;
    }

    /** Stuns every run mob within [radius] of [at] for [ticks]. */
    void stun(Vec3 at, double radius, int ticks) {
        for (Map.Entry<Entity, Tracked> e : mobs.entrySet()) {
            if (e.getValue().ai != null && e.getKey().position().distanceTo(at) <= radius) {
                e.getValue().ai.stun(ticks);
            }
        }
    }

    /** The live mobs (not dying) whose feet are inside [box]. */
    List<Entity> mobsIn(AABB box) {
        List<Entity> out = new ArrayList<>();
        for (Entity entity : mobs.keySet()) {
            if (!entity.isRemoved() && box.contains(entity.position())) {
                out.add(entity);
            }
        }
        return out;
    }

    /** The live mobs whose box, grown by [grow], the segment [from]..[to] passes through, nearest first. */
    List<Entity> mobsAlong(Vec3 from, Vec3 to, double grow) {
        List<Map.Entry<Double, Entity>> hits = new ArrayList<>();
        for (Entity entity : mobs.keySet()) {
            if (entity.isRemoved()) {
                continue;
            }
            AABB box = entity.getBoundingBox().inflate(grow);
            if (box.contains(from)) {
                hits.add(Map.entry(0.0, entity));
                continue;
            }
            box.clip(from, to).ifPresent(at -> hits.add(Map.entry(at.distanceToSqr(from), entity)));
        }
        hits.sort(Map.Entry.comparingByKey());
        List<Entity> out = new ArrayList<>(hits.size());
        hits.forEach(e -> out.add(e.getValue()));
        return out;
    }

    /**
     * spawn_crypt_undead and spawn_king_midas: a mob from a Superboomed crypt, standing at its
     * middle. [onDeath] runs when it dies.
     */
    void spawnFromCrypt(RoomState room, Archetype archetype, double x, double y, double z, Runnable onDeath) {
        Map<String, MobStore.Piece> gear = new HashMap<>();
        String nametag;
        boolean counts = archetype == Archetype.KING_MIDAS;
        if (counts) {
            gear.put("mainhand", new MobStore.Piece("minecraft:golden_sword", null, null, null));
            gear.put("head", new MobStore.Piece("minecraft:golden_helmet", null, null, null));
            gear.put("chest", new MobStore.Piece("minecraft:golden_chestplate", null, null, null));
            gear.put("legs", new MobStore.Piece("minecraft:golden_leggings", null, null, null));
            gear.put("feet", new MobStore.Piece("minecraft:golden_boots", null, null, null));
            nametag = "§c§l" + archetype.fullName;
            // He counts toward clearing the room like a starred mob.
            room.starredMobsRemaining++;
        } else {
            gear.put("mainhand", new MobStore.Piece("minecraft:bone", null, null, null));
            nametag = "§c" + archetype.fullName;
        }
        MobStore.Spawn spawn = new MobStore.Spawn("player", false, archetype.fullName, 0, 0, 0, 0f, gear, null, null);
        Tracked tracked = spawnActive(room, archetype, spawn, x, y, z, 0f, nametag, counts, random);
        if (tracked != null) {
            tracked.onDeath = onDeath;
        }
    }

    /**
     * The yellow rooms' entry-time minibosses (shadow_assassin.rs, default_room.rs, dragon.rs,
     * default_dirt.rs): one boss standing at the room's spot, facing back toward the door.
     */
    void roomEntered(RoomState room) {
        Random rolls = run.derived("mini", room.index);
        String name = room.data().name();
        String id = room.data().id();
        Archetype[] random4 = {Archetype.SHADOW_ASSASSIN, Archetype.LOST_ADVENTURER, Archetype.FROZEN_ADVENTURER,
            Archetype.ANGRY_ARCHAEOLOGIST};
        BlockPos spot;
        Archetype boss;
        if (name.equals("Shadow Assassin")) {
            spot = room.placed.toWorld(15, 69, 15);
            boss = Archetype.SHADOW_ASSASSIN;
        } else if (name.equals("Default") && id.equals("246,102")) {
            spot = room.placed.toWorld(12, 63, 22);
            boss = Archetype.LOST_ADVENTURER;
        } else if (name.equals("Dragon")) {
            spot = room.placed.toWorld(15, 69, 11);
            boss = random4[rolls.nextInt(4)];
        } else if (name.equals("Default") && id.equals("210,66")) {
            spot = room.placed.toWorld(13, 70, 5);
            boss = random4[rolls.nextInt(4)];
        } else {
            return;
        }
        spawnMiniboss(room, boss, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, room.placed.facing().rotateYaw(180f), rolls);
    }

    /** spawn_shadow_assassin, spawn_lost_adventurer, spawn_frozen_adventurer, spawn_angry_archaeologist. */
    private void spawnMiniboss(RoomState room, Archetype boss, double x, double y, double z, float yaw, Random rolls) {
        Map<String, MobStore.Piece> gear = new HashMap<>();
        String hp = "";
        String nametag;
        switch (boss) {
            case SHADOW_ASSASSIN -> {
                gear.put("mainhand", new MobStore.Piece("minecraft:iron_sword", null, null, null));
                gear.put("feet", new MobStore.Piece("minecraft:leather_boots", null, 0x4B0055, null));
                nametag = "§d§l" + boss.fullName + hp;
            }
            case LOST_ADVENTURER -> {
                armour(gear, "Young Dragon", 0xDDE4F0, "LOST_ADVENTURER_YOUNG_DRAGON_HELMET_ICON");
                gear.put("mainhand", new MobStore.Piece("minecraft:diamond_sword", "§6Aspect of the Dragons", null, null));
                nametag = "§6✯ §c" + boss.fullName + hp;
            }
            case FROZEN_ADVENTURER -> {
                armour(gear, "Frozen Blaze", 0xA0DAEF, "FROZEN_BLAZE_HELMET_ICON");
                gear.put("mainhand", new MobStore.Piece("minecraft:stick", "§9Ice Spray Wand", null, null));
                nametag = "§6✯ §c" + boss.fullName + hp;
            }
            default -> {
                gear.put("head", new MobStore.Piece("minecraft:diamond_helmet", "§6Perfect Helmet - Tier XII", null, null));
                gear.put("chest", new MobStore.Piece("minecraft:diamond_chestplate", "§6Perfect Chestplate - Tier XII", null, null));
                gear.put("legs", new MobStore.Piece("minecraft:diamond_leggings", "§6Perfect Leggings - Tier XII", null, null));
                gear.put("feet", new MobStore.Piece("minecraft:diamond_boots", "§6Perfect Boots - Tier XII", null, null));
                gear.put("mainhand", new MobStore.Piece("minecraft:diamond_sword", "§aDiamond Sword", null, null));
                nametag = "§c" + boss.fullName + hp;
            }
        }
        room.starredMobsRemaining++;
        MobStore.Spawn spawn = new MobStore.Spawn("player", true, boss.fullName, 0, 0, 0, 0f, gear, null, null);
        Tracked tracked = spawnActive(room, boss, spawn, x, y, z, yaw, nametag, true, rolls);
        if (tracked != null && boss == Archetype.SHADOW_ASSASSIN) {
            // Unseen until its first ambush.
            for (Map.Entry<Entity, Tracked> e : mobs.entrySet()) {
                if (e.getValue() == tracked) {
                    e.getKey().setInvisible(true);
                }
            }
        }
    }

    /**
     * One of the Watcher's mobs (blood-watcher.md §4), in [room] (the Blood room): spawned like a
     * room's mob, so every hit, arrow and ability reaches it, but with the camp's own HP (no room
     * scaling), tag and modifier. It counts toward no room; [onDeath] runs when it dies.
     */
    LivingEntity spawnCamp(RoomState room, net.rustclear.mod.engine.blood.CampMob mob, double x, double y, double z,
                           float yaw, Runnable onDeath) {
        Archetype archetype = switch (mob.type()) {
            case NORMAL -> Archetype.BLOOD_UNDEAD;
            case BOSS -> Archetype.BLOOD_BOSS;
            case GIANT -> Archetype.BLOOD_GIANT;
            case SILVERFISH -> Archetype.PARASITE;
            case WITHER_MINER -> Archetype.WITHER_MINER;
            case WITHER_GUARD -> Archetype.WITHER_GUARD;
        };
        Map<String, MobStore.Piece> gear = new HashMap<>();
        if (mob.mainHand() != null) {
            gear.put("mainhand", new MobStore.Piece(mob.mainHand(), null, null, null));
        }
        if (mob.feet() != null) {
            gear.put("feet", new MobStore.Piece(mob.feet(), null, null, null));
        }
        MobStore.Spawn spawn = new MobStore.Spawn(archetype.kind == Kind.PLAYER ? "player" : "zombie", false,
            archetype.fullName, 0, 0, 0, 0f, gear, mob.skin(), mob.signature());
        Tracked tracked = spawnActive(room, archetype, spawn, x, y, z, yaw, mob.label(), false, random);
        if (tracked == null) {
            return null;
        }
        tracked.camp = true;
        tracked.levelled = mob.type() == net.rustclear.mod.engine.blood.CampMob.Type.SILVERFISH;
        tracked.killer = mob.killer();
        tracked.modifier = null;
        tracked.label = mob.label();
        tracked.scaled = true;
        tracked.maxHealth = Math.round(mob.health());
        tracked.health = tracked.maxHealth;
        tracked.onDeath = onDeath;
        tracked.ai = new MobAi(archetype, new Vec3(x, y, z), room == null ? -1 : room.index, archetype.kind == Kind.PLAYER, mob.speedFactor());
        tracked.ai.holdUntilLanded = true;
        tracked.tag.setCustomName(tagName(nametagOf(tracked)));
        for (Map.Entry<Entity, Tracked> e : mobs.entrySet()) {
            if (e.getValue() == tracked) {
                syncHealth(e.getKey(), tracked);
                if (mob.invisible()) {
                    e.getKey().setInvisible(true);
                }
                return (LivingEntity) e.getKey();
            }
        }
        return null;
    }

    /**
     * Registers a boss's entity (already in the level, named by its own stands) so every hit,
     * arrow and ability reaches it like a mob's, but goes to [onDamage]; it never dies here.
     */
    void registerBoss(LivingEntity entity, java.util.function.LongConsumer onDamage) {
        ArmorStand none = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        Tracked tracked = new Tracked(null, Archetype.BOSS, false, none);
        tracked.onDamage = onDamage;
        tracked.maxHealth = 1;
        tracked.health = 1;
        tracked.scaled = true;
        mobs.put(entity, tracked);
    }

    /** Forgets a registered boss (its phase removes the entity). */
    void unregister(Entity entity) {
        mobs.remove(entity);
    }

    /** The name a death line gives [entity]'s killer ("Golden Flamer"; else its archetype's), or null. */
    String killerName(Entity entity) {
        Tracked tracked = mobs.get(entity);
        if (tracked == null) {
            return null;
        }
        return tracked.killer != null ? tracked.killer : tracked.archetype == null ? null : tracked.archetype.fullName;
    }

    /** spawn_mimic: a baby zombie in the Mimic's head and tan leather, out of the chest that was opened. */
    void spawnMimic(RoomState room, double x, double y, double z) {
        Map<String, MobStore.Piece> gear = new HashMap<>();
        gear.put("head", new MobStore.Piece("minecraft:player_head", null, null, store.skin("MIMIC_SKULL_TEXTURE")));
        gear.put("chest", new MobStore.Piece("minecraft:leather_chestplate", null, 0xDBCC8F, null));
        gear.put("legs", new MobStore.Piece("minecraft:leather_leggings", null, 0xDBCC8F, null));
        gear.put("feet", new MobStore.Piece("minecraft:leather_boots", null, 0xDBCC8F, null));
        String nametag = "§8[§7Lv" + Archetype.MIMIC.stats(run.floor).level() + "§8]§c§lMimic";
        MobStore.Spawn spawn = new MobStore.Spawn("zombie", false, "Mimic", 0, 0, 0, 0f, gear, null, null);
        Tracked tracked = spawnActive(room, Archetype.MIMIC, spawn, x, y, z, 0f, nametag, false, random);
        if (tracked == null) {
            return;
        }
        tracked.onDeath = () -> run.score.mimicKilled = true;
        for (Map.Entry<Entity, Tracked> e : mobs.entrySet()) {
            if (e.getValue() == tracked && e.getKey() instanceof Mob mob) {
                mob.setBaby(true);
            }
        }
    }

    private void armour(Map<String, MobStore.Piece> gear, String set, int color, String helmetIcon) {
        gear.put("head", new MobStore.Piece("minecraft:player_head", "§6" + set + " Helmet", null, store.skin(helmetIcon)));
        gear.put("chest", new MobStore.Piece("minecraft:leather_chestplate", "§6" + set + " Chestplate", color, null));
        gear.put("legs", new MobStore.Piece("minecraft:leather_leggings", "§6" + set + " Leggings", color, null));
        gear.put("feet", new MobStore.Piece("minecraft:leather_boots", "§6" + set + " Boots", color, null));
    }

    /**
     * apply_king_midas_weapon_hit: any weapon only knocks off one golden armour piece (helmet first)
     * and a fifth of his health; the fifth hit kills him. False for every other mob.
     */
    private boolean kingMidasHit(Entity entity) {
        Tracked tracked = mobs.get(entity);
        if (tracked == null || tracked.archetype != Archetype.KING_MIDAS) {
            return false;
        }
        tracked.hits++;
        if (tracked.hits >= KING_MIDAS_HITS_TO_KILL) {
            kill(entity);
            return true;
        }
        level.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.ITEM_BREAK.value(), SoundSource.HOSTILE, 3f, 1f);
        EquipmentSlot slot = switch (tracked.hits) {
            case 1 -> EquipmentSlot.HEAD;
            case 2 -> EquipmentSlot.CHEST;
            case 3 -> EquipmentSlot.LEGS;
            default -> EquipmentSlot.FEET;
        };
        if (entity instanceof LivingEntity living) {
            living.setItemSlot(slot, ItemStack.EMPTY);
            tracked.health = tracked.maxHealth * (KING_MIDAS_HITS_TO_KILL - tracked.hits) / KING_MIDAS_HITS_TO_KILL;
            tracked.tag.setCustomName(tagName(nametagOf(tracked)));
        }
        return true;
    }

    /** Whether a live mob's box overlaps [box] (a Guided Bat flying into it). */
    boolean touches(AABB box) {
        for (Entity entity : mobs.keySet()) {
            if (entity.getBoundingBox().intersects(box)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wither Impact's implosion (and a Guided Bat's): every mob inside [box] takes the caster's
     * ability damage ([abilityDamage]). The caster is the run's player nearest the middle of [box], which is where
     * Wither Impact lands them and close behind where their bat flies.
     */
    /** What an implosion did: how many mobs it hit and the damage they took (capped by what each had left). */
    record Hits(int count, double damage) {
        static final Hits NONE = new Hits(0, 0);
    }

    Hits killInside(AABB box) {
        Vec3 middle = box.getCenter();
        ServerPlayer caster = null;
        for (ServerPlayer player : level.players()) {
            if (caster == null || player.distanceToSqr(middle) < caster.distanceToSqr(middle)) {
                caster = player;
            }
        }
        if (caster == null) {
            return Hits.NONE;
        }
        long amount = Math.max(1, Math.round(abilityDamage(caster)));
        int count = 0;
        double dealt = 0;
        for (Entity entity : new ArrayList<>(mobs.keySet())) {
            if (box.contains(entity.position()) && !kingMidasHit(entity)) {
                damage(entity, amount);
                count++;
                // Hypixel's line counts the whole hit on each mob, not what was left of its health (real
                // clears read 25-47M an enemy on mobs with far less).
                dealt += amount;
            }
        }
        return new Hits(count, dealt);
    }

    /**
     * Takes [amount] off a mob's health: below zero it dies (kill_mob); otherwise its nametag shows
     * what is left and it flinches. Mobs the run doesn't recognise have no health and die at once.
     */
    /** The run's damage to its mobs and how many it killed (the end block's stats; not split by player). */
    long damageDealt;
    int killed;

    /** Who the damage being dealt is [player]'s (class milestones), and whether it's ranged. */
    private ServerPlayer creditTo;
    private boolean creditRanged;
    /** Whether the hit being dealt is a crit (its damage number is then ✧N✧ in crit colours). */
    private boolean creditCrit;

    /** [by]'s ability hits [entity] for [amount]. */
    void damage(Entity entity, long amount, ServerPlayer by) {
        creditTo = by;
        creditRanged = false;
        try {
            damage(entity, amount);
        } finally {
            creditTo = null;
        }
    }

    void damage(Entity entity, long amount) {
        Tracked tracked = mobs.get(entity);
        if (tracked == null) {
            return;
        }
        if (tracked.onDamage != null || tracked.archetype != null) {
            DamageTags.show(run, entity, amount, creditCrit);
        }
        if (tracked.onDamage != null) {
            if (creditTo != null) {
                // Bosses (OPEN: whether Hypixel caps a boss hit at the HP left, as it does for mobs).
                run.milestones().dealt(creditTo, amount, creditRanged);
            }
            tracked.onDamage.accept(amount);
            if (entity instanceof LivingEntity living) {
                level.getChunkSource().sendToTrackingPlayers(living, new ClientboundHurtAnimationPacket(living));
            }
            return;
        }
        if (tracked.archetype == null) {
            kill(entity);
            return;
        }
        long dealt = Math.min(amount, Math.max(0, (long) tracked.health));
        damageDealt += dealt;
        if (creditTo != null) {
            run.milestones().dealt(creditTo, dealt, creditRanged);
        }
        tracked.health -= amount;
        if (tracked.health <= 0) {
            kill(entity);
            return;
        }
        tracked.tag.setCustomName(tagName(nametagOf(tracked)));
        syncHealth(entity, tracked);
        if (entity instanceof LivingEntity living) {
            level.getChunkSource().sendToTrackingPlayers(living, new ClientboundHurtAnimationPacket(living));
        }
        level.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 0.6f, 1f);
    }

    /** The archetype of a run mob (for its damage), or null. */
    Archetype archetypeOf(Entity entity) {
        Tracked tracked = mobs.get(entity);
        return tracked == null ? null : tracked.archetype;
    }

    /** kill_mob. */
    void kill(Entity entity) {
        Tracked tracked = mobs.remove(entity);
        if (tracked == null) {
            return;
        }
        killed++;
        Vec3 at = entity.position();
        if (tracked.onDamage == null && !(tracked.archetype != null && tracked.archetype.kind == Kind.GIANT)) {
            // Giants (0 of 96) and bosses never dropped one.
            orbs.mobDied(at, creditTo, run.inBoss());
        }
        ServerPlayer killer = creditTo != null ? creditTo : nearestPlayer(at);
        if (tracked.onDamage == null) {
            if (killer != null) {
                run.combat().combatXp(killer, run.inBoss());
            }
        }
        // The death animation (entity event 3) and death_sounds_for, then removed rather than
        // killed so nothing drops.
        level.broadcastEntityEvent(entity, (byte) 3);
        if (tracked.camp) {
            // Camp mobs show "0❤" in red and fall over (health 0 in data 9) until they're removed.
            tracked.health = 0;
            tracked.tag.setCustomName(tagName(nametagOf(tracked)));
            syncHealth(entity, tracked);
        } else {
            tracked.tag.discard();
            tagOwners.remove(tracked.tag);
        }
        Kind kind = tracked.archetype != null ? tracked.archetype.kind : null;
        var species = kind == null ? null : switch (kind) {
            case SKELETON -> SoundEvents.SKELETON_DEATH;
            case ENDERMAN -> SoundEvents.ENDERMAN_DEATH;
            case PLAYER -> SoundEvents.PLAYER_DEATH;
            default -> null;
        };
        if (species != null) {
            level.playSound(null, at.x, at.y, at.z, species, SoundSource.HOSTILE, 1f, 1f);
        }
        level.playSound(null, at.x, at.y, at.z, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.HOSTILE, 1f, 1.492f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.HOSTILE, 0.5f, 1.73f);
        dying.add(entity);
        // Hypixel removes 80% of clear mobs 2 ticks after the death event; only bats, silverfish,
        // giants and blood mobs play vanilla's 20-tick fall (mobs-spawns-pathing.md §6).
        run.schedule(tracked.camp ? CAMP_REMOVE_TICKS : REMOVE_TICKS, () -> {
            dying.remove(entity);
            if (!entity.isRemoved()) {
                if (!tracked.camp) {
                    dropBone(entity.position());
                }
                entity.discard();
            }
            tracked.tag.discard();
            tagOwners.remove(tracked.tag);
        });
        if (tracked.onDeath != null) {
            tracked.onDeath.run();
        }
        if (tracked.counts) {
            run.starredMobKilled(tracked.room, at, killer);
        }
    }

    private ServerPlayer nearestPlayer(Vec3 at) {
        ServerPlayer best = null;
        for (ServerPlayer player : level.players()) {
            if (best == null || player.distanceToSqr(at) < best.distanceToSqr(at)) {
                best = player;
            }
        }
        return best;
    }

    /** Inside the 6x6 grid (with a block's slack) and above the void. */
    private boolean inMap(Entity entity) {
        double max = Layout.GRID * Layout.CELL;
        double x = entity.getX() - Layout.ORIGIN_X;
        double z = entity.getZ() - Layout.ORIGIN_Z;
        return entity.getY() >= level.getMinY() && x >= -1 && z >= -1 && x <= max + 1 && z <= max + 1;
    }

    /**
     * Leftovers no run owns any more: anything that fell into the void, and any mob or NPC that
     * died a vanilla death (void damage gets past invulnerability) and was never removed, which
     * player-shaped NPCs aren't. The camp's and bosses' entities are their phases' to remove.
     */
    void sweep() {
        List<Entity> stale = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Player || dying.contains(entity) || run.isBossEntity(entity)) {
                continue;
            }
            boolean dead = entity instanceof LivingEntity living && living.isDeadOrDying() && living.deathTime >= 20;
            if (dead || entity.getY() < level.getMinY() - 16) {
                stale.add(entity);
            }
        }
        for (Entity entity : stale) {
            if (mobs.containsKey(entity)) {
                kill(entity);
            } else {
                entity.discard();
            }
        }
    }

    RoomState countsFor(Entity e) {
        Tracked t = mobs.get(e);
        return t != null && t.counts ? t.room : null;
    }

    List<Entity> mobsOf(RoomState room) {
        List<Entity> out = new ArrayList<>();
        for (Map.Entry<Entity, Tracked> e : mobs.entrySet()) {
            if (e.getValue().room == room && e.getValue().counts && !e.getKey().isRemoved() && !dying.contains(e.getKey())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** The Watcher camp's live mobs (not dying). */
    List<Entity> campMobs() {
        List<Entity> out = new ArrayList<>();
        for (Map.Entry<Entity, Tracked> e : mobs.entrySet()) {
            if (e.getValue().camp && !e.getKey().isRemoved() && !dying.contains(e.getKey())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    List<Vec3> dormantFels(RoomState room) {
        List<Vec3> out = new ArrayList<>();
        for (FelsMarker f : fels) {
            if (f.room == room && f.counts) {
                out.add(f.at);
            }
        }
        return out;
    }

    int killAllIn(RoomState room) {
        for (Iterator<FelsMarker> it = fels.iterator(); it.hasNext(); ) {
            FelsMarker f = it.next();
            if (f.room == room) {
                it.remove();
                f.marker.discard();
                spawnActive(f.room, Archetype.FELS, f.spawn, f.at.x, f.at.y, f.at.z, f.yaw, f.nametag, f.counts, random);
            }
        }
        int killed = 0;
        for (Map.Entry<Entity, Tracked> e : new ArrayList<>(mobs.entrySet())) {
            if (e.getValue().room == room) {
                kill(e.getKey());
                killed++;
            }
        }
        return killed;
    }

    void dispose() {
        mobs.keySet().forEach(Entity::discard);
        mobs.values().forEach(t -> t.tag.discard());
        mobs.clear();
        tagOwners.keySet().forEach(Entity::discard);
        tagOwners.clear();
        dying.forEach(Entity::discard);
        dying.clear();
        fels.forEach(f -> f.marker.discard());
        fels.clear();
        bones.forEach(Entity::discard);
        bones.clear();
        orbs.dispose();
    }

    // ---------------------------------------------------------------- helpers

    /*
     * A point's offset from the corner. Blocks rotate as whole cells (Facing.rotateX: block b lands on corner - b,
     * which spans corner - b to corner - b + 1), so a point inside block b lands at corner + 1 - its offset on a
     * negated axis; corner - offset would put every mob in a rotated room a block off, often inside a wall.
     */
    private static double rotateX(Facing facing, double x, double z) {
        return switch (facing) {
            case NORTH -> x;
            case EAST -> 1 - z;
            case SOUTH -> 1 - x;
            case WEST -> z;
        };
    }

    private static double rotateZ(Facing facing, double x, double z) {
        return switch (facing) {
            case NORTH -> z;
            case EAST -> x;
            case SOUTH -> 1 - z;
            case WEST -> 1 - x;
        };
    }

    /** format_health: 3,500,000 is "3.5M", 90,000 is "90k". */
    static String formatHealth(long hp) {
        if (hp >= 1_000_000) {
            return trimmed(hp / 1_000_000.0) + "M";
        }
        if (hp >= 1_000) {
            return trimmed(hp / 1_000.0) + "k";
        }
        return Long.toString(hp);
    }

    private static String trimmed(double value) {
        String s = String.format(Locale.ROOT, "%.2f", value);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }
}
