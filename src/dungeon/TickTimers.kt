package com.engineerclient.dungeon

import com.engineerclient.DeadPlayers
import com.engineerclient.EngineerClient
import com.engineerclient.misc.Witherborn
import com.engineerclient.practice.TermInfo
import com.engineerclient.storm.StormCrush
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.HudElement
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.BlockUpdateEvent
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.itemId
import com.adm.utils.noControlCodes
import com.adm.utils.playSoundAtPlayer
import com.adm.utils.render.text
import com.adm.utils.render.textDim
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.toFixed
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.level.block.Blocks
import java.util.Locale

/**
 * Timers through a run, all counted in server ticks so lag doesn't run them down.
 *
 *  - ADM's phase timers (Maxor, Storm, Term Start, Goldor): time since each phase's first line, alone or in one shared HUD.
 *  - Clear Countdown: from the Watcher's first line, 50 s of camp and 4 s of portal; set to 4 s at "You may pass".
 *  - Maxor Move: 85 ticks after his first line (80-87 seen). Crystal Spawn: 40 ticks after a laser hit.
 *  - Purple Pad: pads are read on the 20-tick checks' eve and a read 41 or 61 ticks before a check crushes on it; Storm is in
 *    Purple's zone for the first check from lightning line + 109, so those two reads.
 *  - Giga Lightning: the title countdown steps every 25 ticks and the strike lands about 20 after its 1, so each number resyncs it.
 *  - Necron DPS: he can first be damaged 89 ticks after his first line; none of his lines marks it.
 */
object TickTimers : Module(
    name = "Tick Timers",
    category = Category.BOSS,
    description = "Timers through a run: clear, each F7 phase, Storm's pads and lightning, terminals, Necron and more.",
    key = null,
) {
    private val combine by BooleanSetting("Combine Timers", true, desc = "Maxor, Storm, Term Start and Goldor share one HUD.")
    private val combinedHud by HUD("Combined Timer", "Maxor, Storm, Term Start and Goldor timers in one place.", false, 400, 230, 2f) { example ->
        if (!combine) return@HUD 0 to 0
        if (example) return@HUD draw(fmt(0), maxorColor)
        current()?.let { (t, c) -> draw(t, c) } ?: (0 to 0)
    }.withDependency { combine }

    private val clearDropdown by DropdownSetting("Clear", desc = "Clear Countdown and Portal Text.")
    private val clearHud by HUD("Clear Countdown", "Counts down 54 s from blood opening to the boss; 4 s once the portal spawns.", true, 420, 200, 2f) { example ->
        val left = if (example) 41.3 else bossTicksLeft()?.let { it / 20.0 } ?: return@HUD 0 to 0
        val colour = when { left > 30 -> "§a"; left > 20 -> "§e"; else -> "§c" }
        draw(colour + String.format(Locale.ROOT, "%.1f", left))
    }.withDependency { clearDropdown }
    private val portalHud by HUD("Portal Text", "\"PORTAL\" in pink for 1.8 s when the portal spawns.", true, 400, 160, 4f) { example ->
        if (!example && (!enabled || DungeonUtils.inBoss || portalUntil - serverTicks <= 0)) return@HUD 0 to 0
        draw("§d§lPORTAL")
    }.withDependency { clearDropdown }

    private val maxorDropdown by DropdownSetting("Maxor", desc = "Maxor timer, Maxor Move and Crystal Spawn.")
    private val maxorOn by BooleanSetting("Maxor Tick Timer", true, desc = "Time since Maxor's first line.").withDependency { maxorDropdown }
    private val maxorColor by ColorSetting("Maxor Color", Color(0xFFAA00AA.toInt()), false, desc = "Colour of the Maxor timer.").withDependency { maxorDropdown && maxorOn }
    private val maxorHud by HUD("Maxor HUD", "The Maxor timer.", true, 400, 200, 2f) { example ->
        if (combine) return@HUD 0 to 0
        if (example) return@HUD draw(fmt(0), maxorColor)
        maxorText()?.let { (t, c) -> draw(t, c) } ?: (0 to 0)
    }.withDependency { maxorDropdown && !combine && maxorOn }
    private val maxorMoveHud by HUD("Maxor Move", "Counts down to Maxor starting to move, 4.25 s after his first line.", true, 10, 100, 1.5f) { example ->
        if (example) timer("Maxor", 100, MAXOR_MOVE, true) else left(maxorMoveAt, MAXOR_MOVE)?.let { timer("Maxor", it, MAXOR_MOVE) } ?: (0 to 0)
    }.withDependency { maxorDropdown }
    private val crystalHud by HUD("Crystal Spawn", "Counts down 2 s to the top crystals coming back after a laser hit.", true, 10, 115, 1.5f) { example ->
        if (example) timer("Crystals", 25, CRYSTALS_BACK, true) else left(crystalsAt, CRYSTALS_BACK)?.let { timer("Crystals", it, CRYSTALS_BACK) } ?: (0 to 0)
    }.withDependency { maxorDropdown }

    private val stormDropdown by DropdownSetting("Storm", desc = "Storm timer, Purple Pad, Giga Lightning and Storm Death Time.")
    private val stormOn by BooleanSetting("Storm Timer", true, desc = "Time since Storm's first line, until he dies.").withDependency { stormDropdown }
    private val stormDown by BooleanSetting("Storm Tick Down", false, desc = "Counts down the last 5 s to 29 s instead of up.").withDependency { stormDropdown && stormOn }
    private val stormColor by ColorSetting("Storm Color", Color(0xFF00F9DD.toInt()), false, desc = "Colour of the Storm timer.").withDependency { stormDropdown && stormOn }
    private val stormHud by HUD("Storm HUD", "The Storm timer.", false, 400, 230, 2f) { example ->
        if (combine) return@HUD 0 to 0
        if (example) return@HUD draw(fmt(0), stormColor)
        stormText()?.let { (t, c) -> draw(t, c) } ?: (0 to 0)
    }.withDependency { stormDropdown && !combine && stormOn }
    private val purplePadHud by HUD("Purple Pad", "Counts down to the pad read to be on Purple's pad for, so he's crushed on arrival.", true, 10, 160, 1.5f) { example ->
        if (example) timer("Purple", 30, PAD_TOTAL, true) else padLeft(purpleReads())?.let { timer("Purple", it, PAD_TOTAL) } ?: (0 to 0)
    }.withDependency { stormDropdown }
    private val gigaHud by HUD("Giga Lightning", "Counts down to Storm's Giga Lightning striking.", true, 10, 205, 1.5f) { example ->
        if (example) timer("Giga", 54, GIGA_TOTAL, true) else left(gigaAt, GIGA_TOTAL)?.let { timer("Giga", it, GIGA_TOTAL) } ?: (0 to 0)
    }.withDependency { stormDropdown }
    private val hideGigaTitle: Boolean by BooleanSetting("Hide Giga Title", true, desc = "Hides Storm's countdown title while Giga Lightning shows.").withDependency { stormDropdown && gigaHud.enabled }
    private val stormDeathHud by HUD("Storm Death Time", "At \"Storm is enraged!\", how long Storm took to die, in chat and here for 2 s.", true, 10, 145, 1.5f) { example ->
        if (!example && (!enabled || stormShownUntil - serverTicks <= 0)) return@HUD 0 to 0
        draw("§5" + String.format(java.util.Locale.ROOT, "%.2f", (if (example) 31 * 20 + 7 else stormDiedTicks) / 20.0))
    }.withDependency { stormDropdown }

    private val termsDropdown by DropdownSetting("Terminals", desc = "Term Start and Section Time.")
    private val termStartOn by BooleanSetting("Term Start Timer", true, desc = "Counts down the 3 s from Storm's death until terminals can be done.").withDependency { termsDropdown }
    private val termStartHud by HUD("Term Start HUD", "The Term Start timer.", false, 400, 245, 2f) { example ->
        if (combine) return@HUD 0 to 0
        if (example) return@HUD draw(fmt(60), YELLOW)
        termStartText()?.let { (t, c) -> draw(t, c) } ?: (0 to 0)
    }.withDependency { termsDropdown && !combine && termStartOn }
    private val sectionHud: HudElement by HUD("Section Time", "How long each terminal section took, in purple, when it's done.", true, 420, 330, 5f) { example ->
        draw(if (example) "§514.35" else TermInfo.sectionTime(sectionSeconds) ?: return@HUD 0 to 0)
    }.withDependency { termsDropdown }
    private val sectionSeconds: Double by NumberSetting("Section Time Seconds", 1.5, 0.5..10.0, 0.5, desc = "How long a section's time stays up.", unit = "s").withDependency { termsDropdown && sectionHud.enabled }
    private val sectionDing: Boolean by BooleanSetting("Section Time Ding", true, desc = "A ding when a terminal section ends, as its time goes up.").withDependency { termsDropdown && sectionHud.enabled }

    private val goldorDropdown by DropdownSetting("Goldor", desc = "Goldor timer and Exit Core Text.")
    private val goldorOn by BooleanSetting("Goldor Timer", true, desc = "Time since Goldor's first line, green / gold / red through each 3 s death tick.").withDependency { goldorDropdown }
    private val deathTicks by BooleanSetting("Goldor In Death Ticks", false, desc = "Shows only the time within each 3 s death tick.").withDependency { goldorDropdown && goldorOn }
    private val goldorUp by BooleanSetting("Goldor Tick Up", false, desc = "Within each death tick, counts up instead of down from 3 s.").withDependency { goldorDropdown && goldorOn && deathTicks }
    private val goldorHud by HUD("Goldor HUD", "The Goldor timer.", false, 400, 260, 2f) { example ->
        if (combine) return@HUD 0 to 0
        if (example) return@HUD draw(fmt(0), GREEN)
        goldorText()?.let { (t, c) -> draw(t, c) } ?: (0 to 0)
    }.withDependency { goldorDropdown && !combine && goldorOn }
    private val exitCoreHud by HUD("Exit Core Text", "\"EXIT CORE\" in pink for 0.8 s once the core is open and everyone is in.", true, 400, 160, 4f) { example ->
        if (!example && (!enabled || exitCoreUntil - serverTicks <= 0)) return@HUD 0 to 0
        draw("§d§lEXIT CORE")
    }.withDependency { goldorDropdown }

    private val necronDropdown by DropdownSetting("Necron", desc = "Necron DPS.")
    private val necronHud by HUD("Necron DPS", "Counts down to Necron first taking damage, 4.45 s after his first line.", true, 10, 175, 1.5f) { example ->
        if (example) timer("Necron", 35, NECRON_DPS, true) else left(necronDpsAt, NECRON_DPS)?.let { timer("Necron", it, NECRON_DPS) } ?: (0 to 0)
    }.withDependency { necronDropdown }

    private val otherDropdown by DropdownSetting("Other", desc = "Tac Insertion and M3's Fire Freeze.")
    private val tacHud by HUD("Tac Insertion", "Counts down 3 s to the Tactical Insertion taking you back.", true, 10, 190, 1.5f) { example ->
        if (example) timer("Tac", 30, TAC_RETURN, true) else left(tacReturnAt, TAC_RETURN)?.let { timer("Tac", it, TAC_RETURN) } ?: (0 to 0)
    }.withDependency { otherDropdown }
    private val fireFreezeHud by HUD("Fire Freeze Hud", "Displays a timer for when to use fire freeze in M3.") {
        val timeTillFreeze = fireFreezeTime - 100
        if (it) textDim(formatTimer(50, 60, "Fire Freeze:"), 0, 0, Colors.MINECRAFT_DARK_RED)
        else if (timeTillFreeze > 0) textDim(formatTimer(timeTillFreeze, 60, "Fire Freeze:"), 0, 0, Colors.MINECRAFT_DARK_RED)
        else if (fireFreezeTime >= 0) textDim("Use Fire Freeze", 0, 0, Colors.MINECRAFT_DARK_RED)
        else 0 to 0
    }.withDependency { otherDropdown }
    private val displayInTicks by BooleanSetting("Display in Ticks", false, desc = "Fire Freeze in ticks instead of seconds.").withDependency { otherDropdown && fireFreezeHud.enabled }
    private val symbolDisplay by BooleanSetting("Display Symbol", true, desc = "Displays s or t after Fire Freeze's time.").withDependency { otherDropdown && fireFreezeHud.enabled }
    private val showPrefix by BooleanSetting("Show Prefix", true, desc = "Shows Fire Freeze's prefix.").withDependency { otherDropdown && fireFreezeHud.enabled }

    private val YELLOW = Color(0xFFFFFF55.toInt())
    private val GREEN = Color(0xFF55FF55.toInt())
    private val GOLD = Color(0xFFFFAA00.toInt())
    private val RED = Color(0xFFFF5555.toInt())

    private const val TERM_START_TICKS = 60
    private const val STORM_DOWN_TICKS = 580
    private const val CAMP_TICKS = 50 * 20
    private const val TAC_RETURN = 60
    private const val PORTAL_TICKS = 4 * 20
    private const val PORTAL_TEXT = 36
    private const val EXIT_CORE_TEXT = 16
    private const val MAXOR_MOVE = 85
    private const val CRYSTALS_BACK = 40
    private const val NECRON_DPS = 89
    private const val PAD_LEAD = 41
    private const val PURPLE_AFTER_LIGHTNING = 109
    private const val GIGA_STEP = 25
    private const val GIGA_AFTER_ONE = 20
    /** The longest countdown seen starts at 6. */
    private const val GIGA_TOTAL = 5 * GIGA_STEP + GIGA_AFTER_ONE
    private const val PAD_TOTAL = 60
    /** Maxor's health once his armour is off: a laser hit. Flickers within one stun are one hit. */
    private const val DAMAGEABLE = 500f
    private const val HIT_DEBOUNCE = 30
    private const val STORM_SHOW = 40
    private const val FIRE_FREEZE = 160

    private const val WATCHER = "[BOSS] The Watcher: "
    private const val WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass."
    private const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val STORM_ENRAGED = "⚠ Storm is enraged! ⚠"
    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private val STORM_LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")
    private val STORM_CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_OPEN = "The Core entrance is opening!"
    private val NECRON_START = setOf(
        "[BOSS] Necron: You went further than any human before, congratulations.",
        "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
    )
    private const val FIRE_FREEZE_LINE = "[BOSS] The Professor: Oh? You found my Guardians' one weakness?"

    /** 1 Maxor, 2 Storm, 3 terminals, 4 after; 0 outside the boss. */
    private var phase = 0
    private var maxorTicks = 0
    private var stormTicks = 0
    private var stormDead = false
    private var goldorTicks = 0
    private var termStartLeft = TERM_START_TICKS
    private var stormHurt = false

    private var serverTicks = 0
    /** Server tick the Clear Countdown reaches 0 on; null when it isn't showing. */
    private var bossAt: Int? = null
    private var bloodSeen = false
    private var portalOpen = false
    private var portalUntil = 0
    /** From the core opening until everyone is in it. */
    private var coreOpen = false
    private var exitCoreUntil = 0
    private var maxorMoveAt: Int? = null
    private var inMaxor = false
    private var crystalsAt: Int? = null
    private var lastHit = Int.MIN_VALUE / 2
    private var maxorHealth = 0f
    /** A tick early, to line up with the crush checks. */
    private var stormStart: Int? = null
    private var stormDiedTicks = 0
    private var stormShownUntil = 0
    private var lightningAt: Int? = null
    private var crushes = 0
    /** When Purple's pillar last stepped down. */
    private var purpleStepAt: Int? = null
    private var necronDpsAt: Int? = null
    private var gigaAt: Int? = null
    private var tacReturnAt: Int? = null
    /** The section time last dinged for (TermInfo.sectionDoneAt). */
    private var dingedAt = 0L
    private var fireFreezeTime = -1

    private fun fmt(ticks: Int) = fmt(ticks / 20.0)
    private fun fmt(seconds: Double) = String.format(Locale.ROOT, "%.2f", seconds)

    private fun maxorText(): Pair<String, Color>? =
        if (maxorOn && phase == 1) fmt(maxorTicks) to maxorColor else null

    private fun stormText(): Pair<String, Color>? {
        if (!stormOn || phase != 2 || stormDead) return null
        if (!stormDown) return fmt(stormTicks) to stormColor
        // ADM's count down: only the last 5 s before 29 s.
        return if (STORM_DOWN_TICKS - stormTicks in 0..100) fmt(29.0 - stormTicks / 20.0) to stormColor else null
    }

    private fun termStartText(): Pair<String, Color>? =
        if (termStartOn && phase == 2 && stormHurt && termStartLeft > 0) fmt(termStartLeft) to YELLOW else null

    private fun goldorText(): Pair<String, Color>? {
        if (!goldorOn || phase != 3) return null
        val seconds = goldorTicks / 20.0
        val mod = seconds % 3
        val shown = if (!deathTicks) seconds else if (goldorUp) mod else 3 - mod
        return fmt(shown) to if (mod < 1) GREEN else if (mod < 2) GOLD else RED
    }

    private fun current() = maxorText() ?: stormText() ?: termStartText() ?: goldorText()

    // Centred in a fixed width, as ADM draws them, so the HUD doesn't move as digits change.
    private fun GuiGraphicsExtractor.draw(s: String, color: Color): Pair<Int, Int> {
        text(s, (WIDTH - mc.font.width(s)) / 2, 0, color, true)
        return WIDTH to 9
    }

    private const val WIDTH = 30

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

    private fun time(ticks: Int): String = String.format(Locale.ROOT, "%.2fs", ticks / 20.0)

    /** The time left; [label] only in the HUD editor ([example]), to tell the timers apart there. */
    private fun GuiGraphicsExtractor.timer(label: String, left: Int, total: Int, example: Boolean = false): Pair<Int, Int> =
        draw((if (example) "§7$label: " else "") + colour(left, total) + time(left))

    private fun GuiGraphicsExtractor.draw(s: String): Pair<Int, Int> {
        text(s, 0, 0, Colors.WHITE, shadow = true)
        return mc.font.width(s) to 9
    }

    private fun formatTimer(time: Int, max: Int, prefix: String): String {
        val timeDisplay = if (displayInTicks) "$time${if (symbolDisplay) "t" else ""}" else "${(time / 20f).toFixed()}${if (symbolDisplay) "s" else ""}"
        return "${if (showPrefix) "$prefix " else ""}${colour(time, max)}$timeDisplay"
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
            message == MAXOR_START -> {
                phase = 1; maxorTicks = 0
                inMaxor = true; maxorMoveAt = serverTicks + MAXOR_MOVE; maxorHealth = 0f
            }
            message == STORM_START -> {
                phase = 2; stormTicks = 0; stormDead = false
                inMaxor = false; crystalsAt = null; stormStart = serverTicks - 1
                lightningAt = null; crushes = 0; purpleStepAt = null
            }
            message == STORM_DEAD -> stormDead = true
            message == STORM_ENRAGED -> {
                val start = stormStart
                if (stormDeathHud.enabled && start != null && !stormDead) {
                    stormDiedTicks = serverTicks - start - 1
                    stormShownUntil = serverTicks + STORM_SHOW
                    EngineerClient.chat("§aStorm died at: §e" + String.format(java.util.Locale.ROOT, "%.2f", stormDiedTicks / 20.0) + "s§a.")
                }
            }
            message in STORM_LIGHTNING -> if (stormStart != null && lightningAt == null) lightningAt = serverTicks
            message in STORM_CRUSHED -> if (stormStart != null) crushes++
            message == GOLDOR_START -> { phase = 3; goldorTicks = 0; stormStart = null }
            message == CORE_OPEN -> { phase = 4; coreOpen = true }
            message in NECRON_START -> if (necronDpsAt == null) necronDpsAt = serverTicks + NECRON_DPS
            message == FIRE_FREEZE_LINE -> fireFreezeTime = FIRE_FREEZE
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

    private fun sectionDing() {
        val at = TermInfo.sectionDoneAt
        if (at == dingedAt) return
        dingedAt = at
        if (at == 0L || !sectionDing || !sectionHud.enabled || TermInfo.sectionTime(sectionSeconds) == null) return
        playSoundAtPlayer(SoundEvents.NOTE_BLOCK_BELL.value(), 1f, 1.5f)
    }

    /** Everyone alive in the core, others where the server last put them; a ghost can be anywhere, so the dead count as in. */
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

    /** The P3 Sim stopped or a practice started: the phase timers and Fire Freeze as if no boss line had come. */
    fun stopAll() {
        phase = 0; maxorTicks = 0; stormTicks = 0; stormDead = false; goldorTicks = 0
        termStartLeft = TERM_START_TICKS; stormHurt = false
        fireFreezeTime = -1
    }

    private fun reset() {
        stopAll()
        tacReturnAt = null
        bossAt = null; bloodSeen = false; portalOpen = false; portalUntil = 0; coreOpen = false; exitCoreUntil = 0
        maxorMoveAt = null; inMaxor = false; crystalsAt = null; lastHit = Int.MIN_VALUE / 2; maxorHealth = 0f
        stormStart = null; necronDpsAt = null; gigaAt = null
        stormDiedTicks = 0; stormShownUntil = 0
        lightningAt = null; crushes = 0; purpleStepAt = null
    }

    init {
        clearHud.enabled = true; portalHud.enabled = true
        maxorMoveHud.enabled = true; crystalHud.enabled = true
        necronHud.enabled = true; purplePadHud.enabled = true; gigaHud.enabled = true; stormDeathHud.enabled = true; sectionHud.enabled = true

        on<MessageEvent.Chat> { chat(message) }

        on<TickEvent.Server> {
            serverTicks++
            when (phase) {
                1 -> maxorTicks++
                2 -> if (!stormDead) stormTicks++
                3 -> goldorTicks++
            }
            if (stormHurt && termStartLeft > 0) termStartLeft--
            if (fireFreezeTime >= 0 && DungeonUtils.inBoss) fireFreezeTime--
            val at = bossAt ?: return@on
            // Blood not done by 50 s: hidden until the portal.
            if (!portalOpen && at - serverTicks <= PORTAL_TICKS) bossAt = null
            else if (at - serverTicks <= 0) bossAt = null
        }

        on<TickEvent.End> { watchMaxor(); watchCore(); sectionDing() }

        // Storm's death is a loud wither hurt; terminals open 3 s after it.
        onReceive<ClientboundSoundPacket> {
            if (!stormHurt && phase in 2..3 && sound.value() == SoundEvents.WITHER_HURT && volume == 15f && pitch == 1f) stormHurt = true
            if (sound.value() != SoundEvents.FLINTANDSTEEL_USE || kotlin.math.abs(pitch - 0.746f) > 0.01f) return@onReceive
            mc.execute { if (mc.player?.mainHandItem?.itemId == "TACTICAL_INSERTION") tacReturnAt = serverTicks + TAC_RETURN }
        }

        onReceive<ClientboundSetTitleTextPacket> {
            if (stormStart == null || !gigaHud.enabled) return@onReceive
            val n = text.string.noControlCodes.trim().toIntOrNull()?.takeIf { it in 1..6 } ?: return@onReceive
            gigaAt = serverTicks + (n - 1) * GIGA_STEP + GIGA_AFTER_ONE
            if (hideGigaTitle) it.cancel()
        }

        // Purple's pillar stepping down: its layers go through moving pistons (going back up, they don't).
        on<BlockUpdateEvent> {
            if (stormStart == null || updated.block != Blocks.MOVING_PISTON || pos.y !in StormCrush.FLOOR..StormCrush.TOP) return@on
            val purple = StormCrush.PILLARS[0]
            if (pos.x == purple.columnX && pos.z == purple.columnZ) purpleStepAt = serverTicks
        }

        on<LevelEvent.Load> { reset() }
    }
}
