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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket
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
    private val chunks by BooleanSetting("Chunk Data", true, desc = "Every block, block entity, biome, heightmap and light of each loaded chunk.")
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
    internal val entityTicks by BooleanSetting("Entity Ticks", true, desc = "Every entity's position, rotation, motion and health each tick it changes, and each move the client applies.")
    internal val renderedEntities by BooleanSetting("Rendered Entities", true, desc = "Which entities were drawn each tick, with their name tags and outlines.")
    private val compactEntities by BooleanSetting("Compact Entity Rows", false, desc = "Writes the per-tick entity rows to a separate xz file per part (smaller, slower to read).")
    private val rawPackets by BooleanSetting("Raw Packets", true, desc = "Also keeps every packet's exact bytes as they crossed the wire, both ways, in a sidecar file (the ground truth behind each line).")
    private val bookmark by KeybindSetting("Bookmark", GLFW.GLFW_KEY_UNKNOWN, "Marks this moment in the recording (also /ecrec mark [note]).").onPress { EngineerClient.safely("recorder bookmark") { Rec.mark(null) } }
    private val openFolder by ActionSetting("Open Folder", desc = "Opens the folder the recordings are saved in.") {
        EngineerClient.safely("recorder folder") { java.nio.file.Files.createDirectories(dir); net.minecraft.util.Util.getPlatform().openPath(dir) }
    }

    internal val dir get() = EngineerClient.mc.gameDirectory.toPath().resolve("engineerclient-recordings")

    /** The session the change-only lines below were last compared in; a new one starts them over. */
    private var lastSession: RecorderSession? = null
    private var lastState = ""
    private var lastDungeon = ""
    private var lastSidebar = ""

    /**
     * Packet types never written as lines. Empty: keep-alives, pongs and chunk batches carry timing,
     * and bundles have their own `bundle` line. Kept as the place for a user filter.
     */
    private val SKIP = emptySet<String>()
    private val MOVEMENT = setOf("minecraft:move_entity_pos", "minecraft:move_entity_pos_rot", "minecraft:move_entity_rot", "minecraft:rotate_head",
        "minecraft:set_entity_motion", "minecraft:entity_position_sync", "minecraft:teleport_entity")
    private val EFFECTS = setOf("minecraft:level_particles", "minecraft:sound", "minecraft:sound_entity")

    init {
        // Each world gets its own recording, opened at its login packet by the wire tap
        // (RecorderLifecycle); joining a world without one (a missed login) opens it here.
        on<LevelEvent.Load> { EngineerClient.safely("recorder world") { RecorderLifecycle.onJoin() } }
        // Fabric's event rather than Odin's LevelEvent.Unload: it says which connection left, so the
        // world before's late disconnect never ends the next world's recording.
        ClientPlayConnectionEvents.DISCONNECT.register { handler, _ -> EngineerClient.safely("recorder world end") { RecorderLifecycle.onDisconnect(handler) } }
        on<TickEvent.End> { EngineerClient.safely("recorder tick") { onTick() } }
        EngineerClient.safely("recorder world capture") { WorldCapture.install() }
        EntityCapture.install()
        PacketFate.install()
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

        // Last of all listeners and including cancelled sends: "cancelled" says whether some mod
        // stopped it. Whether it really left is the encoder's `wire_out` line (WireTap).
        onSend<Packet<*>>(priority = Int.MIN_VALUE) { ev ->
            if (!Rec.active || !outbound) return@onSend
            val p = this
            EngineerClient.safely("recorder out") {
                // Odin's hook is on every Connection: the integrated server's sends are clientbound.
                if (runCatching { p.type().flow() }.getOrNull() == PacketFlow.CLIENTBOUND) return@safely
                val type = PacketJson.type(p)
                if (type in SKIP) return@safely
                packetLine("out", type, p, outBody(p), ",\"ph\":\"${WireTap.phase()}\",\"cancelled\":${ev.isCancelled}")
            }
        }
    }

    /**
     * An outbound packet's "f": in full, or with what you typed left out when Typed Chat is off. A
     * command keeps its name (which command was run is not private), a chat message its length and
     * signing data.
     */
    internal fun outBody(p: Packet<*>): () -> String {
        if (Rec.typedChat) return PacketJson.capture(p)
        val s = when (p) {
            is ServerboundChatCommandPacket -> WireTap.redactedCommand(p.command())
            is ServerboundChatCommandSignedPacket -> WireTap.redactedCommand(p.command())
            is ServerboundChatPacket -> StringBuilder(128).append("{\"redacted\":true,\"len\":").append(p.message().length)
                .append(",\"timeStamp\":").append(runCatching { p.timeStamp().toEpochMilli() }.getOrDefault(-1L))
                .append(",\"salt\":").append(PacketJson.writeNow(p.salt()))
                .append(",\"lastSeen\":").append(PacketJson.writeNow(p.lastSeenMessages())).append('}').toString()
            else -> return PacketJson.capture(p)
        }
        return { s }
    }

    private fun bookmarkCommand(source: net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource, note: String?) {
        if (!Rec.active) { source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7Dungeon Recorder is not recording.")); return }
        EngineerClient.safely("recorder bookmark") { Rec.mark(note) }
        source.sendFeedback(Component.literal("${EngineerClient.PREFIX}§7recording marked" + (note?.let { ": §f$it" } ?: ".")))
    }

    /**
     * Every packet any connection receives, on its network thread, ahead of any mod that could cancel
     * it (ConnectionTapMixin). [WireTap] keeps the game connection's and calls back into [inbound].
     */
    fun tap(conn: Connection, packet: Packet<*>) {
        if (!enabled) { WireTap.clearPending(); return }
        EngineerClient.safely("recorder tap") { WireTap.tap(conn, packet); PacketFate.readBegin(packet) }
    }

    /** Whether frames' bytes are kept (Raw Packets); read on the network thread. */
    internal fun rawOn(): Boolean = enabled && rawPackets

    /** A private chat line Hide Private Chats leaves out (in a bundle: any of its packets). */
    internal fun hiddenPrivate(p: Packet<*>): Boolean = when (p) {
        is ClientboundBundlePacket -> p.subPackets().any { hiddenPrivate(it) }
        is ClientboundSystemChatPacket -> Rec.privateText(p.content.string)
        is ClientboundPlayerChatPacket -> Rec.privateText(p.body.content)
        else -> false
    }

    /**
     * One received packet's line(s), from [WireTap.tap]: [ph] the protocol phase, [raw] the seq
     * range of its frames in the raw sidecar. A bundle gets a `bundle` line and each packet in it
     * its own line tagged with the bundle's seq and its index.
     */
    internal fun inbound(p: Packet<*>, ph: String, raw: String?) {
        if (!inbound) return
        val rawM = raw?.let { ",\"raw\":$it" } ?: ""
        if (p is ClientboundBundlePacket) {
            val subs = p.subPackets().toList()
            val b = Rec.nextSeq()
            val env = Rec.envelope("bundle", b)
            Rec.emitLine(b, 96, "bundle", System.currentTimeMillis()) { "$env,\"b\":$b,\"n\":${subs.size},\"ph\":\"$ph\"$rawM}" }
            subs.forEachIndexed { i, sub -> inboundOne(sub, ",\"ph\":\"$ph\",\"b\":$b,\"bi\":$i") }
            PacketFate.rememberBundle(p, subs)
            return
        }
        inboundOne(p, ",\"ph\":\"$ph\"$rawM")
    }

    private fun inboundOne(p: Packet<*>, extra: String) {
        // Before any filter: the mirror must see every entity packet to keep its bases right.
        val abs = try { EntityMirror.annotate(p) } catch (t: Throwable) { "\"absErr\":${q(t.toString())}" }
        val type = PacketJson.type(p)
        if (type in SKIP) return
        if ((type in MOVEMENT && !movement) || (type in EFFECTS && !effects)) return
        ChunkCapture.observe(p)
        val body: () -> String = when {
            hiddenPrivate(p) -> ({ "{\"hidden\":\"private\"}" })
            p is ClientboundLevelChunkWithLightPacket -> ChunkCapture.capture(p)
            p is ClientboundChunksBiomesPacket -> ChunkCapture.biomes(p)
            else -> PacketJson.capture(p)
        }
        packetLine("in", type, p, body, extra, abs)
    }

    /**
     * One packet line: the envelope (seq, ticks, clock taken now, on the packet's own thread), its
     * type and the entities it is about, then its fields. [body] comes from [PacketJson.capture]: the
     * packets holding mutable state are already a finished string, the rest are built on the writer
     * thread.
     */
    private fun packetLine(dir: String, type: String, p: Packet<*>, body: () -> String, extra: String, tail: String? = null) {
        val seq = Rec.nextSeq()
        val env = Rec.envelope(dir, seq)
        val e = PacketDecode.entityMembers(p)
        val pt = q(type)
        if (dir == "in") PacketFate.remember(p, seq)
        val x = if (tail == null) "" else ",$tail"
        Rec.emitLine(seq, 512, type, System.currentTimeMillis()) { "$env,\"p\":$pt$extra$e,\"f\":${body()}$x}" }
    }

    // ------------------------------------------------------------------ lifecycle and client state

    internal fun wanted(): Boolean = when (where) {
        0 -> DungeonUtils.inDungeons
        1 -> DungeonUtils.inDungeons || LocationUtils.isCurrentArea(com.odtheking.odin.utils.skyblock.Island.DungeonHub)
        else -> EngineerClient.mc.level != null
    }

    /** Odin knows where we are, and it is not a place to record. */
    internal fun knownUnwanted(): Boolean = where != 2 && LocationUtils.currentArea != com.odtheking.odin.utils.skyblock.Island.Unknown && !wanted()

    private fun onTick() {
        Rec.tick++
        pushConfig()
        Rec.onTickEnd()
        // Odin knows the area a second or two after the world loads; RecorderLifecycle confirms or gives up.
        RecorderLifecycle.onTick()
        val s = Rec.session ?: return
        if (s !== lastSession) { lastSession = s; lastState = ""; lastDungeon = ""; lastSidebar = "" }
        if (state && Rec.active) clientState()
    }

    internal fun label() = DungeonUtils.floor?.name ?: LocationUtils.currentArea.name

    /** Hands the core the settings it acts on, and how to read all of them for `settings` lines. */
    internal fun pushConfig() {
        PacketJson.cookiePayloads = cookiePayloads
        ChunkCapture.enabled = chunks
        val c = RecConfig(hidePrivate, typedChat, compactEntities, minFreeGb, maxFolderGb, deleteOldest)
        if (c != Rec.config) Rec.config = c
        if (Rec.settingsSource == null) Rec.settingsSource = { settingsSnapshot() }
    }

    private fun settingsSnapshot(): Map<String, String> =
        settings.entries.filter { it.value !is ActionSetting }.associate { (k, v) -> k to runCatching { v.value.toString() }.getOrDefault("?") }

    internal fun settingsJson() = settingsSnapshot().entries.joinToString(",", "{", "}") { "${q(it.key)}:${q(it.value)}" }

    /** Which packet groups are left out of the lines (the raw sidecar keeps them all). */
    internal fun filtersJson(): String {
        val off = ArrayList<String>()
        if (!inbound) off += "in"
        if (!outbound) off += "out"
        if (!movement) off += MOVEMENT
        if (!effects) off += EFFECTS
        return "{\"skip\":${SKIP.joinToString(",", "[", "]") { q(it) }},\"off\":${off.joinToString(",", "[", "]") { q(it) }}," +
            "\"chunkData\":$chunks,\"raw\":$rawPackets,\"typedChat\":$typedChat,\"hidePrivate\":$hidePrivate}"
    }

    /** Turned on mid-world: record from here (the world's first packets are already gone). */
    override fun onEnable() {
        super.onEnable()
        EngineerClient.safely("recorder on") { RecorderLifecycle.onEnable() }
    }

    override fun onDisable() {
        super.onDisable()
        EngineerClient.safely("recorder off") { RecorderLifecycle.onDisable() }
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
