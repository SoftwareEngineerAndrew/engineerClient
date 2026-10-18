package com.engineerclient.misc

import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
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
 *    line) until he dies, and his Giga Lightning: it strikes at 520 (519-521 in 662 F7 and 371 M7
 *    runs since the boss update), 33 ticks after "I'd be happy to show you what that's like!", which
 *    sets it again if his first line was missed. His "ENERGY HEED MY CALL!" comes 29 ticks after it.
 *  - Purple Pad: to the pad read to stand on Purple's pillar pad for, so it comes down on him at the
 *    first check he can be crushed on. Pads are read on the 20-tick checks' eve (phase tick 19 mod
 *    20) and step their pillar 5 blocks over the next 17 ticks, and a read 41 or 61 ticks before a
 *    check crushes on it; 21 is too late for the pillar to be down by then (half of F7's), 81 too
 *    early (it is armed 60 ticks from its last step). Worked out from 470 F7 and 52 M7 runs since
 *    Hypixel's boss update: he is in Purple's zone 107-113 ticks after his lightning line, crushed on
 *    the first check from lightning + 109 (t 660): the reads at t 599 and t 619 (both crush, 97% and
 *    100% with him there). Gone once its pillar steps from one of them.
 *  - Necron: he takes the platform 60 ticks after "I'm afraid, your journey ends now."
 */
object Timers : Module(
    name = "Timers",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Countdowns through a run: clear, exit core, Maxor moving, crystals, Storm and Purple's pad, and Necron (Goldor's death tick: Odin's Goldor Hud).",
    key = null,
) {
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
    private val stormLightningHud by HUD("Storm Lightning", "Counts down to Storm's Giga Lightning striking, 26 s into his phase.", true, 10, 145, 1.5f) { example ->
        when {
            example -> timer("Lightning", 300, LIGHTNING, true)
            stormDead -> 0 to 0
            else -> left(strikeAt, LIGHTNING)?.let { timer("Lightning", it, LIGHTNING) } ?: (0 to 0)
        }
    }
    private val purplePadHud by HUD("Purple Pad", "Storm: counts down to the pad read to be on Purple's pad for (t 599, then t 619 if that one's missed), so he's crushed the moment he gets there. Gone once the pillar comes down.", true, 10, 160, 1.5f) { example ->
        if (example) timer("Purple", 30, PAD_TOTAL, true) else padLeft(purpleReads())?.let { timer("Purple", it, PAD_TOTAL) } ?: (0 to 0)
    }
    private val necronHud by HUD("Necron Drop", "Counts down to Necron taking the platform, 3 s after \"I'm afraid, your journey ends now.\"", true, 10, 175, 1.5f) { example ->
        if (example) timer("Necron", 35, NECRON_DROP, true) else left(necronDropAt, NECRON_DROP)?.let { timer("Necron", it, NECRON_DROP) } ?: (0 to 0)
    }

    private const val CAMP_TICKS = 50 * 20
    private const val PORTAL_TICKS = 4 * 20
    private const val PORTAL_TEXT = 36
    private const val EXIT_CORE_TEXT = 16
    private const val MAXOR_MOVE = 85
    private const val CRYSTALS_BACK = 40
    private const val CRUSH_PERIOD = 20
    /** Giga Lightning strikes this long into his phase, and this long after "I'd be happy to show you what that's like!". */
    private const val LIGHTNING = 520
    private const val STRIKE_AFTER_SHOW = 33
    private const val NECRON_DROP = 60
    /** A pad read this long before a check brings its pillar down on him by then, the read 20 earlier too. */
    private const val PAD_LEAD = 41
    /** He's in Purple's zone for the first check this long after his lightning line. */
    private const val PURPLE_AFTER_LIGHTNING = 109
    /** The pad timer's colours: green from 3 s out, red under 1 s. */
    private const val PAD_TOTAL = 60
    /** Maxor's health once his armour is off: a laser hit. Flickers within one stun are one hit. */
    private const val DAMAGEABLE = 500f
    private const val HIT_DEBOUNCE = 30

    private const val WATCHER = "[BOSS] The Watcher: "
    private const val WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass."
    private const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private const val STORM_SHOW = "[BOSS] Storm: I'd be happy to show you what that's like!"
    private val STORM_LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")
    private val STORM_CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_OPENING = "The Core entrance is opening!"
    private const val NECRON_DROP_LINE = "[BOSS] Necron: I'm afraid, your journey ends now."

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
    /** When Giga Lightning strikes. */
    private var strikeAt: Int? = null
    private var crushes = 0
    /** When Purple's pillar last stepped down. */
    private var purpleStepAt: Int? = null
    private var necronDropAt: Int? = null

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

    private fun time(ticks: Int): String = String.format(java.util.Locale.ROOT, "%.2fs", ticks / 20.0)

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
            }
            message.startsWith(WATCHER) && !bloodSeen -> {
                bloodSeen = true
                bossAt = serverTicks + CAMP_TICKS + PORTAL_TICKS
            }
            message == MAXOR_START -> { inMaxor = true; maxorMoveAt = serverTicks + MAXOR_MOVE; maxorHealth = 0f }
            message == STORM_START -> {
                inMaxor = false; crystalsAt = null; stormStart = serverTicks - 1; stormDead = false
                lightningAt = null; crushes = 0; purpleStepAt = null; strikeAt = serverTicks - 1 + LIGHTNING
            }
            message == STORM_SHOW -> strikeAt = serverTicks + STRIKE_AFTER_SHOW
            message == STORM_DEAD -> stormDead = true
            message in STORM_LIGHTNING -> if (stormStart != null && lightningAt == null) lightningAt = serverTicks
            message in STORM_CRUSHED -> if (stormStart != null) crushes++
            message == GOLDOR_START -> stormStart = null
            message == CORE_OPENING -> coreOpen = true
            message == NECRON_DROP_LINE -> necronDropAt = serverTicks + NECRON_DROP
        }
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

    private fun reset() {
        bossAt = null; bloodSeen = false; portalOpen = false; portalUntil = 0; coreOpen = false; exitCoreUntil = 0
        maxorMoveAt = null; inMaxor = false; crystalsAt = null; lastHit = Int.MIN_VALUE / 2; maxorHealth = 0f
        stormStart = null; stormDead = false; necronDropAt = null
        lightningAt = null; crushes = 0; purpleStepAt = null; strikeAt = null
    }

    init {
        clearHud.enabled = true; portalHud.enabled = true
        maxorHud.enabled = true; crystalHud.enabled = true; stormCheckHud.enabled = true; stormLightningHud.enabled = true
        necronHud.enabled = true; purplePadHud.enabled = true

        on<MessageEvent.Chat> { chat(message) }

        on<TickEvent.Server> {
            serverTicks++
            val at = bossAt ?: return@on
            // Blood not done by 50 s: hidden until the portal.
            if (!portalOpen && at - serverTicks <= PORTAL_TICKS) bossAt = null
            else if (at - serverTicks <= 0) bossAt = null
        }

        on<TickEvent.End> { watchMaxor(); watchCore() }

        // Purple's pillar stepping down: its layers go through moving pistons (going back up, they don't).
        on<BlockUpdateEvent> {
            if (stormStart == null || updated.block != Blocks.MOVING_PISTON || pos.y !in StormCrush.FLOOR..StormCrush.TOP) return@on
            val purple = StormCrush.PILLARS[0]
            if (pos.x == purple.columnX && pos.z == purple.columnZ) purpleStepAt = serverTicks
        }

        on<LevelEvent.Load> { reset() }
    }
}
