package com.engineerclient.p3sim.clear.blood.camp

import com.engineerclient.p3sim.clear.blood.HeadTextures
import com.engineerclient.p3sim.pack.rooms.SkullTexture
import kotlin.random.Random

/**
 * Which skull texture each kind of wall head wears. The textures are recorded data, so none is in the mod: the local rooms pack
 * carries them in its texture table under these roles (written by the pack tooling), and a head whose role the pack lacks is a plain
 * head.
 *
 *  - [GIANT]: the giant's head.
 *  - `blood_boss_1` to `blood_boss_4`: the four revived bosses' heads, by the boss's place in [WallPlan.WALL_BOSSES].
 *  - `blood_mob_<name>`: a standard mob's head, by its name (`blood_mob_Mr. Dead`).
 *  - [SOUL]: the Wandering Soul's head.
 *  - `blood_decor_1` and up: the heads that make nothing, one drawn for each.
 *
 * Which boss name wears which of the four boss textures is not measured: each name keeps its own so none repeats.
 */
class WallLook(private val roles: Set<String>) {
    private val decor = roles.filter { it.startsWith(DECOR_PREFIX) }.sorted()

    /** The key to hand to `BloodCampHost.setHead` for [station]. */
    fun keyFor(station: WallStation, rng: Random): String {
        val role = when (station.cls) {
            WallClass.GIANT -> GIANT
            WallClass.BOSS -> boss(WallPlan.WALL_BOSSES.indexOf(station.mobName).coerceAtLeast(0))
            WallClass.WANDERING_SOUL -> SOUL
            WallClass.STANDARD -> mob(station.mobName)
            WallClass.DECOR -> return if (decor.isEmpty()) HeadTextures.PLAIN else decor[rng.nextInt(decor.size)]
        }
        return if (role in roles) role else HeadTextures.PLAIN
    }

    companion object {
        const val GIANT = "blood_giant"
        const val SOUL = "blood_soul"
        const val DECOR_PREFIX = "blood_decor_"
        fun boss(i: Int) = "blood_boss_${i + 1}"
        fun mob(name: String) = "blood_mob_$name"

        /** The look a rooms pack's texture table gives. */
        fun of(table: List<SkullTexture>) = WallLook(table.mapTo(HashSet()) { it.role })
    }
}
