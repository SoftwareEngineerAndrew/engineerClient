package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.Fight
import com.engineerclient.p3sim.P4Necron
import com.engineerclient.p3sim.Sim
import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.run.api.Services
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.engineerclient.enumSelector
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * The engine's module: the Run container's own plumbing (the human party members and their positions, the automatic room savepoints, the
 * end of the run when Necron is dead, the arena's chunks held for the boss, the location the mods that read Hypixel's mod API are told), the lag layer's settings, and the engine spikes and dev tools (`/ads dev difficulty`, `cost`, `lag`,
 * `launch`, `stubrun`, `stage`, `boss`, `savepoint`, `restore`). It is the first module in [AdsModules.all], so positions are fresh when
 * every other module's tick runs.
 */
object EngineModule : AdsModule {
    override val id = "engine"
    override val owner = "engine"

    /** The lag choices in the settings: [LagPreset] without the run-only wording. */
    enum class LagOption(val preset: LagPreset) {
        OFF(LagPreset.OFF), GOOD(LagPreset.GOOD), TYPICAL(LagPreset.TYPICAL), BAD(LagPreset.BAD), HIGH_SLOT(LagPreset.HIGH_SLOT);
        override fun toString() = preset.label
    }

    lateinit var lagPresetS: SelectorSetting
    lateinit var lagBurstS: NumberSetting<Double>

    override fun registerSettings(host: com.odtheking.odin.features.Module) {
        lagPresetS = host.registerSetting(enumSelector("Lag Preset", LagOption.OFF, desc = "Makes the sim's server lose ticks the way Hypixel's does: it freezes for a moment, then runs a burst to catch up, and part of the lost time never comes back. Good, Typical and Bad lose 14.5, 30.3 and 56.6 ticks a minute (the measured 10th, 50th and 90th percentile of F7 runs); High slot is an instance slot above 200 (about 1.8 times Typical). A run's own lag choice takes priority."))
        lagBurstS = host.registerSetting(NumberSetting("Lag Burst Rate", LagBurst.DEFAULT_RATE, 1.0, 6.0, 0.5, desc = "How many times faster than normal the server runs the ticks it catches up after a freeze. Not measured on Hypixel (only how many ticks come back is), so this is a guess; 2 is the default."))
    }

    override fun onInit() {
        // The two client-side readings of the spikes (frame rate, the moment the world becomes playable).
        EngineerClient.safely("ads spike client tick") {
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register { mc -> EngineerClient.safely("ads spike client") { EngineSpikes.clientTick(mc) } }
        }
        // The mods that read Hypixel's mod API (location, party) and the packets they would send to a server that cannot read them.
        EngineerClient.safely("ads hypixel mod api") { HypixelModApi.install() }
        // Every mob the sim adds in a world that is not PEACEFUL stays: vanilla would otherwise despawn it by distance (ARCHITECTURE 14).
        EngineerClient.safely("ads persistent mobs") {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register { entity, level ->
                if (entity is net.minecraft.world.entity.Mob && com.engineerclient.p3sim.SimServer.isSimLevel(level) && entity.entityTags().contains(Sim.TAG) &&
                    level.server.worldData.difficulty != net.minecraft.world.Difficulty.PEACEFUL) entity.setPersistenceRequired()
            }
        }
    }

    /** Called from the end of every sim server tick: runs the spikes, reads the lag settings, then lets the lag layer stall the thread if a freeze starts. */
    fun lagTick(server: MinecraftServer) {
        EngineSpikes.tick(server)
        if (::lagPresetS.isInitialized) LagRuntime.settingPreset = LagOption.entries[lagPresetS.value.coerceIn(0, LagOption.entries.size - 1)].preset
        if (::lagBurstS.isInitialized) LagRuntime.burstRate = lagBurstS.value.toDouble()
        LagRuntime.afterTick(server)
    }

    // ---- the run's plumbing ---------------------------------------------------------------------------------------------------------

    /** Seconds from Necron's removal to the end screen: the real run prints it 27 ticks later (`P4Necron.STATS - GONE`). */
    const val END_SCREEN_AFTER_GONE_TICKS = 27

    /** How often the run's stage is sent to the clients again. */
    const val STAGE_RESEND_TICKS = 100

    private var defeatedMarked = false

    override fun onRunStart(run: Run) {
        defeatedMarked = false
        holdArena(false)
        for (p in Sim.players()) addHuman(run, p)
        // Where the mods that read Hypixel's mod API are told they are: a dungeon server.
        EngineerClient.safely("ads hypixel location") { HypixelModApi.runStarted(run) }
    }

    override fun onStage(run: Run, from: RunStage?, to: RunStage) {
        // From the blood room on, the chunks of the boss arena are held loaded (ticking, and not sent to the client): the one-tick jump lands on a finished
        // floor, and the boss sim's parts far from every player (devices, crystals, terminals up to 150 blocks apart) keep ticking while the view and
        // simulation distance stay pinned for the whole run, as Hypixel's window does (it only re-centres at the jump).
        if (to == RunStage.BLOOD) holdArena(true) else if (to.ordinal < RunStage.BLOOD.ordinal) holdArena(false)
    }

    override fun onRunEnd(run: Run, reason: EndReason) {
        holdArena(false)
        HypixelModApi.runEnded()
    }

    private val heldChunks = ArrayList<Pair<Int, Int>>()

    /** How many arena chunks are held loaded now ([holdArena]). */
    val heldChunkCount: Int get() = heldChunks.size

    /** Forces every chunk of the boss arena loaded ([on]) or lets them go. Server thread. */
    private fun holdArena(on: Boolean) {
        val level = com.engineerclient.p3sim.SimServer.level
        if (!on) {
            if (level != null) for ((x, z) in heldChunks) EngineerClient.safely("ads arena release") { level.setChunkForced(x, z, false) }
            heldChunks.clear()
            return
        }
        if (level == null || heldChunks.isNotEmpty()) return
        val a = com.engineerclient.p3sim.Arena.data
        for ((cx, cz) in WorldEnv.chunksOf(a.x0, a.z0, a.w, a.d)) EngineerClient.safely("ads arena hold") { level.setChunkForced(cx, cz, true); heldChunks += cx to cz }
    }

    /** Adds (or refreshes) the party record of a human player. */
    internal fun addHuman(run: Run, p: ServerPlayer) {
        val rec = run.state.players.getOrPut(p.uuid) { PlayerRecord(p.uuid, p.gameProfile.name, Runs.classLabel(p), false) }
        rec.x = p.x; rec.y = p.y; rec.z = p.z
    }

    override fun onTick(run: Run) {
        // A human who joined after the savepoint a restore went back to has no record any more (the restore keeps only the saved ones): add it again.
        for (p in Sim.players()) {
            if (run.state.players[p.uuid] == null) addHuman(run, p)
            run.state.players[p.uuid]?.let { it.x = p.x; it.y = p.y; it.z = p.z }
        }
        // The stage goes out again every five seconds: a client that was still joining when the first message was sent would otherwise believe "no run"
        // (Odin's bridge would force the boss for the whole clear). Sending the same stage twice changes nothing on the client.
        if (run.serverTick % STAGE_RESEND_TICKS == 0) run.broadcastStage()
        HypixelModApi.tick(run)
        if (run.stage == RunStage.BOSS) watchNecron(run)
    }

    /** The run ends when Necron is dead: the end screen, then stage END (the score module prints the real summary; the boss sim's own stat block is for the boss practice). */
    private fun watchNecron(run: Run) {
        val ph = Fight.phase as? P4Necron ?: return
        if (ph.t >= P4Necron.GONE && !defeatedMarked) { defeatedMarked = true; run.state.milestones.bossDefeatedT = run.t }
        if (ph.t >= P4Necron.GONE + END_SCREEN_AFTER_GONE_TICKS) {
            // The sidebar and tab clock freeze when the end screen prints.
            run.state.milestones.endT = run.state.milestones.endT ?: run.t
            Services.score.showEndScreen(run)
            run.requestStage(RunStage.END)
        }
    }

    override fun onEvent(run: Run, event: RunEvent) {
        if (event is RoomEntered) roomEntered(run, event)
        if (event is PlayerJoined) Sim.players().firstOrNull { it.uuid == event.player }?.let { HypixelModApi.playerJoined(it) }
    }

    /** A human stepped into a room for the first time since the clock last passed that moment: a savepoint, so "restart this room" works (ARCHITECTURE 7). */
    private fun roomEntered(run: Run, e: RoomEntered) {
        val who = run.state.players[e.player] ?: return
        if (who.isBot || run.stage.ordinal > RunStage.CLEAR.ordinal) return
        val room = run.state.room(e.room) ?: return
        if (room.plan.type == RoomType.ENTRANCE) return
        val store = run.savepoints
        if (store.forRoom(e.room)?.let { it.t <= run.t } == true) return
        store.take(run, "Room: ${room.plan.name}", e.room)
    }

    override fun devCommands(): List<AdsCommand> = EngineCommands.dev()
}
