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
            msg == WATCHER_HANDLE -> detail.add(SplitTracker.BLOOD, at, "§chandle this", step = true, note = CHAT)
            WATCHER_TAUNT.matches(msg) -> detail.add(SplitTracker.BLOOD, at, "§7" + msg.removePrefix(WATCHER).trimEnd('.'), step = true, note = CHAT)
            msg == WATCHER_DONE -> detail.add(SplitTracker.PORTAL, at, "§dopen", step = true, note = CHAT)
            msg == MAXOR_START -> detail.add(SplitTracker.PORTAL, at, "§dentered", step = true, note = CHAT)
            msg in LIGHTNING -> detail.add(SplitTracker.STORM, at, "§elightning", note = CHAT)
            msg in STORM_FREE -> detail.add(SplitTracker.STORM, at, "§bbroke free", note = "chat - said once, whichever crush it was")
            msg == GOLDOR_DEAD -> detail.add(SplitTracker.GOLDOR, at, "§ckilled", note = CHAT)
            msg == GATE_DESTROYED -> detail.add(SplitTracker.TERMS, at, "§cgate destroyed", note = CHAT)
            CRYSTALS_ACTIVE.matches(msg) -> detail.add(SplitTracker.MAXOR, at, "§d" + msg.removeSuffix("!").lowercase(), note = "chat - says 1/2 for both crystals")
            else -> {
                SECTION_DONE.find(msg)?.let { m ->
                    val what = when (m.groupValues[2]) { "terminal" -> "§6term"; "lever" -> "§6lever"; else -> "§6device" }
                    detail.add(SplitTracker.TERMS, at, what + " " + m.groupValues[3] + "/" + m.groupValues[4], m.groupValues[1], note = CHAT)
                    return
                }
                CRYSTAL_PICKUP.find(msg)?.let { m ->
                    pickedAt[m.groupValues[1]] = at
                    detail.add(SplitTracker.MAXOR, at, "§dcrystal picked up", m.groupValues[1], note = CHAT)
                }
            }
        }
    }

    /** An end crystal appeared during Maxor: [placed] on a lower platform, otherwise a fresh spawn. */
    fun onCrystal(at: Stamp, placed: Boolean, placer: String?) {
        if (!placed) return
        val who = placer.orEmpty()
        detail.add(SplitTracker.MAXOR, at, "§dcrystal placed", who, note = "seen appear on a lower platform; placer = nearest player, a guess")
        val picked = pickedAt.remove(who) ?: return
        detail.add(SplitTracker.MAXOR, at, "§dcrystal took §f" + SplitFormat.seconds(at.realMs - picked.realMs), who, note = "their pickup line to the placed crystal")
    }

    fun onSimonPress(at: Stamp, who: String?) =
        detail.add(SplitTracker.TERMS, at, "§ass button", who.orEmpty(), note = "button seen powering; presser = nearest player, a guess")

    /** A wither took a hit. Goldor's every hit while he is alive; Necron's only the first. */
    fun onBossHit(split: String, at: Stamp) {
        when (split) {
            SplitTracker.GOLDOR -> detail.add(split, at, "§ehit", note = "damage packet - only while he is in view; no attacker sent")
            SplitTracker.NECRON -> if (!necronHit) { necronHit = true; detail.add(split, at, "§cfirst hit", note = "damage packet - only while he is in view") }
        }
    }

    /** A blood mob spawned by the Watcher, and later its death with how long it lived. */
    fun onMobSpawn(at: Stamp, name: String, distance: Double) =
        detail.add(SplitTracker.BLOOD, at, "§fspawn $name", note = "seen " + blocks(distance) + " away - ones out of view are missed")

    /**
     * A blood mob gone. Hypixel sends no death, only the mob leaving, and a mob also leaves when
     * it walks out of view - so one gone far away may not be dead.
     */
    fun onMobGone(at: Stamp, name: String, lifeMs: Long, distance: Double) =
        detail.add(SplitTracker.BLOOD, at, "§fkilled $name §7(" + SplitFormat.seconds(lifeMs) + ")",
            note = "gone " + blocks(distance) + " away" + if (distance > 32) " - maybe out of view, not killed" else "")

    /** A moment only Debug shows, found some other way than the steps: [note] says how. */
    fun extra(split: String, at: Stamp, label: String, note: String, who: String = "") = detail.add(split, at, label, who, note = note)

    companion object {
        const val CHAT = "chat"

        fun blocks(d: Double) = String.format(java.util.Locale.ROOT, "%.0f blocks", d)

        private const val WATCHER = "[BOSS] The Watcher: "
        private const val WATCHER_HANDLE = "[BOSS] The Watcher: Let's see how you can handle this."
        private const val WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass."
        private const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
        private const val GOLDOR_DEAD = "[BOSS] Goldor: ...."

        private val LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")

        /** The Watcher's lines between waves. All ten appear in the recorded runs. */
        private val WATCHER_TAUNT = Regex(
            "^\\[BOSS] The Watcher: (?:Not bad\\.|Aw, I liked that one\\.|You'll do\\.|" +
                "That one was weak anyway\\.|I'm impressed\\.|Go, fight!|Go and live again!|" +
                "Hmmm\\.\\.\\. this one!|Very nice\\.|This guy looks like a fighter\\.)$"
        )

        private val SECTION_DONE = Regex("""^(\w+) (?:activated|completed) a (terminal|lever|device)! \((\d+)/(\d+)\)$""")
        private val CRYSTAL_PICKUP = Regex("""^(\w+) picked up an Energy Crystal!$""")
        private val CRYSTALS_ACTIVE = Regex("""^\d+/\d+ Energy Crystals are now active!$""")
        private val STORM_FREE = setOf("[BOSS] Storm: Slowing me down will be your greatest accomplishment!")
        private const val GATE_DESTROYED = "The gate has been destroyed!"
    }
}
