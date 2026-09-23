package com.engineerclient.misc

import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.LocationUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress

/**
 * Personal QoL: the very first title screen this JVM shows connects to Hypixel, and the lobby it
 * lands in sends /skyblock straight away - then again every couple of seconds until Odin sees the
 * Skyblock scoreboard (Hypixel ignores a command sent before the lobby is ready). Both flags are
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
    description = "First title screen this launch: connects to Hypixel, then gets you onto Skyblock as fast as possible.",
) {
    private var hasConnectedToHypixel = false
    private var pendingSkyblockJoin = false
    private var ticksUntilSkyblock = -1
    private var attempts = 0

    private const val HYPIXEL_ADDRESS = "hypixel.net"
    private const val FIRST_TRY_TICKS = 10  // 0.5s after the lobby loads
    private const val RETRY_TICKS = 40      // then every 2s until we are on Skyblock
    private const val TRANSFER_TICKS = 60   // a world load mid-way means a transfer is happening: give it 3s
    private const val MAX_ATTEMPTS = 6

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
                // null, not an empty TransferState: ConnectScreen$1.run() checks this for null to
                // decide whether to tell the server "this is a transfer" (initiateServerboundPlay-
                // Connection's transferConnection flag). A non-null value here - even an "empty"
                // one - declares an illegitimate transfer with nothing having actually transferred
                // us, which is exactly the "you cannot transfer to this server" rejection.
                null,
            )
        }

        on<LevelEvent.Load> {
            if (!enabled || !pendingSkyblockJoin) return@on
            ticksUntilSkyblock = if (attempts == 0) FIRST_TRY_TICKS else TRANSFER_TICKS
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!pendingSkyblockJoin || ticksUntilSkyblock < 0) return@register
            if (LocationUtils.isInSkyblock || attempts >= MAX_ATTEMPTS) { pendingSkyblockJoin = false; ticksUntilSkyblock = -1; return@register }
            if (client.player == null || ticksUntilSkyblock-- > 0) return@register
            attempts++
            sendCommand("skyblock")
            ticksUntilSkyblock = RETRY_TICKS
        }
    }
}
