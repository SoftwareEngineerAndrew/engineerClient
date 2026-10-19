package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.layouts.Layout
import com.engineerclient.p3sim.pack.layouts.LayoutPack

/**
 * Turns the player's choice ([RunConfig.layout]) into a recorded layout, the same way for everyone who needs it: the layout module's
 * `plan`, the run UI's browser and the dev commands (FORMAT-layouts 11.2). Pure.
 *
 *  - [LayoutSource.Random]: the default pool (`playable`, not a duplicate, the config's floor), picked by the RUN seed, so a run is repeated by
 *    its seed alone. The seed is shown to the player at the start.
 *  - [LayoutSource.Seed]: the same, picked by that seed instead.
 *  - [LayoutSource.Recorded]: that run's layout by id. It bypasses the pool filter; one that is not `playable` still builds only if it can,
 *    and the player is warned.
 */
object LayoutPick {
    /** A layout was chosen. [seed] is the one that picked it (null for a recorded id); [warning] is a plain sentence to show, or null. */
    class Picked(val layout: Layout, val seed: Long?, val warning: String?)

    sealed interface Result {
        class Ok(val picked: Picked) : Result
        class Failed(val message: String) : Result
    }

    fun pick(config: RunConfig, pack: LayoutPack): Result {
        val floor = config.floor
        val inPool: (Layout) -> Boolean = { it.playable == true && it.dupOf == null && it.floor == floor }
        return when (val src = config.layout) {
            LayoutSource.Random -> byPool(pack, config.seed, inPool, floor)
            is LayoutSource.Seed -> byPool(pack, src.seed, inPool, floor)
            is LayoutSource.Recorded -> {
                val l = pack.layout(src.id) ?: return Result.Failed("There is no recorded layout called ${src.id} in the layout pool.")
                val warning = when {
                    l.floor != floor -> "That layout is from a ${l.floor} run, not $floor; it is built as recorded."
                    l.playable != true -> "That layout was not recorded completely, so it may not be buildable or may play oddly."
                    l.dupOf != null -> "That layout repeats ${l.dupOf}."
                    else -> null
                }
                Result.Ok(Picked(l, null, warning))
            }
            // A practice room is planned by PracticePlan from every layout that has it, not picked as one layout.
            is LayoutSource.Practice -> Result.Failed("Practice builds one room, not a layout.")
        }
    }

    private fun byPool(pack: LayoutPack, seed: Long, filter: (Layout) -> Boolean, floor: String): Result {
        val l = pack.pick(seed, filter) ?: return Result.Failed("The layout pool has no playable $floor layouts.")
        return Result.Ok(Picked(l, seed, null))
    }
}
