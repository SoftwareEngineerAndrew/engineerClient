package com.engineerclient.waypoints

import com.google.gson.JsonParser
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.sendCommand
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/**
 * Blood rush roles: who kills which of a room's BR Waypoints 2 boxes, set on the site
 * (undonecoffee.com/brroles) for each room, each door the rush can come in through, and each number
 * of players killing. A role is a list of boxes in the order they are killed; the stack is boxes no
 * one owns, that everyone goes to once their own are done.
 *
 * The party agrees on roles in party chat: "!4br 2" is "4 of us are killing, I am role 2". Everyone
 * with the mod keeps track of who has what, and anyone without a role yet is offered the free ones
 * as clickable buttons, which send their own "!4br n" ([claim], or /brrole 4 n).
 */
object BrRoles {

    /** One plan: each role's boxes (by box number) in kill order, and the stack. */
    class Plan(val roles: List<List<Int>>, val stack: List<Int>)

    /** What a box is to you, in the plan for its room. */
    sealed interface Look {
        /** Yours: the [order]th you kill. */
        data class Mine(val order: Int) : Look
        /** The stack: everyone's, once their own are done. */
        data class Stack(val order: Int) : Look
        /** Someone else's: role [role] (1...). */
        data class Theirs(val role: Int) : Look
    }

    /** room -> entry door (room x, z) -> players killing -> plan. */
    @Volatile private var plans: Map<String, Map<Pair<Int, Int>, Map<Int, Plan>>> = emptyMap()

    /** The number killing in the party's current sync, 0 before any. */
    var count = 0
        private set
    /** Your role (1...) in it, if you have taken one. */
    var mine: Int? = null
        private set
    /** Who has which role, in the order they said so. */
    private val taken = LinkedHashMap<String, Int>()

    // "Party > [MVP+] name: !4br 2" — the rank bracket is absent for players without one.
    private val CLAIM = Regex("""^Party > (?:\[[^]]*] )?(\w{1,16}): !([1-5])br ([1-5])$""")

    private val me get() = mc.player?.gameProfile?.name()

    /** A chat line: a role claim is taken note of. True if it was one. */
    fun onChat(line: String): Boolean {
        val m = CLAIM.matchEntire(line) ?: return false
        val name = m.groupValues[1]
        val n = m.groupValues[2].toInt()
        val role = m.groupValues[3].toInt()
        if (role > n) return true
        // A different count is a new sync: everyone picks again.
        if (n != count) { count = n; taken.clear(); mine = null }
        taken.remove(name)
        taken[name] = role
        val you = name.equals(me, ignoreCase = true)
        if (you) mine = role
        announce(name, role, you)
        return true
    }

    /** Says who took what; offers the free roles to anyone who hasn't taken one. */
    private fun announce(name: String, role: Int, you: Boolean) {
        val clash = taken.filter { it.value == role && it.key != name }.keys
        val line = Component.literal(if (you) "§dBR §7you are role §f$role §7of §f$count" else "§dBR §f$name §7is role §f$role §7of §f$count")
        if (clash.isNotEmpty()) line.append(Component.literal(" §c(so is ${clash.joinToString()})"))
        if (!you && mine == null) {
            line.append(Component.literal(" §7— yours:"))
            for (r in 1..count) {
                val holder = taken.entries.firstOrNull { it.value == r }?.key
                val button = if (holder != null) Component.literal(" §8[$r]").withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal("§7Taken by §f$holder"))) }
                else Component.literal(" §a[$r]").withStyle {
                    it.withClickEvent(ClickEvent.RunCommand("/brrole $count $r"))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("§7Say §f!${count}br $r §7in party chat")))
                }
                line.append(button)
            }
        }
        modMessage(line)
    }

    /** Takes role [role] of [n] for the party: says so in party chat, which everyone (you too) reads back. */
    fun claim(n: Int, role: Int) {
        if (n !in 1..5 || role !in 1..n) return modMessage("§cA role is 1 to the number killing (at most 5): /brrole 4 2")
        sendCommand("pc !${n}br $role")
    }

    /** The roles as they stand, for /brrole with nothing after it. */
    fun status() {
        if (count == 0) return modMessage("§dBR §7no roles yet. §f/brrole <killing> <role>§7, or §f!4br 2§7 in party chat.")
        val who = (1..count).joinToString("§7, ") { r -> "§f$r §7${taken.entries.firstOrNull { it.value == r }?.key ?: "§8free"}" }
        modMessage("§dBR §7$count killing: $who" + (mine?.let { " §7· you §f$it" } ?: ""))
    }

    // --- the plans (the site's brroles.json) -----------------------------------------------------

    fun pull() = BoxSync.pullRoles { body -> mc.execute { adopt(body) } }

    private fun adopt(body: String) {
        val rooms = runCatching { JsonParser.parseString(body).asJsonObject["rooms"].asJsonObject }.getOrNull() ?: return
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
                    val stack = o["stack"]?.asJsonArray?.map { it.asInt }.orEmpty()
                    byCount[n.toInt()] = Plan(roles, stack)
                }
                byDoor[xz[0] to xz[1]] = byCount
            }
            out[room] = byDoor
        }
        plans = out
    }

    /**
     * The plan for a room entered through a door (in the room's own coordinates), for the number
     * killing now — the door the site has nearest, if it is within a few blocks. Null without one,
     * or before the party has synced.
     */
    fun planFor(room: String, door: Pair<Int, Int>): Plan? {
        if (count == 0) return null
        val doors = plans[room] ?: return null
        val near = doors.keys.minByOrNull { kotlin.math.abs(it.first - door.first) + kotlin.math.abs(it.second - door.second) } ?: return null
        if (kotlin.math.abs(near.first - door.first) + kotlin.math.abs(near.second - door.second) > 3) return null
        return doors[near]?.get(count)
    }

    /** What box [number] is to you in [plan]; null if the plan leaves it out (or you have no role). */
    fun look(plan: Plan, number: Int): Look? {
        val role = mine
        if (role != null) plan.roles.getOrNull(role - 1)?.indexOf(number)?.takeIf { it >= 0 }?.let { return Look.Mine(it + 1) }
        plan.stack.indexOf(number).takeIf { it >= 0 }?.let { return Look.Stack(it + 1) }
        plan.roles.indexOfFirst { number in it }.takeIf { it >= 0 }?.let { return Look.Theirs(it + 1) }
        return null
    }
}
