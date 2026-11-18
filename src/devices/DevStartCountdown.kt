package com.engineerclient.devices

import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.utils.ServerUtils
import com.adm.utils.render.drawText
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.phys.Vec3
import java.util.Locale

/** Over devices 1-3 from Storm's death: the time until a click sent now lands once the devices work. */
object DevStartCountdown {
    /** Storm's death line to the first credit; Goldor's line comes at 62. */
    private const val START_TICKS = 60
    private const val SON_TICKS = 42
    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private const val SON = "[BOSS] Storm: At least my son died by your hands."
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private val spots = listOf(Vec3(110.5, 122.0, 91.5), Vec3(60.5, 134.0, 142.5), Vec3(-1.5, 122.0, 74.5))

    private var active = false
    private var ticksLeft = 0
    private var lastTick = 0L

    private fun start(ticks: Int) {
        if (!DungeonUtils.inBoss) return
        active = true; ticksLeft = ticks; lastTick = System.nanoTime()
    }

    fun install() {
        on<MessageEvent.Chat> {
            when (message) {
                STORM_DEAD -> start(START_TICKS)
                // Fixed 42 ticks after the death line: puts the count back on the server's ticks.
                SON -> start(START_TICKS - SON_TICKS)
                GOLDOR_START -> active = false
            }
        }
        on<TickEvent.Server> { if (ticksLeft > 0) { ticksLeft--; lastTick = System.nanoTime() } }
        on<LevelEvent.Load> { active = false }

        on<RenderExtractEvent> {
            if (!Devices.startCountdownOn || !active) return@on
            // A click reaches the server half a ping after it's sent, and the ticks counted here came half a ping late.
            val ms = ticksLeft * 50 - (System.nanoTime() - lastTick) / 1_000_000 - ServerUtils.averagePing
            val text = if (ms <= 0) "§a§lGO!" else "§b${String.format(Locale.ROOT, "%.2f", ms / 1000.0)}s"
            for (p in spots) drawText(text, p, 1.2f, false)
        }
        EventBus.subscribe(this)
    }
}
