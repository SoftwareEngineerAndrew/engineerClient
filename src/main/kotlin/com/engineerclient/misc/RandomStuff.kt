package com.engineerclient.misc

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RenderBossBarEvent
import com.odtheking.odin.events.RenderItemNameEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.render.RenderOptimizer
import com.odtheking.odin.features.impl.skyblock.PlayerDisplay
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.scores.DisplaySlot

/**
 * Grab bag of small independent toggles that don't warrant their own module.
 * Each setting is self-contained; add more here rather than spinning up a
 * new module for a one-off QoL toggle.
 */
object RandomStuff : Module(
    name = "Random Stuff",
    category = Category.custom("Blood Rush"),
    description = "A collection of small unrelated QoL toggles."
) {
    /** Read by ChatHider at the chat GUI, so other mods still see every line. */
    val hideChat by BooleanSetting("Hide Chat", false, desc = "Hides all chat messages from the screen. Other mods still see them.")
    private val hideDamage by BooleanSetting("Hide Damage Indicators", false, desc = "Suppresses the red hurt-flash overlay when you take damage.")
    private val signEnterConfirms by BooleanSetting("Enter Confirms Sign", true, desc = "On a sign edit screen, Enter finishes it instead of starting a new line — so a Bazaar or Auction House search is type-and-Enter.")
    private val hideHealthManaUnlessLow by BooleanSetting("Hide Health/Mana Above %", false, desc = "Hides Odin's Health HUD and Mana HUD unless the stat drops below the threshold below. Only has an effect if those HUD elements are already enabled in Odin's Player Display settings.")
    private val healthManaThreshold by NumberSetting("Threshold", 20, 1, 100, 1, desc = "Only show the Health/Mana HUD once the stat drops below this percent of max.", unit = "%").withDependency { hideHealthManaUnlessLow }
    private val hideItemNames by BooleanSetting("Hide Item Names", false, desc = "Hides the item name that pops up above the hotbar when you switch to a different item.")
    private val hideBossBarOutsideBoss by BooleanSetting("Hide Boss Bar Outside Boss", false, desc = "Hides the boss health bar unless you're actually in a dungeon boss fight.")
    private val hideActionBar by BooleanSetting("Hide Action Bar", false, desc = "Hides the entire action bar (the overlay text above the hotbar) — health/mana/defense text, level up messages, all of it.")
    private val hideArmorStands by BooleanSetting("Hide Armor Stands", false, desc = "In dungeons only: hides every armor stand (except terminals, active or inactive) and removes fishing bobbers' extended line.")
    private val blessOnLeave by BooleanSetting("Bless On Party Leave", false, desc = "Sends \"bless\" in party chat whenever someone leaves the party.")
    private val blackSky by BooleanSetting("Black Sky", false, desc = "Makes the sky (and distant fog) black instead of blue. Pairs with Sodium Extra's Sky toggle.")

    private val partyLeaveRegex = Regex("^(?:\\[[^]]*?] ?)?\\w{1,16} has left the party\\.$")

    /**
     * Step 1 of the scoreboard line hider (time/season/keys/%cleared): Odin has
     * no scoreboard-line infrastructure at all to build on, and Hypixel's sidebar text lives in
     * each line's team prefix+suffix (same trick LocationUtils already reads off
     * ClientboundSetPlayerTeamPacket for area detection), not anywhere guessable from outside the
     * game. This prints exactly what's really there so the actual hider can match real text
     * instead of a guess.
     */
    private val dumpScoreboard by ActionSetting("Dump Scoreboard", desc = "Prints every current sidebar line to chat, so the scoreboard line hider below can be built off the real text.") {
        val scoreboard = mc.level?.scoreboard
        val objective = scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (scoreboard == null || objective == null) {
            modMessage("§cNo sidebar is showing right now — open one first.")
            return@ActionSetting
        }
        val entries = scoreboard.listPlayerScores(objective).sortedByDescending { it.value() }
        modMessage("§a--- Scoreboard dump (${entries.size} lines) ---")
        entries.forEachIndexed { i, entry ->
            val team = scoreboard.getPlayerTeam(entry.owner())
            val prefix = team?.playerPrefix?.string ?: ""
            val suffix = team?.playerSuffix?.string ?: ""
            modMessage("§7$i: §f$prefix§8|§f$suffix")
        }
    }

    /**
     * Whether [key] should finish the open sign edit screen. Read by SignEnterMixin.
     *
     * Skyblock only, and that is the whole safety story: every sign edit screen you meet there is
     * a search box (Bazaar, Auction House, anything that asks for text), never a sign you are
     * writing four lines on. Off the island the vanilla behaviour is left alone, and even with
     * this on the arrow keys still move between lines.
     */
    /** Read by FogColorMixin every frame. */
    fun blackSkyActive(): Boolean = enabled && blackSky

    fun signEnterFinishes(key: Int): Boolean =
        enabled && signEnterConfirms && LocationUtils.isInSkyblock &&
            (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER)

    init {
        on<TickEvent.End> {
            if (hideDamage) mc.player?.hurtTime = 0
            PlayerDisplay.onlyShowWhenLow = hideHealthManaUnlessLow
            PlayerDisplay.lowThreshold = healthManaThreshold.toFloat() / 100f
            RenderOptimizer.forceHideAllArmorStands = hideArmorStands && DungeonUtils.inDungeons
        }

        on<RenderItemNameEvent> {
            if (hideItemNames) cancel()
        }

        on<RenderBossBarEvent> {
            if (hideBossBarOutsideBoss && !DungeonUtils.inBoss) cancel()
        }

        on<MessageEvent.Overlay> {
            if (hideActionBar) cancel()
        }

        on<MessageEvent.Chat> {
            if (blessOnLeave && partyLeaveRegex.matches(message)) sendCommand("pc bless")
        }
    }
}
