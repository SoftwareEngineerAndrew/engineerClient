package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import com.engineerclient.misc.ScoreboardLines
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.world.scores.DisplaySlot
import org.lwjgl.glfw.GLFW

/**
 * Dungeon Recorder: everything that happens in a dungeon, for building mods with an LLM. Where
 * Better PF records a replay and Boss Recorder the boss fights, this keeps the lot:
 *
 *  - every packet the server sends and every packet you send, each written out field by field
 *    ([PacketJson]) with the server tick it arrived on;
 *  - your own state every tick (position, look, motion, health, held item, the screen open, the
 *    block being mined) and the game's derived state when it changes (Odin's floor, room and
 *    party, the sidebar);
 *  - your input as it happens and what it came to ([InputCapture]).
 *
 * Files: <game dir>/engineerclient-recordings/, one directory per recording: gzipped JSON Lines
 * parts with an index, a raw packet sidecar and a manifest ([RecorderSession], [Rec]; format:
 * docs/dungeon-recorder.md; tools/recorder/read.py turns them into readable timelines).
 */
object DungeonRecorder : Module(
    name = "Dungeon Recorder",
    category = Category.custom("Engineer Client"),
    description = "Records everything in a dungeon - every packet both ways, field by field, and your own state every tick - as context for building mods. Saved to engineerclient-recordings/ in the game folder.",
) {
    private val where by SelectorSetting("Where", "Dungeons", listOf("Dungeons", "Dungeons + Hub", "Everywhere"), desc = "When to record: in dungeons only, also in the Dungeon Hub (party finder, queueing), or always.")
    private val inbound by BooleanSetting("Server Packets", true, desc = "Every packet the server sends.")
    private val outbound by BooleanSetting("Your Packets", true, desc = "Every packet you send (movement, clicks, container clicks, item use).")
    private val movement by BooleanSetting("Entity Movement", true, desc = "Other entities' movement and head turns (the bulk of the packets).")
    private val effects by BooleanSetting("Particles And Sounds", true, desc = "Particle and sound packets.")
    private val chunks by BooleanSetting("Chunk Data", false, desc = "Chunk loads in full (large: every block of each loaded chunk). Off: only which chunk loaded.")
    private val state by BooleanSetting("Client State", true, desc = "Your own state every tick, Odin's dungeon state and the sidebar when they change.")
    private val typedChat by BooleanSetting("Typed Chat", false, desc = "What you type in chat and commands. Off: only that something was sent.")
    private val hidePrivate by BooleanSetting("Hide Private Chats", true, desc = "Leaves private messages, guild, officer and co-op chat and friend notices out.")
    /** Read by [InputCapture]'s hooks. */
    internal val inputOn by BooleanSetting("Input", true, desc = "Every key, mouse button, scroll and look turn, the actions they start, what Odin cancelled, what the crosshair is on and what each interaction returned.")
    internal val cursorMovesOn by BooleanSetting("Cursor Moves", true, desc = "Every cursor move, with its time in the tick (the largest part of the input lines).")
    private val cookiePayloads by BooleanSetting("Cookie Payloads", false, desc = "Include server cookie bytes (may hold session tokens); off writes length and hash only")
    private val minFreeGb by NumberSetting("Min Free Disk GB", 10.0, 0.0, 500.0, 1.0, desc = "Stops the recording (saying so in the file) when the disk has less free space than this.")
    private val maxFolderGb by NumberSetting("Max Recordings Folder GB", 0.0, 0.0, 2000.0, 10.0, desc = "0 = no limit. Stops the recording when the recordings folder grows past this.")
    private val deleteOldest by BooleanSetting("Delete Oldest When Full", false, desc = "At the folder limit, deletes the oldest finished recordings instead of stopping. Never the one being written.")
    private val compactEntities by BooleanSetting("Compact Entity Rows", false, desc = "Writes the per-tick entity rows to a separate xz file per part (smaller, slower to read).")
    private val bookmark by KeybindSetting("Bookmark", GLFW.GLFW_KEY_UNKNOWN, "Marks this moment in the recording (also /ecrec mark [note]).").onPress { EngineerClient.safely("recorder bookmark") { Rec.mark(null) } }
    private val openFolder by ActionSetting("Open Folder", desc = "Opens the folder the recordings are saved in.") {
        EngineerClient.safely("recorder folder") { java.nio.file.Files.createDirectories(dir); net.minecraft.util.Util.getPlatform().openPath(dir) }
    }

    private val dir get() = EngineerClient.mc.gameDirectory.toPath().resolve("engineerclient-recordings")

    @Volatile private var session: RecorderSession? = null
    private var lastState = ""
    private var lastDungeon = ""
    private var lastSidebar = ""

    /** Packet types never worth a line: keep-alives and the bundle markers. (Light is kept: its arrays are decoded per section.) */
    private val SKIP = setOf("minecraft:keep_alive", "minecraft:pong", "minecraft:bundle_delimiter", "minecraft:chunk_batch_start", "minecraft:chunk_batch_finished")
    private val MOVEMENT = setOf("minecraft:move_entity_pos", "minecraft:move_entity_pos_rot", "minecraft:move_entity_rot", "minecraft:rotate_head",
        "minecraft:set_entity_motion", "minecraft:entity_position_sync", "minecraft:teleport_entity")
    private val EFFECTS = setOf("minecraft:level_particles", "minecraft:sound", "minecraft:sound_entity")

    init {
        // Each world gets its own recording, started as it loads so its first packets (the entities
        // and blocks already there) are kept, and only written once it turns out to be wanted.
        on<LevelEvent.Load> { EngineerClient.safely("recorder world") { stop(); if (enabled) start() } }
        on<LevelEvent.Unload> { EngineerClient.safely("recorder world end") { stop() } }
        on<TickEvent.End> { EngineerClient.safely("recorder tick") { onTick() } }
        InputCapture.install()

        // A recording the game did not get to close (a crash) is cut back to its last whole member
        // and renamed; off the game thread, it only touches files.
        Thread({ EngineerClient.safely("recorder recovery") {
            RecorderFiles.recover(net.fabricmc.loader.api.FabricLoader.getInstance().gameDir.resolve("engineerclient-recordings"))
                .forEach { EngineerClient.logger.info("[ec] recorder: $it") }
        } }, "ec-recorder-recover").start()
        ClientLifecycleEvents.CLIENT_STOPPING.register { RecorderSession.shutdownAll(3000) }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("ecrec").then(literal("mark")
                .executes { ctx -> bookmarkCommand(ctx.source, null); 1 }
                .then(argument("note", StringArgumentType.greedyString()).executes { ctx ->
                    bookmarkCommand(ctx.source, StringArgumentType.getString(ctx, "note")); 1
                })))
        }

        onSend<Packet<*>>(priority = -1000) {
            if (!Rec.active || !outbound) return@onSend
            val p = this
            val type = PacketJson.type(p)
            if (type in SKIP || type == "minecraft:pong") return@onSend
            val redact = !Rec.typedChat && (p is ServerboundChatPacket || p is ServerboundChatCommandPacket || p is ServerboundChatCommandSignedPacket)
            packetLine("out", type, p, if (redact) ({ "{\"redacted\":true}" }) else PacketJson.capture(p))
        }
    }

    private fun bookmarkCommand(source: net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource, note: String?) {
        if (!Rec.active) { source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7Dungeon Recorder is not recording.")); return }
        EngineerClient.safely("recorder bookmark") { Rec.mark(note) }
        source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7recording marked" + (note?.let { ": §f$it" } ?: ".")))
    }

    /** Every packet from the server, on the network thread, ahead of any mod that could cancel it (ConnectionTapMixin). */
    fun tap(packet: Packet<*>) {
        if (!enabled) return
        if (packet is ClientboundPingPacket && packet.id != 0) Rec.serverTicks++
        if (!Rec.active || !inbound) return
        EngineerClient.safely("recorder tap") { inbound(packet) }
    }

    private fun inbound(p: Packet<*>) {
        if (p is ClientboundBundlePacket) { p.subPackets().forEach { inbound(it) }; return }
        val type = PacketJson.type(p)
        if (type in SKIP) return
        if ((type in MOVEMENT && !movement) || (type in EFFECTS && !effects)) return
        val text = when (p) { is ClientboundSystemChatPacket -> p.content.string; is ClientboundPlayerChatPacket -> p.body.content; else -> null }
        if (text != null && Rec.privateText(text)) return
        val body: () -> String =
            if (p is ClientboundLevelChunkWithLightPacket && !chunks) { val x = p.x; val z = p.z; { "{\"x\":$x,\"z\":$z}" } }
            else PacketJson.capture(p)
        packetLine("in", type, p, body)
    }

    /**
     * One packet line: the envelope (seq, ticks, clock taken now, on the packet's own thread), its
     * type and the entities it is about, then its fields. [body] comes from [PacketJson.capture]: the
     * packets holding mutable state are already a finished string, the rest are built on the writer
     * thread.
     */
    private fun packetLine(dir: String, type: String, p: Packet<*>, body: () -> String) {
        val seq = Rec.nextSeq()
        val env = Rec.envelope(dir, seq)
        val e = PacketDecode.entityMembers(p)
        val pt = q(type)
        Rec.emitLine(seq, 512, type, System.currentTimeMillis()) { "$env,\"p\":$pt$e,\"f\":${body()}}" }
    }

    // ------------------------------------------------------------------ lifecycle and client state

    private fun wanted(): Boolean = when (where) {
        0 -> DungeonUtils.inDungeons
        1 -> DungeonUtils.inDungeons || LocationUtils.isCurrentArea(com.odtheking.odin.utils.skyblock.Island.DungeonHub)
        else -> EngineerClient.mc.level != null
    }

    /** Whether the current session has been confirmed, and when it started (for the give-up). */
    private var confirmed = false
    private var startedTick = 0

    private fun onTick() {
        Rec.tick++
        pushConfig()
        Rec.onTickEnd()
        val s = session ?: return
        if (!confirmed) {
            // Odin knows the area a second or two after the world loads; a minute without it is not one to keep.
            if (wanted()) { confirmed = true; s.confirm(label()); Rec.requestKeyframe("confirm") }
            else if (Rec.tick - startedTick > 20 * 60 || (where != 2 && LocationUtils.currentArea != com.odtheking.odin.utils.skyblock.Island.Unknown && !wanted())) {
                session = null; Rec.end(s); s.abandon(); return
            }
        }
        if (state) clientState()
    }

    private fun label() = DungeonUtils.floor?.name ?: LocationUtils.currentArea.name

    /** Hands the core the settings it acts on, and how to read all of them for `settings` lines. */
    private fun pushConfig() {
        PacketJson.cookiePayloads = cookiePayloads
        val c = RecConfig(hidePrivate, typedChat, compactEntities, minFreeGb, maxFolderGb, deleteOldest)
        if (c != Rec.config) Rec.config = c
        if (Rec.settingsSource == null) Rec.settingsSource = { settingsSnapshot() }
    }

    private fun settingsSnapshot(): Map<String, String> =
        settings.entries.filter { it.value !is ActionSetting }.associate { (k, v) -> k to runCatching { v.value.toString() }.getOrDefault("?") }

    private fun settingsJson() = settingsSnapshot().entries.joinToString(",", "{", "}") { "${q(it.key)}:${q(it.value)}" }

    private fun start() {
        val mc = EngineerClient.mc
        val version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("engineerclient").map { it.metadata.version.friendlyString }.orElse("?")
        confirmed = false; startedTick = Rec.tick
        pushConfig()
        val meta = """"mod":${q(version)},"mc":"26.1.2","self":${q(mc.player?.name?.string ?: "?")},""" +
            """"selfId":${mc.player?.id ?: -1},"server":${q(mc.currentServer?.ip ?: "?")},"settings":${settingsJson()}"""
        val s = RecorderSession(dir, meta)
        session = s
        Rec.begin(s)
        lastState = ""; lastDungeon = ""; lastSidebar = ""
    }

    private fun stop() {
        val s = session ?: return
        session = null
        if (!confirmed) { Rec.end(s); s.abandon(); return }
        Rec.emit("end", "")
        Rec.end(s)
        s.close()
    }

    /** Turned on mid-world: record from here (the world's first packets are already gone). */
    override fun onEnable() {
        super.onEnable()
        if (EngineerClient.mc.level != null && session == null) EngineerClient.safely("recorder on") { start(); Rec.requestKeyframe("enable") }
    }

    override fun onDisable() {
        super.onDisable()
        stop()
    }

    /** Your own state, every tick it changes; Odin's dungeon state and the sidebar when they change. */
    private fun clientState() {
        val mc = EngineerClient.mc
        val p = mc.player ?: return
        val held = RichJson.itemNow(p.mainHandItem)
        val screen = mc.screen?.let { "{\"class\":${q(it.javaClass.simpleName)},\"title\":${q(it.title.string)}}" } ?: "null"
        val v = p.deltaMovement
        val st = """"pos":[${f(p.x)},${f(p.y)},${f(p.z)}],"rot":[${f1(p.yRot)},${f1(p.xRot)}],"vel":[${f(v.x)},${f(v.y)},${f(v.z)}],""" +
            """"ground":${p.onGround()},"hp":${f1(p.health)},"abs":${f1(p.absorptionAmount)},"food":${p.foodData.foodLevel},""" +
            """"slot":${p.inventory.selectedSlot},"held":$held,"screen":$screen""" + InputCapture.mineMembers()
        if (st != lastState) { lastState = st; Rec.emit("me", st) }

        if (Rec.tick % 10 != 0) return
        val effects = p.activeEffects.joinToString(",") { "[${q(BuiltInRegistries.MOB_EFFECT.getKey(it.effect.value()).toString())},${it.amplifier},${it.duration}]" }
        val team = DungeonUtils.dungeonTeammates.joinToString(",") { "[${q(it.name)},${q(it.clazz.name)},${it.isDead}]" }
        val dungeon = """"area":${q(LocationUtils.currentArea.name)},"floor":${q(DungeonUtils.floor?.name ?: "")},"boss":${DungeonUtils.inBoss},""" +
            """"room":${q(DungeonUtils.currentRoomName)},"party":[$team],"effects":[$effects],"fps":${mc.fps}"""
        if (dungeon != lastDungeon) { lastDungeon = dungeon; Rec.emit("game", dungeon) }

        val board = mc.level?.scoreboard
        val objective = board?.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (board != null && objective != null) {
            val lines = ScoreboardLines.sidebarEntries(board, objective).joinToString(",") { q(ScoreboardLines.plain(ScoreboardLines.lineText(board, it))) }
            val sidebar = """"title":${q(objective.displayName.string)},"lines":[$lines]"""
            if (sidebar != lastSidebar) { lastSidebar = sidebar; Rec.emit("sidebar", sidebar) }
        }
    }

    private fun q(s: String) = com.google.gson.JsonPrimitive(s).toString()
    private fun f(d: Double) = String.format(java.util.Locale.ROOT, "%.3f", d)
    private fun f1(v: Float) = String.format(java.util.Locale.ROOT, "%.1f", v)
}
