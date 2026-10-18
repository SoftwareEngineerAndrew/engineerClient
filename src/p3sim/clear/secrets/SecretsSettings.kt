package com.engineerclient.p3sim.clear.secrets

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.Module

/**
 * The secrets' Odin settings. They live in this object, not in [SecretsModule], so the module object itself stays free of Odin
 * types (the headless module test instantiates it). Each default is a named, documented guess where the recordings do not say.
 */
object SecretsSettings {
    /**
     * How far (sideways, any height) a party member must be before a floor item, a bat or a wither essence skull appears. Measured as the distance to the nearest
     * party member when each first showed up: items at about 31 blocks (27.8 to 42.2), bats about 6.5 (up to 17), skulls about 10 (3 to 19). A repeating 20-tick pass.
     */
    const val ITEM_SPAWN_RANGE = 32.0
    const val BAT_SPAWN_RANGE = 14.0
    const val SKULL_SPAWN_RANGE = 16.0
    /** How far past a player's body (sideways) an item is picked up: the game's own pickup box, the player's box widened by one block (and half a block up and down). */
    const val PICKUP_RANGE = 1.0
    /** The Superboom TNT's blast radius around the block it hits. UNMEASURED. */
    const val SUPERBOOM_RADIUS = 3.0
    /** Ticks a lever's wall stays an invisible barrier before it is air (four recorded samples read about 25). */
    const val LEVER_WALL_TICKS = 25
    /**
     * Chance (percent) that a run has a bonus bat at all. Which kill carries the point is drawn per run (the first counted secret-bat kill 70 percent of the
     * time, the second 24, ...), and that already reproduces the 71 percent of one-bat runs that show it; so this stays at 100.
     */
    const val BONUS_BAT_PERCENT = 100

    private var itemRangeS: NumberSetting<Double>? = null
    private var batRangeS: NumberSetting<Double>? = null
    private var skullRangeS: NumberSetting<Double>? = null
    private var pickupRangeS: NumberSetting<Double>? = null
    private var boomRadiusS: NumberSetting<Double>? = null
    private var leverTicksS: NumberSetting<Int>? = null
    private var autoCreditS: BooleanSetting? = null
    private var bonusBatS: NumberSetting<Int>? = null
    private var blessingBoostS: BooleanSetting? = null

    fun register(host: Module) {
        itemRangeS = host.registerSetting(NumberSetting("Secrets Item Range", ITEM_SPAWN_RANGE, 8.0..64.0, 1.0, unit = " blocks", desc = "How near (sideways) a party member must be before an item secret lies on the floor."))
        batRangeS = host.registerSetting(NumberSetting("Secrets Bat Range", BAT_SPAWN_RANGE, 4.0..64.0, 1.0, unit = " blocks", desc = "How near (sideways) a party member must be before a secret bat appears."))
        skullRangeS = host.registerSetting(NumberSetting("Secrets Skull Range", SKULL_SPAWN_RANGE, 4.0..64.0, 1.0, unit = " blocks", desc = "How near (sideways) a party member must be before a wither essence or key skull appears."))
        pickupRangeS = host.registerSetting(NumberSetting("Secrets Pickup Range", PICKUP_RANGE, 0.0..5.0, 0.5, unit = " blocks", desc = "How far past your body (sideways) you reach to pick up an item secret; the game's own is 1."))
        boomRadiusS = host.registerSetting(NumberSetting("Secrets Superboom Radius", SUPERBOOM_RADIUS, 1.0..8.0, 0.5, unit = " blocks", desc = "How far a Superboom TNT reaches: a crypt or secret wall with any block this close to the blast is destroyed whole."))
        leverTicksS = host.registerSetting(NumberSetting("Secrets Lever Wall Time", LEVER_WALL_TICKS, 0..100, 1, unit = " ticks", desc = "How long a lever's wall stays an invisible barrier before it opens for good."))
        autoCreditS = host.registerSetting(BooleanSetting("Secrets Auto Credit", true, desc = "A room whose secrets the room data cannot all place counts the missing ones when you find the last one it does place, so the room can still turn green."))
        bonusBatS = host.registerSetting(NumberSetting("Secrets Bonus Bat Chance", BONUS_BAT_PERCENT, 0..100, 1, unit = "%", desc = "How often a run has a bonus bat at all: one of the first few secret bats killed then also counts a point of bonus score."))
        blessingBoostS = host.registerSetting(BooleanSetting("Blessing Boost x1.25", false, desc = "Hypixel has days when every blessing is 25 percent stronger (Power V reads +36.3 & 1.19x instead of +29 & 1.15x). Turn this on to practise a boosted day."))
    }

    val itemRange: Double get() = itemRangeS?.value ?: ITEM_SPAWN_RANGE
    val batRange: Double get() = batRangeS?.value ?: BAT_SPAWN_RANGE
    val skullRange: Double get() = skullRangeS?.value ?: SKULL_SPAWN_RANGE
    val pickupRange: Double get() = pickupRangeS?.value ?: PICKUP_RANGE
    val superboomRadius: Double get() = boomRadiusS?.value ?: SUPERBOOM_RADIUS
    val leverWallTicks: Int get() = leverTicksS?.value ?: LEVER_WALL_TICKS
    val autoCredit: Boolean get() = autoCreditS?.value ?: true
    val blessingBoost: Double get() = if (blessingBoostS?.value == true) Blessings.BOOST else 1.0
    val bonusBatPercent: Int get() = bonusBatS?.value ?: BONUS_BAT_PERCENT
}
