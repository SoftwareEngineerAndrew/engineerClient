package com.engineerclient.practice

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player

/**
 * Terminal Hitboxes: each P3 terminal's clickable box (both its stands) while terminals run, in F7/M7 and the P3 Sim,
 * coloured by whether it's this section's or another's, and aimed at, in reach or out of it. Done ones aren't drawn.
 *
 * From 430 terminal clicks in 81 recorded Hypixel runs (Dungeon Recorder, Oct 8-10): Hypixel answers every left
 * (attack) or right (interact) click the client sends on either stand, once per terminal per tick, a tick later: a fresh
 * window, "already been completed" if it's done, "doesn't seem to be responsive" if it's another section's. It never
 * turned one down for distance (the farthest answered 2.90 blocks from the eyes it knew, 3.39 from the feet), so what
 * decides a click is the client's own pick: the crosshair on either stand's box, under the entity reach (3 blocks) from
 * your eyes and before any block (375 crosshair hits on a stand, the farthest 2.998). The box drawn is both stands'
 * together, and it's in range when a point of it is that close with nothing in front.
 *
 * The live section is [TermInfo]'s count (chat read off the wire, so blade-addons' Terminal Timestamps can't hide it),
 * backed by the world: a terminal is only ever done in the live section, and the section doors open as sections end.
 * Was part of Term Info; its settings came over once ([adoptTermInfoSettings]).
 */
object TerminalHitboxes : Module(
    name = "Terminal Hitboxes",
    key = null,
    category = Category.custom("Engineer Client", 860, 10),
    description = "Outlines each P3 terminal's clickable box (both its stands) while terminals run, in F7/M7 and the P3 Sim: by whether it's this section's, and whether you're aiming at it, it's in your reach or it's out of it. Done ones aren't drawn.",
    toggled = true,
) {
    private val aimColor by ColorSetting("Section Aiming", Color(0, 255, 0, 1f), true, desc = "This section's, your crosshair on it: a click opens it.")
    private val readyColor by ColorSetting("Section In Range", Color(0, 140, 0, 1f), true, desc = "This section's and in your reach (part of the box under 3 blocks from your eyes with nothing in front of it), crosshair elsewhere.")
    private val farColor by ColorSetting("Section Out Of Range", Color(255, 255, 0, 1f), true, desc = "This section's but out of your reach: a click there never gets sent.")
    private val idleAimColor by ColorSetting("Other Section Aiming", Color(255, 0, 0, 1f), true, desc = "Another section's, your crosshair on it: a click gets \"This Terminal doesn't seem to be responsive at the moment.\".")
    private val idleNearColor by ColorSetting("Other Section In Range", Color(140, 0, 0, 1f), true, desc = "Another section's, in your reach, crosshair elsewhere.")
    private val idleColor by ColorSetting("Other Section Out Of Range", Color(255, 128, 0, 1f), true, desc = "Another section's, out of your reach.")
    private val fill by BooleanSetting("Terminal Fill", false, desc = "Fills the boxes too, faintly.")
    private val walls by BooleanSetting("Terminals Through Walls", true, desc = "Drawn over blocks in the way.")
    private var adopted by BooleanSetting("Adopted Term Info Settings", false, desc = "").hide()

    /** A block in the bottom row of each section door's barrier wall: S1→S2, S2→S3, S3→S4. */
    private val DOOR_PROBES = listOf(BlockPos(100, 115, 122), BlockPos(18, 115, 132), BlockPos(8, 115, 50))

    /**
     * The 17 terminals, as the P3 Sim places them (Hypixel's stand spots): Hypixel's "Inactive Terminal" stand spawns on
     * [com.engineerclient.p3sim.Station.at] and settles 0.02 above it, its "CLICK HERE" stand 0.355 below it.
     */
    private val TERMINALS by lazy { com.engineerclient.p3sim.Station.all().filter { it.kind == com.engineerclient.p3sim.Station.Kind.TERMINAL } }
    /** Terminals seen done this run (Hypixel): their stands can go out of tracking range, a done one stays done. */
    private val doneSeen = BooleanArray(17)
    /** The terminal you last clicked (index in [TERMINALS]). */
    @Volatile private var lastClicked = -1
    /** The terminal whose window last opened for you (the one clicked then): your own "activated a terminal!" is that one. */
    @Volatile private var openedTerm = -1
    /** Hypixel's terminal windows. */
    private val TERM_TITLE = Regex("^(Click in order!|Select all the .+ items!|What starts with: '.+'\\?|Correct all the panes!|Change all to same color!|Click the button on time!)$")
    private val OWN_TASK = Regex("^(\\w{1,16}) activated a terminal! \\(\\d+/\\d+\\)$")

    /** A new P3 or world: nothing done, nothing clicked. From [TermInfo]. */
    fun reset() {
        doneSeen.fill(false); lastClicked = -1; openedTerm = -1
    }

    /** A terminal window opening ([title]) is the last terminal you clicked. From [TermInfo], any thread. */
    fun opened(title: String) {
        if (!TERM_TITLE.matches(title)) return
        mc.execute { openedTerm = lastClicked }
    }

    /** A P3 chat line, on the game thread: your own "activated a terminal!" marks the one you opened done at once. */
    fun chat(msg: String) {
        val m = OWN_TASK.matchEntire(msg) ?: return
        if (m.groupValues[1] == mc.player?.name?.string && openedTerm >= 0) doneSeen[openedTerm] = true
    }

    /**
     * The live section by the doors, without chat: Hypixel takes a door's 152 barriers away in one tick (two at most)
     * once its section is over (last task and gate both in), in all 225 section ends of 72 recorded runs, 0-3 ticks after
     * the chat says so. 1 + the doors open, in order. Not barrier rather than air (a head was once placed in an open
     * one), and only loaded (an unloaded chunk reads as air).
     */
    private fun doorSection(level: net.minecraft.world.level.Level): Int {
        var s = 1
        for (p in DOOR_PROBES) {
            if (!level.isLoaded(p) || level.getBlockState(p).`is`(net.minecraft.world.level.block.Blocks.BARRIER)) break
            s++
        }
        return s
    }

    /** The terminal whose stand [e] is, as an index in [TERMINALS], or -1. */
    private fun terminalOf(e: net.minecraft.world.entity.Entity): Int {
        if (e !is net.minecraft.world.entity.decoration.ArmorStand) return -1
        return TERMINALS.indexOfFirst { kotlin.math.abs(e.x - it.at.x) < 0.01 && kotlin.math.abs(e.z - it.at.z) < 0.01 && e.y > it.at.y - 0.5 && e.y < it.at.y + 0.1 }
    }

    private fun RenderExtractEvent.drawTerminals() {
        // Your own sim: its state. Someone else's (a party): the world and chat, as on Hypixel.
        val sim = com.engineerclient.p3sim.P3Sim.localSim
        if (!com.engineerclient.p3sim.P3Sim.inSim && !(DungeonUtils.inBoss && DungeonUtils.floor?.floorNumber == 7)) return
        val level = mc.level ?: return
        val player = mc.player ?: return
        val simStations = if (sim) (com.engineerclient.p3sim.Fight.phase as? com.engineerclient.p3sim.GoldorPhase)?.stations else null
        val stands = TERMINALS.mapIndexed { i, t ->
            level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand::class.java,
                net.minecraft.world.phys.AABB(t.at.x - 0.1, t.at.y - 0.5, t.at.z - 0.1, t.at.x + 0.1, t.at.y + 0.1, t.at.z + 0.1)) { terminalOf(it) == i }
        }
        val done = BooleanArray(TERMINALS.size) { i ->
            val t = TERMINALS[i]
            if (sim) simStations?.firstOrNull { it.kind == t.kind && it.section == t.section && it.label == t.label }?.done == true
            else {
                // Hypixel's stands come and go with tracking range: a done one is remembered. Done: the top stand drops
                // 0.375 onto the bottom one's height (at any distance, unlike the names), or reads "Terminal Active".
                val s = stands[i]
                if (s.size >= 2 && s.maxOf { it.y } - s.minOf { it.y } < 0.1 || s.any { it.customName?.string?.contains("Terminal Active") == true }) doneSeen[i] = true
                doneSeen[i]
            }
        }
        // Only while terminals run (Goldor's first line to the core opening), not over P1, P2 or P4. On Hypixel the chat
        // count is backed by the world, so a missed line or a world reload mid-P3 can't hold it back.
        val live = if (sim) TermInfo.liveSection() else {
            val chat = TermInfo.liveSection()
            val doneBound = TERMINALS.indices.filter { done[it] }.maxOfOrNull { TERMINALS[it].section } ?: 0
            if (TermInfo.coreOpened || chat == 0 && doneBound == 0) 0 else maxOf(chat, doneBound, doorSection(level))
        }
        if (live == 0) return
        val eye = player.getEyePosition(mc.deltaTracker.getGameTimeDeltaPartialTick(false))
        val reach = player.entityInteractionRange()
        val aimedAt = (mc.hitResult as? net.minecraft.world.phys.EntityHitResult)?.entity?.let { terminalOf(it) } ?: -1
        TERMINALS.forEachIndexed { i, t ->
            // Done ones, and any of an earlier section (it only ended once all its terminals were done).
            if (done[i] || t.section < live) return@forEachIndexed
            val at = t.at
            val box = stands[i].map { it.boundingBox }.reduceOrNull { a, b -> a.minmax(b) }
                ?: net.minecraft.world.phys.AABB(at.x - 0.25, at.y - 0.355, at.z - 0.25, at.x + 0.25, at.y + 1.995, at.z + 0.25)
            // Aiming: the game's own pick (what a click goes to) is one of its stands.
            val aimed = aimedAt == i
            val near = aimed || inReach(level, player, eye, box, reach)
            val color = if (t.section == live) { if (aimed) aimColor else if (near) readyColor else farColor }
                else { if (aimed) idleAimColor else if (near) idleNearColor else idleColor }
            if (fill) drawFilledBox(box, color.withAlpha(color.alphaFloat * 0.2f), depth = !walls)
            drawWireFrameBox(box, color, depth = !walls)
        }
    }

    /**
     * Whether a click could reach [box]: vanilla picks an entity when the crosshair ray meets its box closer than [reach]
     * to your eyes and before any block (outline shapes). So some point of the box that close with a clear line to it:
     * the nearest point, else a grid over the box (a stand's foot sits in the floor block).
     */
    private fun inReach(level: net.minecraft.world.level.Level, player: Player, eye: net.minecraft.world.phys.Vec3, box: net.minecraft.world.phys.AABB, reach: Double): Boolean {
        if (box.contains(eye)) return true
        val r2 = reach * reach
        val near = net.minecraft.world.phys.Vec3(eye.x.coerceIn(box.minX, box.maxX), eye.y.coerceIn(box.minY, box.maxY), eye.z.coerceIn(box.minZ, box.maxZ))
        if (near.distanceToSqr(eye) >= r2) return false
        fun clear(p: net.minecraft.world.phys.Vec3): Boolean {
            val hit = level.clip(net.minecraft.world.level.ClipContext(eye, p, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player))
            return hit.type == net.minecraft.world.phys.HitResult.Type.MISS || hit.location.distanceToSqr(eye) >= p.distanceToSqr(eye) - 1e-6
        }
        if (clear(near)) return true
        val e = 1e-3
        for (i in 0..2) for (j in 0..8) for (k in 0..2) {
            val p = net.minecraft.world.phys.Vec3(
                box.minX + e + (box.maxX - box.minX - 2 * e) * i / 2, box.minY + e + (box.maxY - box.minY - 2 * e) * j / 8, box.minZ + e + (box.maxZ - box.minZ - 2 * e) * k / 2)
            if (p.distanceToSqr(eye) < r2 && clear(p)) return true
        }
        return false
    }

    /**
     * Once: these settings were Term Info's (Engineer Client) until Term Info became Coffee Client's HUD. Its saved
     * values (in engineerclient.json, still there until Odin next saves it) are taken over: the colours, fill, walls,
     * and its Terminal Hitboxes toggle as this module's on/off.
     */
    fun adoptTermInfoSettings() {
        if (adopted) return
        adopted = true
        val file = mc.gameDirectory.toPath().resolve("config/odin/addons/engineerclient.json")
        val old = if (java.nio.file.Files.exists(file)) com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).asJsonArray
            .firstOrNull { it.asJsonObject.get("name")?.asString == "Term Info" }?.asJsonObject?.getAsJsonObject("settings") else null
        if (old != null) {
            for ((name, element) in old.entrySet()) {
                if (!element.isJsonPrimitive) continue
                runCatching {
                    when (val setting = settings[name]) {
                        is ColorSetting -> setting.value = Color(element.asString.removePrefix("#"))
                        is BooleanSetting -> if (name != "Adopted Term Info Settings") setting.value = element.asBoolean
                        else -> {}
                    }
                }
            }
            if (old.get("Terminal Hitboxes")?.asBoolean == false && enabled) toggle()
        }
        // Not saved here: client init is still adding settings to Odin's modules, and a save now would drop the saved
        // values of the ones still to come. Odin's next save keeps these (until then this simply runs again).
    }

    init {
        on<RenderExtractEvent> { com.engineerclient.EngineerClient.safely("terminal hitboxes") { drawTerminals() } }
        // The terminal your clicks go to, so your own "activated a terminal!" marks it done at once.
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register { _, level, _, entity, _ ->
            if (level.isClientSide) terminalOf(entity).let { if (it >= 0) lastClicked = it }
            net.minecraft.world.InteractionResult.PASS
        }
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register { _, level, _, entity, _ ->
            if (level.isClientSide) terminalOf(entity).let { if (it >= 0) lastClicked = it }
            net.minecraft.world.InteractionResult.PASS
        }
    }
}
