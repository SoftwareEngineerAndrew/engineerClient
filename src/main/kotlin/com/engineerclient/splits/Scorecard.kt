package com.engineerclient.splits

import java.util.Locale

/**
 * Scorecard splits: the whole run on one table. Each row is one of the normal splits — its total
 * first, then the sub splits that make it up — each on whichever clock tells you more about it:
 * real time where lag is part of the story (the clear, terminals), the server's ticks where only the
 * fight is (the bosses). Seconds to one decimal, no "s".
 *
 * Nothing here touches Minecraft: the module feeds it the moments the game shows and asks for rows.
 * A row is tab-separated cells, the first being the split's total, for the HUD to lay out as a
 * table. A cell that is not known is left empty so the columns after it stay in line.
 *
 * Each sub split is the time since the one before it (the first, since the split started), except
 * where noted.
 */
class Scorecard {

    // Portal: the nether portal appearing in the blood room, 3-6 s after the Watcher lets you go.
    private var portalOpen: Stamp? = null

    // Maxor: every "Energy Crystals are now active!" line (two a round), each stun, and his wither
    // being removed — the kill, 21-35 ticks before Storm speaks in every recorded run.
    private val crystals = mutableListOf<Stamp>()
    private val maxorStuns = mutableListOf<Stamp>()
    private var maxorGone: Stamp? = null

    // Storm: his first lightning (he starts moving), each crush, each time he breaks free of one,
    // and his death.
    private var stormMoving: Stamp? = null
    private val crushes = mutableListOf<Stamp>()
    private val freed = mutableListOf<Stamp>()
    private var stormDead: Stamp? = null

    // Goldor: everyone in the core, the first hit on him, his death ("...." — the "Necron, forgive
    // me." that follows 81-83 ticks later in every run is the end of his death animation).
    private var allIn: Stamp? = null
    private var goldorHit: Stamp? = null
    private var goldorDead: Stamp? = null

    // Necron: leaving mid once his opening animation is over (148-223 ticks in), and first back on
    // it after the first DPS knocks him off.
    private var necronFree: Stamp? = null
    private var necronMid: Stamp? = null

    /** What each moment was read from, for the debug line. */
    var onEvent: (String) -> Unit = {}

    fun reset() {
        portalOpen = null; crystals.clear(); maxorStuns.clear(); maxorGone = null
        stormMoving = null; crushes.clear(); freed.clear(); stormDead = null
        allIn = null; goldorHit = null; goldorDead = null
        necronFree = null; necronMid = null
    }

    fun onChat(msg: String, at: Stamp) {
        when {
            CRYSTAL_ACTIVE.matches(msg) -> { crystals += at; note("crystal active ${crystals.size}") }
            msg in MAXOR_STUNNED && crystals.size >= 2 -> { maxorStuns += at; note("Maxor stunned ${maxorStuns.size}") }
            msg in LIGHTNING && stormMoving == null -> { stormMoving = at; note("Storm moving (lightning)") }
            msg in STORM_CRUSHED -> { crushes += at; note("Storm crushed ${crushes.size}") }
            msg in STORM_FREE && crushes.size > freed.size -> stormFree(at, "chat")
            msg == STORM_DEAD -> { stormDead = at; note("Storm dead") }
            msg == GOLDOR_DEAD -> { goldorDead = at; note("Goldor dead") }
        }
    }

    /** The first nether portal block of the run: the portal out of the blood room has opened. */
    fun onPortal(at: Stamp) { if (portalOpen == null) { portalOpen = at; note("portal open") } }

    /** Storm moved away from where a crush pinned him: that DPS window is over. */
    fun onStormMoved(at: Stamp) { if (crushes.size > freed.size) stormFree(at, "Storm moved") }

    /** Whether Storm is pinned by a crush right now, so the module knows to watch him. */
    val stormPinned: Boolean get() = crushes.size > freed.size && stormDead == null

    fun onEveryoneInCore(at: Stamp, how: String) { if (allIn == null) { allIn = at; note("everyone in core ($how)") } }

    /** A wither removed while Maxor is up; the last before Storm is Maxor dying. */
    fun onMaxorGone(at: Stamp) { maxorGone = at; note("a wither went (Maxor dead?)") }

    /** Necron off mid after his animation, then first back on it. */
    fun onNecronOffMid(at: Stamp) { if (necronFree == null) { necronFree = at; note("Necron left mid") } }
    fun onNecronBackAtMid(at: Stamp) { if (necronFree != null && necronMid == null) { necronMid = at; note("Necron back at mid") } }

    /** Which of Necron's moves the module should watch for next, if any. */
    val necronWatch: Boolean get() = necronMid == null
    val necronOff: Boolean get() = necronFree != null

    /** Whether everyone-in is still to be found. */
    val waitingForCore: Boolean get() = allIn == null

    /** The first hit on Goldor, and what showed it (a damage packet, the wither's hurt sound). */
    fun onGoldorHit(at: Stamp, how: String) {
        if (goldorHit != null || goldorDead != null) return
        goldorHit = at
        note("first hit on Goldor ($how)")
    }

    private fun stormFree(at: Stamp, how: String) {
        freed += at
        note("Storm free of crush ${freed.size} ($how)")
    }

    private fun note(what: String) = onEvent(what)

    /**
     * The table's rows, one per split that has started. [bloodRooms] is each finished blood rush
     * room's total in ticks and [rushOver] whether the blood door is open; [terms] the four
     * terminal sections.
     */
    fun rows(splits: List<Split>, now: Stamp, bloodRooms: List<Long>, rushOver: Boolean, terms: List<Split>): List<String> {
        val out = mutableListOf<String>()
        for (s in splits) {
            val end = s.stop ?: now
            val cells = mutableListOf<String>()
            when (s.label) {
                SplitTracker.OPEN -> {
                    cells += colour(s.label) + real(s.start, end)
                    // Each room on the server's clock, then their average in gold once the rush is done.
                    for (t in bloodRooms) cells += "§c" + fmt(t * 50)
                    if (rushOver && bloodRooms.isNotEmpty()) cells += "§6" + fmt(bloodRooms.sum() * 50 / bloodRooms.size)
                }
                SplitTracker.BLOOD -> cells += colour(s.label) + real(s.start, end)
                SplitTracker.PORTAL -> {
                    cells += colour(s.label) + real(s.start, end)
                    val open = portalOpen?.takeIf { it.realMs >= s.start.realMs }
                    cells += "§5" + real(s.start, open)
                    // From the portal opening to the boss: everyone is warped in on the same tick.
                    if (s.stop != null) cells += "§6" + real(open, s.stop)
                }
                SplitTracker.MAXOR -> {
                    cells += colour(s.label) + tick(s.start, end)
                    val placed1 = crystals.getOrNull(1)
                    val stun1 = maxorStuns.firstOrNull()
                    val placed2 = crystals.getOrNull(3)
                    cells += "§3" + tick(s.start, placed1)
                    cells += "§6" + tick(placed1, stun1)
                    cells += "§3" + tick(stun1, placed2)
                    // From Maxor starting, not the one before: how long he took all told.
                    if (s.stop != null) cells += "§c" + tick(s.start, maxorGone?.takeIf { it.realMs <= s.stop.realMs })
                }
                SplitTracker.STORM -> {
                    cells += colour(s.label) + tick(s.start, end)
                    val c1 = crushes.getOrNull(0); val c2 = crushes.getOrNull(1)
                    val f1 = freed.getOrNull(0)
                    cells += "§6" + tick(stormMoving, c1)
                    cells += "§c" + tick(c1, f1)
                    cells += "§6" + tick(f1, c2)
                    // The last one runs to his death; a third crush on the way turns it dark red.
                    cells += (if (crushes.size > 2) "§4" else "§c") + tick(c2, stormDead)
                }
                SplitTracker.TERMS -> {
                    cells += colour(s.label) + real(s.start, end)
                    for (t in terms) if (t.stop != null) cells += "§8" + real(t.start, t.stop)
                }
                SplitTracker.GOLDOR -> {
                    cells += colour(s.label) + tick(s.start, end)
                    // Anyone already in when the core opens counts from the opening.
                    val inAt = allIn?.let { if (it.realMs < s.start.realMs) s.start else it }
                    cells += "§5" + tick(s.start, inAt)
                    cells += "§3" + tick(inAt, goldorHit)
                    cells += "§c" + tick(goldorHit, goldorDead)
                }
                SplitTracker.NECRON -> {
                    cells += colour(s.label) + tick(s.start, end)
                    cells += "§a" + tick(necronFree, necronMid)
                }
                else -> continue
            }
            out += trimEmpty(cells)
        }
        return out
    }

    /** The cells as one row, empty trailing cells dropped (an empty cell is a colour code alone). */
    private fun trimEmpty(cells: List<String>): String {
        val kept = cells.toMutableList()
        while (kept.size > 1 && kept.last().length <= 2) kept.removeAt(kept.lastIndex)
        return kept.joinToString("\t") { if (it.length <= 2) "" else it }
    }

    private fun colour(label: String) = label.take(2).replace('&', '§')

    /** Seconds to one decimal on the real clock, or "" if either end is not known yet. */
    private fun real(from: Stamp?, to: Stamp?) = if (from == null || to == null) "" else fmt(to.realMs - from.realMs)

    /** The same on the server's clock. */
    private fun tick(from: Stamp?, to: Stamp?) = if (from == null || to == null) "" else fmt((to.tick - from.tick) * 50L)

    private fun fmt(ms: Long) = String.format(Locale.ROOT, "%.1f", ms.coerceAtLeast(0) / 1000.0)

    private companion object {
        val CRYSTAL_ACTIVE = Regex("""^\d+/\d+ Energy Crystals are now active!$""")
        val MAXOR_STUNNED = setOf("[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!", "[BOSS] Maxor: YOU TRICKED ME!")
        val LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")
        val STORM_CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")
        /** Said when he breaks free of a crush without dying — only sometimes; his moving is the rest. */
        val STORM_FREE = setOf("[BOSS] Storm: Slowing me down will be your greatest accomplishment!")
        const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
        const val GOLDOR_DEAD = "[BOSS] Goldor: ...."
    }
}
