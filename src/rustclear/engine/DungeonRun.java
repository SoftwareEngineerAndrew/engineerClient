package net.rustclear.mod.engine;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.Floor;
import net.rustclear.mod.engine.blood.BloodCamp;
import net.rustclear.mod.engine.boss.Attacks;
import net.rustclear.mod.engine.boss.BossChat;
import net.rustclear.mod.engine.boss.BossContext;
import net.rustclear.mod.engine.boss.BossEntities;
import net.rustclear.mod.engine.boss.BossFight;
import net.rustclear.mod.engine.boss.Profile;
import net.rustclear.mod.engine.boss.RunBar;
import net.rustclear.mod.mixin.ArmorStandAccessor;
import net.rustclear.mod.mixin.MannequinAccessor;

/**
 * One dungeon run: the rooms and doors as they change, the ready-up and countdown, keys, the
 * map and the sidebar (Smalldungeons dungeon.rs Dungeon, plus the per-player parts of main.rs's tick).
 */
public final class DungeonRun implements EngineRun {
    public enum State { NOT_READY, STARTING, STARTED, FINISHED }

    /** Rooms queue for their mobs and only get them on ticks that are a multiple of this (MOB_SPAWN_CYCLE_TICKS). */
    private static final int MOB_SPAWN_CYCLE_TICKS = 5;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
    private static final String MORT_SKIN = "ewogICJ0aW1lc3RhbXAiIDogMTYxODc4MTA4Mzk0NywKICAicHJvZmlsZUlkIiA6ICJhNzdkNmQ2YmFjOWE0NzY3YTFhNzU1NjYxOTllYmY5MiIsCiAgInByb2ZpbGVOYW1lIiA6ICIwOEJFRDUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWI1Njg5NWI5NjU5ODk2YWQ2NDdmNTg1OTkyMzhhZjUzMmQ0NmRiOWMxYjAzODliOGJiZWI3MDk5OWRhYjMzZCIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9";
    private static final String MORT_SIGNATURE = "aNIhT2Tj20v1lONBOK3fIwBqJwWnjErq20h663Gb+PVmR9Iweh1h2ZEJ2pwDDnM4Af1XFDA5hS1Z9yOc8EdVTKyyi1yj9EIvMwQz/Q4N2sBsjWGZtCe8/Zy+X82iv0APB4cumE2gkgDbPjxCFNbpVKmV3U1WzwY/GKOMHofhWS1ULedQ1TszuMmDuHPLEzWaXigZ+xt5zChXvE8QoLTfBvgb8wtqVpyxAKf/o8xQduKiNE7t+de1CwOhLqbVTGh7DU0vLC5stDuqN+nC9dS7c2CG0ori6gFoGMvP4oIss6zm1nb0laMrZidJTgmuXk2Pv4NGDBXdYcAzhfWcSWGsBVMWrJfccgFheG+YcGYaYj6V2nBp0YTqqhN4wDt3ltyTNEMOr/JKyBTLzq/F7IL6rrdyMw+MbAgCa1FhfXxtzdQE2KsL55pbr2DZ8J4DYf+/OC1pWCJ4vvA/A1qGHyi3Zwtj9lCl1Jq5Qm2P9BgWxpk0ikJefRPMg4qWOEcYnjqwXuEp+IgTJi1xr+j/+g28aS1TsF8ijaJjSbEN4urrf3RYL+PZBcggzX9VaPB0NPdioOXznIotY+S6ZW7FnSh6UnrGAKadQBVLey5zmVWMfXlBUq9JMh0csuNd4dDQCLNK8oGORhMgksOMHhVaBie4otUgJ7ThR/WPjOAKiG2TNU0=";
    private static final double MORT_FACE_RANGE = 8.0;

    public final ServerLevel level;
    public final DungeonPlan plan;
    /** F7 or M7: the mobs' health and damage, and what the sidebar and tab list call the floor. */
    public final Floor floor;
    /** Players' SkyBlock health, damage taken and deaths. */
    private final Combat combat;
    private final Lava lava = new Lava(this);
    /** Each class's abilities and on-hit passives. */
    private final ClassAbilities abilities;
    /** Each player's class milestones. */
    private final Milestones milestones = new Milestones(this);
    /** Blessing levels found this run, by name ("power", "time"...), for everyone's stats. */
    private final Map<String, Integer> blessings = new HashMap<>();
    public final List<RoomState> rooms = new ArrayList<>();
    public final List<DoorState> doors = new ArrayList<>();
    /** Room index per grid cell, -1 for none. */
    private final int[] grid = new int[Layout.GRID * Layout.GRID];
    public final DungeonMap map;
    public final Score score = new Score();
    /** Each player's own sidebar: Hypixel lists your teammates, not you. */
    private final Map<UUID, Sidebar> sidebars = new HashMap<>();
    private final MapId mapId;
    private final MapItemSavedData mapData;

    private State state = State.NOT_READY;
    /** Who has readied up at Mort (or in the party screen); the run counts down once everyone has. */
    private final Set<UUID> ready = new HashSet<>();
    /** A duel starts every team's countdown itself (EngineRun.holdStart). */
    private boolean holdStart;
    /** Run ticks when the run reached its finish (the blood door opened), or -1. */
    private long finishedAt = -1;
    private int countdown;
    private long ticks;
    /**
     * The team's keys (clear-rooms-doors.md §5): anyone can open a door with a key a teammate
     * picked up, and the team never holds more than one wither key ("1x" at most; keys and doors
     * alternate in 343/343 runs).
     */
    private int witherKeys;
    private boolean bloodKey;
    private final LinkedHashSet<Integer> pendingMobRooms = new LinkedHashSet<>();
    private final List<Pickup> pickups = new ArrayList<>();
    /** Things to do once their tick comes. */
    private final List<Task> tasks = new ArrayList<>();

    private record Task(long due, Runnable action) {}
    private final SecretSystem secrets;
    private final ItemSystem items;
    private final GearSystem gear;
    private final MobSystem mobs;
    private final Superboom superboom;
    private final RedstoneKey redstoneKey;
    private final FallingBlocks fallingBlocks;
    private final Crushers crushers;
    private final DoorAnimation doorAnimation;
    private final TabList tabList;
    /** The F7 / M7 numbers for the Watcher and the bosses. */
    final Profile profile;
    /** The run's one boss bar: the compass in the clear, then the Watcher and each boss. */
    final RunBar bar = new RunBar();
    /** The Watcher's and the bosses' chat queue. */
    final BossChat bossChat = new BossChat();
    /** Entities the camp and the bosses own, kept out of MobSystem's loops. */
    final BossEntities bossEntities = new BossEntities();
    /** What the camp and the boss phases see of this run. */
    private final BossContext bossContext = new Context();
    /** The boss phases' block changes, put back when the run ends. */
    private final net.rustclear.mod.engine.boss.BossBlocks bossBlocks;
    final BloodCamp bloodCamp;
    final BossFight bossFight;
    /** Each puzzle room's logic, by room index. */
    private final Map<Integer, Puzzle> puzzles = new HashMap<>();
    /** Resets per puzzle room, by room index (each one's fresh puzzle rolls a stream of its own). */
    private final Map<Integer, Integer> puzzleResets = new HashMap<>();
    /** The run's own Random: from the dungeon's seed (mixed, so it isn't the planner's stream), or unseeded. */
    private final Random random;
    /** The dungeon's seed (EngineServer.newDungeon, practice), or null for an unseeded run (duels). */
    private final Long seed;
    /** The practice session watching this run, or null in a normal one. */
    private final PracticeHooks hooks;

    /**
     * What a practice run (PracticeSession) hears of the run, and where it may take over. Every
     * hook is a no-op by default, so a session implements only what its mode needs.
     */
    interface PracticeHooks {
        /** The blood door opened, [runTicks] after Mort's first line. */
        default void onBloodDoor(DungeonRun run, long runTicks) {
        }

        /** The party is going through the blood portal; true when practice ends the run there (no boss). */
        default boolean onEnterBoss(DungeonRun run) {
            return false;
        }

        /** The boss is beaten (Necron on F7, the Wither King on M7). */
        default void onDefeated(DungeonRun run) {
        }
    }
    private Mannequin mort;
    /** Practice mode's route timer, or null in a normal run (Dungeon::practice_room). */
    private Practice.Route practice;
    /** Practice with the room's mobs (Dungeon settings' toggle; off as on the server). */
    private boolean practiceMobs;
    /** The Water Board maze practice mode asks for, or -1 for a random one. */
    int forcedWaterPattern = -1;
    private ArmorStand mortClick;
    /** Mort's name, on its own stand above him as on Hypixel; Skyblocker finds the entrance by it. */
    private ArmorStand mortName;
    /**
     * A practice room has no entrance, but Skyblocker's secret waypoints anchor on one: the Mort
     * name stand plus a green entrance square on the map. Both go in this empty grid cell.
     */
    private static final int PRACTICE_ENTRANCE_X = 0;
    private static final int PRACTICE_ENTRANCE_Z = 0;

    public DungeonRun(ServerLevel level, DungeonPlan plan, Floor floor) {
        this(level, plan, floor, null, null);
    }

    /** A run of [plan] on [floor]; [seed] (null: unseeded) makes its rolls repeat, [hooks] is practice's or null. */
    DungeonRun(ServerLevel level, DungeonPlan plan, Floor floor, Long seed, PracticeHooks hooks) {
        this.level = level;
        this.plan = plan;
        this.floor = floor;
        this.seed = seed;
        this.hooks = hooks;
        this.random = seed == null ? new Random() : new Random(seed * 31 + 7);
        Arrays.fill(grid, -1);
        for (Layout.Door door : plan.layout().doors) {
            doors.add(new DoorState(door));
        }
        int maxX = 0;
        int maxZ = 0;
        for (int i = 0; i < plan.rooms().size(); i++) {
            RoomState room = new RoomState(i, plan.rooms().get(i));
            rooms.add(room);
            for (Layout.Segment s : room.segments()) {
                grid[s.x() + s.z() * Layout.GRID] = i;
                maxX = Math.max(maxX, s.x());
                maxZ = Math.max(maxZ, s.z());
            }
        }
        linkNeighbours();
        boolean practiceLayout = plan.layout().source.startsWith("practice:");
        // A practice map is drawn 6 rooms wide, like a full dungeon, so mods place its cells right.
        this.map = practiceLayout ? new DungeonMap(Layout.GRID - 1, Layout.GRID - 1) : new DungeonMap(maxX, maxZ);
        if (practiceLayout) {
            map.drawPracticeEntrance(PRACTICE_ENTRANCE_X, PRACTICE_ENTRANCE_Z);
        }
        // Hypixel's dungeon map id: Skyblocker looks for 1024, and Odin skips ids with any of
        // 1000's bits set, which getFreeMapId reaches after a few dungeons.
        this.mapId = new MapId(1024);
        this.mapData = MapItemSavedData.createFresh(1_000_000, 1_000_000, (byte) 0, false, false, Level.OVERWORLD).locked();
        level.setMapData(mapId, mapData);
        this.secrets = new SecretSystem(this, random);
        this.items = new ItemSystem(this);
        this.gear = new GearSystem(this, items);
        this.mobs = new MobSystem(this, random);
        this.superboom = new Superboom(this);
        this.redstoneKey = new RedstoneKey(this);
        this.fallingBlocks = new FallingBlocks(this);
        this.crushers = new Crushers(this);
        this.doorAnimation = new DoorAnimation(level);
        this.tabList = new TabList(this);
        this.combat = new Combat(this);
        this.abilities = new ClassAbilities(this);
        this.profile = Profile.of(floor);
        this.bossBlocks = new net.rustclear.mod.engine.boss.BossBlocks(level);
        this.bloodCamp = new BloodCamp(bossContext);
        this.bossFight = new BossFight(bossContext);
    }

    List<Pickup> pickups() {
        return pickups;
    }

    boolean holdsKey(boolean blood) {
        return blood ? bloodKey : witherKeys > 0;
    }

    /** A random int below [bound] from the run's random. */
    int randomInt(int bound) {
        return random.nextInt(bound);
    }

    /** Whether [entity] belongs to the blood camp or a boss phase (MobSystem leaves those alone). */
    /** In the boss (any phase, or after it ended): pearls are refused and the Hyperion doesn't teleport. */
    boolean inBoss() {
        return bossFight.active() || bossFight.ended();
    }

    boolean isBossEntity(Entity entity) {
        return bossEntities.contains(entity);
    }

    BossContext bossContext() {
        return bossContext;
    }

    void openBloodDoorForTest(ServerPlayer player) {
        for (DoorState door : doors) {
            if (door.type() == Layout.DoorType.BLOOD && door.locked) {
                bloodKey = true;
                useBlock(player, new BlockPos(door.door.x(), 70, door.door.z()));
                return;
            }
        }
    }

    /** The run as the blood camp and the boss phases see it. */
    private final class Context implements BossContext {
        @Override
        public ServerLevel level() {
            return level;
        }

        @Override
        public List<ServerPlayer> players() {
            return level.players();
        }

        @Override
        public Profile profile() {
            return profile;
        }

        @Override
        public RunBar bar() {
            return bar;
        }

        @Override
        public BossChat chat() {
            return bossChat;
        }

        @Override
        public BossEntities entities() {
            return bossEntities;
        }

        @Override
        public void broadcast(String legacy) {
            forEachPlayer(p -> p.sendSystemMessage(EngineItems.styled(legacy)));
        }

        @Override
        public void hit(ServerPlayer player, Attacks.Attack attack) {
            combat.bossHit(player, attack, attack.damage(profile));
        }

        @Override
        public void hit(ServerPlayer player, Attacks.Attack attack, double damage) {
            combat.bossHit(player, attack, damage);
        }

        @Override
        public void defeated() {
            RunEnd.defeated(DungeonRun.this);
        }

        @Override
        public void kill(ServerPlayer player, String killer) {
            combat.kill(player, killer);
        }

        @Override
        public boolean isGhost(ServerPlayer player) {
            return combat.isGhost(player);
        }

        @Override
        public long runTicks() {
            return runTicks;
        }

        @Override
        public Random random() {
            return random;
        }

        @Override
        public Random random(String stream) {
            return derived(stream, 0);
        }

        @Override
        public Room bloodRoom() {
            RoomState blood = bloodRoomState();
            if (blood == null) {
                return null;
            }
            Layout.Segment seg = blood.segments().get(0);
            int cx = seg.x() * Layout.CELL + 15 + Layout.ORIGIN_X;
            int cz = seg.z() * Layout.CELL + 15 + Layout.ORIGIN_Z;
            // The floor: the first solid block under the room's middle (Hypixel's is y 69, the Watcher at 73).
            int floor = 69;
            for (int y = 80; y > level.getMinY(); y--) {
                if (!level.getBlockState(new net.minecraft.core.BlockPos(cx, y, cz)).isAir()) {
                    floor = y + 1;
                    break;
                }
            }
            int dx = 0;
            int dz = 1;
            for (DoorState door : doors) {
                if (door.type() == Layout.DoorType.BLOOD) {
                    int ox = door.door.x() - cx;
                    int oz = door.door.z() - cz;
                    dx = Math.abs(ox) > Math.abs(oz) ? Integer.signum(ox) : 0;
                    dz = dx == 0 ? Integer.signum(oz) : 0;
                }
            }
            return new Room(new net.minecraft.world.phys.Vec3(cx + 0.5, floor + 4, cz + 0.5), floor, dx, dz);
        }

        @Override
        public boolean inBloodRoom(ServerPlayer player) {
            RoomState blood = bloodRoomState();
            return blood != null && roomAt(player.getX(), player.getZ()) == blood.index;
        }

        @Override
        public net.minecraft.world.entity.LivingEntity spawnCampMob(net.rustclear.mod.engine.blood.CampMob mob,
                                                                  net.minecraft.world.phys.Vec3 at, float yaw, Runnable onDeath) {
            RoomState blood = bloodRoomState();
            return blood == null ? null : mobs.spawnCamp(blood, mob, at.x, at.y, at.z, yaw, onDeath);
        }

        @Override
        public List<Entity> campMobs() {
            return mobs.campMobs();
        }

        @Override
        public void campHit(ServerPlayer player, String line, double raw, boolean trueDamage, String killer) {
            combat.campHit(player, line, raw, trueDamage, killer);
        }

        @Override
        public net.rustclear.mod.engine.boss.BossBlocks blocks() {
            return bossBlocks;
        }

        @Override
        public String nameOf(ServerPlayer player) {
            return nameColour(player);
        }

        @Override
        public net.minecraft.world.entity.LivingEntity spawnBossMob(net.rustclear.mod.engine.blood.CampMob mob,
                                                                  net.minecraft.world.phys.Vec3 at, float yaw, Runnable onDeath) {
            return mobs.spawnCamp(null, mob, at.x, at.y, at.z, yaw, onDeath);
        }

        @Override
        public void registerBoss(net.minecraft.world.entity.LivingEntity entity, java.util.function.LongConsumer onDamage) {
            mobs.registerBoss(entity, onDamage);
        }

        @Override
        public void unregisterBoss(Entity entity) {
            mobs.unregister(entity);
        }

        @Override
        public void killMob(Entity entity) {
            mobs.kill(entity);
        }

        @Override
        public void cryptSkull(ServerPlayer player) {
            combat.cryptSkull(player);
        }

        @Override
        public void enterBoss() {
            DungeonRun.this.enterBoss();
        }
    }

    /**
     * The first player through the blood portal takes the whole party into the boss (OPEN: 38):
     * everyone lands at (73.5, 221.5, 14.5), the bar turns to Maxor a tick later and his first
     * line comes 2 ticks after the teleport (boss-p1-maxor.md §1, critic C5).
     */
    private boolean enteredBoss;

    private void enterBoss() {
        if (enteredBoss) {
            return;
        }
        enteredBoss = true;
        BossArena.of(level).finishNow();
        net.minecraft.world.phys.Vec3 at = net.rustclear.mod.engine.boss.maxor.MaxorPhase.entry();
        for (ServerPlayer p : level.players()) {
            p.teleportTo(level, at.x, at.y, at.z, java.util.Set.of(), 360f, 0f, true);
        }
        SimLog.event("boss_entry");
        // Autopet's On Boss Spawn fires on the teleport, 2 ticks before Maxor's first line (34 of 34 recorded).
        for (ServerPlayer p : level.players()) {
            Pets.trigger(p, Pets.Trigger.BOSS_SPAWN, "Maxor");
        }
        int before = net.rustclear.mod.engine.boss.maxor.MaxorPhase.teleportBefore();
        schedule(before - 1, () -> bar.boss("Maxor", 1f));
        schedule(before, () -> bossFight.begin(new net.rustclear.mod.engine.boss.maxor.MaxorPhase()));
    }

    private String lastBossPhase;

    /** Autopet's On Boss Spawn for the bosses after Maxor, as each one's phase starts (OPEN: their exact tick). */
    private void bossSpawnTrigger() {
        String name = bossFight.phase() == null ? null : bossFight.phase().name();
        if (name == null || name.equals(lastBossPhase)) {
            return;
        }
        lastBossPhase = name;
        String boss = switch (name) {
            case "storm" -> "Storm";
            case "goldor" -> "Goldor";
            case "necron" -> "Necron";
            case "wither_king" -> "Wither King";
            default -> null;
        };
        if (boss != null) {
            for (ServerPlayer p : level.players()) {
                Pets.trigger(p, Pets.Trigger.BOSS_SPAWN, boss);
            }
        }
    }

    private RoomState bloodRoomState() {
        for (RoomState room : rooms) {
            if (room.type() == RoomType.BLOOD) {
                return room;
            }
        }
        return null;
    }

    Combat combat() {
        return combat;
    }

    Milestones milestones() {
        return milestones;
    }

    /** Ticks since Mort's first line. */
    long runTicks() {
        return runTicks;
    }

    ClassAbilities abilities() {
        return abilities;
    }

    /** The blessings found so far (StatBuilder.Run). */
    Map<String, Integer> blessings() {
        return Map.copyOf(blessings);
    }

    /**
     * A blessing of [level] of [name] ("power", "wisdom", "stone", "life"; "time" from the Quiz).
     * Levels of one kind add up: Hypixel's tab footer shows the sums (Life V, VII, VIII, XIII...).
     * OPEN: whether the stats follow the summed level or multiply blessing by blessing.
     */
    void addBlessing(String name, int level) {
        blessings.merge(name, level, Integer::sum);
    }

    /** Whether this run is a practice room (its action bar is the route timer's). */
    boolean isPractice() {
        return practice != null;
    }

    /** Dungeon::from_layout's neighbour wiring: which door and room lies past each side of each cell. */
    private void linkNeighbours() {
        for (RoomState room : rooms) {
            List<Layout.Segment> segments = room.segments();
            for (Layout.Segment segment : segments) {
                List<RoomState.Neighbour> list = new ArrayList<>();
                int cx = segment.x() * Layout.CELL + 15 + Layout.ORIGIN_X;
                int cz = segment.z() * Layout.CELL + 15 + Layout.ORIGIN_Z;
                int[][] sides = {
                    {segment.x(), segment.z() - 1, cx, cz - 16},
                    {segment.x() + 1, segment.z(), cx + 16, cz},
                    {segment.x(), segment.z() + 1, cx, cz + 16},
                    {segment.x() - 1, segment.z(), cx - 16, cz},
                };
                for (int side = 0; side < 4; side++) {
                    int nx = sides[side][0];
                    int nz = sides[side][1];
                    if (nx < 0 || nz < 0 || nx >= Layout.GRID || nz >= Layout.GRID) {
                        continue;
                    }
                    boolean own = false;
                    for (Layout.Segment other : segments) {
                        if (other.x() == nx && other.z() == nz) {
                            own = true;
                            break;
                        }
                    }
                    if (own) {
                        continue;
                    }
                    for (int d = 0; d < doors.size(); d++) {
                        Layout.Door door = doors.get(d).door;
                        if (door.x() == sides[side][2] && door.z() == sides[side][3]) {
                            int neighbour = grid[nx + nz * Layout.GRID];
                            if (neighbour >= 0) {
                                list.add(new RoomState.Neighbour(side, d, neighbour));
                            }
                            break;
                        }
                    }
                }
                room.neighbours.add(list);
            }
        }
    }

    public State state() {
        return state;
    }

    // ---------------------------------------------------------------- setup and teardown

    /**
     * apply_practice_dungeon: a practice room skips the lobby and is live at once. Call before
     * [populate] (so the Water Board gets its maze and no Mort is spawned).
     */
    void startPractice(Practice.Route route, int waterPattern, boolean mobs) {
        practice = route;
        practiceMobs = mobs;
        forcedWaterPattern = waterPattern;
        score.reset();
        state = State.STARTED;
        runTicks = 0;
    }

    /** Spawns Mort in the entrance room. */
    public void populate() {
        for (RoomState room : rooms) {
            Puzzle puzzle = Puzzles.create(this, room);
            if (puzzle != null) {
                puzzles.put(room.index, puzzle);
                puzzle.setup();
            }
        }
        redstoneKey.setup();
        if (practice != null) {
            // Only the hidden name stand, at the map's entrance square (see PRACTICE_ENTRANCE_X).
            mortName = new ArmorStand(EntityTypes.ARMOR_STAND, level);
            ((ArmorStandAccessor) mortName).rustclear$setMarker(true);
            mortName.snapTo(PRACTICE_ENTRANCE_X * Layout.CELL + Layout.ORIGIN_X + 15.5, 71.55,
                PRACTICE_ENTRANCE_Z * Layout.CELL + Layout.ORIGIN_Z + 15.5, 0f, 0f);
            mortName.setInvisible(true);
            mortName.setNoGravity(true);
            mortName.setPermanentlyInvulnerable(true);
            mortName.setCustomName(EngineItems.text("§bMort"));
            mortName.setCustomNameVisible(false);
            level.addFreshEntity(mortName);
        }
        for (RoomState room : rooms) {
            if (room.type() == RoomType.ENTRANCE && practice == null) {
                BlockPos pos = room.placed.toWorld(15, 69, 4);
                mort = new Mannequin(EntityTypes.MANNEQUIN, level);
                mort.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, room.placed.facing().rotateYaw(0f), 0f);
                mort.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(EngineItems.profile("Mort", MORT_SKIN, MORT_SIGNATURE)));
                mort.setCustomNameVisible(false);
                mort.setPermanentlyInvulnerable(true);
                mort.setNoGravity(true);
                ((MannequinAccessor) mort).rustclear$setImmovable(true);
                mort.setSilent(true);
                level.addFreshEntity(mort);

                mortClick = new ArmorStand(EntityTypes.ARMOR_STAND, level);
                mortClick.snapTo(pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 0f, 0f);
                mortClick.setInvisible(true);
                mortClick.setNoGravity(true);
                mortClick.setPermanentlyInvulnerable(true);
                mortClick.setCustomName(EngineItems.text("§eCLICK"));
                mortClick.setCustomNameVisible(true);
                level.addFreshEntity(mortClick);

                mortName = new ArmorStand(EntityTypes.ARMOR_STAND, level);
                ((ArmorStandAccessor) mortName).rustclear$setMarker(true);
                mortName.snapTo(pos.getX() + 0.5, pos.getY() + 2.55, pos.getZ() + 0.5, 0f, 0f);
                mortName.setInvisible(true);
                mortName.setNoGravity(true);
                mortName.setPermanentlyInvulnerable(true);
                mortName.setCustomName(EngineItems.text("§bMort"));
                mortName.setCustomNameVisible(true);
                level.addFreshEntity(mortName);
            }
        }
    }

    /** Removes everything this run spawned. */
    @Override
    public void dispose() {
        // Ghosts get their inventories back and stop being ghosts before the next dungeon.
        combat.ghosts().clear();
        if (mort != null) {
            mort.discard();
        }
        if (mortName != null) {
            mortName.discard();
        }
        if (mortClick != null) {
            mortClick.discard();
        }
        items.dispose();
        gear.dispose();
        mobs.dispose();
        secrets.dispose();
        redstoneKey.dispose();
        fallingBlocks.dispose();
        crushers.dispose();
        for (Puzzle puzzle : puzzles.values()) {
            puzzle.dispose();
            puzzle.removeTracked();
        }
        puzzles.clear();
        pickups.forEach(Pickup::remove);
        pickups.clear();
        sidebars.values().forEach(Sidebar::remove);
        sidebars.clear();
        doorAnimation.dispose();
        tabList.dispose();
        abilities.dispose();
        bloodCamp.dispose();
        bossFight.dispose();
        bossEntities.discardAll();
        bar.remove(level.players());
    }

    public boolean isMort(Entity entity) {
        return entity == mort || entity == mortClick;
    }

    /** What every player carries: the map in the last hotbar slot. */
    @Override
    public void equip(ServerPlayer player) {
        EngineItem.giveLoadout(player);
        giveMap(player);
        // Autopet's On Enter Island ("You enter Dungeon."), as joining the instance fires it on Hypixel.
        Pets.trigger(player, Pets.Trigger.ENTER_ISLAND, "Dungeon");
    }

    /** The Magical Map on hotbar slot 9 (a ghost keeps it too). */
    void giveMap(ServerPlayer player) {
        ItemStack mapItem = EngineItems.named(Items.FILLED_MAP, "§bMagical Map");
        mapItem.set(DataComponents.MAP_ID, mapId);
        ItemStack there = player.getInventory().getItem(8);
        player.getInventory().setItem(8, mapItem);
        if (!there.isEmpty() && !there.is(Items.FILLED_MAP) && !player.getInventory().add(there)) {
            // Something moved onto the map's slot (a dungeon item): it goes back in the bag, not away.
            player.drop(there, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
        sendMap(player);
    }

    // ---------------------------------------------------------------- ready-up and start

    /**
     * Mort's menu (and the party screen's Ready): readies [player], or un-readies them. Once
     * everyone in the dungeon is ready the countdown starts; anyone un-readying during it stops it.
     * Alone, the first click starts the run, as before parties.
     */
    @Override
    public void toggleReady(ServerPlayer player) {
        if (state != State.NOT_READY && state != State.STARTING) {
            return;
        }
        if (ready.add(player.getUUID())) {
            broadcast(nameColour(player) + "§a is now ready!");
            // Ready Up's click: block.lever.click BLOCKS 0.55 / 2.0 (menus-ui.md §3).
            forEachPlayer(p -> level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.55f, 2f));
            startIfAllReady();
        } else {
            ready.remove(player.getUUID());
            broadcast(nameColour(player) + "§c is no longer ready!");
            if (state == State.STARTING) {
                // The countdown silently stops and starts again at 4 once everyone is ready (408abb).
                state = State.NOT_READY;
            }
        }
    }

    /** The leader's Start: everyone ready at once, and the countdown begins. */
    @Override
    public void forceStart() {
        if (state != State.NOT_READY) {
            return;
        }
        forEachPlayer(p -> ready.add(p.getUUID()));
        startIfAllReady();
    }

    @Override
    public boolean isReady(UUID id) {
        return state == State.STARTING || state == State.STARTED || state == State.FINISHED || ready.contains(id);
    }

    /** Starts the countdown once every player here is ready (players who left don't count), unless held. */
    private void startIfAllReady() {
        if (!holdStart && allReady()) {
            beginCountdown();
        }
    }

    @Override
    public boolean allReady() {
        List<ServerPlayer> players = level.players();
        ready.removeIf(id -> players.stream().noneMatch(p -> p.getUUID().equals(id)));
        return state == State.NOT_READY && !players.isEmpty() && ready.size() == players.size();
    }

    @Override
    public void holdStart(boolean hold) {
        holdStart = hold;
    }

    @Override
    public void beginCountdown() {
        if (state == State.NOT_READY) {
            forEachPlayer(p -> ready.add(p.getUUID()));
            state = State.STARTING;
            countdown = COUNTDOWN_TICKS;
        }
    }

    @Override
    public ServerLevel level() {
        return level;
    }

    @Override
    public Phase phase() {
        return switch (state) {
            case NOT_READY -> Phase.LOBBY;
            case STARTING -> Phase.COUNTDOWN;
            case STARTED -> finished() ? Phase.FINISHED : Phase.RUNNING;
            case FINISHED -> Phase.FINISHED;
        };
    }

    /**
     * Has the run reached its finish? For now the blood door opening, which is also where the
     * Rustclear leaderboard stops its clock: no blood mobs spawn yet, so "blood cleared" can't be
     * told apart. Change this one place once they do.
     */
    boolean finished() {
        return finishedAt >= 0;
    }

    @Override
    public Progress progress() {
        boolean started = state == State.STARTED;
        return new Progress(score.clearedRooms, score.totalRooms, score.secretsFound, score.totalSecrets,
            started ? runTicks : 0, started ? score.total() : 0, finished());
    }

    /** The run's dungeon map, which this level answers for map id 1024 (EngineMapMixin). */
    MapItemSavedData mapData() {
        return mapData;
    }

    MapId mapId() {
        return mapId;
    }

    // ---------------------------------------------------------------- input, from EngineServer's hooks

    @Override
    public boolean onHitBlock(ServerPlayer sp, BlockPos pos) {
        if (combat.isGhost(sp)) {
            // A ghost touches nothing: only its kit's abilities work (onUseItem).
            return true;
        }
        if (hitBlock(sp, pos)) {
            return true;
        }
        EngineItem held = EngineItem.of(sp.getMainHandItem());
        if (held == EngineItem.SUPERBOOM_TNT) {
            // Hitting a block with it blows up there too: placed in front of the face, so not while the player stands
            // in that block (the user, 2026-10-19: a block can't be placed inside the player).
            if (Superboom.blocked(sp, pos)) {
                return true;
            }
            if (!bossFight.boom(sp, pos)) {
                superboom.use(sp, pos);
            }
            return true;
        }
        if (held == EngineItem.DUNGEONBREAKER) {
            items.hitBlock(sp, pos);
            return true;
        }
        return false;
    }

    @Override
    public boolean onUseBlock(ServerPlayer sp, BlockPos pos, Direction face) {
        if (combat.isGhost(sp)) {
            // A ghost touches nothing: only its kit's abilities work (onUseItem).
            return true;
        }
        if (useBlock(sp, pos, face)) {
            return true;
        }
        if (EngineItem.of(sp.getMainHandItem()) == EngineItem.SUPERBOOM_TNT) {
            if (!bossFight.boom(sp, pos)) {
                superboom.use(sp, pos);
            }
            return true;
        }
        // Item abilities run from onUseItem only: the client follows an unconsumed block use with a
        // UseItem packet, so firing them here too made one click etherwarp twice.
        return false;
    }

    @Override
    public boolean onUseItem(ServerPlayer sp) {
        if (combat.isGhost(sp)) {
            // A ghost holds only the ghost kit (Ghosts): its abilities work, nothing else does.
            combat.ghosts().rightClick(sp);
            return true;
        }
        EngineItem item = EngineItem.of(sp.getMainHandItem());
        if (item != null) {
            return items.rightClick(sp, item);
        }
        ItemDefs.Instance data = DataItems.of(sp.getMainHandItem());
        return data != null && gear.rightClick(sp, data);
    }

    @Override
    public void onLeftClick(ServerPlayer sp) {
        if (combat.isGhost(sp)) {
            return;
        }
        gear.leftClick(sp);
        abilities.leftClick(sp);
    }

    @Override
    public boolean onArrowHit(AbstractArrow arrow, Entity entity) {
        if (!mobs.isMob(entity) || !(arrow.getOwner() instanceof ServerPlayer shooter)) {
            return false;
        }
        if (combat.isGhost(shooter)) {
            // An arrow loosed before the death does nothing once its shooter is a ghost.
            arrow.discard();
            return true;
        }
        gear.arrowHit(shooter, arrow, entity);
        return true;
    }

    @Override
    public boolean onBowRelease(ServerPlayer sp, ItemStack bow, int ticksDrawn) {
        if (combat.isGhost(sp)) {
            // A ghost touches nothing: only its kit's abilities work (onUseItem).
            return true;
        }
        ItemDefs.Instance data = DataItems.of(bow);
        return data != null && gear.release(sp, data, ticksDrawn);
    }

    @Override
    public boolean onUseEntity(ServerPlayer sp, Entity entity) {
        if (combat.isGhost(sp)) {
            // A ghost touches nothing: only its kit's abilities work (onUseItem).
            return true;
        }
        if (isMort(entity)) {
            // Mort stays where he always was, but ready-up is in the party lobby now.
            sp.sendSystemMessage(EngineItems.text(state == State.NOT_READY
                ? "§e[NPC] §bMort§f: §rReady up in the party lobby, adventurer."
                : "§e[NPC] §bMort§f: Good luck."));
            return true;
        }
        return useEntity(sp, entity);
    }

    @Override
    public boolean onAttackEntity(ServerPlayer sp, Entity entity) {
        if (combat.isGhost(sp)) {
            // A ghost's hands go through things; only its kit's abilities hit.
            return true;
        }
        if (bloodCamp.isWatcher(entity)) {
            bloodCamp.attacked(sp, entity);
            return true;
        }
        if (mobs.isMob(entity)) {
            // A Mage's sword swing is their beam (ClassAbilities, from the same click), not a second hit.
            if (PlayerStats.classOf(sp) != DungeonClass.MAGE || !ClassAbilities.beamItem(sp.getMainHandItem())) {
                mobs.attack(sp, entity);
            }
            return true;
        }
        return attackEntity(sp, entity);
    }

    @Override
    public void startedTracking(Entity entity, ServerPlayer player) {
        mobs.startedTracking(entity, player);
    }

    @Override
    public void stoppedTracking(Entity entity, ServerPlayer player) {
        mobs.stoppedTracking(entity, player);
    }

    /** The compass bar's width in characters (408abb: a 45-character name). */
    private static final int COMPASS_WIDTH = 45;
    /** How far to either side of where you look the compass reaches. */
    private static final double COMPASS_HALF_ANGLE = 90;

    /**
     * The clear's compass bar for [viewer] (menus-ui.md "Boss bars"): 45 characters of spaces with
     * each teammate's first 3 letters (green) and a red bold ☬ placed by direction from where the
     * viewer looks. OPEN: 32 (what ☬ points to; the Blood room here).
     */
    private String compassName(ServerPlayer viewer) {
        String[] cells = new String[COMPASS_WIDTH];
        java.util.Arrays.fill(cells, " ");
        double yaw = viewer.getYRot();
        RoomState blood = null;
        for (RoomState room : rooms) {
            if (room.type() == RoomType.BLOOD) {
                blood = room;
            }
        }
        if (blood != null) {
            Layout.Segment seg = blood.segments().get(0);
            placeOnCompass(cells, viewer, yaw, seg.x() * Layout.CELL + 15.5 + Layout.ORIGIN_X,
                seg.z() * Layout.CELL + 15.5 + Layout.ORIGIN_Z, new String[] {"§c§l☬"});
        }
        for (ServerPlayer p : level.players()) {
            if (p == viewer) {
                continue;
            }
            String name = p.getName().getString();
            String three = name.substring(0, Math.min(3, name.length()));
            String[] marks = new String[three.length()];
            for (int i = 0; i < marks.length; i++) {
                marks[i] = (i == 0 ? "§a" : "") + three.charAt(i);
            }
            placeOnCompass(cells, viewer, yaw, p.getX(), p.getZ(), marks);
        }
        return String.join("", cells);
    }

    private static void placeOnCompass(String[] cells, ServerPlayer viewer, double yaw, double x, double z, String[] marks) {
        double bearing = Math.toDegrees(Math.atan2(-(x - viewer.getX()), z - viewer.getZ()));
        double off = ((bearing - yaw) % 360 + 540) % 360 - 180;
        if (Math.abs(off) > COMPASS_HALF_ANGLE) {
            return;
        }
        int middle = COMPASS_WIDTH / 2;
        int at = middle + (int) Math.round(off / COMPASS_HALF_ANGLE * middle) - marks.length / 2;
        for (int i = 0; i < marks.length; i++) {
            int c = at + i;
            if (c >= 0 && c < cells.length) {
                cells[c] = marks[i];
            }
        }
    }

    /** Dungeon::start_dungeon. */
    private void start() {
        // One boss bar for the whole run, added on Mort's first line (408abb: Add n11536 = Mort).
        if (practice == null) {
            bar.compass(this::compassName);
        }
        int fairyDoor = fairyEntryDoor();
        for (int i = 0; i < doors.size(); i++) {
            DoorState door = doors.get(i);
            if (door.type() == Layout.DoorType.ENTRANCE || (door.type() == Layout.DoorType.WITHER && i == fairyDoor)) {
                openDoor(door, START_DOOR_AIR_TICKS);
                continue;
            }
            if (door.type() != Layout.DoorType.NORMAL) {
                door.locked = true;
            }
        }
        for (RoomState room : rooms) {
            if (room.type() == RoomType.ENTRANCE && !room.entered) {
                room.entered = true;
                map.drawRoom(rooms, doors, room.index);
                pendingMobRooms.add(room.index);
                secrets.roomEntered(room);
                secrets.runStarted(room);
                break;
            }
        }
    }

    /** The door you enter the fairy room through, on the shortest way from the entrance (find_fairy_entry_door). */
    private int fairyEntryDoor() {
        int entrance = -1;
        int fairy = -1;
        for (RoomState room : rooms) {
            if (room.type() == RoomType.ENTRANCE) entrance = room.index;
            if (room.type() == RoomType.FAIRY) fairy = room.index;
        }
        if (entrance < 0 || fairy < 0) {
            return -1;
        }
        boolean[] visited = new boolean[rooms.size()];
        int[] entryDoor = new int[rooms.size()];
        Arrays.fill(entryDoor, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        visited[entrance] = true;
        queue.add(entrance);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (current == fairy) {
                return entryDoor[current];
            }
            for (List<RoomState.Neighbour> list : rooms.get(current).neighbours) {
                for (RoomState.Neighbour n : list) {
                    if (!visited[n.roomIndex()]) {
                        visited[n.roomIndex()] = true;
                        entryDoor[n.roomIndex()] = n.doorIndex();
                        queue.add(n.roomIndex());
                    }
                }
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- doors

    /** A door's core goes barrier this many ticks after its "opened" line (35/35 doors, critic S3). */
    private static final int DOOR_BARRIER_TICKS = 1;
    /** ...and air this many after that (1,382 main doors: median 14, min 14). */
    private static final int DOOR_AIR_TICKS = 14;
    /** The Entrance and Fairy doors at the start hold their barrier one tick longer (critic S3: 15). */
    private static final int START_DOOR_AIR_TICKS = 15;

    /** A door opens on its line's tick: falling copies now, the core barrier a tick later, then air. */
    private void openDoor(DoorState door) {
        openDoor(door, DOOR_AIR_TICKS);
    }

    private void openDoor(DoorState door, int airTicks) {
        door.locked = false;
        door.opened = true;
        doorAnimation.open(door);
        schedule(DOOR_BARRIER_TICKS, () -> {
            setDoorCore(door, Blocks.BARRIER.defaultBlockState());
            SimLog.event("door_barrier", "x", door.door.x(), "z", door.door.z(), "type", door.type().name(),
                "start", airTicks == START_DOOR_AIR_TICKS);
            schedule(airTicks, () -> {
                setDoorCore(door, Blocks.AIR.defaultBlockState());
                SimLog.event("door_air", "x", door.door.x(), "z", door.door.z());
            });
        });
    }

    private void setDoorCore(DoorState door, net.minecraft.world.level.block.state.BlockState state) {
        int x = door.door.x();
        int z = door.door.z();
        for (int bx = x - 1; bx <= x + 1; bx++) {
            for (int y = 69; y <= 72; y++) {
                for (int bz = z - 1; bz <= z + 1; bz++) {
                    level.setBlock(new BlockPos(bx, y, bz), state, FLAGS);
                }
            }
        }
    }

    /** A right click on [face] of a block: the Redstone Key first (it needs the face), then [useBlock]. */
    public boolean useBlock(ServerPlayer player, BlockPos pos, Direction face) {
        return redstoneKey.useBlock(player, pos, face) || useBlock(player, pos);
    }

    /** A right click on a block. Returns true when it was a door (handled). */
    public boolean useBlock(ServerPlayer player, BlockPos pos) {
        if (bossFight.useBlock(player, pos)) {
            return true;
        }
        for (int i = 0; i < doors.size(); i++) {
            DoorState door = doors.get(i);
            if (!door.locked || !door.contains(pos.getX(), pos.getY(), pos.getZ())) {
                continue;
            }
            boolean blood = door.type() == Layout.DoorType.BLOOD;
            if (blood ? !bloodKey : witherKeys == 0) {
                level.playSound(null, pos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 8f, 0.5f);
                player.sendSystemMessage(EngineItems.text("§cYou do not have the key for this door!"));
                return true;
            }
            if (state != State.STARTED) {
                return true;
            }
            if (blood) {
                bloodKey = false;
            } else {
                witherKeys--;
            }
            // Measured (b1e397 n 294105): the pling at 8 / 4.05 for every door, plus a ghast hurt for blood.
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 8f, 4.05f);
            if (blood) {
                level.playSound(null, pos, SoundEvents.GHAST_HURT, SoundSource.BLOCKS, 2f, 0.49f);
            }
            openDoor(door);
            int room = roomAt(player.getX(), player.getZ());
            if (room >= 0) {
                map.drawRoom(rooms, doors, room);
            }
            if (blood) {
                if (finishedAt < 0) {
                    finishedAt = runTicks;
                }
                broadcast("§cThe §lBLOOD DOOR§c has been opened!");
                broadcast("§5A shiver runs down your spine...");
                // The camp (its greeting 3 ticks after the door, 318/318 runs; run timers like
                // Skyblocker's splits mark blood open by it).
                bar.hold();
                bloodCamp.start();
                // The boss arena goes in during the camp, a slice a tick (BossArena).
                BossArena.of(level).start();
            } else {
                broadcast(nameColour(player) + "§a opened a §8§lWITHER §adoor!");
            }
            return true;
        }
        for (Puzzle puzzle : puzzles.values()) {
            if (puzzle.useBlock(player, pos)) {
                return true;
            }
        }
        return secrets.useBlock(player, pos);
    }

    Puzzle puzzleOf(int index) {
        return puzzles.get(index);
    }

    /** A right click on an entity other than Mort: the puzzles get it. */
    public boolean useEntity(ServerPlayer player, Entity entity) {
        if (bossFight.use(player, entity) || RewardChests.use(player, entity)) {
            return true;
        }
        for (Puzzle puzzle : puzzles.values()) {
            if (puzzle.useEntity(player, entity)) {
                return true;
            }
        }
        return false;
    }

    /** A left click on an entity that isn't a dungeon mob: the puzzles get it. */
    public boolean attackEntity(ServerPlayer player, Entity entity) {
        if (bossFight.attack(player, entity) || isBossEntity(entity)) {
            return true;
        }
        for (Puzzle puzzle : puzzles.values()) {
            if (puzzle.attackEntity(player, entity)) {
                return true;
            }
        }
        return false;
    }

    /** A left click on a block: the puzzles get it first. */
    public boolean hitBlock(ServerPlayer player, BlockPos pos) {
        if (bossFight.hitBlock(player, pos)) {
            return true;
        }
        for (Puzzle puzzle : puzzles.values()) {
            if (puzzle.hitBlock(player, pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A failed puzzle comes back [delay] ticks later as a fresh puzzle: the old one is taken down
     * and a new one set up in its room, so it can be redone.
     */
    void resetPuzzle(RoomState room, int delay) {
        schedule(delay, () -> {
            Puzzle old = puzzles.remove(room.index);
            if (old != null) {
                old.dispose();
                old.removeTracked();
            }
            room.puzzleFailed = false;
            room.puzzleCompleted = false;
            score.puzzlesInFail = Math.max(0, score.puzzlesInFail - 1);
            puzzleResets.merge(room.index, 1, Integer::sum);
            Puzzle fresh = Puzzles.create(this, room);
            if (fresh != null) {
                puzzles.put(room.index, fresh);
                fresh.setup();
                if (room.entered) {
                    fresh.entered();
                }
            }
            map.drawRoom(rooms, doors, room.index);
            SimLog.event("puzzle_reset", "room", room.data().name());
        });
    }

    /**
     * A secret bat died: "A Bat has been slain. +1 Bonus Score", at most once a run (93 lines in
     * about 205 main runs, never two). OPEN: 15 (one special bat, or the first bat killed? The
     * first one here).
     */
    void batSlain() {
        if (!score.batSlain) {
            score.batSlain = true;
            broadcast("§eA Bat has been slain. §a+1 Bonus Score");
        }
    }

    /** Redraws [room]'s map tile (update_map_for_room). */
    void redrawRoom(RoomState room) {
        map.drawRoom(rooms, doors, room.index);
    }

    /** A secret was found in [room] (found_secrets += 1, capped at the room's count, redrawing its map tick). */
    void foundSecret(RoomState room) {
        if (room.foundSecrets < room.data().secrets()) {
            room.foundSecrets++;
            if (room.entered) {
                map.drawRoom(rooms, doors, room.index);
            }
        }
    }

    SecretSystem secrets() {
        return secrets;
    }

    Superboom superboom() {
        return superboom;
    }

    MobSystem mobs() {
        return mobs;
    }

    ItemSystem items() {
        return items;
    }

    GearSystem gear() {
        return gear;
    }

    /** Is [pos] a block the run answers clicks on: a locked door, a secret, a locked chest or a lever? */
    boolean isInteractable(BlockPos pos) {
        for (DoorState door : doors) {
            if (!door.opened && door.type() != Layout.DoorType.NORMAL && door.type() != Layout.DoorType.ENTRANCE
                && door.contains(pos.getX(), pos.getY(), pos.getZ())) {
                return true;
            }
        }
        for (Puzzle puzzle : puzzles.values()) {
            if (puzzle.isInteractable(pos)) {
                return true;
            }
        }
        return secrets.isInteractable(pos) || redstoneKey.isInteractable(pos);
    }

    /** Runs [task] [delay] ticks from now. */
    /** Ticks since the run began. */
    long ticks() {
        return ticks;
    }

    Random random() {
        return random;
    }

    /**
     * A Random of its own for one [stream] of rolls ("puzzle", "mobs", "chest"...) at [index]: from
     * the dungeon's seed, mixed (splitmix64), so a replayed map rolls the same puzzles, mobs and
     * chests whatever happened before. Unseeded: a fresh one off the run's random.
     */
    Random derived(String stream, int index) {
        if (seed == null) {
            return new Random(random.nextLong());
        }
        long z = seed ^ stream.hashCode() * 0x9E3779B97F4A7C15L ^ index * 0xC2B2AE3D27D4EB4FL;
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new Random(z ^ (z >>> 31));
    }

    /** How often [roomIndex]'s puzzle has been reset this run (each reset rolls a new puzzle). */
    int puzzleResets(int roomIndex) {
        return puzzleResets.getOrDefault(roomIndex, 0);
    }

    void schedule(int delay, Runnable task) {
        tasks.add(new Task(ticks + delay, task));
    }

    /** kill_mob's room bookkeeping: one starred mob fewer, and at none the room is cleared and its keys drop where it died. */
    void starredMobKilled(RoomState room, Vec3 at, ServerPlayer killer) {
        if (room.starredMobsRemaining > 0) {
            room.starredMobsRemaining--;
        }
        if (room.starredMobsRemaining == 0) {
            // Who checkmarked it: the last starred kill's player (scripted runs score each role's rooms).
            SimLog.event("room_clear", "room", room.data().name(), "who", killer == null ? "" : killer.getName().getString(),
                "ghost", killer != null && Ghosts.isGhost(killer));
            map.drawRoom(rooms, doors, room.index);
            boolean wither = maybeGrantKey(room, Pickup.Kind.WITHER, at);
            boolean blood = maybeGrantKey(room, Pickup.Kind.BLOOD, at);
            // grant_room_clear_rewards: a TNT (unless a key already brought one) and a 50% blessing.
            if (!wither && !blood) {
                pickups.add(Pickup.spawn(level, at.x, at.y - 1, at.z, Pickup.Kind.TNT));
            }
            Random drop = derived("clear", room.index);
            if (drop.nextBoolean()) {
                Pickup.Kind blessing = Blessings.itemKind(drop);
                pickups.add(Pickup.spawn(level, at.x, at.y - 1, at.z + 1, blessing));
            }
        }
    }

    /**
     * Drops a key for a room that has no starred mobs left, if it leads to a locked door of that
     * kind without one yet (maybe_grant_door_key). [at] is where the last starred mob died, or
     * null for the cell's middle.
     */
    private boolean maybeGrantKey(RoomState room, Pickup.Kind kind, Vec3 at) {
        if (room.starredMobsRemaining != 0) {
            return false;
        }
        Layout.DoorType wanted = kind == Pickup.Kind.WITHER ? Layout.DoorType.WITHER : Layout.DoorType.BLOOD;
        List<Layout.Segment> segments = room.segments();
        for (int s = 0; s < segments.size(); s++) {
            for (RoomState.Neighbour n : room.neighbours.get(s)) {
                DoorState door = doors.get(n.doorIndex());
                if (door.type() == wanted && !door.keyGranted) {
                    door.keyGranted = true;
                    double x = at != null ? at.x : Layout.ORIGIN_X + segments.get(s).x() * 32.0 + 16.0;
                    double y = at != null ? at.y - Pickup.KEY_DROP : 70.0;
                    double z = at != null ? at.z : Layout.ORIGIN_Z + segments.get(s).z() * 32.0 + 16.0;
                    pickups.add(Pickup.spawn(level, x, y, z, kind));
                    pickups.add(Pickup.spawn(level, x + 1, y, z, Pickup.Kind.TNT));
                    SimLog.event("key_drop", "kind", kind.name(), "room", room.data().name(), "x", x, "y", y, "z", z);
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- ticking

    @Override
    public void tick() {
        if (!tasks.isEmpty()) {
            List<Task> due = new ArrayList<>();
            for (Iterator<Task> it = tasks.iterator(); it.hasNext(); ) {
                Task task = it.next();
                if (task.due() <= ticks) {
                    due.add(task);
                    it.remove();
                }
            }
            due.forEach(task -> task.action().run());
        }
        faceMort();
        items.tick();
        gear.tick();
        abilities.tick();
        fallingBlocks.tick();
        mobs.tick();
        if (level.getGameTime() % 20 == 0) {
            mobs.sweep();
        }
        tickPickups();
        doorAnimation.tick();
        bloodCamp.tick();
        BossArena.of(level).tick();
        bossFight.tick();
        bossSpawnTrigger();
        bossChat.tick(ticks, bossContext::broadcast);
        bar.tick(level.players());

        switch (state) {
            // Someone who hadn't readied may have left.
            case NOT_READY -> {
                if (!ready.isEmpty() && ticks % 10 == 0) {
                    startIfAllReady();
                }
            }
            case STARTING -> tickStarting();
            case STARTED -> tickStarted();
            default -> {
            }
        }
        // The map goes out every 5 ticks (clear-rooms-doors.md §7: gaps 4-6), the whole 128x128
        // patch when it changed (Odin's map reads only full updates), else the icons alone.
        if (ticks % MAP_TICKS == 0 && map.takeDirty()) {
            for (int i = 0; i < map.pixels.length; i++) {
                mapData.setColor(i % 128, i / 128, map.pixels[i]);
            }
            for (ServerPlayer player : level.players()) {
                sendMap(player);
            }
        }
        for (ServerPlayer player : level.players()) {
            if (ticks % 60 == 0) {
                player.addEffect(new MobEffectInstance(MobEffects.HASTE, 200, 2, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, false, false));
            }
        }
        if (state == State.STARTED) {
            lava.tick();
            combat.tick();
        }
        updateSidebar();
        tabList.tick();
        // Hypixel sends a ping every server tick, and mods (dtMap's secret spawn timer, tick
        // counters) count server ticks by them.
        var ping = new ClientboundPingPacket((int) (ticks % Integer.MAX_VALUE) + 1);
        forEachPlayer(p -> p.connection.send(ping));
        ticks++;
    }

    /** Hypixel's start lines for a class only [player] plays: what doubling did to its passives. */
    private void announceClass(ServerPlayer player) {
        if (!PlayerStats.solo(player)) {
            return;
        }
        DungeonClass c = PlayerStats.classOf(player);
        int lv = PlayerStats.profile(player).classLevel(c);
        String tag = "§a[" + c.title + "] §f";
        player.sendSystemMessage(EngineItems.text("§6Your " + c.title + " stats are doubled because you are the only player using this class!"));
        for (DungeonClass.Doubled d : c.doubled(lv)) {
            player.sendSystemMessage(EngineItems.text(tag + d.label() + " §c" + Stat.number(d.normal()) + d.unit()
                + " §f-> §a" + Stat.number(d.solo()) + d.unit()));
        }
    }

    /**
     * "Starting in 4 seconds." a median 9 ticks after the last ready, then 3, 2, "1 second." 20
     * ticks apart, and Mort's first line 20 after that (clear-rooms-doors.md §1): 89 ticks.
     */
    private static final int COUNTDOWN_LEAD = 9;
    private static final int COUNTDOWN_TICKS = COUNTDOWN_LEAD + 80;

    private void tickStarting() {
        countdown--;
        if (countdown == 0) {
            forEachPlayer(p -> {
                sound(p, SoundEvents.ENDER_DRAGON_GROWL, 1f, 1f);
                sound(p, SoundEvents.VILLAGER_TRADE, 1f, 0.7f);
            });
            forEachPlayer(this::announceClass);
            forEachPlayer(p -> Pets.trigger(p, Pets.Trigger.DUNGEON_START));
            broadcast("§e[NPC] §bMort§f: Here, I found this map when I first entered the dungeon.");
            score.reset();
            score.master = floor.master();
            score.startNanos = System.nanoTime();
            state = State.STARTED;
            runTicks = 0;
            start();
        } else if (countdown <= 80 && countdown % 20 == 0) {
            int seconds = countdown / 20;
            broadcast("§aStarting in " + seconds + " second" + (seconds == 1 ? "" : "s") + ".");
        }
    }

    /** Ticks since the run started. */
    private long runTicks;

    private static final int MAP_TICKS = 5;

    private void tickStarted() {
        runTicks++;
        if (ticks % MAP_TICKS == 0) {
            sendMapIcons();
        }
        score.elapsedTicks = practice != null ? practice.elapsedTicks(runTicks) : runTicks;
        abilities.remind(runTicks, level.players());
        score.syncExploration(rooms);
        if (runTicks % Score.REFRESH_TICKS == 0) {
            // The live score is recomputed every 200 ticks from Mort's first line (critic S22).
            score.refresh();
            SimLog.event("score_refresh", "score", score.live);
        }

        // No Mort in a practice room, but his lines still go out: client mods take them as the start
        // of the run (Devonian's splits and star-mob boxes, Skyblocker's score and map).
        if (runTicks == 1 && practice != null) {
            broadcast("§e[NPC] §bMort§f: Here, I found this map when I first entered the dungeon.");
        }
        if (runTicks == 40) {
            forEachPlayer(p -> sound(p, SoundEvents.VILLAGER_TRADE, 1f, 0.7f));
            broadcast("§e[NPC] §bMort§f: You should find it useful if you get lost.");
        }
        if (runTicks == 70) {
            forEachPlayer(p -> sound(p, SoundEvents.VILLAGER_TRADE, 1f, 0.7f));
            broadcast("§e[NPC] §bMort§f: Good luck.");
        }
        if (runTicks % 20 == 0) {
            for (DoorState door : doors) {
                if (door.type() == Layout.DoorType.BLOOD && door.locked) {
                    level.playSound(null, door.door.x(), 70, door.door.z(), SoundEvents.ELDER_GUARDIAN_HURT, SoundSource.HOSTILE, 3f, 0.5f);
                }
            }
        }

        Set<Integer> justEntered = new LinkedHashSet<>();
        Set<Integer> neighbours = new LinkedHashSet<>();
        for (ServerPlayer player : level.players()) {
            int index = roomAt(player.getX(), player.getZ());
            if (index < 0) {
                continue;
            }
            RoomState room = rooms.get(index);
            if (!room.entered) {
                room.entered = true;
                justEntered.add(index);
            }
            for (List<RoomState.Neighbour> list : room.neighbours) {
                for (RoomState.Neighbour n : list) {
                    neighbours.add(n.roomIndex());
                }
            }
        }
        // As on the server, a practice room spawns no mobs (not even its mini-boss) unless the
        // practice mobs toggle is on.
        boolean spawnMobs = practice == null || practiceMobs;
        if (spawnMobs) {
            pendingMobRooms.addAll(justEntered);
        }
        for (int index : justEntered) {
            secrets.roomEntered(rooms.get(index));
            if (spawnMobs) {
                mobs.roomEntered(rooms.get(index));
                mobs.scaleOnEntry(rooms.get(index));
            }
            Puzzle puzzle = puzzles.get(index);
            if (puzzle != null) {
                puzzle.entered();
            }
        }
        secrets.tick(runTicks);
        crushers.tick();
        for (Puzzle puzzle : List.copyOf(puzzles.values())) {
            puzzle.tick();
        }
        for (int index : neighbours) {
            if (spawnMobs && !justEntered.contains(index) && !rooms.get(index).mobsSpawned) {
                pendingMobRooms.add(index);
            }
        }
        if (runTicks % MOB_SPAWN_CYCLE_TICKS == 0 && !pendingMobRooms.isEmpty()) {
            List<Integer> spawning = new ArrayList<>(pendingMobRooms);
            pendingMobRooms.clear();
            for (int index : spawning) {
                RoomState room = rooms.get(index);
                if (!room.mobsSpawned) {
                    room.mobsSpawned = true;
                    mobs.spawnRoom(room);
                }
                if (!room.entered) {
                    continue;
                }
                map.drawRoom(rooms, doors, index);
                maybeGrantKey(room, Pickup.Kind.WITHER, null);
                maybeGrantKey(room, Pickup.Kind.BLOOD, null);
            }
        }
        if (practice != null) {
            practice.tick(this, runTicks);
        }
    }

    private void tickPickups() {
        for (Iterator<Pickup> it = pickups.iterator(); it.hasNext(); ) {
            Pickup pickup = it.next();
            // OPEN: 1 (does Hypixel refuse a second wither key?): the next key waits on the
            // ground while the team holds one, which keeps keys and doors alternating as measured.
            boolean blocked = pickup.kind == Pickup.Kind.WITHER && witherKeys > 0;
            ServerPlayer taker = pickup.tick(level, blocked);
            if (taker == null) {
                continue;
            }
            it.remove();
            switch (pickup.kind) {
                case WITHER -> witherKeys++;
                case BLOOD -> bloodKey = true;
                default -> {
                }
            }
            if (pickup.kind.isBlessing()) {
                broadcast(Blessings.obtainedLine(taker, pickup.kind));
                Blessings.grant(this, pickup.kind, 5, taker, pickup.age());
            } else {
                broadcast(nameColour(taker) + "§f §ehas obtained " + pickup.kind.name + "§e!");
                // Hypixel's follow-up line, which key counters (NoammAddons' door keys) read (e12827).
                if (pickup.kind == Pickup.Kind.WITHER) {
                    broadcast("§e§lRIGHT CLICK §7on §7a §8WITHER §7door§7 to open it. This key can only be used to open §a1§7 door!");
                } else if (pickup.kind == Pickup.Kind.BLOOD) {
                    broadcast("§e§lRIGHT CLICK §7on §7the §cBLOOD DOOR§7 to open it. This key can only be used to open §a1§7 door!");
                }
            }
        }
    }

    private void faceMort() {
        if (mort == null) {
            return;
        }
        ServerPlayer nearest = null;
        double best = MORT_FACE_RANGE * MORT_FACE_RANGE;
        for (ServerPlayer player : level.players()) {
            double d = player.distanceToSqr(mort);
            if (d <= best) {
                best = d;
                nearest = player;
            }
        }
        if (nearest != null) {
            double dx = nearest.getX() - mort.getX();
            double dz = nearest.getZ() - mort.getZ();
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            mort.setYRot(yaw);
            mort.setYHeadRot(yaw);
            mort.setYBodyRot(yaw);
        }
    }

    // ---------------------------------------------------------------- map and sidebar

    @Override
    public void resendHud() {
        // The location again too, for mods that forget it when the level changes.
        forEachPlayer(HypixelSignals::join);
        sidebars.values().forEach(Sidebar::resend);
        tabList.resend();
    }

    /** The room a world position is in, or -1 (the grid cell it falls in). */
    public int roomAt(double x, double z) {
        int ix = (int) x;
        int iz = (int) z;
        if (ix < Layout.ORIGIN_X || iz < Layout.ORIGIN_Z) {
            return -1;
        }
        int gx = (ix - Layout.ORIGIN_X) / Layout.CELL;
        int gz = (iz - Layout.ORIGIN_Z) / Layout.CELL;
        if (gx >= Layout.GRID || gz >= Layout.GRID) {
            return -1;
        }
        return grid[gx + gz * Layout.GRID];
    }

    private void sendMapIcons() {
        // As on Hypixel, each player sees their own arrow as the green FRAME icon and the others as
        // PLAYER icons in party order; Devonian and NoammAddons tell who is who that way.
        List<ServerPlayer> players = level.players();
        for (ServerPlayer viewer : players) {
            List<MapDecoration> icons = new ArrayList<>();
            for (ServerPlayer player : players) {
                byte[] pos = map.toIcon(player.getX(), player.getZ());
                float yaw = ((player.getYRot() % 360f) + 360f) % 360f;
                byte rot = (byte) (Math.round(yaw / 22.5f) & 0x0F);
                var type = player == viewer ? MapDecorationTypes.FRAME : MapDecorationTypes.PLAYER;
                icons.add(new MapDecoration(type, pos[0], pos[1], rot, Optional.empty()));
            }
            viewer.connection.send(new ClientboundMapItemDataPacket(mapId, (byte) 0, true, Optional.of(icons), Optional.empty()));
        }
    }

    private void sendMap(ServerPlayer player) {
        var patch = new MapItemSavedData.MapPatch(0, 0, 128, 128, map.pixels.clone());
        player.connection.send(new ClientboundMapItemDataPacket(mapId, (byte) 0, true, Optional.of(List.of()), Optional.of(patch)));
    }

    private static final String[] SKYBLOCK_MONTHS = {
        "Early Spring", "Spring", "Late Spring", "Early Summer", "Summer", "Late Summer",
        "Early Autumn", "Autumn", "Late Autumn", "Early Winter", "Winter", "Late Winter",
    };

    private static String skyblockDate() {
        long epoch = 1_559_829_300_000L;
        long year = 124L * 60 * 60 * 1000;
        long month = year / 12;
        long day = month / 31;
        long elapsed = Math.max(0, Instant.now().toEpochMilli() - epoch);
        long dayOfYear = (elapsed % year) / day;
        int m = (int) (dayOfYear / 31);
        long d = dayOfYear % 31 + 1;
        String suffix = (d % 100 >= 11 && d % 100 <= 13) ? "th" : switch ((int) (d % 10)) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return SKYBLOCK_MONTHS[m] + " " + d + suffix;
    }

    /** Cleared % and the teammates' health, as last drawn: Hypixel moves them on a 20-tick grid. */
    private int shownPercent;
    private final Map<UUID, String> shownHealth = new HashMap<>();

    /**
     * The sidebar as Hypixel draws it (menus-ui.md "Sidebar", 15 lines at most): date and server
     * with the current room's id, the SkyBlock date and time, the location; then the run's lines.
     * Before the run: each player "[M] ign [LvN]" and "Starting in: 0:04". In the clear: "Keys:
     * ■ ✗ ■ 0x" (live), "Time Elapsed", "Cleared: x% (score)" and the teammates' health (the
     * viewer isn't listed). In the boss the Keys line goes and the date line ends in "f7".
     */
    private void updateSidebar() {
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern("MM/dd/yy"));
        String time = now.format(DateTimeFormatter.ofPattern("h:mma", Locale.ROOT)).toLowerCase(Locale.ROOT);
        List<ServerPlayer> players = level.players();
        boolean boss = bossFight.active() || bossFight.ended();
        if (state != State.STARTED || runTicks % 20 == 0) {
            shownPercent = score.clearedPercent();
            for (ServerPlayer p : players) {
                shownHealth.put(p.getUUID(), combat.isGhost(p) ? "§cDEAD"
                    : String.format(Locale.ROOT, "§a%,d§c❤", Math.round(combat.health(p))));
            }
        }
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer viewer : players) {
            present.add(viewer.getUUID());
            String roomId = "";
            int index = roomAt(viewer.getX(), viewer.getZ());
            if (index >= 0 && !boss) {
                roomId = " " + rooms.get(index).data().id();
            }
            List<String> lines = new ArrayList<>(List.of(
                "§e§lSKYBLOCK", "§7" + date + " §8m0RC" + (boss ? " f7" : roomId), "", " " + skyblockDate(), " §7" + time,
                " §7⏣ §cThe Catacombs §7(" + floor.code + ")", ""));
            switch (state) {
                case NOT_READY, STARTING -> {
                    for (ServerPlayer p : players) {
                        DungeonClass c = PlayerStats.classOf(p);
                        int lv = PlayerStats.profile(p).classLevel(c);
                        lines.add("§e[" + c.title.charAt(0) + "] §b" + p.getName().getString() + " §7[Lv" + lv + "]");
                    }
                    lines.add("");
                    if (state == State.STARTING) {
                        lines.add("Starting in: §a0:0" + Math.min(4, (countdown + 19) / 20));
                        lines.add("");
                    }
                }
                case STARTED, FINISHED -> {
                    long seconds = (practice != null ? practice.elapsedTicks(runTicks) : runTicks) / 20;
                    String elapsed = seconds >= 60
                        ? String.format("%02dm %02ds", seconds / 60, seconds % 60)
                        : String.format("%02ds", seconds);
                    if (!boss) {
                        lines.add("Keys: §c■ " + (bloodKey ? "§a✓" : "§c✗") + " §8■ §a" + witherKeys + "x");
                    }
                    lines.add("Time Elapsed: §a" + elapsed);
                    lines.add("Cleared: §a" + shownPercent + "% §8(" + score.live + ")");
                    lines.add("");
                    boolean any = false;
                    for (ServerPlayer p : players) {
                        if (p == viewer) {
                            continue;
                        }
                        any = true;
                        DungeonClass c = PlayerStats.classOf(p);
                        lines.add("§e[" + c.title.charAt(0) + "] §b" + p.getName().getString() + " "
                            + shownHealth.getOrDefault(p.getUUID(), ""));
                    }
                    if (any) {
                        lines.add("");
                    }
                }
            }
            lines.add("§ewww.hypixel.net");
            // Sidebar lines must differ from each other; pad repeats (blank lines) with colour codes.
            Set<String> seen = new HashSet<>();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                while (!seen.add(line)) {
                    line = line + "§r";
                }
                lines.set(i, line);
            }
            sidebars.computeIfAbsent(viewer.getUUID(), id -> new Sidebar(level, id)).show(lines);
        }
        sidebars.keySet().removeIf(id -> !present.contains(id));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A player's name as Hypixel's dungeon lines colour it: by rank ("§b[MVP§3+§b] name" on the key
     * lines, "§bname" on ready and door lines). Rustclear players have no rank: gray, as an unranked
     * player's name is.
     */
    static String nameColour(ServerPlayer player) {
        return "§7" + player.getName().getString();
    }

    void broadcast(String message) {
        forEachPlayer(p -> p.sendSystemMessage(EngineItems.text(message)));
    }

    void forEachPlayer(Consumer<ServerPlayer> action) {
        for (ServerPlayer player : List.copyOf(level.players())) {
            action.accept(player);
        }
    }

    void sound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.MASTER, volume, pitch);
    }
}
