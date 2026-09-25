package com.engineerclient.rotation

import com.engineerclient.EcConfig
import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.events.PacketEvent
import com.odtheking.odin.events.core.EventPriority
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import java.util.Collections
import java.util.IdentityHashMap
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.Color
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.utils.playSoundAtPlayer
import com.odtheking.odin.utils.playSoundSettings
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.sounds.SoundEvents

/**
 * Reads phase 3 out of chat and shows you the role you are on.
 *
 * Section tracking mirrors Odin's own (its copy is private): a section ends only once BOTH the
 * final count and "The gate has been destroyed!" have landed, in either order, because in a real
 * run those two lines routinely arrive out of order.
 */
object P3Rotation : Module(
    name = "Dynamic Term Roles",
    category = Category.custom("Engineer Client"),
    description = "Tracks the phase-3 terminal rotation and shows the role you are on.",
) {
    private val announce by BooleanSetting("Announce In Chat", true, desc = "Prints your next role in chat the moment your current one is done.")
    private val showTeam by BooleanSetting("Show Team", false, desc = "Lists every tracked player's role on the HUD, not just yours.")
    val highlightLeaps by BooleanSetting(
        "Highlight Leap Target", true,
        desc = "Rings the player you should leap to in Odin's leap menu — soft while they are still on their way, solid once they are in place.",
    )
    private val leapSound by BooleanSetting("Sounds", true, desc = "Plays the role's sound when you are handed one, and the leap-ready sound when your target is in place.")
    val dimOthers by BooleanSetting("Dim Other Players", false, desc = "Greys out the three players you should NOT leap to in Odin's leap menu, as well as ringing the one you should.")
    private val roleVignette by BooleanSetting("Role Vignette", true, desc = "Flashes the screen edge in the role's colour when you are handed a new role, and amber when your leap target is in place.")
    private val vignetteCorner by BooleanSetting("Vignette In Leap Corner", false, desc = "Flashes only the corner of the screen where your leap target sits in Odin's leap menu, so you know which quadrant to click before it is open.").withDependency { roleVignette }

    // One identity per slot: the same job is the same colour and the same note in every section.
    private val slotColors by DropdownSetting("Slot Colours")
    private val slot1 by ColorSetting("Slot 1", Color(255, 85, 85), desc = "1st terminal / ss").withDependency { slotColors }
    private val slot2 by ColorSetting("Slot 2", Color(255, 170, 0), desc = "2nd terminal / 21").withDependency { slotColors }
    private val slot3 by ColorSetting("Slot 3", Color(85, 255, 85), desc = "3rd terminal / i4 / ee3").withDependency { slotColors }
    private val slot4 by ColorSetting("Slot 4", Color(85, 255, 255), desc = "4th terminal / 43").withDependency { slotColors }
    private val slot5 by ColorSetting("Slot 5", Color(170, 85, 255), desc = "5th terminal / levers / early-enter / core").withDependency { slotColors }

    fun slotColor(slot: Int): Color = when (slot) { 1 -> slot1; 2 -> slot2; 3 -> slot3; 4 -> slot4; else -> slot5 }

    // One sound per slot, each with its own id, pitch and volume and a "Play sound" button to
    // audition it. Defaults are five different note-block instruments so they tell apart untuned.
    // For now every slot is a note-block pling on a rising scale, one pitch per slot — Odin's own
    // sound-settings helper cannot take a default pitch, hence the local copy of it below.
    private val slotSounds by DropdownSetting("Slot Sounds")
    private val sound1 = soundSettings("Slot 1 Sound", "block.note_block.pling", 0.6f) { slotSounds }
    private val sound2 = soundSettings("Slot 2 Sound", "block.note_block.pling", 0.8f) { slotSounds }
    private val sound3 = soundSettings("Slot 3 Sound", "block.note_block.pling", 1.0f) { slotSounds }
    private val sound4 = soundSettings("Slot 4 Sound", "block.note_block.pling", 1.3f) { slotSounds }
    private val sound5 = soundSettings("Slot 5 Sound", "block.note_block.pling", 1.7f) { slotSounds }
    private val readySound = soundSettings("Leap Ready Sound", "block.note_block.pling", 2.0f) { slotSounds }

    /** Odin's `createSoundSettings`, plus a default pitch. Same four settings, same Play button. */
    private fun soundSettings(name: String, sound: String, pitchDefault: Float, deps: () -> Boolean): () -> Triple<String, Float, Float> {
        val id = +StringSetting(name, sound, desc = "Sound id, as /playsound takes it.", length = 64).withDependency { deps() }
        val pitch = +NumberSetting("$name Pitch", pitchDefault, 0.1f, 2f, 0.01f, desc = "Pitch.").withDependency { deps() }
        val volume = +NumberSetting("$name Volume", 1f, 0.1f, 1f, 0.01f, desc = "Volume.").withDependency { deps() }
        val get = { Triple(id.value, volume.value, pitch.value) }
        +ActionSetting("Play $name", desc = "Plays it.") { playSoundSettings(get()) }.withDependency { deps() }
        return get
    }

    private fun playSlot(slot: Int) = playSoundSettings(when (slot) { 1 -> sound1(); 2 -> sound2(); 3 -> sound3(); 4 -> sound4(); else -> sound5() })
    val announceToParty by BooleanSetting(
        "Announce Procs & Leaps", false,
        desc = "Only if you cannot run Odin's own Leap Announce and Announce Invincibility: EC sends them instead. With both on, the party hears everything twice.",
    )

    private val roleHud by HUD("Your Role", "Shows the phase-3 role you are on and what is left of it.") { example ->
        drawRoleHud(this, example)
    }

    /**
     * Everything the mod believes, on screen, so a clip of a run is enough to see where it went
     * wrong. Off by default; the same content is in `/brw debug` and in the game log under `[ec]`.
     */
    private val debugHud by HUD("Debug HUD", "Full internal state of the rotation: every player's role, tasks, masks, arrivals, pot exits used, and the last decisions.", false, x = 10, y = 120, scale = 1f) { example ->
        drawDebugHud(this, example)
    }

    /**
     * The team's starting roles, role id -> IGN, assembled from each client's "brw s1 <role>"
     * announcement. Every client hears the same announcements in the same order, so every client
     * builds the same map — no one has to type the whole team in. Cleared with the world.
     */
    val teamRoles = LinkedHashMap<String, String>()
    private var wasInBoss = false

    /** Which of the four sections is live, 1-based. Display only — the rotation itself is section-agnostic. */
    var section = 1
        private set
    private var sectionComplete = false
    private var gateBlown = false

    init {
        RotationEngine.masksAvailable = { ign -> MaskTracker.available(ign) }

        // Chat is read off the WIRE, not from Odin's chat event. Odin posts that event from Fabric's
        // ClientReceiveMessageEvents.ALLOW_GAME, which short-circuits: the moment any mod registered
        // ahead of it hides or rewrites a line (terminal-split features do exactly that to every
        // completion line), no later listener runs and the line simply never existed for us. Two
        // clients lost all of section 1 that way. The packet hook fires before any chat handling,
        // on the network thread, so the text is handed to the main thread in arrival order.
        //
        // Hypixel sends a terminal completion together with its sound and title, and the protocol
        // delivers that as ONE bundle packet. Odin's connection hook posts only the outer bundle;
        // its second hook, which should post each inner packet, demonstrably did not deliver a
        // single completion line in a real run. So bundles are opened here, by hand, and every
        // packet is remembered by identity so a line is never processed twice if both paths fire.
        //
        // And even that was not enough: blade-addons and devonian inject into the same network
        // method Odin does, ahead of it, and consume completion packets before Odin's hook ever
        // fires. So EC has its own mixin there at priority 1 (ConnectionTapMixin -> [tap]) — first
        // in line, read-only. Odin's event stays as a second path; [take] dedupes by identity.
        on<PacketEvent.Receive>(EventPriority.HIGHEST) { handlePacket(packet, "odin") }

        on<TickEvent.Server> {
            if (!enabled) return@on
            MaskTracker.tick()
            // Announce on entering the boss room — minutes before phase 3, so everyone's binding is
            // settled long before the first terminal line could arrive.
            val inBoss = DungeonUtils.inBoss
            if (inBoss && !wasInBoss) EngineerClient.safely("announce role") {
                announceMyRole()
                val check = SetupCheck.run()
                check.forEach { EcLog.log("SETUP", (if (it.ok) "ok   " else "FAIL ") + it.what + (if (!it.ok) " — ${it.fix}" else "")) }
                check.filter { !it.ok }.takeIf { it.isNotEmpty() }?.let { bad ->
                    EngineerClient.chat("§8[§6EC§8]§c ${bad.size} setup problem${if (bad.size == 1) "" else "s"} — §7/brw setup")
                }
            }
            wasInBoss = inBoss
        }

        on<TickEvent.End> {
            RoleVignette.tick()
            if (!enabled || !RotationEngine.running) return@on
            // The second half of the cue: your target has arrived, so it is time to click.
            EngineerClient.safely("leap ready") {
                if (LeapSignal.pollBecameReady()) {
                    if (leapSound) playSoundSettings(readySound())
                    if (roleVignette) RoleVignette.flash(LEAP_READY_COLOR, 20, leapQuadrant())
                }
            }
        }

        on<LevelEvent.Load> {
            EcLog.log("SECTION", "world load — state reset")
            teamRoles.clear()
            wasInBoss = false
            LeapSignal.reset()
            RoleVignette.clear()
            MaskTracker.reset()
            RotationEngine.reset()
            section = 1
            sectionComplete = false
            gateBlown = false
        }
    }

    /** Called from [com.engineerclient.mixin.ConnectionTapMixin] on the network thread, for every inbound packet. */
    fun tap(packet: Packet<*>) {
        EngineerClient.safely("packet tap") { handlePacket(packet, "tap") }
    }

    private fun handlePacket(packet: Packet<*>, via: String) {
        if (!enabled) return
        when (packet) {
            is ClientboundSystemChatPacket -> take(packet, via)
            is ClientboundBundlePacket -> {
                var n = 0
                packet.subPackets().forEach { inner -> if (inner is ClientboundSystemChatPacket) { take(inner, "$via-bundle"); n++ } }
                if (n > 0 && DungeonUtils.inBoss) EcLog.log("PKT", "bundle via $via with $n chat packet(s)")
            }
            else -> {}
        }
    }

    /** Packets already handed to [onChat], by identity — the last few hundred is plenty. */
    private val seenPackets: MutableSet<Packet<*>> = Collections.newSetFromMap(IdentityHashMap())
    private val seenOrder = ArrayDeque<Packet<*>>()

    private fun take(p: ClientboundSystemChatPacket, via: String) {
        if (p.overlay()) return
        synchronized(seenPackets) {
            if (!seenPackets.add(p)) return
            seenOrder.addLast(p)
            while (seenOrder.size > 512) seenPackets.remove(seenOrder.removeFirst())
        }
        val text = p.content().string
        EngineerClient.mc.execute {
            EngineerClient.safely("p3 chat") {
                if (DungeonUtils.inBoss && P3ChatParser.completion(text) != null) EcLog.log("PKT", "completion via $via")
                onChat(text)
            }
        }
    }

    private fun onChat(raw: String) {
        // Every line the mod looked at, verbatim, so the run can be replayed offline.
        if (DungeonUtils.inDungeons) EcLog.log("CHAT", raw)
        // Mask state is followed for the WHOLE dungeon, not just phase 3: a cooldown started in
        // an earlier phase is still running when the terminals begin.
        onMaskChat(raw)
        when {
            P3ChatParser.isPhaseStart(raw) -> {
                announceMyRole()   // a refresh, in case anyone joined the party after the boss door
                start()
            }

            P3ChatParser.isPhaseEnd(raw) -> {
                EcLog.log("SECTION", "phase 3 over (core opening)")
                if (RotationEngine.running) EngineerClient.chat("§8[§6EC§8]§7 phase 3 done.")
                RotationEngine.reset()
                section = 1
                sectionComplete = false
                gateBlown = false
            }

            P3ChatParser.isGateDestroyed(raw) -> {
                gateBlown = true
                if (sectionComplete) nextSection()
            }

            else -> P3ChatParser.completion(raw)?.let { done ->
                credit(done.ign, done.type)
                if (done.sectionDone) {
                    // 7/7 (8/8): whoever still holds an open role here had it done for them.
                    RotationEngine.completeSection(section)
                    myAfterSweep()
                    if (gateBlown) nextSection() else sectionComplete = true
                }
            }
        }
    }

    /**
     * Every client has to reach the same answer about masks and arrivals, so that state is only
     * ever taken from PARTY chat — one event, one order, seen by all five. Your own proc and leap
     * lines are sent only to you; EC forwards them itself, immediately, in Odin's wording. This
     * is a team mod with a prescribed setup, so the party is not expected to also have Odin's
     * announcements on — that would just say everything twice.
     */
    private fun onMaskChat(raw: String) {
        P3ChatParser.partyLine(raw)?.let { party ->
            P3ChatParser.startingRole(party.message)?.let { onRoleAnnouncement(party.ign, it); return }
            MaskTracker.onPartyAnnouncement(party.ign, party.message)
            // Arrival announcements from /posmsg boxes, and "Leaped to X!" — both are how the
            // leap cue learns that its target is in place.
            P3ChatParser.leapedTo(party.message)?.let { target -> RotationEngine.onLeapAnnounce(party.ign, target) }
                ?: run {
                    RotationEngine.onPartyMessage(party.ign, party.message)
                    // My own arrival can finish my role (core: "out of core") or put me in the core.
                    if (party.ign.equals(EngineerClient.mc.player?.name?.string, true)) myAfterSweep()
                }
            return
        }
        if (!announceToParty) return
        val clean = P3ChatParser.clean(raw)
        val me = EngineerClient.mc.player?.name?.string ?: return
        MaskTracker.Kind.ofSelfLine(clean)?.let { kind ->
            // Count what will be on cooldown once this one lands, matching Odin's "(n/3)".
            val onCooldown = (MaskTracker.onCooldown(me) + kind).toSet().size
            sendCommand("pc ${kind.name.lowercase().replaceFirstChar { it.uppercase() }} Procced! ($onCooldown/3)")
        }
        P3ChatParser.teleportedTo(clean)?.let { target -> sendCommand("pc Leaped to $target!") }
    }

    private fun nextSection() {
        section++
        sectionComplete = false
        gateBlown = false
        EcLog.log("SECTION", "now section $section")
    }

    private fun start() {
        EcLog.log("SECTION", "phase 3 start — team: " + teamRoles.entries.joinToString { "${it.value}=${RotationSpec.graph.name(it.key)}" })
        val me = EngineerClient.mc.player?.name?.string
        if (EcConfig.data.myStartingRole == null) {
            EngineerClient.chat("§8[§6EC§8]§c You have no starting role — §7set it with §f/brw role <role>§7.")
        }
        RotationEngine.begin(teamRoles)
        val missing = RotationSpec.graph.startingRoles.filter { it.id !in teamRoles }.map { it.name }
        if (missing.isNotEmpty()) EngineerClient.chat("§8[§6EC§8]§c nobody announced: §f${missing.joinToString(", ")}")
        val mine = me?.let { RotationEngine.roleOf(it) }
        EngineerClient.chat("§8[§6EC§8]§7 phase 3 — you are §a${mine?.name ?: "§cunassigned"}§7.")
    }

    /** Say which section-1 role I run, in the form every client parses. Only where it means anything: F7/M7. */
    fun announceMyRole() {
        val role = RotationSpec.graph.role(EcConfig.data.myStartingRole) ?: return
        if (!DungeonUtils.inDungeons || DungeonUtils.floor?.floorNumber != 7) return
        sendCommand("pc brw s1 ${role.name}")
    }

    /** After a section sweep my role may have changed without a line of mine — signal it like any hand-off. */
    private var lastSignalledRole: String? = null
    private fun myAfterSweep() {
        val me = EngineerClient.mc.player?.name?.string ?: return
        val now = RotationEngine.roleOf(me)?.id ?: if (RotationEngine.isFinished(me)) "recore" else null
        if (now == lastSignalledRole) return
        lastSignalledRole = now
        val role = RotationEngine.roleOf(me)
        if (role != null) signalRole(role) else if (RotationEngine.isFinished(me)) signalRecore()
    }

    /**
     * The leap-menu quadrant your current leap target occupies (Odin's `leapTeammates` order,
     * the same one LeapHighlight rings), or null when the option is off or they are not listed —
     * then the vignette falls back to the whole edge.
     */
    private fun leapQuadrant(): Int? {
        if (!vignetteCorner) return null
        val ign = LeapSignal.current()?.ign ?: return null
        val index = DungeonUtils.leapTeammates.indexOfFirst { it.name.equals(ign, ignoreCase = true) }
        return index.takeIf { it in 0..3 }
    }

    private fun signalRecore() {
        LeapSignal.reset()
        // Core's identity: slot 5, played twice so it reads as "core" rather than a hand-off.
        if (roleVignette) RoleVignette.flash(slotColor(5), quadrant = leapQuadrant())
        if (leapSound) { playSlot(5); playSlot(5) }
        if (announce) EngineerClient.chat("§8[§6EC§8]§7 next: §a§lcore§r §7— rush in; the first one there is who you leap to.")
    }

    private fun signalRole(next: RotationSpec.Role) {
        LeapSignal.reset()
        val slot = next.signalSlot
        if (roleVignette) RoleVignette.flash(slotColor(slot), quadrant = leapQuadrant())
        if (leapSound) playSlot(slot)
        if (announce) {
            val tail = if (next.early) " §7(early enter — the team leaps to you)" else ""
            EngineerClient.chat("§8[§6EC§8]§7 next: §a§l${next.name}§r$tail")
            LeapSignal.current()?.let { leap ->
                EngineerClient.chat("§8[§6EC§8]§7 leap to §b${leap.ign}§7 once they are in section ${leap.section}." +
                    if (leap.note.isNotBlank()) " §8${leap.note}" else "")
            }
            if (next.leapNote.isNotBlank() && LeapSignal.current() == null) EngineerClient.chat("§8[§6EC§8]§8 ${next.leapNote}")
        }
    }

    private fun onRoleAnnouncement(ign: String, roleName: String) {
        val role = RotationSpec.graph.startingRoles.firstOrNull { it.name.equals(roleName, ignoreCase = true) }
        if (role == null) {
            EcLog.log("WARN", "$ign announced unknown starting role '$roleName'")
            EngineerClient.chat("§8[§6EC§8]§c $ign announced unknown role '$roleName'")
            return
        }
        EcLog.log("ROLE", "$ign runs ${role.name}")
        // Last announcement wins, and a player moving roles releases their old one.
        teamRoles.entries.removeIf { it.value.equals(ign, ignoreCase = true) }
        teamRoles[role.id] = ign
        if (RotationEngine.running) RotationEngine.addStarter(ign, role.id)
    }

    private fun credit(ign: String, type: String) {
        val me = EngineerClient.mc.player?.name?.string
        val mine = ign.equals(me, ignoreCase = true)
        val next = RotationEngine.onTaskDone(ign, type)
        if (!mine) return
        when {
            next != null -> { lastSignalledRole = next.id; signalRole(next) }
            me != null && RotationEngine.isFinished(me) && lastSignalledRole != "recore" -> { lastSignalledRole = "recore"; signalRecore() }
        }
    }

    private fun myRole() = EngineerClient.mc.player?.name?.string?.let { RotationEngine.roleOf(it) }

    // ------------------------------------------------------------------- HUD

    private fun drawRoleHud(gfx: net.minecraft.client.gui.GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        if (example) {
            val w = gfx.textDim("§62§7:§a§l4  §7T L", 0, 0, Colors.WHITE).first
            gfx.text("§62§7:§a§l4  §7T L", 0, 0, Colors.WHITE)
            return w to 9
        }
        if (!RotationEngine.running || DungeonUtils.getF7Phase() != M7Phases.P3) return 0 to 0

        val me = EngineerClient.mc.player?.name?.string ?: return 0 to 0
        val lines = mutableListOf<String>()

        val role = RotationEngine.roleOf(me)
        lines += if (role == null) (if (RotationEngine.isFinished(me)) "§6Role §a§lcore §7— rush in" else "§6Role §8—") else {
            val left = RotationEngine.remainingFor(me).joinToString(" ") { shortType(it) }
            val flag = if (role.early) " §a✦" else ""
            // "2:4" — section in orange, role name in green — so the same job in a different
            // section reads differently at a glance.
            "§6${role.section}§7:§a§l${role.name}§r$flag${if (left.isEmpty()) "" else "  §7$left"}"
        }

        if (showTeam) {
            RotationEngine.tracked()
                .filter { !it.ign.equals(me, true) }
                .forEach { h ->
                    val name = RotationSpec.graph.name(h.roleId)
                    val left = h.remaining.joinToString(" ") { shortType(it) }
                    lines += "§7${h.ign}: §f$name${if (left.isEmpty()) "" else " §8$left"}"
                }
        }

        RotationEngine.stuck?.let { lines += "§cstuck at ${it.potName}" }

        var width = 0
        val chip = role?.let { slotColor(it.signalSlot) } ?: if (RotationEngine.isFinished(me)) slotColor(5) else null
        val x0 = if (chip != null) 11 else 0
        chip?.let { gfx.fill(0, 1, 8, 9, it.rgba) }
        lines.forEachIndexed { i, s ->
            width = maxOf(width, gfx.textDim(s, x0, i * 10, Colors.WHITE).first + x0)
            gfx.text(s, x0, i * 10, Colors.WHITE)
        }
        return width to lines.size * 10
    }

    private val LEAP_READY_COLOR = Color(255, 200, 70)

    /** The debug lines, shared by the HUD and `/brw debug`. */
    fun debugLines(): List<String> {
        val me = EngineerClient.mc.player?.name?.string ?: "?"
        val g = RotationSpec.graph
        val lines = mutableListOf<String>()
        lines += "§6EC debug §7spec v${g.version}  " +
            (if (RotationEngine.running) "§arunning" else "§8idle") +
            "  §7section §f$section §8(done=${if (sectionComplete) "y" else "n"} gate=${if (gateBlown) "y" else "n"})" +
            "  §7f7=§f${DungeonUtils.getF7Phase()}"
        lines += "§7leap: §f" + RotationEngine.leapDebug(me)

        RotationEngine.tracked().forEach { h ->
            val role = g.role(h.roleId)
            val name = role?.name ?: "?"
            val left = if (h.remaining.isEmpty()) "§8—" else h.remaining.joinToString(" ") { shortType(it) }
            val masks = MaskTracker.available(h.ign)
            val said = RotationEngine.saidBy(h.ign).filter { it != RotationSpec.ARRIVED_ON_LEAP }.take(3).joinToString(",")
            val leapt = if (RotationSpec.ARRIVED_ON_LEAP in RotationEngine.saidBy(h.ign)) " §a↯" else ""
            lines += "${if (h.ign.equals(me, true)) "§e" else "§7"}${h.ign.take(12).padEnd(12)} §f${name.padEnd(7)} §7S${role?.section ?: "?"} $left  §7m§f$masks$leapt" +
                (if (said.isNotEmpty()) " §8$said" else "")
        }
        RotationEngine.stuck?.let { lines += "§cSTUCK ${it.ign} at ${it.potName} after ${it.role}" }

        val pots = g.pots.joinToString("  ") { p ->
            val used = RotationEngine.usedExits(p.id)
            "§7${p.name.replace("Pot ", "P")}§8[" + p.exits.indices.joinToString("") { if (it in used) "§f${it + 1}" else "§8·" } + "§8]"
        }
        lines += pots

        RotationEngine.recent(7).forEach { lines += "§8$it" }
        return lines
    }

    private fun drawDebugHud(gfx: net.minecraft.client.gui.GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val lines = if (example) listOf("§6EC debug §7(example)", "§7leap: §f-> Skyyqt  READY", "§ep3wr         §f4       §7S2 §bT §aL  §7m§f3") else debugLines()
        if (!example && !enabled) return 0 to 0
        var width = 0
        lines.forEachIndexed { i, s ->
            width = maxOf(width, gfx.textDim(s, 0, i * 10, Colors.WHITE).first)
            gfx.text(s, 0, i * 10, Colors.WHITE)
        }
        return width to lines.size * 10
    }

    private fun shortType(type: String) = when (type) {
        "terminal" -> "§bT"
        "lever" -> "§aL"
        "device" -> "§6D"
        else -> "§7?"
    }
}
