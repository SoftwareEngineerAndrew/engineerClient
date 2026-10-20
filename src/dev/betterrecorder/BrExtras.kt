package com.devgineerclient.betterrecorder

import com.devgineerclient.DevgineerClient
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player

/**
 * The captures beyond the plain record, always on: each hit you take with everything near you (melee reach and swing rate), which
 * living things can see you (aggro), every name stand's shown/hidden state with your distance and sight (tag rules), and rare sightings
 * ("wanted": the first time one turns up chat says so, and `/brec wanted` lists how often each has been caught).
 */
object BrExtras {
    /** Sightings worth a line in chat the first time each turns up. */
    class Wanted(val id: String, val title: String, val match: (String) -> Boolean)

    val WANTED = listOf(
        Wanted("wandering_soul", "Wandering Soul (its skin)") { it.contains("Wandering Soul") },
        Wanted("crypt_undead", "Crypt Undead") { it.contains("Crypt Undead") },
        Wanted("skeletor_prime", "Skeletor Prime") { it.contains("Skeletor Prime") },
        Wanted("red_qqq", "the red ??? stand") { it.contains("§c§l???") },
        Wanted("shadow_assassin", "Shadow Assassin") { it.contains("Shadow Assassin") },
        Wanted("king_midas", "King Midas") { it.contains("King Midas") },
        Wanted("mimic", "Mimic") { it.contains("Mimic") },
        Wanted("prince", "Prince") { it.contains("Prince") },
    )

    private val caughtThisRun = HashSet<String>()
    private var lastSeeing = ""

    fun newRun() { caughtThisRun.clear(); lastSeeing = "" }

    fun spawned(s: BrSession, e: Entity) {
        val name = e.customName?.let { BrJson.legacy(it) } ?: (e as? Player)?.gameProfile?.name ?: return
        for (w in WANTED) if (w.match(name) && caughtThisRun.add(w.id)) {
            s.line("wanted") { j -> j.s("id", w.id).i("e", e.id).s("name", name) }
            // Counted (and said) only on Hypixel: ADS runs are not real sightings.
            if (s.where == "HYP") BetterRecorder.caught(w.id, w.title)
        }
    }

    fun tick(s: BrSession) {
        val mc = DevgineerClient.mc
        val me = mc.player ?: return
        val all = mc.level?.entitiesForRendering() ?: return
        // A hit taken: everything living within 8 blocks, with its distance, whether it sees you and how far into its swing it is (0 to 1).
        if (me.hurtTime == 10) {
            val near = all.filter { it !== me && it is LivingEntity && it.distanceTo(me) < 8f }
            s.line("hit") { j ->
                j.f("hp", me.health).raw("near", near.joinToString(",", "[", "]") { e ->
                    "[${e.id},${BrJson.n3(e.distanceTo(me).toDouble())},${if (me.hasLineOfSight(e)) 1 else 0},${BrJson.n3((e as LivingEntity).getSwingAnimation(1f).toDouble())}]"
                })
            }
        }
        // Who can see you (mobs within 32 blocks), when that set changes.
        val seeing = all.filter { it !== me && it is LivingEntity && it !is ArmorStand && it.isAlive && it.distanceTo(me) < 32f && me.hasLineOfSight(it) }
            .map { it.id }.sorted().joinToString(",", "[", "]")
        if (seeing != lastSeeing) { lastSeeing = seeing; s.line("sees") { it.raw("ids", seeing) } }
        // Name stands every 2 ticks: shown or not, your distance, your sight.
        if (s.t % 2 == 0) {
            val stands = all.filter { it is ArmorStand && it.customName != null && it.distanceTo(me) < 48f }
            if (stands.isNotEmpty()) s.line("tagsee") { j ->
                j.raw("s", stands.joinToString(",", "[", "]") { "[${it.id},${if (it.isCustomNameVisible) 1 else 0},${BrJson.n3(it.distanceTo(me).toDouble())},${if (me.hasLineOfSight(it)) 1 else 0}]" })
            }
        }
    }
}
