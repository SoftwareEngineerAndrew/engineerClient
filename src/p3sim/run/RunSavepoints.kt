package com.engineerclient.p3sim.run

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.slf4j.LoggerFactory

/**
 * What a savepoint needs from the live world, kept behind an interface so the order of a restore is unit-tested without
 * Minecraft ([MinecraftSavepointWorld] is the real one).
 */
interface SavepointWorld {
    /** The human players' positions and gear, as JSON. */
    fun capturePlayers(): JsonArray
    /** Puts the humans back where and how [data] has them. */
    fun restorePlayers(data: JsonArray)
    /** Every [WorldRegion] back to its start state; returns the blocks fixed. */
    fun resyncRegions(): Int
    /** Removes every entity the sim spawned (mobs, stands, bots): the modules re-create theirs in `restore`. */
    fun clearEntities()

    object None : SavepointWorld {
        override fun capturePlayers() = JsonArray()
        override fun restorePlayers(data: JsonArray) {}
        override fun resyncRegions() = 0
        override fun clearEntities() {}
    }
}

/**
 * The run's savepoints, in memory for the length of the run. A savepoint is the [RunState] snapshot, one entry per module
 * ([RunSystem.snapshot]), and the players' positions and gear (docs/ads/ARCHITECTURE.md 7). Restoring does exactly this, in this order:
 *
 *  1. the stage goes back (`forceStage`) and the run clock is put back (`restoreClock`; the absolute server tick keeps counting);
 *  2. [RunState.restore];
 *  3. every [WorldRegion] is resynced to its start state, and every sim entity is removed;
 *  4. every module's `restore(entry)` runs in module order and re-applies what it changed and re-creates what it spawned;
 *  5. the players are put back;
 *  6. `SavepointRestored` is posted.
 *
 * Only a run that is still in the dungeon (COUNTDOWN, CLEAR, BLOOD) can be restored, and only to a savepoint taken there: the boss has its
 * own restart. Old savepoints are dropped from the middle when there are more than [maxKept]; the first ("Start") is never dropped.
 */
class RunSavepoints(private val world: SavepointWorld = SavepointWorld.None, private val maxKept: Int = 120) : SavepointStore {
    private val log = LoggerFactory.getLogger("engineerclient")
    private val points = ArrayList<Savepoint>()
    private var counter = 0

    /** True while a restore is running: nothing may take a savepoint (the state is half back). */
    var restoring = false
        private set

    override fun take(run: Run, label: String, roomIndex: Int?): Savepoint? {
        if (restoring || run.isOver || !run.stage.inDungeon) return null
        // A savepoint taken while a person is a ghost would bring them back alive in a ghost's inventory: wait for the revive (the next room takes one).
        if (run.state.players.values.any { it.ghost && !it.isBot }) return null
        val modules = JsonObject()
        for (s in run.systemList) {
            val snap = try { s.snapshot(run) } catch (t: Throwable) { log.error("[ads] ${s.id}.snapshot failed", t); null }
            if (snap != null) modules.add(s.id, snap)
        }
        val data = JsonObject().also {
            it.add("state", run.state.snapshot())
            it.add("modules", modules)
            it.add("players", world.capturePlayers())
            it.addProperty("serverTick", run.serverTick)
            it.addProperty("t0Known", run.t0Tick != null)
        }
        val sp = Savepoint("sp${++counter}", label, run.t, run.stage, roomIndex, data)
        points += sp
        if (points.size > maxKept) points.removeAt(1)
        run.bus.post(SavepointTaken(run.t, sp.id))
        return sp
    }

    override fun list(): List<Savepoint> = points.toList()

    override fun restore(run: Run, id: String): Boolean {
        val sp = points.firstOrNull { it.id == id } ?: return false
        if (restoring || run.isOver || !run.stage.inDungeon || !sp.stage.inDungeon) {
            log.warn("[ads] savepoint ${sp.id} (${sp.label}) cannot be restored in stage ${run.stage}")
            return false
        }
        restoring = true
        try {
            if (run.stage != sp.stage) run.forceStage(sp.stage)
            run.restoreClock(sp.t, sp.data.get("t0Known")?.asBoolean ?: true)
            sp.data.getAsJsonObject("state")?.let { run.state.restore(it) }
            val fixed = try { world.resyncRegions() } catch (t: Throwable) { log.error("[ads] region resync failed", t); 0 }
            try { world.clearEntities() } catch (t: Throwable) { log.error("[ads] clearing entities failed", t) }
            val modules = sp.data.getAsJsonObject("modules")
            for (s in run.systemList) {
                val entry = modules?.get(s.id)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                try { s.restore(run, entry) } catch (t: Throwable) { log.error("[ads] ${s.id}.restore failed", t) }
            }
            sp.data.getAsJsonArray("players")?.let { try { world.restorePlayers(it) } catch (t: Throwable) { log.error("[ads] restoring players failed", t) } }
            log.info("[ads] restored savepoint ${sp.id} \"${sp.label}\" at t=${sp.t} ($fixed blocks resynced)")
        } finally {
            restoring = false
        }
        run.bus.post(SavepointRestored(run.t, sp.id))
        return true
    }
}
