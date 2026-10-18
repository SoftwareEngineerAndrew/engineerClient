package com.engineerclient.p3sim.run

/**
 * Keeps a module that throws on every tick from filling `latest.log`: the first failure of each kind is reported in full, the next ones are
 * held back and counted, and the count comes with the next full report once [everyMillis] have passed. Server thread only.
 */
class FailureLog(private val everyMillis: Long = 30_000, private val clock: () -> Long = System::currentTimeMillis) {
    private class Seen(var lastReported: Long, var held: Int)

    private val seen = HashMap<String, Seen>()

    /**
     * Whether a failure of [key] should be reported now: null to stay quiet, else how many of this kind were held back since the last
     * report (0 for the first).
     */
    fun admit(key: String): Int? {
        val now = clock()
        val s = seen[key]
        if (s == null) { seen[key] = Seen(now, 0); return 0 }
        if (now - s.lastReported < everyMillis) { s.held++; return null }
        val held = s.held
        s.lastReported = now; s.held = 0
        return held
    }

    /** `" (N more since the last report)"` for a count above zero, else empty. */
    fun suffix(held: Int): String = if (held > 0) " ($held more since the last report)" else ""
}
