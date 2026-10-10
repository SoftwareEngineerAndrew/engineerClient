package com.engineerclient.splits

import java.util.Locale

/**
 * The Goldor sub split: from "The Core entrance is opening!" (after S4), each teammate's leap into
 * the core and Goldor starting to move, in server ticks from that line.
 *
 * Since Hypixel's boss update Goldor doesn't wait for everyone: in 17 recorded F7 runs he set off
 * 3-5 ticks after the line in most, while the last player came in 11-159 ticks after it, so the
 * two are timed apart. Kept free of Minecraft (the module feeds it), so it tests headlessly.
 */
class GoldorCore {
    /** The server tick the core opened on; null before it (or after a reset). */
    var openTick: Int? = null; private set
    /** Who came in, in order, with the ticks after the core opened. */
    private val inside = LinkedHashMap<String, Int>()
    var goldorMoved: Int? = null; private set
    /** The server tick it stops showing on (Necron's first line + [LINGER]). */
    private var hideAt: Int? = null

    val watching get() = openTick != null && hideAt == null

    fun reset() { openTick = null; inside.clear(); goldorMoved = null; hideAt = null }

    fun onCoreOpen(tick: Int) { reset(); openTick = tick }

    /** [name] seen inside the core box on [tick]; only the first time counts. */
    fun onInside(name: String, tick: Int) {
        val open = openTick ?: return
        if (hideAt == null && name !in inside) inside[name] = tick - open
    }

    fun onGoldorMoved(tick: Int) {
        val open = openTick ?: return
        if (goldorMoved == null) goldorMoved = tick - open
    }

    fun onNecron(tick: Int) { if (openTick != null && hideAt == null) hideAt = tick + LINGER }

    /** The HUD's lines at [now] (server ticks): [me] in white, the last one in, in gold, once everyone is. */
    fun lines(now: Int, me: String?, everyone: Int): List<String> {
        if (openTick == null || hideAt?.let { now >= it } == true) return emptyList()
        val out = mutableListOf("§eCore")
        val done = everyone > 0 && inside.size >= everyone
        val last = inside.keys.lastOrNull()
        for ((name, t) in inside) {
            val colour = if (done && name == last) "§6" else "§a"
            out += "${if (name == me) "§f" else "§7"}$name $colour${secs(t)}"
        }
        out += "§eGoldor moved " + (goldorMoved?.let { "§f" + secs(it) } ?: "§8...")
        return out
    }

    companion object {
        const val LINGER = 200
        fun secs(ticks: Int): String = String.format(Locale.ROOT, "%.2fs", ticks / 20.0)
    }
}
