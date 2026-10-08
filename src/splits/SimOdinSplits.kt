package com.engineerclient.splits

import com.odtheking.odin.utils.skyblock.SplitsGroup
import com.odtheking.odin.utils.skyblock.SplitsManager

/**
 * The P3 Sim's run in Odin's Splits. The sim starts partway (P1, P2, P3, a section, the core, P4),
 * so the lines before it (Mort, the Watcher, ...) never come and Odin would show nothing. The run is
 * started directly, the starting phase's split gets its time, and every split before it is filled in
 * backwards as its Pace target ([target]: the Engineer look's F7 boxes, a blank one the Odin PB,
 * neither the dark green time). On Hypixel the earlier rows would hold that run's real times, which
 * the sim cannot know; Pace targets stand in for them rather than blank rows, which would also make
 * the next "took" line and the split lengths wrong.
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

    /** How long [odinName] counts as before the sim's start: its Pace target, as ms. */
    fun target(odinName: String): Long =
        OdinSplitsLook.f7Target(odinName)?.let { (it * 1000).toLong() }
            ?: OURS[odinName]?.let { SplitPace.ref(it)?.ms } ?: 0L

    private val startRunMethod = runCatching { SplitsManager::class.java.getDeclaredMethod("startRun").apply { isAccessible = true } }.getOrNull()

    private var pending: String? = null
    private var headMs = 0L
    private var needStart = false

    /** Odin's run starts as its "Starting in 1 second." line would (nothing is said in chat: Hypixel's P3 has no such line). */
    private fun startOdinRun(): Boolean {
        runCatching { startRunMethod?.invoke(SplitsManager) }
        return group()?.splits?.isNotEmpty() == true
    }

    /**
     * The sim starting at [odinName]'s split (client thread). Odin's run is started directly (its own
     * trigger is the dungeon's "Starting in 1 second." line, which would show in chat), and the
     * starting split takes its moment now, [headMs] ago (an S2-S4 start or the lead-in, no line of its
     * own comes). Set at once, so no later line of Odin's is timed against a split with no time (its
     * "took" lines would print ~1.79e9 s) and its HUD shows rows through the lead-in as Hypixel's does.
     */
    fun start(odinName: String, headMs: Long = 0) {
        pending = odinName
        this.headMs = headMs
        needStart = true
        tick()
    }

    /**
     * The sim's Stop: Odin's run gone, as a world load leaves it (no rows, no clock), and a start
     * still waiting on it dropped.
     */
    fun stop() {
        pending = null
        needStart = false
        runCatching { groupField?.set(null, SplitsGroup(emptyList(), null)) }
    }

    /** Every client tick: starts Odin's run once it can (the dungeon bridge up), and fills it in. */
    fun tick() {
        val name = pending ?: return
        if (needStart) { if (!startOdinRun()) return; needStart = false }
        val g = group() ?: return
        val splits = g.splits
        val k = splits.indexOfFirst { it.name == name }
        if (k < 0) { pending = null; return }
        if (splits[k].time == 0L) {
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
