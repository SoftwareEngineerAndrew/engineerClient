package com.engineerclient.p3sim.clear.replay

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.index
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimNet
import com.engineerclient.p3sim.SimServer
import com.engineerclient.p3sim.clear.ui.RunStarter
import com.engineerclient.p3sim.clear.ui.UiModule
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.EndReason
import com.engineerclient.p3sim.run.LayoutSource
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.RunStage

/**
 * Replays: a recorded run played in ADS on its own layout, taking over your player (`/ads run replay <id>`). You see what the
 * recording's player saw (their moves, look, hotbar, clicks) while ADS answers every click as it would yours, the teammates walk
 * their recorded paths with their names and skins, and the differ writes down every place ADS went another way
 * ([ReplayDiff]). The replay files are made outside the mod from Dungeon Recorder recordings and stay in the game folder.
 */
object ReplayModule : AdsModule {
    override val id = "replay"
    override val owner = "replay"

    @Volatile var session: ReplaySession? = null

    override fun onInit() {
        ReplayClient.init()
        SimNet.onServer("$NET.stop") { _, _ -> session?.stop("stopped by /ads run replay stop") }
    }

    override fun onRunStart(run: Run) {
        session?.stop("a new run started"); session = null
        val id = run.config.replay ?: return
        val host = SimServer.player ?: return
        try {
            session = ReplaySession(run, ReplayFile.load(id), host).also { it.start() }
        } catch (t: Throwable) { Sim.note("Replay $id could not be read: ${t.message}") }
    }

    override fun onTick(run: Run) { session?.takeIf { it.run === run && !it.ended }?.tick() }

    override fun onTickEnd(run: Run) { session?.takeIf { it.run === run && !it.ended }?.tickEnd() }

    override fun onStage(run: Run, from: RunStage?, to: RunStage) {
        if (to == RunStage.BOSS_ENTRY || to == RunStage.BOSS) session?.takeIf { it.run === run }?.stop("ADS reached the boss")
    }

    override fun onEvent(run: Run, event: com.engineerclient.p3sim.run.RunEvent) { session?.takeIf { it.run === run && !it.ended }?.diff?.adsEvent(event) }

    override fun onRunEnd(run: Run, reason: EndReason) { session?.takeIf { it.run === run }?.stop("the run ended ($reason)"); session = null }

    override fun runCommands(): List<AdsCommand> = listOf(
        AdsCommand("replay", "/ads run replay [list | <id> | stop]",
            "Plays a recorded run on its layout with your player taken over; \"stop\" gives you it back. The report is written beside the replay.",
            requiresSim = false, client = { ctx ->
                when (val a = ctx.arg(0)) {
                    null, "list" -> {
                        val all = ReplayFile.list()
                        if (all.isEmpty()) ctx.reply("No replays in config/engineerclient/ads/replays.")
                        else { ctx.reply("${all.size} replays:"); all.forEach { ctx.reply("  $it") } }
                    }
                    "stop" -> { SimNet.send("$NET.stop"); ReplayClient.stop() }
                    else -> {
                        val id = ReplayFile.resolve(a) ?: return@AdsCommand ctx.reply("No replay \"$a\" (or more than one starts so): /ads run replay list.")
                        val f = try { ReplayFile.load(id) } catch (t: Throwable) { return@AdsCommand ctx.reply("Replay $id could not be read: ${t.message}") }
                        // You play the recording's class (the leap window, the tab and the class's items are theirs).
                        val ci = com.engineerclient.p3sim.Party.CLASSES.indexOfFirst { it.name == f.self.cls?.uppercase() }
                        if (ci >= 0) P3Sim.classS.index = ci
                        ctx.reply("Replaying $id: ${f.self.cls ?: "?"}, with ${f.party.joinToString(", ") { "${it.name} (${it.cls ?: "?"})" }}.")
                        mc.execute {
                            RunStarter.start(RunConfig(layout = LayoutSource.Recorded(id), seed = UiModule.drawSeed(), bots = false, partySize = 5,
                                noDeaths = false, mobHpScale = 1.0, replay = id)) { msg ->
                                ctx.reply(msg)
                                // A layout ADS cannot build: the report says so at once (a batch must not wait on a replay that never starts).
                                if (FAILED.any { msg.contains(it) }) failedReport(id, msg)
                            }
                        }
                    }
                }
            }),
    )

    /** The report of a replay that never started: not complete, and why. */
    private fun failedReport(id: String, why: String) = EngineerClient.safely("ads replay failed report") {
        val j = com.google.gson.JsonObject()
        j.addProperty("id", id); j.addProperty("complete", false); j.addProperty("unbuildable", true); j.addProperty("end", why.replace(Regex("§."), ""))
        java.nio.file.Files.createDirectories(ReplayFile.dir)
        java.nio.file.Files.writeString(ReplayFile.dir.resolve("$id.report.json"), j.toString())
    }

    /** What RunStarter says when a run does not start. */
    private val FAILED = listOf("not playable", "could not", "There is no recorded layout", " matches ", "Only the host")

    const val NET = ReplaySession.NET
}
