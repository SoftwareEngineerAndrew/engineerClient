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
    fun ready(name: String) = "§a$name §7is now ready!"

    /** "Starting in 4 seconds." ... "Starting in 1 second." */
    fun startingIn(seconds: Int) = "§e§lStarting in $seconds ${if (seconds == 1) "second" else "seconds"}."

    /** Mort's three lines, in order (the NPC that hands the party the map). */
    val mortLines: List<String> = listOf(
        "Here, I found this map when I first entered the dungeon.",
        "You should find it useful if you get lost.",
        "Good luck.",
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
