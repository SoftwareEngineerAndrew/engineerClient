package com.engineerclient.splits

/**
 * What happens inside the Watcher, Portal and boss splits beyond the 25 named boss steps — only
 * the moments that were asked for, each filed under the split it actually happens in.
 *
 * Checked against the 32 recorded F7 runs:
 *  - The energy crystals are Maxor's: two spawn on the upper platforms (y 238) when he starts,
 *    get picked up ("X picked up an Energy Crystal!"), and reappear placed on the lower ones
 *    (y 224). Chat's "1/2 Energy Crystals are now active!" says 1/2 for both, so the placed
 *    crystal appearing is what counts, and whoever stands nearest it placed it.
 *  - Goldor dies on "[BOSS] Goldor: ....". "Necron, forgive me.", 81-83 ticks later, ends his
 *    death animation.
 *  - Simon Says presses are its buttons turning powered; the nearest player pressed them.
 *
 * Not possible: who hit Goldor or Necron (hits arrive with no attacker), and Storm's crushers
 * (silent block movements with nobody attached). The hit *times* are here, uncredited.
 */
class BossDetail(private val detail: SplitDetail) {

    private val pickedAt = HashMap<String, Stamp>()
    private var necronHit = false

    fun reset() {
        pickedAt.clear(); necronHit = false
    }

    fun onChat(msg: String, at: Stamp) {
        when {
            msg == WATCHER_HANDLE -> detail.add(SplitTracker.BLOOD, at, "§chandle this", step = true)
            WATCHER_TAUNT.matches(msg) -> detail.add(SplitTracker.BLOOD, at, "§7" + msg.removePrefix(WATCHER).trimEnd('.'), step = true)
            msg == WATCHER_DONE -> detail.add(SplitTracker.PORTAL, at, "§dopen", step = true)
            msg == MAXOR_START -> detail.add(SplitTracker.PORTAL, at, "§dentered", step = true)
            msg in LIGHTNING -> detail.add(SplitTracker.STORM, at, "§elightning")
            msg == GOLDOR_DEAD -> detail.add(SplitTracker.GOLDOR, at, "§ckilled")
            else -> {
                SECTION_DONE.find(msg)?.let { m ->
                    val what = when (m.groupValues[2]) { "terminal" -> "§6term"; "lever" -> "§6lever"; else -> "§6device" }
                    detail.add(SplitTracker.TERMS, at, what + " " + m.groupValues[3] + "/" + m.groupValues[4], m.groupValues[1])
                    return
                }
                CRYSTAL_PICKUP.find(msg)?.let { m ->
                    pickedAt[m.groupValues[1]] = at
                    detail.add(SplitTracker.MAXOR, at, "§dcrystal picked up", m.groupValues[1])
                }
            }
        }
    }

    /** An end crystal appeared during Maxor: [placed] on a lower platform, otherwise a fresh spawn. */
    fun onCrystal(at: Stamp, placed: Boolean, placer: String?) {
        if (!placed) return detail.add(SplitTracker.MAXOR, at, "§dcrystal spawned")
        val who = placer.orEmpty()
        detail.add(SplitTracker.MAXOR, at, "§dcrystal placed", who)
        val picked = pickedAt.remove(who) ?: return
        detail.add(SplitTracker.MAXOR, at, "§dcrystal took §f" + SplitFormat.seconds(at.realMs - picked.realMs), who)
    }

    fun onSimonPress(at: Stamp, who: String?) = detail.add(SplitTracker.TERMS, at, "§ass button", who.orEmpty())

    /** A wither took a hit. Goldor's every hit while he is alive; Necron's only the first. */
    fun onBossHit(split: String, at: Stamp) {
        when (split) {
            SplitTracker.GOLDOR -> detail.add(split, at, "§ehit")
            SplitTracker.NECRON -> if (!necronHit) { necronHit = true; detail.add(split, at, "§cfirst hit") }
        }
    }

    /** A blood mob spawned by the Watcher, and later its death with how long it lived. */
    fun onMobSpawn(at: Stamp, name: String) = detail.add(SplitTracker.BLOOD, at, "§fspawn $name")

    fun onMobGone(at: Stamp, name: String, lifeMs: Long) =
        detail.add(SplitTracker.BLOOD, at, "§fkilled $name §7(" + SplitFormat.seconds(lifeMs) + ")")

    private companion object {
        const val WATCHER = "[BOSS] The Watcher: "
        const val WATCHER_HANDLE = "[BOSS] The Watcher: Let's see how you can handle this."
        const val WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass."
        const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
        const val GOLDOR_DEAD = "[BOSS] Goldor: ...."

        val LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")

        /** The Watcher's lines between waves. All ten appear in the recorded runs. */
        val WATCHER_TAUNT = Regex(
            "^\\[BOSS] The Watcher: (?:Not bad\\.|Aw, I liked that one\\.|You'll do\\.|" +
                "That one was weak anyway\\.|I'm impressed\\.|Go, fight!|Go and live again!|" +
                "Hmmm\\.\\.\\. this one!|Very nice\\.|This guy looks like a fighter\\.)$"
        )

        val SECTION_DONE = Regex("""^(\w+) (?:activated|completed) a (terminal|lever|device)! \((\d+)/(\d+)\)$""")
        val CRYSTAL_PICKUP = Regex("""^(\w+) picked up an Energy Crystal!$""")
    }
}
