package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.PacketListener

/**
 * When a recording starts and ends. A session opens at the world's login packet, on the network
 * thread, before the packet itself is written ([WireTap.tap]), so the world's very first packets and
 * the login and configuration that came before it ([WireTap.cache]) are all in it. It stays pending
 * (a `.pending-` directory) until Odin says this is a place to record, and is deleted if it never is.
 *
 * It ends when the connection starts over (a reconfiguration or a new login), when the client
 * leaves the world it belongs to, or when the module is turned off. A respawn (Hypixel's server
 * switches) does not end it: one recording follows you from the lobby into the run.
 *
 * Each session remembers the packet listener it belongs to, so the late "disconnected" event of the
 * world before (it runs on the game thread, after the network thread may already have opened the
 * next world's session) never ends the new one.
 */
object RecorderLifecycle {

    private val lock = Any()

    /** The ClientPacketListener the current session belongs to (null: unknown, any disconnect ends it). */
    @Volatile private var owner: PacketListener? = null
    @Volatile private var confirmed = false
    @Volatile private var startedTick = 0

    /** A minute without Odin recognising a wanted place is not a world to keep. */
    private const val GIVE_UP_TICKS = 20 * 60
    /** After a server switch Odin still reports the old area for a moment; don't judge before this. */
    private const val SETTLE_TICKS = 200

    /**
     * Opens a session: a still-open one is ended first, then everything held since the login started
     * is replayed into the new one. [via] is login (network thread), respawn (network thread), join or
     * enable (game thread). Returns the new session, or null when the module is off.
     */
    fun beginWorld(via: String, owner: PacketListener?, selfId: Int): RecorderSession? = synchronized(lock) {
        if (!DungeonRecorder.enabled) return null
        endLocked("replaced")
        DungeonRecorder.pushConfig()
        val onGame = runCatching { EngineerClient.mc.isSameThread }.getOrDefault(false)
        val base = baseMeta(via, selfId)
        val s = RecorderSession(DungeonRecorder.dir, if (onGame) base + "," + gameMeta() else base)
        this.owner = owner
        confirmed = false
        startedTick = Rec.tick
        Rec.begin(s)
        WireTap.replay(s)
        if (!onGame) {
            // Server, settings and filters are read on the game thread; a part opened before they
            // arrive has only the base, so they are also written as a meta2 line.
            EngineerClient.mc.execute {
                EngineerClient.safely("recorder meta") {
                    val more = gameMeta()
                    s.metaBody = "$base,$more"
                    if (Rec.session === s) Rec.emit("meta2", more)
                }
            }
        }
        s
    }

    /** Ends the current session (an `end` line, then close; a never-confirmed one is deleted instead). */
    fun endWorld(why: String) = synchronized(lock) { endLocked(why) }

    private fun endLocked(why: String) {
        val s = Rec.session ?: return
        if (confirmed && s.running) Rec.emit("end", "\"why\":${RecorderFiles.q(why)}")
        if (!Rec.end(s)) return
        owner = null
        if (!confirmed) s.abandon() else s.close()
        confirmed = false
    }

    /** Odin's LevelEvent.Load (play joined, game thread): a world whose login was missed still gets recorded from here. */
    fun onJoin() {
        if (Rec.session != null || !DungeonRecorder.enabled) return
        WireTap.adoptCurrent()
        val listener = EngineerClient.mc.connection
        if (beginWorld("join", listener, PacketDecode.selfId) != null) Rec.requestKeyframe("join")
    }

    /** Fabric's play-disconnect event (game thread): ends the session only if it belongs to [listener]. */
    fun onDisconnect(listener: ClientPacketListener) = synchronized(lock) {
        val o = owner
        if (o == null || o === listener) endLocked("disconnect")
    }

    /** Turned on mid-world: record from here, starting with a full snapshot (the world's first packets are gone). */
    fun onEnable() {
        if (EngineerClient.mc.level == null || Rec.session != null) return
        WireTap.adoptCurrent()
        val self = EngineerClient.mc.player?.id ?: PacketDecode.selfId
        if (beginWorld("enable", EngineerClient.mc.connection, self) != null) Rec.requestKeyframe("enable")
    }

    fun onDisable() {
        endWorld("disabled")
        WireTap.reset()
    }

    /** Game thread, every tick: confirm the pending session once Odin knows the place, or give it up. */
    fun onTick() {
        val s = Rec.session ?: return
        if (confirmed) return
        if (DungeonRecorder.wanted()) {
            synchronized(lock) {
                if (Rec.session !== s) return
                confirmed = true
                s.confirm(DungeonRecorder.label())
            }
            Rec.requestKeyframe("confirm")
            return
        }
        val age = Rec.tick - startedTick
        if (age > GIVE_UP_TICKS || (age > SETTLE_TICKS && DungeonRecorder.knownUnwanted())) {
            synchronized(lock) { if (Rec.session === s && !confirmed) endLocked("unwanted") }
        }
    }

    // ------------------------------------------------------------------ meta

    private fun version(id: String): String =
        FabricLoader.getInstance().getModContainer(id).map { it.metadata.version.friendlyString }.orElse(null) ?: "?"

    /** Meta members safe to read on any thread: versions, the account, the entity id. */
    private fun baseMeta(via: String, selfId: Int): String {
        val user = runCatching { EngineerClient.mc.user }.getOrNull()
        return "\"via\":${RecorderFiles.q(via)},\"mod\":${RecorderFiles.q(version("engineerclient"))},\"odin\":${RecorderFiles.q(version("odin"))}," +
            "\"fabricApi\":${RecorderFiles.q(version("fabric-api"))},\"mc\":${RecorderFiles.q(runCatching { SharedConstants.getCurrentVersion().name() }.getOrDefault("?"))}," +
            "\"self\":${RecorderFiles.q(user?.name)},\"uuid\":${RecorderFiles.q(user?.profileId?.toString())},\"selfId\":$selfId"
    }

    /** Meta members read on the game thread: the server, every setting, and which packet groups are left out. */
    private fun gameMeta(): String =
        "\"server\":${RecorderFiles.q(EngineerClient.mc.currentServer?.ip)},\"settings\":${DungeonRecorder.settingsJson()},\"filters\":${DungeonRecorder.filtersJson()}"
}
