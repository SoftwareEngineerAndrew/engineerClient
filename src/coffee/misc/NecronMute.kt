package com.coffeeclient.misc

/**
 * When [RandomStuff]'s Mute Sounds In P4 may mute: Necron's fight, read from chat. It opens on his first line
 * (F7 always "You went further...", M7 sometimes "Finally, I heard...") and closes on anything that ends his
 * fight: his old death line, the Wither King's first line (M7, the relics placed), the run's end (EXTRA STATS),
 * or a new world. RandomStuff only mutes while this is open AND you're in F7/M7 boss at P4's height, so a stray
 * line or a missed close can't mute anything outside P4. Pure: chat lines without colour codes.
 */
class NecronMute {
    @Volatile var open = false
        private set

    fun chat(line: String) {
        val t = line.trim()
        when {
            t in START_LINES -> open = true
            t == DEATH_LINE || t.startsWith(WITHER_KING) || t == EXTRA_STATS -> open = false
        }
    }

    fun reset() { open = false }

    companion object {
        val START_LINES = setOf(
            "[BOSS] Necron: You went further than any human before, congratulations.",
            "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
        )
        const val DEATH_LINE = "[BOSS] Necron: All this, for nothing..."
        const val WITHER_KING = "[BOSS] Wither King: "
        const val EXTRA_STATS = "> EXTRA STATS <"
    }
}
