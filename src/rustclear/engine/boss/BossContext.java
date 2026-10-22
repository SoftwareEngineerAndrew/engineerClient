package net.rustclear.mod.engine.boss;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the blood camp and the boss phases see of their run (DungeonRun implements it). The map,
 * score, tab list and party stay the run's, so they carry through into the boss as on Hypixel.
 */
public interface BossContext {
    ServerLevel level();

    /** The run's players, ghosts included. */
    List<ServerPlayer> players();

    Profile profile();

    RunBar bar();

    /** The one boss chat queue (Watcher and bosses). */
    BossChat chat();

    BossEntities entities();

    /** A chat line (§ codes) to every player, as styled text. */
    void broadcast(String legacy);

    /** [attack] hits [player] for its floor damage: the chat line, Defense (or True Defense), death. */
    void hit(ServerPlayer player, Attacks.Attack attack);

    /** [attack] hits [player] for [damage] (an attack whose damage varies, like Giga Lightning). */
    void hit(ServerPlayer player, Attacks.Attack attack, double damage);

    boolean isGhost(ServerPlayer player);

    /** The boss is beaten (Necron, or the Wither King on M7): the end blocks, the reward room and its chests. */
    void defeated();

    /** [player] dies (a mask or Phoenix may still save them); null [killer]: "You died and became a ghost." */
    void kill(ServerPlayer player, String killer);

    /** Ticks since Mort's first line. */
    long runTicks();

    /** The run's random. */
    java.util.Random random();

    /** A Random of its own for [stream] ("blood"...), from the dungeon's seed: a replayed map rolls it the same. */
    java.util.Random random(String stream);

    /**
     * The Blood room: its centre (x.5, floor + 4, z.5; the Watcher's home), the floor's y, and
     * the side the blood door is on (a unit step in x or z from the centre).
     */
    record Room(net.minecraft.world.phys.Vec3 centre, int floorY, int doorDx, int doorDz) {}

    /** The Blood room, or null when the run has none. */
    Room bloodRoom();

    /** Whether [player] stands inside the Blood room's 31 x 31. */
    boolean inBloodRoom(ServerPlayer player);

    /**
     * Spawns one of the Watcher's mobs through the run's mob system, so every hit and ability
     * reaches it; [onDeath] runs on the tick it dies. Null if it couldn't be made.
     */
    net.minecraft.world.entity.LivingEntity spawnCampMob(net.rustclear.mod.engine.blood.CampMob mob,
                                                       net.minecraft.world.phys.Vec3 at, float yaw, Runnable onDeath);

    /** The camp's live mobs (not dying). */
    List<net.minecraft.world.entity.Entity> campMobs();

    /**
     * A camp attack hits [player]: [line] (§ codes) in their chat, then [raw] damage less Defense
     * (True Defense when [trueDamage]); a death names [killer].
     */
    void campHit(ServerPlayer player, String line, double raw, boolean trueDamage, String killer);

    // ---------------------------------------------------------------- the boss

    /** The boss phases' block changes (restored at the run's end). */
    BossBlocks blocks();

    /** [player]'s name as Hypixel colours it in chat (by rank). */
    String nameOf(ServerPlayer player);

    /**
     * One of a boss phase's mobs (P1's adds) through the run's mob system, outside any room, so
     * every hit and ability reaches it; [onDeath] runs when it dies.
     */
    net.minecraft.world.entity.LivingEntity spawnBossMob(net.rustclear.mod.engine.blood.CampMob mob,
                                                       net.minecraft.world.phys.Vec3 at, float yaw, Runnable onDeath);

    /** Every player hit, arrow and ability on [entity] goes to [onDamage] (a boss), not to a health bar of its own. */
    void registerBoss(net.minecraft.world.entity.LivingEntity entity, java.util.function.LongConsumer onDamage);

    void unregisterBoss(net.minecraft.world.entity.Entity entity);

    /** Kills one of a phase's mobs (as a player would). */
    void killMob(net.minecraft.world.entity.Entity entity);

    /** The party goes through the blood portal into the boss (Maxor's phase starts 2 ticks later). */
    void enterBoss();

    /** "A Crypt Wither Skull exploded, hitting you for 9,000 damage." on [player]. */
    void cryptSkull(ServerPlayer player);
}
