package com.engineerclient.p3sim.clear.damage

import com.engineerclient.p3sim.run.DamageSource
import java.util.UUID

/**
 * What each live mob has taken so far, by source: the damage hub's own book of the mob side (the mob's HP itself lives in its
 * record, written only by the mobs module). It answers "who is chewing on this mob" for the dev tools and for tests, and is
 * dropped when the mob dies or leaves.
 */
class MobDamageLedger {
    class Entry {
        var hits = 0
        var total = 0.0
        val bySource: MutableMap<DamageSource, Double> = java.util.EnumMap(DamageSource::class.java)
        var lastBy: UUID? = null
    }

    private val entries = HashMap<UUID, Entry>()

    fun record(mob: UUID, amount: Double, source: DamageSource, by: UUID?) {
        val e = entries.getOrPut(mob) { Entry() }
        e.hits++
        e.total += amount
        e.bySource.merge(source, amount, Double::plus)
        if (by != null) e.lastBy = by
    }

    fun of(mob: UUID): Entry? = entries[mob]

    fun forget(mob: UUID) { entries.remove(mob) }

    fun clear() = entries.clear()

    val size: Int get() = entries.size
}
