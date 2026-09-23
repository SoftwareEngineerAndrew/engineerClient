package com.engineerclient.misc

import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.sendCommand
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.TransferState
import net.minecraft.client.multiplayer.resolver.ServerAddress

/**
 * Personal QoL: the very first title screen this JVM shows connects to Hypixel, and the first
 * world load after that sends /skyblock once things have had a moment to settle. Both flags are
 * in-memory only, never saved - "only the first time" is just "once per game launch", no config
 * plumbing needed to enforce it.
 *
 * Hooked directly to raw Fabric events rather than Odin's own TickEvent.End: that event is wired
 * to ClientTickEvents.END_LEVEL_TICK, which only fires once a world is loaded - it never fires at
 * the title screen, so a countdown built on it would sit at its starting value forever and never
 * reach zero. ScreenEvents.AFTER_INIT (fires once the title screen has actually finished
 * initializing, unlike Odin's BEFORE_INIT-based ScreenEvent.Open) makes the connect-delay
 * unnecessary entirely.
 */
object AutoJoinHypixel : Module(
    name = "Auto Join Hypixel",
    category = Category.custom("Blood Rush"),
    description = "First title screen this launch: connects to Hypixel. First world after that: sends /skyblock.",
) {
    private var hasConnectedToHypixel = false
    private var pendingSkyblockJoin = false
    private var ticksUntilSkyblock = -1

    private const val HYPIXEL_ADDRESS = "hypixel.net"
    private const val SKYBLOCK_DELAY_TICKS = 60 // ~3s, gives the lobby time to fully load in

    init {
        ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            if (!enabled || hasConnectedToHypixel || screen !is TitleScreen) return@register
            hasConnectedToHypixel = true
            pendingSkyblockJoin = true
            ConnectScreen.startConnecting(
                screen,
                client,
                ServerAddress.parseString(HYPIXEL_ADDRESS),
                ServerData("Hypixel", HYPIXEL_ADDRESS, ServerData.Type.OTHER),
                false,
                TransferState(emptyMap(), emptyMap(), false),
            )
        }

        on<LevelEvent.Load> {
            if (!enabled || !pendingSkyblockJoin) return@on
            ticksUntilSkyblock = SKYBLOCK_DELAY_TICKS
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (ticksUntilSkyblock < 0) return@register
            if (ticksUntilSkyblock-- == 0) {
                pendingSkyblockJoin = false
                sendCommand("skyblock")
            }
        }
    }
}
