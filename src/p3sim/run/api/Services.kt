package com.engineerclient.p3sim.run.api

import com.engineerclient.p3sim.clear.bots.BotsModule
import com.engineerclient.p3sim.clear.damage.DamageModule
import com.engineerclient.p3sim.clear.hud.HudModule
import com.engineerclient.p3sim.clear.layout.LayoutModule
import com.engineerclient.p3sim.clear.mobs.MobsModule
import com.engineerclient.p3sim.clear.puzzles.PuzzlesModule
import com.engineerclient.p3sim.clear.score.ScoreModule
import com.engineerclient.p3sim.clear.secrets.SecretsModule
import com.engineerclient.p3sim.clear.state.DoorsModule
import com.engineerclient.p3sim.clear.traps.TrapsModule

/**
 * The fixed wiring between the modules: each service interface is implemented by exactly one module object, and this is
 * where you find it. No registration, no shared list to edit. Until a module is filled in, its object answers with the
 * interface's harmless defaults (nothing spawns, nothing is hurt), so a half-built tree still runs.
 *
 * Calling pattern: `Services.mobs.setHp(run, id, 0.0, by)`.
 */
object Services {
    val layout: LayoutService get() = LayoutModule
    val doors: DoorService get() = DoorsModule
    val mobs: MobService get() = MobsModule
    val damage: DamageService get() = DamageModule
    val secrets: SecretService get() = SecretsModule
    val score: ScoreService get() = ScoreModule
    val puzzles: PuzzleHost get() = PuzzlesModule
    val bots: BotService get() = BotsModule
    val traps: TrapService get() = TrapsModule
    val channels: ChannelService get() = HudModule
}
