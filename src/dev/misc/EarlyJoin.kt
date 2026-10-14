package com.devgineerclient.misc

import com.devgineerclient.DevgineerClient
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.ServerList
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket

/**
 * Joins the launch's quick-play server (Prism's "join server on launch") while the first resource load is still
 * running, instead of after it. Resolving, login, the Mojang session join and the whole configuration phase
 * (registries, tags, the pack offer) need no textures, so they happen under the loading overlay. Only the switch
 * to play is held until the load is done (EarlyJoinHoldMixin): the server's play packets wait in the socket, since
 * the connection stops reading at the end of configuration until the client switches over.
 *
 * QuickPlayMixin then skips vanilla's own connect after the load. -Ddc.earlyJoin=false turns it off.
 */
object EarlyJoin {
    /** The early connection was started; vanilla's quick play connect is skipped. */
    @JvmStatic var started = false
        private set
    private var held: Pair<ClientConfigurationPacketListenerImpl, ClientboundFinishConfigurationPacket>? = null

    fun install() {
        if (System.getProperty("dc.earlyJoin") == "false") return
        val args = FabricLoader.getInstance().getLaunchArguments(false)
        val address = args.indexOf("--quickPlayMultiplayer").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: return
        ClientLifecycleEvents.CLIENT_STARTED.register { mc -> if (!mc.isGameLoadFinished) connect(mc, address) }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            val (listener, packet) = held ?: return@register
            if (!mc.isGameLoadFinished) return@register
            held = null
            if (listener.isAcceptingMessages) listener.handleConfigurationFinished(packet)
        }
    }

    private fun connect(mc: Minecraft, address: String) = runCatching {
        // Like QuickPlay.joinMultiplayerWorld, but an unlisted server isn't saved (its name would be the untranslated
        // key: languages load with the resources).
        val list = ServerList(mc).apply { load() }
        val data = list.get(address) ?: ServerData(address, address, ServerData.Type.OTHER)
        ConnectScreen.startConnecting(JoinMultiplayerScreen(TitleScreen()), mc, ServerAddress.parseString(address), data, true, null)
        started = true
        DevgineerClient.logger.info("[dc] early join: connecting to {} during the resource load", address)
    }.onFailure { DevgineerClient.logger.warn("[dc] early join failed, quick play will connect after the load", it) }

    /** Called before the switch to play: true holds it (the caller cancels) until the resource load is done. */
    @JvmStatic
    fun hold(listener: ClientConfigurationPacketListenerImpl, packet: ClientboundFinishConfigurationPacket): Boolean {
        if (Minecraft.getInstance().isGameLoadFinished) return false
        held = listener to packet
        return true
    }

    /** Vanilla's quick play connect after the load is skipped while the early one is alive. */
    @JvmStatic
    fun skipQuickPlay() = started && Minecraft.getInstance().gui.screen() !is DisconnectedScreen
}
