package com.engineerclient.p3sim.run

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.SimNet
import com.engineerclient.p3sim.run.plugin.AdsPlugins

/**
 * One-time wiring of everything in `run/` and the modules, called from `P3Sim.init()` (settings from `P3Sim`'s own `init`).
 * It is the only place the skeleton touches the existing code, in two added lines of `P3Sim.kt` plus one in its command
 * registration (`AdsCommands.attach`). Each step is guarded: one module failing to wire never stops the others, or the game.
 */
object AdsBootstrap {
    private var done = false

    /** Loads the plug-ins, installs the client/server stage line, registers the command receiver and every module's [AdsModule.onInit]. Once. */
    fun init() {
        if (done) return
        done = true
        EngineerClient.safely("ads plug-ins") { AdsPlugins.load() }
        RunWire.sender = { kind, data -> SimNet.sendAll(kind, data) }
        EngineerClient.safely("ads client run") { ClientRun.register() }
        // Leaving the world forgets the run's stage (Odin's bridge then goes back to "in boss" for the boss practice).
        EngineerClient.safely("ads client run reset") {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> ClientRun.reset() }
        }
        Runs.onStopped = {
            LagRuntime.runPreset = LagPreset.OFF
            ViewPin.release()
            // The boss practice that may follow in this world has the frozen noon it always had (a run runs the clock).
            com.engineerclient.p3sim.SimServer.server?.let { s -> EngineerClient.safely("ads run stop clock") { s.gameRules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, s); com.engineerclient.p3sim.Sim.command("time set noon") } }
            // The clear's entities go with the run (the modules removed theirs in onRunEnd; this catches the rest). Not when the server is gone.
            if (com.engineerclient.p3sim.SimServer.server != null) EngineerClient.safely("ads run stop entities") { com.engineerclient.p3sim.Sim.clearEntities() }
        }
        EngineerClient.safely("ads input") { RunInput.install() }
        EngineerClient.safely("ads commands") { AdsCommands.onInit() }
        for (m in AdsModules.all) EngineerClient.safely("ads ${m.id} init") { m.onInit() }
    }

    /** Lets every module add its settings to [host] (ADS's FullRunSettings). Called once at start-up, before its file is read. */
    fun registerSettings(host: com.engineerclient.ads.settings.Settings) {
        for (m in AdsModules.all) EngineerClient.safely("ads ${m.id} settings") { m.registerSettings(host) }
    }
}
