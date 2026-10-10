package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.playSoundAtPlayer
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.level.block.Blocks
import com.engineerclient.DeadPlayers
import com.engineerclient.storm.StormCrush

/**
 * Countdowns through a run, each its own HUD, all counted in server ticks so lag doesn't run them
 * down. Apart from the Clear Countdown they share one look: the time left, green / yellow / red
 * (no label: each is named in the HUD editor, where its example shows one) by the share of the wait still to go, as Odin's tick timers are.
 *
 *  - Clear Countdown: from the Watcher's first line (blood open), 50 s of camp and 4 s of portal
 *    to the boss. Gone at 4 s left if the Watcher hasn't let you go by then; when he does ("You
 *    may pass" - the portal, which you are typically through about 4 s later) it is set to 4 s.
 *  - Exit Core Text: "EXIT CORE", as the PORTAL text, for 0.8 s once the core has opened and every
 *    teammate alive is inside it (the inner chamber, as the splits' core box); the dead count as in.
 *  - Maxor Move: Maxor starts moving 85 ticks after his first line (80-87 in 38 recorded runs).
 *  - Crystal Spawn: after a laser hit (Maxor becoming damageable) the top crystals come back 40
 *    ticks later.
 *  - Storm: the next crush check (every 20 ticks from the phase starting, a tick before his first
 *    line) until he dies, and the lightning at 548.
 *  - Purple Pad / Yellow Pad: to the pad read to stand on Storm's pillar pad for, so it comes down
 *    on him at the first check he can be crushed on. Pads are read on the 20-tick checks' eve (phase
 *    tick 19 mod 20) and step their pillar 5 blocks over the next 17 ticks, and a read 41 or 61
 *    ticks before a check crushes on it; 21 is too late for the pillar to be down by then (half of
 *    F7's), 81 too early (it is armed 60 ticks from its last step). Worked out from 470 F7 and 52
 *    M7 runs since Hypixel's boss update:
 *     - Purple: he is in its zone 107-113 ticks after his lightning line, crushed on the first
 *       check from lightning + 109 (t 660): the reads at t 599 and t 619 (both crush, 97% and 100%
 *       with him there). Gone once its pillar steps from one of them.
 *     - Yellow: after a Purple crush he flies there as "Storm is enraged!" comes (+-3 ticks) and is
 *       in its zone 80-97 ticks later (M7 77-92); the crush comes on the first check from enrage +
 *       87 (M7 84) - only a check 93 or more after it is certain, so the read 41 ticks before that
 *       check, which still covers the next one. Then the read after it too: he hovers at y 173 over
 *       Yellow, where one step (181 -> 176) reaches his head, but at y 169-172 it takes a second
 *       (-> 171) - one read crushed 105 of 119 times at y 173, none of 5 below it.
 *  - Necron: he takes the platform 60 ticks after "I'm afraid, your journey ends now."
 *  - Relics: in M7 they spawn 45 ticks after "All this, for nothing...", or since he stopped saying
 *    it, 5 ticks after his death burst ([onNecronDead]).
 */
object Timers : Module(
    name = "Timers",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Countdowns through a run: clear, exit core, Maxor moving, crystals, Storm and his pads, Necron and relics (Goldor's death tick: Odin's Goldor Hud).",
    key = null,
) {
    private val showTicks by BooleanSetting("Show Ticks", false, desc = "The timers below the Clear Countdown in server ticks instead of seconds.")

    // --- Clear Countdown ---------------------------------------------------------------------------

    private val clearHud by HUD("Clear Countdown", "From blood opening, counts down 54 s to the boss (50 s camp, 4 s portal): green, yellow under 30, red under 20. Hidden if blood isn't done by 50 s; set to 4 s when the portal spawns.", true, 420, 200, 2f) { example ->
        val left = if (example) 41.3 else bossTicksLeft()?.let { it / 20.0 } ?: return@HUD 0 to 0
        val colour = when { left > 30 -> "§a"; left > 20 -> "§e"; else -> "§c" }
        draw(colour + String.format(java.util.Locale.ROOT, "%.1f", left))
    }
    private val portalHud by HUD("Portal Text", "\"PORTAL\" in pink on screen for 1.8 s when the portal spawns.", true, 400, 160, 4f) { example ->
        if (!example && (!enabled || DungeonUtils.inBoss || portalUntil - serverTicks <= 0)) return@HUD 0 to 0
        draw("§d§lPORTAL")
    }
    private val portalChime by BooleanSetting("Portal Chime", true, desc = "A chime when the portal spawns.")
    private val exitCoreHud by HUD("Exit Core Text", "\"EXIT CORE\" in pink on screen for 0.8 s once the core has opened and everyone has leapt in.", true, 400, 160, 4f) { example ->
        if (!example && (!enabled || exitCoreUntil - serverTicks <= 0)) return@HUD 0 to 0
        draw("§d§lEXIT CORE")
    }

    // --- Boss timers -------------------------------------------------------------------------------

    private val maxorHud by HUD("Maxor Move", "Counts down to Maxor starting to move, 4.25 s after his first line.", true, 10, 100, 1.5f) { example ->
        if (example) timer("Maxor", 100, MAXOR_MOVE, true) else left(maxorMoveAt, MAXOR_MOVE)?.let { timer("Maxor", it, MAXOR_MOVE) } ?: (0 to 0)
    }
    private val crystalHud by HUD("Crystal Spawn", "Counts down to the top crystals coming back after a laser hit, 2 s.", true, 10, 115, 1.5f) { example ->
        if (example) timer("Crystals", 25, CRYSTALS_BACK, true) else left(crystalsAt, CRYSTALS_BACK)?.let { timer("Crystals", it, CRYSTALS_BACK) } ?: (0 to 0)
    }
    private val stormCheckHud by HUD("Storm Crush Check", "Counts down to Storm's next crush check, once a second through his phase.", true, 10, 130, 1.5f) { example ->
        val start = stormStart
        when {
            example -> timer("Crush", 12, CRUSH_PERIOD, true)
            start == null || stormDead -> 0 to 0
            else -> timer("Crush", CRUSH_PERIOD - Math.floorMod(serverTicks - start, CRUSH_PERIOD), CRUSH_PERIOD)
        }
    }
    private val stormLightningHud by HUD("Storm Lightning", "Counts down to Storm's lightning, 27.4 s into his phase.", true, 10, 145, 1.5f) { example ->
        val start = stormStart
        when {
            example -> timer("Lightning", 300, LIGHTNING, true)
            start == null || stormDead -> 0 to 0
            else -> left(start + LIGHTNING, LIGHTNING)?.let { timer("Lightning", it, LIGHTNING) } ?: (0 to 0)
        }
    }
    private val purplePadHud by HUD("Purple Pad", "Storm: counts down to the pad read to be on Purple's pad for (t 599, then t 619 if that one's missed), so he's crushed the moment he gets there. Gone once the pillar comes down.", true, 10, 160, 1.5f) { example ->
        if (example) timer("Purple", 30, PAD_TOTAL, true) else padLeft(purpleReads())?.let { timer("Purple", it, PAD_TOTAL) } ?: (0 to 0)
    }
    private val yellowPadHud by HUD("Yellow Pad", "Storm: after a Purple crush, counts down to the pad read to be on Yellow's pad for - worked out from when he's enraged, as he flies there - and then the next one (if he comes in low, the pillar needs both).", true, 10, 205, 1.5f) { example ->
        if (example) timer("Yellow", 25, PAD_TOTAL, true) else padLeft(yellowReads())?.let { timer("Yellow", it, PAD_TOTAL) } ?: (0 to 0)
    }
    private val necronHud by HUD("Necron Drop", "Counts down to Necron taking the platform, 3 s after \"I'm afraid, your journey ends now.\"", true, 10, 175, 1.5f) { example ->
        if (example) timer("Necron", 35, NECRON_DROP, true) else left(necronDropAt, NECRON_DROP)?.let { timer("Necron", it, NECRON_DROP) } ?: (0 to 0)
    }
    private val relicsHud by HUD("Relics", "M7: counts down to the relics spawning, 2.25 s after \"All this, for nothing...\".", true, 10, 190, 1.5f) { example ->
        if (example) timer("Relics", 30, RELICS, true) else left(relicsAt, RELICS)?.let { timer("Relics", it, RELICS) } ?: (0 to 0)
    }

    private const val CAMP_TICKS = 50 * 20
    private const val PORTAL_TICKS = 4 * 20
    private const val PORTAL_TEXT = 36
    private const val EXIT_CORE_TEXT = 16
    private const val MAXOR_MOVE = 85
    private const val CRYSTALS_BACK = 40
    private const val CRUSH_PERIOD = 20
    private const val LIGHTNING = 548
    private const val NECRON_DROP = 60
    /** A pad read this long before a check brings its pillar down on him by then, the read 20 earlier too. */
    private const val PAD_LEAD = 41
    /** He's in Purple's zone for the first check this long after his lightning line. */
    private const val PURPLE_AFTER_LIGHTNING = 109
    /** The first check he can be crushed on at Yellow is this long after "Storm is enraged!" (F7, M7). */
    private const val YELLOW_AFTER_ENRAGE = 87
    private const val YELLOW_AFTER_ENRAGE_M7 = 84
    /** The pad timers' colours: green from 3 s out, red under 1 s. */
    private const val PAD_TOTAL = 60
    private const val RELICS = 45
    private const val RELICS_AFTER_DEATH = 5
    /** Maxor's health once his armour is off: a laser hit. Flickers within one stun are one hit. */
    private const val DAMAGEABLE = 500f
    private const val HIT_DEBOUNCE = 30

    private const val WATCHER = "[BOSS] The Watcher: "
    private const val WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass."
    private const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private const val STORM_ENRAGED = "⚠ Storm is enraged! ⚠"
    private val STORM_LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")
    private val STORM_CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_OPENING = "The Core entrance is opening!"
    private const val NECRON_DROP_LINE = "[BOSS] Necron: I'm afraid, your journey ends now."
    private const val NECRON_DEAD = "[BOSS] Necron: All this, for nothing..."

    private var serverTicks = 0
    /** Server tick the Clear Countdown reaches 0 on; null when it isn't showing. */
    private var bossAt: Int? = null
    private var bloodSeen = false
    private var portalOpen = false
    /** Server tick the PORTAL text goes off on. */
    private var portalUntil = 0
    /** From the core opening until everyone is in it. */
    private var coreOpen = false
    /** Server tick the EXIT CORE text goes off on. */
    private var exitCoreUntil = 0
    private var maxorMoveAt: Int? = null
    private var inMaxor = false
    private var crystalsAt: Int? = null
    private var lastHit = Int.MIN_VALUE / 2
    private var maxorHealth = 0f
    private var stormStart: Int? = null
    private var stormDead = false
    private var lightningAt: Int? = null
    private var enragedAt: Int? = null
    private var crushes = 0
    /** A crush line came: which pillar it was is read off where he is, on the next client tick. */
    private var crushPending = false
    private var firstCrushPurple = false
    /** When Purple's pillar last stepped down. */
    private var purpleStepAt: Int? = null
    private var necronDropAt: Int? = null
    private var relicsAt: Int? = null

    private fun bossTicksLeft(): Int? {
        if (!enabled || DungeonUtils.inBoss) return null
        val at = bossAt ?: return null
        return (at - serverTicks).takeIf { it > 0 }
    }

    /** Ticks left to [at] while it is up to [total] ahead; null once it has passed. */
    private fun left(at: Int?, total: Int): Int? {
        if (at == null) return null
        return (at - serverTicks).takeIf { it in 1..total }
    }

    /** The first crush check (every 20 ticks from the phase start) at or after [t]. */
    private fun checkFrom(t: Int): Int? {
        val start = stormStart ?: return null
        return start + Math.ceilDiv(t - start, StormCrush.CHECK_PERIOD) * StormCrush.CHECK_PERIOD
    }

    /** Purple's two reads, until it's pressed for one of them or he's crushed. */
    private fun purpleReads(): List<Int> {
        val light = lightningAt ?: return emptyList()
        if (crushes > 0 || stormDead) return emptyList()
        val check = checkFrom(light + PURPLE_AFTER_LIGHTNING) ?: return emptyList()
        val reads = listOf(check - PAD_LEAD - StormCrush.CHECK_PERIOD, check - PAD_LEAD)
        if ((purpleStepAt ?: Int.MIN_VALUE) > reads[0]) return emptyList()
        return reads
    }

    /** Yellow's two reads after a Purple crush, until he's crushed again. */
    private fun yellowReads(): List<Int> {
        val enraged = enragedAt ?: return emptyList()
        if (!firstCrushPurple || crushes != 1 || stormDead) return emptyList()
        val after = if (DungeonUtils.floor?.name?.startsWith("M") == true) YELLOW_AFTER_ENRAGE_M7 else YELLOW_AFTER_ENRAGE
        val first = (checkFrom(enraged + after) ?: return emptyList()) - PAD_LEAD
        return listOf(first, first + StormCrush.CHECK_PERIOD)
    }

    /** Ticks to the next of [reads] (0 on the read itself), or null once they're all past. */
    private fun padLeft(reads: List<Int>): Int? {
        if (!enabled) return null
        return reads.firstOrNull { it >= serverTicks }?.let { it - serverTicks }
    }

    private fun colour(left: Int, total: Int): String = when {
        left >= total * 0.66f -> "§a"
        left >= total * 0.33f -> "§6"
        else -> "§c"
    }

    private fun time(ticks: Int): String =
        if (showTicks) "${ticks}t" else String.format(java.util.Locale.ROOT, "%.2fs", ticks / 20.0)

    /** The time left; [label] only in the HUD editor ([example]), to tell the timers apart there. */
    private fun GuiGraphicsExtractor.timer(label: String, left: Int, total: Int, example: Boolean = false): Pair<Int, Int> =
        draw((if (example) "§7$label: " else "") + colour(left, total) + time(left))

    private fun GuiGraphicsExtractor.draw(s: String): Pair<Int, Int> {
        text(s, 0, 0, Colors.WHITE, shadow = true)
        return mc.font.width(s) to 9
    }

    private fun chat(message: String) {
        when {
            message == WATCHER_DONE -> {
                bloodSeen = true
                portalOpen = true
                bossAt = serverTicks + PORTAL_TICKS
                portalUntil = serverTicks + PORTAL_TEXT
                if (enabled && portalChime) playSoundAtPlayer(SoundEvents.NOTE_BLOCK_CHIME.value(), 1f, 1.2f)
            }
            message.startsWith(WATCHER) && !bloodSeen -> {
                bloodSeen = true
                bossAt = serverTicks + CAMP_TICKS + PORTAL_TICKS
            }
            message == MAXOR_START -> { inMaxor = true; maxorMoveAt = serverTicks + MAXOR_MOVE; maxorHealth = 0f }
            message == STORM_START -> {
                inMaxor = false; crystalsAt = null; stormStart = serverTicks - 1; stormDead = false
                lightningAt = null; enragedAt = null; crushes = 0; crushPending = false; firstCrushPurple = false; purpleStepAt = null
            }
            message == STORM_DEAD -> stormDead = true
            message in STORM_LIGHTNING -> if (stormStart != null && lightningAt == null) lightningAt = serverTicks
            message == STORM_ENRAGED -> if (stormStart != null) enragedAt = serverTicks
            message in STORM_CRUSHED -> if (stormStart != null && ++crushes == 1) crushPending = true
            message == GOLDOR_START -> stormStart = null
            message == CORE_OPENING -> coreOpen = true
            message == NECRON_DROP_LINE -> necronDropAt = serverTicks + NECRON_DROP
            message == NECRON_DEAD -> if (DungeonUtils.floor?.name?.startsWith("M") == true) relicsAt = serverTicks + RELICS
        }
    }

    /**
     * Necron dead on M7 (DungeonSplits: the TNT burst he dies in). He no longer says "All this, for
     * nothing..." since Hypixel's boss update; in the recorded fights the burst came 40 ticks after
     * that line, so the relics are [RELICS_AFTER_DEATH] ticks after it.
     */
    fun onNecronDead() {
        if (relicsAt == null || relicsAt!! < serverTicks) relicsAt = serverTicks + RELICS_AFTER_DEATH
    }

    /** Maxor's armour coming off (his health jumping past [DAMAGEABLE]): a laser hit. Client thread. */
    private fun watchMaxor() {
        if (!inMaxor) return
        val level = mc.level ?: return
        val maxor = level.entitiesForRendering().firstOrNull { it is WitherBoss && Witherborn.isBoss(it) } as? WitherBoss ?: return
        val hp = maxor.health
        if (hp >= DAMAGEABLE && maxorHealth < DAMAGEABLE && serverTicks - lastHit >= HIT_DEBOUNCE) {
            lastHit = serverTicks
            crystalsAt = serverTicks + CRYSTALS_BACK
        }
        maxorHealth = hp
    }

    /**
     * Once the core is open: you and every teammate alive inside it, others where the server last put
     * them; the dead count as in ([DeadPlayers]: a ghost can be anywhere). Someone alive out of view
     * holds it back. Client thread.
     */
    private fun watchCore() {
        if (!coreOpen) return
        val level = mc.level ?: return
        val me = mc.player ?: return
        if (!DeadPlayers.isDead(me.name.string) && !inCore(me.x, me.y, me.z)) return
        for (mate in DungeonUtils.dungeonTeammates) {
            if (DeadPlayers.isDead(mate) || mate.name == me.name.string) continue
            val p = mate.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == mate.name } ?: return
            val at = p.positionCodec.base
            if (!inCore(at.x, at.y, at.z)) return
        }
        coreOpen = false
        exitCoreUntil = serverTicks + EXIT_CORE_TEXT
    }

    /** The inner chamber: the core and the space at its door (DungeonSplits' core box). */
    private fun inCore(x: Double, y: Double, z: Double) = x >= 39 && x < 71 && y >= 110 && y < 155.5 && z >= 54 && z < 118

    /** Which pillar the first crush was: the one his position (as the server last put him) is nearest. Client thread. */
    private fun readCrush() {
        if (!crushPending) return
        crushPending = false
        val storm = mc.level?.entitiesForRendering()?.filterIsInstance<WitherBoss>()
            ?.filter { it.isAlive && it.y in 150.0..215.0 && Witherborn.isBoss(it) }
            ?.minByOrNull { it.distanceToSqr(70.0, 180.0, 53.0) } ?: return
        val at = storm.positionCodec.base.takeIf { it != net.minecraft.world.phys.Vec3.ZERO } ?: storm.position()
        firstCrushPurple = StormCrush.nearest(at.x, at.z).name == "Purple"
    }

    private fun reset() {
        bossAt = null; bloodSeen = false; portalOpen = false; portalUntil = 0; coreOpen = false; exitCoreUntil = 0
        maxorMoveAt = null; inMaxor = false; crystalsAt = null; lastHit = Int.MIN_VALUE / 2; maxorHealth = 0f
        stormStart = null; stormDead = false; necronDropAt = null; relicsAt = null
        lightningAt = null; enragedAt = null; crushes = 0; crushPending = false; firstCrushPurple = false; purpleStepAt = null
    }

    init {
        clearHud.enabled = true; portalHud.enabled = true
        maxorHud.enabled = true; crystalHud.enabled = true; stormCheckHud.enabled = true; stormLightningHud.enabled = true
        necronHud.enabled = true; relicsHud.enabled = true; purplePadHud.enabled = true; yellowPadHud.enabled = true

        on<MessageEvent.Chat> { chat(message) }

        on<TickEvent.Server> {
            serverTicks++
            val at = bossAt ?: return@on
            // Blood not done by 50 s: hidden until the portal.
            if (!portalOpen && at - serverTicks <= PORTAL_TICKS) bossAt = null
            else if (at - serverTicks <= 0) bossAt = null
        }

        on<TickEvent.End> { watchMaxor(); watchCore(); readCrush() }

        // Purple's pillar stepping down: its layers go through moving pistons (going back up, they don't).
        on<BlockUpdateEvent> {
            if (stormStart == null || updated.block != Blocks.MOVING_PISTON || pos.y !in StormCrush.FLOOR..StormCrush.TOP) return@on
            val purple = StormCrush.PILLARS[0]
            if (pos.x == purple.columnX && pos.z == purple.columnZ) purpleStepAt = serverTicks
        }

        on<LevelEvent.Load> { reset() }
    }
}
