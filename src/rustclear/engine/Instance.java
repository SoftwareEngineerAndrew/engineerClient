package net.rustclear.mod.engine;

import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.rustclear.mod.Floor;
import net.rustclear.mod.RustclearMod;

/**
 * One dungeon in one level: its builder, plan, spawn and run, and the practice room when one is
 * loaded. A party plays in the overworld's instance; in a duel each team has its own, in its own
 * dimension (TeamDimensions), so every team's dungeon sits at Hypixel's coordinates. Its players
 * are its level's players. Server thread only.
 */
final class Instance {
    final ServerLevel level;
    /** "A" or "B" for a duel team's instance, null for the party's. */
    final String team;
    private DungeonBuilder builder;
    private DungeonPlan plan;
    private DungeonBuilder.Spawn spawn;
    private EngineRun run;
    /** The practice room loaded with /practice, or null when playing normal dungeons. */
    Practice.Pick practicePick;
    Practice.Route practiceRoute;

    Instance(ServerLevel level, String team) {
        this.level = level;
        this.team = team;
    }

    EngineRun run() {
        return run;
    }

    /** The run as a dungeon, or null when the run is something else (or none). */
    DungeonRun dungeon() {
        return run instanceof DungeonRun d ? d : null;
    }

    DungeonPlan plan() {
        return plan;
    }

    List<ServerPlayer> players() {
        return List.copyOf(level.players());
    }

    /**
     * Tears down the current dungeon, builds [next] here and sends this instance's players to its
     * start. With a practice [route], the start is the route's door and the run is live at once.
     */
    void install(DungeonPlan next, RoomStore store, Practice.Route route, int waterPattern, boolean practiceMobs,
                 Random random, BuildTimer timer) {
        install(next, store, route, waterPattern, practiceMobs, random, timer,
            EngineServer.settings().floorOrDefault(), null, null, null);
    }

    /**
     * [install] on [floor] (not the leader's saved one), with the run seeded from [runSeed] (null:
     * unseeded), its start at [spawnOverride] when given (as a route's door is), and [hooks] for
     * practice (null in a normal run).
     */
    void install(DungeonPlan next, RoomStore store, Practice.Route route, int waterPattern, boolean practiceMobs,
                 Random random, BuildTimer timer, Floor floor, Long runSeed,
                 DungeonBuilder.Spawn spawnOverride, DungeonRun.PracticeHooks hooks) {
        if (run != null) {
            run.dispose();
            run = null;
        }
        timer.lap("dispose");
        if (builder == null) {
            builder = new DungeonBuilder(level);
            builder.warm(store);
            timer.lap("warm");
        }
        plan = next;
        spawn = builder.build(plan, store, random, timer);
        boolean guests = false;
        for (ServerPlayer player : level.players()) {
            guests |= HypixelSignals.newDungeon(player, builder.touchedChunks());
        }
        timer.lap("chunks");
        if (route != null) {
            Practice.DoorSpawn door = route.spawn;
            spawn = new DungeonBuilder.Spawn(door.x(), door.y(), door.z(), door.yaw());
        }
        if (spawnOverride != null) {
            spawn = spawnOverride;
        }
        DungeonRun dungeon = new DungeonRun(level, plan, floor, runSeed, hooks);
        run = dungeon;
        if (route != null) {
            dungeon.startPractice(route, waterPattern, practiceMobs);
        }
        dungeon.populate();
        if (guests) {
            // Guests reset when their rustclear:signals arrives; the HUD follows it on the same connection.
            dungeon.schedule(2, dungeon::resendHud);
        }
        for (ServerPlayer player : level.players()) {
            // A ghost from the last run (Combat) comes back for the new one.
            player.setGameMode(GameType.ADVENTURE);
            PlayerSpeed.apply(player);
            toSpawn(player);
            run.equip(player);
        }
        timer.lap("populate");
        RustclearMod.LOGGER.info("Built Rustclear dungeon {}{} in {} ms ({}; {})", plan.layout().source,
            team == null ? "" : " for team " + team, timer.totalMs(), builder.stats(), timer);
    }

    /** Sends [player] to this dungeon's start, from wherever (any level) they are. */
    void toSpawn(ServerPlayer player) {
        if (spawn != null) {
            player.teleportTo(level, spawn.x(), spawn.y(), spawn.z(), Set.of(), spawn.yaw(), 0f, true);
        }
    }

    /** Ends this instance's run (its level stays). */
    void dispose() {
        if (run != null) {
            run.dispose();
            run = null;
        }
        practicePick = null;
        practiceRoute = null;
    }
}
