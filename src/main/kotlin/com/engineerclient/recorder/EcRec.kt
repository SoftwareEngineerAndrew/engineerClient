package com.engineerclient.recorder

import com.engineerclient.ClassDetect
import com.engineerclient.EcConfig
import com.engineerclient.EngineerClient
import com.engineerclient.RushProfiles
import com.engineerclient.practice.OdinSimonSays
import com.engineerclient.practice.TermInfo
import com.engineerclient.rotation.EcLog
import com.engineerclient.rotation.LeapSignal
import com.engineerclient.rotation.MaskTracker
import com.engineerclient.rotation.P3Rotation
import com.engineerclient.rotation.RotationEngine
import com.engineerclient.splits.DungeonSplits
import com.engineerclient.splits.Stamp
import com.engineerclient.storm.StormPhase
import com.engineerclient.waypoints.BrRoles
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

/**
 * Engineer Client's own modules, as the recorder sees them: the splits and sub splits, the
 * scorecard, the blood rush room by room, every world observation the splits act on, the P3
 * rotation and its decision trail, EcLog, Storm's crush checks, the Simon Says solver, Term Info,
 * Blood Rush Waypoints 2, Leap Extras, the class/rush profile, and EC's own clocks.
 *
 * The EC modules call in here at the moment something happens (all `ec.*` kinds, plus `label` and
 * `label.gone` with "by":"ec"). Their state is plain Kotlin owned by the thread that calls, so the
 * body is built right there, from finished values, before it is queued. Every call is a single
 * volatile read when nothing is recording: the body lambdas are inline and never run then.
 *
 * Every line that comes from an EC moment carries EC's own stamp as "at":{"ms","tick"} — its real
 * time and DungeonSplits' server-tick count, the two clocks every EC time is measured on — so EC's
 * numbers can be checked against the envelope's t, n and ms. `ec.clocks` gives the offsets of EC's
 * separate tick counters (DungeonSplits, StormPhase, TermInfo, the SS solver) to n.
 *
 * Never calls DungeonSplits.subLines/scorecardCells/gradedSteps/recordBest: those save the config.
 */
object EcRec {

    /** A finished line (the contract's plain form). Does nothing when not recording. */
    fun emit(kind: String, body: String) = Rec.emit(kind, body)

    /** A line whose body is built only when recording; a failure in it becomes an `error` line, never the caller's problem. */
    inline fun line(kind: String, body: (Obj) -> Unit) {
        if (!Rec.active) return
        try {
            val o = Obj()
            body(o)
            Rec.emit(kind, o.toString())
        } catch (t: Throwable) {
            failed(kind, t)
        }
    }

    /**
     * A snapshot written only when it differs from the last one under [key] (game thread, per
     * part). [compare] is what decides "changed" — the snapshot minus anything that ticks on its own.
     */
    inline fun changed(kind: String, key: String, compare: (Obj) -> Unit, extra: (Obj) -> Unit = {}) {
        if (!Rec.active) return
        try {
            val o = Obj()
            compare(o)
            val body = o.toString()
            if (!Rec.changed("ec:$key", body)) return
            extra(o)
            Rec.emit(kind, o.toString())
        } catch (t: Throwable) {
            failed(kind, t)
        }
    }

    /** One of the splits' world observations: what, how it was seen, who (a guess, when it is one) and its value. */
    inline fun obs(at: Stamp, what: String, how: String, who: String? = null, value: (Obj) -> Unit = {}) = line("ec.obs") { o ->
        o.at(at).str("what", what).str("how", how)
        if (who != null) o.str("who", who)
        value(o)
    }

    /** The same, written only when its value changed (continuous readings: positions, distances, states). */
    inline fun obsChanged(at: Stamp, what: String, how: String, value: (Obj) -> Unit) {
        if (!Rec.active) return
        try {
            val v = Obj(); value(v)
            if (!Rec.changed("ec:obs:$what", v.toString())) return
            val o = Obj().at(at).str("what", what).str("how", how)
            value(o)
            Rec.emit("ec.obs", o.toString())
        } catch (t: Throwable) {
            failed("ec.obs", t)
        }
    }

    fun failed(kind: String, t: Throwable) {
        EngineerClient.logger.error("[ec] recorder $kind failed", t)
        runCatching { Rec.emit("error", "\"p\":${RecorderFiles.q(kind)},\"err\":${RecorderFiles.q(t.toString())}") }
    }

    /**
     * JSON members without the braces, written in order. Numbers follow the recorder's rules
     * (shortest exact doubles, NaN/Infinity as strings, longs past 2^53 as strings).
     */
    class Obj {
        private val sb = StringBuilder(128)

        fun key(k: String): StringBuilder {
            if (sb.isNotEmpty()) sb.append(',')
            PacketJson.str(sb, k)
            return sb.append(':')
        }

        fun str(k: String, v: String?): Obj { key(k); if (v == null) sb.append("null") else PacketJson.str(sb, v); return this }
        fun num(k: String, v: Number?): Obj { key(k); if (v == null) sb.append("null") else PacketJson.num(sb, v); return this }
        fun bool(k: String, v: Boolean?): Obj { key(k).append(v?.toString() ?: "null"); return this }
        /** An already-finished JSON value. */
        fun raw(k: String, json: String): Obj { key(k).append(json); return this }
        fun at(s: Stamp?): Obj = raw("at", stamp(s))
        fun stamp(k: String, s: Stamp?): Obj = raw(k, stamp(s))
        fun strs(k: String, v: Iterable<String?>): Obj = raw(k, v.joinToString(",", "[", "]") { s -> if (s == null) "null" else str(s) })
        fun nums(k: String, v: Iterable<Number?>): Obj = raw(k, v.joinToString(",", "[", "]") { n -> if (n == null) "null" else num(n) })
        fun stamps(k: String, v: Iterable<Stamp?>): Obj = raw(k, v.joinToString(",", "[", "]") { stamp(it) })
        /** A nested object. */
        inline fun obj(k: String, body: (Obj) -> Unit): Obj { val o = Obj(); body(o); return raw(k, "{$o}") }
        /** An array of nested objects. */
        fun <T> objs(k: String, items: Iterable<T>, body: (Obj, T) -> Unit): Obj =
            raw(k, items.joinToString(",", "[", "]") { item -> val o = Obj(); body(o, item); "{$o}" })

        override fun toString() = sb.toString()

        companion object {
            fun str(s: String): String = StringBuilder(s.length + 2).also { PacketJson.str(it, s) }.toString()
            fun num(n: Number): String = StringBuilder().also { PacketJson.num(it, n) }.toString()
            /** EC's stamp: real ms and DungeonSplits' server tick. */
            fun stamp(s: Stamp?): String = if (s == null) "null" else "{\"ms\":${s.realMs},\"tick\":${s.tick}}"
        }
    }

    // ------------------------------------------------------------------ the periodic snapshots

    private var lastSession: RecorderSession? = null
    private var lastClocksMs = 0L

    /** Registers the EcLog mirror, the engine trail and the per-tick snapshots. Once, from DungeonRecorder. */
    fun install() {
        // EcLog: every line it writes, P3Rotation's raw chat among them, so private lines are kept out.
        EcLog.listeners += { tag, msg ->
            line("eclog") { o ->
                o.str("tag", tag)
                // ChatHider's log prefixes the line ("rule ⇐ text"): the text after it is checked too.
                val private = Rec.privateText(msg) || (msg.contains(" ⇐ ") && Rec.privateText(msg.substringAfter(" ⇐ ")))
                if (private) o.str("hidden", "private") else o.str("msg", msg)
            }
        }
        // The engine's decision trail, uncapped; chained so a listener set before this keeps working.
        val prev = RotationEngine.trailListener
        RotationEngine.trailListener = { msg ->
            prev?.invoke(msg)
            line("ec.engine") { o -> o.str("msg", msg) }
        }
        on<TickEvent.End> {
            if (!Rec.active) return@on
            EngineerClient.safely("recorder ec tick") { tick() }
        }
        EventBus.subscribe(this)
    }

    private fun tick() {
        val s = Rec.session
        val nowMs = System.currentTimeMillis()
        // Each new recording (each world) starts with the clocks, then once a minute.
        if (s !== lastSession || nowMs - lastClocksMs >= 60_000) {
            lastSession = s; lastClocksMs = nowMs
            clocks()
        }
        if (DungeonUtils.inDungeons) {
            p3()
            if (Rec.tick % 20 == 0) {
                pace()
                profile()
            }
        } else if (Rec.tick % 20 == 0) profile()
    }

    /** EC's own tick counters next to n, so each can be put on the recording's clock. */
    private fun clocks() = line("ec.clocks") { o ->
        o.num("splits", DungeonSplits.recServerTicks)
        o.num("storm", StormPhase.recServerTicks)
        o.num("terms", TermInfo.recServerTicks)
        o.num("ss", OdinSimonSays.recTick)
        o.num("rec", Rec.serverTicks)
    }

    /** The run's pace against the dark green times and the time lost to lag, every second of a run. */
    private fun pace() {
        val now = DungeonSplits.recNow()
        val lag = DungeonSplits.lag(now) ?: return
        val pace = DungeonSplits.pace(now)
        line("ec.pace") { o ->
            o.at(now).num("lagMs", lag)
            if (pace != null) o.obj("pace") { p -> p.num("ms", pace.ms).num("ticks", pace.ticks) }
        }
    }

    /**
     * The P3 rotation, every tick it changes: roles, sections, every tracked holder, pots, the leap
     * cue and the party's invincibilities. MaskTracker is advanced on Odin's server tick (the
     * network thread), so it is read guarded; its cooldown counts are written but not compared.
     */
    private fun p3() {
        val holders = RotationEngine.tracked()
        val igns = LinkedHashSet<String>()
        P3Rotation.teamRoles.values.forEach { igns += it }
        holders.forEach { igns += it.ign }
        EngineerClient.mc.player?.name?.string?.let { igns += it }
        changed("ec.p3", "p3", { o ->
            o.bool("enabled", P3Rotation.enabled).bool("running", RotationEngine.running).bool("finishedAll", RotationEngine.finished())
            o.num("section", P3Rotation.section).bool("sectionDone", P3Rotation.recSectionComplete).bool("gate", P3Rotation.recGateBlown)
            o.obj("teamRoles") { t -> P3Rotation.teamRoles.forEach { (role, ign) -> t.str(role, ign) } }
            o.objs("holders", holders) { h, it ->
                h.str("ign", it.ign).str("role", it.roleId).strs("left", it.remaining.toList()).strs("history", RotationEngine.historyOf(it.ign))
                h.str("leapTarget", RotationEngine.leapTargetFor(it.ign)).bool("leapReady", RotationEngine.leapReadyFor(it.ign))
            }
            o.raw("stuck", RotationEngine.stuck?.let { st -> "{" + Obj().str("ign", st.ign).str("pot", st.potName).str("role", st.role) + "}" } ?: "null")
            o.obj("usedExits") { u -> RotationEngine.recUsedExits().forEach { (pot, used) -> u.nums(pot, used.sorted()) } }
            o.strs("finished", RotationEngine.recFinished()).strs("inCore", RotationEngine.recInCore())
            o.obj("said") { s -> RotationEngine.recSaid().forEach { (ign, said) -> s.strs(ign, said.sorted()) } }
            o.raw("leap", LeapSignal.current()?.let { l -> "{" + Obj().str("ign", l.ign).num("section", l.section).bool("ready", l.ready).str("note", l.note) + "}" } ?: "null")
            o.raw("masks", runCatching {
                igns.joinToString(",", "{", "}") { ign -> Obj.str(ign) + ":{" + Obj().num("available", MaskTracker.available(ign)).strs("cooling", MaskTracker.onCooldown(ign).map { it.name }) + "}" }
            }.getOrElse { "{\"@error\":${Obj.str(it.toString())}}" })
        }, { o ->
            o.raw("maskTicks", runCatching {
                igns.joinToString(",", "{", "}") { ign -> Obj.str(ign) + ":{" + MaskTracker.Kind.entries.joinToString(",") { k -> Obj.str(k.name) + ":" + MaskTracker.cooldownTicks(ign, k) } + "}" }
            }.getOrElse { "{\"@error\":${Obj.str(it.toString())}}" })
        })
    }

    private val gson by lazy { com.google.gson.Gson() }

    /** Class detection, the rush profile, EC's config and BR Roles, each second they change. */
    private fun profile() = changed("ec.profile", "profile", { o ->
        o.str("detected", ClassDetect.detected?.name).num("fallbackFloors", ClassDetect.fallbackFloors)
        o.str("effectiveClass", RushProfiles.effectiveClass()).str("activePack", RushProfiles.activePackName())
        o.raw("config", gson.toJson(EcConfig.data))
        o.obj("brRoles") { b ->
            b.num("count", BrRoles.count).num("mine", BrRoles.mine).bool("onDoor", BrRoles.youOnDoor).bool("active", BrRoles.active)
            b.str("doorRunner", BrRoles.doorRunner).num("settingKilling", BrRoles.settingKilling).num("settingRole", BrRoles.settingRole)
            b.obj("claims") { c -> BrRoles.recClaims().forEach { (ign, role) -> c.num(ign, role) } }
        }
    })
}
