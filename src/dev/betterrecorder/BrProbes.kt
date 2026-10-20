package com.devgineerclient.betterrecorder

import com.devgineerclient.DevgineerClient
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player

/**
 * Probes: the questions ADS still has about the clear that only a run can answer. Each probe says what to do in the run; while it is
 * on, its start and end are marked in the file and any extra capture it needs runs. Rare sightings ("wanted") are always watched:
 * the first time one turns up in a recording, chat says so, and `/brec wanted` lists how often each has been caught so far.
 */
object BrProbes {
    class Probe(val id: String, val title: String, val how: String)

    val PROBES = listOf(
        Probe("melee", "Mob melee", "Let ordinary zombies and skeletons hit you for a while without killing them (stand still, then back away slowly). Records each hit you take with every mob near you, to get melee reach and swing rate."),
        Probe("aggro", "Aggro and walks", "Walk slowly up to a room's mobs from far away and stop at 30, 20, 12 and 6 blocks for 5 seconds each. Records when they turn, start walking and where to."),
        Probe("tags", "Name tags", "Walk round a room of starred mobs without killing them, in and out of sight behind walls. Records every name tag's shown/hidden switch with your distance and line of sight."),
        Probe("damage", "Damage numbers", "Hit mobs with one weapon at a time (sword, bow, Implosion, beam) and note which. Records each hit against the numbers that appear."),
        Probe("puzzle", "Puzzle", "Stand in a puzzle room before anyone touches it, and stay until it is done (do it yourself if you can). The room is snapshotted before and after and every block, entity and sound in it is kept."),
        Probe("boulder", "Boulder pushes", "In Boulder, press boulders one at a time, waiting a second between presses, including some wrong ones."),
        Probe("doors", "Doors and keys", "Open wither doors and the blood door yourself; try one without a key first."),
        Probe("crypts", "Crypts", "Blow crypts with Superboom from close by; note any Crypt Undead."),
        Probe("abilities", "Class abilities", "Use your Ultimate and Ability near mobs and teammates, with a few seconds between, and try them again on cooldown."),
        Probe("deaths", "Deaths and revives", "Note when someone dies and is revived (or revive someone), and what the score does."),
    )

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

    private val on = LinkedHashSet<String>()
    private val caughtThisRun = HashSet<String>()

    fun active(): List<String> = on.toList()

    fun start(id: String): String {
        val p = PROBES.firstOrNull { it.id == id } ?: return "§cNo probe \"$id\". /brec probes lists them."
        on += id
        BetterRecorder.session?.line("probe") { j -> j.s("id", id).b("on", true) }
        return "§aProbe ${p.title} on.§r ${p.how}"
    }

    fun stop(id: String?): String {
        val ids = if (id == null) on.toList() else listOf(id)
        for (x in ids) if (on.remove(x)) BetterRecorder.session?.line("probe") { j -> j.s("id", x).b("on", false) }
        return if (ids.isEmpty()) "No probe is on." else "Stopped: ${ids.joinToString()}"
    }

    fun newRun() { caughtThisRun.clear(); for (id in on) BetterRecorder.session?.line("probe") { j -> j.s("id", id).b("on", true) } }

    // ------------------------------------------------------------------ hooks from the session (game thread)

    fun spawned(s: BrSession, e: Entity) {
        val name = e.customName?.let { BrJson.legacy(it) } ?: (e as? Player)?.gameProfile?.name ?: return
        for (w in WANTED) if (w.match(name) && caughtThisRun.add(w.id)) {
            s.line("wanted") { j -> j.s("id", w.id).i("e", e.id).s("name", name) }
            BetterRecorder.caught(w.id, w.title)
        }
    }

    fun gone(s: BrSession, e: Entity) {}

    /** Per tick: the melee probe writes everything within 6 blocks of you, with whether it can see you, each tick you are hit. */
    fun tick(s: BrSession) {
        val mc = DevgineerClient.mc
        val me = mc.player ?: return
        if ("melee" in on && me.hurtTime == 10) {
            val near = mc.level?.entitiesForRendering()?.filter { it !== me && it is LivingEntity && it.distanceTo(me) < 6f }.orEmpty()
            s.line("hit") { j ->
                j.f("hp", me.health).raw("near", near.joinToString(",", "[", "]") { "[${it.id},${BrJson.n3(it.distanceTo(me).toDouble())},${me.hasLineOfSight(it)}]" })
            }
        }
        if ("tags" in on && s.t % 2 == 0) {
            val stands = mc.level?.entitiesForRendering()?.filter { it is net.minecraft.world.entity.decoration.ArmorStand && it.customName != null && it.distanceTo(me) < 40f }.orEmpty()
            if (stands.isNotEmpty()) s.line("tagsee") { j ->
                j.raw("s", stands.joinToString(",", "[", "]") { "[${it.id},${if (it.isCustomNameVisible) 1 else 0},${BrJson.n3(it.distanceTo(me).toDouble())},${if (me.hasLineOfSight(it)) 1 else 0}]" })
            }
        }
    }
}
