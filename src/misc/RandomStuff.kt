package com.engineerclient.misc

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.RenderExtractEvent
import com.engineerclient.pf.HubNametags
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.FriendsButton
import net.minecraft.client.gui.components.PlainTextButton
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemDisplayContext

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
    /**
     * The inverse of the "Show Numbers" option older Odin versions had on Terminal Solver.
     * With it on the numbers terminal ("Click in order!") is drawn without its 1-10, so the only
     * thing to go on is the solver's three order colours - you click the colour, not the number.
     *
     * Two places draw those numbers, so two read this: [com.engineerclient.mixin.NumbersHandlerMixin]
     * for the text Odin itself draws (every render type, and the practice sim), and
     * [com.engineerclient.mixin.TerminalNumberMixin] for the vanilla stack count, which the Normal
     * render type still shows on the slots Odin hands back to vanilla.
     */
    private val hideTermNumbers by BooleanSetting("Hide Numbers' Numbers", false, desc = "In the numbers terminal (\"Click in order!\"), hides the numbers 1-10 so only the solver's order colours are left to go on. Odin's old Show Numbers, inverted. Applies to /termsim as well.")
    private val termCursorReset by BooleanSetting("Force Cursor Reset", false, desc = "Opening a terminal always puts the cursor in the middle of the screen. On Linux (Xwayland) the game sometimes leaves it where it was; this moves it again. Terminals (and /termsim) only, no other menu.")
    private val partyFinderStats by BooleanSetting("Party Finder Stats", true, desc = "In the Party Finder, each listed player's Catacombs level, secrets and S+ PB for the floor, and which classes the party is missing (yours in bold).")
    private val signEnterConfirms by BooleanSetting("Enter Confirms Sign", true, desc = "On a sign edit screen, Enter finishes it instead of starting a new line — so a Bazaar or Auction House search is type-and-Enter.")

    private val hideItemNames by BooleanSetting("Hide Item Names", false, desc = "Hides the item name that pops up above the hotbar when you switch to a different item.")
    private val hideActionBar by BooleanSetting("Hide Action Bar", false, desc = "Hides the entire action bar (the overlay text above the hotbar) — health/mana/defense text, level up messages, all of it.")

    private val blessOnLeave by BooleanSetting("Bless On Party Leave", true, desc = "Sends \"bless\" in party chat whenever someone leaves the party.")
    private val blackSky by BooleanSetting("Black Sky", true, desc = "Makes the sky (and distant fog) black instead of blue. Pairs with Sodium Extra's Sky toggle.")

    // --- Blur in GUI ---------------------------------------------------------------------------
    //
    // Vanilla blurs the world behind its own menus (pause, options, Odin's click GUI) but never
    // behind an in-world UI — and on Skyblock the in-world UIs are the ones that matter: a chest,
    // the Bazaar, the Auction House, your own inventory. This turns the same blur on for all of
    // them, and moves the cut so only the world is blurred: the HUD stays sharp behind the GUI,
    // which is the point, because the sidebar and Odin's map are still worth reading with a chest
    // open.
    //
    // It is the game's own box-blur post chain, so the cost is exactly what the pause menu costs
    // — six full-screen passes — and only for the frames a screen is actually open.
    private val blurInGui by BooleanSetting("Blur In GUI", false, desc = "Blurs the world behind any open GUI — a chest, the Bazaar, your inventory. The HUD and the GUI itself stay sharp.")
    private val blurStrength by NumberSetting("Blur Strength", 4, 1..10, 1, desc = "How far the blur reaches, in pixels. 10 is as far as the game's own blur shader goes.").withDependency { blurInGui }

    // --- Enchantment glint ---------------------------------------------------------------------
    //
    // Every Skyblock weapon, piece of armour and most of the junk in a dungeon inventory carries
    // enchantments, so the glint is on nearly everything at once — it washes out item colours in
    // the hotbar and turns a full inventory into a moving surface, which is exactly the wrong thing
    // to be reading a chest through at speed. On by default.
    //
    // Purely a render-side change: nothing here touches the stack, its components, or
    // [net.minecraft.world.item.ItemStack.hasFoil] itself. That matters, because Odin's terminal
    // solvers decide what has been clicked from the `ENCHANTMENT_GLINT_OVERRIDE` *component*
    // (`ItemUtils.hasGlint`), not from what is drawn — so Select All and Starts With keep solving
    // correctly no matter what is hidden here.
    //
    // Hooked at the two `ItemModel.update` implementations that set a foil type (see ItemFoilMixin)
    // and at worn-equipment rendering (see ArmorFoilMixin).
    private val noGlint by BooleanSetting("No Enchant Glint", true, desc = "Removes the enchantment glint from items, so colours and textures stay readable.")

    // --- Own arrows ----------------------------------------------------------------------------
    //
    // Arrows you fire yourself (any bow) are not drawn while still within 5 blocks of you, so a
    // shortbow spam does not cover the screen. Only arrows in flight: one stuck in a block draws as
    // usual, and an arrow in an item frame is an item, not an arrow entity, so it is never touched.
    // Hooked at EntityRenderDispatcher.shouldRender (see OwnArrowsHideMixin).
    private val hideOwnArrows by BooleanSetting("Hide Own Arrows Nearby", true, desc = "Arrows you shoot are not drawn while they fly within 5 blocks of you.")

    // --- Startup and restart -------------------------------------------------------------------

    private val autoJoinSkyblock by BooleanSetting("Auto Join Skyblock", false, desc = "Each time you connect to Hypixel (Prism's join on launch, the server list...), /skyblock goes out right after you land, and again until you're on Skyblock.")
    private val skyblockDelay by NumberSetting("Skyblock Join Delay", 0.2, 0.0..3.0, 0.05, desc = "How long after landing on Hypixel the first /skyblock goes out.", unit = "s").withDependency { autoJoinSkyblock }

    // --- Hub Nametag Stats ------------------------------------------------------------------------
    //
    // In the Dungeon Hub, a stat line over real players' nametags: Catacombs level, secrets and the
    // S+ PB on one floor (HubNametags draws it).

    private val hubNametags by BooleanSetting("Hub Nametag Stats", true, desc = "In the Dungeon Hub, Catacombs level, secrets and the S+ PB on the PB Floor over players' nametags.")
    private val pbFloor by SelectorSetting("PB Floor", HubNametags.PbFloor.F7, desc = "Which floor's S+ PB the nametag stats show.").withDependency { hubNametags }
    private val nametagGap by NumberSetting("Gap", 0f, 0.0..1.0, 0.05f, desc = "Extra space between a player's nametag and the stat line, in blocks.").withDependency { hubNametags }

    // --- Clean ---------------------------------------------------------------------------------
    //
    // Hypixel's sidebar carries a few lines nobody reads mid-run - the real-world date, the Skyblock
    // clock and season, and in dungeons the Keys and Cleared counters. ScoreboardLines does the
    // matching and the hiding; these settings only say what to hide. Clean Chat's rules are ChatHider's.

    private val cleanMenus by BooleanSetting("Clean Menus", true, desc = "Title screen: no Friends, Language or Accessibility buttons. Esc menu: no Advancements, Statistics, Server Links or icon row (bugs, feedback, friends, player reporting), and Options shares its row with a Mods button.")
    private val skipLoadingScreen by BooleanSetting("Clean Loading Screen", true, desc = "No \"Loading terrain\" screen when joining or changing world: the world shows as it loads in.")
    private val hideSbLines by BooleanSetting("Clean Scoreboard", false, desc = "Hides the noisy sidebar lines: date and server, clock, season, other locations, objective, Keys, pre-start countdown, Solo, www.hypixel.net, blank spacers and the title, plus the three below. Purse, Bits and teammates always stay. Off, the sidebar is left alone.")
    private val hideSbCatacombs by BooleanSetting("Scoreboard: Hide Catacombs Location", true, desc = "Hides the location line in dungeons: The Catacombs (F1-F7, M1-M7, E).").withDependency { hideSbLines }
    private val hideSbElapsed by BooleanSetting("Scoreboard: Hide Time Elapsed", false, desc = "Hides the dungeon's Time Elapsed line.").withDependency { hideSbLines }
    private val hideSbCleared by BooleanSetting("Scoreboard: Hide Cleared %", false, desc = "Hides the dungeon's Cleared: #% (#) line.").withDependency { hideSbLines }
    private val cleanChat by BooleanSetting("Clean Chat", true, desc = "Hides the chat lines its rules mark Block (built in, or config/engineerclient/chat-hider.json). Other mods still see every line.")
    private val logHidden by BooleanSetting("Log Hidden Lines", false, desc = "Writes every hidden line and the rule that hid it to the EC session log, for checking a rule.").withDependency { cleanChat }
    private val reloadChatRules by ActionSetting("Reload Rules", desc = "Re-reads config/engineerclient/chat-hider.json (or the built-in rules if that file is absent).") { com.engineerclient.chat.ChatHider.reload() }.withDependency { cleanChat }

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

    /** Skip Loading Screen: read by SkipLoadingScreenMixin on every world change. */
    fun skipsLoadingScreen(): Boolean = enabled && skipLoadingScreen

    /** Clean Menus: read by PauseMenuMixin, and below for the title screen. */
    fun cleansMenus(): Boolean = enabled && cleanMenus

    /** Clean Chat: read by ChatHider for every line the chat GUI is about to add. */
    fun cleansChat(): Boolean = enabled && cleanChat

    /** Clean Chat's Log Hidden Lines. */
    fun logsHiddenChat(): Boolean = logHidden

    /**
     * Whether text drawn at pose scale [scale] should be sampled smoothly (ClickGuiTextMixin): only in
     * Odin's menu, and only where a font pixel doesn't land on a whole number of screen pixels.
     */
    fun smoothsGuiText(scale: Float): Boolean {
        if (mc.gui.screen() !is com.odtheking.odin.clickgui.ClickGUI) return false
        val px = scale * mc.window.guiScale
        return kotlin.math.abs(px - kotlin.math.round(px)) > 0.01f
    }

    /** Mod Menu's mod list over [parent], or null without Mod Menu (the Esc menu's Options then stays full width). */
    fun modsScreen(parent: Screen): Screen? = runCatching {
        Class.forName("com.terraformersmc.modmenu.gui.ModsScreen").getConstructor(Screen::class.java).newInstance(parent) as Screen
    }.getOrNull()

    private val titleIconKeys = setOf("options.language", "options.accessibility", "accessibility.onboarding.accessibility.button")

    /** Takes the Friends/Language/Accessibility row off the title screen and closes the gap it leaves. */
    private fun cleanTitleScreen(screen: TitleScreen) {
        val widgets = Screens.getWidgets(screen)
        val icons = widgets.filter { it is FriendsButton || (it is Button && (it.message.contents as? TranslatableContents)?.key in titleIconKeys) }
        val row = icons.firstOrNull()?.y ?: return
        widgets.removeAll(icons)
        widgets.filter { it.y > row && it !is PlainTextButton }.forEach { it.y -= 24 }
    }

    /** Read by PartyFinderStats for every Party Finder tooltip. */
    fun showsPartyFinderStats(): Boolean = enabled && partyFinderStats

    /** Read by FogColorMixin every frame. */
    fun blackSkyActive(): Boolean = enabled && blackSky

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

    /**
     * Whether the glint should be dropped for an item about to be drawn in [context].
     *
     * Called once per item layer whenever a model is resolved — every frame for items in the
     * world, and on every miss of the GUI item atlas — so it stays a handful of field reads: no
     * allocation, no screen walk beyond the identity check Odin's terminal state already gives us.
     */
    fun hidesGlint(context: ItemDisplayContext): Boolean {
        if (!enabled || !noGlint) return false
        // The one exception, and it is not a toggle because turning it off only ever hurts: while a
        // terminal is open the glint is how you see which items you have already clicked. Odin's
        // solvers read the glint component rather than the render, so they are unaffected either way
        // - this is purely so a human can still tell.
        return !(context == ItemDisplayContext.GUI && inTerminal())
    }

    /** True while the numbers terminal's 1-10 should not be drawn at all. */
    @JvmStatic
    fun hidesTerminalNumbers(): Boolean = enabled && hideTermNumbers

    /** [arrow] is one the local player shot, still in flight ([inGround] false), within 5 blocks of them. */
    fun hidesOwnArrow(arrow: net.minecraft.world.entity.projectile.arrow.AbstractArrow, inGround: Boolean): Boolean {
        if (!enabled || !hideOwnArrows || inGround) return false
        val me = mc.player ?: return false
        return arrow.owner === me && arrow.distanceToSqr(me) < 25.0
    }

    /**
     * The same question for one slot of the open screen, so the vanilla count is only ever dropped
     * on the terminal's own slots - your inventory below it keeps its stack sizes.
     */
    @JvmStatic
    fun hidesTerminalNumber(slot: Slot): Boolean {
        if (!hidesTerminalNumbers()) return false
        val term = TerminalUtils.currentTerm ?: return false
        return term.type == TerminalTypes.NUMBERS && slot.index < term.type.windowSize
    }

    /** Whether the glint should be dropped for a piece of worn equipment. */
    fun hidesArmorGlint(): Boolean = enabled && noGlint

    /**
     * Whether the world behind the open screen should be blurred this frame.
     *
     * Read by GuiRenderStateBlurMixin (marks the blur), ScreenBlurMixin (drops vanilla's own
     * marker) and BlurRadiusMixin (radius). All three run in the same frame's extract pass on the
     * render thread, so they cannot disagree — which matters, because the game throws outright if
     * one frame is told to blur twice.
     */
    fun blursGui(): Boolean = enabled && blurInGui && mc.gui.screen() != null && mc.level != null

    /** Set by GuiBlurMixin while `Gui.extractRenderState` builds the frame's GUI (render thread only). */
    var buildingGui = false

    /** A GUI state reset now starts a state the frame will draw: it gets the blur line at stratum 0. */
    fun blursAfterReset(): Boolean = buildingGui && blursGui()

    /** Radius for [blursGui], on the same 1..10 scale as vanilla's Menu Background Blur slider. */
    fun blurRadius(): Int = blurStrength.toInt()

    /** Hide Item Names: read by GuiItemNameMixin. */
    fun hidesItemNames(): Boolean = enabled && hideItemNames

    /** Force Cursor Reset: whether it is on at all; read by Coffee Client's CursorReset. */
    fun forcesTermCursorReset(): Boolean = enabled && termCursorReset

    /** Force Cursor Reset: on, and a terminal is open now. */
    fun termCursorResetNow(): Boolean = forcesTermCursorReset() && inTerminal()

    /** A live terminal (Odin tracks the open one) or a practice term sim. */
    private fun inTerminal(): Boolean =
        TerminalUtils.currentTerm != null || mc.gui.screen() is TermSimGUI

    init {
        on<TickEvent.End> {
            ScoreboardLines.hideLines = enabled && hideSbLines
            ScoreboardLines.hideCatacombsLocation = hideSbCatacombs
            ScoreboardLines.hideTimeElapsed = hideSbElapsed
            ScoreboardLines.hideCleared = hideSbCleared
        }

        on<RenderExtractEvent> {
            if (enabled && hubNametags) HubNametags.render(this, pbFloor, nametagGap.toDouble())
        }

        on<MessageEvent.Overlay> {
            if (hideActionBar) cancel()
        }

        on<MessageEvent.Chat> {
            // At most once per [BLESS_COOLDOWN_MS]: a party filling and emptying (or someone joining and
            // leaving over and over) would otherwise have the server mute you for spam.
            if (blessOnLeave && partyLeaveRegex.matches(message) && System.currentTimeMillis() - lastBless >= BLESS_COOLDOWN_MS) {
                lastBless = System.currentTimeMillis(); sendCommand("pc bless")
            }
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is TitleScreen && cleansMenus()) cleanTitleScreen(screen)
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
        }

        on<LevelEvent.Unload> { ScoreboardLines.hideLines = false }

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

    // Switched off, the tick that writes the hider's flag stops running, so the sidebar is handed
    // back whole here: left on, the mixin would keep hiding lines for a module that is off.
    override fun onDisable() {
        super.onDisable()
        ScoreboardLines.hideLines = false
    }
}
