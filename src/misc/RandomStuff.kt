package com.engineerclient.misc

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.engineerclient.terminals.sim.TermSimGUI
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.engineerclient.terminals.TerminalUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress

/**
 * Grab bag of small independent toggles that don't warrant their own module.
 * Each setting is self-contained; add more here rather than spinning up a
 * new module for a one-off QoL toggle. While it is on, the i4 Complete title ([I4Complete]) is too.
 */
object RandomStuff : Module(
    name = "Random Stuff",
    category = Category.custom("Engineer Client", 860, 10),
    description = "A collection of small unrelated QoL toggles.",
    key = null,
) {
    private val termCursorReset by BooleanSetting("Force Cursor Reset", false, desc = "Opening a terminal always puts the cursor in the middle of the screen. On Linux (Xwayland) the game sometimes leaves it where it was; this moves it again. Terminals (and /termsim) only, no other menu.")
    private val signEnterConfirms by BooleanSetting("Enter Confirms Sign", true, desc = "On a sign edit screen, Enter finishes it instead of starting a new line — so a Bazaar or Auction House search is type-and-Enter.")

    private val blessOnLeave by BooleanSetting("Bless On Party Leave", true, desc = "Sends \"bless\" in party chat whenever someone leaves the party.")

    // --- Startup and restart -------------------------------------------------------------------

    private val autoJoinSkyblock by BooleanSetting("Auto Join Skyblock", false, desc = "Each time you connect to Hypixel (Prism's join on launch, the server list...), /skyblock goes out right after you land, and again until you're on Skyblock.")
    private val skyblockDelay by NumberSetting("Skyblock Join Delay", 0.2, 0.0..3.0, 0.05, desc = "How long after landing on Hypixel the first /skyblock goes out.", unit = "s").withDependency { autoJoinSkyblock }

    private val partyLeaveRegex = Regex("^(?:\\[[^]]*?] ?)?\\w{1,16} has left the party\\.$")
    private const val BLESS_COOLDOWN_MS = 10_000L
    private var lastBless = 0L

    // Auto join state, per connection.
    private var pendingSkyblockJoin = false
    /** The connection Auto Join Skyblock last started on: a JOIN on it again is a server switch. */
    private var skyblockConnection: net.minecraft.network.Connection? = null
    private var ticksUntilSkyblock = -1
    private var attempts = 0

    /** The title screen's Join Hypixel button: connects to Hypixel (Auto Join Skyblock then takes it to Skyblock). */
    fun joinHypixel(screen: net.minecraft.client.gui.screens.Screen) = connect(screen)

    private fun connect(screen: net.minecraft.client.gui.screens.Screen) {
        ConnectScreen.startConnecting(
            screen,
            mc,
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

    private const val HYPIXEL_ADDRESS = "hypixel.net"
    // A retry sent while the transfer is under way gets "Please don't spam the command!", so they're far apart.
    private const val RETRY_TICKS = 60      // after the first, every 3s until we are on Skyblock
    private const val TRANSFER_TICKS = 60   // a server switch mid-way means the transfer is happening: give it 3s
    private const val MAX_ATTEMPTS = 5

    /**
     * Whether text drawn at pose scale [scale] should be sampled smoothly (ClickGuiTextMixin): only in
     * Odin's menu, and only where a font pixel doesn't land on a whole number of screen pixels.
     */
    fun smoothsGuiText(scale: Float): Boolean {
        if (mc.gui.screen() !== com.engineerclient.gui.AdmClickGui) return false
        val px = scale * mc.window.guiScale
        return kotlin.math.abs(px - kotlin.math.round(px)) > 0.01f
    }

    /**
     * Whether [key] should finish the open sign edit screen. Read by SignEnterMixin.
     *
     * Skyblock only, and that is the whole safety story: every sign edit screen you meet there is
     * a search box (Bazaar, Auction House, anything that asks for text), never a sign you are
     * writing four lines on. Off the island the vanilla behaviour is left alone, and even with
     * this on the arrow keys still move between lines.
     */
    fun signEnterFinishes(key: Int): Boolean =
        enabled && signEnterConfirms && LocationUtils.isInSkyblock &&
            (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER)

    /** Force Cursor Reset: whether it is on at all; read by Coffee Client's CursorReset. */
    fun forcesTermCursorReset(): Boolean = enabled && termCursorReset

    /** Force Cursor Reset: on, and a terminal is open now. */
    fun termCursorResetNow(): Boolean = forcesTermCursorReset() && inTerminal()

    /** A live terminal (Odin tracks the open one) or a practice term sim. */
    private fun inTerminal(): Boolean =
        TerminalUtils.currentTerm != null || mc.gui.screen() is TermSimGUI

    init {
        on<MessageEvent.Chat> {
            // At most once per [BLESS_COOLDOWN_MS]: a party filling and emptying (or someone joining and
            // leaving over and over) would otherwise have the server mute you for spam.
            if (blessOnLeave && partyLeaveRegex.matches(message) && System.currentTimeMillis() - lastBless >= BLESS_COOLDOWN_MS) {
                lastBless = System.currentTimeMillis(); sendCommand("pc bless")
            }
        }

        // Auto Join Skyblock: a new connection to Hypixel starts the countdown to the first /skyblock. Hypixel
        // moves you to Skyblock through the configuration phase (its resource pack comes then), which fires JOIN
        // again on the same connection: that is the transfer, not a new connection, so it only waits for it.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register { handler, _, client ->
            val ip = client.currentServer?.ip?.lowercase().orEmpty()
            // Any new connection drops what the last one left pending: a countdown cut short by a
            // disconnect must not carry on into the next world, Hypixel or not.
            pendingSkyblockJoin = false
            ticksUntilSkyblock = -1
            if (!enabled || !autoJoinSkyblock || !ip.contains("hypixel")) return@register
            val connection = handler.connection
            if (connection === skyblockConnection) {
                if (pendingSkyblockJoin && attempts > 0) ticksUntilSkyblock = TRANSFER_TICKS
                return@register
            }
            skyblockConnection = connection
            pendingSkyblockJoin = true
            attempts = 0
            ticksUntilSkyblock = (skyblockDelay * 20).toInt()
            // No delay: now, not on the first tick, which waits behind the lobby's first frame. As a packet:
            // Fabric's client commands aren't set up yet this early and would swallow it.
            if (ticksUntilSkyblock == 0) {
                attempts = 1
                handler.send(net.minecraft.network.protocol.game.ServerboundChatCommandPacket("skyblock"))
                ticksUntilSkyblock = RETRY_TICKS
            }
        }

        // A server switch after a /skyblock is the transfer under way. Lobby to Skyblock is the same dimension,
        // so no new world (LevelEvent.Load) comes: the respawn packet does.
        onReceive<net.minecraft.network.protocol.game.ClientboundRespawnPacket> {
            if (!enabled || !autoJoinSkyblock || !pendingSkyblockJoin || attempts == 0) return@onReceive
            ticksUntilSkyblock = TRANSFER_TICKS
        }

        // The lobby can ignore a command sent before it is ready, so /skyblock goes out [skyblockDelay]
        // after landing and then every 3 seconds until Odin sees the Skyblock scoreboard. On raw client
        // ticks: Odin's TickEvent.End only fires with a world loaded. Raw ticks also run with the module
        // off, so turning it (or the setting) off mid-countdown ends the countdown here.
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!pendingSkyblockJoin || ticksUntilSkyblock < 0) return@register
            if (!enabled || !autoJoinSkyblock || LocationUtils.isInSkyblock || attempts >= MAX_ATTEMPTS) { pendingSkyblockJoin = false; ticksUntilSkyblock = -1; return@register }
            if (client.player == null || ticksUntilSkyblock-- > 0) return@register
            attempts++
            sendCommand("skyblock")
            ticksUntilSkyblock = RETRY_TICKS
        }
    }
}
