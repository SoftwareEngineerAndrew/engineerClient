package com.coffeeclient.misc

import com.coffeeclient.CoffeeClient
import com.coffeeclient.CoffeeClient.mc
import com.engineerclient.ClassDetect
import com.engineerclient.DeadPlayers
import com.engineerclient.practice.TermInfo
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.sounds.SoundEvents
import java.util.Locale

/**
 * Your P3 job's time, by your class (Engineer Client's ClassDetect), in real time from Goldor's first line:
 *  - Mage: to standing on the EE2 spot (gone if S3 starts first); then from everyone being in the core
 *    (the dead count as in) to Goldor's first hurt sound.
 *  - Berserk: to i4 (S4's device) being done, as Term Info counts devices (an early one too).
 *  - Archer, Tank: to your 2nd terminal in S1 (gone if S2 starts first).
 *  - Healer: to Simon Says (S1's device) being done.
 * Each shows while it runs and for Show Done For after; with Chat Message it's said in chat too.
 */
object RoleTimes : Module(
    name = "Role Times",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "Your P3 job's time by class: Mage to EE2 and core to Goldor's first hit, Berserk to i4, Archer and Tank to their 2 S1 terms, Healer to SS."
) {
    private val chatMessage by BooleanSetting("Chat Message", true, desc = "Also says each time in chat when it's done.")
    private val doneFor by NumberSetting("Show Done For", 5.0, 1.0..30.0, 1.0, desc = "How long a finished time stays up.", unit = "s")

    private val hud by HUD("Role Times", "Your class's P3 times: counting up while they run, green once done.", true, 10, 190, 1.5f) { example ->
        val lines = if (example) listOf(line("EE2", 7850, true), line("Goldor Hit", 1200, false))
        else {
            val now = System.currentTimeMillis()
            timers.filter { t -> t.doneMs.let { it == null || now - it <= doneFor * 1000 } }
                .map { t -> line(t.label, (t.doneMs ?: now) - t.startMs, t.doneMs != null) }
        }
        var w = 0
        for ((i, s) in lines.withIndex()) {
            text(s, 0, i * 10, Colors.WHITE, shadow = true)
            w = maxOf(w, mc.font.width(s))
        }
        w to lines.size * 10
    }

    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    /** On by default, once (turning it off sticks). */
    fun enableByDefault() {
        if (switchedOn) return
        switchedOn = true
        if (!enabled) toggle()
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }

    private fun line(label: String, ms: Long, done: Boolean) =
        "§7$label: ${if (done) "§a" else "§f"}${String.format(Locale.ROOT, "%.2fs", ms / 1000.0)}"

    private class Timer(val label: String, val startMs: Long) {
        @Volatile var doneMs: Long? = null
        var said = false
    }

    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_OPENING = "The Core entrance is opening!"
    private val MY_TERMINAL = Regex("^(\\w{1,16}) activated a terminal! \\(\\d+/\\d+\\)$")
    private val CONTROL_CODES = Regex("§.")
    /** The EE2 spot, on top by the S2 gate (P3 Sim's EE2). */
    private const val EE2_X = 60.5
    private const val EE2_Y = 132.0
    private const val EE2_Z = 139.0

    /** Shown this P3, in order. Made and dropped on the client thread; the network thread only finishes them. */
    @Volatile private var timers: List<Timer> = emptyList()
    @Volatile private var ee2: Timer? = null
    @Volatile private var s1Terms: Timer? = null
    @Volatile private var i4: Timer? = null
    @Volatile private var ss: Timer? = null
    @Volatile private var goldorHit: Timer? = null
    @Volatile private var myTerms = 0
    @Volatile private var coreOpen = false

    private fun reset() {
        timers = emptyList(); ee2 = null; s1Terms = null; i4 = null; ss = null; goldorHit = null
        myTerms = 0; coreOpen = false
    }

    private fun add(label: String, now: Long) = Timer(label, now).also { timers = timers + it }

    private fun drop(t: Timer?) {
        if (t != null) timers = timers - t
    }

    private fun start() {
        reset()
        val now = System.currentTimeMillis()
        when (ClassDetect.myClass()) {
            DungeonClass.MAGE -> ee2 = add("EE2", now)
            DungeonClass.BERSERK -> i4 = add("i4", now)
            DungeonClass.ARCHER, DungeonClass.TANK -> s1Terms = add("S1 Terms", now)
            DungeonClass.HEALER -> ss = add("SS", now)
            else -> {}
        }
    }

    private fun finish(t: Timer?) {
        if (t != null && t.doneMs == null) t.doneMs = System.currentTimeMillis()
    }

    private fun tick() {
        if (timers.isEmpty() && !coreOpen) return
        val section = TermInfo.sectionNumber()
        ee2?.let { t ->
            if (t.doneMs != null) return@let
            val p = mc.player
            if (p != null && p.onGround() && Math.abs(p.y - EE2_Y) < 0.6 && Math.hypot(p.x - EE2_X, p.z - EE2_Z) <= 1.0) finish(t)
            else if (section != null && section >= 3) { drop(t); ee2 = null }
        }
        s1Terms?.let { t -> if (t.doneMs == null && section != null && section >= 2) { drop(t); s1Terms = null } }
        i4?.let { if (TermInfo.deviceDone(4)) finish(it) }
        ss?.let { if (TermInfo.deviceDone(1)) finish(it) }
        if (coreOpen && ClassDetect.myClass() == DungeonClass.MAGE && everyoneInCore()) {
            coreOpen = false
            goldorHit = add("Goldor Hit", System.currentTimeMillis())
        }
        if (chatMessage) for (t in timers) {
            val done = t.doneMs ?: continue
            if (t.said) continue
            t.said = true
            CoffeeClient.msg("§b${t.label}: §a${String.format(Locale.ROOT, "%.2fs", (done - t.startMs) / 1000.0)}")
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
        // Ahead of Term Info (1000): a 2nd terminal that ends S1 is counted before Term Info moves on to S2.
        onReceive<ClientboundSystemChatPacket>(priority = 1001, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(CONTROL_CODES, "")
            when {
                msg == GOLDOR_START -> mc.execute { CoffeeClient.safely("role times start") { start() } }
                msg == CORE_OPENING -> coreOpen = true
                else -> s1Terms?.let { t ->
                    val m = MY_TERMINAL.matchEntire(msg) ?: return@let
                    if (m.groupValues[1] != mc.user.name || TermInfo.sectionNumber() != 1) return@let
                    if (++myTerms >= 2) finish(t)
                }
            }
        }
        onReceive<ClientboundSoundPacket> {
            if (sound.value() == SoundEvents.WITHER_HURT) finish(goldorHit)
        }
        on<TickEvent.End> { CoffeeClient.safely("role times") { tick() } }
        on<LevelEvent.Load> { reset() }
    }
}
