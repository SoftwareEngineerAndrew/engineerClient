package com.engineerclient

import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Odin API that differs between the Odin versions this mod runs on. This build compiles against 0.3.7, but ADM players
 * also run Odin 0.4.0, where a call compiled for 0.3.7 throws NoSuchMethodError.
 */
object OdinCompat {

    /**
     * Odin 0.4.0 made `batKilled` a Boolean and moved the count to `numBatsKilled`; up to 0.3.7 `batKilled` is the count.
     * Looked up once, on DungeonStats (DungeonUtils' getters only forward to it).
     */
    private val batCount: MethodHandle by lazy {
        val lookup = MethodHandles.publicLookup()
        val stats = DungeonListener.DungeonStats::class.java
        runCatching { lookup.findVirtual(stats, "getNumBatsKilled", MethodType.methodType(Int::class.javaPrimitiveType)) }
            .getOrElse { lookup.findVirtual(stats, "getBatKilled", MethodType.methodType(Int::class.javaPrimitiveType)) }
    }

    /** Bats killed so far this run, on any Odin version. */
    fun batsKilled(stats: DungeonListener.DungeonStats = DungeonListener.dungeonStats): Int = batCount.invoke(stats) as Int
}
