package com.engineerclient.lcm

import com.engineerclient.dungeon.ClearInfo

/**
 * Clear Info's "Paul Score": whether Paul's +10 bonus counts. Odin (0.3.6+) goes by the mayor alone with no way to
 * force it; Auto keeps that, On / Off override it. `PaulScoreMixin` applies it at Odin's DungeonListener.getPaul,
 * which every Paul check goes through: Odin's score, its needed secrets and Map Info's score.
 */
object PaulScore {

    enum class Mode(private val label: String) {
        AUTO("Auto"), ON("On"), OFF("Off");
        override fun toString() = label
    }

    /** The setting's label (Auto / On / Off), for the recorder. */
    @JvmStatic
    fun label(): String = ClearInfo.paulScore.toString()

    private val paulField by lazy {
        runCatching {
            Class.forName("com.odtheking.odin.utils.skyblock.dungeon.DungeonListener").getDeclaredField("paul").apply { isAccessible = true }
        }.getOrNull()
    }

    /** Odin's own Paul (the mayor check), read from its field so the override is left out; null if it can't be read. */
    @JvmStatic
    fun real(): Boolean? = runCatching { paulField?.getBoolean(null) }.getOrNull()

    /** The forced Paul value, or null to leave Odin's mayor check. */
    @JvmStatic
    fun forced(): Boolean? = when (ClearInfo.paulScore) {
        Mode.ON -> true
        Mode.OFF -> false
        else -> null
    }
}
