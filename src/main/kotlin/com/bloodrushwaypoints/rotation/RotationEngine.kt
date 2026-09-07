package com.bloodrushwaypoints.rotation

import com.bloodrushwaypoints.rotation.RotationSpec.Pot
import com.bloodrushwaypoints.rotation.RotationSpec.Role
import org.slf4j.LoggerFactory

/**
 * Runs the phase-3 rotation: who holds which role right now, and who moves where when
 * someone finishes.
 *
 * Pots are streaming, not queues — a player passes through the instant they finish and takes
 * the first exit they qualify for. Attribution is by IGN alone: a completion line credits the
 * next unmatched task of that type in that player's current role, so the mod never has to work
 * out *which* terminal was pressed.
 *
 * Verified against 30,000 random finish schedules (100% clean) before it was written; the same
 * schedules run as a JUnit test in [RotationEngineTest].
 */
object RotationEngine {

    private val logger = LoggerFactory.getLogger("brw-rotation")

    /** One player and what is left of their current role. */
    data class Holder(
        val ign: String,
        var roleId: String,
        /** Task types still outstanding, in no particular order — any of them may land next. */
        val remaining: MutableList<String>,
    )

    /** Why the engine stopped being able to place someone. Surfaced so a bad run is visible, not silent. */
    data class Stuck(val ign: String, val potName: String, val role: String)

    private val graph get() = RotationSpec.graph

    /**
     * How many invincibilities a player has off cooldown. Injected so the engine stays free of
     * Minecraft; the client points it at [MaskTracker], tests point it wherever they like.
     * Everyone in this party carries all three, so a teammate's count is exact rather than an
     * upper bound, and it is derived on every client from the same party announcements — which is
     * what keeps the mask branch decided identically everywhere.
     */
    var masksAvailable: (String) -> Int = { 3 }

    private val holders = LinkedHashMap<String, Holder>()
    private val usedExits = HashMap<String, MutableSet<Int>>()
    private val history = LinkedHashMap<String, MutableList<String>>()

    /** Party messages each player has sent this run, lower-cased — the arrival announcements. */
    private val said = HashMap<String, MutableSet<String>>()

    /** Players whose section-4 (end) role is done, in order: they are heading for the core. */
    private val finished = LinkedHashSet<String>()

    /** Players who announced the recore arrival after finishing, in order — the first is the leap target. */
    private val inCore = LinkedHashSet<String>()

    /**
     * Every decision the engine makes, newest last, so a clip of a run can be read against what
     * the mod believed at each moment. Also written to the game log at INFO under `[brw]`, which
     * is what to pull when a clip shows a wrong cue.
     */
    private val trail = ArrayDeque<String>()
    private const val TRAIL_MAX = 60

    /** Replay/test hook: sees every decision uncapped. */
    var trailListener: ((String) -> Unit)? = null

    private fun note(msg: String) {
        trailListener?.invoke(msg)
        val stamped = "${java.time.LocalTime.now().withNano(0)} $msg"
        trail.addLast(stamped)
        while (trail.size > TRAIL_MAX) trail.removeFirst()
        logger.info("[brw] $msg")
        BrwLog.log("ENGINE", msg)
    }

    fun recent(n: Int = 8): List<String> = trail.takeLast(n)

    fun usedExits(potId: String): Set<Int> = usedExits[potId].orEmpty()

    fun saidBy(ign: String): Set<String> = said[ign.lowercase()].orEmpty()

    /** Set when a player reached a pot with no exit left for them — a defect in the spec or a mis-detected run. */
    var stuck: Stuck? = null
        private set

    var running = false
        private set

    fun reset() {
        holders.clear()
        usedExits.clear()
        history.clear()
        said.clear()
        finished.clear()
        inCore.clear()
        trail.clear()
        stuck = null
        running = false
    }

    /**
     * Start a phase-3 rotation. [bindings] maps a section-1 role id to the IGN that runs it;
     * anyone unbound simply is not tracked.
     */
    fun begin(bindings: Map<String, String>) {
        reset()
        running = true
        note("begin: " + bindings.entries.joinToString { "${it.value}=${graph.name(it.key)}" })
        bindings.forEach { (roleId, ign) -> if (graph.role(roleId) != null) assign(ign, roleId, depth = 0) }
    }

    /**
     * Bind one more starting player after [begin] — their announcement came in late. Ignored if
     * that role is already held or they are already tracked, so a repeat announcement is harmless.
     */
    fun addStarter(ign: String, roleId: String) {
        if (!running || holders.containsKey(ign) || holders.values.any { it.roleId == roleId }) return
        if (graph.role(roleId)?.section != 1) return
        assign(ign, roleId, depth = 0)
    }

    fun roleOf(ign: String): Role? = graph.role(holders[ign]?.roleId)

    fun remainingFor(ign: String): List<String> = holders[ign]?.remaining.orEmpty()

    fun historyOf(ign: String): List<String> = history[ign].orEmpty()

    fun tracked(): List<Holder> = holders.values.toList()

    /**
     * Who [ign] should spirit-leap to, or null when there is nobody to leap to.
     *
     * The team crosses a gate by leaping to whoever early-entered the section beyond it, so the
     * target is simply the holder of the early-enter role for the section this player's own role
     * sits in — with the obvious exception that the early-enterer does not leap to themselves.
     */
    fun leapTargetFor(ign: String): String? {
        val myRole = graph.role(holders[ign]?.roleId) ?: return null
        val rules = myRole.leapRules.ifEmpty {
            listOf(RotationSpec.LeapRule(graph.earlyEnterInto(myRole.section)?.id ?: return null))
        }
        for (rule in rules) {
            if (rule.target == RotationSpec.NO_LEAP) return null
            if (rule.requires != null && whoHeld(rule.requires) == null) continue
            if (rule.target == myRole.id) continue
            // The early-enterer does not leap to themselves, and neither does the player who
            // simply carried straight on from that role.
            whoHeld(rule.target)?.takeUnless { it.equals(ign, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    /** A party message from [ign]. Only arrival texts matter, but recording all is cheaper than deciding. */
    fun onPartyMessage(ign: String, message: String) {
        if (!running) return
        val text = message.trim().lowercase()
        if (graph.roles.any { it.arrived.equals(text, ignoreCase = true) }) note("$ign arrived: \"$text\"")
        said.getOrPut(ign.lowercase()) { mutableSetOf() }.add(text)

        // Some roles finish on their arrival announcement rather than on a completion line.
        holders[ign]?.let { h ->
            val role = graph.role(h.roleId)
            if (role != null && role.completeOnArrived && role.arrived.equals(text, ignoreCase = true)) {
                h.remaining.clear()
                note("$ign ${role.name} COMPLETE (arrived)")
                complete(ign, depth = 0)
            }
        }
        // Recore: in the core, having finished section 4.
        if (text == graph.recoreArrived.trim().lowercase() && ign in finished && inCore.add(ign)) note("$ign is in the core")
    }

    /**
     * The section's counter hit its total, so every role still open in that section was done by
     * someone else — count it as complete for whoever holds it. Called AFTER the closing line's own
     * credit, so the player who actually finished it is routed first.
     */
    fun completeSection(section: Int) {
        holders.values.toList().forEach { h ->
            val role = graph.role(h.roleId) ?: return@forEach
            if (role.section == section && h.remaining.isNotEmpty() && holders[h.ign] === h) {
                note("${h.ign} ${role.name} COMPLETE (section $section done, still had ${h.remaining})")
                h.remaining.clear()
                complete(h.ign, depth = 0)
            }
        }
    }

    fun isFinished(ign: String): Boolean = ign in finished

    /** Who [ign] leaps to for the recore: the first teammate in the core, or null while nobody is. */
    fun recoreTargetFor(ign: String): String? =
        if (ign !in finished) null else inCore.firstOrNull { !it.equals(ign, ignoreCase = true) }

    /**
     * Odin's leap announcement — "Leaped to X!". Whoever leapt is now wherever X is, so they
     * inherit X's arrivals. This is what makes "the 1st-terminal player leapt into s4, so they
     * can now be leapt to" observable without a box of their own.
     */
    fun onLeapAnnounce(leaper: String, target: String) {
        if (!running) return
        note("$leaper leaped to $target")
        val mine = said.getOrPut(leaper.lowercase()) { mutableSetOf() }
        mine.add(RotationSpec.ARRIVED_ON_LEAP)
        said[target.lowercase()]?.let { inherited -> mine.addAll(inherited - RotationSpec.ARRIVED_ON_LEAP) }
    }

    /**
     * Whether the leap [ign] should make is ready — the person they are waiting on has announced
     * arrival. Null when the strategy has no announcement to wait for, so the caller can fall back
     * to something weaker like position.
     */
    fun leapReadyFor(ign: String): Boolean? {
        val myRole = graph.role(holders[ign]?.roleId) ?: return null
        val rule = activeLeapRule(ign, myRole) ?: return null
        val waitRole = graph.role(rule.waitFor ?: rule.target) ?: return null
        if (waitRole.arrived.isBlank()) return null
        val who = whoHeld(waitRole.id) ?: return false
        return said[who.lowercase()]?.contains(waitRole.arrived.trim().lowercase()) == true
    }

    /** A one-line account of the current leap cue for [ign], for the debug display. */
    fun leapDebug(ign: String): String {
        val myRole = graph.role(holders[ign]?.roleId) ?: return "no role"
        val rule = activeLeapRule(ign, myRole) ?: return "no leap"
        val target = whoHeld(rule.target) ?: "?"
        val waitRole = graph.role(rule.waitFor ?: rule.target) ?: return "-> $target (bad wait role)"
        val waiter = whoHeld(waitRole.id) ?: "?"
        val ready = leapReadyFor(ign)
        val how = when {
            waitRole.arrived.isBlank() -> "no arrival text → position fallback"
            waitRole.arrived == RotationSpec.ARRIVED_ON_LEAP -> "$waiter leapt as ${waitRole.name}?"
            else -> "$waiter said \"${waitRole.arrived}\"?"
        }
        return "-> $target  ${if (ready == true) "READY" else if (ready == false) "waiting" else "?"}  ($how)"
    }

    /** The rule [leapTargetFor] would act on right now, or null. */
    private fun activeLeapRule(ign: String, myRole: Role): RotationSpec.LeapRule? {
        val rules = myRole.leapRules.ifEmpty {
            listOf(RotationSpec.LeapRule(graph.earlyEnterInto(myRole.section)?.id ?: return null))
        }
        for (rule in rules) {
            if (rule.target == RotationSpec.NO_LEAP) return null
            if (rule.requires != null && whoHeld(rule.requires) == null) continue
            if (rule.target == myRole.id) continue
            whoHeld(rule.target)?.takeUnless { it.equals(ign, ignoreCase = true) } ?: continue
            return rule
        }
        return null
    }

    /** Who holds [roleId] now, or who held it earlier this run once they have moved on. */
    private fun whoHeld(roleId: String): String? =
        holders.values.firstOrNull { it.roleId == roleId }?.ign
            ?: history.entries.firstOrNull { roleId in it.value }?.key

    /** True once every tracked player has run out of roles. */
    fun finished(): Boolean = running && holders.isEmpty()

    /**
     * Credit one completion line. Returns the role the player moved ON TO when this finished
     * their role, or null when the line only advanced their progress (or was not ours).
     */
    fun onTaskDone(ign: String, type: String): Role? {
        if (!running) return null
        val holder = holders[ign] ?: return null
        val idx = holder.remaining.indexOf(type)
        if (idx < 0) {
            note("$ign $type — not in ${graph.name(holder.roleId)}'s remaining ${holder.remaining}, ignored")
            return null
        }
        holder.remaining.removeAt(idx)
        if (holder.remaining.isNotEmpty()) {
            note("$ign $type ✓ ${graph.name(holder.roleId)} still needs ${holder.remaining}")
            return null
        }
        note("$ign $type ✓ ${graph.name(holder.roleId)} COMPLETE")
        return complete(ign, depth = 0)
    }

    // ---------------------------------------------------------------- routing

    private fun assign(ign: String, roleId: String, depth: Int): Role? {
        val role = graph.role(roleId) ?: return null
        holders[ign] = Holder(ign, roleId, role.checked.map { it.type }.toMutableList())
        history.getOrPut(ign) { mutableListOf() }.add(roleId)
        note("$ign -> ${role.name} (S${role.section})" + if (role.checked.isEmpty()) " [instant]" else " needs ${holders[ign]!!.remaining}")
        // A leap only counts towards the role it was made under — leaping into s2 says nothing
        // about being through into s4.
        said[ign.lowercase()]?.remove(RotationSpec.ARRIVED_ON_LEAP)
        // A role with nothing to wait for is done the moment it is handed out — that is how
        // `core` reaches the final pot first, and how `l+ee2` hands straight on to role 5.
        if (role.checked.isEmpty() && !role.completeOnArrived && depth < MAX_DEPTH) return complete(ign, depth + 1) ?: role
        return role
    }

    private fun complete(ign: String, depth: Int): Role? {
        val holder = holders[ign] ?: return null
        val finished = graph.role(holder.roleId) ?: return null
        if (depth >= MAX_DEPTH) {
            logger.warn("[brw] rotation: hand-off chain too deep at ${finished.name}")
            return null
        }
        return when (finished.exit.kind) {
            "end" -> {
                note("$ign done (${finished.name} was an end role) — recore")
                holders.remove(ign)
                this.finished.add(ign)
                null
            }

            "forced" -> {
                val to = finished.exit.to ?: return null.also { holders.remove(ign) }
                assign(ign, to, depth + 1)
            }

            else -> {
                val pot = graph.potsFor(finished.id).firstOrNull()
                if (pot == null) {
                    logger.warn("[brw] rotation: ${finished.name} finished but no pot lists it")
                    holders.remove(ign)
                    null
                } else {
                    enterPot(ign, pot, finished.id, depth + 1)
                }
            }
        }
    }

    /** Walk a player through a pot, following pot-to-pot exits until they land on a role. */
    private fun enterPot(ign: String, startPot: Pot, justFinished: String, depth: Int): Role? {
        var pot = startPot
        repeat(MAX_DEPTH) {
            val used = usedExits.getOrPut(pot.id) { mutableSetOf() }
            val isLast = holders.none { (other, holder) ->
                other != ign && canReach(holder.roleId, pot.id, other, mutableSetOf())
            }
            // A mask requirement asks for the best cover available, not an absolute number: an
            // exit wanting two invincibilities settles for one when nobody in the party has two,
            // and for none when the party is dry. Without that it would gate on something no one
            // can satisfy and stall the board.
            val bestInParty = holders.keys.maxOfOrNull { masksAvailable(it) } ?: 0
            fun qualifies(i: Int): Boolean {
                val exit = pot.exits[i]
                val needed = minOf(exit.mask, bestInParty)
                return i !in used &&
                    (!exit.last || isLast) &&
                    (exit.cond.isEmpty() || justFinished in exit.cond) &&
                    (needed <= 0 || masksAvailable(ign) >= needed)
            }
            val index = pot.exits.indices.firstOrNull { qualifies(it) }
            if (index == null) {
                stuck = Stuck(ign, pot.name, graph.name(justFinished))
                note("STUCK: $ign reached ${pot.name} (just did ${graph.name(justFinished)}) with no exit — used ${used.map { it + 1 }}, last=$isLast, masks=${masksAvailable(ign)}")
                logger.warn("[brw] rotation: $ign reached ${pot.name} with no exit left")
                holders.remove(ign)
                return null
            }
            used.add(index)
            val ex = pot.exits[index]
            note("$ign through ${pot.name} exit ${index + 1} -> ${graph.name(ex.targets.firstOrNull())}" +
                (if (ex.last) " [last]" else "") + (if (ex.cond.isNotEmpty()) " [cond ok]" else "") +
                (if (ex.mask > 0) " [mask ${masksAvailable(ign)}/${ex.mask}]" else ""))
            val target = pot.exits[index].targets.firstOrNull() ?: return null.also { holders.remove(ign) }
            val next = graph.pot(target)
            if (next != null) {
                pot = next
            } else {
                return assign(ign, target, depth + 1)
            }
        }
        return null
    }

    /**
     * Could a player holding [roleId] still arrive at [potId]? Walks the graph carrying the role
     * they last finished, so exit conditions are honoured, and skips exits already consumed.
     * This is what makes a `last`-reserved exit decidable the moment someone passes through.
     */
    private fun canReach(roleId: String, potId: String, ign: String, seen: MutableSet<Pair<String, String>>): Boolean {
        val role = graph.role(roleId) ?: return false
        return when (role.exit.kind) {
            "end" -> false
            "forced" -> role.exit.to?.let { canReach(it, potId, ign, seen) } == true
            else -> graph.potsFor(role.id).any { reachFromPot(it.id, role.id, potId, ign, seen) }
        }
    }

    private fun reachFromPot(potId: String, justFinished: String, target: String, ign: String, seen: MutableSet<Pair<String, String>>): Boolean {
        if (potId == target) return true
        if (!seen.add(potId to justFinished)) return false
        val pot = graph.pot(potId) ?: return false
        val used = usedExits[potId].orEmpty()
        pot.exits.forEachIndexed { i, exit ->
            if (i in used) return@forEachIndexed
            if (exit.cond.isNotEmpty() && justFinished !in exit.cond) return@forEachIndexed
            if (exit.mask > 0 && masksAvailable(ign) < exit.mask) return@forEachIndexed
            exit.targets.forEach { t ->
                val hit = if (graph.isPot(t)) reachFromPot(t, justFinished, target, ign, seen)
                else canReach(t, target, ign, seen)
                if (hit) return true
            }
        }
        return false
    }

    private const val MAX_DEPTH = 24
}
