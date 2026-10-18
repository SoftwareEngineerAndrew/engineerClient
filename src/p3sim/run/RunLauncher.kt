package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.PackSet
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.run.api.Services

/**
 * How a full run is started from the client: the call the run UI (the start screen, `/ads run ...`) and the dev commands make.
 *
 * Why this is not just `SimNet.send`: the ADS world is made FRESH on every open (`SimWorld`), and the dungeon is written into
 * each chunk while it is generated, which is before any player or server-side message exists. So starting a run means: choose
 * the layout, plan it, register the world regions, THEN open (or rebuild) the world, and when its server is up start the [Run] with
 * the same config. The config therefore has to survive the world being made; [pending] holds it across that gap.
 *
 *  1. CLIENT thread: [launch] loads the packs (`Packs.loadAsync`), asks `Services.layout.plan` and `region`, registers the regions
 *     with [WorldRegions], stores the config and plan as [pending], and opens or rebuilds the world (`SimWorld.open` / `rebuild`).
 *     A problem (packs missing, layout not buildable) is a plain-English line through `reply`, never an exception, and nothing is changed.
 *  2. SERVER thread, once the sim server has started and the player has joined: the engine calls [takePending] and `Runs.start`.
 *
 * Starting a run from inside the ADS world therefore rebuilds the world (a few seconds). Restarting the SAME dungeon needs no
 * rebuild: that is a savepoint restore (`/ads run restart`, `restart room`), see [SavepointStore].
 *
 * Engine fills [launch]; the shape below is the contract the UI and the dev commands call.
 */
object RunLauncher {
    @Volatile
    var pending: RunConfig? = null
        private set

    /** The plan that goes with [pending], so the server side does not plan a second time (and cannot pick a different layout). */
    @Volatile
    var pendingPlan: DungeonPlan? = null
        private set

    /**
     * The pack set the pending plan and region were made from, pinned (see [PackSet.pin]) until the run takes over or the launch is dropped: a reload of
     * the packs in between (`/ads dev packs`, another launch) would otherwise close the room file under the chunk generator's workers.
     */
    private var pendingPacks: PackSet? = null

    /** Set while a launch is waiting for the packs (once the world is being opened, [pending] is what refuses a second one). */
    @Volatile private var launching = false

    /** The name of the dungeon region registered for the pending launch (so [abort] can take it out again). */
    private var dungeonRegion: String? = null

    /**
     * Starts a full run with [config] (client thread). Returns true when the launch was accepted: the packs load off-thread, then the
     * layout is planned, the regions are registered and the world is opened (or rebuilt, when you are in ADS already); every later
     * step reports through [reply] on the client thread, as plain sentences. False means refused at once (a party joiner cannot
     * start a run, one launch is already under way); [reply] says why.
     */
    fun launch(config: RunConfig, reply: (String) -> Unit): Boolean {
        val mc = net.minecraft.client.Minecraft.getInstance()
        if (launching || pending != null) { reply("A run is already starting."); return false }
        if (com.engineerclient.p3sim.P3Sim.inSim && !com.engineerclient.p3sim.P3Sim.localSim) { reply("Only the host can start a run."); return false }
        launching = true
        reply("Loading the data packs...")
        Packs.loadAsync().whenComplete { set, err ->
            mc.execute {
                try {
                    if (err != null || set == null) { reply("The data packs could not be read: ${err?.message ?: "unknown error"}"); return@execute }
                    val problem = begin(config, set, reply)
                    if (problem != null) reply(problem)
                } catch (t: Throwable) {
                    org.slf4j.LoggerFactory.getLogger("engineerclient").error("[ads] launching a run failed", t)
                    reply("Starting the run failed: ${t.message ?: t.javaClass.simpleName}. The log has the details.")
                    abort()
                } finally { launching = false }
            }
        }
        return true
    }

    /** Plans, registers the regions, remembers the launch and opens the world; a plain-English problem, or null when the world is opening. Client thread. */
    private fun begin(config: RunConfig, set: PackSet, reply: (String) -> Unit): String? {
        if (!set.canBuildDungeon) return set.userLines().joinToString(" ").ifBlank { Packs.missingMessage(set.dir) }
        // Lines about packs that did load but with problems (a missing mob table, say) are worth saying, once.
        set.userLines().forEach(reply)
        val plan = when (val r = Services.layout.plan(config, set)) {
            is DungeonPlan.Result.Ok -> r.plan
            is DungeonPlan.Result.Failed -> return r.failure.message
        }
        val region = Services.layout.region(plan, set) ?: return "The dungeon builder is not installed in this build, so a full run cannot be built."
        // Decode the volumes the layout needs now (off the generator's workers), so the world opens without stalling on them.
        registerDungeon(region)
        setPending(config, plan, set)
        reply("Building ${plan.layoutId} (seed ${config.seed})...")
        launchWorld()
        return null
    }

    /** Registers the dungeon's region and the clipped arena region for the world about to open, remembering them so [abort] can take them back. */
    internal fun registerDungeon(region: WorldRegion) {
        WorldRegions.register(region)
        dungeonRegion = region.name
        ArenaRegion.register()
    }

    /** Opens the world for the pending launch: a rebuild when you are in ADS already (the dungeon is written while chunks generate), else a plain open. */
    internal fun launchWorld() {
        if (com.engineerclient.p3sim.P3Sim.inSim) com.engineerclient.p3sim.SimWorld.rebuild(forRun = true) else com.engineerclient.p3sim.SimWorld.open(forRun = true)
    }

    /**
     * Drops a launch that will not happen (the world failed to open, a plain ADS open or rebuild while one was waiting): the pending
     * config and plan, and the regions registered for it. The arena and dungeon regions of a world that is already running are left alone.
     */
    fun abort() {
        // Nothing was waiting (a run is already on, or none was asked for): the regions in use belong to the world that is running.
        if (pending == null && dungeonRegion == null) return
        cancel()
        WorldRegions.unregister(ArenaRegion.NAME)
        dungeonRegion?.let { WorldRegions.unregister(it) }
        dungeonRegion = null
    }

    /** Engine: remembers what to start once the world is up. */
    @Synchronized fun setPending(config: RunConfig, plan: DungeonPlan, packs: PackSet? = null) {
        dropPendingPacks()
        pending = config; pendingPlan = plan
        packs?.pin(); pendingPacks = packs
        lastLaunch = plan.layoutId to config.seed
    }

    /** The layout and seed of the last run launched (client side): ADR writes them into a recording of the run. */
    @Volatile var lastLaunch: Pair<String, Long>? = null

    /**
     * Server thread, with [takePending]: the pack set the plan was made from, still pinned for the caller, who unpins it once the run holds its own pin
     * (or the start failed). Null when the launch had none (the stub run, a dev command).
     */
    @Synchronized fun takePendingPacks(): PackSet? = pendingPacks.also { pendingPacks = null }

    @Synchronized private fun dropPendingPacks() { pendingPacks?.unpin(); pendingPacks = null }

    /** Engine, server thread, when the sim server has started: the config to start, once; null when the world was opened for the boss practice. */
    fun takePending(): Pair<RunConfig, DungeonPlan>? {
        val c = pending; val p = pendingPlan
        pending = null; pendingPlan = null
        // The world is up and uses the regions now; they are no longer this launch's to take back.
        dungeonRegion = null
        return if (c != null && p != null) c to p else null
    }

    /** Forgets a pending launch (the world failed to open, or the player went back to the title screen). */
    @Synchronized fun cancel() { pending = null; pendingPlan = null; dropPendingPacks() }
}
