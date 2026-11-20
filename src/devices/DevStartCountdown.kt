package com.engineerclient.devices

import com.adm.events.MessageEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.SubscribeWhile
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

    private const val PING_WINDOW = 40

    private var active = false
    private var ticksLeft = 0
    private val pingTicks = ArrayDeque<Int>()

    private fun start(ticks: Int) {
        if (!DungeonUtils.inBoss) return
        active = true; ticksLeft = ticks
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
        on<TickEvent.Server> {
            if (ticksLeft > 0) ticksLeft--
            pingTicks.addLast((ServerUtils.averagePing + 49) / 50)
            if (pingTicks.size > PING_WINDOW) pingTicks.removeFirst()
        }

        on<RenderExtractEvent> {
            if (!active) return@on
            // A click lands half a ping after it's sent and these ticks came half a ping late; when ping sits across a tick boundary, take the later tick.
            val ticks = ticksLeft - (pingTicks.maxOrNull() ?: 0)
            val text = if (ticks <= 0) "§a§lGO!" else "§b${String.format(Locale.ROOT, "%.2f", ticks / 20.0)}s"
            for (p in spots) drawText(text, p, 1.2f, false)
        }
        SubscribeWhile.add(this, { active = false; ticksLeft = 0; pingTicks.clear() }) {
            Devices.startCountdownOn && DungeonUtils.inBoss && DungeonUtils.floor?.floorNumber == 7
        }
    }
}
