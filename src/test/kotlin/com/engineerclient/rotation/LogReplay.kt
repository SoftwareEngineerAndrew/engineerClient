package com.engineerclient.rotation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Replays a EC session log through the real parser and engine.
 *
 *   ./gradlew replay -Plog=/path/to/brw-20260906-213000.log
 *
 * Feeds every `CHAT` line back in, in order, exactly as the client received it, and prints the
 * engine's decisions alongside. Then compares those decisions with the `ENGINE` lines the mod
 * wrote live: a difference means the replayed build and the build that ran differ — or the
 * engine is nondeterministic, which it must never be. A run that went wrong is diagnosed here by
 * reading down to the first decision that disagrees with what the player saw.
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull()?.let(Path::of)
        ?: System.getProperty("brw.log")?.let(Path::of)
        ?: run { System.err.println("usage: replay <brw-session.log>"); exitProcess(2) }

    val lines = Files.readAllLines(path)
    val me = lines.firstNotNullOfOrNull { l -> Regex("\tSESSION\tI am (\\S+)").find(l)?.groupValues?.get(1) } ?: "?"
    val liveDecisions = lines.filter { "\tENGINE\t" in it }.map { it.substringAfter("\tENGINE\t") }

    val replayed = mutableListOf<String>()
    RotationEngine.trailListener = { replayed += it }
    RotationEngine.masksAvailable = { ign -> MaskTracker.available(ign) }
    MaskTracker.reset()

    val team = LinkedHashMap<String, String>()
    var section = 1; var sectionDone = false; var gate = false
    // Cooldowns run on the game clock live; here they run on the log's own timestamps, or every
    // proc would stay on cooldown forever and the mask gate would replay wrong.
    var lastMillis = -1L
    fun millisOf(t: String): Long {
        val (h, m, rest) = t.split(":"); val (sec, ms) = rest.split(".")
        return ((h.toLong() * 60 + m.toLong()) * 60 + sec.toLong()) * 1000 + ms.toLong()
    }
    println("replaying ${path.fileName} as $me\n")

    for (raw in lines) {
        val parts = raw.split('\t', limit = 3)
        if (parts.size < 3 || parts[1] != "CHAT") continue
        val time = parts[0]; val chat = parts[2]
        val before = replayed.size
        val now = millisOf(time)
        if (lastMillis >= 0 && now > lastMillis) repeat(((now - lastMillis) / 50).toInt().coerceAtMost(20 * 600)) { MaskTracker.tick() }
        lastMillis = now

        P3ChatParser.partyLine(chat)?.let { party ->
            P3ChatParser.startingRole(party.message)?.let { name ->
                RotationSpec.graph.startingRoles.firstOrNull { it.name.equals(name, true) }?.let { role ->
                    team.entries.removeIf { it.value.equals(party.ign, true) }
                    team[role.id] = party.ign
                    if (RotationEngine.running) RotationEngine.addStarter(party.ign, role.id)
                }
                return@let
            }
            MaskTracker.onPartyAnnouncement(party.ign, party.message)
            P3ChatParser.leapedTo(party.message)?.let { RotationEngine.onLeapAnnounce(party.ign, it) }
                ?: RotationEngine.onPartyMessage(party.ign, party.message)
        } ?: run {
            when {
                P3ChatParser.isPhaseStart(chat) -> { section = 1; sectionDone = false; gate = false; RotationEngine.begin(team) }
                P3ChatParser.isPhaseEnd(chat) -> RotationEngine.reset()
                P3ChatParser.isGateDestroyed(chat) -> { gate = true; if (sectionDone) { section++; sectionDone = false; gate = false; println("$time  -- section $section") } }
                else -> P3ChatParser.completion(chat)?.let { c ->
                    if (c.sectionDone) { if (gate) { section++; sectionDone = false; gate = false; println("$time  -- section $section") } else sectionDone = true }
                    RotationEngine.onTaskDone(c.ign, c.type)
                }
            }
        }

        val decided = replayed.drop(before)
        if (decided.isNotEmpty() || P3ChatParser.completion(chat) != null) {
            println("$time  CHAT  ${P3ChatParser.clean(chat).take(90)}")
            decided.forEach { println("              -> $it") }
            RotationEngine.leapTargetFor(me)?.let { println("              leap: ${RotationEngine.leapDebug(me)}") }
        }
    }

    println("\n=== live vs replay ===")
    if (liveDecisions.isEmpty()) {
        println("the log has no ENGINE lines to compare against (synthetic, or the mod never started a rotation); replay made ${replayed.size} decisions")
        RotationEngine.stuck?.let { println("STUCK: $it") }
        return
    }
    val n = maxOf(liveDecisions.size, replayed.size)
    var firstDiff = -1
    for (i in 0 until n) {
        val a = liveDecisions.getOrNull(i); val b = replayed.getOrNull(i)
        if (a != b) { firstDiff = i; break }
    }
    if (firstDiff < 0) println("identical: ${replayed.size} decisions")
    else {
        println("DIFFER at decision ${firstDiff + 1} of live=${liveDecisions.size} replay=${replayed.size}")
        println("  live:   ${liveDecisions.getOrNull(firstDiff)}")
        println("  replay: ${replayed.getOrNull(firstDiff)}")
    }
    RotationEngine.stuck?.let { println("STUCK: $it") }
}
