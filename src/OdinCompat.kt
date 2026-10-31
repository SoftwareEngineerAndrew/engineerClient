package com.engineerclient

import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
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

    /**
     * DungeonUtils' score getters are inline in Odin, so calling them compiles their 0.3.7 body (with its int batKilled)
     * into this mod. Called through their real getters instead, they run the installed Odin's own body.
     */
    private fun dungeonUtilsInt(getter: String): MethodHandle =
        MethodHandles.publicLookup().findVirtual(DungeonUtils::class.java, getter, MethodType.methodType(Int::class.javaPrimitiveType))
            .bindTo(DungeonUtils)

    private val neededSecretsHandle by lazy { dungeonUtilsInt("getNeededSecretsAmount") }
    private val bonusScoreHandle by lazy { dungeonUtilsInt("getGetBonusScore") }
    private val scoreHandle by lazy { dungeonUtilsInt("getScore") }

    /** DungeonUtils.neededSecretsAmount, on any Odin version. */
    fun neededSecretsAmount(): Int = neededSecretsHandle.invoke() as Int

    /** DungeonUtils.getBonusScore, on any Odin version. */
    fun bonusScore(): Int = bonusScoreHandle.invoke() as Int

    /** DungeonUtils.score, on any Odin version. */
    fun score(): Int = scoreHandle.invoke() as Int
}
