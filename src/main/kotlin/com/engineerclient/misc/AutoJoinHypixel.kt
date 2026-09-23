package com.engineerclient.misc

import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.sendCommand
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
 * The actual connect (and the skyblock command) are each delayed a few ticks past their trigger
 * rather than fired synchronously from the event: ScreenEvent.Open for the title screen fires on
 * Fabric's BEFORE_INIT, before the screen has finished setting itself up, and calling
 * mc.setScreen from inside that could race the title screen's own init.
 */
object AutoJoinHypixel : Module(
    name = "Auto Join Hypixel",
    category = Category.custom("Blood Rush"),
    description = "First title screen this launch: connects to Hypixel. First world after that: sends /skyblock.",
) {
    private var hasConnectedToHypixel = false
    private var pendingSkyblockJoin = false
    private var ticksUntilConnect = -1
    private var ticksUntilSkyblock = -1

    private const val HYPIXEL_ADDRESS = "hypixel.net"
    private const val CONNECT_DELAY_TICKS = 5 // let the title screen finish its own init first
    private const val SKYBLOCK_DELAY_TICKS = 60 // ~3s, gives the lobby time to fully load in

    init {
        on<ScreenEvent.Open> {
            if (!enabled || hasConnectedToHypixel || screen !is TitleScreen) return@on
            hasConnectedToHypixel = true
            ticksUntilConnect = CONNECT_DELAY_TICKS
        }

        on<LevelEvent.Load> {
            if (!pendingSkyblockJoin) return@on
            ticksUntilSkyblock = SKYBLOCK_DELAY_TICKS
        }

        on<TickEvent.End> {
            if (ticksUntilConnect >= 0 && ticksUntilConnect-- == 0) {
                val screen = mc.screen ?: return@on
                pendingSkyblockJoin = true
                ConnectScreen.startConnecting(
                    screen,
                    mc,
                    ServerAddress.parseString(HYPIXEL_ADDRESS),
                    ServerData("Hypixel", HYPIXEL_ADDRESS, ServerData.Type.OTHER),
                    false,
                    TransferState(emptyMap(), emptyMap(), false),
                )
            }

            if (ticksUntilSkyblock >= 0 && ticksUntilSkyblock-- == 0) {
                pendingSkyblockJoin = false
                sendCommand("skyblock")
            }
        }
    }
}
