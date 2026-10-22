package com.engineerclient.ads

import com.engineerclient.p3sim.P3Sim
import net.rustclear.mod.engine.Engine

/**
 * ADS's own worlds, client side: the P3 Sim's ([P3Sim.inSim]: your own or a party's you joined) and Rustclear's
 * dungeon worlds ([Engine.isEngineClient]: hosting one, or a guest in another player's). What runs only there (ADS's
 * splits) checks this; anywhere else, Hypixel included, it is false.
 */
object AdsWorlds {
    @JvmStatic
    val active: Boolean get() = P3Sim.inSim || Engine.isEngineClient()

    /** In a Rustclear world (not the sim): its runs start with Hypixel's "Starting in 1 second." */
    val engine: Boolean get() = !P3Sim.inSim && Engine.isEngineClient()
}
