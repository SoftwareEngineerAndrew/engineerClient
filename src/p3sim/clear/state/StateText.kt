package com.engineerclient.p3sim.clear.state

import com.engineerclient.p3sim.run.KeyKind

/** The volume slider a sound belongs to (Hypixel sends each sound under one, so a player's slider settings work as on the real server). */
enum class CueSource { MASTER, BLOCKS, HOSTILE, PLAYERS }

/** A sound the state module plays: Minecraft's sound id, volume, pitch and sound category exactly as Hypixel sends them (client-state 3.8). */
data class Cue(val id: String, val volume: Float, val pitch: Float, val source: CueSource = CueSource.MASTER)

/**
 * The chat lines and sounds of the countdown, doors and keys. The formats are Hypixel's generic system lines with a `{name}`
 * for whoever did it (FORMAT-runstate 10.5); the plain text, colour codes stripped, is what Odin, Devonian and Blade Addons
 * match. The colour codes follow the recorded chat components. Pure.
 */
object StateText {
    // ---- sounds ------------------------------------------------------------------------------------------------------
    val COUNTDOWN_TICK = Cue("block.lever.click", 0.55f, 2.0f)
    val MORT_LINE = Cue("entity.villager.trade", 1.0f, 0.698f)
    val DOOR_OPENED = Cue("block.note_block.pling", 8.0f, 4.048f, CueSource.BLOCKS)
    val KEY_OBTAINED = Cue("entity.experience_orb.pickup", 1.0f, 1.492f, CueSource.PLAYERS)
    /** The vault shutter that follows the orb sound when a key is picked up (wither and blood keys alike). */
    val KEY_SHUTTER = Cue("block.vault.open_shutter", 2.0f, 1.0f, CueSource.PLAYERS)
    val BLOOD_DOOR = Cue("entity.ghast.hurt", 2.0f, 0.492f, CueSource.HOSTILE)
    val NO_KEY = Cue("entity.enderman.teleport", 8.0f, 0.0f, CueSource.HOSTILE)

    // ---- the start ---------------------------------------------------------------------------------------------------
    /** "<name> is now ready!": the name in its rank colour, the rest green (ADR, as recorded: `§bSoloF7§r§a is now ready!`). */
    fun ready(name: String, colour: String = "§b") = "$colour$name§r§a is now ready!"

    /** "<name> is no longer ready!" (ADR: `§aJoeher47§r§c is no longer ready!`). */
    fun notReady(name: String, colour: String = "§b") = "$colour$name§r§c is no longer ready!"

    /** Mort's Ready Up window, a class picked (ADR: `§bp3wr§r§a selected the Mage Class!`). [cls] is the class word ("Mage"). */
    fun selectedClass(name: String, colour: String, cls: String) = "$colour$name§r§a selected the $cls Class!"

    /** "Starting in 4 seconds." ... "Starting in 1 second." */
    fun startingIn(seconds: Int) = "§e§lStarting in $seconds ${if (seconds == 1) "second" else "seconds"}."

    /** Mort's three lines, in order (the NPC that hands the party the map). */
    val mortLines: List<String> = listOf(
        "Here, I found this map when I first entered the dungeon.",
        "You should find it useful if you get lost.",
        "Good luck.",
    )

    /**
     * The lines a player whose class nobody else in the party plays reads on Mort's first tick, just before his line: the doubled
     * stats of a level 50 class with the usual tree (the commonest recorded set for each class, ADR 1,500 runs; 177 Mage, 161 Archer,
     * 134 Healer, 55 Berserk, 38 Tank). Null for a class without one.
     */
    fun classDoubled(dungeonClass: String?): List<String>? {
        val cls = CLASS_NAMES.firstOrNull { it.equals(dungeonClass, ignoreCase = true) } ?: return null
        val stats = DOUBLED_STATS[cls] ?: return null
        return listOf("§6Your §r§a$cls §r§6stats are doubled because you are the only player using this class!") +
            stats.map { (stat, from, to) -> "§a[$cls] §r§f$stat §r§c$from§r§f -> §r§a$to" }
    }

    private val CLASS_NAMES = listOf("Healer", "Berserk", "Archer", "Tank", "Mage")

    private val DOUBLED_STATS: Map<String, List<Triple<String, String, String>>> = mapOf(
        "Mage" to listOf(Triple("Intelligence", "490", "740"), Triple("Ability Damage", "14", "19"), Triple("Cooldown Reduction", "49%", "74%")),
        "Archer" to listOf(Triple("Extra Arrow Chance", "97%", "147%"), Triple("Arrow Damage", "225.2%", "275.2%")),
        "Healer" to listOf(
            Triple("Healing Aura Healing", "1.8%", "2.8%"), Triple("Ghost Healing Aura Healing", "20.5%", "21%"), Triple("Revive Cooldown", "59", "49"),
            Triple("Overheal Maximum Shield", "36.4%", "56.4%"), Triple("Vitality", "18.2", "28.2"), Triple("Mending", "30.5", "40.5"), Triple("Healing Aura Range", "8", "16"),
        ),
        "Berserk" to listOf(
            Triple("Melee Damage", "76%", "116%"), Triple("Walk Speed", "49.2", "79.2"), Triple("Bloodlust Damage", "56%", "76%"),
            Triple("Lust For Blood Damage Increase Cap", "880%", "1,130%"), Triple("Lust For Blood Damage Increase Per Hit", "159%", "174%"),
            Triple("Indomitable Strength to Defense", "9.8%", "14.8%"), Triple("Weapon Master Swing Range Increase", "4.9", "5.4"),
            Triple("Bloodlust Heal Percent", "3%", "6%"), Triple("Bloodlust Duration", "5", "10"),
        ),
        "Tank" to listOf(
            Triple("Defense", "101", "151"), Triple("Total Defense", "35%", "60%"), Triple("Absorption Health", "700", "900"), Triple("Vitality", "15.1", "25.1"),
            Triple("Absorption Shield Health Required", "50%", "100%"), Triple("Absorption Shield Percentage", "10%", "20%"), Triple("Barrier Cooldown", "90", "45"),
        ),
    )

    fun mort(index: Int) = "§e[NPC] §bMort§f: §r${mortLines[index]}"

    // ---- doors and keys ----------------------------------------------------------------------------------------------
    fun keyName(kind: KeyKind) = if (kind == KeyKind.WITHER) "Wither Key" else "Blood Key"

    /**
     * The rank bracket every key pickup line carries on Hypixel (the line is "[RANK] name has obtained ..."). The sim's players have no
     * rank, so this is the commonest one in the recordings (MVP+, 76 % of pickups); it is a look, not a claim about the player.
     */
    const val KEY_RANK_PREFIX = "§b[MVP§c+§b]"

    /** The key pickup line. [bracket] is the finder's rank bracket (a bot has its own; the default is [KEY_RANK_PREFIX]). */
    fun keyObtained(kind: KeyKind, name: String, bracket: String = KEY_RANK_PREFIX) =
        "$bracket $name§f §ehas obtained ${if (kind == KeyKind.WITHER) "§8" else "§c"}${keyName(kind)}§e!"

    fun rightClick(kind: KeyKind) =
        if (kind == KeyKind.WITHER) "§e§lRIGHT CLICK §7on a §8WITHER §7door to open it. This key can only be used to open §a1§7 door!"
        else "§e§lRIGHT CLICK §7on the §cBLOOD DOOR§7 to open it. This key can only be used to open §a1§7 door!"

    /** The wither door line; [colour] is the opener's name colour (`§b` unless the opener's rank is another). */
    fun witherOpened(name: String, colour: String = "§b") = "$colour$name §aopened a §8§lWITHER §r§adoor!"

    const val BLOOD_OPENED = "§cThe §c§lBLOOD DOOR§r§c has been opened!"
    const val SHIVER = "§5A shiver runs down your spine..."
    const val NO_KEY_LINE = "§cYou do not have the key for this door!"

    /** Strips `§x` colour and format codes: the text the clients' parsers see. */
    fun plain(s: String): String = s.replace(Regex("§."), "")
}
