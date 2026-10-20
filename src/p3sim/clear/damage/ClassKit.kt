package com.engineerclient.p3sim.clear.damage

/**
 * The five classes' Ability (Ctrl+Q, the whole-stack drop) and Ultimate (Q, "Press DROP to activate it!") as pure rules: names, cooldowns,
 * when they are first ready and the chat lines, as recorded (ADR, 488 runs; the lines are Hypixel's own with their colours).
 *
 * Measured: Ultimate cooldowns from "Used X!" plus the seconds "Your Ultimate is currently on cooldown for N more seconds." still gave (Castle
 * of Stone 150 s n=239, Ragnarok 60 s n=199, Rapid Fire 100 s n=387, Thunderstorm 255 s or 130 s n=1724, two clusters); ability cooldowns the same
 * way or from "Used X!" to "X is now available!" (Explosive Shot 22.3 s n=129, Guided Sheep 7 s or 15 s, see [abilityCd], Throwing Axe 10.7 s n=2, Healing Circle 2 s n=10).
 * An Ultimate is first ready a median 430 ticks after Mort's first line (n=425), an Ability 411 (n=243); while an Ultimate waits unused its
 * reminder comes every 607 ticks (n=2,119).
 *
 * UNMEASURED (no recording has it): Wish's cooldown (120 s here), Seismic Wave's (15 s here), and which of Thunderstorm's two clusters is the
 * doubled Mage (130 s is taken as the Mage who is the only one, whose stats are doubled).
 */
object ClassKit {
    class Kit(val cls: String, val ability: String, val abilityCd: Int, val ultimate: String, val ultimateCd: Int)

    /** Ticks after T0 (Mort's first line) at which the Ultimate is first ready. */
    const val ULT_FIRST_READY = 430
    /** Ticks after T0 at which the Ability is first ready. */
    const val ABILITY_FIRST_READY = 411
    /** Ticks between "is ready to use!" reminders while the Ultimate waits unused. */
    const val ULT_REMINDER = 607

    private val KITS = listOf(
        Kit("Healer", "Healing Circle", 40, "Wish", 2400),
        Kit("Mage", "Guided Sheep", 300, "Thunderstorm", 2600),
        Kit("Berserk", "Throwing Axe", 214, "Ragnarok", 1200),
        Kit("Archer", "Explosive Shot", 446, "Rapid Fire", 2000),
        Kit("Tank", "Seismic Wave", 300, "Castle of Stone", 3000),
    )

    /** The kit of [dungeonClass] (any case; "Berserker" is Berserk), or null. */
    fun of(dungeonClass: String?): Kit? {
        val c = dungeonClass?.lowercase()?.let { if (it == "berserker") "berserk" else it } ?: return null
        return KITS.firstOrNull { it.cls.lowercase() == c }
    }

    /**
     * The Ability's cooldown in ticks. Guided Sheep: 140 for the party's only Mage (doubled), 300 with another Mage (139 DR and ADR replays:
     * sheep to "is now available!" 139-140 in 169 of 169 lines of a lone Mage, 299-300 in 102 of 106 with two or more; Ctrl+Q and the burst alike).
     */
    fun abilityCd(kit: Kit, doubled: Boolean): Int = if (kit.ability == "Guided Sheep") (if (doubled) 140 else 300) else kit.abilityCd

    /** The Ultimate's cooldown in ticks: Thunderstorm is 255 s, or 130 s for a Mage whose stats are doubled (the party's only Mage). */
    fun ultimateCd(kit: Kit, doubled: Boolean): Int = if (kit.ultimate == "Thunderstorm") (if (doubled) 2600 else 5100) else kit.ultimateCd

    fun used(name: String) = "§aUsed §r§6$name§r§a!"
    fun abilityAvailable(name: String) = "§6$name §r§ais now available!"
    fun ultimateReady(name: String) = "§6$name§r§a is ready to use! Press §r§6§lDROP§r§a to activate it!"
    fun ultimateRecharged(cls: String, name: String) = "§aYour $cls §r§e§lULTIMATE§r§a §r§6$name §r§ais now available!"
    fun abilityCooling(ticksLeft: Int) = "§cYour Regular Ability is currently on cooldown for ${secondsLeft(ticksLeft)} more seconds."
    fun ultimateCooling(ticksLeft: Int) = "§cYour Ultimate is currently on cooldown for ${secondsLeft(ticksLeft)} more seconds."
    /** "Your Thunderstorm hit 13 enemies for 1,349,128.3 damage." */
    fun hitLine(name: String, n: Int, damage: Double): String {
        val d = "%,.1f".format(java.util.Locale.ROOT, damage).removeSuffix(".0")
        return "§7Your $name hit §r§c$n §r§7${if (n == 1) "enemy" else "enemies"} for §r§c$d §r§7damage."
    }
    fun wishSelf(healed: Int, shield: Int) = "§eYour §r§6Wish §r§ehealed your entire team for §r§a${"%,d".format(java.util.Locale.ROOT, healed)} §r§ehealth and shielded them for §r§a${"%,d".format(java.util.Locale.ROOT, shield)}§r§e!"

    private fun secondsLeft(ticks: Int) = maxOf(1, (ticks + 19) / 20)
}
