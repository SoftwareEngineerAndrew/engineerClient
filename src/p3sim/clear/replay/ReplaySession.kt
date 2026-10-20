package com.engineerclient.p3sim.clear.replay

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimItems
import com.engineerclient.p3sim.SimNet
import com.engineerclient.p3sim.SimServer
import com.engineerclient.p3sim.clear.puzzles.PuzzlesModule
import com.engineerclient.p3sim.run.CheckMark
import com.engineerclient.p3sim.run.DamageSource
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.DoorPhase
import com.engineerclient.p3sim.run.PlayerRecord
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.api.DoorOpenResult
import com.engineerclient.p3sim.run.api.Services
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.HashedStack
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.network.protocol.game.ServerboundPunchPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Input
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * One replay being played (server thread): the recording's player is the host (what they sent is handed to the sim's packet
 * handlers at the tick it was sent, so ADS answers it as it would the host's own clicks), the teammates are [ReplayBodies] with
 * party records, and what the teammates did that ADS cannot do by itself (their kills, doors, puzzle solves, deaths) is done for
 * them when the recording says it happened and ADS has not (each one logged: [diff]).
 */
class ReplaySession(val run: Run, val file: ReplayFile, val host: ServerPlayer) {
    private val bodies: ReplayBodies
    private val mateByName = LinkedHashMap<String, ReplayBodies.Mate>()
    private val mateIdName = HashMap<Int, String>(file.idToMate)
    val diff = ReplayDiff(this)

    /** Recorded entities (for finding ADS's own one an attack or a death was about). */
    class RecEnt(val id: Int, var type: String?, var x: Double, var y: Double, var z: Double, var name: String?) { var gone = false }
    private val ents = HashMap<Int, RecEnt>()

    /** Run tick of the last event applied. */
    private var lastK: Int = Int.MIN_VALUE
    private var seq = 1000
    var ended = false; private set

    /** What a teammate did that ADS must have done by then: checked at [due], done then if it still has not happened. */
    private class Pending(val due: Int, val what: String, val done: () -> Boolean, val force: () -> String?)
    private val pending = ArrayList<Pending>()

    /** The hotbar, armour and offhand as recorded now (slot -> stack), kept on the host. */
    private val wantInv = HashMap<Int, ItemStack>()
    private var invDirty = false

    init {
        val mates = file.party.map { p ->
            val u = p.uuid?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.nameUUIDFromBytes("replay:${p.name}".toByteArray())
            ReplayBodies.Mate(p.name, u, p.cls, p.tex, p.sig)
        }
        mates.forEach { mateByName[it.name] = it }
        bodies = ReplayBodies(mates)
    }

    private fun cls(c: String?) = c?.uppercase()

    fun start() {
        // The teammates are party members (tab, leap window, key pickups by position, the score's deaths).
        for (m in bodies.all()) {
            val rec = run.state.players.getOrPut(m.uuid) { PlayerRecord(m.uuid, m.name, cls(m.cls), true) }
            rec.dungeonClass = cls(m.cls)
            rec.extras[MATE] = true
        }
        SimNet.send(host, NET, "start ${file.id}")
        diff.note(run.t, "start", "replay ${file.id} (${file.floor}) as ${file.self.cls}; ${file.party.size} teammates; recorded k ${file.firstK}..${file.lastK}, boss at ${file.bossK}")
    }

    fun stop(why: String) {
        if (ended) return
        ended = true
        bodies.removeAll()
        for (m in bodies.all()) run.state.players[m.uuid]?.extras?.remove(MATE)
        SimNet.send(host, NET, "stop")
        diff.note(run.t, "stop", why)
        diff.write()
    }

    // ------------------------------------------------------------------ the tick

    fun tick() {
        if (ended) return
        if (host.isRemoved) { stop("the player left"); return }
        val now = run.t
        if (lastK == Int.MIN_VALUE) {
            // The first tick: everything before it only sets state (where the teammates are, what you hold), no actions.
            for (e in file.events) { val k = e[0].asInt; if (k >= now) break; apply(e, catchUp = true) }
            lastK = now - 1
        }
        while (lastK < now) {
            lastK++
            var pose: JsonArray? = null
            val evs = file.at(lastK)
            for ((i, e) in evs.withIndex()) {
                if (e[1].asString == "me") { pose = e; continue }
                EngineerClient.safely("ads replay ${e[1].asString}") { apply(e, catchUp = false, same = evs, at = i) }
            }
            if (pose != null) sendPose(pose)
        }
        // The teammates' party records follow their bodies.
        for (m in bodies.all()) run.state.players[m.uuid]?.let { r -> if (!m.x.isNaN()) { r.x = m.x; r.y = m.y; r.z = m.z; r.extras["yaw"] = m.yaw } }
        bodies.place()
        if (invDirty) syncInv()
        val due = pending.filter { it.due <= now }
        pending.removeAll(due)
        for (p in due) EngineerClient.safely("ads replay force") {
            if (p.done()) diff.note(now, "ok", p.what)
            else { val r = p.force(); diff.forced(now, p.what, r) }
        }
        selfDeaths(now)
        diff.tick(now)
        if (now > file.lastK || file.bossK?.let { now >= it } == true) stop("the recording reached ${if (now > file.lastK) "its end" else "the boss"}")
    }

    private fun sendPose(e: JsonArray) {
        // x y z yaw pitch slot flags
        SimNet.send(host, NET, "p ${e.d(2)} ${e.d(3)} ${e.d(4)} ${e.d(5)} ${e.d(6)} ${e.i(7) ?: -1} ${e.i(8) ?: 0}")
    }

    // ------------------------------------------------------------------ events

    private fun apply(e: JsonArray, catchUp: Boolean, same: List<JsonArray> = emptyList(), at: Int = 0) {
        val k = e[0].asInt
        when (e[1].asString) {
            // ---- recorded entities
            "spawn" -> {
                val id = e.i(2) ?: return
                val r = RecEnt(id, e.s(3), e.d(5) ?: 0.0, e.d(6) ?: 0.0, e.d(7) ?: 0.0, e.s(8))
                ents[id] = r
                mate(id)?.let { m -> m.x = r.x; m.y = r.y; m.z = r.z; m.yaw = e.d(9)?.toFloat() ?: m.yaw; m.present = true }
            }
            "emv" -> ents[e.i(2)]?.let { r -> r.x = e.d(3) ?: r.x; r.y = e.d(4) ?: r.y; r.z = e.d(5) ?: r.z }
            "name" -> ents[e.i(2)]?.name = e.s(3)
            "gone", "rm" -> {
                val id = e.i(2) ?: return
                ents[id]?.gone = true
                mate(id)?.present = false
            }
            // ---- the teammates' bodies
            "mmv" -> mate(e.i(2))?.let { m ->
                m.x = e.d(3) ?: m.x; m.y = e.d(4) ?: m.y; m.z = e.d(5) ?: m.z
                e.d(6)?.let { m.yaw = it.toFloat() }; e.d(7)?.let { m.pitch = it.toFloat() }
                m.present = true
            }
            "mrot" -> mate(e.i(2))?.let { m -> e.d(3)?.let { m.yaw = it.toFloat() }; e.d(4)?.let { m.pitch = it.toFloat() } }
            "mhead" -> mate(e.i(2))?.let { m -> e.d(3)?.let { m.head = it.toFloat() } }
            "mflags" -> mate(e.i(2))?.let { m -> m.flags = e.i(3) ?: 0 }
            "manim" -> if (!catchUp) mate(e.i(2))?.let { m -> if (e.i(3) == 0) m.swing = true }
            "eq" -> mate(e.i(2))?.let { m -> ReplayBodies.slot(e.s(3))?.let { s -> m.held[s] = ReplayBodies.stack(e.s(4), e.s(6)) } }
            // ---- your inventory
            "inv" -> inv(e)
            // ---- what the recording says happened (forced when ADS does not)
            "eev" -> if (!catchUp && e.i(3) == 3) mobDied(k, e.i(2) ?: return)
            "chat" -> if (!catchUp) chat(k, e.s(2) ?: return)
            "room" -> if (!catchUp) room(k, e)
            "blk" -> if (!catchUp) diff.recordedBlock(k, e.i(2) ?: return, e.i(3) ?: return, e.i(4) ?: return, e.s(5))
            "screen" -> if (!catchUp && e.s(3) == "inventory") SimNet.send(host, NET, "inv 1")
            "screenClose" -> if (!catchUp && host.containerMenu === host.inventoryMenu) SimNet.send(host, NET, "inv 0")
            "tpme" -> if (!catchUp) diff.recordedTeleport(k, e)
            // ---- what you sent
            else -> if (!catchUp && e[1].asString.startsWith("o.")) self(e, same, at)
        }
    }

    private fun mate(id: Int?): ReplayBodies.Mate? = id?.let { mateIdName[it] }?.let { mateByName[it] }

    // ------------------------------------------------------------------ you

    private fun self(e: JsonArray, same: List<JsonArray>, at: Int) {
        if (!run.stage.inDungeon) return
        val c = host.connection
        when (e[1].asString) {
            "o.m" -> {
                val x = e.d(2); val y = e.d(3); val z = e.d(4); val yaw = e.d(5)?.toFloat(); val pitch = e.d(6)?.toFloat()
                val ground = e.i(7) == 1; val hcol = e.i(8) == 1
                val pk = when {
                    x != null && yaw != null -> ServerboundMovePlayerPacket.PosRot(x, y!!, z!!, yaw, pitch!!, ground, hcol)
                    x != null -> ServerboundMovePlayerPacket.Pos(x, y!!, z!!, ground, hcol)
                    yaw != null -> ServerboundMovePlayerPacket.Rot(yaw, pitch!!, ground, hcol)
                    else -> ServerboundMovePlayerPacket.StatusOnly(ground, hcol)
                }
                c.handleMovePlayer(pk)
                if (x != null) diff.moved(e[0].asInt, x, y!!, z!!, host)
            }
            "o.slot" -> c.handleSetCarriedItem(ServerboundSetCarriedItemPacket(e.i(2) ?: return))
            "o.use" -> c.handleUseItem(ServerboundUseItemPacket(hand(e.s(2)), seq++, e.d(3)?.toFloat() ?: host.yRot, e.d(4)?.toFloat() ?: host.xRot))
            "o.useOn" -> {
                val pos = e.get(3).asJsonArray.let { BlockPos(it[0].asInt, it[1].asInt, it[2].asInt) }
                val dir = Direction.byName(e.s(4)?.lowercase() ?: "up") ?: Direction.UP
                val loc = e.get(5).asJsonArray.let { Vec3(it[0].asDouble, it[1].asDouble, it[2].asDouble) }
                c.handleUseItemOn(ServerboundUseItemOnPacket(hand(e.s(2)), BlockHitResult(loc, dir, pos, e.i(6) == 1), seq++))
            }
            "o.atk" -> {
                val t = target(e.o(2) ?: return) ?: return diff.missingTarget(e[0].asInt, "attack", e.o(2)!!)
                c.handleAttack(ServerboundAttackPacket(t.id))
            }
            "o.int" -> {
                val b = e.o(2) ?: return
                val t = target(b) ?: return run { if (e.s(3) == "MAIN_HAND") diff.missingTarget(e[0].asInt, "interact", b) }
                val loc = e.get(4).takeIf { it.isJsonArray }?.asJsonArray?.let { Vec3(it[0].asDouble, it[1].asDouble, it[2].asDouble) } ?: Vec3.ZERO
                c.handleInteract(ServerboundInteractPacket(t.id, hand(e.s(3)), loc, e.i(5) == 1))
            }
            "o.act" -> {
                val action = runCatching { ServerboundPlayerActionPacket.Action.valueOf(e.s(2)!!) }.getOrNull() ?: return
                val pos = e.get(3).takeIf { it.isJsonArray }?.asJsonArray?.let { BlockPos(it[0].asInt, it[1].asInt, it[2].asInt) } ?: BlockPos.ZERO
                val dir = Direction.byName(e.s(4)?.lowercase() ?: "down") ?: Direction.DOWN
                when (action) {
                    // The sim's own block hit (an adventure-mode player's start_destroy never reaches it): the Dungeonbreaker, levers, Superboom.
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK -> Sim.asPlayer(host) { SimItems.serverHit(host, "${pos.x},${pos.y},${pos.z},${dir.ordinal},0") }
                    ServerboundPlayerActionPacket.Action.DROP_ITEM, ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS ->
                        Sim.asPlayer(host) { SimItems.serverDrop(host, action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) }
                    else -> c.handlePlayerAction(ServerboundPlayerActionPacket(action, pos, dir, e.i(5) ?: seq++))
                }
            }
            "o.swing" -> {
                c.handlePunch(ServerboundPunchPacket.INSTANCE)
                // A swing that follows a right click in the same tick is that click's; any other is a left click (the sim's shortbows, Mage Beam...).
                val fromUse = same.subList(0, at).any { it[1].asString in RIGHT_CLICKS }
                if (!fromUse) Sim.asPlayer(host) { SimItems.serverLeftClick(host, SimItems.idOf(host.mainHandItem) ?: "") }
            }
            "o.cmd" -> runCatching { ServerboundPlayerCommandPacket.Action.valueOf(e.s(2)!!) }.getOrNull()?.let { c.handlePlayerCommand(ServerboundPlayerCommandPacket(host, it)) }
            "o.input" -> {
                val b = (e.s(2) ?: return).map { it == '1' }
                if (b.size >= 7) c.handlePlayerInput(ServerboundPlayerInputPacket(Input(b[0], b[1], b[2], b[3], b[4], b[5], b[6])))
            }
            "o.click" -> click(e)
            "o.close" -> if (host.containerMenu !== host.inventoryMenu) host.closeContainer()
        }
    }

    private fun hand(s: String?) = if (s == "OFF_HAND") InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND

    /**
     * A click in a window: the open window's slot holding the item of that name (the recorded slot number when it holds nothing
     * named, or the names are not the sim's). Clicks in your own inventory are not replayed: the recorded inventory is set as it went.
     */
    private fun click(e: JsonArray) {
        val menu = host.containerMenu
        if (menu === host.inventoryMenu) return
        val name = e.s(6)?.trim()
        val slot = e.i(3) ?: return
        val byName = name?.takeIf { it.isNotEmpty() }?.let { n -> menu.slots.indexOfFirst { s -> s.hasItem() && clean(s.item.hoverName.string) == n } }?.takeIf { it >= 0 }
        val use = byName ?: slot
        if (byName == null && name != null) diff.note(e[0].asInt, "click", "no \"$name\" in ADS's ${host.containerMenu.javaClass.simpleName}; clicked slot $slot")
        if (use !in 0 until menu.slots.size) return
        val input = runCatching { ContainerInput.valueOf(e.s(5)!!) }.getOrNull() ?: ContainerInput.PICKUP
        host.connection.handleContainerClick(ServerboundContainerClickPacket(menu.containerId, menu.stateId, use.toShort(), (e.i(4) ?: 0).toByte(), input, Int2ObjectOpenHashMap(), HashedStack.EMPTY))
    }

    /**
     * ADS's entity for a recorded one: a teammate's body, else the nearest living thing within 6 blocks of where the recorded
     * one was (the same entity type ahead of another; a named stand for a stand).
     */
    private fun target(b: JsonObject): Entity? {
        b.get("mate")?.takeIf { it.isJsonPrimitive }?.asString?.let { n -> return mateByName[n]?.body?.takeIf { !it.isRemoved } }
        val x = b.get("x")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
        val y = b.get("y").asDouble; val z = b.get("z").asDouble
        val type = b.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        val tag = b.get("tag")?.takeIf { it.isJsonPrimitive }?.asString?.let { clean(it) }
        val box = AABB(x - 6, y - 4, z - 6, x + 6, y + 6, z + 6)
        val all = Sim.level.getEntities(null as Entity?, box) { it !== host && it.isAlive && it !is ServerPlayer }
        return all.minByOrNull { en ->
            var d = en.position().distanceTo(Vec3(x, y, z))
            val et = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(en.type).toString()
            if (et != type) d += 4
            if (en is net.minecraft.world.entity.decoration.ArmorStand && tag != null && clean(en.customName?.string ?: "") != tag) d += 3
            if (en !is LivingEntity && en !is net.minecraft.world.entity.decoration.ArmorStand) d += 2
            d
        }
    }

    // ------------------------------------------------------------------ inventory

    private fun inv(e: JsonArray) {
        val arr = e.get(3).takeIf { it.isJsonArray }?.asJsonArray ?: return
        for (s in arr) {
            val a = s.asJsonArray
            val slot = a[0].asInt
            if (slot !in 0..8 && slot !in 36..40) continue
            val o = a[1].takeIf { it.isJsonObject }?.asJsonObject
            wantInv[slot] = if (o == null) ItemStack.EMPTY else stackOf(o)
        }
        invDirty = true
    }

    /**
     * A recorded stack as the sim knows it: the recorded item (its name, lore, model) with the SkyBlock id the sim's item handlers read.
     */
    private fun stackOf(o: JsonObject): ItemStack {
        val sb = o.get("sb")?.takeIf { it.isJsonPrimitive }?.asString
        val st = o.get("full")?.takeIf { it.isJsonObject }?.let { full ->
            runCatching { ItemStack.CODEC.parse(Sim.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), full).result().orElse(null) }.getOrNull()
        } ?: ReplayBodies.stack(o.get("id")?.asString, o.get("name")?.takeIf { it.isJsonPrimitive }?.asString).also { s -> o.get("count")?.asInt?.let { s.count = it } }
        if (sb != null && !st.isEmpty) CustomData.update(DataComponents.CUSTOM_DATA, st) { t: CompoundTag -> t.putString("id", sb) }
        return st
    }

    private fun syncInv() {
        invDirty = false
        val inv = host.inventory
        for ((slot, want) in wantInv) {
            val have = if (slot <= 35) inv.getItem(slot) else when (slot) {
                36 -> host.getItemBySlot(EquipmentSlot.FEET); 37 -> host.getItemBySlot(EquipmentSlot.LEGS)
                38 -> host.getItemBySlot(EquipmentSlot.CHEST); 39 -> host.getItemBySlot(EquipmentSlot.HEAD)
                else -> host.getItemBySlot(EquipmentSlot.OFFHAND)
            }
            if (SimItems.idOf(have) == SimItems.idOf(want) && have.count == want.count && have.item == want.item) continue
            if (slot <= 35) inv.setItem(slot, want.copy())
            else host.setItemSlot(when (slot) { 36 -> EquipmentSlot.FEET; 37 -> EquipmentSlot.LEGS; 38 -> EquipmentSlot.CHEST; 39 -> EquipmentSlot.HEAD; else -> EquipmentSlot.OFFHAND }, want.copy())
        }
        host.inventoryMenu.broadcastChanges()
    }

    // ------------------------------------------------------------------ teammates' doings

    private fun mobDied(k: Int, id: Int) {
        if (mateIdName.containsKey(id)) return
        val r = ents[id] ?: return
        if (r.type == null || r.type == "minecraft:armor_stand" || r.type == "minecraft:player" && r.name == null && r.x == 0.0) return
        val rx = r.x; val ry = r.y; val rz = r.z
        val label = (r.name ?: r.type)
        // The ADS mob nearest where it died, alive when the recording's died.
        val mob = Services.mobs.all().filter { it.alive && Math.abs(it.y - ry) < 6 }.minByOrNull { (it.x - rx) * (it.x - rx) + (it.z - rz) * (it.z - rz) }
            ?.takeIf { Math.hypot(it.x - rx, it.z - rz) < 8 }
        if (mob == null) { diff.note(k, "mob", "recorded death of $label at ${fmt(rx, ry, rz)}: no living ADS mob within 8"); return }
        val who = nearestMate(rx, rz)
        pending += Pending(k + GRACE, "${mob.name} killed (recorded death of $label)", { !mob.alive }) {
            Services.damage.hurtMob(run, mob.id, mob.hp + 1e12, DamageSource.OTHER, who)
            if (mob.alive) "hurtMob left it alive" else "killed by ${who?.let { run.state.players[it]?.name } ?: "?"}"
        }
    }

    private fun nearestMate(x: Double, z: Double): UUID? =
        (bodies.all().filter { !it.x.isNaN() && it.present }.map { it.uuid to Math.hypot(it.x - x, it.z - z) } + (host.uuid to Math.hypot(host.x - x, host.z - z)))
            .minByOrNull { it.second }?.first

    private fun chat(k: Int, raw: String) {
        val t = clean(raw)
        diff.recordedChat(k, t)
        WITHER_OPENED.find(t)?.let { m -> door(k, DoorKind.WITHER, m.groupValues[1]); return }
        if (t.startsWith("The BLOOD DOOR has been opened!")) { door(k, DoorKind.BLOOD, null); return }
        if (t.startsWith("Party >")) return
        DIED.find(t)?.let { m -> death(k, m.groupValues[1]); return }
        REVIVED.find(t)?.let { m -> revive(k, m.groupValues[1], m.groupValues[2]); return }
    }

    private fun uuidOf(name: String?): UUID? = when (name) {
        null -> null
        file.self.name, "You" -> host.uuid
        else -> mateByName[name]?.uuid
    }

    private fun posOf(u: UUID?): Vec3? = when (u) {
        null -> null
        host.uuid -> host.position()
        else -> bodies.all().firstOrNull { it.uuid == u }?.takeIf { !it.x.isNaN() }?.let { Vec3(it.x, it.y, it.z) }
    }

    private fun door(k: Int, kind: DoorKind, who: String?) {
        val by = uuidOf(who)
        val at = posOf(by) ?: host.position()
        val door = run.plan.doors.filter { it.kind == kind }.minByOrNull { d -> val c = d.spot; Math.hypot(c.first + 0.5 - at.x, c.second + 0.5 - at.z) } ?: return
        val rec = run.state.doors[door.index]
        pending += Pending(k + DOOR_GRACE, "${kind.name.lowercase()} door ${door.index} opened by ${who ?: "?"}", { rec.phase != DoorPhase.CLOSED }) {
            var r = Services.doors.tryOpen(run, door.index, by)
            if (r == DoorOpenResult.NO_KEY) {
                // ADS had no key for it (the teammate took one ADS did not drop, or not yet): given, then opened.
                if (kind == DoorKind.WITHER) run.state.keys.witherHeld++ else run.state.keys.bloodHeld = true
                r = Services.doors.tryOpen(run, door.index, by)
                "no key in ADS (given one): $r"
            } else r.toString()
        }
    }

    /** Recorded deaths of yours (k), to tell ADS's own kills of you from the recording's. */
    private val selfDeathKs: List<Int> by lazy {
        file.all("chat").mapNotNull { e -> val t = clean(e.s(2) ?: ""); DIED.find(t)?.takeIf { !t.startsWith("Party >") && uuidOf(it.groupValues[1]) == host.uuid }?.let { e[0].asInt } }
    }
    private var wasGhost = false

    /** ADS killing you where the recording did not die: written down, and you are revived so the replay goes on as recorded. */
    private fun selfDeaths(now: Int) {
        val rec = run.state.players[host.uuid] ?: return
        if (rec.ghost && !wasGhost && selfDeathKs.none { Math.abs(it - now) <= 40 }) {
            diff.forced(now, "ADS killed you (the recording did not die here)", "revived")
            Services.damage.revive(run, host.uuid, null)
        }
        wasGhost = rec.ghost
    }

    private fun death(k: Int, name: String) {
        val u = uuidOf(name) ?: return
        val rec = run.state.players[u] ?: return
        pending += Pending(k + 2, "$name died", { rec.ghost }) { Services.damage.kill(run, u, DamageSource.OTHER); if (rec.ghost) "killed" else "kill refused (no deaths?)" }
    }

    private fun revive(k: Int, name: String, by: String) {
        val u = uuidOf(name) ?: return
        val rec = run.state.players[u] ?: return
        pending += Pending(k + 2, "$name revived by $by", { !rec.ghost }) { Services.damage.revive(run, u, uuidOf(by)); if (!rec.ghost) "revived" else "revive refused" }
    }

    /** A recorded room changing mark: for the differ, and a puzzle the recording solved is solved in ADS too (when it is not). */
    private fun room(k: Int, e: JsonArray) {
        val mark = e.s(3) ?: return
        val tiles = e.get(5).takeIf { it.isJsonArray }?.asJsonArray ?: return
        val t0 = tiles.firstOrNull()?.asJsonArray ?: return
        val placed = run.plan.roomAt(t0[0].asInt, t0[1].asInt) ?: return diff.note(k, "room", "no ADS room at tile ${t0[0]},${t0[1]} (${e.s(2)})")
        diff.recordedRoom(k, placed.index, e.s(2), mark)
        if (e.s(4) == "PUZZLE" && (mark == "GREEN" || mark == "WHITE")) {
            val rr = run.state.room(placed.index) ?: return
            pending += Pending(k + GRACE, "puzzle ${placed.name} solved", { rr.check == CheckMark.GREEN || rr.check == CheckMark.WHITE }) {
                val pz = PuzzlesModule.puzzleOf(run, placed.index) ?: return@Pending "no ADS puzzle in the room"
                Services.puzzles.reportSolved(pz, nearestMate(placed.tiles.first().let { (it.x * 32 - 185).toDouble() }, placed.tiles.first().let { (it.z * 32 - 185).toDouble() }))
                "reported solved"
            }
        }
    }

    companion object {
        const val NET = "ads.replay"
        const val MATE = "replayMate"
        /** Ticks ADS gets to do by itself what the recording did (a kill, a puzzle) before it is done for it. */
        const val GRACE = 20
        const val DOOR_GRACE = 6
        private val RIGHT_CLICKS = setOf("o.use", "o.useOn", "o.int")
        private val WITHER_OPENED = Regex("^(\\w{1,16}) opened a WITHER door!")
        private val DIED = Regex("☠ (\\w{1,16}) .*became a ghost")
        private val REVIVED = Regex("❣ (\\w{1,16}) was revived by (\\w{1,16})")
        fun clean(s: String) = s.replace(Regex("§."), "").trim()
        fun fmt(x: Double, y: Double, z: Double) = "%.1f %.1f %.1f".format(x, y, z)
        val hostPlayer: ServerPlayer? get() = SimServer.player
    }
}
