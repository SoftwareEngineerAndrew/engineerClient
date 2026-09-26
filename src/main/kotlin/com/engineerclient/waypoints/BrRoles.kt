package com.engineerclient.waypoints

import com.google.gson.JsonParser
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import kotlin.math.abs

/**
 * Blood rush roles: who kills which of a room's BR Waypoints 2 boxes, set on the site
 * (undonecoffee.com/brroles) for each room, each door the rush can come in through, and each number
 * of players killing (2-4; one alone kills every box), with a separate set for M7. Each role has its boxes, in the order they are killed, and its stack:
 * boxes it helps with once its own are done. There is always a door runner besides, who only rushes
 * the doors and kills nothing.
 *
 * The party agrees on roles in party chat: "!3br 2" is "3 of us are killing, I am role 2"; "!br d"
 * (or "!3br d") is "I am on the door". Everyone with the mod keeps track of who has what, and anyone
 * without a role yet is offered the free ones as clickable buttons (they run /brrole).
 *
 * The door runner is also found without being told: at each wither or blood door, whoever got to it
 * first ([doorFell]). Several people can be at a door when it opens; the one there first is the one
 * rushing it.
 */
object BrRoles {

    /** One plan: each role's boxes (by box number) in kill order, and each role's stack. */
    class Plan(val roles: List<List<Int>>, val stacks: List<List<Int>>)

    /** What a box is to you, in the plan for its room. */
    sealed interface Look {
        /** Yours: the [order]th you kill. */
        data class Mine(val order: Int) : Look
        /** In your stack: help with it once yours are done. */
        data object Stack : Look
        /** Someone else's, role [role] (1...), or only in other roles' stacks (0). */
        data class Theirs(val role: Int) : Look
    }

    const val MAX_KILLING = 4

    /** room -> entry door (room x, z) -> players killing -> plan; and the same for M7. */
    @Volatile private var plans: Map<String, Map<Pair<Int, Int>, Map<Int, Plan>>> = emptyMap()
    @Volatile private var m7Plans: Map<String, Map<Pair<Int, Int>, Map<Int, Plan>>> = emptyMap()

    /** The number killing in the party's current sync, 0 before any. */
    var count = 0
        private set
    /** Your role (1...) in it, if you have taken one. */
    var mine: Int? = null
        private set
    /** Who has which role, in the order they said so. */
    private val taken = LinkedHashMap<String, Int>()
    /** Who said they are on the door. */
    private var doorClaim: String? = null
    /** How many doors each player has been first to this run; the most is the door runner. */
    private val firstAt = HashMap<String, Int>()
    private var announcedRunner: String? = null

    // "Party > [MVP+] name: !3br 2", "!br d", "!3br d" — the rank bracket is absent for players without one.
    private val CLAIM = Regex("""^Party > (?:\[[^]]*] )?(\w{1,16}): !([1-4])?br ([1-4]|d)$""")

    private val me get() = mc.player?.gameProfile?.name()

    /** The door runner: who said so, else who has been first to the most doors this run. */
    val doorRunner: String?
        get() = doorClaim ?: firstAt.maxByOrNull { it.value }?.key

    /** Whether you are the door runner. */
    val onDoor get() = doorRunner?.equals(me, ignoreCase = true) == true

    /** A chat line: a role claim is taken note of. True if it was one. */
    fun onChat(line: String): Boolean {
        val m = CLAIM.matchEntire(line) ?: return false
        val name = m.groupValues[1]
        val n = m.groupValues[2].toIntOrNull()
        val what = m.groupValues[3]
        val you = name.equals(me, ignoreCase = true)
        // A different count is a new sync: everyone picks again.
        if (n != null && n != count) { count = n; taken.clear(); mine = null }
        if (what == "d") {
            doorClaim = name
            taken.remove(name)
            if (you) mine = null
            modMessage(Component.literal(if (you) "§dBR §7you are on the §6door" else "§dBR §f$name §7is on the §6door").also { offer(it, you) })
            return true
        }
        if (n == null) return true // "!br 2": a role needs the number killing
        val role = what.toInt()
        if (role > n) return true
        taken.remove(name)
        taken[name] = role
        if (doorClaim.equals(name, ignoreCase = true)) doorClaim = null
        if (you) mine = role
        val clash = taken.filter { it.value == role && it.key != name }.keys
        val line = Component.literal(if (you) "§dBR §7you are role §f$role §7of §f$count" else "§dBR §f$name §7is role §f$role §7of §f$count")
        if (clash.isNotEmpty()) line.append(Component.literal(" §c(so is ${clash.joinToString()})"))
        offer(line, you)
        modMessage(line)
        return true
    }

    /** The free roles (and the door) as buttons, for anyone who hasn't taken one. */
    private fun offer(line: Component, you: Boolean) {
        if (you || mine != null || doorClaim.equals(me, ignoreCase = true) || count == 0) return
        val parts = line as? net.minecraft.network.chat.MutableComponent ?: return
        parts.append(Component.literal(" §7— yours:"))
        for (r in 1..count) {
            val holder = taken.entries.firstOrNull { it.value == r }?.key
            parts.append(if (holder != null) Component.literal(" §8[$r]").withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal("§7Taken by §f$holder"))) }
            else button(" §a[$r]", "/brrole $count $r", "§7Say §f!${count}br $r §7in party chat"))
        }
        if (doorClaim == null) parts.append(button(" §6[door]", "/brrole door", "§7Say §f!br d §7in party chat"))
    }

    private fun button(text: String, command: String, hover: String) = Component.literal(text).withStyle {
        it.withClickEvent(ClickEvent.RunCommand(command)).withHoverEvent(HoverEvent.ShowText(Component.literal(hover)))
    }

    /** Takes role [role] of [n] for the party: says so in party chat, which everyone (you too) reads back. */
    fun claim(n: Int, role: Int) {
        if (n !in 1..MAX_KILLING || role !in 1..n) return modMessage("§cUp to $MAX_KILLING kill (the door runner is extra): /brrole 3 2")
        sendCommand("pc !${n}br $role")
    }

    fun claimDoor() = sendCommand(if (count > 0) "pc !${count}br d" else "pc !br d")

    /** The roles as they stand, for /brrole with nothing after it. */
    fun status() {
        if (count == 0 && doorRunner == null) return modMessage("§dBR §7no roles yet. §f/brrole <killing> <role>§7 or §f/brrole door§7, or §f!3br 2§7 / §f!br d§7 in party chat.")
        val who = (1..count).joinToString("§7, ") { r -> "§f$r §7${taken.entries.firstOrNull { it.value == r }?.key ?: "§8free"}" }
        val door = doorRunner?.let { "§6door §7$it" + if (doorClaim == null) " §8(first to ${firstAt[it]} door${if (firstAt[it] == 1) "" else "s"})" else "" } ?: "§6door §8unknown"
        modMessage("§dBR §7$count killing: $who§7, $door" + (mine?.let { " §7· you §f$it" } ?: ""))
    }

    // --- the door runner, from who gets to the doors first ---------------------------------------

    /** Where each player was over the last while: name -> (tick, x, z), oldest first. */
    private val trail = HashMap<String, ArrayDeque<Triple<Int, Double, Double>>>()
    private const val TRAIL_TICKS = 600
    /** Close enough to a door to be "at" it: the door is 3 wide, and you stand in front of it. */
    private const val AT_DOOR = 3.5
    private var lastDoorTick = 0

    /** Every tick of the rush: where everyone is. */
    fun track(tick: Int) {
        val level = mc.level ?: return
        for (p in level.players()) {
            if (p.uuid.version() == 2) continue // Hypixel's NPCs are players too
            val t = trail.getOrPut(p.gameProfile.name()) { ArrayDeque() }
            t.addLast(Triple(tick, p.x, p.z))
            while (t.isNotEmpty() && t.first().first < tick - TRAIL_TICKS) t.removeFirst()
        }
    }

    /**
     * A wither or blood door started falling at world ([x], [z]): whoever reached it first since the
     * last door fell gets the point. Nobody within reach of it (it opened out of everyone's sight)
     * gives no one anything.
     */
    fun doorFell(tick: Int, x: Double, z: Double) {
        var first: String? = null
        var firstTick = Int.MAX_VALUE
        for ((name, t) in trail) {
            val arrived = t.firstOrNull { it.first > lastDoorTick && abs(it.second - x) <= AT_DOOR && abs(it.third - z) <= AT_DOOR }?.first ?: continue
            if (arrived < firstTick) { firstTick = arrived; first = name }
        }
        lastDoorTick = tick
        first ?: return
        firstAt[first] = (firstAt[first] ?: 0) + 1
        val runner = doorRunner
        if (doorClaim == null && runner != null && runner != announcedRunner) {
            announcedRunner = runner
            modMessage("§dBR §7door runner: §f$runner §8(first to the door)")
        }
    }

    /** A new run (or world): who got to doors first starts again; the party's roles stay. */
    fun newRun() {
        firstAt.clear(); trail.clear(); lastDoorTick = 0; announcedRunner = null
    }

    // --- the plans (the site's brroles.json) -----------------------------------------------------

    fun pull() = BoxSync.pullRoles { body -> mc.execute { adopt(body) } }

    private fun adopt(body: String) {
        val doc = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return
        plans = read(doc["rooms"]?.takeIf { it.isJsonObject }?.asJsonObject ?: return)
        m7Plans = doc["m7"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { read(it) } ?: emptyMap()
    }

    private fun read(rooms: com.google.gson.JsonObject): Map<String, Map<Pair<Int, Int>, Map<Int, Plan>>> {
        val out = HashMap<String, Map<Pair<Int, Int>, Map<Int, Plan>>>()
        for ((room, doors) in rooms.entrySet()) {
            val byDoor = HashMap<Pair<Int, Int>, Map<Int, Plan>>()
            for ((door, counts) in doors.asJsonObject.entrySet()) {
                val xz = door.split(',').mapNotNull { it.toIntOrNull() }
                if (xz.size != 2) continue
                val byCount = HashMap<Int, Plan>()
                for ((n, p) in counts.asJsonObject.entrySet()) runCatching {
                    val o = p.asJsonObject
                    val roles = o["roles"].asJsonArray.map { r -> r.asJsonArray.map { it.asInt } }
                    val stacks = o["stacks"]?.asJsonArray?.map { r -> r.asJsonArray.map { it.asInt } } ?: roles.map { emptyList() }
                    byCount[n.toInt()] = Plan(roles, stacks)
                }
                byDoor[xz[0] to xz[1]] = byCount
            }
            out[room] = byDoor
        }
        return out
    }

    /**
     * The plan for a room entered through a door (in the room's own coordinates), for the number
     * killing now — the door the site has nearest, if it is within a few blocks. Null without one,
     * or before the party has synced.
     */
    fun planFor(room: String, door: Pair<Int, Int>): Plan? {
        if (count == 0) return null
        // In M7 its own roles, else (none set for this room yet) the other floors'.
        if (DungeonUtils.floor == Floor.M7) planIn(m7Plans, room, door)?.let { return it }
        return planIn(plans, room, door)
    }

    private fun planIn(table: Map<String, Map<Pair<Int, Int>, Map<Int, Plan>>>, room: String, door: Pair<Int, Int>): Plan? {
        val doors = table[room] ?: return null
        val near = doors.keys.minByOrNull { abs(it.first - door.first) + abs(it.second - door.second) } ?: return null
        if (abs(near.first - door.first) + abs(near.second - door.second) > 3) return null
        return doors[near]?.get(count)
    }

    /** What box [number] is to you in [plan]; null if the plan leaves it out. */
    fun look(plan: Plan, number: Int): Look? {
        val role = mine
        if (role != null) {
            plan.roles.getOrNull(role - 1)?.indexOf(number)?.takeIf { it >= 0 }?.let { return Look.Mine(it + 1) }
            if (plan.stacks.getOrNull(role - 1)?.contains(number) == true) return Look.Stack
        }
        plan.roles.indexOfFirst { number in it }.takeIf { it >= 0 }?.let { return Look.Theirs(it + 1) }
        if (plan.stacks.any { number in it }) return Look.Theirs(0)
        return null
    }
}
