package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelHandlerContext
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.DisconnectionDetails
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket
import net.minecraft.network.protocol.game.ServerboundEditBookPacket
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.network.protocol.login.ClientboundHelloPacket
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket
import net.minecraft.network.protocol.login.ServerboundKeyPacket

/**
 * The game connection as the wire sees it: which connection is the game's, what protocol phase it
 * is in, the raw bytes of every frame both ways, and the session lifecycle that follows from it.
 *
 * Everything here runs on the connection's netty thread (the decoder, encoder and Connection's own
 * channelRead0 all fire on it, one after another), so it only copies bytes and pushes onto queues.
 *
 * Which connection: the first one to reach LOGIN is the game's, until another one does. The
 * server-list pinger never leaves STATUS, and the integrated server's own channels are told apart
 * by flow (it decodes serverbound and encodes clientbound packets), so neither is recorded.
 *
 * Between worlds (a new connection's login and configuration, or a reconfiguration) no session
 * exists yet, so lines and frames go to [cache] and are replayed into the session the next
 * ClientboundLoginPacket opens: the registries, tags, resource packs and known packs the world is
 * built from are part of the recording.
 *
 * Raw frames: the decoder hook ([decodeFrame]) copies each inbound frame (after decryption and
 * decompression, so it is `[packet id varint][payload]`) into a per-thread list that the next
 * [tap] drains; a bundle's delimiters and sub-packets all arrive before the one channelRead0 that
 * carries the bundle, so they land on the bundle's line. The encoder hook ([encodedFrame]) copies
 * each outbound frame as it is written and says so in a `wire_out` line: proof the packet left,
 * where the `out` line (from Odin's send event) is only what was attempted.
 */
object WireTap {

    /** The raw sidecar's phase byte. Fixed here rather than enum ordinals, so ids never move with the game. */
    fun phaseId(p: ConnectionProtocol): Int = when (p) {
        ConnectionProtocol.HANDSHAKING -> 0
        ConnectionProtocol.STATUS -> 1
        ConnectionProtocol.LOGIN -> 2
        ConnectionProtocol.CONFIGURATION -> 3
        ConnectionProtocol.PLAY -> 4
    }

    fun phaseName(p: ConnectionProtocol): String = p.name.lowercase()

    /** The game's connection, learned at its first LOGIN (or CONFIGURATION, or from the client when recording starts mid-world). */
    @Volatile var gameConn: Connection? = null
        private set

    /** After a login or reconfiguration started and before the world's login packet: lines go to [cache]. */
    @Volatile private var betweenWorlds = false

    /** The server kicked us (a disconnect packet arrived): the next disconnect is the server's. */
    @Volatile private var kicked = false
    /** An exception closed the connection: the next disconnect is an error's. */
    @Volatile private var errored = false
    /** One disconnect line per connection (Connection.disconnect may run more than once). */
    @Volatile private var disconnectWritten: Connection? = null

    /** Lines and frames held while no session exists, replayed into the next one (64 MB, oldest first out). */
    val cache = ConfigCache(64L shl 20)

    private class Frame(val seq: Long, val phase: Int, val bytes: ByteArray)
    private val pending = ThreadLocal.withInitial { ArrayList<Frame>(4) }

    /** The handshake frame goes out before the connection is known to be the game's; held until it is. */
    private class Handshake(val conn: Connection, val seq: Long, val ms: Long, val bytes: ByteArray)
    @Volatile private var handshake: Handshake? = null

    /** The decoder and encoder only see a ChannelHandlerContext; the Connection is a handler in the same pipeline. */
    private class CtxConn(val ctx: ChannelHandlerContext, val conn: Connection?)
    @Volatile private var lastCtx: CtxConn? = null

    private fun connOf(ctx: ChannelHandlerContext): Connection? {
        lastCtx?.let { if (it.ctx === ctx) return it.conn }
        val c = runCatching { ctx.pipeline().get(Connection::class.java) }.getOrNull()
        lastCtx = CtxConn(ctx, c)
        return c
    }

    /** The phase the game connection is in now ("play" when unknown), for `out` lines. */
    fun phase(): String = gameConn?.packetListener?.protocol()?.let(::phaseName) ?: "play"

    // ------------------------------------------------------------------ which connection

    /**
     * Learns the game connection. A connection entering LOGIN is a new game connection: whatever was
     * being recorded ends, and the cache starts over for the new connection's login.
     */
    private fun adopt(conn: Connection, proto: ConnectionProtocol) {
        if (conn === gameConn) return
        if (proto == ConnectionProtocol.LOGIN) {
            gameConn = conn
            kicked = false; errored = false
            Rec.chatChannel = "all"
            RecorderLifecycle.endWorld("reconnect")
            cache.clear()
            betweenWorlds = true
            handshake?.takeIf { it.conn === conn }?.let { h ->
                cache.add(ConfigCache.Raw(h.seq, h.ms, 1, phaseId(ConnectionProtocol.HANDSHAKING), h.bytes, false, h.bytes.size))
            }
            handshake = null
        } else if (proto == ConnectionProtocol.CONFIGURATION && gameConn == null) {
            gameConn = conn
            betweenWorlds = Rec.session == null
        }
    }

    /** The client's current game connection, adopted when recording starts on the game thread (enabled mid-world). */
    fun adoptCurrent() {
        val c = EngineerClient.mc.connection?.connection ?: return
        if (gameConn !== c) { gameConn = c; kicked = false; errored = false }
    }

    /** Frames on [conn] are worth copying: it is the game's, and a session or the cache will take them. */
    private fun recording(conn: Connection?): Boolean = conn != null && conn === gameConn && (Rec.active || betweenWorlds)

    // ------------------------------------------------------------------ inbound

    /** PacketDecoderTapMixin, at decode HEAD: copies the frame about to be decoded. Never keeps the buffer. */
    fun decodeFrame(ctx: ChannelHandlerContext, info: ProtocolInfo<*>, buf: ByteBuf) {
        if (!DungeonRecorder.rawOn()) return
        if (info.flow() != PacketFlow.CLIENTBOUND) return
        val proto = info.id()
        if (proto == ConnectionProtocol.STATUS || proto == ConnectionProtocol.HANDSHAKING) return
        val conn = connOf(ctx) ?: return
        adopt(conn, proto)
        if (!recording(conn)) return
        pending.get().add(Frame(Rec.nextSeq(), phaseId(proto), ByteBufUtil.getBytes(buf, buf.readerIndex(), buf.readableBytes())))
    }

    /** Drops frames held for a channelRead0 that will not be recorded (module off). Cheap. */
    fun clearPending() {
        val l = pending.get()
        if (l.isNotEmpty()) l.clear()
    }

    /**
     * Every packet the game connection receives, from ConnectionTapMixin on the netty thread, ahead of
     * every mod that could cancel it: the lifecycle it implies, then its raw frames and its line.
     */
    fun tap(conn: Connection, packet: Packet<*>) {
        val held = pending.get()
        val frames: List<Frame> = if (held.isEmpty()) emptyList() else ArrayList(held).also { held.clear() }
        if (conn.receiving != PacketFlow.CLIENTBOUND) return
        val proto = conn.packetListener?.protocol() ?: return
        if (proto == ConnectionProtocol.STATUS) return
        adopt(conn, proto)
        if (conn !== gameConn) return
        val ph = phaseName(proto)

        if (proto == ConnectionProtocol.PLAY && packet is ClientboundPingPacket && packet.id != 0) Rec.serverTicks++
        if (packet is ClientboundDisconnectPacket || packet is ClientboundLoginDisconnectPacket) kicked = true

        when (packet) {
            is ClientboundStartConfigurationPacket -> {
                RecorderLifecycle.endWorld("reconfigure")
                cache.clear()
                betweenWorlds = true
            }
            is ClientboundLoginPacket -> {
                PacketDecode.selfId = packet.playerId()
                RecorderLifecycle.beginWorld("login", conn.packetListener, packet.playerId())
                betweenWorlds = false
                if (Rec.active) world("login", packet.playerId(), packet.commonPlayerSpawnInfo(), packet.chunkRadius(), packet.simulationDistance(),
                    packet.hardcore(), packet.enforcesSecureChat(), null)
            }
            is ClientboundRespawnPacket -> {
                // A server switch (or an end-portal trip): the same recording carries on; one that
                // was dropped (an unwanted lobby) starts again here, so the new world's packets are kept.
                RecorderLifecycle.onRespawn()
                if (Rec.session == null) RecorderLifecycle.beginWorld("respawn", conn.packetListener, PacketDecode.selfId)
                if (Rec.active) {
                    world("respawn", PacketDecode.selfId, packet.commonPlayerSpawnInfo(), null, null, null, null, packet.dataToKeep())
                    Rec.requestKeyframe("respawn")
                }
            }
            else -> {}
        }

        val active = Rec.active
        if (!active && !betweenWorlds) return
        val hidden = DungeonRecorder.hiddenPrivate(packet)
        val withheld = when {
            packet is ClientboundHelloPacket -> "crypto"
            packet is ClientboundStoreCookiePacket && !PacketJson.cookiePayloads -> "cookie"
            hidden -> "private"
            else -> null
        }
        for (f in frames) {
            if (active) Rec.raw(f.seq, 0, f.phase, f.bytes, withheld)
            else cache.add(ConfigCache.Raw(f.seq, System.currentTimeMillis(), 0, f.phase, if (withheld != null) null else f.bytes, withheld != null, f.bytes.size))
        }
        val raw = if (frames.isEmpty()) null else "[${frames.first().seq},${frames.last().seq}]"
        if (active) DungeonRecorder.inbound(packet, ph, raw, frames.sumOf { it.bytes.size })
        else {
            val type = PacketJson.type(packet)
            val f = if (hidden) "{\"hidden\":\"private\"}" else PacketJson.capture(packet)()
            cacheLine("config", type, "\"dir\":\"in\",\"ph\":\"$ph\",\"p\":${RecorderFiles.q(type)}" + (raw?.let { ",\"raw\":$it" } ?: "") + ",\"f\":$f")
        }
    }

    /**
     * `world`: the dimension the server put us in, from the login or respawn packet alone (the client
     * has not built the level yet, so nothing is read from it).
     */
    private fun world(via: String, selfId: Int, spawn: CommonPlayerSpawnInfo, chunkRadius: Int?, simDist: Int?,
                      hardcore: Boolean?, secureChat: Boolean?, dataToKeep: Byte?) {
        val sb = StringBuilder(256)
        sb.append("\"via\":").append(RecorderFiles.q(via)).append(",\"selfId\":").append(selfId)
        sb.append(",\"dimension\":").append(RecorderFiles.q(runCatching { spawn.dimension().identifier().toString() }.getOrNull()))
        sb.append(",\"dimType\":").append(RecorderFiles.q(runCatching { spawn.dimensionType().unwrapKey().map { it.identifier().toString() }.orElse(null) }.getOrNull()))
        runCatching { spawn.dimensionType().value() }.getOrNull()?.let { type ->
            sb.append(",\"minY\":").append(type.minY()).append(",\"height\":").append(type.height())
                .append(",\"logicalHeight\":").append(type.logicalHeight())
                .append(",\"sky\":").append(type.hasSkyLight()).append(",\"ceiling\":").append(type.hasCeiling())
        }
        sb.append(",\"seaLevel\":").append(spawn.seaLevel())
        sb.append(",\"gameType\":").append(RecorderFiles.q(spawn.gameType().getName()))
        sb.append(",\"previousGameType\":").append(RecorderFiles.q(spawn.previousGameType()?.getName()))
        sb.append(",\"debug\":").append(spawn.isDebug()).append(",\"flat\":").append(spawn.isFlat())
        chunkRadius?.let { sb.append(",\"chunkRadius\":").append(it) }
        simDist?.let { sb.append(",\"simDistance\":").append(it) }
        hardcore?.let { sb.append(",\"hardcore\":").append(it) }
        secureChat?.let { sb.append(",\"enforcesSecureChat\":").append(it) }
        dataToKeep?.let { sb.append(",\"dataToKeep\":").append(it.toInt()) }
        Rec.emit("world", sb.toString())
    }

    // ------------------------------------------------------------------ outbound

    /** PacketEncoderTapMixin, at encode HEAD: whether to remember where this frame starts. */
    fun wantOut(): Boolean = DungeonRecorder.enabled

    /**
     * PacketEncoderTapMixin, at encode RETURN: [out] holds the frame from [start] (packet id and
     * payload, before compression and encryption).
     */
    fun encodedFrame(ctx: ChannelHandlerContext, info: ProtocolInfo<*>, packet: Packet<*>, out: ByteBuf, start: Int) {
        if (!DungeonRecorder.enabled) return
        if (info.flow() != PacketFlow.SERVERBOUND) return
        val proto = info.id()
        if (proto == ConnectionProtocol.STATUS) return
        val conn = connOf(ctx) ?: return
        val len = out.writerIndex() - start
        if (len < 0) return
        val rawOn = DungeonRecorder.rawOn()
        if (proto == ConnectionProtocol.HANDSHAKING) {
            // Every connection says hello first, the pinger's too; kept until one turns out to be the game's.
            if (rawOn) handshake = Handshake(conn, Rec.nextSeq(), System.currentTimeMillis(), ByteBufUtil.getBytes(out, start, len))
            return
        }
        adopt(conn, proto)
        if (!recording(conn)) return
        val seq = Rec.nextSeq()
        val type = PacketJson.type(packet)
        when (packet) {
            is ServerboundChatCommandPacket -> Rec.noteCommand(packet.command())
            is ServerboundChatCommandSignedPacket -> Rec.noteCommand(packet.command())
        }
        val withheld = when {
            packet is ServerboundKeyPacket -> "crypto"
            packet is ServerboundCookieResponsePacket && !PacketJson.cookiePayloads -> "cookie"
            else -> typedReason(packet)
        }
        // A withheld frame is still copied so the session can write its length; it drops the bytes.
        val bytes = if (rawOn) ByteBufUtil.getBytes(out, start, len) else null
        val ph = phaseName(proto)
        if (Rec.active) {
            if (bytes != null) Rec.raw(seq, 1, phaseId(proto), bytes, withheld)
            Rec.emit("wire_out", "\"p\":${RecorderFiles.q(type)},\"ph\":\"$ph\",\"len\":$len" + (if (bytes != null) ",\"rawSeq\":$seq" else ""))
        } else {
            if (bytes != null) cache.add(ConfigCache.Raw(seq, System.currentTimeMillis(), 1, phaseId(proto), if (withheld != null) null else bytes, withheld != null, len))
            // Odin's send event only covers play: configuration replies are written from here.
            val f = DungeonRecorder.outBody(packet)()
            cacheLine("config", type, "\"dir\":\"out\",\"ph\":\"$ph\",\"p\":${RecorderFiles.q(type)},\"len\":$len" +
                (if (bytes != null) ",\"raw\":[$seq,$seq]" else "") + ",\"f\":$f")
        }
    }

    // ------------------------------------------------------------------ disconnects

    /** ConnectionEndTapMixin, at Connection.disconnect HEAD (every disconnect path ends there). */
    fun disconnected(conn: Connection, details: DisconnectionDetails) {
        if (!DungeonRecorder.enabled || conn !== gameConn || disconnectWritten === conn) return
        disconnectWritten = conn
        val reason = details.reason()
        val endOfStream = runCatching { (reason.contents as? TranslatableContents)?.key == "disconnect.endOfStream" }.getOrDefault(false)
        val by = when { errored -> "error"; kicked || endOfStream -> "server"; else -> "client" }
        val sb = StringBuilder(256)
        sb.append("\"by\":\"").append(by).append("\",\"reason\":")
        try { RichJson.component(sb, reason) } catch (t: Throwable) { PacketJson.error(sb, t) }
        sb.append(",\"report\":").append(RecorderFiles.q(details.report().map { it.toString() }.orElse(null)))
        sb.append(",\"bugReport\":").append(RecorderFiles.q(details.bugReportLink().map { it.toString() }.orElse(null)))
        line("disconnect", sb.toString())
    }

    /** ConnectionEndTapMixin, at Connection.exceptionCaught HEAD: what broke the connection (it disconnects next). */
    fun connectionError(conn: Connection, error: Throwable) {
        if (!DungeonRecorder.enabled || conn !== gameConn) return
        errored = true
        clearPending()
        line("conn_error", "\"error\":${RecorderFiles.q(error.toString())},\"stack\":${RecorderFiles.q(error.stackTraceToString())}")
    }

    // ------------------------------------------------------------------ lines

    /** A line into the session, or into the cache between worlds (else nowhere: nothing is being recorded). */
    private fun line(kind: String, body: String) {
        if (Rec.active) Rec.emit(kind, body)
        else if (betweenWorlds) cacheLine(kind, kind, body)
    }

    private fun cacheLine(kind: String, type: String, body: String) {
        cache.add(ConfigCache.Line(Rec.nextSeq(), Rec.tick, Rec.serverTicks, System.currentTimeMillis(), System.nanoTime(), kind, type, body))
    }

    /** Called when a session opens: everything held since the login started goes in first, with its original seq and time. */
    fun replay(s: RecorderSession) {
        val taken = cache.take()
        for (e in taken.entries) when (e) {
            is ConfigCache.Line -> s.line(e.seq, e.kind, e.ms,
                RecorderFiles.envelope(e.kind, e.seq, e.t, e.n, e.ms, e.nanoTime - s.startNs) + (if (e.body.isEmpty()) "}" else ",${e.body}}"), t = e.t, n = e.n)
            is ConfigCache.Raw -> s.raw(e.seq, e.dir, e.phase, e.data, e.withheld, e.len)
        }
        taken.dropped?.let { d -> Rec.emit("gap", d.json()) }
    }

    /** Every serverbound packet that carries text you typed (unit-tested against every String-holding packet). */
    val TYPED_TEXT_PACKETS: Set<Class<*>> = setOf(
        ServerboundChatPacket::class.java, ServerboundChatCommandPacket::class.java, ServerboundChatCommandSignedPacket::class.java,
        ServerboundCommandSuggestionPacket::class.java, ServerboundSignUpdatePacket::class.java, ServerboundRenameItemPacket::class.java,
        ServerboundEditBookPacket::class.java,
        // Creative-mode text fields: typed too.
        net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket::class.java,
        net.minecraft.network.protocol.game.ServerboundSetCommandMinecartPacket::class.java,
        net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket::class.java,
        net.minecraft.network.protocol.game.ServerboundSetJigsawBlockPacket::class.java,
        net.minecraft.network.protocol.game.ServerboundSetTestBlockPacket::class.java,
    )

    /**
     * Why [p]'s text is left out, or null when it carries none or may be written: "typed_chat"
     * (Typed Chat off) or "private" (a private message, Hide Private Chats on). The packet line, the
     * raw frame and the config cache all follow it.
     */
    fun typedReason(p: Packet<*>): String? {
        if (p.javaClass !in TYPED_TEXT_PACKETS) return null
        if (!Rec.typedChat) return "typed_chat"
        val private = when (p) {
            is ServerboundChatCommandPacket -> Rec.privateOutbound(p.command(), true)
            is ServerboundChatCommandSignedPacket -> Rec.privateOutbound(p.command(), true)
            is ServerboundCommandSuggestionPacket -> Rec.privateOutbound(p.command, true)
            is ServerboundChatPacket -> Rec.privateOutbound(p.message(), false)
            else -> false
        }
        return if (private) "private" else null
    }

    /**
     * The "f" of an outbound packet whose text is left out ([typedReason]), or null to write it in
     * full. A command keeps its name (which command was run is not private), a chat message its
     * length and signing data, a sign its line lengths, an anvil name its length, a book its page count.
     */
    fun typedRedaction(p: Packet<*>): String? {
        val why = typedReason(p) ?: return null
        val sb = StringBuilder(128)
        when (p) {
            is ServerboundChatCommandPacket -> return redactedCommand(p.command())
            is ServerboundChatCommandSignedPacket -> return redactedCommand(p.command())
            is ServerboundCommandSuggestionPacket -> {
                val c = redactedCommand(p.command.removePrefix("/"))
                sb.append("{\"id\":").append(p.id).append(",\"len\":").append(p.command.length).append(',').append(c, 1, c.length)
                return sb.toString()
            }
            is ServerboundChatPacket -> sb.append("{\"redacted\":true,\"len\":").append(p.message().length)
                .append(",\"timeStamp\":").append(runCatching { p.timeStamp().toEpochMilli() }.getOrDefault(-1L))
                .append(",\"salt\":").append(PacketJson.writeNow(p.salt()))
                .append(",\"lastSeen\":").append(PacketJson.writeNow(p.lastSeenMessages()))
            is ServerboundSignUpdatePacket -> sb.append("{\"redacted\":true,\"pos\":").append(PacketJson.writeNow(p.pos))
                .append(",\"isFrontText\":").append(p.isFrontText)
                .append(",\"lens\":").append(p.lines.joinToString(",", "[", "]") { it.length.toString() })
            is ServerboundRenameItemPacket -> sb.append("{\"redacted\":true,\"len\":").append(p.name.length)
            is ServerboundEditBookPacket -> sb.append("{\"redacted\":true,\"slot\":").append(p.slot()).append(",\"pages\":").append(p.pages().size)
                .append(",\"title\":").append(p.title().isPresent)
            else -> sb.append("{\"redacted\":true")
        }
        sb.append(",\"why\":").append(RecorderFiles.q(why)).append('}')
        return sb.toString()
    }

    /**
     * A command with Typed Chat off: `{"command":root,"args":"<redacted>"}`. Which command was run is
     * not private; what followed it may be. args is null for a command typed without any.
     */
    fun redactedCommand(command: String): String {
        val c = command.trim()
        val root = c.substringBefore(' ')
        val args = if (c.length > root.length) "\"<redacted>\"" else "null"
        return "{\"command\":${RecorderFiles.q(root)},\"args\":$args}"
    }

    /** The module was turned off: nothing held is wanted any more. */
    fun reset() {
        cache.clear()
        handshake = null
    }
}

/**
 * Lines and raw frames held while no session exists (a login or reconfiguration in progress),
 * bounded by an estimate of the bytes held. Past [capBytes] the oldest go first, counted, and the
 * replay says what is missing in a `gap` line. Thread-safe.
 */
class ConfigCache(private val capBytes: Long) {

    sealed class Entry(val seq: Long, val ms: Long, val type: String) { abstract val cost: Long }

    /** A finished line body (members after the envelope); the envelope is rebuilt on replay from these values. */
    class Line(seq: Long, val t: Int, val n: Int, ms: Long, val nanoTime: Long, val kind: String, type: String, val body: String) : Entry(seq, ms, type) {
        override val cost: Long get() = 96L + 2L * body.length
    }

    class Raw(seq: Long, ms: Long, val dir: Int, val phase: Int, val data: ByteArray?, val withheld: Boolean, val len: Int) : Entry(seq, ms, "raw") {
        override val cost: Long get() = 48L + (data?.size ?: 0)
    }

    class Dropped(val seqA: Long, val seqB: Long, val lines: Long, val msA: Long, val msB: Long, val types: Map<String, Long>) {
        fun json(): String = "\"range\":[$seqA,$seqB],\"lines\":$lines,\"msRange\":[$msA,$msB],\"why\":\"config_cache\",\"types\":" +
            types.entries.joinToString(",", "{", "}") { "${RecorderFiles.q(it.key)}:${it.value}" }
    }

    class Taken(val entries: List<Entry>, val dropped: Dropped?)

    private val entries = ArrayDeque<Entry>()
    private var bytes = 0L
    private var dLines = 0L
    private var dSeqA = Long.MAX_VALUE; private var dSeqB = Long.MIN_VALUE
    private var dMsA = Long.MAX_VALUE; private var dMsB = Long.MIN_VALUE
    private val dTypes = LinkedHashMap<String, Long>()

    @Synchronized fun add(e: Entry) {
        entries.addLast(e)
        bytes += e.cost
        while (bytes > capBytes && entries.size > 1) {
            val o = entries.removeFirst()
            bytes -= o.cost
            dLines++
            if (o.seq < dSeqA) dSeqA = o.seq; if (o.seq > dSeqB) dSeqB = o.seq
            if (o.ms < dMsA) dMsA = o.ms; if (o.ms > dMsB) dMsB = o.ms
            dTypes.merge(o.type, 1L, Long::plus)
        }
    }

    @Synchronized fun size(): Int = entries.size
    @Synchronized fun bytes(): Long = bytes

    @Synchronized fun clear() {
        entries.clear(); bytes = 0
        resetDropped()
    }

    /** Everything held, oldest first, and what was dropped; the cache is empty afterwards. */
    @Synchronized fun take(): Taken {
        val d = if (dLines > 0) Dropped(dSeqA, dSeqB, dLines, dMsA, dMsB, LinkedHashMap(dTypes)) else null
        val out = entries.toList()
        clear()
        return Taken(out, d)
    }

    private fun resetDropped() {
        dLines = 0; dSeqA = Long.MAX_VALUE; dSeqB = Long.MIN_VALUE; dMsA = Long.MAX_VALUE; dMsB = Long.MIN_VALUE
        dTypes.clear()
    }
}
