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
import com.odtheking.odin.events.core.onReceive
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
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Recore's Core Leap Highlight for the early enters: in S1, S2 and S3, the part of the screen where the teammates already
 * through to the next section (at its early enter, [PointsOfInterest], or anywhere in it) are in Odin's Leap Menu, so the
 * flick is on its way as the menu opens. Same looks as Recore's ([LeapHighlightLook]), its own colour.
 *
 * Where each teammate is comes from the best source there is ([PoiPresence]): their own game over Pose POV's party link
 * (kind 13, [PoiWire]: every game in P3 sends its own, on each change and once a second, this on or not, as Term Progress
 * does), their player if it's in view, their shared pose, or their "at ee3!"-style party message. So someone out of view
 * on EE3 still lights up.
 *
 * Each P3's end logs a line: frames out, how many teammates TURN reached, what came in, and who was highlighted where, by
 * which source.
 */
object EeHighlight : Module(
    name = "EE Highlight",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Like Recore's Core Leap Highlight, for the early enters: in S1-S3, where the teammates already through to the next section are in Odin's Leap Menu, so you flick there as it opens. Shared over Pose POV's party link.",
    key = null,
    toggled = true,
) {
    private val ee2 by BooleanSetting("EE2", true, desc = "In S1: whoever's through to S2, on top of its device or on the safe spot by its 3rd terminal.")
    private val ee3 by BooleanSetting("EE3", true, desc = "In S2: whoever's through to S3, low by the S2 / S3 corner or high on the S3 wall's ledge.")
    private val ee4 by BooleanSetting("EE4", true, desc = "In S3: whoever's through to S4, just outside the core.")
    private val anywhere by BooleanSetting("Anywhere In Next Section", true, desc = "Also whoever is anywhere in the next section, not just on an early enter spot: they're through too.")
    private val callouts by BooleanSetting("Party Chat Callouts", true, desc = "Also goes by party messages like \"at ee3!\" or \"at core!\" (Positional Messages), for teammates without this mod who are out of view.")
    private val all by BooleanSetting("Highlight All", true, desc = "Every teammate who's through, each where they'll be in the leap menu, not just the first one.")
    private val hideThere by BooleanSetting("Hide Once You're There", true, desc = "Off for the rest of the section once you're through to the next one yourself.")
    private val look = LeapHighlightLook(this, "EE Leap Look", { true }, "someone is through",
        "Shows the highlight now, top right, as if someone were on EE2 (also over this menu), to try the looks below; with Highlight All, bottom left too. Turn it off after.",
        Color(0, 200, 255, 121 / 255f))
    private val showSpots by BooleanSetting("Show Spots", false, desc = "Draws the early enter spots' boxes in the boss, through walls: where standing counts.")

    private val CONTROL_CODES = Regex("§.")
    private val PARTY = Regex("""^Party > (?:\[[^\]]+] )?(\w{1,16}): (.+)$""")
    private const val RESEND_MS = 1000L
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
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay || !callouts) return@onReceive
            val msg = content.string.replace(CONTROL_CODES, "")
            mc.execute { EngineerClient.safely("ee highlight chat") { partyChat(msg) } }
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

    /** The section area of a position: its P3 section (1-4), the core ([PoiWire.AREA_CORE]), or 0. */
    private fun areaAt(x: Double, y: Double, z: Double): Int {
        P3Sections.sectionAt(x, y, z).takeIf { it != 0 }?.let { return it }
        // Recore's core box (DungeonSplits', P3 Sim's).
        return if (x >= 39 && x < 71 && y < 155.5 && z >= 54 && z < 118) PoiWire.AREA_CORE else 0
    }

    private fun poiAt(x: Double, y: Double, z: Double) = PointsOfInterest.at(x, y, z)?.id ?: 0

    /** Whether the early enter into [opens] (2-4) is one to highlight. */
    private fun eeOn(opens: Int) = when (opens) { 2 -> ee2; 3 -> ee3; 4 -> ee4; else -> false }

    /** Whether being on [poi] in [area] is being through to section [opens]. */
    private fun through(poi: Int, area: Int, opens: Int) =
        (poi != 0 && PointsOfInterest.byId(poi)?.opens == opens) || (anywhere && area == opens)

    // ------------------------------------------------------------------ every tick

    private var sentPoi = -1
    private var sentArea = -1
    private var sentAt = 0L
    private var heldSince = 0L
    private var changed = false
    /** Each teammate's last shared pose position and when (ms), to tell a leap's drag from standing there. */
    private val lastPose = HashMap<String, Pair<Vec3, Long>>()

    private fun tick() {
        val me = mc.player
        val level = mc.level
        val sec = if (me != null && level != null && (DungeonUtils.inBoss || com.engineerclient.p3sim.P3Sim.inSim)) TermInfo.sectionNumber() ?: 0 else 0
        if (sec != section) sectionChanged(sec)
        if (sec == 0 || me == null || level == null) return
        val now = System.currentTimeMillis()

        // Yourself, exact, for teammates: whether or not this is on (as Term Progress does), unless Pose POV's sharing is off.
        val myPoi = poiAt(me.x, me.y, me.z)
        val myArea = areaAt(me.x, me.y, me.z)
        if (myPoi != sentPoi || myArea != sentArea) {
            if (!changed) heldSince = now
            changed = true
        }
        if ((changed && now - sentAt >= CHANGE_GAP_MS) || now - sentAt >= RESEND_MS) {
            if (changed) { sentPoi = myPoi; sentArea = myArea; changed = false }
            sentAt = now
            if (PoseShare.sharing) share(PoiWire.encode(sentPoi, sentArea, now - heldSince))
        }
        if (!enabled) return

        val opens = sec + 1
        if (opens in 2..4 && !LeapTeammates.inFlight(me) && through(myPoi, myArea, opens)) selfThere = true

        // Teammates in view, else by their shared pose.
        for (m in DungeonUtils.leapTeammates) {
            if (m.name == "Empty") continue
            val key = m.name.lowercase()
            if (com.engineerclient.DeadPlayers.isDead(m)) { presence.forget(key); continue }
            val e = LeapTeammates.entity(m, level)
            if (e != null) {
                if (!LeapTeammates.inFlight(e)) presence.seen(key, poiAt(e.x, e.y, e.z), areaAt(e.x, e.y, e.z), now, PoiPresence.Source.SEEN)
                continue
            }
            val pose = PoseShare.pose(m.name, System.nanoTime()) ?: continue
            val p = pose.pos
            val last = lastPose.put(key, p to now)
            // A leap's drag: more than 2 blocks a tick since the last one.
            if (last != null && now - last.second < 200) {
                val most = 2.0 * ((now - last.second) / 50.0).coerceAtLeast(1.0)
                if (last.first.distanceToSqr(p) > most * most) continue
            }
            presence.seen(key, poiAt(p.x, p.y, p.z), areaAt(p.x, p.y, p.z), now, PoiPresence.Source.POSE)
        }
    }

    private fun sectionChanged(sec: Int) {
        if (section != 0 && sec == 0) EngineerClient.safely("ee highlight report") { report() }
        if (section == 0 && sec != 0) { framesOut = 0; mostDirect = 0; mostLinked = 0; heardFrom.clear(); picked.clear() }
        section = sec
        sectionStartAt = System.currentTimeMillis()
        selfThere = false
        if (sec == 0) {
            presence.clear(); lastPose.clear()
            sentPoi = -1; sentArea = -1; sentAt = 0L; changed = false
        }
    }

    // ------------------------------------------------------------------ the party link and chat

    // For the log line at P3's end: frames out, the most teammates one reached over TURN, the most linked, what came in.
    private var framesOut = 0
    private var mostDirect = 0
    private var mostLinked = 0
    private val heardFrom = HashMap<String, Int>()
    /** Each teammate highlighted in each section ("S1 name" -> "EE2 (shared) 6.3 s into S1"), for the log. */
    private val picked = LinkedHashMap<String, String>()
    /** When this section began here (its chat, or the sim). */
    private var sectionStartAt = 0L

    private fun share(frame: ByteArray) {
        framesOut++
        mostDirect = maxOf(mostDirect, PoseShare.shareExtra(frame))
        mostLinked = maxOf(mostLinked, PoseShare.linkedCount)
    }

    /** A teammate's frame (on the game thread). */
    private fun heard(sender: String, h: PoiWire.Heard) {
        if (section == 0) return
        if (DungeonUtils.leapTeammates.none { it.name.equals(sender, ignoreCase = true) }) return
        presence.shared(sender, h.poi, h.area, h.heldMs, System.currentTimeMillis())
        heardFrom.merge(sender, 1, Int::plus)
    }

    /** A party message that says its sender just got to an early enter ("at ee3!"), from a teammate other than you. */
    private fun partyChat(msg: String) {
        if (section !in 1..3) return
        val m = PARTY.matchEntire(msg) ?: return
        val name = m.groupValues[1]
        if (name.equals(mc.player?.name?.string, ignoreCase = true)) return
        if (DungeonUtils.leapTeammates.none { it.name.equals(name, ignoreCase = true) }) return
        val poi = PointsOfInterest.callout(m.groupValues[2]) ?: return
        presence.callout(name.lowercase(), poi.id, System.currentTimeMillis(), section)
    }

    private fun report() {
        val heard = heardFrom.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} from ${it.key}" }.ifEmpty { "nothing" }
        val relay = if (PoseShare.relayTakes(PoiWire.KIND)) "the relay takes them too" else "TURN only"
        val who = picked.entries.joinToString("; ") { "${it.key}: ${it.value}" }.ifEmpty { "nobody highlighted" }
        EngineerClient.logger.info("[ee highlight] P3: $framesOut frames out, TURN reached up to $mostDirect of up to $mostLinked linked teammates ($relay); heard $heard; $who")
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
            if (section !in 1..3 || !eeOn(opens)) return
            if (hideThere && selfThere) return
            val now = System.currentTimeMillis()
            val mates = DungeonUtils.leapTeammates
            val through = mates.withIndex().mapNotNull { (i, m) ->
                if (i > 3 || m.name == "Empty") return@mapNotNull null
                val w = presence.where(m.name.lowercase(), now, section) ?: return@mapNotNull null
                if (!through(w.poi, w.area, opens)) return@mapNotNull null
                val label = PointsOfInterest.byId(w.poi)?.takeIf { it.opens == opens }?.label ?: "S$opens"
                note(m.name, label, w)
                LeapHighlightLook.Target(i, m.name, m.clazz.name.lowercase(), m.playerSkin ?: self.skin, w.since, label)
            }.sortedBy { it.since }
            if (all) through else through.take(1)
        }
        look.draw(gfx, targets)
    }

    /** Logs each teammate the first time they're highlighted in a section, with where, how it's known and when they got there. */
    private fun note(name: String, label: String, w: PoiPresence.Where) {
        val key = "S$section $name"
        if (key in picked) return
        val at = "%.1f s into S$section".format(java.util.Locale.ROOT, (w.since - sectionStartAt) / 1000.0)
        picked[key] = "$label (${w.source.label}) $at"
        EngineerClient.logger.info("[ee highlight] S$section: $name through to S${section + 1} at $label, ${w.source.label}, $at")
    }
}
