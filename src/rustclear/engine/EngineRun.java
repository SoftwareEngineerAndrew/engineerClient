package net.rustclear.mod.engine;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;

/**
 * One run in one [Instance]: what parties, duels and the event hooks need from it, and nothing
 * about what is being run. DungeonRun is the dungeon; a boss fight (M7) is meant to be another
 * implementation, so parties, ready-up, synchronized starts and duel results work for it as they
 * are. Server thread only.
 */
interface EngineRun {
    /** Where the run happens; its players are this level's. */
    ServerLevel level();

    void tick();

    /** Removes everything the run spawned and takes its HUD off its players' screens. */
    void dispose();

    /** Gives [player] the run's items (on joining it, respawning or a new run). */
    void equip(ServerPlayer player);

    /** Sends the HUD again (after client mods reset for a new run). */
    void resendHud();

    // ---------------------------------------------------------------- ready-up and start

    enum Phase { LOBBY, COUNTDOWN, RUNNING, FINISHED }

    Phase phase();

    boolean isReady(UUID id);

    void toggleReady(ServerPlayer player);

    /** The leader's Start: everyone ready at once. */
    void forceStart();

    /** Whether every player here is ready (and there is at least one). */
    boolean allReady();

    /**
     * While held, everyone being ready doesn't start the countdown: a duel starts every team's
     * countdown itself, on one tick, with [beginCountdown].
     */
    void holdStart(boolean hold);

    void beginCountdown();

    // ---------------------------------------------------------------- input (true: handled)

    boolean onHitBlock(ServerPlayer player, BlockPos pos);

    boolean onUseBlock(ServerPlayer player, BlockPos pos, Direction face);

    boolean onUseItem(ServerPlayer player);

    /** A left click with anything in hand, air or not (shortbows shoot on it). */
    void onLeftClick(ServerPlayer player);

    /** A player's arrow reached [entity]. True when the run took the hit (the arrow is gone). */
    boolean onArrowHit(AbstractArrow arrow, Entity entity);

    /** A drawn bow let go after [ticksDrawn]. True when the run shot it instead of vanilla. */
    boolean onBowRelease(ServerPlayer player, ItemStack bow, int ticksDrawn);

    /** A right click on an entity (Mort opens the ready menu). */
    boolean onUseEntity(ServerPlayer player, Entity entity);

    boolean onAttackEntity(ServerPlayer player, Entity entity);

    void startedTracking(Entity entity, ServerPlayer player);

    void stoppedTracking(Entity entity, ServerPlayer player);

    // ---------------------------------------------------------------- results

    /** How far along the run is, for duel progress and results. */
    Progress progress();

    /**
     * Where a run stands: rooms cleared, secrets, ticks since it started, its score (0 until it
     * starts), and whether it reached its finish (a dungeon's blood door opening, for now).
     */
    record Progress(int roomsCleared, int totalRooms, int secretsFound, int totalSecrets, long ticks, int score,
                    boolean finished) {}
}
