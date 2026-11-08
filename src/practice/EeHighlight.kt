package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.engineerclient.poi.PoiPresence
import com.engineerclient.poi.PoiWire
import com.engineerclient.poi.PointsOfInterest
import com.engineerclient.pov.PoseShare
import com.engineerclient.rotation.P3Sections
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Recore's Core Leap Highlight for the early enters: in S1, S2 and S3, the part of the screen where each teammate standing on
 * the next section's early enter ([PointsOfInterest]) is in Odin's Leap Menu, so the flick is on its way as the menu opens.
 * Same looks as Recore's ([LeapHighlightLook]), its own colour.
 *
 * Only when it's sure ([PoiPresence]): a wrong highlight sends you leaping to someone who isn't there, which is worse than
 * none. Every current reading of where they are has to have them on the spot, settled: their player in view this tick,
 * their shared pose, and their own game over Pose POV's party link (kind 13, [PoiWire]: every game sends its own in S1-S3,
 * on each change and four times a second, this on or not, as Term Progress does), at most 0.7 s old. Out of view with no
 * fresh frame is no highlight, never "where they were last"; party messages ("at ee3!") don't count.
 *
 * Each P3's end logs a line: frames out, how many teammates TURN reached, what came in, and who was highlighted where, by
 * which sources; and each time a teammate's sources disagreed about them being on a spot.
 */
object EeHighlight : Module(
    name = "EE Highlight",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Like Recore's Core Leap Highlight, for the early enters: in S1-S3, where the teammates surely on the next section's early enter are in Odin's Leap Menu, so you flick there as it opens. Shared over Pose POV's party link.",
    key = null,
    toggled = true,
) {
    private val ee2 by BooleanSetting("EE2", true, desc = "In S1: whoever's on the platform in front of S2's device (Lights).")
    private val ee3 by BooleanSetting("EE3", true, desc = "In S2: whoever's on the low walkway over the lava past S2's gate, by the S2 / S3 corner.")
    private val ee3High by BooleanSetting("EE3 High", false, desc = "In S2: whoever's on the slab ledge high on S3's east wall. A strategy some parties use, decided ahead of time: turn it on for a party that does.")
    private val ee4 by BooleanSetting("EE4", true, desc = "In S3: whoever's on the walkway up to the core door.")
    private val all by BooleanSetting("Highlight All", true, desc = "Every teammate who's on the early enter, each where they'll be in the leap menu, not just the first one there.")
    private val hideThere by BooleanSetting("Hide Once You're There", true, desc = "Off for the rest of the section once you're through to the next one yourself.")
    private val look = LeapHighlightLook(this, "EE Leap Look", { true }, "someone is on the early enter",
        "Shows the highlight now, top right, as if someone were on EE2 (also over this menu), to try the looks below; with Highlight All, bottom left too. Turn it off after.",
        Color(0, 200, 255, 121 / 255f))
    private val showSpots by BooleanSetting("Show Spots", false, desc = "Draws the early enter spots' boxes in the boss, through walls: where standing counts.")

    /** Your own frame at least this often in S1-S3: with [PoiPresence.SHARE_FRESH_MS], one lost and teammates still have it. */
    private const val RESEND_MS = 250L
    /** Frames on a change no closer than this (someone along a box's edge would send one a tick). */
    private const val CHANGE_GAP_MS = 100L
    private val SPOT_COLOR = Color(0, 200, 255, 0.9f)

    private val presence = PoiPresence()
    /** The P3 section in progress (1-4; [TermInfo]), 0 outside P3. */
    private var section = 0
    /** You've been through to the next section during this one. */
    private var selfThere = false

    init {
        PoseShare.onExtra(PoiWire.KIND) { sender, b ->
            val h = PoiWire.decode(b) ?: return@onExtra
            mc.execute { EngineerClient.safely("ee highlight heard") { heard(sender, h) } }
        }
        ClientTickEvents.END_CLIENT_TICK.register { EngineerClient.safely("ee highlight") { tick() } }
        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, Identifier.fromNamespaceAndPath("engineerclient", "ee_highlight")) { gfx, _ ->
            // The HUD is drawn under any open screen too, so this is also the behind-the-terminal draw.
            EngineerClient.safely("ee highlight draw") { draw(gfx) }
        }
        on<RenderExtractEvent> {
            if (!showSpots || !(DungeonUtils.inBoss || com.engineerclient.p3sim.P3Sim.inSim)) return@on
            for (p in PointsOfInterest.ALL) for (b in p.boxes) {
                drawWireFrameBox(AABB(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ), SPOT_COLOR, depth = false)
                drawText("${p.label} ($b)", Vec3((b.minX + b.maxX) / 2, b.maxY + 0.5, (b.minZ + b.maxZ) / 2), 1f, false)
            }
        }
    }

    // ------------------------------------------------------------------ where things are

    private fun poiAt(x: Double, y: Double, z: Double) = PointsOfInterest.at(x, y, z)?.id ?: 0

    /** The section standing on [poi] puts you in (2-4), 0 for none. */
    private fun opensOf(poi: Int) = PointsOfInterest.byId(poi)?.opens ?: 0

    /** Whether [poi] is one to highlight. */
    private fun poiOn(poi: PointsOfInterest.Poi) = when (poi) {
        PointsOfInterest.EE2 -> ee2
        PointsOfInterest.EE3 -> ee3
        PointsOfInterest.EE3_HIGH -> ee3High
        PointsOfInterest.EE4 -> ee4
        else -> false
    }

    // ------------------------------------------------------------------ every tick

    private var curPoi = 0
    private var curSince = 0L
    private var sentPoi = -1
    private var sentAt = 0L
    private var seq = 0
    /** Each teammate's last shared pose position and when (ms), to tell a leap's drag from standing there. */
    private val lastPose = HashMap<String, Pair<Vec3, Long>>()

    private fun tick() {
        val me = mc.player
        val level = mc.level
        val sec = if (me != null && level != null && (DungeonUtils.inBoss || com.engineerclient.p3sim.P3Sim.inSim)) TermInfo.sectionNumber() ?: 0 else 0
        if (sec != section) sectionChanged(sec)
        if (sec !in 1..3 || me == null || level == null) return
        val now = System.currentTimeMillis()

        // Yourself, for teammates: whether or not this is on (as Term Progress does), unless Pose POV's sharing is off. On a
        // leap's drag, or dead, you're on no spot.
        val dead = me.isSpectator || com.engineerclient.DeadPlayers.isDead(me.name.string)
        val moving = LeapTeammates.inFlight(me)
        val myPoi = if (dead || moving) 0 else poiAt(me.x, me.y, me.z)
        if (myPoi != curPoi) { curPoi = myPoi; curSince = now }
        if ((curPoi != sentPoi && now - sentAt >= CHANGE_GAP_MS) || now - sentAt >= RESEND_MS) {
            sentPoi = curPoi; sentAt = now
            if (PoseShare.sharing) share(PoiWire.encode(seq, curPoi, now - curSince))
            seq = (seq + 1) and 255
        }
        if (!enabled) return

        val opens = sec + 1
        // Hiding is the safe way to be wrong, so for yourself anywhere in the next section counts.
        if (!dead && !moving && (opensOf(myPoi) == opens || P3Sections.sectionAt(me.x, me.y, me.z) == opens)) selfThere = true

        // Teammates in view, else by their shared pose, every tick: out of view with no pose is no reading at all.
        for (m in DungeonUtils.leapTeammates) {
            if (m.name == "Empty") continue
            val key = m.name.lowercase()
            if (com.engineerclient.DeadPlayers.isDead(m)) { presence.forget(key); continue }
            val e = LeapTeammates.entity(m, level)
            if (e != null) {
                presence.seen(key, if (LeapTeammates.inFlight(e)) 0 else poiAt(e.x, e.y, e.z), now, PoiPresence.Source.SEEN)
            } else {
                val pose = PoseShare.pose(m.name, System.nanoTime())
                if (pose != null) {
                    val p = pose.pos
                    val last = lastPose.put(key, p to now)
                    // A leap's drag: more than 2 blocks a tick since the last one.
                    val dragged = last != null && now - last.second < 200 &&
                        last.first.distanceToSqr(p) > (2.0 * ((now - last.second) / 50.0).coerceAtLeast(1.0)).let { it * it }
                    presence.seen(key, if (dragged) 0 else poiAt(p.x, p.y, p.z), now, PoiPresence.Source.POSE)
                }
            }
            logIfNew(m.name, key, opens, now)
        }
    }

    private fun sectionChanged(sec: Int) {
        if (section != 0 && sec == 0) EngineerClient.safely("ee highlight report") { report() }
        if (section == 0 && sec != 0) { framesOut = 0; mostDirect = 0; mostLinked = 0; heardFrom.clear(); outOfOrder = 0; picked.clear(); split.clear() }
        section = sec
        sectionStartAt = System.currentTimeMillis()
        selfThere = false
        if (sec == 0) {
            presence.clear(); lastPose.clear()
            curPoi = 0; sentPoi = -1; sentAt = 0L
        }
    }

    // ------------------------------------------------------------------ the party link and the log

    // For the log line at P3's end: frames out, the most teammates one reached over TURN, the most linked, what came in.
    private var framesOut = 0
    private var mostDirect = 0
    private var mostLinked = 0
    private val heardFrom = HashMap<String, Int>()
    private var outOfOrder = 0
    /** Each teammate highlighted in each section ("S1 name" -> "EE2 (shared+seen) 6.3 s into S1"), for the log. */
    private val picked = LinkedHashMap<String, String>()
    /** Teammates whose sources disagreed in a section ("S2 name"), each logged once. */
    private val split = HashSet<String>()
    /** When this section began here (its chat, or the sim). */
    private var sectionStartAt = 0L

    private fun share(frame: ByteArray) {
        framesOut++
        mostDirect = maxOf(mostDirect, PoseShare.shareExtra(frame))
        mostLinked = maxOf(mostLinked, PoseShare.linkedCount)
    }

    /** A teammate's frame (on the game thread). */
    private fun heard(sender: String, h: PoiWire.Heard) {
        if (section !in 1..3) return
        if (DungeonUtils.leapTeammates.none { it.name.equals(sender, ignoreCase = true) }) return
        if (!presence.shared(sender.lowercase(), h.seq, h.poi, h.heldMs, System.currentTimeMillis())) outOfOrder++
        heardFrom.merge(sender, 1, Int::plus)
    }

    private fun report() {
        val heard = heardFrom.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} from ${it.key}" }.ifEmpty { "nothing" }
        val late = if (outOfOrder > 0) " ($outOfOrder out of order, dropped)" else ""
        val relay = if (PoseShare.relayTakes(PoiWire.KIND)) "the relay takes them too" else "TURN only"
        val who = picked.entries.joinToString("; ") { "${it.key}: ${it.value}" }.ifEmpty { "nobody highlighted" }
        EngineerClient.logger.info("[ee highlight] P3: $framesOut frames out, TURN reached up to $mostDirect of up to $mostLinked linked teammates ($relay); heard $heard$late; $who")
    }

    /**
     * Logs a teammate the first time in a section they're surely on the next early enter (whether or not it's drawn), and the
     * first time their current sources disagree about it, which is a highlight held back.
     */
    private fun logIfNew(name: String, key: String, opens: Int, now: Long) {
        val w = presence.sureAt(key, now)
        if (w != null) {
            val poi = PointsOfInterest.byId(w.poi) ?: return
            if (poi.opens != opens || !poiOn(poi)) return
            val k = "S$section $name"
            if (k in picked) return
            val label = poi.label
            val by = w.sources.joinToString("+") { it.label }
            val at = "%.1f s into S$section".format(java.util.Locale.ROOT, (w.since - sectionStartAt) / 1000.0)
            picked[k] = "$label ($by) $at"
            EngineerClient.logger.info("[ee highlight] S$section: $name on $label, by $by, there from $at")
            return
        }
        val cur = presence.current(key, now)
        if (cur.values.none { opensOf(it) == opens } || cur.values.toSet().size < 2) return
        if (!split.add("S$section $name")) return
        val says = cur.entries.joinToString(", ") { (s, poi) -> "${s.label} ${PointsOfInterest.byId(poi)?.label ?: "none"}" }
        EngineerClient.logger.info("[ee highlight] S$section: $name not highlighted, sources disagree: $says")
    }

    // ------------------------------------------------------------------ the highlight

    private fun draw(gfx: GuiGraphicsExtractor) {
        if (!enabled || mc.gui.hud.isHidden) return
        val self = mc.player ?: return
        if (!look.screenAllows()) return
        val targets = if (look.preview) {
            val since = System.currentTimeMillis() / 3000 * 3000
            listOfNotNull(
                LeapHighlightLook.Target(1, "Teammate", "mage", self.skin, since, "EE2"),
                if (all) LeapHighlightLook.Target(2, "Teammate", "archer", self.skin, since, "EE2") else null,
            )
        } else {
            val opens = section + 1
            if (section !in 1..3) return
            if (hideThere && selfThere) return
            val now = System.currentTimeMillis()
            val mates = DungeonUtils.leapTeammates
            val on = mates.withIndex().mapNotNull { (i, m) ->
                if (i > 3 || m.name == "Empty") return@mapNotNull null
                val w = presence.sureAt(m.name.lowercase(), now) ?: return@mapNotNull null
                val poi = PointsOfInterest.byId(w.poi)?.takeIf { it.opens == opens && poiOn(it) } ?: return@mapNotNull null
                // Whoever got there first goes first; each fades in from when it's sure.
                w.since to LeapHighlightLook.Target(i, m.name, m.clazz.name.lowercase(), m.playerSkin ?: self.skin, w.sureFrom, poi.label)
            }.sortedBy { it.first }.map { it.second }
            if (all) on else on.take(1)
        }
        look.draw(gfx, targets)
    }
}
