package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.clear.bots.BotsModule
import com.engineerclient.p3sim.clear.blood.BloodModule
import com.engineerclient.p3sim.clear.damage.DamageModule
import com.engineerclient.p3sim.clear.hud.HudModule
import com.engineerclient.p3sim.clear.layout.LayoutModule
import com.engineerclient.p3sim.clear.mobs.MobsModule
import com.engineerclient.p3sim.clear.puzzles.PuzzlesModule
import com.engineerclient.p3sim.clear.puzzles.hard.HardPuzzlesModule
import com.engineerclient.p3sim.clear.score.ScoreModule
import com.engineerclient.p3sim.clear.secrets.SecretsModule
import com.engineerclient.p3sim.clear.state.DoorsModule
import com.engineerclient.p3sim.clear.traps.TrapsModule
import com.engineerclient.p3sim.clear.ui.UiModule
import com.engineerclient.p3sim.run.api.PuzzleType
import com.google.gson.JsonObject

/**
 * One participant in a run's lifecycle. The run calls every system, in the fixed order of [AdsModules.all], on the sim
 * server thread; a system that throws is logged and skipped, the others still run. A system keeps its state in its own
 * object (reset in [onRunStart], saved in [snapshot], restored in [restore]); shared facts go through [RunState] and the
 * [RunBus].
 *
 * Lifecycle of one run, in call order:
 *  1. `onRunStart` (once, the dungeon is planned and the world region is registered, the countdown begins);
 *  2. `onStage` at every stage change (`from` is null for the first), `onTick` every server tick (then `onTickEnd`, once every
 *     system's `onTick` ran), `onEvent` for every bus event;
 *  3. `onRunEnd` (once). After it nothing else is called for that run.
 * A savepoint restore calls [restore] on every system with the data its [snapshot] returned, in module order, then posts
 * `SavepointRestored`. [onRunStart] is NOT called again by a restore.
 */
interface RunSystem {
    /** Stable lowercase id; also the key of this system's entry in a savepoint. */
    val id: String

    fun onRunStart(run: Run) {}
    fun onTick(run: Run) {}
    fun onTickEnd(run: Run) {}
    fun onStage(run: Run, from: RunStage?, to: RunStage) {}
    fun onEvent(run: Run, event: RunEvent) {}
    fun onRunEnd(run: Run, reason: EndReason) {}

    /** This system's state for a savepoint (JSON, small), or null if it has none. Must not hold entity references: ids, numbers, strings. */
    fun snapshot(run: Run): JsonObject? = null

    /** Rebuilds the state [snapshot] returned, including re-creating its entities. */
    fun restore(run: Run, data: JsonObject) {}
}

/**
 * A work item's module: a [RunSystem] plus the hooks that let it add settings, commands and puzzle types WITHOUT editing any
 * shared file. Each work item fills in its own `object ...Module : AdsModule` (the skeleton created them all, empty); this is
 * how a module is "registered": by existing in [AdsModules.all].
 *
 * Rules for a module object: initialising it must be cheap and must not touch Minecraft, Odin, `P3Sim` or `Fight` statics
 * (the registry is read while those are still starting up, and the headless `ModulesTest` instantiates every module). Do the
 * real work in the hooks. In particular create Odin settings INSIDE [registerSettings] (keep them in a `lateinit var`), not in
 * the object's initialiser, and keep pure logic free of settings: pass values in as parameters.
 */
interface AdsModule : RunSystem {
    /** The work item that owns this module (`layout`, `doors`...); see docs/ads/OWNERSHIP.md. */
    val owner: String

    /**
     * Called once at game start on the client thread, before any world: register `SimNet.onServer/onClient` kinds,
     * Fabric callbacks and other one-time wiring here. Both halves of the integrated server live in this game, so a module
     * registers both its server and client handlers.
     */
    fun onInit() {}

    /**
     * Adds this module's settings to the ADS Odin module ([host] is `P3Sim`): `host.registerSetting(NumberSetting(...))`.
     * Keep the setting objects in the module; read them by `.value`. Called once at game start. Every constant that is
     * documented as unmeasured and that a player might want to change is a setting.
     */
    fun registerSettings(host: com.engineerclient.ads.settings.Settings) {}

    /** `/ads dev <name>` commands (spikes, probes, tools). */
    fun devCommands(): List<AdsCommand> = emptyList()

    /** `/ads run <name>` subcommands (starting, restarting, controlling a run). */
    fun runCommands(): List<AdsCommand> = emptyList()

    /** Puzzle kinds this module provides; the puzzles module collects all of them. */
    fun puzzleTypes(): List<PuzzleType> = emptyList()

    // ---- player interaction (see [RunInput]) ------------------------------------------------------------------------
    // Called on the server thread, as the clicking player, for the MAIN hand, only while a Run is in COUNTDOWN, CLEAR or BLOOD.
    // Return null for "not mine" (the next module is asked, then the sim's own item and block handlers); any other result ends
    // the question: SUCCESS to consume the click, FAIL to refuse it, PASS to stop the modules but let the sim's handlers go on.
    // Do not register your own Fabric UseBlock, UseEntity, AttackEntity or UseItem callback for run logic: use these.

    /** A right click on a block (a door, chest, lever, skull, a puzzle block...). */
    fun onUseBlock(run: Run, player: net.minecraft.server.level.ServerPlayer, hand: net.minecraft.world.InteractionHand, hit: net.minecraft.world.phys.BlockHitResult): net.minecraft.world.InteractionResult? = null

    /** A right click on an entity (a puzzle NPC, a key stand, a chest minecart...). */
    fun onUseEntity(run: Run, player: net.minecraft.server.level.ServerPlayer, hand: net.minecraft.world.InteractionHand, entity: net.minecraft.world.entity.Entity): net.minecraft.world.InteractionResult? = null

    /** A left click on an entity (a melee hit on a mob, a bat). */
    fun onAttackEntity(run: Run, player: net.minecraft.server.level.ServerPlayer, hand: net.minecraft.world.InteractionHand, entity: net.minecraft.world.entity.Entity): net.minecraft.world.InteractionResult? = null

    /** A right click with the held item, not aimed at a block or entity. */
    fun onUseItem(run: Run, player: net.minecraft.server.level.ServerPlayer, hand: net.minecraft.world.InteractionHand): net.minecraft.world.InteractionResult? = null
}

/**
 * Every module, in the order they are called. The order is the contract: earlier modules have seen an event before later
 * ones, and `onTick` runs top to bottom. The list is fixed by the skeleton and never edited by a work item.
 *
 * Why this order: the engine's own plumbing first; the layout (the plan the rest read); the state module (room check marks,
 * doors, keys) before anything that reacts to them; mobs, then damage (which kills them); secrets and score (which read the
 * check marks); puzzles and traps; the bots (which read all of that); blood (the Watcher and the boss seam); the HUD last,
 * so every surface draws the state as it is at the end of the tick; the run UI has no tick work.
 */
object AdsModules {
    val all: List<AdsModule> by lazy {
        listOf(
            EngineModule,
            com.engineerclient.p3sim.clear.replay.ReplayModule,
            LayoutModule,
            DoorsModule,
            MobsModule,
            DamageModule,
            SecretsModule,
            ScoreModule,
            PuzzlesModule,
            HardPuzzlesModule,
            TrapsModule,
            BotsModule,
            BloodModule,
            HudModule,
            UiModule,
        )
    }

    fun byId(id: String): AdsModule? = all.firstOrNull { it.id == id }
}
