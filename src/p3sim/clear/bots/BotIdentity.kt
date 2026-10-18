package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.run.PlayerRecord
import java.util.UUID
import kotlin.random.Random

/**
 * What a teammate bot looks like to a client: its name and profile id (the tab entry carries them), and the text round the name over its head. On
 * Hypixel the `[level] name glyph` over a teammate is the name with a scoreboard team's prefix and suffix, so [prefix] and [suffix] are those, and
 * [nameColour] (`§b`) the team colour of the name itself.
 */
class BotLook(val name: String, val profileId: UUID, val prefix: String, val suffix: String, val nameColour: String = "§b") {
    /** The whole text as it reads over the head. */
    val tag: String get() = prefix + name + suffix
}

/** The rank a teammate shows in chat (and the colour of its name everywhere). */
enum class BotRank(val nameColour: String, val bracket: String, val share: Double) {
    MVP_PLUS("§b", "§b[MVP§c+§b]", 0.69),
    VIP("§a", "§a[VIP]", 0.12),
    VIP_PLUS("§a", "§a[VIP§6+§a]", 0.12),
    MVP_PLUS_PLUS("§6", "§6[MVP§c++§6]", 0.06),
}

/**
 * Who a teammate bot is, as a player on Hypixel is: a name, a rank, a SkyBlock level and a class level, an optional glyph after the name, and a
 * profile id of the kind real accounts have (version 4). The tab row, the nametag, the chat lines and the leap window all read these.
 */
class BotIdentity(
    val name: String,
    val rank: BotRank,
    val sbLevel: Int,
    val classLevel: Int,
    /** The glyph after the name with its colour codes (`§6➶`), or null for none. */
    val glyph: String?,
    val uuid: UUID,
) {
    /** The colour code of the level in square brackets. */
    val levelColour: String get() = BotIdentities.levelColour(sbLevel)

    /** What floats over the head: `§8[§4540§8] §bName §6➶`. */
    val nametag: String get() = look.tag

    /** What a client is shown of the bot: the name, the profile id, and the text before (`§8[§4540§8] §b`) and after (` §6➶`) the name. */
    val look: BotLook get() = BotLook(name, uuid, "§8[$levelColour$sbLevel§8] ${rank.nameColour}", glyph?.let { " $it" } ?: "", rank.nameColour)

    /** Writes the facts the other modules read (the tab list, the chat lines) into the bot's player record. */
    fun applyTo(rec: PlayerRecord) {
        rec.extras["sbLevel"] = sbLevel
        rec.extras["classLevel"] = classLevel
        rec.extras["rank"] = rank.name
        rec.extras["rankBracket"] = rank.bracket
        rec.extras["nameColour"] = rank.nameColour
        rec.extras["glyph"] = glyph ?: ""
    }
}

/** The tab, chat and nametag style of a party member, read from the player record (a human or a bot without an identity reads as the commonest rank, MVP+). */
object PartyStyle {
    const val DEFAULT_COLOUR = "§b"
    const val DEFAULT_BRACKET = "§b[MVP§c+§b]"

    /** The colour code of the member's name (`§b`). */
    fun nameColour(rec: PlayerRecord?): String = (rec?.extras?.get("nameColour") as? String) ?: DEFAULT_COLOUR

    /** The rank in front of the name in chat (`§b[MVP§c+§b]`). */
    fun bracket(rec: PlayerRecord?): String = (rec?.extras?.get("rankBracket") as? String) ?: DEFAULT_BRACKET

    /** The glyph after the name in the tab list and over the head with its codes, "" for none; null when the record has no identity (the caller keeps its own default). */
    fun glyph(rec: PlayerRecord?): String? = rec?.extras?.get("glyph") as? String
}

/**
 * Draws teammate identities. The numbers come from the Hypixel recordings (the teammates the human saw): ranks of 32 distinct teammates, the
 * SkyBlock level of 309 tab rows (p5 93, p25 255, p50 326, p75 502, p95 540), the class level of 317 (34, 41, 48, 50, 50) and the glyph after the
 * name. The names are invented from two small word lists, so no name here belongs to anybody.
 */
object BotIdentities {
    private val LEVEL = listOf(0.0 to 40.0, 0.05 to 93.0, 0.25 to 255.0, 0.5 to 326.0, 0.75 to 502.0, 0.95 to 540.0, 1.0 to 571.0)
    private val CLASS_LEVEL = listOf(0.0 to 30.0, 0.05 to 34.0, 0.25 to 41.0, 0.5 to 48.0, 0.6 to 50.0, 1.0 to 50.0)

    /** Glyphs after a name and how often they occur (the rest of the share is none). */
    private val GLYPHS = listOf("§6➶" to 0.19, "§7✿" to 0.06, "§7ᛃ" to 0.04, "§b§l⚛" to 0.04, "§6§l⚛" to 0.03, "§7❤" to 0.02, "§6⚔" to 0.02, "§7✨" to 0.02, "§7☠" to 0.02)

    /** The level colour: one band per 40 levels, as the SkyBlock level is coloured (1 to 39 gray is assumed). */
    fun levelColour(level: Int): String = when {
        level < 40 -> "§7"; level < 80 -> "§f"; level < 120 -> "§e"; level < 160 -> "§a"; level < 200 -> "§2"; level < 240 -> "§b"
        level < 280 -> "§3"; level < 320 -> "§9"; level < 360 -> "§d"; level < 400 -> "§5"; level < 440 -> "§6"; level < 480 -> "§c"
        else -> "§4"
    }

    val PREFIXES = listOf(
        "Amber", "Ash", "Birch", "Bramble", "Cinder", "Clover", "Copper", "Dawn", "Drift", "Dusk", "Ember", "Fern", "Flint", "Frost", "Gale", "Glimmer",
        "Hazel", "Indigo", "Juniper", "Larch", "Lumen", "Marsh", "Mistral", "Moss", "Nimbus", "Onyx", "Pebble", "Quartz", "Russet", "Saffron", "Sable",
        "Thistle", "Tundra", "Umber", "Velvet", "Willow", "Wisp", "Yarrow", "Zephyr", "Maple", "Coral", "Granite", "Harbor", "Lantern", "Meadow", "Prairie",
    )
    val SUFFIXES = listOf(
        "fox", "wolf", "hawk", "otter", "moth", "finch", "stag", "badger", "lynx", "wren", "newt", "hare", "crane", "viper", "bison", "gecko", "mantis",
        "puma", "seal", "swan", "toad", "vole", "lark", "ibis", "kite", "mole", "owl", "pike", "shrew", "tern", "wasp", "drake", "heron", "marten", "pika",
        "raven", "quail", "stoat", "tapir", "egret", "falcon", "gull", "lemur", "magpie", "osprey", "plover",
    )

    /** One name that is 3 to 16 letters, digits and underscores, in one of the styles real names come in. */
    fun name(random: Random): String {
        val a = PREFIXES[random.nextInt(PREFIXES.size)]; val b = SUFFIXES[random.nextInt(SUFFIXES.size)]
        val pick = random.nextDouble()
        val raw = when {
            pick < 0.40 -> a + b.replaceFirstChar { it.uppercase() }
            pick < 0.60 -> (a + b).lowercase() + random.nextInt(2, 100)
            pick < 0.75 -> a + b.replaceFirstChar { it.uppercase() } + random.nextInt(1, 100)
            pick < 0.85 -> a + "_" + b.replaceFirstChar { it.uppercase() }
            else -> a + random.nextInt(2, 1000)
        }
        return raw.take(16)
    }

    /** A version 4 (random) profile id drawn from [random], so a run's bots keep the same ids for the same seed. */
    fun uuid(random: Random): UUID {
        val msb = (random.nextLong() and -0xf001L) or 0x4000L
        val lsb = (random.nextLong() and 0x3fffffffffffffffL) or Long.MIN_VALUE
        return UUID(msb, lsb)
    }

    /** One identity; [taken] are lower-case names already in the party. */
    fun draw(random: Random, taken: Set<String>): BotIdentity {
        var name = name(random)
        var guard = 0
        while (name.lowercase() in taken && guard++ < 50) name = name(random)
        val rank = pickRank(random.nextDouble())
        val level = PlanMath.curve(LEVEL, random.nextDouble()).toInt().coerceIn(40, 571)
        val cls = PlanMath.curve(CLASS_LEVEL, random.nextDouble()).toInt().coerceIn(30, 50)
        return BotIdentity(name, rank, level, cls, pickGlyph(random.nextDouble()), uuid(random))
    }

    fun pickRank(u: Double): BotRank {
        var x = u
        for (r in BotRank.entries) { if (x < r.share) return r; x -= r.share }
        return BotRank.MVP_PLUS
    }

    fun pickGlyph(u: Double): String? {
        var x = u
        for ((g, share) in GLYPHS) { if (x < share) return g; x -= share }
        return null
    }

    /** The old look: the class word as the name, the way the boss sim names its teammates, with the rank and levels of the commonest account. */
    fun classWord(cls: String, uuid: UUID, sbLevel: Int, classLevel: Int): BotIdentity =
        BotIdentity(BotRoster.label(cls), BotRank.MVP_PLUS, sbLevel, classLevel, null, uuid)
}
