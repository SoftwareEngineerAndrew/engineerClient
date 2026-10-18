package com.engineerclient.p3sim.clear.ui

import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.pack.PackSet
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.run.LayoutPick
import com.engineerclient.p3sim.run.LayoutSource
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.RunLauncher

/**
 * The client-side front door for starting a run, shared by the start screen and the `/ads run random|seed|layout` commands:
 * make sure the data packs are there (a clear message with the folder if not), turn a hand-typed layout id into a real one, warn
 * about a layout that may not play well, then hand over to [RunLauncher.launch]. Client thread only.
 */
object RunStarter {
    /** Starts a run with [config]. Every problem is a plain sentence through [reply]; nothing throws. */
    fun start(config: RunConfig, reply: (String) -> Unit) {
        if (P3Sim.inSim && !P3Sim.localSim) { reply("Only the host of the party can start a run."); return }
        withPacks(reply) { set -> launch(config, set, reply) }
    }

    /**
     * Runs [then] on the client thread with packs that can build a dungeon. Packs are loaded once and kept; a set that could not build
     * one is loaded again first (a developer may just have put an override in the folder). If they still cannot, [reply] gets
     * [PackSet.userLines] and [then] does not run.
     */
    fun withPacks(reply: (String) -> Unit, then: (PackSet) -> Unit) {
        val have = Packs.current
        if (have != null && have.canBuildDungeon) { then(have); return }
        Packs.loadAsync().whenComplete { set, err ->
            mc.execute {
                if (err != null || set == null) { reply("ADS could not read its data packs: ${err?.message ?: "unknown error"}."); return@execute }
                if (!set.canBuildDungeon) { set.userLines().forEach(reply); return@execute }
                then(set)
            }
        }
    }

    private fun launch(config0: RunConfig, set: PackSet, reply: (String) -> Unit) {
        val layouts = set.layouts ?: return
        var config = config0
        val src = config.layout
        if (src is LayoutSource.Recorded) {
            when (val r = LayoutBrowserModel.resolve(layouts.layouts.map { it.id }, src.id)) {
                is LayoutBrowserModel.Resolved.One -> config = config.copy(layout = LayoutSource.Recorded(r.id))
                is LayoutBrowserModel.Resolved.Many -> {
                    reply("\"${src.id}\" matches ${r.ids.size} layouts, for example ${r.ids.take(3).joinToString(", ")}. Type more of the id.")
                    return
                }
                LayoutBrowserModel.Resolved.Nothing -> { reply("There is no recorded layout called ${src.id}. The start screen's layout browser lists them."); return }
            }
        }
        when (val picked = LayoutPick.pick(config, layouts)) {
            is LayoutPick.Result.Failed -> { reply(picked.message); return }
            is LayoutPick.Result.Ok -> picked.picked.warning?.let(reply)
        }
        reply(RunArgs.describe(config))
        RunLauncher.launch(config, reply)
    }
}
