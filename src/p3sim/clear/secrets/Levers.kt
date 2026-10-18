package com.engineerclient.p3sim.clear.secrets

import com.engineerclient.p3sim.pack.Pos
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.SecretKind

/**
 * A lever of a room, in the world: the wall cells it opens and the locked chests (by site key) that it unlocks. Pure.
 */
class LeverInfo(val room: Int, val pos: Pos, val opens: List<Pos>, val chests: List<String>) {
    val key: Long get() = CellGroups.key(pos.x, pos.y, pos.z)
}

object Levers {
    /**
     * The levers this module handles, by their block key: those a room's design lists (with the wall they open) and those that only
     * unlock a locked chest. Puzzle, trap, fairy, entrance and blood rooms are left to their own modules, and so are the three key rooms (see
     * [RedstoneKeys]). Also returns the keys of the
     * locked chests that have no lever at all (they were never locked).
     */
    fun build(plan: DungeonPlan, secrets: SecretPlan): Pair<Map<Long, LeverInfo>, Set<String>> {
        val levers = HashMap<Long, LeverInfo>()
        val unlocked = LinkedHashSet<String>()
        for (r in plan.rooms) {
            val design = r.design ?: continue
            val placement = r.placement ?: continue
            if (r.type != RoomType.NORMAL && r.type != RoomType.RARE && r.type != RoomType.YELLOW) continue
            // A key room has no lever: the redstone block the pack lists as one is the key's socket, and its locked chests open when the key is used.
            if (RedstoneKeys.forDesign(design.id) != null) continue
            val chestsByLever = HashMap<Long, MutableList<String>>()
            for (s in secrets.room(r.index)?.sites.orEmpty()) {
                if (s.kind != SecretKind.LOCKED_CHEST) continue
                val l = s.lever
                if (l == null) { unlocked += s.key; continue }
                chestsByLever.getOrPut(CellGroups.key(l.x, l.y, l.z)) { ArrayList() } += s.key
            }
            for (lv in design.levers) {
                val w = placement.toWorld(lv.pos.x, lv.pos.y, lv.pos.z)
                val k = CellGroups.key(w.x, w.y, w.z)
                val opens = lv.opens.map { c -> placement.toWorld(c.x, c.y, c.z).let { Pos(it.x, it.y, it.z) } }
                levers[k] = LeverInfo(r.index, Pos(w.x, w.y, w.z), opens, chestsByLever[k].orEmpty())
            }
            for ((k, list) in chestsByLever) if (k !in levers) levers[k] = LeverInfo(r.index, secrets.site(list.first())!!.lever!!, emptyList(), list)
        }
        return levers to unlocked
    }
}
