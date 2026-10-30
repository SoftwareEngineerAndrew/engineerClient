package com.devgineerclient.betterrecorder

import com.devgineerclient.DevgineerClient
import com.devgineerclient.mixin.BrecKeyClicksAccessor
import com.devgineerclient.mixin.RecTabOverlayAccessor
import com.devgineerclient.recorder.RecConfig
import com.devgineerclient.recorder.Rec
import com.devgineerclient.recorder.RecorderFiles
import com.devgineerclient.recorder.RecorderSession
import com.devgineerclient.recorder.RichJson
import com.engineerclient.adr.ADR
import com.engineerclient.adr.RunRecorder
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.StringArgumentType
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.SharedConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import java.nio.file.Files
import java.nio.file.Path

/**
 * Better Recorder: every Hypixel dungeon run, losslessly, cheap enough to leave on - for ADS's fidelity replay. While ADR
 * records a run (Hypixel only), it keeps every packet both ways as raw bytes ([BrecWire]: with the configuration data a
 * reader needs and the world's packets from before the run was known), what never goes over the wire (keys held,
 * presses, look, position, motion, the open screen, frame and tick timing, ping) as a line per client tick with only
 * what changed, and a profile snapshot at the start. Local only, nothing is uploaded. On by default.
 *
 * Written with the Dungeon Recorder's machinery ([RecorderSession]: writer thread, bounded queue, gap lines, raw
 * sidecar, crash recovery) into devgineerclient-recordings/better/<ADR stamp>.br/. FORMAT.md next to this file says
 * what is in it.
 */
object BetterRecorder : Module(
    name = "Better Recorder",
    key = null,
    category = Category.custom("Devgineer Client", 860, 10),
    description = "Records every Hypixel dungeon run in full (every packet raw, your inputs and look per tick, your profile at the start) into a folder next to the ADR run, for ADS's replay. Records only while ADR does; local only.",
) {
    private val openFolder by ActionSetting("Open Folder", desc = "Opens the folder the recordings are saved in.") {
        DevgineerClient.safely("better recorder folder") { Files.createDirectories(dir); com.mojang.blaze3d.Blaze3D.openPath(dir) }
    }

    /** Whether the module has had its one-time switch-on (on by default, existing installs too). */
    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    fun enableByDefault() {
        if (switchedOn) return
        switchedOn = true
        if (!enabled) toggle()
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }

    private val dir: Path get() = DevgineerClient.mc.gameDirectory.toPath().resolve("devgineerclient-recordings").resolve("better")

    private class Session(val run: RunRecorder, val rec: RecorderSession) {
        /** The last value written per input field. */
        val last = HashMap<String, String>()
        var lastTickNs = 0L
        var ticks = 0L
    }

    private var session: Session? = null
    /** A run whose recording the session stopped itself (disk nearly full): not started again. */
    private var stoppedRun: RunRecorder? = null
    /** Client ticks since the game started (the n of every line). */
    private var clientTick = 0
    /** Presses waiting at the start of this tick, before the game took them: use, attack, drop. */
    private val clicks = IntArray(3)
    private var keyBits = 0
    /** From chat: "Profile ID: ..." and "You are playing on profile: ..." (Hypixel says these as you join SkyBlock). */
    @Volatile private var profileId: String? = null
    @Volatile private var profileName: String? = null

    init {
        ClientTickEvents.START_CLIENT_TICK.register { DevgineerClient.safely("better recorder input") { tickStart() } }
        ClientTickEvents.END_CLIENT_TICK.register { DevgineerClient.safely("better recorder tick") { tickEnd() } }
        ClientLifecycleEvents.CLIENT_STOPPING.register { DevgineerClient.safely("better recorder exit") { stop(wait = true) } }

        onReceive<ClientboundSystemChatPacket> {
            if (overlay) return@onReceive
            val text = content.string
            PROFILE_ID.find(text)?.let { profileId = it.groupValues[1] }
            PROFILE_NAME.find(text)?.let { profileName = it.groupValues[1] }
        }

        ClientCommandRegistrationCallback.EVENT.register { d, _ ->
            d.register(literal("brec")
                .executes { DevgineerClient.msg(status()); 1 }
                .then(literal("mark").executes { mark(null); 1 }.then(argument("note", StringArgumentType.greedyString()).executes { c -> mark(StringArgumentType.getString(c, "note")); 1 })))
        }

        // Recordings a crash left unfinished are cut back to their last whole member.
        Thread({ runCatching { RecorderFiles.recover(dir) } }, "better-recorder-recover").apply { isDaemon = true; start() }
    }

    override fun onEnable() {
        BrecWire.on = true
        super.onEnable()
    }

    override fun onDisable() {
        stop(wait = false)
        BrecWire.on = false
        BrecWire.buffering = false
        BrecWire.reset()
        super.onDisable()
    }

    private fun tickStart() {
        clientTick++
        BrecWire.tick = clientTick
        if (session == null) return
        val o = DevgineerClient.mc.options
        clicks[0] = (o.keyUse as BrecKeyClicksAccessor).`brec$clicks`()
        clicks[1] = (o.keyAttack as BrecKeyClicksAccessor).`brec$clicks`()
        clicks[2] = (o.keyDrop as BrecKeyClicksAccessor).`brec$clicks`()
        var b = 0
        val keys = arrayOf<KeyMapping>(o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint, o.keyUse, o.keyAttack, o.keyDrop)
        for (i in keys.indices) if (keys[i].isDown) b = b or (1 shl i)
        if (InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL)) b = b or (1 shl 10)
        keyBits = b
    }

    private fun tickEnd() {
        BrecWire.on = enabled
        BrecWire.buffering = enabled && ADR.onHypixel()
        if (!BrecWire.buffering) BrecWire.dropWorld()
        val run = if (enabled && ADR.onHypixel()) ADR.recording else null
        if (session?.run !== run) { stop(wait = false); if (run != null && run !== stoppedRun) start(run) }
        val s = session ?: return
        if (!s.rec.running) { stoppedRun = s.run; stop(wait = false); return }
        val now = System.nanoTime()
        val sb = StringBuilder(160)
        sb.append(",\"dt\":").append((now - s.lastTickNs) / 1000)
        s.lastTickNs = now
        val times = BrecWire.drainTimes()
        if (times.isNotEmpty()) {
            sb.append(",\"fr\":[")
            var i = 0
            while (i < times.size) {
                if (i > 0) sb.append(',')
                sb.append(times[i]).append(',').append((times[i + 1] - s.rec.startNs) / 1000)
                i += 2
            }
            sb.append(']')
        }
        val input = input(s)
        if (input.isNotEmpty()) sb.append(",\"in\":{").append(input).append('}')
        line(s, "tk", sb)
        s.ticks++
    }

    /** This tick's inputs and own state, only the fields that changed (as `"key":value` members). */
    private fun input(s: Session): String {
        val mc = DevgineerClient.mc
        val out = StringBuilder()
        fun put(key: String, value: String) {
            if (s.last.put(key, value) == value) return
            if (out.isNotEmpty()) out.append(',')
            out.append('"').append(key).append("\":").append(value)
        }
        put("k", keyBits.toString())
        // Presses are events, not state: written whenever there are any.
        if (clicks[0] or clicks[1] or clicks[2] != 0) {
            if (out.isNotEmpty()) out.append(',')
            out.append("\"c\":[").append(clicks[0]).append(',').append(clicks[1]).append(',').append(clicks[2]).append(']')
        }
        val p = mc.player
        if (p != null) {
            put("sl", p.inventory.selectedSlot.toString())
            put("r", "[${p.yRot},${p.xRot}]")
            put("p", "[${p.x},${p.y},${p.z}]")
            val v = p.deltaMovement
            put("v", "[${v.x},${v.y},${v.z}]")
            put("g", if (p.onGround()) "1" else "0")
            put("st", (if (p.isSprinting) 1 else 0).or(if (p.isShiftKeyDown) 2 else 0).or(if (p.isUsingItem) 4 else 0).toString())
            put("ping", (mc.connection?.getPlayerInfo(p.uuid)?.latency ?: -1).toString())
        }
        val scr = mc.gui.screen()
        put("scr", if (scr == null) "null" else "[${RecorderFiles.q(scr.javaClass.simpleName)},${RecorderFiles.q(scr.title.string)}]")
        put("fps", mc.fps.toString())
        return out.toString()
    }

    private fun start(run: RunRecorder) {
        Files.createDirectories(dir)
        val mc = DevgineerClient.mc
        val p = mc.player
        val v = SharedConstants.getCurrentVersion()
        val meta = buildString {
            append("\"brec\":").append(FORMAT_VERSION)
            append(",\"brecFormat\":").append(RecorderFiles.q(FORMAT_NOTE))
            append(",\"adr\":").append(RecorderFiles.q(run.stamp))
            append(",\"mc\":").append(RecorderFiles.q(v.name()))
            append(",\"protocol\":").append(v.protocolVersion())
            append(",\"uuid\":").append(RecorderFiles.q(p?.uuid?.toString()))
            append(",\"name\":").append(RecorderFiles.q(p?.gameProfile?.name()))
            append(",\"server\":").append(RecorderFiles.q(mc.currentServer?.ip))
            append(",\"profileId\":").append(RecorderFiles.q(profileId))
            append(",\"profileName\":").append(RecorderFiles.q(profileName))
        }
        val rec = RecorderSession(dir, meta, { CONFIG }) { msg ->
            if (!msg.contains("Dungeon Recorder saved")) DevgineerClient.msg(msg.replace("Dungeon Recorder", "Better Recorder"))
        }
        rec.confirm("brec")
        val s = Session(run, rec)
        s.lastTickNs = System.nanoTime()
        session = s
        val (frames, dropped) = BrecWire.goLive(rec)
        // The frames from before the run was known: their (seq, µs since start (negative), client tick).
        val pre = StringBuilder(frames.size * 24 + 32).append(",\"fr\":[")
        frames.forEachIndexed { i, f ->
            if (i > 0) pre.append(',')
            pre.append(f.seq).append(',').append((f.ns - rec.startNs) / 1000).append(',').append(f.tick)
        }
        pre.append(']')
        if (dropped > 0) pre.append(",\"dropped\":").append(dropped)
        line(s, "pre", pre)
        p?.let { line(s, "prof", profile(it)) }
    }

    /** What the client can see of you without opening a menu, as the run starts. */
    private fun profile(p: LocalPlayer): StringBuilder {
        val sb = StringBuilder(32 * 1024)
        sb.append(",\"profileId\":").append(RecorderFiles.q(profileId)).append(",\"profileName\":").append(RecorderFiles.q(profileName))
        sb.append(",\"hp\":").append(p.health).append(",\"maxHp\":").append(p.maxHealth).append(",\"abs\":").append(p.absorptionAmount)
            .append(",\"food\":").append(p.foodData.foodLevel).append(",\"sat\":").append(p.foodData.saturationLevel)
            .append(",\"xp\":[").append(p.experienceLevel).append(',').append(p.experienceProgress).append(']')
        // Slots 0-8 hotbar, 9-35 inventory, 36-39 armour (feet to head), 40 offhand; every component.
        sb.append(",\"inv\":[")
        val inv = p.inventory
        for (i in 0 until inv.containerSize) {
            if (i > 0) sb.append(',')
            val stack = inv.getItem(i)
            if (stack.isEmpty) sb.append("null") else RichJson.item(sb, stack)
        }
        sb.append(']')
        sb.append(",\"attrs\":[")
        p.attributes.syncableAttributes.forEachIndexed { i, a ->
            if (i > 0) sb.append(',')
            sb.append('[').append(RecorderFiles.q(a.attribute.registeredName)).append(',').append(a.baseValue).append(',').append(a.value).append(",[")
            a.modifiers.forEachIndexed { j, m ->
                if (j > 0) sb.append(',')
                sb.append('[').append(RecorderFiles.q(m.id().toString())).append(',').append(m.amount()).append(',').append(m.operation().ordinal).append(']')
            }
            sb.append("]]")
        }
        sb.append(']')
        sb.append(",\"fx\":[")
        p.activeEffects.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append('[').append(RecorderFiles.q(e.effect.registeredName)).append(',').append(e.amplifier).append(',').append(e.duration).append(']')
        }
        sb.append(']')
        // The tab list as drawn (Hypixel's widgets: stats, pet, ...), its header and footer.
        val overlay = DevgineerClient.mc.gui.hud.tabList
        val acc = overlay as RecTabOverlayAccessor
        sb.append(",\"tab\":[")
        acc.`dc$recPlayerInfos`().forEachIndexed { i, info ->
            if (i > 0) sb.append(',')
            RichJson.component(sb, overlay.getNameForDisplay(info))
        }
        sb.append(']')
        sb.append(",\"tabHeader\":"); acc.`dc$recHeader`()?.let { RichJson.component(sb, it) } ?: sb.append("null")
        sb.append(",\"tabFooter\":"); acc.`dc$recFooter`()?.let { RichJson.component(sb, it) } ?: sb.append("null")
        return sb
    }

    private fun stop(wait: Boolean) {
        val s = session ?: return
        session = null
        BrecWire.stopLive(s.rec)
        line(s, "end", StringBuilder(",\"ticks\":").append(s.ticks))
        val adr = s.run.stamp
        val finish = Runnable {
            if (!s.rec.closeAndWait(if (wait) 3000 else 120_000)) return@Runnable
            // Named after the ADR run (the session names itself by its own start), unless that name is taken.
            val target = dir.resolve("$adr.br")
            runCatching { if (!Files.exists(target)) Files.move(s.rec.dir, target) }
        }
        if (wait) finish.run() else Thread(finish, "better-recorder-close").apply { isDaemon = false; start() }
    }

    private fun line(s: Session, kind: String, body: CharSequence) {
        val seq = Rec.nextSeq()
        val ms = System.currentTimeMillis()
        val t = s.run.currentTick
        val text = StringBuilder(body.length + 96).append(RecorderFiles.envelope(kind, seq, t, clientTick, ms, System.nanoTime() - s.rec.startNs))
            .append(body).append('}').toString()
        s.rec.line(seq, kind, ms, text, t = t, n = clientTick)
    }

    private fun mark(note: String?) {
        val s = session ?: run { DevgineerClient.msg("§7Better Recorder: not recording."); return }
        line(s, "mark", StringBuilder(",\"note\":").append(RecorderFiles.q(note)))
        s.rec.addMark(Rec.nextSeq(), System.currentTimeMillis(), note)
        DevgineerClient.msg("§aMarked§7 at tick ${s.run.currentTick}${if (note != null) ": $note" else ""}")
    }

    private fun status(): String {
        val s = session ?: return "§7Better Recorder: not recording${if (!enabled) " (off)" else " (it records while ADR records a Hypixel dungeon run)"}."
        return "§aBetter Recorder§7: ${s.run.stamp}.br, tick ${s.run.currentTick}, ${s.ticks} client ticks."
    }

    private const val FORMAT_VERSION = 3
    private const val FORMAT_NOTE = "Better Recorder v3 (src/dev/betterrecorder/FORMAT.md): Dungeon Recorder layout (manifest.json, partNNNN.jsonl.gz + .raw.gz sidecar + .idx.jsonl). " +
        "Lines: envelope {k,seq,t=ADR tick,n=client tick,ms,ns}. pre {fr:[seq,us,clientTick]...} frames buffered before the run; " +
        "tk {dt us, fr:[seq,us]... live frames this tick, in:{changed: k key bits fwd,back,left,right,jump,sneak,sprint,use,attack,drop,ctrl; c [use,attack,drop] presses; sl; r [yaw,pitch]; p; v; g; st sprint|sneak<<1|using<<2; ping; scr; fps}}; " +
        "prof (profile at start); mark; end. Raw sidecar: [u32 len][varlong seq][dir 0 in/1 out][phase 3 config/4 play][withheld][packet id + payload, uncompressed]."

    /** Small enough to always leave on: stops (with a line) under 5 GB free; no folder cap. */
    private val CONFIG = RecConfig(minFreeGb = 5.0)
    private val PROFILE_ID = Regex("""^Profile ID: ([0-9a-f-]{32,36})""")
    private val PROFILE_NAME = Regex("""^You are playing on profile: (\w+)""")
}
