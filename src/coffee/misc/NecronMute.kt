package com.coffeeclient.misc

/**
 * When [RandomStuff]'s Mute Sounds In P4 mutes, by chat: from Goldor dying ("[BOSS] Goldor: ....") or Necron's
 * first line, whichever comes, until his death line or the run's end (EXTRA STATS), then [over] until a new
 * world. RandomStuff mutes while this is open, or at P4's height while not [over], and only in F7's boss. Pure:
 * chat lines without colour codes.
 */
class NecronMute {
    @Volatile var open = false
        private set
    /** His fight is over (his death line or the run's end): nothing mutes again until a new world. */
    @Volatile var over = false
        private set

    fun chat(line: String) {
        val t = line.trim()
        when {
            t == DEATH_LINE || t == EXTRA_STATS -> { open = false; over = true }
            t == GOLDOR_DEAD || t.startsWith(NECRON) -> if (!over) open = true
        }
    }

    fun reset() { open = false; over = false }

    companion object {
        const val GOLDOR_DEAD = "[BOSS] Goldor: ...."
        const val NECRON = "[BOSS] Necron: "
        const val DEATH_LINE = "[BOSS] Necron: All this, for nothing..."
        const val EXTRA_STATS = "> EXTRA STATS <"
    }
}
