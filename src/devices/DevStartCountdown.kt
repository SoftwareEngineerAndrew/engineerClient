package com.engineerclient.devices

import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.utils.ServerUtils
import com.adm.utils.render.drawText
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3
import java.util.Locale

/** Over devices 1-3 from Storm's death: the time until a click sent now lands once the devices work. */
object DevStartCountdown {
    private const val START_TICKS = 60
    private val spots = listOf(Vec3(110.5, 122.0, 91.5), Vec3(60.5, 134.0, 142.5), Vec3(-1.5, 122.0, 74.5))

    private var ticksLeft = 0
    private var lastTick = 0L

    fun install() {
        // Storm's death is a loud wither hurt; the devices work 3 s after it.
        onReceive<ClientboundSoundPacket> {
            if (ticksLeft > 0 || sound.value() != SoundEvents.WITHER_HURT || volume != 15f || pitch != 1f) return@onReceive
            val phase = DungeonUtils.getF7Phase()
            if (DungeonUtils.inBoss && (phase == M7Phases.P2 || phase == M7Phases.P3)) { ticksLeft = START_TICKS; lastTick = System.nanoTime() }
        }
        on<TickEvent.Server> { if (ticksLeft > 0) { ticksLeft--; lastTick = System.nanoTime() } }
        on<MessageEvent.Chat> { if (message == "[BOSS] Goldor: Who dares trespass into my domain?") ticksLeft = 0 }
        on<LevelEvent.Load> { ticksLeft = 0 }

        on<RenderExtractEvent> {
            if (!Devices.startCountdownOn || ticksLeft <= 0) return@on
            // A click reaches the server half a ping after it's sent, and the ticks counted here came half a ping late.
            val ms = ticksLeft * 50 - (System.nanoTime() - lastTick) / 1_000_000 - ServerUtils.averagePing
            val text = if (ms <= 0) "§a§lGO!" else "§b${String.format(Locale.ROOT, "%.2f", ms / 1000.0)}s"
            for (p in spots) drawText(text, p, 1.2f, false)
        }
        EventBus.subscribe(this)
    }
}
