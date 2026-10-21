package com.engineerclient.p3sim.clear.state

import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.EndReason
import com.engineerclient.p3sim.run.KeyKind
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.api.DoorOpenResult
import com.engineerclient.p3sim.run.api.DoorService
import com.engineerclient.p3sim.run.api.KeyPlan
import com.google.gson.JsonObject
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.phys.BlockHitResult
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Run-state logic of the clear: the countdown and Mort lines, doors (coal -> barrier -> air over 14 ticks), the key
 * economy, the room check-mark state machine (the ONLY writer of `RoomRecord.check`), the tile counters, room discovery
 * and entry detection from position. The logic lives in [StateController] (one per run, headless-tested); this object is
 * the wiring: settings, the click hook, the dev commands, the [DoorService] answers.
 * FILLED BY: the `doors` work item (owns `src/p3sim/clear/state/` and below, `src/p3sim/pack/runstate/` and below).
 */
object DoorsModule : AdsModule, DoorService {
    override val id = "doors"
    override val owner = "doors"

    private val log = LoggerFactory.getLogger("engineerclient")

    /** The effects the controller uses; tests of the module replace it. */
    internal var effects: () -> StateEffects = { McStateEffects }

    /** The running controller, or null with no run. */
    @Volatile var controller: StateController? = null
        private set

    private var countdownDelayS: NumberSetting<*>? = null
    private var pickupRadiusS: NumberSetting<*>? = null
    private var synthesiseS: BooleanSetting? = null

    override fun registerSettings(host: com.odtheking.odin.features.Module) {
        countdownDelayS = host.registerSetting(NumberSetting("Doors Arrival Wait", 60, 0..400, 5, unit = " ticks", desc = "How long after a run starts the \"Starting in 4 seconds\" count begins. Hypixel: a median 57 ticks after the potions line you read on arriving."))
        pickupRadiusS = host.registerSetting(NumberSetting("Doors Key Pickup Radius", 2.5, 1.0..6.0, 0.5, unit = " blocks", desc = "How close you or a teammate has to be to take a Wither or Blood Key off the floor. Not measured: most keys on Hypixel are taken in the tick they appear (by whoever killed the last mob), the rest after about 8 ticks."))
        synthesiseS = host.registerSetting(BooleanSetting("Doors Synthesise Clears", true, desc = "A room with no mob that has to die turns white on the measured schedule (median 50 ticks after it is discovered) instead of staying grey. Trap rooms are not on it: they turn white when their end chest is opened."))
    }

    private fun config(): StateConfig {
        val d = StateConfig()
        return StateConfig(
            countdownDelayTicks = (countdownDelayS?.value as? Number)?.toInt() ?: d.countdownDelayTicks,
            pickupRadius = (pickupRadiusS?.value as? Number)?.toDouble() ?: d.pickupRadius,
            synthesiseClears = synthesiseS?.value ?: d.synthesiseClears,
            keyFallbackTicks = d.keyFallbackTicks,
        )
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------------

    override fun onRunStart(run: Run) {
        val c = StateController(run, StateRules.fromPack(run.packs.runstate), config(), effects())
        controller = c
        c.start()
        MortNpc.spawn(run.plan)
    }

    override fun onTick(run: Run) { controller?.tick(); MortNpc.tick() }

    override fun onEvent(run: Run, event: RunEvent) { controller?.onEvent(event) }

    override fun onRunEnd(run: Run, reason: EndReason) { controller?.end(); controller = null; MortNpc.remove() }

    override fun snapshot(run: Run): JsonObject? = controller?.snapshot()

    override fun restore(run: Run, data: JsonObject) { controller?.restore(data); MortNpc.spawn(run.plan) }

    // ---- DoorService -----------------------------------------------------------------------------------------------------

    override fun keyPlan(plan: com.engineerclient.p3sim.run.DungeonPlan): KeyPlan = KeyPlans.of(plan)

    override fun keyForRoom(run: Run, roomIndex: Int): KeyKind? = controller?.keyForRoom(roomIndex)

    override fun dropKey(run: Run, roomIndex: Int, x: Double, z: Double) { controller?.dropKey(roomIndex, x, z) }
    override fun pickUpKey(run: Run, by: java.util.UUID, kind: KeyKind, roomIndex: Int?, x: Double, z: Double): Boolean = controller?.pickUpKey(by, kind, roomIndex, x, z) ?: false

    override fun tryOpen(run: Run, door: Int, by: UUID?): DoorOpenResult = controller?.tryOpen(door, by) ?: DoorOpenResult.NOT_A_DOOR

    override fun doorAtBlock(run: Run, x: Int, y: Int, z: Int): Int? = controller?.doorAtBlock(x, y, z)

    /**
     * A swing at the block ([x],[y],[z]) by [by]: the no-key line when it is a closed key door and the party has no key. Called from the
     * client's block-hit message (the adventure-mode player never reaches Fabric's attack callback); see the integration request.
     */
    override fun touchBlock(run: Run, x: Int, y: Int, z: Int, by: UUID?): Boolean {
        val c = controller ?: return false
        return c.doorAtBlock(x, y, z)?.let { c.touch(it, by) } ?: false
    }

    /**
     * Finishes a room's objective now (white, or green with no secret left). For the bots module's measured schedule and the dev
     * command; a no-op for rooms already done and for the entrance, Fairy and blood rooms.
     */
    fun completeRoom(run: Run, roomIndex: Int) { controller?.objectiveDone(roomIndex) }

    // ---- clicks ----------------------------------------------------------------------------------------------------------

    override fun onUseBlock(run: Run, player: ServerPlayer, hand: InteractionHand, hit: BlockHitResult): InteractionResult? {
        val c = controller ?: return null
        val pos = hit.blockPos
        val door = c.doorAtBlock(pos.x, pos.y, pos.z) ?: return null
        if (!run.plan.doors[door].kind.hasBody) return null
        c.tryOpen(door, player.uuid)
        // The click is the door's: the held item's ability does not fire from the use_item the client sends after it.
        com.engineerclient.p3sim.SimItems.swallowFollowUp(player)
        return InteractionResult.SUCCESS
    }

    // ---- dev commands ----------------------------------------------------------------------------------------------------

    override fun devCommands(): List<AdsCommand> = listOf(
        AdsCommand("doors", "/ads dev doors", "Lists every door with its kind and state, the keys held, and the key stands on the floor.", server = { ctx ->
            val run = ctx.run; val c = controller
            if (run == null || c == null) ctx.reply("No run is on.") else describe(run, c).forEach(ctx::reply)
        }),
        AdsCommand("roomstate", "/ads dev roomstate", "Lists every room with its check mark, secrets and who is inside, and the Opened and Completed counters.", server = { ctx ->
            val run = ctx.run
            if (run == null) ctx.reply("No run is on.") else {
                for (r in run.state.rooms) ctx.reply("#${r.index} ${r.plan.name} (${r.plan.type.name.lowercase()}, ${r.plan.tileCount}t): ${r.check} secrets ${r.secretsFound}/${r.secretsMax} mobs ${r.mobsRequiredAlive}/${r.mobsRequired} inside ${r.occupants.size}")
                val k = run.state.counters
                ctx.reply("Opened ${k.opened}/${k.total}, completed ${k.completed}/${k.total} (${k.clearedPct}%).")
            }
        }),
        AdsCommand("givekey", "/ads dev givekey <wither|blood>", "Adds a key to the party count as if it had been picked up.", server = { ctx ->
            val c = controller
            val kind = if (ctx.arg(0).equals("blood", true)) KeyKind.BLOOD else KeyKind.WITHER
            if (c == null) ctx.reply("No run is on.") else { c.ledger.pick(kind); ctx.reply("Gave a ${StateText.keyName(kind)}.") }
        }),
        AdsCommand("opendoor", "/ads dev opendoor <n>", "Opens door n without a key (barrier, then air 14 ticks later).", server = { ctx ->
            val c = controller; val n = ctx.arg(0)?.toIntOrNull()
            if (c == null || n == null) ctx.reply("Usage: /ads dev opendoor <door number> (see /ads dev doors).") else { c.beginFall(n, ctx.player.uuid); ctx.reply("Door $n is falling.") }
        }),
        AdsCommand("clearroom", "/ads dev clearroom <n>", "Finishes room n's objective now (white, or green with no secret left).", server = { ctx ->
            val c = controller; val n = ctx.arg(0)?.toIntOrNull()
            if (c == null || n == null) ctx.reply("Usage: /ads dev clearroom <room number> (see /ads dev roomstate).") else { c.objectiveDone(n); ctx.reply("Room $n: ${ctx.run?.state?.rooms?.getOrNull(n)?.check}.") }
        }),
    )

    private fun describe(run: Run, c: StateController): List<String> = buildList {
        val k = run.state.keys
        add("t=${run.t}  keys: wither ${k.witherHeld} (got ${k.witherObtained}, used ${k.witherUsed}), blood ${if (k.bloodHeld) "held" else if (k.bloodUsed) "used" else "no"}")
        add("key rooms: wither ${c.keyPlan.witherRooms}, blood ${c.keyPlan.bloodRoom}; dropped ${c.ledger.droppedRooms()}")
        for (d in run.plan.doors) if (d.kind != DoorKind.NONE && d.kind != DoorKind.NORMAL && d.kind != DoorKind.UNKNOWN) {
            val rec = run.state.doors[d.index]
            add("door ${d.index} ${d.kind} between rooms ${d.roomA} and ${d.roomB}: ${rec.phase}${rec.openedAt?.let { " since t=$it" } ?: ""}")
        }
        for (s in c.standList()) add("key stand ${s.kind} in room ${s.room} at ${"%.1f".format(s.x)}, ${"%.2f".format(s.y)}, ${"%.1f".format(s.z)}")
    }
}
