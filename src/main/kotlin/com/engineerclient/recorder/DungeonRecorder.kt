package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import com.engineerclient.misc.ScoreboardLines
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
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
import net.minecraft.core.registries.BuiltInRegistries
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

/**
 * Dungeon Recorder: everything that happens in a dungeon, for building mods with an LLM. Where
 * Better PF records a replay and Boss Recorder the boss fights, this keeps the lot:
 *
 *  - every packet the server sends and every packet you send, each written out field by field
 *    ([PacketJson]) with the server tick it arrived on;
 *  - your own state every tick (position, look, motion, health, held item, keys held, the screen
 *    open) and the game's derived state when it changes (Odin's floor, room and party, the sidebar).
 *
 * Files: config/engineerclient/recorder/, gzipped JSON Lines in hourly parts (format:
 * docs/dungeon-recorder.md; tools/recorder/read.py turns them into readable timelines).
 */
object DungeonRecorder : Module(
    name = "Dungeon Recorder",
    category = Category.custom("Engineer Client"),
    description = "Records everything in a dungeon - every packet both ways, field by field, and your own state every tick - as context for building mods. Saved to config/engineerclient/recorder/.",
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
    private val budgetMb by NumberSetting("Max MB Per Hour", 1000.0, 50.0, 2000.0, 50.0, desc = "Compressed size cap per hour. Near it, entity movement, particles and sounds are left out until the hour turns.")
    private val openFolder by ActionSetting("Open Folder", desc = "Opens the folder the recordings are saved in.") {
        EngineerClient.safely("recorder folder") { java.nio.file.Files.createDirectories(dir); net.minecraft.util.Util.getPlatform().openPath(dir) }
    }

    private val dir get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("recorder")

    @Volatile private var session: RecorderSession? = null
    @Volatile private var tick = 0
    @Volatile private var serverTicks = 0
    private var lastState = ""
    private var lastDungeon = ""
    private var lastSidebar = ""

    private val PRIVATE_CHAT = Regex("""^(?:(?:From|To) (?:\[[^\]]+] )?\w{1,16}: |(?:Guild|Officer|Co-op|Friend) > )""")

    /** Packet types never worth a line: keep-alives, the bundle markers, light. */
    private val SKIP = setOf("minecraft:keep_alive", "minecraft:pong", "minecraft:bundle_delimiter", "minecraft:light_update", "minecraft:chunk_batch_start", "minecraft:chunk_batch_finished")
    /** Big and rarely useful: their name and size only. */
    private val SUMMARY = setOf("minecraft:commands", "minecraft:update_tags", "minecraft:update_recipes", "minecraft:recipe_book_add", "minecraft:update_advancements")
    private val MOVEMENT = setOf("minecraft:move_entity_pos", "minecraft:move_entity_pos_rot", "minecraft:move_entity_rot", "minecraft:rotate_head",
        "minecraft:set_entity_motion", "minecraft:entity_position_sync", "minecraft:teleport_entity")
    private val EFFECTS = setOf("minecraft:level_particles", "minecraft:sound", "minecraft:sound_entity")

    init {
        // Each world gets its own recording, started as it loads so its first packets (the entities
        // and blocks already there) are kept, and only written once it turns out to be wanted.
        on<LevelEvent.Load> { EngineerClient.safely("recorder world") { stop(); if (enabled) start() } }
        on<LevelEvent.Unload> { EngineerClient.safely("recorder world end") { stop() } }
        on<TickEvent.End> { EngineerClient.safely("recorder tick") { onTick() } }

        onSend<Packet<*>>(priority = -1000) {
            val s = session ?: return@onSend
            if (!outbound) return@onSend
            val p = this
            val n = serverTicks; val t = tick; val ms = System.currentTimeMillis()
            val type = PacketJson.type(p)
            if (type in SKIP || type == "minecraft:pong") return@onSend
            val redact = !typedChat && (p is ServerboundChatPacket || p is ServerboundChatCommandPacket || p is ServerboundChatCommandSignedPacket)
            s.add { line("out", type, t, n, ms) { if (redact) "{\"redacted\":true}" else PacketJson.write(p) } }
        }
    }

    /** Every packet from the server, on the network thread, ahead of any mod that could cancel it (ConnectionTapMixin). */
    fun tap(packet: Packet<*>) {
        if (!enabled) return
        if (packet is ClientboundPingPacket && packet.id != 0) serverTicks++
        val s = session ?: return
        if (!inbound) return
        EngineerClient.safely("recorder tap") { inbound(s, packet, tick, serverTicks, System.currentTimeMillis()) }
    }

    private fun inbound(s: RecorderSession, p: Packet<*>, t: Int, n: Int, ms: Long) {
        if (p is ClientboundBundlePacket) { p.subPackets().forEach { inbound(s, it, t, n, ms) }; return }
        val type = PacketJson.type(p)
        if (type in SKIP) return
        val low = type in MOVEMENT || type in EFFECTS
        if ((type in MOVEMENT && !movement) || (type in EFFECTS && !effects)) return
        if (hidePrivate) {
            val text = when (p) { is ClientboundSystemChatPacket -> p.content.string; is ClientboundPlayerChatPacket -> p.body.content; else -> null }
            if (text != null && PRIVATE_CHAT.containsMatchIn(text.replace(Regex("§."), ""))) return
        }
        s.add(low) {
            line("in", type, t, n, ms) {
                when {
                    p is ClientboundLevelChunkWithLightPacket && !chunks -> "{\"x\":${p.x},\"z\":${p.z}}"
                    type in SUMMARY -> "{\"summary\":true}"
                    else -> PacketJson.write(p)
                }
            }
        }
    }

    /** One packet line: direction, type, client tick, server tick, wall clock, then its fields. */
    private inline fun line(dir: String, type: String, t: Int, n: Int, ms: Long, body: () -> String) =
        """{"k":"$dir","p":"$type","t":$t,"n":$n,"ms":$ms,"f":${body()}}"""

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
        tick++
        val s = session ?: return
        if (!confirmed) {
            // Odin knows the area a second or two after the world loads; a minute without it is not one to keep.
            if (wanted()) { confirmed = true; s.confirm() }
            else if (tick - startedTick > 20 * 60 || (where != 2 && LocationUtils.currentArea != com.odtheking.odin.utils.skyblock.Island.Unknown && !wanted())) {
                session = null; s.abandon(); return
            }
        }
        if (state) clientState(s)
    }

    private fun start() {
        val mc = EngineerClient.mc
        val version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("engineerclient").map { it.metadata.version.friendlyString }.orElse("?")
        val label = { (DungeonUtils.floor?.name ?: LocationUtils.currentArea.name).replace(Regex("[^A-Za-z0-9]+"), "") }
        confirmed = false; startedTick = tick
        session = RecorderSession(dir, label, { (budgetMb * 1024 * 1024).toLong() }) {
            """{"k":"meta","format":"recorder-1","part":0,"mod":${q(version)},"mc":"26.1.2","self":${q(mc.player?.name?.string ?: "?")},""" +
                """"selfId":${mc.player?.id ?: -1},"server":${q(mc.currentServer?.ip ?: "?")},"t":$tick,"n":$serverTicks,"ms":${System.currentTimeMillis()}}"""
        }
        lastState = ""; lastDungeon = ""; lastSidebar = ""
    }

    private fun stop() {
        val s = session ?: return
        session = null
        if (!confirmed) { s.abandon(); return }
        s.line("""{"k":"end","t":$tick,"ms":${System.currentTimeMillis()}}""")
        Thread({ s.close() }, "ec-recorder-close").apply { isDaemon = true; start() }
    }

    /** Turned on mid-world: record from here (the world's first packets are already gone). */
    override fun onEnable() {
        super.onEnable()
        if (EngineerClient.mc.level != null && session == null) EngineerClient.safely("recorder on") { start() }
    }

    override fun onDisable() {
        super.onDisable()
        stop()
    }

    /** Your own state, every tick it changes; Odin's dungeon state and the sidebar when they change. */
    private fun clientState(s: RecorderSession) {
        val mc = EngineerClient.mc
        val p = mc.player ?: return
        val o = mc.options
        val keys = listOf(o.keyUp to "w", o.keyLeft to "a", o.keyDown to "s", o.keyRight to "d", o.keyJump to "jump", o.keyShift to "sneak",
            o.keySprint to "sprint", o.keyAttack to "attack", o.keyUse to "use").filter { it.first.isDown }.joinToString(",") { "\"${it.second}\"" }
        val held = StringBuilder().also { PacketJson.item(it, p.mainHandItem) }
        val screen = mc.screen?.let { "{\"class\":${q(it.javaClass.simpleName)},\"title\":${q(it.title.string)}}" } ?: "null"
        val v = p.deltaMovement
        val st = """"pos":[${f(p.x)},${f(p.y)},${f(p.z)}],"rot":[${f1(p.yRot)},${f1(p.xRot)}],"vel":[${f(v.x)},${f(v.y)},${f(v.z)}],""" +
            """"ground":${p.onGround()},"hp":${f1(p.health)},"abs":${f1(p.absorptionAmount)},"food":${p.foodData.foodLevel},""" +
            """"slot":${p.inventory.selectedSlot},"held":$held,"keys":[$keys],"screen":$screen"""
        if (st != lastState) { lastState = st; s.line("""{"k":"me","t":$tick,"n":$serverTicks,$st}""") }

        if (tick % 10 != 0) return
        val effects = p.activeEffects.joinToString(",") { "[${q(BuiltInRegistries.MOB_EFFECT.getKey(it.effect.value()).toString())},${it.amplifier},${it.duration}]" }
        val team = DungeonUtils.dungeonTeammates.joinToString(",") { "[${q(it.name)},${q(it.clazz.name)},${it.isDead}]" }
        val dungeon = """"area":${q(LocationUtils.currentArea.name)},"floor":${q(DungeonUtils.floor?.name ?: "")},"boss":${DungeonUtils.inBoss},""" +
            """"room":${q(DungeonUtils.currentRoomName)},"party":[$team],"effects":[$effects],"fps":${mc.fps}"""
        if (dungeon != lastDungeon) { lastDungeon = dungeon; s.line("""{"k":"game","t":$tick,"n":$serverTicks,$dungeon}""") }

        val board = mc.level?.scoreboard
        val objective = board?.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (board != null && objective != null) {
            val lines = ScoreboardLines.sidebarEntries(board, objective).joinToString(",") { q(ScoreboardLines.plain(ScoreboardLines.lineText(board, it))) }
            val sidebar = """"title":${q(objective.displayName.string)},"lines":[$lines]"""
            if (sidebar != lastSidebar) { lastSidebar = sidebar; s.line("""{"k":"sidebar","t":$tick,"n":$serverTicks,$sidebar}""") }
        }
    }

    private fun q(s: String) = com.google.gson.JsonPrimitive(s).toString()
    private fun f(d: Double) = String.format(java.util.Locale.ROOT, "%.3f", d)
    private fun f1(v: Float) = String.format(java.util.Locale.ROOT, "%.1f", v)
}
