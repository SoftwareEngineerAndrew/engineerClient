package com.engineerclient.p3sim.run.api

import com.engineerclient.p3sim.pack.PackSet
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.WorldRegion

/**
 * Builds the dungeon. Implemented by [com.engineerclient.p3sim.clear.layout.LayoutModule].
 *
 * The flow, owned by the engine (`Runs.start`): [plan] picks and resolves the layout (pure), the engine registers
 * [region] with [com.engineerclient.p3sim.run.WorldRegions] BEFORE the chunks generate, the world is created or the region is
 * stamped, and the run begins. The blocks are written during chunk generation (straight into sections, no block updates)
 * because Odin scans a room once, when its centre chunk loads.
 */
interface LayoutService {
    /**
     * Chooses the layout per [RunConfig.layout] (random, seed or recorded id; use [com.engineerclient.p3sim.run.LayoutPick.pick], the one place that
     * choice is made) from the layout pool and resolves designs
     * and placements. Pure; no world access. A failure carries a plain-English message (`rot` unresolved, room missing...).
     */
    fun plan(config: RunConfig, packs: PackSet): DungeonPlan.Result =
        DungeonPlan.Result.Failed(com.engineerclient.p3sim.run.PlanFailure("The layout builder is not installed yet."))

    /**
     * The world region holding the dungeon's blocks for [plan]: rooms pasted at their tiles and rotations, doors carved and
     * filled, roofs, block entities (skulls with textures). `fill` runs on the chunk generator's worker threads.
     * Null when the layout builder is not available.
     */
    fun region(plan: DungeonPlan, packs: PackSet): WorldRegion? = null
}
