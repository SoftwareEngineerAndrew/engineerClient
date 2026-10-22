package com.engineerclient.p3sim.clear.secrets

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsServerContext
import com.engineerclient.p3sim.run.SecretKind
import com.engineerclient.p3sim.run.api.Services
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * `/ads dev` commands for the secrets: look at a room's secrets, walk to one, count some without finding them, set off a Superboom
 * where you look, list a room's crypts. They exist so the in-game steps in the work report can be followed without the damage module's
 * item hooks.
 */
internal object SecretsDev {
    fun commands(): List<AdsCommand> = listOf(
        AdsCommand("secrets", "/ads dev secrets [all]", "Lists the secrets of the room you stand in (kind, spot, found or not), or the found/max of every room with 'all'.", server = ::secrets),
        AdsCommand("secretgo", "/ads dev secretgo <n>", "Teleports you onto the n-th secret still to find in your room (the numbers /ads dev secrets prints).", server = ::go),
        AdsCommand("secretgive", "/ads dev secretgive [n]", "Counts n secrets (default 1) of your room as found, the way a bot would, so you can watch the bars and the room turn green.", server = ::give),
        AdsCommand("secretcrypts", "/ads dev secretcrypts", "Lists the crypts and Superboom walls of your room and whether each is destroyed.", server = ::crypts),
        AdsCommand("secretboom", "/ads dev secretboom", "Sets off a Superboom at the block you look at (what the Superboom item will do once it is wired in).", server = ::boom),
        AdsCommand("secretbonus", "/ads dev secretbonus <mimic|prince|bat>", "Scores a bonus mob as if it had died.", server = ::bonus),
    )

    private fun secrets(ctx: AdsServerContext) {
        val run = ctx.run ?: return ctx.reply("No run is on.")
        val info = SecretsModule.devInfo(run) ?: return ctx.reply("The secrets are not set up for this run.")
        val led = run.state.secrets
        if (ctx.arg(0) == "all") {
            ctx.reply("Team: ${led.teamFound}/${led.teamMax} secrets (${led.percent}%), ${led.crypts} crypts, ${led.witherEssence} wither essence.")
            for (r in run.state.rooms) {
                val rs = info.plan.room(r.index)
                if (r.secretsMax == 0) continue
                ctx.reply("  ${r.index} ${r.plan.name}: ${r.secretsFound}/${r.secretsMax}  placeable ${rs?.placeable ?: 0}  ${r.check}")
            }
            return
        }
        val room = run.state.roomAt(ctx.player.x, ctx.player.z) ?: return ctx.reply("You are not in a room.")
        val rs = info.plan.room(room.index)
        ctx.reply("${room.plan.name} (room ${room.index}): ${room.secretsFound}/${room.secretsMax} found, ${rs?.placeable ?: 0} placeable.")
        rs?.sites?.forEachIndexed { i, s ->
            val st = if (info.book.isFound(s)) "found" else if (s.kind == SecretKind.LOCKED_CHEST && s.key !in info.unlocked) "locked" else "to find"
            ctx.reply("  $i  ${s.kind} at ${s.x} ${s.y} ${s.z}  $st${if (s.baked) "  (in the room's blocks)" else ""}")
        }
    }

    private fun go(ctx: AdsServerContext) {
        val run = ctx.run ?: return ctx.reply("No run is on.")
        val info = SecretsModule.devInfo(run) ?: return
        val room = run.state.roomAt(ctx.player.x, ctx.player.z) ?: return ctx.reply("You are not in a room.")
        val n = ctx.arg(0)?.toIntOrNull() ?: return ctx.reply("Say which secret: /ads dev secretgo <n>.")
        val s = info.plan.room(room.index)?.sites?.getOrNull(n) ?: return ctx.reply("This room has no secret number $n.")
        Sim.tp(ctx.player, s.x + 0.5, s.y + 1.0, s.z + 0.5)
        ctx.reply("Moved to ${s.kind} at ${s.x} ${s.y} ${s.z}.")
    }

    private fun give(ctx: AdsServerContext) {
        val run = ctx.run ?: return ctx.reply("No run is on.")
        val room = run.state.roomAt(ctx.player.x, ctx.player.z) ?: return ctx.reply("You are not in a room.")
        val n = (ctx.arg(0)?.toIntOrNull() ?: 1).coerceIn(1, 50)
        var done = 0
        repeat(n) {
            val s = SecretsModule.pendingSecrets(run, room.index).firstOrNull()
            val ok = if (s != null) Services.secrets.found(run, room.index, s.kind, ctx.player.uuid, s.x, s.y, s.z)
            else Services.secrets.found(run, room.index, SecretKind.CHEST, ctx.player.uuid, 0, 0, 0)
            if (ok) done++
        }
        ctx.reply("Counted $done. ${room.plan.name}: ${room.secretsFound}/${room.secretsMax}; room is ${room.check}.")
    }

    private fun crypts(ctx: AdsServerContext) {
        val run = ctx.run ?: return ctx.reply("No run is on.")
        val info = SecretsModule.devInfo(run) ?: return
        val room = run.state.roomAt(ctx.player.x, ctx.player.z) ?: return ctx.reply("You are not in a room.")
        val gs = info.groups.ofRoom(room.index)
        ctx.reply("${room.plan.name}: ${gs.count { it.kind == GroupKind.CRYPT }} crypt tombs, ${gs.count { it.kind == GroupKind.WALL }} secret walls. Team crypts: ${run.state.secrets.crypts}.")
        for (g in gs) ctx.reply("  ${g.kind} ${g.id}: ${g.cells.size} blocks around ${g.minX} ${g.minY} ${g.minZ}  ${if (info.groups.isDestroyed(g)) "destroyed" else "standing"}")
    }

    private fun boom(ctx: AdsServerContext) {
        val run = ctx.run ?: return ctx.reply("No run is on.")
        val hit = ctx.player.pick(12.0, 1f, false)
        if (hit.type != HitResult.Type.BLOCK) return ctx.reply("Look at a block (within 12 blocks).")
        val p = (hit as BlockHitResult).blockPos
        val n = Services.secrets.onSuperboom(run, ctx.player.uuid, p.x, p.y, p.z)
        ctx.reply("Superboom at ${p.x} ${p.y} ${p.z}: $n blocks destroyed. Crypts: ${run.state.secrets.crypts}.")
    }

    private fun bonus(ctx: AdsServerContext) {
        val run = ctx.run ?: return ctx.reply("No run is on.")
        val kind = com.engineerclient.p3sim.run.BonusKind.entries.firstOrNull { it.name.equals(ctx.arg(0), true) } ?: return ctx.reply("Say mimic, prince or bat.")
        Services.secrets.bonus(run, kind, ctx.player.uuid)
        ctx.reply("Scored the ${kind.name.lowercase()}.")
    }
}
