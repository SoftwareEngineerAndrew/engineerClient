package com.engineerclient.bossrecorder

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import java.util.Locale

/**
 * Records the dungeon boss fights (and the Watcher's blood camp) packet by packet, for working out
 * their mechanics: what docs/mechanics/ could not measure from Better PF's recordings. Files go to
 * config/engineerclient/bossrecorder/; the format is docs/boss-recorder.md and
 * tools/boss-mechanics/netlog.py reads them.
 */
object BossRecorder : Module(
    name = "Boss Recorder",
    category = Category.custom("Engineer Client"),
    description = "Records the boss fights packet by packet, each stamped with its server tick: bosses' health and damage, every mob's and player's exact movement, projectiles, blocks and sounds. For working out the bosses' mechanics.",
) {
    val watcherCamp by BooleanSetting("Watcher Camp", true, desc = "Also records the blood camp, from the blood door opening until the Watcher is done.")
    val sounds by BooleanSetting("Sounds", true, desc = "Records the sounds the server plays during the fights (lightning, explosions, abilities).")
    val blocks by BooleanSetting("Blocks", true, desc = "Records block changes during the fights (Storm's pillars, Goldor's doors, Necron's platforms), to the tick.")
    private val hidePrivateChats by BooleanSetting("Hide Private Chats", true, desc = "Leaves private messages, guild, officer and co-op chat, and friends coming online out of recordings. Party chat stays in.")
    val startMessage by BooleanSetting("Recording Message", true, desc = "Says in chat when a boss fight starts being recorded.")
    val savedMessage by BooleanSetting("Saved Message", true, desc = "Says in chat, with the file's size, when a recording is saved.")
    private val openFolder by ActionSetting("Open Folder", desc = "Opens the folder the recordings are saved in.") {
        EngineerClient.safely("boss recorder folder") {
            java.nio.file.Files.createDirectories(dir)
            net.minecraft.util.Util.getPlatform().openPath(dir)
        }
    }

    /** Whether the module has had its one-time switch-on (it is on by default, on existing installs too). */
    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    /**
     * On by default: modules start off and only a saved config turns them on, so this turns it on
     * once - on a fresh install and on one that had never seen it - and never again, so switching
     * it off sticks. Call after the configs have loaded.
     */
    fun enableByDefault() {
        if (switchedOn) return
        switchedOn = true
        if (!enabled) toggle()
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }

    private val dir get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("bossrecorder")

    /** The recording for the current world; read on the network thread. */
    @Volatile var current: BossRecording? = null
        private set

    private val PRIVATE_CHAT = Regex("""^(?:(?:From|To) (?:\[[^\]]+] )?\w{1,16}: |(?:Guild|Officer|Co-op|Friend) > )""")
    private val CONTROL_CODES = Regex("§.")

    init {
        on<LevelEvent.Load> { EngineerClient.safely("boss recorder start") { current?.finish(); current = BossRecording(dir) } }
        on<LevelEvent.Unload> { EngineerClient.safely("boss recorder end") { current?.finish(); current = null } }
        on<TickEvent.End> { EngineerClient.safely("boss recorder tick") { current?.onTick() } }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val s = current ?: return@onReceive
            val text = content.string.replace(CONTROL_CODES, "")
            if (hidePrivateChats && PRIVATE_CHAT.containsMatchIn(text)) return@onReceive
            val n = s.serverTicks
            EngineerClient.mc.execute { EngineerClient.safely("boss recorder chat") { if (current === s) s.onChat(text, n) } }
        }

        // Your own movement as the server receives it (it never sends it back), and your swings.
        onSend<ServerboundMovePlayerPacket> {
            val s = current ?: return@onSend
            if (!s.focus) return@onSend
            val n = s.serverTicks
            val pos = if (hasPosition()) "${b(getX(0.0))},${b(getY(0.0))},${b(getZ(0.0))}" else "null,null,null"
            val rot = if (hasRotation()) "${a(getYRot(0f))},${a(getXRot(0f))}" else "null,null"
            val entry = "\"me\",$pos,$rot,${if (isOnGround()) 1 else 0}"
            EngineerClient.mc.execute { if (current === s) s.net(n, entry) }
        }
        onSend<ServerboundSwingPacket> {
            val s = current ?: return@onSend
            if (!s.focus) return@onSend
            val n = s.serverTicks
            EngineerClient.mc.execute { if (current === s) s.net(n, "\"msw\"") }
        }
    }

    override fun onEnable() {
        super.onEnable()
        if (EngineerClient.mc.level != null && current == null) current = BossRecording(dir)
    }

    override fun onDisable() {
        super.onDisable()
        EngineerClient.safely("boss recorder off") { current?.finish(); current = null }
    }

    /** Every inbound packet, on the network thread, ahead of any mod that could cancel it (ConnectionTapMixin). */
    fun tap(packet: Packet<*>) {
        if (!enabled) return
        val s = current ?: return
        EngineerClient.safely("boss recorder tap") { BossLog.tap(packet, s) }
    }

    private fun b(v: Double) = String.format(Locale.ROOT, "%.5f", v)
    private fun a(v: Float) = String.format(Locale.ROOT, "%.1f", v)
}
