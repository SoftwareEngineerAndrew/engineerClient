package com.engineerclient.p3sim.clear.secrets

import kotlin.random.Random

/*
 * What a secret gives. A chest opened by hand gives, by what the recordings show (649 first opens): nothing 44 percent, a blessing
 * 42, a lump of x10 essence 14 and now and then (1.4 percent) a line for an item. Superboom TNT and Revive Stones are NOT chest
 * loot: they arrive when a room is cleared (the mechanism is not established; see [LootTables.roomClear], on the report's unmeasured
 * list). Every number is a named constant. Pure.
 */

/**
 * A kind of item a secret can give, with the name the player sees (Odin matches the item secret by this name). [floorCount] is the
 * size of the stack lying on the floor, [style] the rarity word of its tooltip style, [colour] the colour of its name.
 */
enum class LootItem(val displayName: String, val id: String, val colour: String = "§9", val style: String = "common", val floorCount: Int = 1) {
    SUPERBOOM("Superboom TNT", "SUPERBOOM_TNT", "§9", "rare"),
    REVIVE_STONE("Revive Stone", "REVIVE_STONE", "§6", "legendary"),
    SPIRIT_LEAP("Spirit Leap", "SPIRIT_LEAP", "§9", "rare", floorCount = 5),
    HEALING_POTION("Healing VIII Splash Potion", "POTION", "§5", "common"),
    DECOY("Decoy", "DUNGEON_DECOY", "§a", "uncommon"),
    INFLATABLE_JERRY("Inflatable Jerry", "INFLATABLE_JERRY", "§f", "common", floorCount = 2),
    TRAINING_WEIGHTS("Training Weights", "TRAINING_WEIGHTS", "§a", "uncommon"),
    TRAP("Trap", "DUNGEON_TRAP", "§a", "uncommon"),
    DEFUSE_KIT("Defuse Kit", "DEFUSE_KIT", "§a", "uncommon"),
    CANDYCOMB("Candycomb", "CANDYCOMB", "§9", "rare");
}

/** The four coloured essence chests (always x10, measured). */
val ESSENCE_TYPES = listOf("Ice", "Gold", "Spider", "Diamond")

/** The colour of an essence type's name in the chat line (`x10 Diamond Essence`). */
fun essenceColour(type: String): String = when (type) { "Gold" -> "§6"; "Spider" -> "§5"; "Wither" -> "§d"; else -> "§b" }

/** One reward. */
sealed interface Drop {
    class Item(val item: LootItem, val count: Int = 1) : Drop
    class Essence(val type: String, val amount: Int = 10) : Drop
    class Blessing(val blessing: com.engineerclient.p3sim.clear.secrets.Blessing) : Drop
    /** Coins, a junk item or an empty chest: nothing the player is told about. */
    data object Nothing : Drop
}

/** The chat lines of a find, as the recordings read them (the `§` codes are Hypixel's). [colour] is the colour of a teammate's name. Pure. */
object SecretLines {
    /** The finder's own line for a wither essence skull, and what a teammate sees. */
    const val WITHER_ESSENCE_SELF = "§fYou found a §r§dWither Essence§r§f! Everyone gains an extra essence!"
    fun witherEssenceOther(colour: String, name: String) = "$colour$name §r§ffound a §r§dWither Essence§r§f! Everyone gains an extra essence!"

    private fun lump(drop: Drop.Essence) = "${essenceColour(drop.type)}x${drop.amount} ${drop.type} Essence§r§f!"

    /** A chest's coloured essence: `ESSENCE! You found x10 Ice Essence!` for the finder, `ESSENCE! <name> found x10 Gold Essence!` for the rest. */
    fun chestEssenceSelf(drop: Drop.Essence) = "§6§lESSENCE! §r§fYou found §r${lump(drop)}"
    fun chestEssenceOther(colour: String, name: String, drop: Drop.Essence) = "§6§lESSENCE! §r$colour$name §r§ffound §r${lump(drop)}"

    /** `<rank> <name> has obtained Superboom TNT x2!`: [who] is the rank and name; the item in its colour, the quantity (when more than one) dark grey. */
    fun obtained(who: String, item: LootItem, count: Int) =
        "$who§r§f §r§ehas obtained §r${item.colour}${item.displayName}" + (if (count > 1) " §r§8x$count" else "") + "§r§e!"
}

object LootTables {
    // ---- chests -------------------------------------------------------------------------------------------------------------
    // First opens by the recorder's owner (649 chests in 216 real sessions, loot lines matched 20 to 27 ticks after the opening):
    // no line 286 (44.1 percent), a blessing 273 (42.1 percent), x10 essence 88 (13.6 percent), an item line 9 (1.4 percent).
    // A chest with a reward is also the chest with the harp roll (54 percent of first opens), so the two share one draw.
    /** Share of chests that give a blessing. */
    const val CHEST_BLESSING = 0.40
    /** Share that give a lump of x10 essence (Ice, Gold, Spider or Diamond, equally often). */
    const val CHEST_ESSENCE = 0.13
    /** Share that print a "has obtained" line for an item (the same draw as a room clear, see [roomClear]). */
    const val CHEST_ITEM = 0.01
    /** Share of Superboom TNT drops that give two. */
    const val SUPERBOOM_DOUBLE = 0.10

    /** What a chest holds. Anything but [Drop.Nothing] is a reward chest: it plays the harp and reveals its loot 24 ticks after the click. */
    fun chest(random: Random): Drop {
        var u = random.nextDouble()
        u -= CHEST_BLESSING
        if (u < 0) return Drop.Blessing(Blessings.pickChest(random))
        u -= CHEST_ESSENCE
        if (u < 0) return Drop.Essence(ESSENCE_TYPES[random.nextInt(ESSENCE_TYPES.size)])
        u -= CHEST_ITEM
        if (u < 0) return rewardItem(random)
        return Drop.Nothing
    }

    // ---- room clears ----------------------------------------------------------------------------------------------------------
    // Own "has obtained Superboom TNT / Revive Stone" lines (271 / 68 in 216 sessions, 4.14 / 1.05 per session party-wide) do not
    // follow a chest, lever or skull click (8 of 339 do). 23 of 56 land 0 to 4 ticks after a white room check (22 of 492 white checks
    // carry one, 4.5 percent; 3 of 615 green checks). The mechanism is NOT established: a drop when a room is cleared is the best fit.
    // UNMEASURED: whose drop it is (here the player who dealt the last required kill), and the split between the two items.
    /** Chance that a cleared room drops Superboom TNT for the clearer. */
    const val ROOM_CLEAR_SUPERBOOM = 0.04
    /** Chance that a cleared room drops a Revive Stone for the clearer. */
    const val ROOM_CLEAR_REVIVE = 0.01

    // ---- mob kills ----------------------------------------------------------------------------------------------------------------
    // The rest of the party-wide "has obtained" lines come when mobs die (a recorded own Blessing pickup arrives in the same tick as the
    // Wither Key and the Beating Heart of the key mob): per run 4.14 Superboom TNT, 1.05 Revive Stones and 4.7 blessings (ADR, 5-player
    // F7), against about 0.9 and 0.2 from room clears. A run kills about 170 starred mobs (the mob pack's starred p50 over a layout's
    // rooms), so each starred kill drops at these rates. UNMEASURED: that only starred kills drop, and that kills drop evenly.
    /** Chance that a starred mob's death drops Superboom TNT for its killer. */
    const val KILL_SUPERBOOM = 0.019
    /** Chance that it drops a Revive Stone. */
    const val KILL_REVIVE = 0.005
    /** Chance that it drops a blessing (level V, see [Blessings.pickKill]). */
    const val KILL_BLESSING = 0.028

    /** What a starred mob's death drops, or null for nothing. */
    fun mobKill(random: Random): Drop? {
        val u = random.nextDouble()
        if (u < KILL_BLESSING) return Drop.Blessing(Blessings.pickKill(random))
        if (u < KILL_BLESSING + KILL_SUPERBOOM) return superboom(random)
        if (u < KILL_BLESSING + KILL_SUPERBOOM + KILL_REVIVE) return Drop.Item(LootItem.REVIVE_STONE)
        return null
    }

    /** A room clear's drop, or null for none (most rooms drop nothing). */
    fun roomClear(random: Random): Drop? {
        val u = random.nextDouble()
        if (u < ROOM_CLEAR_SUPERBOOM) return superboom(random)
        if (u < ROOM_CLEAR_SUPERBOOM + ROOM_CLEAR_REVIVE) return Drop.Item(LootItem.REVIVE_STONE)
        return null
    }

    /** One of the two items a "has obtained" line is about, in the room-clear ratio (four Superboom TNT to one Revive Stone). */
    fun rewardItem(random: Random): Drop.Item =
        if (random.nextDouble() * (ROOM_CLEAR_SUPERBOOM + ROOM_CLEAR_REVIVE) < ROOM_CLEAR_SUPERBOOM) superboom(random) else Drop.Item(LootItem.REVIVE_STONE)

    private fun superboom(random: Random) = Drop.Item(LootItem.SUPERBOOM, if (random.nextDouble() < SUPERBOOM_DOUBLE) 2 else 1)

    // ---- item secrets (a thing lying on the floor) ----------------------------------------------------------------------------
    // Weights are the item entities seen in 579 recorded dungeons (scout-secrets 1.1), Superboom excluded (it comes from room clears).
    private val ITEM_WEIGHTS = listOf(
        LootItem.HEALING_POTION to 344, LootItem.TRAINING_WEIGHTS to 304, LootItem.TRAP to 284, LootItem.DECOY to 261,
        LootItem.INFLATABLE_JERRY to 248, LootItem.SPIRIT_LEAP to 197, LootItem.REVIVE_STONE to 136, LootItem.DEFUSE_KIT to 48, LootItem.CANDYCOMB to 26,
    )

    fun itemSecret(random: Random): LootItem {
        var n = random.nextInt(ITEM_WEIGHTS.sumOf { it.second })
        for ((item, w) in ITEM_WEIGHTS) { n -= w; if (n < 0) return item }
        return ITEM_WEIGHTS.last().first
    }

    /** The items an item secret can show (the floor item's name is what Odin's pickup detection reads). */
    val ITEM_SECRET_ITEMS: List<LootItem> get() = ITEM_WEIGHTS.map { it.first }
}
