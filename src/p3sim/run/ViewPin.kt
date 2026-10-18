package com.engineerclient.p3sim.run

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.SimServer

/**
 * View and simulation distance pinned to [DISTANCE] chunks for the length of a run (ARCHITECTURE 2). Hypixel holds a fixed square of 15 x 15 chunks round
 * the player (211 to 225 of them in steady state, 112 blocks out; measured over 97 sessions), whatever the client's own render distance is. The game
 * tracks chunks within `max(0, |dx| - 2)^2 + max(0, |dz| - 2)^2 < d^2`, so d = 6 is the value that gives the same 15 x 15 with its corners cut
 * (213 chunks); 5 gave 13 x 13 (157), 7 gives 17 x 17 (265). The simulation distance follows, which also keeps the mobs the server ticks near what
 * the measurements assume.
 *
 * The pin lasts the whole run, the boss included: Hypixel's window never changes size at the jump to the arena, it only re-centres. The boss sim's
 * far-apart parts keep ticking through forced chunks over the arena ([EngineModule]), not through a bigger distance.
 *
 * The integrated server copies the client's render and simulation distance options into the player list every tick, so a plain
 * server-side setting would be undone at once. The pin therefore writes the two options themselves (in memory only: the options file is not
 * saved here) and remembers the player's values; [release] puts them back when the run ends, the world is left, or the game starts a boss
 * practice. If the game is closed with a pin on and the options screen was used meanwhile, options.txt keeps 6 and 6 until changed.
 */
object ViewPin {
    /** The pinned distance, in chunks: the window is 15 chunks wide (112 blocks out from the player's chunk). */
    const val DISTANCE = 6

    /**
     * Hypixel sends the chunks of its window at exactly this many a server tick, nearest to the player first (371 of 379 ticks in one session, 492 of 504 in
     * another; the first 225 take 45 ticks). The integrated server's own connection to its owner is not paced at all (it sends every ready chunk in the same tick),
     * so [com.engineerclient.mixin.AdsEngineChunkPaceMixin] lets a run's sender work like a remote one at this rate.
     */
    const val CHUNKS_PER_TICK = 5.0f

    /** Whether the host's chunks are sent at [CHUNKS_PER_TICK]: while a full run is starting or on (the boss practice's world loads as it always did). Server thread. */
    @JvmStatic fun pacingChunks(): Boolean = SimServer.server != null && (RunLauncher.pending != null || Runs.current != null)

    private var savedRender: Int? = null
    private var savedSim: Int? = null

    /** The distances the player had before the pin, or null when nothing is pinned. */
    val saved: Pair<Int, Int>? get() = savedRender?.let { r -> savedSim?.let { s -> r to s } }

    /** Pins both distances. Any thread; the option write runs on the client thread, the server's own values are set at once when called on its thread. */
    fun pin() {
        mc.execute {
            EngineerClient.safely("ads view pin") {
                if (savedRender == null) {
                    savedRender = mc.options.renderDistance().get(); savedSim = mc.options.simulationDistance().get()
                }
                mc.options.renderDistance().set(DISTANCE); mc.options.simulationDistance().set(DISTANCE)
            }
        }
        SimServer.server?.let { s ->
            if (s.isSameThread) { s.playerList.setViewDistance(DISTANCE); s.playerList.setSimulationDistance(DISTANCE) }
        }
    }

    /** Puts the player's own distances back (a no-op when nothing is pinned). */
    fun release() {
        mc.execute {
            EngineerClient.safely("ads view release") {
                val r = savedRender; val s = savedSim
                savedRender = null; savedSim = null
                if (r != null) mc.options.renderDistance().set(r)
                if (s != null) mc.options.simulationDistance().set(s)
            }
        }
    }
}
