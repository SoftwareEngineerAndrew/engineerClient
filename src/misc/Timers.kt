package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
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

/**
 * Countdowns through a run, each its own HUD, all counted in server ticks so lag doesn't run them
 * down. Apart from the Clear Countdown they share one look: the time left, green / yellow / red
 * (no label: each is named in the HUD editor, where its example shows one) by the share of the wait still to go, as Odin's tick timers are.
 *
 *  - Clear Countdown: from the Watcher's first line (blood open), 50 s of camp and 4 s of portal
 *    to the boss. Gone at 4 s left if the Watcher hasn't let you go by then; when he does ("You
 *    may pass" - the portal, which you are typically through about 4 s later) it is set to 4 s.
 *  - Maxor Move: Maxor starts moving 85 ticks after his first line (80-87 in 38 recorded runs).
 *  - Crystal Spawn: after a laser hit (Maxor becoming damageable) the top crystals come back 40
 *    ticks later.
 *  - Storm: the next crush check (every 20 ticks from the phase starting, a tick before his first
 *    line) until he dies, and the lightning at 548.
 *  - Necron: he takes the platform 60 ticks after "I'm afraid, your journey ends now."
 *  - Relics: in M7 they spawn 45 ticks after "All this, for nothing...", or since he stopped saying
 *    it, 5 ticks after his death burst ([onNecronDead]).
 */
object Timers : Module(
    name = "Timers",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Countdowns through a run: clear, Maxor moving, crystals, Storm, Necron and relics (Goldor's death tick: Odin's Goldor Hud).",
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
    private val necronHud by HUD("Necron Drop", "Counts down to Necron taking the platform, 3 s after \"I'm afraid, your journey ends now.\"", true, 10, 175, 1.5f) { example ->
        if (example) timer("Necron", 35, NECRON_DROP, true) else left(necronDropAt, NECRON_DROP)?.let { timer("Necron", it, NECRON_DROP) } ?: (0 to 0)
    }
    private val relicsHud by HUD("Relics", "M7: counts down to the relics spawning, 2.25 s after \"All this, for nothing...\".", true, 10, 190, 1.5f) { example ->
        if (example) timer("Relics", 30, RELICS, true) else left(relicsAt, RELICS)?.let { timer("Relics", it, RELICS) } ?: (0 to 0)
    }

    private const val CAMP_TICKS = 50 * 20
    private const val PORTAL_TICKS = 4 * 20
    private const val PORTAL_TEXT = 36
    private const val MAXOR_MOVE = 85
    private const val CRYSTALS_BACK = 40
    private const val CRUSH_PERIOD = 20
    private const val LIGHTNING = 548
    private const val NECRON_DROP = 60
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
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val NECRON_DROP_LINE = "[BOSS] Necron: I'm afraid, your journey ends now."
    private const val NECRON_DEAD = "[BOSS] Necron: All this, for nothing..."

    private var serverTicks = 0
    /** Server tick the Clear Countdown reaches 0 on; null when it isn't showing. */
    private var bossAt: Int? = null
    private var bloodSeen = false
    private var portalOpen = false
    /** Server tick the PORTAL text goes off on. */
    private var portalUntil = 0
    private var maxorMoveAt: Int? = null
    private var inMaxor = false
    private var crystalsAt: Int? = null
    private var lastHit = Int.MIN_VALUE / 2
    private var maxorHealth = 0f
    private var stormStart: Int? = null
    private var stormDead = false
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
            message == STORM_START -> { inMaxor = false; crystalsAt = null; stormStart = serverTicks - 1; stormDead = false }
            message == STORM_DEAD -> stormDead = true
            message == GOLDOR_START -> stormStart = null
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

    private fun reset() {
        bossAt = null; bloodSeen = false; portalOpen = false; portalUntil = 0
        maxorMoveAt = null; inMaxor = false; crystalsAt = null; lastHit = Int.MIN_VALUE / 2; maxorHealth = 0f
        stormStart = null; stormDead = false; necronDropAt = null; relicsAt = null
    }

    init {
        clearHud.enabled = true; portalHud.enabled = true
        maxorHud.enabled = true; crystalHud.enabled = true; stormCheckHud.enabled = true; stormLightningHud.enabled = true
        necronHud.enabled = true; relicsHud.enabled = true

        on<MessageEvent.Chat> { chat(message) }

        on<TickEvent.Server> {
            serverTicks++
            val at = bossAt ?: return@on
            // Blood not done by 50 s: hidden until the portal.
            if (!portalOpen && at - serverTicks <= PORTAL_TICKS) bossAt = null
            else if (at - serverTicks <= 0) bossAt = null
        }

        on<TickEvent.End> { watchMaxor() }

        on<LevelEvent.Load> { reset() }
    }
}
