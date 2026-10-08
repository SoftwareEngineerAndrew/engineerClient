package com.devgineerclient.recorder

import com.devgineerclient.DevgineerClient
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
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import com.mojang.blaze3d.platform.InputConstants

/**
 * Dungeon Recorder: everything that happens in a dungeon, losslessly, for building mods with an LLM.
 * Where Better PF records a replay and Boss Recorder the boss fights, this keeps the lot:
 *
 *  - every packet both ways, field by field ([PacketJson]), with every frame's exact bytes and the
 *    connection's lifecycle ([WireTap], [RecorderLifecycle]) and what became of each packet ([PacketFate]);
 *  - the world as the client held it ([ChunkCapture], [WorldCapture]) and every entity every tick
 *    ([EntityMirror], [EntityCapture]);
 *  - the player: input and what it came to ([InputCapture]), the full player state, the camera and the clock
 *    ([PlayerState], [FrameCapture], [EnvOptions]), screens, chat and HUD as drawn ([ScreenCapture],
 *    [HudCapture]), sounds and particles ([EffectsCapture]), opt-in thumbnails ([ThumbCapture]);
 *  - what the mods made of it: Odin's state, events and internals ([OdinState], [OdinEvents],
 *    [OdinInternals]).
 *
 * This object holds the settings and the packet path; each capture unit installs its own hooks and
 * does nothing while no recording is open. Files: <game dir>/devgineerclient-recordings/, one
 * directory per recording: gzipped JSON Lines parts with an index, a raw packet sidecar and a
 * manifest ([RecorderSession], [Rec]).
 */
object DungeonRecorder : Module(
    name = "Dungeon Recorder",
    key = null,
    category = Category.custom("Devgineer Client", 860, 10),
    description = "Records everything in a dungeon, losslessly - every packet both ways, the world, every entity, your input and state, screens and HUD, Odin's state - as context for building mods. Saved to devgineerclient-recordings/ in the game folder.",
) {
    /** Where to record; the label is what the selector shows. */
    internal enum class Where(private val label: String) {
        DUNGEONS("Dungeons"), DUNGEONS_AND_HUB("Dungeons + Hub"), EVERYWHERE("Everywhere");
        override fun toString() = label
    }

    private val where by SelectorSetting("Where", Where.DUNGEONS, desc = "When to record: in dungeons only, also in the Dungeon Hub (party finder, queueing), or always.")
    private val inbound by BooleanSetting("Server Packets", true, desc = "Every packet the server sends.")
    private val outbound by BooleanSetting("Your Packets", true, desc = "Every packet you send (movement, clicks, container clicks, item use).")
    private val movement by BooleanSetting("Entity Movement", true, desc = "Other entities' movement and head turns (the bulk of the packets).")
    private val effects by BooleanSetting("Particles And Sounds", true, desc = "Particle and sound packets.")
    private val playedSounds by BooleanSetting("Played Sounds", true, desc = "Every sound the game played or tried to (the server's, the client's own and mods'): the file it resolved to, volume, and whether it started; and every stop.")
    private val spawnedParticles by BooleanSetting("Spawned Particles", true, desc = "Every particle requested from the world and every particle that actually spawned (client-made ones included), once per tick.")
    private val chunks by BooleanSetting("Chunk Data", true, desc = "Every block, block entity, biome, heightmap and light of each loaded chunk.")
    private val state by BooleanSetting("Client State", true, desc = "Your own state every tick at full precision, your inventory, effects and cooldowns.")
    private val perFrameCamera by BooleanSetting("Per-Frame Camera", true, desc = "The camera in every rendered frame (partial tick, look, position, FOV), so what was on screen can be rebuilt exactly.")
    private val typedChat by BooleanSetting("Typed Chat", false, desc = "What you type in chat, commands, signs, anvils and books. Off: only that something was sent.")
    private val hidePrivate by BooleanSetting("Hide Private Chats", true, desc = "Leaves private messages, guild, officer and co-op chat and friend notices out, those you send included (even with Typed Chat on).")
    /** Read by [InputCapture]'s hooks. */
    internal val inputOn by BooleanSetting("Input", true, desc = "Every key, mouse button, scroll and look turn, the actions they start, what Odin cancelled, what the crosshair is on and what each interaction returned.")
    internal val cursorMovesOn by BooleanSetting("Cursor Moves", true, desc = "Every cursor move, with its time in the tick (the largest part of the input lines).")
    private val cookiePayloads by BooleanSetting("Cookie Payloads", false, desc = "Include server cookie bytes (may hold session tokens); off writes length and hash only")
    private val minFreeGb by NumberSetting("Min Free Disk GB", 10.0, 0.0..500.0, 1.0, desc = "Stops the recording (saying so in the file) when the disk has less free space than this.")
    private val maxFolderGb by NumberSetting("Max Recordings Folder GB", 0.0, 0.0..2000.0, 10.0, desc = "0 = no limit. Stops the recording when the recordings folder grows past this.")
    private val deleteOldest by BooleanSetting("Delete Oldest When Full", false, desc = "At the folder limit, deletes the oldest finished recordings instead of stopping. Never the one being written.")
    internal val entityTicks by BooleanSetting("Entity Ticks", true, desc = "Every entity's position, rotation, motion and health each tick it changes, and each move the client applies.")
    internal val renderedEntities by BooleanSetting("Rendered Entities", true, desc = "Which entities were drawn each tick, with their name tags and outlines.")
    private val compactEntities by BooleanSetting("Compact Entity Rows", false, desc = "Writes the per-tick entity rows to a separate xz file per part (smaller, slower to read).")
    private val rawPackets by BooleanSetting("Raw Packets", true, desc = "Also keeps every packet's exact bytes as they crossed the wire, both ways, in a sidecar file (the ground truth behind each line).")
    internal val odinInternals by BooleanSetting("Odin Internals", true, desc = "Odin's private solver/tracker state via reflection (version-fragile, read-only).")
    private val thumbs by BooleanSetting("Frame Thumbnails", false, desc = "Small JPEGs of the screen as you saw it (what other mods draw: HUDs, waypoints, custom GUIs). They show private chat too and cannot be redacted; none are taken while you type (unless Typed Chat is on). Adds 100-400 MB an hour and a little frame time.")
    private val thumbFps by NumberSetting("Thumbnail FPS", 1.0, 0.5..4.0, 0.5, desc = "Frame thumbnails a second (plus one on each screen open and title).")
    private val bookmark by KeybindSetting("Bookmark", InputConstants.UNKNOWN, "Marks this moment in the recording (also /dcrec mark [note]).").onPress { DevgineerClient.safely("recorder bookmark") { Rec.mark(null) } }
    private val openFolder by ActionSetting("Open Folder", desc = "Opens the folder the recordings are saved in.") {
        DevgineerClient.safely("recorder folder") { java.nio.file.Files.createDirectories(dir); com.mojang.blaze3d.Blaze3D.openPath(dir) }
    }

    /** The recordings folder's name, under the game directory. */
    private const val FOLDER = "devgineerclient-recordings"
    internal val dir get() = DevgineerClient.mc.gameDirectory.toPath().resolve(FOLDER)

    private val MOVEMENT = setOf("minecraft:move_entity_pos", "minecraft:move_entity_pos_rot", "minecraft:move_entity_rot", "minecraft:rotate_head",
        "minecraft:set_entity_motion", "minecraft:entity_position_sync", "minecraft:teleport_entity")
    private val EFFECTS = setOf("minecraft:level_particles", "minecraft:sound", "minecraft:sound_entity")

    init {
        // Each world gets its own recording, opened at its login packet by the wire tap
        // (RecorderLifecycle); joining a world without one (a missed login) opens it here.
        on<LevelEvent.Load> { DevgineerClient.safely("recorder world") { RecorderLifecycle.onJoin() } }
        // Fabric's event rather than Odin's LevelEvent.Unload: it says which connection left, so the
        // world before's late disconnect never ends the next world's recording.
        ClientPlayConnectionEvents.DISCONNECT.register { handler, _ -> DevgineerClient.safely("recorder world end") { RecorderLifecycle.onDisconnect(handler) } }
        on<TickEvent.End> { DevgineerClient.safely("recorder tick") { onTick() } }
        // Every capture unit registers its own hooks once. Each is guarded on its own: one that fails
        // to install (an Odin or Minecraft change) leaves the rest, and the game, running.
        DevgineerClient.safely("recorder world capture") { WorldCapture.install() }
        DevgineerClient.safely("recorder entities") { EntityCapture.install() }
        DevgineerClient.safely("recorder packet fate") { PacketFate.install() }
        DevgineerClient.safely("recorder input") { InputCapture.install() }
        DevgineerClient.safely("recorder player") { PlayerState.install() }
        DevgineerClient.safely("recorder frames") { FrameCapture.install() }
        DevgineerClient.safely("recorder env") { EnvOptions.install() }
        // Screens, chat as shown, HUD, tab list, scoreboards and boss bars.
        DevgineerClient.safely("recorder screens") { ScreenCapture.install() }
        DevgineerClient.safely("recorder hud") { HudCapture.install() }
        DevgineerClient.safely("recorder effects") { EffectsCapture.install() }
        // Odin's dungeon state, its event stream and its private solver/tracker state.
        DevgineerClient.safely("recorder odin state") { OdinState.install() }
        DevgineerClient.safely("recorder odin events") { OdinEvents.install() }
        DevgineerClient.safely("recorder odin internals") { OdinInternals.install() }
        DevgineerClient.safely("recorder thumbs") { ThumbCapture.install() }

        // A recording the game did not get to close (a crash) is cut back to its last whole member
        // and renamed; off the game thread, it only touches files.
        Thread({ DevgineerClient.safely("recorder recovery") {
            RecorderFiles.recover(net.fabricmc.loader.api.FabricLoader.getInstance().gameDir.resolve(FOLDER))
                .forEach { DevgineerClient.logger.info("[dc] recorder: $it") }
        } }, "dc-recorder-recover").start()
        // End the world first (an `end` line; a recording never confirmed is deleted, not kept), then
        // wait for the files of every session still writing.
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            DevgineerClient.safely("recorder exit") { RecorderLifecycle.onExit() }
            RecorderSession.shutdownAll(3000)
        }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("dcrec").then(literal("mark")
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
            DevgineerClient.safely("recorder out") {
                // Odin's hook is on every Connection: the integrated server's sends are clientbound.
                if (runCatching { p.type().flow() }.getOrNull() == PacketFlow.CLIENTBOUND) return@safely
                val type = PacketJson.type(p)
                packetLine("out", type, p, outBody(p), ",\"ph\":\"${WireTap.phase()}\",\"cancelled\":${ev.isCancelled}")
            }
        }
    }

    /**
     * An outbound packet's "f": in full, or with what you typed left out when Typed Chat is off (or
     * it is a private message and Hide Private Chats is on): see [WireTap.typedRedaction].
     */
    internal fun outBody(p: Packet<*>): () -> String {
        val r = WireTap.typedRedaction(p) ?: return PacketJson.capture(p)
        return { r }
    }

    private fun bookmarkCommand(source: net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource, note: String?) {
        if (!Rec.active) { source.sendFeedback(Component.literal("${DevgineerClient.PREFIX}§7Dungeon Recorder is not recording.")); return }
        DevgineerClient.safely("recorder bookmark") { Rec.mark(note) }
        source.sendFeedback(Component.literal("${DevgineerClient.PREFIX}§7recording marked" + (note?.let { ": §f$it" } ?: ".")))
    }

    /**
     * Every packet any connection receives, on its network thread, ahead of any mod that could cancel
     * it (ConnectionTapMixin). [WireTap] keeps the game connection's and calls back into [inbound].
     */
    fun tap(conn: Connection, packet: Packet<*>) {
        if (!enabled) { WireTap.clearPending(); return }
        DevgineerClient.safely("recorder tap") { WireTap.tap(conn, packet); PacketFate.readBegin(packet) }
    }

    /** Whether frames' bytes are kept (Raw Packets); read on the network thread. */
    internal fun rawOn(): Boolean = enabled && rawPackets

    /** A private chat line Hide Private Chats leaves out (in a bundle: any of its packets). */
    internal fun hiddenPrivate(p: Packet<*>): Boolean = when (p) {
        is ClientboundBundlePacket -> p.subPackets().any { hiddenPrivate(it) }
        is ClientboundSystemChatPacket -> Rec.privateText(p.content.string)
        // A signed whisper's body is only the message: its chat type says it is private.
        is ClientboundPlayerChatPacket -> Rec.privateType(p.chatType()) || Rec.privateText(p.body.content)
        is ClientboundDisguisedChatPacket -> Rec.privateType(p.chatType()) || Rec.privateText(p.message().string)
        else -> false
    }

    /**
     * One received packet's line(s), from [WireTap.tap]: [ph] the protocol phase, [raw] the seq
     * range of its frames in the raw sidecar. A bundle gets a `bundle` line and each packet in it
     * its own line tagged with the bundle's seq and its index.
     */
    internal fun inbound(p: Packet<*>, ph: String, raw: String?, rawLen: Int = 0) {
        if (!inbound) return
        val rawM = raw?.let { ",\"raw\":$it" } ?: ""
        if (p is ClientboundBundlePacket) {
            val subs = p.subPackets().toList()
            val b = Rec.nextSeq()
            val env = Rec.envelope("bundle", b)
            Rec.emitLine(b, 96, "bundle", System.currentTimeMillis()) { "$env,\"b\":$b,\"count\":${subs.size},\"ph\":\"$ph\"$rawM}" }
            val each = if (subs.isEmpty()) 0 else rawLen / subs.size
            subs.forEachIndexed { i, sub -> inboundOne(sub, ",\"ph\":\"$ph\",\"b\":$b,\"bi\":$i", each) }
            PacketFate.rememberBundle(p, subs)
            return
        }
        inboundOne(p, ",\"ph\":\"$ph\"$rawM", rawLen)
    }

    private fun inboundOne(p: Packet<*>, extra: String, rawLen: Int) {
        // Before any filter: the mirror must see every entity packet to keep its bases right.
        val abs = try { EntityMirror.annotate(p) } catch (t: Throwable) { "\"absErr\":${q(t.toString())}" }
        val type = PacketJson.type(p)
        if ((type in MOVEMENT && !movement) || (type in EFFECTS && !effects)) return
        ChunkCapture.observe(p)
        val body: () -> String = when {
            hiddenPrivate(p) -> ({ "{\"hidden\":\"private\"}" })
            p is ClientboundLevelChunkWithLightPacket -> ChunkCapture.capture(p)
            p is ClientboundChunksBiomesPacket -> ChunkCapture.biomes(p)
            else -> PacketJson.capture(p)
        }
        packetLine("in", type, p, body, extra, abs, rawLen)
    }

    /**
     * One packet line: the envelope (seq, ticks, clock taken now, on the packet's own thread), its
     * type and the entities it is about, then its fields. [body] comes from [PacketJson.capture]: the
     * packets holding mutable state are already a finished string, the rest are built on the writer
     * thread. The queue's memory cap counts what the builder holds: its own size when it knows it
     * ([Sized]: frozen strings, chunks), else about three times the packet's frame ([rawLen]).
     */
    private fun packetLine(dir: String, type: String, p: Packet<*>, body: () -> String, extra: String, tail: String? = null, rawLen: Int = 0) {
        val seq = Rec.nextSeq()
        val env = Rec.envelope(dir, seq)
        val e = PacketDecode.entityMembers(p)
        val pt = q(type)
        if (dir == "in") PacketFate.remember(p, seq)
        val x = if (tail == null) "" else ",$tail"
        val est = maxOf(512, (body as? Sized)?.est ?: 0, 3 * rawLen) + extra.length + (tail?.length ?: 0)
        Rec.emitLine(seq, est, type, System.currentTimeMillis()) { "$env,\"p\":$pt$extra$e,\"f\":${body()}$x}" }
    }

    // ------------------------------------------------------------------ lifecycle and client state

    internal fun wanted(): Boolean = when (where) {
        Where.DUNGEONS -> DungeonUtils.inDungeons
        Where.DUNGEONS_AND_HUB -> (DungeonUtils.inDungeons) || LocationUtils.isCurrentArea(com.odtheking.odin.utils.skyblock.Island.DungeonHub)
        Where.EVERYWHERE -> DevgineerClient.mc.level != null
    }

    /** Odin knows where we are, and it is not a place to record. */
    internal fun knownUnwanted(): Boolean = where != Where.EVERYWHERE && LocationUtils.currentArea != com.odtheking.odin.utils.skyblock.Island.Unknown && !wanted()

    private fun onTick() {
        Rec.tick++
        pushConfig()
        Rec.onTickEnd()
        // Odin knows the area a second or two after the world loads; RecorderLifecycle confirms or gives up.
        RecorderLifecycle.onTick()
        PlayerState.tick()
    }

    internal fun label() = DungeonUtils.floor?.name ?: LocationUtils.currentArea.name

    /** Hands the core the settings it acts on, and how to read all of them for `settings` lines. */
    internal fun pushConfig() {
        PacketJson.cookiePayloads = cookiePayloads
        ChunkCapture.enabled = chunks
        PlayerState.enabled = state
        FrameCapture.perFrame = perFrameCamera
        EffectsCapture.sounds = playedSounds
        EffectsCapture.particles = spawnedParticles
        ThumbCapture.on = thumbs
        ThumbCapture.fps = thumbFps
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
        // "skip" (per-type filter) stays in the format for readers but is always empty; groups go in "off".
        // "skip" stays in the format (always empty: no packet type is ever left out of the lines).
        return "{\"skip\":[],\"off\":${off.joinToString(",", "[", "]") { q(it) }}," +
            "\"chunkData\":$chunks,\"raw\":$rawPackets,\"typedChat\":$typedChat,\"hidePrivate\":$hidePrivate}"
    }

    /** Turned on mid-world: record from here (the world's first packets are already gone). */
    override fun onEnable() {
        super.onEnable()
        DevgineerClient.safely("recorder on") { RecorderLifecycle.onEnable() }
    }

    override fun onDisable() {
        super.onDisable()
        DevgineerClient.safely("recorder off") { RecorderLifecycle.onDisable() }
    }

    private fun q(s: String) = com.google.gson.JsonPrimitive(s).toString()
}
