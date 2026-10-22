package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.pack.mobs.MobArchetype
import com.engineerclient.p3sim.pack.mobs.MobSkin
import com.engineerclient.p3sim.pack.mobs.MobTag
import java.util.Base64

/**
 * What the Watcher's camp mobs look like on Hypixel, for the mob pack has none of them: each of the 17 standard mobs, the four revived bosses,
 * the giant and the Wandering Soul is an archetype of its own here, outside the pack's list (indices from [BASE]). Measured on ADR (495 runs
 * plus the 1,023 of the R2 backup):
 *
 * - The NPCs are fake players named after the mob with a trailing space ("Revoker ", "Mr. Dead"; "Spirit Bear" and the soul's
 *   "WanderingSoul " as written), each with one skin (two are slim). They hold a stone sword and wear chainmail (the camp's own measurement).
 * - The giant is a giant in leather armour, its tag 12.5 blocks up; an NPC's tag is about 2.13 up. Both at body id + 1.
 * - The tag: a standard mob `§2` + icon + ` §r` + the word's colour + `Word Name` + `§r §a` HP `§r§c❤`; a giant `§2` + icon + `§r§7` + icon,
 *   a boss `§e` (Spirit Bear `§a`) + icon + `§r§5` + icon, bold. The word colours: Speedy b, Healthy a, Stormy 1, Stealth 7, Golden 6, Boomer c;
 *   a word in the colour already set (a Stealth giant) is written without its code. HP is green, yellow at half or less, `§c0❤` dead.
 */
object BloodLooks {
    const val BASE = 100_000
    const val PREFIX = "blood:"

    private class Look(val name: String, val kind: Kind, val profile: String, val skin: String?, val slim: Boolean = false)
    enum class Kind { STANDARD, BOSS, GIANT, SOUL }

    private val LOOKS: List<Look> = listOf(
        Look("Freak", Kind.STANDARD, "Freak ", "7d12b2ade413a6cd7cca3c95e961ba9f0ae7165fa41fc7b5d5f094a01240c609"),
        Look("Reaper", Kind.STANDARD, "Reaper ", "a89f6303af85877610912dc04b8b1e89724752f0a7eea05ab6547e228179c06f", slim = true),
        Look("Tear", Kind.STANDARD, "Tear ", "c919e5b8d56f062a21d224de14af771e2f55d09b59e7b099d09daa57540b79cf", slim = true),
        Look("Leech", Kind.STANDARD, "Leech ", "5a79860aca799407c0faa10b1bbcf42998fad4ebcf31d7a214180826b4ac94e1"),
        Look("Mr. Dead", Kind.STANDARD, "Mr. Dead", "fb3973a752b24a2f3abb003427f6dbe6ca3a61db0a1bcf351c6eab27ec27e50"),
        Look("Putrid", Kind.STANDARD, "Putrid ", "f4624a9a8c69ca204504abb043d47456cd9b09749a36357462303f276a229d4"),
        Look("Psycho", Kind.STANDARD, "Psycho ", "aa23c8cde2943c84249de8351bc3540be5f8afaaba8b2cb032fc5acad78a269b"),
        Look("Ooze", Kind.STANDARD, "Ooze ", "9171f35b8f508142bd8c65417d0f324153ab9147739ee4d10dea733cc80eaa20"),
        Look("Mute", Kind.STANDARD, "Mute ", "5cccd53f5191c29a9dc8f0170fbdc4e59e66476aae33de27b468f1de1b7cf3b2"),
        Look("Vader", Kind.STANDARD, "Vader ", "ad67f97d7f821729beb34a82c3f13592b40439fe5248e72576fde7aa180bf77"),
        Look("Parasite", Kind.STANDARD, "Parasite ", "4774871190c878c9a2c4496c1e10257c6c4ea13807d72c15d7ac6ab3a7a9a8dc"),
        Look("Walker", Kind.STANDARD, "Walker ", "8421ba5b8e3573ef97beb5b40e15d15b20f30631c4c5330c3deda3047df0e92"),
        Look("Revoker", Kind.STANDARD, "Revoker ", "ff184c19e725623d32828a0a4e741e86f135ac63dbc828ff3c8468338f3683b"),
        Look("Cannibal", Kind.STANDARD, "Cannibal ", "b5ba76e02cab72fa7d8ac54ceec849976ab0b00a01068d68c266766bf70c3997"),
        Look("Skull", Kind.STANDARD, "Skull ", "ad22772f769045fdc5be819ad68b01a97ac04c60886d2ca7afee39b282f7a383"),
        Look("Frost", Kind.STANDARD, "Frost ", "3260325171a7ba8460830c0eea515c757a665e5b16a14207ba1a3182752bee87"),
        Look("Flamer", Kind.STANDARD, "Flamer ", "67237eddaebdbbdaacfa912885560ccdc65da93b4c3d513532868ec23bb5b448"),
        Look("Livid", Kind.BOSS, "Livid ", "c1007c5b7114abec734206d4fc613da4f3a0e99f71ff949cedadc99079135a0b"),
        Look("Spirit Bear", Kind.BOSS, "Spirit Bear", "62d8fd3aa5617b1dac0aae9c81f6dd70ad93a59942f460d27e4d55a5cb8918e8"),
        Look("Bonzo", Kind.BOSS, "Bonzo ", "12716ecbf5b8da00b05f316ec6af61e8bd02805b21eb8e440151468dc656549c"),
        Look("Scarf", Kind.BOSS, "Scarf ", "7de7bbbdf22bfe17980d4e20687e386f11d59ee1db6f8b4762391b79a5ac532d"),
        Look("Giant", Kind.GIANT, "", null),
        Look("Wandering Soul", Kind.SOUL, "WanderingSoul ", "2cf0de45e70ee69c82ec9c29088adc4c48b35356ef5c4d40849f6796cf7f89b6", slim = true),
    )

    private const val ICON = ""
    private const val ICON_GIANT = ""
    private const val ICON_BOSS = ""
    private const val ICON_BONZO_SCARF = ""
    private const val ICON_SPIRIT_BEAR = ""

    /** Tag height over the feet: an NPC's, a giant's. */
    const val NPC_TAG_DY = 2.125
    const val GIANT_TAG_DY = 12.5

    private val WORD_COLOUR = mapOf("Speedy" to "b", "Healthy" to "a", "Stormy" to "1", "Stealth" to "7", "Golden" to "6", "Boomer" to "c")

    private val archetypes: List<MobArchetype> = LOOKS.mapIndexed { i, l ->
        MobArchetype(
            index = BASE + i, id = PREFIX + l.name, name = l.name, cls = "special",
            entity = if (l.kind == Kind.GIANT) "minecraft:giant" else "minecraft:player",
            disguise = l.kind != Kind.GIANT,
            skin = l.skin?.let { MobSkin(it, skinValue(it, l.slim), null, l.slim) },
            bodyName = null, equipment = null, hp = emptyMap(), star = false,
            tag = MobTag(idOffset = 1, dy = if (l.kind == Kind.GIANT) GIANT_TAG_DY else NPC_TAG_DY, head = "", nameColor = "", bold = l.kind == Kind.BOSS || l.name == "Livid"),
            movement = if (l.kind == Kind.GIANT) "static" else "chase", wake = null, modifierWeights = null, seen = emptyMap(),
        )
    }

    /** The archetype a camp spawn asks for by name (the extras' `name`), or null for a name the camp does not have. */
    fun forName(name: String?): MobArchetype? = name?.let { n -> LOOKS.indexOfFirst { it.name == n }.takeIf { it >= 0 }?.let { archetypes[it] } }

    /** The archetype at [index] (one of this object's), or null. */
    fun archetype(index: Int): MobArchetype? = archetypes.getOrNull(index - BASE)

    fun isBlood(arch: MobArchetype): Boolean = arch.id.startsWith(PREFIX)

    /** The fake player's profile name. */
    fun profileName(arch: MobArchetype): String = LOOKS.getOrNull(arch.index - BASE)?.profile ?: arch.name

    private fun kindOf(arch: MobArchetype): Kind = LOOKS.getOrNull(arch.index - BASE)?.kind ?: Kind.STANDARD

    /** The tag of a camp mob with [word] (or "" for none) at [hp] of [maxHp]. */
    fun tag(arch: MobArchetype, word: String, hp: Double, maxHp: Double): String {
        val head = when (kindOf(arch)) {
            Kind.GIANT -> "§2$ICON§r§7$ICON_GIANT "
            Kind.BOSS -> when (arch.name) {
                "Spirit Bear" -> "§a$ICON_SPIRIT_BEAR§r§5$ICON_BOSS "
                "Livid" -> "§2$ICON "
                else -> "§e$ICON_BONZO_SCARF§r§5$ICON_BOSS "
            }
            else -> "§2$ICON "
        }
        val colour = WORD_COLOUR[word] ?: "c"
        // A word in the colour the head already ends with (grey after a giant's icon) is written without a code of its own.
        val start = if (kindOf(arch) == Kind.GIANT && colour == "7") "" else "§r§$colour"
        val bold = if (arch.tag?.bold == true) "§l" else ""
        val text = if (word.isEmpty()) arch.name else "$word ${arch.name}"
        if (hp <= 0.0) return "$head$start$bold$text§r §c0❤"
        val hpColour = if (maxHp > 0 && hp <= 0.5 * maxHp) "e" else "a"
        return "$head$start$bold$text§r §$hpColour${MobTags.formatHp(hp)}§r§c❤"
    }

    /** A skin's `textures` property value for texture [id] (slim when [slim]). */
    fun skinValue(id: String, slim: Boolean): String {
        val meta = if (slim) ",\"metadata\":{\"model\":\"slim\"}" else ""
        val json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/$id\"$meta}}}"
        return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }
}
