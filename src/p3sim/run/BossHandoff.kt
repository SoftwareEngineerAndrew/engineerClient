package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.Fight

/**
 * The seam between a run and the boss sim: what [Run.beginBoss] does once the run is in the BOSS stage. The boss practice's phase chain
 * (P1 Maxor, P2 Storm, P3 Goldor, P4 Necron) runs unchanged; the run only watches it ([EngineModule] ends the run when Necron is dead).
 * The view and simulation distance pin ([ViewPin]) is not touched here.
 */
object BossHandoff {
    /**
     * Starts P1 from the run: Maxor's first line follows the jump by `options.firstLineDelayTicks` (recorded: jump + 2), and the dungeon
     * stays as it is. Installed as [Run.bossStarter] by [Runs.start].
     */
    fun start(run: Run, options: BossEntryOptions) {
        // The boss changes arena blocks from here on: a later resync of the arena has something to compare.
        ArenaRegion.markDirty()
        // The view and simulation distance stay pinned: Hypixel's chunk window is the same size before and after the jump (it only re-centres, and no
        // set_chunk_cache_radius or set_simulation_distance is sent), and the boss sim's parts far from every player keep ticking because the arena's
        // chunks are held loaded from the blood room on ([EngineModule.onStage]). [Runs.stop] gives the player's own distances back.
        val t0 = run.t0Tick
        if (options.firstLineDelayTicks > 0) Fight.later(options.firstLineDelayTicks, "run boss start") { Fight.startRunBoss(options, t0) }
        else Fight.startRunBoss(options, t0)
    }
}
