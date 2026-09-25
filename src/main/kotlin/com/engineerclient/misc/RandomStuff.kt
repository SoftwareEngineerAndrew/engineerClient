package com.engineerclient.misc

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RenderBossBarEvent
import com.odtheking.odin.events.RenderItemNameEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import com.odtheking.odin.features.impl.render.RenderOptimizer
import com.odtheking.odin.features.impl.skyblock.PlayerDisplay
import com.odtheking.odin.utils.alert
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.world.item.ItemDisplayContext

/**
 * Grab bag of small independent toggles that don't warrant their own module.
 * Each setting is self-contained; add more here rather than spinning up a
 * new module for a one-off QoL toggle.
 */
object RandomStuff : Module(
    name = "Random Stuff",
    category = Category.custom("Engineer Client"),
    description = "A collection of small unrelated QoL toggles."
) {
    /** Read by ChatHider at the chat GUI, so other mods still see every line. */
    val hideChat by BooleanSetting("Hide Chat", false, desc = "Hides all chat messages from the screen. Other mods still see them.")
    private val hideDamage by BooleanSetting("Hide Damage Indicators", false, desc = "Suppresses the red hurt-flash overlay when you take damage.")
    private val signEnterConfirms by BooleanSetting("Enter Confirms Sign", true, desc = "On a sign edit screen, Enter finishes it instead of starting a new line — so a Bazaar or Auction House search is type-and-Enter.")
    private val hideHealthManaUnlessLow by BooleanSetting("Hide Health/Mana Above %", false, desc = "Hides Odin's Health HUD and Mana HUD unless the stat drops below the threshold below. Only has an effect if those HUD elements are already enabled in Odin's Player Display settings.")
    private val healthManaThreshold by NumberSetting("Threshold", 20, 1, 100, 1, desc = "Only show the Health/Mana HUD once the stat drops below this percent of max.", unit = "%").withDependency { hideHealthManaUnlessLow }
    private val hideItemNames by BooleanSetting("Hide Item Names", false, desc = "Hides the item name that pops up above the hotbar when you switch to a different item.")
    private val hideActionBar by BooleanSetting("Hide Action Bar", false, desc = "Hides the entire action bar (the overlay text above the hotbar) — health/mana/defense text, level up messages, all of it.")
    private val hideArmorStands by BooleanSetting("Hide Armor Stands", false, desc = "In dungeons only: hides every armor stand (except terminals, active or inactive) and removes fishing bobbers' extended line.")
    private val blessOnLeave by BooleanSetting("Bless On Party Leave", false, desc = "Sends \"bless\" in party chat whenever someone leaves the party.")
    private val blackSky by BooleanSetting("Black Sky", false, desc = "Makes the sky (and distant fog) black instead of blue. Pairs with Sodium Extra's Sky toggle.")

    // --- Enchantment glint ---------------------------------------------------------------------
    //
    // Every Skyblock weapon, piece of armour and most of the junk in a dungeon inventory carries
    // enchantments, so the glint is on nearly everything at once — it washes out item colours in
    // the hotbar and turns a full inventory into a moving surface, which is exactly the wrong thing
    // to be reading a chest through at speed. On by default; it was its own always-on module before
    // it moved in here.
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

    // --- Startup and restart -------------------------------------------------------------------

    private val autoJoinHypixel by BooleanSetting("Auto Join Hypixel", false, desc = "First title screen this launch: connects to Hypixel, then gets you onto Skyblock as fast as possible.")

    // --- Scoreboard lines ----------------------------------------------------------------------
    //
    // Hypixel's sidebar carries a few lines nobody reads mid-run - the real-world date, the
    // Skyblock clock and season, and in dungeons the Keys and Cleared counters. ScoreboardLines
    // does the matching and the hiding; these settings only say what to hide.

    private val hideSbCustom by StringSetting("Scoreboard: Also Hide", "", 200, desc = "Extra sidebar lines to hide, separated by ;. A piece of the line is enough - run Dump Scoreboard and copy what you see. Regexes work too.")

    /**
     * Prints the sidebar to chat, exactly as the game assembles it, so the patterns above can be
     * built from real text. The built-in ones are guesses written off the top of someone's head -
     * this is how they get corrected.
     */
    private val dumpScoreboard by ActionSetting("Dump Scoreboard", desc = "Prints every current sidebar line to chat, with its colour codes, so the line hider can be pointed at the real text.") {
        ScoreboardLines.dump()
    }

    private val partyLeaveRegex = Regex("^(?:\\[[^]]*?] ?)?\\w{1,16} has left the party\\.$")

    // Auto join state. All in-memory, never saved - "only the first time" is just "once per game
    // launch", no config plumbing needed to enforce it.
    private var hasConnectedToHypixel = false
    private var pendingSkyblockJoin = false
    private var ticksUntilSkyblock = -1
    private var attempts = 0

    private const val HYPIXEL_ADDRESS = "hypixel.net"
    private const val FIRST_TRY_TICKS = 10  // 0.5s after the lobby loads
    private const val RETRY_TICKS = 40      // then every 2s until we are on Skyblock
    private const val TRANSFER_TICKS = 60   // a world load mid-way means a transfer is happening: give it 3s
    private const val MAX_ATTEMPTS = 6

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

    /** Whether the glint should be dropped for a piece of worn equipment. */
    fun hidesArmorGlint(): Boolean = enabled && noGlint

    /** A live terminal (Odin tracks the open one) or a practice term sim. */
    private fun inTerminal(): Boolean =
        TerminalUtils.currentTerm != null || mc.screen is TermSimGUI

    init {
        on<TickEvent.End> {
            if (hideDamage) mc.player?.hurtTime = 0
            PlayerDisplay.onlyShowWhenLow = hideHealthManaUnlessLow
            PlayerDisplay.lowThreshold = healthManaThreshold.toFloat() / 100f
            RenderOptimizer.forceHideAllArmorStands = hideArmorStands && DungeonUtils.inDungeons
            ScoreboardLines.hideLines = enabled
            ScoreboardLines.customPatterns = hideSbCustom
        }

        on<RenderItemNameEvent> {
            if (hideItemNames) cancel()
        }

        on<MessageEvent.Overlay> {
            if (hideActionBar) cancel()
        }

        on<MessageEvent.Chat> {
            if (blessOnLeave && partyLeaveRegex.matches(message)) sendCommand("pc bless")
        }

        // Auto join Hypixel. Hooked directly to raw Fabric events rather than Odin's own
        // TickEvent.End: that event is wired to ClientTickEvents.END_LEVEL_TICK, which only fires
        // once a world is loaded - it never fires at the title screen, so a countdown built on it
        // would sit at its starting value forever and never reach zero. ScreenEvents.AFTER_INIT
        // (fires once the title screen has actually finished initializing, unlike Odin's
        // BEFORE_INIT-based ScreenEvent.Open) makes a connect-delay unnecessary entirely.
        ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            if (!enabled || !autoJoinHypixel || hasConnectedToHypixel || screen !is TitleScreen) return@register
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

        // The lobby we land in ignores a command sent before it is ready, so /skyblock goes out
        // shortly after the world loads and then every couple of seconds until Odin sees the
        // Skyblock scoreboard.
        on<LevelEvent.Unload> { ScoreboardLines.hideLines = false }

        on<LevelEvent.Load> {
            if (!enabled || !autoJoinHypixel || !pendingSkyblockJoin) return@on
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
