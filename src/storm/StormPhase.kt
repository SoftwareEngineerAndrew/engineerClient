package com.engineerclient.storm

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
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.phys.AABB

/**
 * F7/M7 phase 2 (Storm): Storm Ticks, the server ticks since his phase started, optionally modulo
 * 20, only while Storm's split is running - his first line to Goldor's, the same lines the splits
 * use - and only while you are in his arena. His crush checks come every 20 server ticks from the
 * start of the phase, so with Modulo 20 on they land on the rollover to 0. (His crush hitbox at
 * each check is Sub Splits' Show Crush Info.)
 *
 * The phase starts when Storm's wither appears, one server tick before his first line (every
 * recording), so the count reads 1 as that line arrives. Server ticks are Odin's (one per ping the
 * server sends), so a lagging server slows the count down with it.
 */
object StormPhase : Module(
    name = "Storm Phase",
    category = Category.custom("Engineer Client", 860, 10),
    description = "F7 P2: a server-tick counter from the start of Storm's phase. His crush hitbox is Sub Splits' Show Crush Info.",
    key = null,
) {

    private val CONTROL_CODES = Regex("§.")

    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"

    /** Storm's arena: the boss rooms stack up, and his floor is the one between y 150 and 215. */
    private val ARENA = AABB(0.0, 150.0, 0.0, 140.0, 215.0, 145.0)

    private val modulo by BooleanSetting("Modulo 20", false, desc = "Show the count modulo 20 (0-19). The crush checks are every 20 server ticks from the start of the phase, so they land on the rollover to 0.")
    private val offset by NumberSetting("Tick Offset", 0, -10..10, 1, desc = "Shifts the count, and which ticks count as crush checks (Sub Splits' Show Crush Info too), by this many ticks - in case your own testing puts the checks on a different tick than the recorded runs did.", unit = "t")

    /** [offset], for Show Crush Info's checks (0 with this module off). */
    val checkOffset: Int get() = if (enabled) offset else 0

    private val ticksHud by HUD("Storm Ticks", "Server ticks since Storm's phase started.", true, 5, 30, 0.75f) { example ->
        if (example) return@HUD draw(this, if (modulo) 7 else 347)
        val count = count() ?: return@HUD 0 to 0
        if (mc.player?.let { ARENA.contains(it.position()) } != true) return@HUD 0 to 0
        draw(this, if (modulo) Math.floorMod(count, StormCrush.CHECK_PERIOD) else count)
    }

    // The clock: Odin's server ticks, counted here so the module works without the splits on.
    @Volatile private var serverTicks = 0
    /** The server tick Storm's phase started on (a tick before his first line); null outside it. */
    @Volatile private var phaseStart: Int? = null

    init {
        on<LevelEvent.Load> { serverTicks = 0; phaseStart = null }
        on<TickEvent.Server> { serverTicks++ }

        // Chat straight off the network, before any mod can hide it (the splits do the same).
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            when (content.string.replace(CONTROL_CODES, "")) {
                STORM_START -> phaseStart = serverTicks - 1
                GOLDOR_START -> phaseStart = null
            }
        }
    }

    /** Server ticks since the phase started (with [offset]), or null outside it. */
    private fun count(): Int? = phaseStart?.let { serverTicks - it + offset }

    private fun draw(gfx: GuiGraphicsExtractor, value: Int): Pair<Int, Int> {
        // The check tick itself in green, so the rollover stands out.
        val colour = if (modulo && value == 0) "§a" else "§f"
        val line = "§bStorm " + colour + value + if (modulo) "" else "t"
        gfx.text(line, 0, 0, Colors.WHITE, shadow = true)
        return mc.font.width(line) to 10
    }
}
