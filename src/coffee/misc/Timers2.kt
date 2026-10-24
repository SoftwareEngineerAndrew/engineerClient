package com.coffeeclient.misc

import com.coffeeclient.CoffeeClient
import com.coffeeclient.CoffeeClient.mc
import com.engineerclient.ClassDetect
import com.engineerclient.DeadPlayers
import com.engineerclient.practice.TermInfo
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.sounds.SoundEvents
import java.util.Locale

/**
 * Storm's countdowns, in server ticks so lag doesn't run them down (they were Engineer Client's Timers'):
 *  - Storm Crush Check: the next crush check, every 20 ticks from the phase starting (a tick before his
 *    first line) until he dies.
 *  - Storm Lightning: his Giga Lightning strikes 520 ticks into the phase (519-521 in 662 F7 and 371 M7
 *    runs since the boss update), 33 after "I'd be happy to show you what that's like!", which sets it
 *    again if his first line was missed.
 * Green / yellow / red by the share of the wait still to go, as Odin's tick timers are.
 *
 * And Role Time: your P3 job's time by class (Engineer Client's ClassDetect), in real time from Goldor's
 * first line, shown in orange for 1.5 s once done, as Section Time is:
 *  - Mage: to standing on the EE2 spot (none if S3 starts first); then from everyone being in the core
 *    (the dead count as in) to Goldor's first hurt sound.
 *  - Berserk: to i4 (S4's device) being done, as Term Info counts devices (an early one too).
 *  - Archer, Tank: to your 2nd terminal in S1 (none if S2 starts first).
 *  - Healer: to Simon Says (S1's device) being done.
 */
object Timers2 : Module(
    name = "Timers2",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "Storm's countdowns (his next crush check, his Giga Lightning) and your P3 job's time by class."
) {
    private val stormCheckHud by HUD("Storm Crush Check", "Counts down to Storm's next crush check, once a second through his phase.", true, 10, 130, 1.5f) { example ->
        val start = stormStart
        when {
            example -> timer(crush, 12, true)
            start == null || stormDead -> 0 to 0
            else -> timer(crush, CRUSH_PERIOD - Math.floorMod(serverTicks - start, CRUSH_PERIOD))
        }
    }
    private val stormLightningHud by HUD("Storm Lightning", "Counts down to Storm's Giga Lightning striking, 26 s into his phase.", true, 10, 145, 1.5f) { example ->
        val at = strikeAt
        when {
            example -> timer(lightning, 300, true)
            stormDead || at == null -> 0 to 0
            else -> (at - serverTicks).takeIf { it in 1..LIGHTNING }?.let { timer(lightning, it) } ?: (0 to 0)
        }
    }
    private val roleHud by HUD("Role Time", "Your class's P3 job, how long it took from Goldor's first line, in orange: Mage to EE2 and core to Goldor's first hit, Berserk to i4, Archer and Tank to their 2nd S1 term, Healer to SS.", true, 420, 270, 5f) { example ->
        val s = if (example) "§67.85" else shown?.takeIf { System.currentTimeMillis() - it.second <= ROLE_SHOWN_MS }?.first ?: return@HUD 0 to 0
        text(s, 0, 0, Colors.WHITE, shadow = true)
        mc.font.width(s) to 9
    }

    private const val CRUSH_PERIOD = 20
    private const val LIGHTNING = 520
    private const val STRIKE_AFTER_SHOW = 33
    private const val ROLE_SHOWN_MS = 1500L

    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private const val STORM_SHOW = "[BOSS] Storm: I'd be happy to show you what that's like!"
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_OPENING = "The Core entrance is opening!"
    private val TERMINAL = Regex("^(\\w{1,16}) activated a terminal! \\(\\d+/\\d+\\)$")
    private val CONTROL_CODES = Regex("§.")
    /** The EE2 spot, on top by the S2 gate (P3 Sim's EE2). */
    private const val EE2_X = 60.5
    private const val EE2_Y = 132.0
    private const val EE2_Z = 139.0

    private var serverTicks = 0
    private var stormStart: Int? = null
    private var stormDead = false
    /** When Giga Lightning strikes. */
    private var strikeAt: Int? = null

    private enum class Job { EE2, I4, S1_TERMS, SS, GOLDOR_HIT }

    /** The jobs being timed, from when (real ms). Made and dropped on the client thread; the network thread finishes one. */
    private val running: MutableMap<Job, Long> = java.util.concurrent.ConcurrentHashMap()
    /** The last time done and when it was, for [ROLE_SHOWN_MS]. */
    @Volatile private var shown: Pair<String, Long>? = null
    @Volatile private var myTerms = 0
    @Volatile private var coreOpen = false

    private fun colour(left: Int, total: Int): String = when {
        left >= total * 0.66f -> "§a"
        left >= total * 0.33f -> "§6"
        else -> "§c"
    }

    private fun timeLeft(left: Int, total: Int): String = colour(left, total) + String.format(Locale.ROOT, "%.2fs", left / 20.0)

    /**
     * A countdown HUD's label and length, and its text for the ticks left it was last drawn at: those change once a
     * tick, the HUD draws every frame. One each, as the crush check and the lightning show together.
     */
    private class Countdown(val label: String, val total: Int) {
        private var left = 0
        private var text = ""

        fun text(left: Int): String {
            if (left != this.left) { text = timeLeft(left, total); this.left = left }
            return text
        }
    }
    private val crush = Countdown("Crush", CRUSH_PERIOD)
    private val lightning = Countdown("Lightning", LIGHTNING)

    /** The time left; the label only in the HUD editor ([example]), to tell the timers apart there. */
    private fun GuiGraphicsExtractor.timer(countdown: Countdown, left: Int, example: Boolean = false): Pair<Int, Int> {
        val s = if (example) "§7${countdown.label}: " + timeLeft(left, countdown.total) else countdown.text(left)
        text(s, 0, 0, Colors.WHITE, shadow = true)
        return mc.font.width(s) to 9
    }

    private fun resetRoles() {
        running.clear(); myTerms = 0; coreOpen = false
    }

    private fun startRoles() {
        resetRoles()
        val now = System.currentTimeMillis()
        when (ClassDetect.myClass()) {
            DungeonClass.MAGE -> running[Job.EE2] = now
            DungeonClass.BERSERK -> running[Job.I4] = now
            DungeonClass.ARCHER, DungeonClass.TANK -> running[Job.S1_TERMS] = now
            DungeonClass.HEALER -> running[Job.SS] = now
            else -> {}
        }
    }

    private fun finish(job: Job) {
        val from = running.remove(job) ?: return
        shown = "§6${String.format(Locale.ROOT, "%.2f", (System.currentTimeMillis() - from) / 1000.0)}" to System.currentTimeMillis()
    }

    private fun tickRoles() {
        if (running.isEmpty() && !coreOpen) return
        val section = TermInfo.sectionNumber()
        if (Job.EE2 in running) {
            val p = mc.player
            if (p != null && p.onGround() && Math.abs(p.y - EE2_Y) < 0.6 && Math.hypot(p.x - EE2_X, p.z - EE2_Z) <= 1.0) finish(Job.EE2)
            else if (section != null && section >= 3) running.remove(Job.EE2)
        }
        if (Job.S1_TERMS in running && section != null && section >= 2) running.remove(Job.S1_TERMS)
        if (Job.I4 in running && TermInfo.deviceDone(4)) finish(Job.I4)
        if (Job.SS in running && TermInfo.deviceDone(1)) finish(Job.SS)
        if (coreOpen && ClassDetect.myClass() == DungeonClass.MAGE && everyoneInCore()) {
            coreOpen = false
            running[Job.GOLDOR_HIT] = System.currentTimeMillis()
        }
    }

    /** You and every teammate alive in the inner chamber (Engineer Client's core box); the dead count as in. */
    private fun everyoneInCore(): Boolean {
        val level = mc.level ?: return false
        val me = mc.player ?: return false
        if (!DeadPlayers.isDead(me.name.string) && !inCore(me.x, me.y, me.z)) return false
        for (mate in DungeonUtils.dungeonTeammates) {
            if (DeadPlayers.isDead(mate) || mate.name == me.name.string) continue
            val p = mate.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == mate.name } ?: return false
            val at = p.positionCodec.base
            if (!inCore(at.x, at.y, at.z)) return false
        }
        return true
    }

    private fun inCore(x: Double, y: Double, z: Double) = x >= 39 && x < 71 && y >= 110 && y < 155.5 && z >= 54 && z < 118

    init {
        on<MessageEvent.Chat> {
            when (message) {
                STORM_START -> { stormStart = serverTicks - 1; stormDead = false; strikeAt = serverTicks - 1 + LIGHTNING }
                STORM_SHOW -> strikeAt = serverTicks + STRIKE_AFTER_SHOW
                STORM_DEAD -> stormDead = true
                GOLDOR_START -> { stormStart = null; strikeAt = null }
            }
        }
        // Ahead of Term Info (1000): a 2nd terminal that ends S1 is counted before Term Info moves on to S2.
        onReceive<ClientboundSystemChatPacket>(priority = 1001, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(CONTROL_CODES, "")
            when {
                msg == GOLDOR_START -> mc.execute { CoffeeClient.safely("role time start") { startRoles() } }
                msg == CORE_OPENING -> coreOpen = true
                Job.S1_TERMS in running -> {
                    val m = TERMINAL.matchEntire(msg) ?: return@onReceive
                    if (m.groupValues[1] == mc.user.name && TermInfo.sectionNumber() == 1 && ++myTerms >= 2) finish(Job.S1_TERMS)
                }
            }
        }
        onReceive<ClientboundSoundPacket> {
            if (sound.value() == SoundEvents.WITHER_HURT) finish(Job.GOLDOR_HIT)
        }
        on<TickEvent.Server> { serverTicks++ }
        on<TickEvent.End> { CoffeeClient.safely("role time") { tickRoles() } }
        on<LevelEvent.Load> { stormStart = null; stormDead = false; strikeAt = null; resetRoles(); shown = null }
    }
}
