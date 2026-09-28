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
 * of players killing (2-4), with a separate set for M7. Each role has its boxes, in the order
 * they are killed, and its stack: boxes it helps with once its own are done. There is always a door runner besides, who only rushes
 * the doors and kills nothing.
 *
 * Each player sets how many kill (duo 2, trio 3, quad 4) and their own role in BR Waypoints 2's
 * settings, for a team that runs together. Party chat overrides that for a run, for pickup groups:
 * "!3br 2" is "3 of us are killing, I am role 2", "!br 2" the same at the team size already set,
 * "!br d" (or "!3br d") is "I am on the door". A claim lasts until the dungeon ends. Everyone
 * with the mod keeps track of who has what, and anyone without a role yet is offered the free ones as clickable buttons (they run /brrole).
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

    /** From the settings: how many kill, and your role — null for All Boxes (roles off), 0 the door. */
    @Volatile var settingKilling = 2
    @Volatile var settingRole: Int? = null

    /** What party chat said this run, over the settings. */
    private var chatCount: Int? = null
    private var chatMine: Int? = null

    /**
     * How many kill: party chat's if it has said, else the setting — 0 (roles off) when your
     * setting is All Boxes, or a role the team size doesn't have.
     */
    val count: Int
        get() = chatCount ?: settingRole.let { if (it == null || it > settingKilling) 0 else settingKilling }

    /** Whether you are on the door: you said so in chat, or it is your setting and chat hasn't given you a role. */
    val youOnDoor: Boolean
        get() = doorClaim?.equals(me, ignoreCase = true) ?: (settingRole == 0 && chatMine == null)

    /** Whether roles decide what you see: there is a team size and you have a role or the door. */
    val active: Boolean
        get() = count > 0 && (youOnDoor || mine != null)

    /** Your role (1...), if you have one: claimed in chat this run, else your setting. None on the door. */
    val mine: Int?
        get() {
            if (youOnDoor) return null
            chatMine?.let { return it }
            return settingRole?.takeIf { it in 1..count }
        }
    /** Who has which role, in the order they said so. */
    private val taken = LinkedHashMap<String, Int>()
    /** Who said they are on the door. */
    private var doorClaim: String? = null

    // "Party > [MVP+] name: !3br 2", "!br d", "!3br d" — the rank bracket is absent for players without one.
    private val CLAIM = Regex("""^Party > (?:\[[^]]*] )?(\w{1,16}): !([2-4])?br ([1-4]|d)$""")

    private val me get() = mc.player?.gameProfile?.name()

    /** The door runner: who said so in chat, or you by your setting. */
    val doorRunner: String?
        get() = doorClaim ?: me.takeIf { youOnDoor }

    /** A chat line: a role claim is taken note of. True if it was one. */
    fun onChat(line: String): Boolean {
        val m = CLAIM.matchEntire(line) ?: return false
        val name = m.groupValues[1]
        val n = m.groupValues[2].toIntOrNull()
        val what = m.groupValues[3]
        val you = name.equals(me, ignoreCase = true)
        // A different count is a new sync: everyone picks again.
        if (n != null && n != count) { taken.clear(); chatMine = null }
        if (n != null) chatCount = n
        if (what == "d") {
            doorClaim = name
            taken.remove(name)
            if (you) chatMine = null
            modMessage(Component.literal(if (you) "§dBR §7you are on the §6door" else "§dBR §f$name §7is on the §6door").also { offer(it, you) })
            return true
        }
        // "!br 2": at the team size already set, in chat or in the settings.
        val killing = n ?: chatCount ?: settingKilling
        val role = what.toInt()
        if (role > killing) return true
        chatCount = killing
        taken.remove(name)
        taken[name] = role
        if (doorClaim.equals(name, ignoreCase = true)) doorClaim = null
        if (you) chatMine = role
        val clash = taken.filter { it.value == role && it.key != name }.keys
        val line = Component.literal(if (you) "§dBR §7you are role §f$role §7of §f$count" else "§dBR §f$name §7is role §f$role §7of §f$count")
        if (clash.isNotEmpty()) line.append(Component.literal(" §c(so is ${clash.joinToString()})"))
        offer(line, you)
        modMessage(line)
        return true
    }

    /** The free roles (and the door) as buttons, for anyone who hasn't taken one. */
    private fun offer(line: Component, you: Boolean) {
        if (you || mine != null || youOnDoor || count == 0) return
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
        if (n !in 2..MAX_KILLING || role !in 1..n) return modMessage("§c2 to $MAX_KILLING kill (the door runner is extra): /brrole 3 2")
        sendCommand("pc !${n}br $role")
    }

    fun claimDoor() = sendCommand(if (count > 0) "pc !${count}br d" else "pc !br d")

    /** The roles as they stand, for /brrole with nothing after it. */
    fun status() {
        if (count == 0 && doorRunner == null) return modMessage("§dBR §7no roles yet. §f/brrole <killing> <role>§7 or §f/brrole door§7, or §f!3br 2§7 / §f!br d§7 in party chat.")
        val who = (1..count).joinToString("§7, ") { r -> "§f$r §7${taken.entries.firstOrNull { it.value == r }?.key ?: "§8free"}" }
        val door = doorRunner?.let { "§6door §7$it" } ?: "§6door §8unknown"
        val from = if (chatCount != null) "§8(party chat)" else "§8(settings)"
        modMessage("§dBR §7$count killing $from§7: $who§7, $door" + (mine?.let { " §7· you §f$it" } ?: ""))
    }

    /**
     * A new world. Roles claimed in party chat last until the dungeon they were for is over
     * ([dungeonOver]), then everyone is back on their settings — a claim made before warping in
     * still counts once you are in.
     */
    fun newRun(dungeonOver: Boolean) {
        if (dungeonOver) { taken.clear(); chatCount = null; chatMine = null; doorClaim = null }
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
