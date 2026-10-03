package com.engineerclient.splits

import com.odtheking.odin.utils.skyblock.SplitsGroup
import com.odtheking.odin.utils.skyblock.SplitsManager

/**
 * The P3 Sim's run in Odin's Splits. The sim starts partway (P1, P2, P3, a section, the core, P4),
 * so the lines before it (Mort, the Watcher, ...) never come and Odin would show nothing. Once Odin
 * has started the run ("Starting in 1 second.") and the starting phase's split has its time, every
 * split before it is filled in backwards as your Pace target for it ([target]: the Engineer look's
 * F7 boxes, a blank one your Odin PB, neither the dark green time).
 *
 * Odin keeps each split as the moment (clock and its tick count) its line came; a split's length is
 * the next one's moment minus its own. Its PBs are never saved in the sim (PersonalBestSimMixin).
 */
object SimOdinSplits {
    private val groupField = runCatching { SplitsManager::class.java.getDeclaredField("currentSplits").apply { isAccessible = true } }.getOrNull()
    private val ticksField = runCatching { SplitsManager::class.java.getDeclaredField("tickCounter").apply { isAccessible = true } }.getOrNull()

    private fun group(): SplitsGroup? = groupField?.get(null) as? SplitsGroup
    private fun odinTicks(): Long = ticksField?.getLong(null) ?: 0L

    // Odin's names for the F7 splits, and ours.
    const val BLOOD_OPEN = "§2Blood Open"
    const val BLOOD_CLEAR = "§bBlood Clear"
    const val PORTAL = "§dPortal Entry"
    const val MAXOR = "§5Maxor"
    const val STORM = "§3Storm"
    const val TERMINALS = "§6Terminals"
    const val GOLDOR = "§7Goldor"
    const val NECRON = "§cNecron"

    private val OURS = mapOf(
        BLOOD_OPEN to SplitTracker.OPEN, BLOOD_CLEAR to SplitTracker.BLOOD, PORTAL to SplitTracker.PORTAL,
        MAXOR to SplitTracker.MAXOR, STORM to SplitTracker.STORM, TERMINALS to SplitTracker.TERMS,
        GOLDOR to SplitTracker.GOLDOR, NECRON to SplitTracker.NECRON,
    )

    /** Odin's name for one of our split labels. */
    fun odinName(ours: String): String? = OURS.entries.firstOrNull { it.value == ours }?.key

    /** How long [odinName] counts as before the sim's start: your Pace target, as ms. */
    fun target(odinName: String): Long =
        OdinSplitsLook.f7Target(odinName)?.let { (it * 1000).toLong() }
            ?: OURS[odinName]?.let { SplitPace.ref(it)?.ms } ?: 0L

    private var pending: String? = null
    private var headMs = 0L
    private var before: SplitsGroup? = null

    /**
     * The sim starting at [odinName]'s split (client thread, before "Starting in 1 second." reaches
     * Odin). [headMs]: already that far into it (an S2-S4 start, no line of its own comes).
     */
    fun start(odinName: String, headMs: Long = 0) {
        pending = odinName
        this.headMs = headMs
        before = group()
    }

    /** Every client tick: fills the run in once Odin has started it and the starting split has its moment. */
    fun tick() {
        val name = pending ?: return
        val g = group() ?: return
        if (g === before) return // Odin hasn't started the sim's run yet
        val splits = g.splits
        val k = splits.indexOfFirst { it.name == name }
        if (k < 0) { pending = null; return }
        if (splits[k].time == 0L) {
            if (headMs <= 0) return
            splits[k].time = System.currentTimeMillis() - headMs
            splits[k].ticks = odinTicks() - headMs / 50
        }
        for (j in k - 1 downTo 0) {
            val ms = target(splits[j].name)
            splits[j].time = splits[j + 1].time - ms
            splits[j].ticks = splits[j + 1].ticks - ms / 50
        }
        pending = null
    }
}
