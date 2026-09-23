package com.engineerclient.misc

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.GuiEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RenderBossBarEvent
import com.odtheking.odin.events.RenderItemNameEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.render.RenderOptimizer
import com.odtheking.odin.features.impl.skyblock.PlayerDisplay
import com.odtheking.odin.utils.Color.Companion.multiplyAlpha
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.containsOneOf
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

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
    private val hideArmorStands by BooleanSetting("Hide Armor Stands", false, desc = "Hides every armor stand in the world (except terminals, active or inactive) and removes fishing bobbers' extended line.")
    private val junkHighlight by BooleanSetting("Junk Highlight", false, desc = "Faint red backdrop on inventory slots holding known-useless dungeon drops.")

    /**
     * SkyBlock item IDs, matched via [itemId] rather than display name - immune to color codes,
     * rarity prefixes, and localization. Sourced from Cameron's own AutoCroesus worthless.txt
     * (github.com/undonecoffee/allModules) - dungeon discs, the M7 boss fish, and enchant book
     * duplicates past the level that actually matters (e.g. Feather Falling 6-10, once you've
     * got the useful lower levels or a higher one already).
     */
    private val junkItemIds = hashSetOf(
        "DUNGEON_DISC_1", "DUNGEON_DISC_2", "DUNGEON_DISC_3", "DUNGEON_DISC_4", "DUNGEON_DISC_5",
        "MAXOR_THE_FISH", "STORM_THE_FISH", "GOLDOR_THE_FISH",
        "ENCHANTMENT_ULTIMATE_NO_PAIN_NO_GAIN_1", "ENCHANTMENT_ULTIMATE_NO_PAIN_NO_GAIN_2",
        "ENCHANTMENT_ULTIMATE_NO_PAIN_NO_GAIN_3", "ENCHANTMENT_ULTIMATE_NO_PAIN_NO_GAIN_4",
        "ENCHANTMENT_ULTIMATE_NO_PAIN_NO_GAIN_5",
        "ENCHANTMENT_ULTIMATE_COMBO_1", "ENCHANTMENT_ULTIMATE_COMBO_2", "ENCHANTMENT_ULTIMATE_COMBO_3",
        "ENCHANTMENT_ULTIMATE_COMBO_4", "ENCHANTMENT_ULTIMATE_COMBO_5",
        "ENCHANTMENT_ULTIMATE_BANK_1", "ENCHANTMENT_ULTIMATE_BANK_2", "ENCHANTMENT_ULTIMATE_BANK_3",
        "ENCHANTMENT_ULTIMATE_BANK_4", "ENCHANTMENT_ULTIMATE_BANK_5",
        "ENCHANTMENT_ULTIMATE_JERRY_1", "ENCHANTMENT_ULTIMATE_JERRY_2", "ENCHANTMENT_ULTIMATE_JERRY_3",
        "ENCHANTMENT_ULTIMATE_JERRY_4", "ENCHANTMENT_ULTIMATE_JERRY_5",
        "ENCHANTMENT_FEATHER_FALLING_6", "ENCHANTMENT_FEATHER_FALLING_7", "ENCHANTMENT_FEATHER_FALLING_8",
        "ENCHANTMENT_FEATHER_FALLING_9", "ENCHANTMENT_FEATHER_FALLING_10",
        "ENCHANTMENT_INFINITE_QUIVER_6", "ENCHANTMENT_INFINITE_QUIVER_7", "ENCHANTMENT_INFINITE_QUIVER_8",
        "ENCHANTMENT_INFINITE_QUIVER_9", "ENCHANTMENT_INFINITE_QUIVER_10",
    )

    /**
     * A handful of extras called out by name in chat rather than by ID - matched against
     * hoverName instead, since that's all that's known about them. This list is Cameron's own
     * call on what's junk for him, not an objective classification (Revive Stone, for instance,
     * is on Odin's own "worth grabbing" dungeonItemDrops list) - add/remove freely.
     */
    private val junkNames = hashSetOf(
        "Bone", "Rotten Flesh", "String", "Spider Eye", "Gunpowder", "Arrow",
        "Ink Sac", "Spider's Eye", "Wither Skeleton Skull", "Egg", "Revive Stone",
    )
    private val junkColor = Colors.MINECRAFT_RED.multiplyAlpha(0.35f)

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

    init {
        on<TickEvent.End> {
            if (hideDamage) mc.player?.hurtTime = 0
            PlayerDisplay.onlyShowWhenLow = hideHealthManaUnlessLow
            PlayerDisplay.lowThreshold = healthManaThreshold.toFloat() / 100f
            RenderOptimizer.forceHideAllArmorStands = hideArmorStands
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

        on<GuiEvent.RenderSlot> {
            if (!junkHighlight) return@on
            val item = slot.item
            if (item.isEmpty) return@on
            // hoverName.string keeps its color/formatting codes (e.g. "§fRevive Stone"), so an
            // exact match against plain names never hit - contains catches it regardless of rarity
            // color, same fix EventDispatcher's own item-name matching already relies on.
            val isJunk = item.itemId in junkItemIds || item.hoverName.string.containsOneOf(junkNames)
            if (!isJunk) return@on
            guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, junkColor.rgba)
        }
    }
}
