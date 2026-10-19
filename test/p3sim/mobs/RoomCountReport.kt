package com.engineerclient.p3sim.mobs

import com.engineerclient.p3sim.clear.mobs.MobRolls
import com.engineerclient.p3sim.pack.Packs
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * Optional report, not a check: per room of the REAL mob pack, the starred and unstarred counts of its recorded F7 visits next to 300 rolls of
 * the sim's own draw. Runs only with ADS_REAL_PACKS and ADS_ROOM_REPORT (the JSON file it writes).
 */
class RoomCountReport {
    @Test fun writeRoomCounts() {
        val dir = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
        val out = System.getenv("ADS_ROOM_REPORT")
        assumeTrue(dir != null && out != null, "ADS_REAL_PACKS and ADS_ROOM_REPORT not both set")
        val set = Packs.load(dir!!)
        try {
            val pack = set.mobs ?: return
            val sb = StringBuilder("[")
            val rng = Random(7)
            for (rm in pack.rooms) {
                val visits = rm.layouts.filter { it.fl == "F7" || it.fl == "?" }
                if (visits.isEmpty()) continue
                val rs = visits.map { v -> v.mobs.count { it.star } }; val ru = visits.map { v -> v.mobs.count { !it.star } }
                val ss = ArrayList<Int>(); val su = ArrayList<Int>()
                repeat(300) {
                    val r = MobRolls.rollRoom(rm, "F7", rng)
                    ss += r.count { it.star }; su += r.count { !it.star }
                }
                if (sb.length > 1) sb.append(",\n")
                sb.append("{\"room\":\"${rm.name.replace("\"", "")}\",\"clear\":\"${rm.clear}\",\"type\":\"${rm.type ?: ""}\",\"visits\":${visits.size},")
                sb.append("\"realStar\":$rs,\"realUnstar\":$ru,\"simStar\":$ss,\"simUnstar\":$su}")
            }
            sb.append("]")
            Files.writeString(Path.of(out!!), sb.toString())
        } finally { (set as? AutoCloseable)?.close() }
    }
}
