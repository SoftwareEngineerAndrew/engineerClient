package com.engineerclient.p3sim.clear.replay

import com.engineerclient.p3sim.clear.traps.TrapsModule
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
    private var ownName: String? = null

    /** Where the recording had you at the end of the last tick. */
    private var recPos: Vec3? = null

    private class Pending(val due: Int, val what: String, val done: () -> Boolean, val force: () -> String?)
    private val pending = ArrayList<Pending>()

    /** The hotbar, armour and offhand as recorded now (slot -> stack), kept on the host. */
    private val wantInv = HashMap<Int, ItemStack>()
    private var invDirty = false
    private var lastHost: Vec3? = null

    /** The server's teleport count for you when [teleported] last looked. */
    private var tpSeen = -1

    /**
     * Whether ADS teleported you since [teleported] last looked, from [from]: the server sent a teleport (its id moved on) and you are more
     * than half a block away, as Hypixel's count leaves out its resyncs to where you stood. A blink into a wall can move you under 2 blocks.
     */
    private fun teleported(from: Vec3): Boolean {
        val id = (host.connection as com.engineerclient.mixin.ServerTeleportAccessor).ec_getAwaitingTeleport()
        val sent = tpSeen != -1 && id != tpSeen
        tpSeen = id
        return sent && from.distanceTo(host.position()) > 0.5
    }

    init {
        val mates = file.party.map { p ->
            val u = p.uuid?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.nameUUIDFromBytes("replay:${p.name}".toByteArray())
            ReplayBodies.Mate(p.name, u, p.cls, p.tex, p.sig)
        }
        mates.forEach { mateByName[it.name] = it }
        bodies = ReplayBodies(mates)
    }

    private fun cls(c: String?) = c?.uppercase()

    /**
     * The recording's mobs, room by room, as the mobs module plans them: each where it was first seen, its kind and modifier from its tag,
     * starred as tagged, with the HP its tag showed. Rooms the recording saw no mob in are rolled as usual. Blood's are the blood module's.
     */
    private val roomPlans: Map<Int, List<com.engineerclient.p3sim.clear.mobs.PlannedMob>> by lazy {
        recordedScale.size
        val pack = run.packs.mobs ?: return@lazy emptyMap()
        val byName = (pack.archetypes + com.engineerclient.p3sim.clear.mobs.LevelLooks.all).sortedByDescending { it.name.length }
        val out = HashMap<Int, MutableList<com.engineerclient.p3sim.clear.mobs.PlannedMob>>()
        val unknown = HashMap<String, Int>()
        val unknownWord = HashMap<String, Int>()
        for (m in file.mobs) {
            val room = run.plan.roomAtBlock(m.x, m.z) ?: continue
            if (room.type == com.engineerclient.p3sim.run.RoomType.BLOOD || room.type == com.engineerclient.p3sim.run.RoomType.ENTRANCE || room.type == com.engineerclient.p3sim.run.RoomType.FAIRY) continue
            val star = m.tag.contains("✯")
            val name = bareTag(m.tag)
            val arch = byName.firstOrNull { name == it.name || name.endsWith(" " + it.name) }
            if (arch == null) { unknown.merge(name, 1, Int::plus); continue }
            // What a broken crypt lets out comes on its own tick, not with the room.
            if (arch.id == CRYPT_UNDEAD || com.engineerclient.p3sim.clear.mobs.LevelLooks.isPrince(arch)) {
                cryptMobs += CryptMob(m.k, m.id, arch.id, room.index, m.x, m.y, m.z, (firstHp[m.id] ?: m.hp).takeIf { it > 0 }); continue
            }
            val word = name.removeSuffix(arch.name).trim()
            val mod = word.takeIf { it.isNotEmpty() }?.let { pack.modifier(it) }
            if (word.isNotEmpty() && mod == null) unknownWord.merge(word, 1, Int::plus)
            val rng = run.subRandom("replay.mob.${m.id}")
            val id = if (arch.disguise) com.engineerclient.p3sim.clear.mobs.MobRolls.uuidV2(rng) else com.engineerclient.p3sim.clear.mobs.MobRolls.uuidV4(rng)
            // Its base HP: a mob first seen after the WITHER door into its room had scaled already.
            val seen = seenScale[room.index]?.takeIf { f -> ratio(m, arch, mod)?.let { kotlin.math.abs(it / f - 1) < 0.05 } == true }
            val hp = (firstHp[m.id] ?: m.hp).takeIf { it > 0 }?.let { h -> seen?.let { Math.round(h / it).toDouble() }
                ?: recordedScale[room.index]?.takeIf { (k, _) -> (firstHpK[m.id] ?: m.k) > k }?.let { Math.round(h / it.second).toDouble() } ?: h }
                ?: (arch.hpFor(run.config.floor)?.toDouble() ?: 1.0)
            planned[m.id] = id; plannedBack[id] = m.id
            out.getOrPut(room.index) { ArrayList() } += com.engineerclient.p3sim.clear.mobs.PlannedMob(id, arch.index, star, star || arch.cls == "miniboss", mod?.id.orEmpty(), hp,
                m.x, m.y, m.z, m.yaw, arch.movement != "static", null)
        }
        cryptMobs.sortBy { it.k }
        for ((n, c) in unknown) diff.note(0, "mobs", "the mob pack has no kind for \"$n\" (${c}x): not spawned")
        for ((w, c) in unknownWord) diff.note(0, "mobs", "the mob pack has no word \"$w\" (${c}x): spawned without it")
        out
    }

    /** The Higher Or Lower blazes of the recording: each body where it was first seen, with the HP its tag gave (x, y, z, HP). */
    private val recordedBlazes: List<DoubleArray> by lazy {
        val first = HashMap<Int, JsonArray>()
        val out = LinkedHashMap<String, DoubleArray>()
        for (e in file.events) {
            when (e.s(1)) {
                "spawn" -> e.i(2)?.let { first.putIfAbsent(it, e) }
                "name" -> {
                    val hp = e.s(3)?.let { BLAZE_TAG.find(it) }?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
                    val b = first[(e.i(2) ?: continue) - 1]?.takeIf { it.s(3) == "minecraft:blaze" } ?: continue
                    val x = b.d(5) ?: continue; val y = b.d(6) ?: continue; val z = b.d(7) ?: continue
                    out.putIfAbsent("$x $y $z", doubleArrayOf(x, y, z, hp))
                }
            }
        }
        out.values.toList()
    }

    /** The ADS mob planned for each recorded mob. */
    private val planned = HashMap<Int, java.util.UUID>()
    private val plannedBack = HashMap<java.util.UUID, Int>()
    /** Each recorded mob's HP over the run (k, HP; 0 is its death), and how far your hits have used it. */
    private val recHp: Map<Int, List<Pair<Int, Double>>> by lazy {
        val out = HashMap<Int, MutableList<Pair<Int, Double>>>()
        for (e in file.events) {
            if (e.size() > 3 && e[1].asString == "hp") {
                val v = e[3].asDouble; val l = out.getOrPut(e[2].asInt) { ArrayList() }
                if (v > 100.0 || (v <= 0.0 && l.isNotEmpty())) l += e[0].asInt to v
            }
            // A mob killed outright gets no last HP update, only its death.
            if (e.size() > 3 && e[1].asString == "eev" && e[3].asInt == 3) out[e[2].asInt]?.takeIf { it.isNotEmpty() }?.add(e[0].asInt to 0.0)
        }
        out
    }
    private val hpUsedK = HashMap<Int, Int>()

    /**
     * Your pearl thrown now as the recording had it: its pearl (spawned within [PEARL_NEAR] ticks, 1.5 blocks across from where you were),
     * the teleport of you when it went (within 2 ticks): the ticks from now and where. Null when there is none.
     */
    private fun recordedPearl(): Pair<Int, Vec3>? {
        val now = run.t; val at = recPos ?: return null
        val sp = file.events.firstOrNull { it.size() > 7 && it[1].asString == "spawn" && it.s(3) == "minecraft:ender_pearl" && it[0].asInt in now - PEARL_NEAR..now + PEARL_NEAR
            && kotlin.math.hypot((it.d(5) ?: return@firstOrNull false) - at.x, (it.d(7) ?: return@firstOrNull false) - at.z) < 1.5 && it[2].asInt !in pearlsUsed } ?: return null
        val id = sp[2].asInt
        pearlsUsed += id
        val gone = file.events.firstOrNull { it.size() > 2 && it[1].asString == "rm" && it[2].asInt == id && it[0].asInt >= sp[0].asInt }?.get(0)?.asInt ?: return null
        val tp = file.events.firstOrNull { it.size() > 2 && it[1].asString == "tpme" && it[0].asInt in gone - 2..gone + 2 && it[2].isJsonArray } ?: return null
        val p = tp[2].asJsonArray
        return (tp[0].asInt - now) to Vec3(p[0].asDouble, p[1].asDouble, p[2].asDouble)
    }
    private val pearlsUsed = HashSet<Int>()

    /**
     * Your Teleport Maze steps: each teleport onto a slab's middle (y on a half, x and z on halves) from standing on a pad's frame (the top of an
     * end-portal frame, 0.8125 up its block), as the frame block and the landing block (world x, z, x, z).
     */
    private val recordedMazeSteps: List<IntArray> by lazy {
        val out = ArrayList<IntArray>()
        var at: DoubleArray? = null
        for (e in file.events) {
            when (e[1].asString) {
                "o.m" -> if (e.size() > 4 && !e[2].isJsonNull) at = doubleArrayOf(e[2].asDouble, e[3].asDouble, e[4].asDouble)
                "tpme" -> {
                    val p = e[2].takeIf { it.isJsonArray }?.asJsonArray ?: continue
                    val from = at ?: continue
                    val x = p[0].asDouble; val y = p[1].asDouble; val z = p[2].asDouble
                    fun half(v: Double) = kotlin.math.abs(v - Math.floor(v) - 0.5) < 0.01
                    if (half(x) && half(y) && half(z) && kotlin.math.abs(from[1] - Math.floor(from[1]) - 0.8125) < 0.01)
                        out += intArrayOf(Math.floor(from[0]).toInt(), Math.floor(from[2]).toInt(), Math.floor(x).toInt(), Math.floor(z).toInt())
                    at = doubleArrayOf(x, y, z)
                }
            }
        }
        out
    }

    /** Every block the recording showed as a trapped chest (the Mimic's chest). */
    private val recordedTrapped: Set<BlockPos> by lazy {
        file.events.filter { it.size() > 5 && it[1].asString == "blk" && it.s(5)?.startsWith("minecraft:trapped_chest") == true }
            .mapTo(HashSet()) { BlockPos(it[2].asInt, it[3].asInt, it[4].asInt) }
    }

    /**
     * Each tick you clicked a Hyperion (main hand) in the recording: whether Hypixel cast for it, by your explode sounds (volume 1, within
     * 2 blocks of where you stood or of a teleport of yours a tick either side: the cast's own lands you there first) in the 3 ticks after,
     * each sound one click's in order.
     */
    private val recordedHypeCasts: Map<Int, Boolean> get() = hypeCasts.first

    /**
     * The Hyperion clicks that also right clicked an entity (main hand, same tick) and whose cast went off where you stood, with no
     * teleport. Standing inside the entity, Hypixel's cast stayed put 27 times and teleported 25 (DR, a teammate within 0.6 blocks):
     * nothing recorded tells the two apart, so a replay takes the recording's.
     */
    private val recordedHypeStill: Set<Int> get() = hypeCasts.second

    private val hypeCasts: Pair<Map<Int, Boolean>, Set<Int>> by lazy {
        val hotbar = arrayOfNulls<String>(9); var held = 0; var x = 0.0; var z = 0.0
        val clicks = ArrayList<Int>(); val casts = ArrayList<Int>(); val still = HashSet<Int>(); val withEntity = HashSet<Int>()
        val tps = file.events.filter { it.size() > 2 && it[1].asString == "tpme" && it[2].isJsonArray }.map { Triple(it[0].asInt, it[2].asJsonArray[0].asDouble, it[2].asJsonArray[2].asDouble) }
        for (e in file.events) {
            if (e.size() < 3) continue
            when (e[1].asString) {
                "o.slot" -> held = e.i(2)?.takeIf { it in 0..8 } ?: held
                "inv" -> e.get(3).takeIf { it.isJsonArray }?.asJsonArray?.forEach { s ->
                    val a = s.asJsonArray; val slot = a[0].asInt
                    if (slot in 0..8) hotbar[slot] = a[1].takeIf { it.isJsonObject }?.asJsonObject?.get("sb")?.takeIf { it.isJsonPrimitive }?.asString
                }
                "o.m" -> if (e.size() > 4 && e[2].isJsonPrimitive) { x = e[2].asDouble; z = e[4].asDouble }
                "tpme" -> e.get(2).takeIf { it.isJsonArray }?.asJsonArray?.let { x = it[0].asDouble; z = it[2].asDouble }
                "o.use", "o.useOn" -> if (e.s(2) == "MAIN_HAND" && hotbar[held] == "HYPERION" && clicks.lastOrNull() != e[0].asInt) clicks += e[0].asInt
                "o.int" -> if (e.s(3) == "MAIN_HAND") withEntity += e[0].asInt
                "snd" -> if (e.size() > 7 && e.s(2) == "minecraft:entity.generic.explode" && e[7].asDouble == 1.0) {
                    val k = e[0].asInt; val sx = e[4].asDouble; val sz = e[6].asDouble
                    val landed = tps.any { (tk, tx, tz) -> Math.abs(tk - k) <= 1 && Math.hypot(sx - tx, sz - tz) < 2 }
                    if (Math.hypot(sx - x, sz - z) < 2 || landed) { casts += k; if (!landed) still += k }
                }
            }
        }
        val out = HashMap<Int, Boolean>(); val stays = HashSet<Int>(); var i = 0
        for (k in clicks) {
            while (i < casts.size && casts[i] < k) i++
            val cast = i < casts.size && casts[i] <= k + 3
            if (cast) { if (casts[i] in still && k in withEntity) stays += k; i++ }
            out[k] = cast
        }
        out to stays
    }

    /** The first tick each recorded chest's lid opened (a block.chest.open sound at its centre). */
    private val recordedChestOpen: Map<BlockPos, Int> by lazy {
        val out = HashMap<BlockPos, Int>()
        for (e in file.events) if (e.size() > 6 && e[1].asString == "snd" && e.s(2) == "minecraft:block.chest.open")
            out.putIfAbsent(BlockPos(Math.floor(e[4].asDouble).toInt(), Math.floor(e[5].asDouble).toInt(), Math.floor(e[6].asDouble).toInt()), e[0].asInt)
        out
    }

    /** When your Ultimate was first ready in the recording (its first "is ready to use!" line), or null. */
    private val recordedUltFirst: Int? by lazy {
        file.events.firstOrNull { it.size() > 2 && it[1].asString == "chat" && it[2].isJsonPrimitive && clean(it[2].asString).endsWith(" is ready to use! Press DROP to activate it!") }?.get(0)?.asInt
    }

    /**
     * The recording's tab refresh, as k mod [TAB_TICKS]: Hypixel's tab counters (Time, rooms, secrets, crypts, deaths) change once a second,
     * all on one tick of it, a tick later now and then (DR: every change of a run on two neighbouring ticks of the 20). The tick its Time
     * changed on most; null with fewer than 5 changes.
     */
    private val recordedTabPhase: Int? by lazy {
        var last: String? = null
        val at = IntArray(TAB_TICKS)
        for (e in file.events) {
            if (e.size() < 3 || e[1].asString != "dung" || !e[2].isJsonObject || e[0].asInt <= 0) continue
            val v = e[2].asJsonObject.get("elapsed")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            if (last != null && v != last) at[Math.floorMod(e[0].asInt, TAB_TICKS)]++
            last = v
        }
        if (at.sum() < 5) null else at.indices.maxBy { at[it] }
    }

    /** The tick the recording's Blood room portal appeared (its first nether_portal block; DR: all in one tick), or null. */
    private val recordedPortalK: Int? by lazy {
        file.events.firstOrNull { it.size() > 5 && it[1].asString == "blk" && it[5].isJsonPrimitive && it[5].asString.startsWith("minecraft:nether_portal") }?.get(0)?.asInt
    }
    private var portalSaid = false

    /** Your recorded Implosion lines: k, enemies, damage. */
    private val implosions: List<Triple<Int, Int, Double>> by lazy {
        file.events.mapNotNull { e ->
            if (e.size() < 3 || e[1].asString != "chat" || !e[2].isJsonPrimitive) return@mapNotNull null
            val m = IMPLOSION.find(clean(e[2].asString)) ?: return@mapNotNull null
            Triple(e[0].asInt, m.groupValues[1].toInt(), m.groupValues[2].replace(",", "").toDouble())
        }
    }
    private val implosionUsed = HashSet<Int>()

    /**
     * Each recorded line's click (your use_item): the latest one a tick or two before the line, else one on its tick, each click one line
     * (DR: the line 0 to 2 ticks after the click, mostly 1; 5,688 of 5,707 lines paired so). Click k to the line's index in [implosions].
     */
    private val implosionByClick: Map<Int, Int> by lazy {
        val clicks = file.events.filter { it.size() > 1 && it[1].asString == "o.use" && it[0].asInt > 0 }.mapTo(HashSet()) { it[0].asInt }
        val out = HashMap<Int, Int>()
        for ((i, l) in implosions.withIndex()) {
            val c = intArrayOf(1, 2, 0).map { l.first - it }.firstOrNull { it in clicks && it !in out } ?: continue
            out[c] = i
        }
        out
    }

    /**
     * The recorded line of the cast ADS runs now: the nearest unused one within [IMPLOSION_NEAR] ticks; none (0 enemies): Hypixel's cast
     * hit nothing, as it says nothing then.
     */
    private fun recordedImplosion(by: java.util.UUID): Pair<Int, Double>? {
        if (forcing || ended || by != host.uuid) return null
        val now = run.t
        // A cast of your click now: that click's line, or none (Hypixel's hit nothing).
        if (lastClickK == now) {
            val i = implosionByClick[now]?.takeIf { it !in implosionUsed } ?: return 0 to 0.0
            implosionUsed += i
            return implosions[i].second to implosions[i].third
        }
        val i = implosions.indices.filter { it !in implosionUsed && kotlin.math.abs(implosions[it].first - now) <= IMPLOSION_NEAR }
            .minByOrNull { kotlin.math.abs(implosions[it].first - now) } ?: return 0 to 0.0
        implosionUsed += i
        return implosions[i].second to implosions[i].third
    }
    /** Set while the session itself kills a mob (a recorded death), so the hit is not read as yours. */
    private var forcing = false

    /**
     * Your hit on [mob] does what the recording shows: the HP its recorded mob lost from [HIT_BEFORE] ticks before now to [HIT_AFTER] after
     * (each loss counted once), all it has when the recording's died then, nothing when it lost none. Null for anyone else's hit.
     */
    private fun hitAmount(mob: java.util.UUID, by: java.util.UUID?): Double? {
        if (forcing || ended || by != host.uuid) return null
        val id = plannedBack[mob] ?: return null
        val l = recHp[id] ?: return 0.0
        val now = run.t
        val from = maxOf(hpUsedK[id] ?: Int.MIN_VALUE, now - HIT_BEFORE - 1)
        val before = l.lastOrNull { it.first <= from }?.second ?: return 0.0
        val low = l.filter { it.first > from && it.first <= now + HIT_AFTER }.minByOrNull { it.second } ?: return 0.0
        hpUsedK[id] = low.first
        if (low.second <= 0.0) return 1e15
        return maxOf(0.0, before - low.second)
    }
    /** Recorded HP as the replay goes, and the WITHER-door jumps to check two ticks on. */
    private val liveHp = HashMap<Int, Double>()
    private val hpChecks = ArrayList<Triple<Int, Int, Double>>()

    /** Each recorded mob's first real HP (over 100: a body's vanilla health comes first) and the k it was seen. */
    private val firstHp = HashMap<Int, Double>()
    private val firstHpK = HashMap<Int, Int>()

    /**
     * Each room's WITHER-door HP factor as the recording shows it: its mobs' HP jumps (the commonest ratio, to 0.05) and the first k one
     * jumped. Hypixel's factor is known only from here (see MobsModule.scale).
     */
    private val recordedScale: Map<Int, Pair<Int, Double>> by lazy {
        val mobs = file.mobs.associateBy { it.id }
        val last = HashMap<Int, Double>(); val jumped = HashSet<Int>()
        val byRoom = HashMap<Int, MutableList<Pair<Int, Double>>>()
        for (e in file.events) {
            if (e.size() < 4 || e[1].asString != "hp") continue
            val id = e[2].asInt; val v = e[3].asDouble; val k = e[0].asInt
            val m = mobs[id] ?: continue
            if (v <= 100.0) continue
            if (id !in firstHp) { firstHp[id] = v; firstHpK[id] = k }
            val l = last.put(id, v) ?: continue
            if (k <= 0 || v / l <= 1.04 || !jumped.add(id)) continue
            val room = run.plan.roomAtBlock(m.x, m.z)?.index ?: continue
            byRoom.getOrPut(room) { ArrayList() } += k to Math.round(v / l * 20) / 20.0
        }
        byRoom.mapValues { (_, l) -> l.minOf { it.first } to l.groupingBy { it.second }.eachCount().maxBy { it.value }.key }
    }

    /** A recorded mob's first HP over its kind's (with its word's factor), or null when its kind is unknown. */
    private fun ratio(m: ReplayFile.Mob, arch: com.engineerclient.p3sim.pack.mobs.MobArchetype, mod: com.engineerclient.p3sim.pack.mobs.MobModifier?): Double? {
        val base = arch.hpFor(run.config.floor)?.takeIf { it > 0 } ?: return null
        val h = (firstHp[m.id] ?: m.hp).takeIf { it > 0 } ?: return null
        return h / (base * (mod?.hpFactor ?: 1.0))
    }

    /**
     * The WITHER-door factor of a room no mob of which the recording saw jump (they were all first seen scaled): the commonest ratio (to
     * 0.05) of their first HP over their kind's, when it is over 1.04. Rooms [recordedScale] knows are not here.
     */
    private val seenScale: Map<Int, Double> by lazy {
        recordedScale.size
        val pack = run.packs.mobs ?: return@lazy emptyMap()
        val byName = (pack.archetypes + com.engineerclient.p3sim.clear.mobs.LevelLooks.all).sortedByDescending { it.name.length }
        val byRoom = HashMap<Int, MutableList<Double>>()
        for (m in file.mobs) {
            val room = run.plan.roomAtBlock(m.x, m.z)?.index ?: continue
            if (room in recordedScale) continue
            val name = bareTag(m.tag)
            val arch = byName.firstOrNull { name == it.name || name.endsWith(" " + it.name) } ?: continue
            val mod = name.removeSuffix(arch.name).trim().takeIf { it.isNotEmpty() }?.let { pack.modifier(it) }
            val r = ratio(m, arch, mod) ?: continue
            byRoom.getOrPut(room) { ArrayList() } += Math.round(r * 20) / 20.0
        }
        byRoom.mapNotNull { (room, l) -> l.groupingBy { it }.eachCount().maxBy { it.value }.key.takeIf { it > 1.04 }?.let { room to it } }.toMap()
    }

    private class Throw(val thr: Int, val land: Int, val x: Double, val y: Double, val z: Double)

    /**
     * The recording's Watcher throws, in ticks after its BLOOD door, in throw order: each head is a nameless armor stand at rest on the
     * Blood room's wall; it is thrown on its first move after the door and lands (the mob is added) when it is removed.
     * Measured over 139 DR runs: 78 to 80 ticks of flight for the first four, 38 to 40 for the later ones, as [CampMech] has it.
     */
    private val recordedThrows: List<Throw> by lazy {
        val door = file.events.firstOrNull { it.size() > 2 && it[1].asString == "chat" && it[2].isJsonPrimitive && it[2].asString.startsWith("The BLOOD DOOR has been opened!") }?.get(0)?.asInt
            ?: return@lazy emptyList()
        val named = file.events.filter { it.size() > 2 && it[1].asString == "name" }.mapTo(HashSet()) { it[2].asInt }
        class Stand(val x: Double, val y: Double, val z: Double) { var thr = -1; var land = -1 }
        val stands = HashMap<Int, Stand>()
        for (e in file.events) {
            val k = e[0].asInt; val kind = e[1].asString
            if (kind == "spawn" && e.size() > 7 && e[3].asString == "minecraft:armor_stand" && e[2].asInt !in named && e[2].asInt !in stands) {
                val x = e[5].asDouble; val y = e[6].asDouble; val z = e[7].asDouble
                // A head at rest on the wall (block centre, 0.8 over a block), seen before the door or later, once the recorded player came near.
                fun frac(v: Double) = ((v % 1.0) + 1.0) % 1.0
                val atRest = Math.abs(frac(x) - 0.5) < 0.01 && Math.abs(frac(z) - 0.5) < 0.01 && Math.abs(frac(y) - 0.8) < 0.1
                if (atRest && run.plan.roomAtBlock(x, z)?.type == com.engineerclient.p3sim.run.RoomType.BLOOD) stands[e[2].asInt] = Stand(x, y, z)
                continue
            }
            if (k <= door || e.size() < 3 || (kind != "emv" && kind != "gone")) continue
            val st = stands[e[2].asInt] ?: continue
            if (kind == "emv" && st.thr < 0 && e.size() > 5 && Math.hypot(e[3].asDouble - st.x, e[5].asDouble - st.z) > 0.05) st.thr = k - door
            if (kind == "gone" && st.land < 0) st.land = k - door
        }
        // A real throw flies 38 to 80 ticks; the wall's other heads go when the room is left.
        stands.values.filter { it.thr >= 0 && it.land - it.thr in 30..90 }.sortedBy { it.thr }.map { Throw(it.thr, it.land, it.x, it.y, it.z) }
    }

    /** The recording's throw order as ADS's wall stations (the same stand within a block), or null when any head has no station or the camp is not whole. */
    private fun recordedOrder(wall: List<com.engineerclient.p3sim.clear.blood.camp.WallStation>): List<com.engineerclient.p3sim.clear.blood.camp.WallStation>? {
        val t = recordedThrows
        if (t.size < 4 + com.engineerclient.p3sim.clear.blood.camp.CampMech.LATER_THROWS) { diff.note(run.t, "camp", "the recording has ${t.size} throws, not a whole camp: ADS draws its own order"); return null }
        val out = t.map { r -> wall.firstOrNull { Math.abs(it.stand.x - r.x) < 0.6 && Math.abs(it.stand.z - r.z) < 0.6 && Math.abs(it.stand.y - r.y) < 2.0 } }
        if (out.any { it == null } || out.distinct().size != out.size) { diff.note(run.t, "camp", "the recording's heads do not all match ADS's wall: ADS draws its own order"); return null }
        return out.filterNotNull()
    }
    /** The recording's camp mobs, each with the index of the head it came down with: bodies that first showed up on a head's landing (a tick before to three after it). */
    private val campMobs: Map<Int, Int> by lazy {
        val door = file.events.firstOrNull { it.size() > 2 && it[1].asString == "chat" && it[2].isJsonPrimitive && it[2].asString.startsWith("The BLOOD DOOR has been opened!") }?.get(0)?.asInt
            ?: return@lazy emptyMap()
        val lands = recordedThrows.map { door + it.land }
        val seen = HashSet<Int>(); val out = HashMap<Int, Int>()
        for (e in file.events) {
            if (e.size() < 4 || e[1].asString != "spawn" || !seen.add(e[2].asInt) || e[3].asString == "minecraft:armor_stand") continue
            val k = e[0].asInt
            val i = lands.indexOfFirst { k - it in -1..3 }
            if (i >= 0) out[e[2].asInt] = i
        }
        out
    }

    /**
     * The recording's camp for the Watcher: its throw order and his lines, in ticks after the BLOOD door. The pass line is the camp's
     * own; the two lines after "Things feel a little more roomy now, eh?" are said by the camp on their own clock.
     */
    private fun recordedCampOf(wall: List<com.engineerclient.p3sim.clear.blood.camp.WallStation>): com.engineerclient.p3sim.clear.blood.camp.WatcherCamp.Replayed? {
        val camp = com.engineerclient.p3sim.clear.blood.camp.CampMech
        val lines = com.engineerclient.p3sim.clear.blood.camp.CampLines
        val door = file.events.firstOrNull { it.size() > 2 && it[1].asString == "chat" && it[2].isJsonPrimitive && it[2].asString.startsWith("The BLOOD DOOR has been opened!") }?.get(0)?.asInt
            ?: return null
        val said = ArrayList<Pair<com.engineerclient.p3sim.clear.blood.camp.CampMech.Request, String>>()
        for (e in file.events) {
            if (e.size() < 3 || e[1].asString != "chat" || !e[2].isJsonPrimitive || e[0].asInt < door) continue
            val t = e[2].asString
            val cls = camp.lineOf(t) ?: continue
            val text = t.removePrefix("[BOSS] The Watcher: ")
            if (text in lines.INTRO_OTHER_MORE) continue
            said += com.engineerclient.p3sim.clear.blood.camp.CampMech.Request(e[0].asInt - door, cls) to text
        }
        val opening = said.firstOrNull { it.first.line == com.engineerclient.p3sim.clear.blood.camp.CampMech.Line.INTRO }?.second
        val order = recordedOrder(wall)
        val mobs = recordedCampLooks
        return com.engineerclient.p3sim.clear.blood.camp.WatcherCamp.Replayed(order, camp.requestsOf(said.map { it.first }), opening,
            said.filter { it.first.line != com.engineerclient.p3sim.clear.blood.camp.CampMech.Line.INTRO }.map { it.second },
            throwTicks = order?.let { recordedThrows.map { it.thr } },
            mobs = mobs.map { it?.second }, modifier = mobs.mapNotNull { it?.first }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)
    }

    /**
     * The recording's camp mob of each throw: the modifier and the mob of the tag over the body that came down with it (body id + 1),
     * with the highest HP the tag showed (null: only 0 seen).
     */
    private val recordedCampLooks: List<Pair<String?, com.engineerclient.p3sim.clear.blood.camp.WatcherCamp.Mob>?> by lazy {
        val names = HashSet(com.engineerclient.p3sim.clear.blood.camp.WallPlan.STANDARD + com.engineerclient.p3sim.clear.blood.camp.WallPlan.WALL_BOSSES + "Giant")
        val tags = HashMap<Int, ArrayList<String>>()
        for (e in file.events) if (e.size() > 3 && e[1].asString == "name" && e[3].isJsonPrimitive) tags.getOrPut(e[2].asInt) { ArrayList() } += bareTag(clean(e[3].asString))
        val out = arrayOfNulls<Pair<String?, com.engineerclient.p3sim.clear.blood.camp.WatcherCamp.Mob>>(recordedThrows.size)
        for ((id, i) in campMobs.entries.sortedBy { it.key }) {
            if (i !in out.indices || out[i] != null) continue
            var got: Pair<String?, String>? = null; var hp = 0.0
            for (t in tags[id + 1].orEmpty()) {
                val m = CAMP_TAG.find(t) ?: continue
                var mod: String? = m.groupValues[1].ifEmpty { null }; var name = m.groupValues[2]
                if (name !in names && mod != null && "$mod $name" in names) { name = "$mod $name"; mod = null }
                if (name !in names) continue
                got = mod to name
                hp = maxOf(hp, hpValue(m.groupValues[3]))
            }
            got?.let { out[i] = it.first to com.engineerclient.p3sim.clear.blood.camp.WatcherCamp.Mob(it.second, hp.takeIf { h -> h > 0 }) }
        }
        out.toList()
    }
    private val campSeen = HashSet<UUID>()
    private var campSeeded = false
    /** ADS's camp mobs in the order they came down. */
    private val adsCamp = ArrayList<UUID>()

    /** ADS's camp spawns for [ReplayDiff.campAds]: each mob first seen in the Blood room after ADS's BLOOD door. */
    private fun watchCamp(now: Int) {
        val open = run.state.milestones.bloodOpenT ?: return
        val blood = run.plan.rooms.firstOrNull { it.type == com.engineerclient.p3sim.run.RoomType.BLOOD } ?: return
        // What is there on the first look (the Watcher, if he is a mob) is not a spawn.
        // A dead Parasite's split is not a head the Watcher threw.
        for (m in Services.mobs.inRoom(blood.index)) if (campSeen.add(m.id) && campSeeded && m.kind != com.engineerclient.p3sim.clear.mobs.LevelLooks.PARASITE_ID) { diff.campAds += now - open; adsCamp += m.id }
        campSeeded = true
    }

    /** Your rank bracket and name colour before the replay. */
    private var ownLooks: Pair<Any?, Any?>? = null

    /**
     * Whether the recording's day had Hypixel's blessing boost, from its grant lines (`Granted you +36.3 & +1.18x` boosted, `+29 & +1.15x`
     * plain; Life by its HP multiplier); null when it has none.
     */
    private val recordedBoost: Boolean? by lazy {
        var r: Boolean? = null
        for (e in file.events) if (e.size() > 2 && e[1].asString == "chat" && e[2].isJsonPrimitive) {
            val t = clean(e[2].asString).trim()
            if (!t.startsWith("Granted you")) continue
            if (BOOSTED_GRANT.containsMatchIn(t)) return@lazy true
            if (PLAIN_GRANT.containsMatchIn(t)) r = false
        }
        r
    }

    /**
     * Who killed the Prince: you when Hypixel gave you its line (only the killer gets it), else a teammate (the one whose mod posted
     * "Prince Killed!", or any).
     */
    private val princeKiller: UUID? by lazy {
        var poster: String? = null
        for (e in file.events) if (e.size() > 2 && e[1].asString == "chat" && e[2].isJsonPrimitive) {
            val t = clean(e[2].asString)
            if (t.startsWith("A Prince falls")) return@lazy host.uuid
            if (poster == null && t.endsWith(": Prince Killed!")) poster = t.substringBefore(":").substringAfterLast(' ')
        }
        uuidOf(poster) ?: run.state.players.keys.firstOrNull { it != host.uuid }
    }

    /** Each name's rank in the recording's chat. */
    private val ranks: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        for (e in file.events) if (e.size() > 2 && e[1].asString == "chat" && e[2].isJsonPrimitive) RANKED.find(clean(e[2].asString))?.let { m -> out.putIfAbsent(m.groupValues[2], m.groupValues[1]) }
        out
    }

    fun start() {
        recordedScale.size
        diff.campHypixel = recordedThrows.map { it.land }
        com.engineerclient.p3sim.clear.blood.camp.WatcherCamp.replay = { wall -> if (!ended) recordedCampOf(wall) else null }
        com.engineerclient.p3sim.clear.state.StateController.keysFromRecording = true
        com.engineerclient.p3sim.clear.state.StateController.clearsFromRecording = true
        com.engineerclient.p3sim.clear.secrets.SecretsSettings.replayBoost = recordedBoost
        com.engineerclient.p3sim.clear.mobs.MobsModule.doorScale = { r, room -> if (r === run && !ended) recordedScale[room]?.second ?: seenScale[room] else null }
        // Mobs go where the recording has them while it sees them (out of its sight, ADS moves them).
        com.engineerclient.p3sim.clear.mobs.MobsModule.replayPose = { id -> if (ended) null else plannedBack[id]?.let { ents[it] }?.takeIf { !it.gone && !(it.x == 0.0 && it.z == 0.0) }?.let { doubleArrayOf(it.x, it.y, it.z) } }
        com.engineerclient.p3sim.clear.damage.DamageModule.replayHit = { mob, by -> hitAmount(mob, by) }
        com.engineerclient.p3sim.clear.damage.DamageModule.replayImplosion = { by -> recordedImplosion(by) }
        com.engineerclient.p3sim.clear.damage.ClassAbilities.replayBursts = { p -> !ended && p === host }
        SimItems.replayPearl = { p -> if (!ended && p === host) recordedPearl() else null }
        com.engineerclient.p3sim.clear.puzzles.hard.TpMazePuzzle.replay = { r -> if (r === run && !ended) recordedMazeSteps else null }
        com.engineerclient.p3sim.clear.damage.ClassAbilities.replayUltFirst = { p -> if (!ended && p === host) recordedUltFirst else null }
        // The recorded player's pet: Kill Combo lines only if the recording has them.
        com.engineerclient.p3sim.clear.damage.DamageModule.killCombo = file.events.any { it.size() > 2 && it[1].asString == "chat" && it[2].isJsonPrimitive && it[2].asString.contains("Kill Combo") }
        com.engineerclient.p3sim.clear.secrets.SecretsModule.teamFromRecording = true
        com.engineerclient.p3sim.clear.secrets.SecretsModule.dropsFromRecording = true
        // You go by the recording's name in the party's lines ("is now ready!", "opened a WITHER door!", "has obtained").
        run.state.players[host.uuid]?.let { r ->
            ownName = r.name; r.name = file.self.name
            ownLooks = r.extras["rankBracket"] to r.extras["nameColour"]
            ranks[file.self.name]?.let { rank -> RANK_LOOKS[rank]?.let { (bracket, colour) -> r.extras["rankBracket"] = bracket; r.extras["nameColour"] = colour } }
        }
        // The Watcher opens his camp the way he did in the recording (what picks one opening or the other on Hypixel is not known).
        val intro = file.events.firstOrNull { it.size() > 2 && it[1].asString == "chat" && it[2].isJsonPrimitive && it[2].asString.contains("The Watcher: ") &&
            (it[2].asString.contains(com.engineerclient.p3sim.clear.blood.camp.CampLines.INTRO) || it[2].asString.contains(com.engineerclient.p3sim.clear.blood.camp.CampLines.INTRO_OTHER)) }
        com.engineerclient.p3sim.clear.blood.camp.CampLines.script = HashMap<com.engineerclient.p3sim.clear.blood.camp.CampMech.Line, ArrayDeque<String>>().also { m ->
            for (e in file.events) if (e.size() > 2 && e[1].asString == "chat" && e[2].isJsonPrimitive) {
                val t = clean(e[2].asString).substringAfter("[BOSS] The Watcher: ", "").ifEmpty { null } ?: continue
                com.engineerclient.p3sim.clear.blood.camp.CampLines.classOf(t)?.let { c -> m.getOrPut(c) { ArrayDeque() }.addLast(t) }
            }
        }
        com.engineerclient.p3sim.clear.blood.camp.CampLines.forcedOpening = intro?.get(2)?.asString?.let {
            if (it.contains(com.engineerclient.p3sim.clear.blood.camp.CampLines.INTRO_OTHER)) com.engineerclient.p3sim.clear.blood.camp.CampLines.INTRO_OTHER else com.engineerclient.p3sim.clear.blood.camp.CampLines.INTRO }
        TrapsModule.recordingMovesCrushers = true
        com.engineerclient.p3sim.clear.mobs.MobsModule.roomMobs = { r, room -> if (r === run && !ended) roomPlans[room] else null }
        com.engineerclient.p3sim.clear.puzzles.BlazePuzzle.replayBlazes = { r -> if (r === run && !ended) recordedBlazes else null }
        com.engineerclient.p3sim.clear.puzzles.WeirdosPuzzle.replayRoom = { r, spots -> if (r === run && !ended) recordedWeirdos(spots) else null }
        com.engineerclient.p3sim.clear.puzzles.QuizPuzzle.replay = object : com.engineerclient.p3sim.clear.puzzles.QuizPuzzle.Replay {
            override fun drives(run: Run) = run === this@ReplaySession.run && !ended
            override fun questions(run: Run, start: Int) = if (drives(run)) recordedQuizzes.getOrNull(start) else null
        }
        com.engineerclient.p3sim.clear.secrets.SecretsModule.replayBonusBat = { r -> if (r === run && !ended) 0 else null }
        com.engineerclient.p3sim.clear.secrets.SecretsModule.replayTrapped = { r -> if (r === run && !ended) recordedTrapped else null }
        // The tab refreshes on the recording's tick of the second.
        com.engineerclient.p3sim.clear.hud.HudModule.tabPhase = { r -> if (r === run && !ended) recordedTabPhase?.let { p -> Math.floorMod(p + r.serverTick - r.t, TAB_TICKS) } else null }
        // The Blood room's portal opens on the recording's tick, not on ADS's rolled 80 or 90 after the defeat.
        com.engineerclient.p3sim.clear.blood.BloodModule.portalFromReplay = recordedPortalK != null
        // A Hyperion click casts as Hypixel's did (its ticks against yours decide which of two quick clicks it takes).
        com.engineerclient.p3sim.SimItems.replayCast = { if (!ended) recordedHypeCasts[lastClickK] else null }
        com.engineerclient.p3sim.SimItems.replayStill = { !ended && lastClickK in recordedHypeStill }
        val packed = file.events.filter { it[1].asString == "blk" && it.s(5) == "minecraft:packed_ice" }.map { com.engineerclient.p3sim.pack.rooms.WorldPos(it[2].asInt, it[3].asInt, it[4].asInt) }.distinct()
        com.engineerclient.p3sim.clear.puzzles.IceFillPuzzle.replayPacked = { packed }
        com.engineerclient.p3sim.SimItems.etherwarpRefused = { why -> if (!ended) diff.note(run.t, "etherwarp", "ADS did not warp: $why") }
        diff.note(0, "hyperion", "the recording has ${recordedHypeCasts.size} Hyperion clicks, ${recordedHypeCasts.count { it.value }} cast, ${recordedHypeStill.size} in place inside an entity (${recordedHypeStill.sorted().joinToString()})")
        hurtSub = run.bus.on<com.engineerclient.p3sim.run.PlayerDamaged>{ if (it.player == host.uuid && it.percent > 0.0) hurtAt = run.t }
        // Every secret ADS counts, by whom, against the recording's count: where ADS's total goes past Hypixel's.
        secretSub = run.bus.on<com.engineerclient.p3sim.run.SecretFound> {
            val who = if (it.player == host.uuid) "you" else mateByName.values.firstOrNull { m -> m.uuid == it.player }?.name ?: "?"
            diff.note(run.t, "secret", "${it.kind} at ${it.x} ${it.y} ${it.z} by $who: ADS ${run.state.secrets.teamFound}, recording $recSecrets")
        }
        // A chest whose lid Hypixel opened is not locked by then, whatever ADS's lever (no lever was seen pulled for 2026-10-03_01-27-41's Arrow Trap chest).
        com.engineerclient.p3sim.clear.secrets.SecretsModule.replayOpened = { r, pos -> if (r === run && !ended) recordedChestOpen[pos]?.let { it <= run.t + CHEST_OPEN_LEAD } ?: false else null }
        val rewardChests = ReplayFile.rewardChests(file.events)
        com.engineerclient.p3sim.clear.secrets.SecretsModule.replayChestReward = { r, x, y, z -> if (r === run && !ended) "$x,$y,$z" in rewardChests else null }
        // The teammates are party members (tab, leap window, key pickups by position, the score's deaths).
        for (m in bodies.all()) {
            val rec = run.state.players.getOrPut(m.uuid) { PlayerRecord(m.uuid, m.name, cls(m.cls), true) }
            rec.dungeonClass = cls(m.cls)
            rec.extras[MATE] = true
            // Their rank as the recording's lines show it ("[MVP++] _Cynapse_ has obtained ..."), for ADS's lines about them.
            ranks[m.name]?.let { rank -> RANK_LOOKS[rank]?.let { (bracket, colour) -> rec.extras["rankBracket"] = bracket; rec.extras["nameColour"] = colour } }
        }
        SimNet.send(host, NET, "start ${file.id}")
        diff.note(run.t, "start", "replay ${file.id} (${file.floor}) as ${file.self.cls}; ${file.party.size} teammates; recorded k ${file.firstK}..${file.lastK}, boss at ${file.bossK}")
    }

    fun stop(why: String) {
        if (ended) return
        ended = true
        TrapsModule.recordingMovesCrushers = false
        com.engineerclient.p3sim.clear.secrets.SecretsModule.teamFromRecording = false
        com.engineerclient.p3sim.clear.secrets.SecretsModule.dropsFromRecording = false
        ownName?.let { n -> run.state.players[host.uuid]?.name = n }
        ownLooks?.let { (b, c) -> run.state.players[host.uuid]?.extras?.let { x -> if (b == null) x.remove("rankBracket") else x["rankBracket"] = b; if (c == null) x.remove("nameColour") else x["nameColour"] = c } }
        com.engineerclient.p3sim.clear.blood.camp.CampLines.forcedOpening = null
        com.engineerclient.p3sim.clear.blood.camp.CampLines.script = null
        com.engineerclient.p3sim.clear.mobs.MobsModule.roomMobs = null
        com.engineerclient.p3sim.clear.puzzles.BlazePuzzle.replayBlazes = null
        com.engineerclient.p3sim.clear.puzzles.WeirdosPuzzle.replayRoom = null
        com.engineerclient.p3sim.clear.puzzles.QuizPuzzle.replay = null
        com.engineerclient.p3sim.clear.secrets.SecretsModule.replayBonusBat = null
        com.engineerclient.p3sim.clear.secrets.SecretsModule.replayTrapped = null
        com.engineerclient.p3sim.clear.blood.BloodModule.portalFromReplay = false
        com.engineerclient.p3sim.SimItems.replayCast = null
        com.engineerclient.p3sim.SimItems.replayStill = null
        com.engineerclient.p3sim.SimItems.etherwarpRefused = null
        com.engineerclient.p3sim.clear.puzzles.IceFillPuzzle.replayPacked = null
        com.engineerclient.p3sim.clear.hud.HudModule.tabPhase = null
        hurtSub?.cancel(); hurtSub = null
        secretSub?.cancel(); secretSub = null
        com.engineerclient.p3sim.clear.secrets.SecretsModule.replayOpened = null
        com.engineerclient.p3sim.clear.secrets.SecretsModule.replayChestReward = null
        com.engineerclient.p3sim.clear.mobs.MobsModule.doorScale = null
        com.engineerclient.p3sim.clear.damage.DamageModule.replayHit = null
        com.engineerclient.p3sim.clear.damage.DamageModule.replayImplosion = null
        com.engineerclient.p3sim.clear.damage.ClassAbilities.replayBursts = null
        SimItems.replayPearl = null
        com.engineerclient.p3sim.clear.puzzles.hard.TpMazePuzzle.replay = null
        com.engineerclient.p3sim.clear.damage.ClassAbilities.replayUltFirst = null
        com.engineerclient.p3sim.clear.mobs.MobsModule.replayPose = null
        com.engineerclient.p3sim.clear.blood.camp.WatcherCamp.replay = null
        com.engineerclient.p3sim.clear.state.StateController.keysFromRecording = false
        com.engineerclient.p3sim.clear.state.StateController.clearsFromRecording = false
        com.engineerclient.p3sim.clear.secrets.SecretsSettings.replayBoost = null
        com.engineerclient.p3sim.clear.damage.DamageModule.killCombo = true
        bodies.removeAll()
        for (m in bodies.all()) run.state.players[m.uuid]?.extras?.remove(MATE)
        SimNet.send(host, NET, "stop")
        diff.note(run.t, "stop", why)
        diff.ended(why, run.t, file.bossK, file.lastK, why.startsWith("the recording reached") || why == "ADS reached the boss")
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
        // ADS moving you between ticks (a teleport of its own: a Hyperion, a leap, a pad), against Hypixel's.
        lastHost?.let { l -> if (teleported(l)) diff.adsTeleport(now, host.position()) }
        // Last tick was a tab refresh: the counters as it showed them (the state now is that tick's end).
        recordedTabPhase?.let { p -> if (Math.floorMod(now - 1 - p, TAB_TICKS) == 0) diff.tabRefreshed() }
        while (lastK < now) {
            lastK++
            var pose: JsonArray? = null
            // What the server sent on a tick is applied before what you sent: your packets of the tick reach the server after it sent its own
            // (a teammate's key pickup logged after your click on the door still opens it on Hypixel). Yours wait for the tick's end
            // ([tickEnd]): your client had the tick's world when it sent them, ADS's own changes too (2026-10-03_02-36-19 k17: the entrance
            // barrier went on the tick of a Hyperion click that went through it). Its mob deaths come last: a cast of yours on the tick
            // kills on the tick (DR: an Implosion line on its click's tick, its mobs dead on it), so a death is read after it.
            val all = file.at(lastK)
            val death = { e: JsonArray -> e[1].asString == "eev" && e.size() > 3 && e[3].isJsonPrimitive && e[3].asInt == 3 }
            val yours = { e: JsonArray -> e[1].asString.startsWith("o.") }
            val evs = all.filter { !yours(it) && !death(it) }
            late += all.filter(yours) + all.filter(death)
            // Your packets recorded after the tick's tpme were sent once your client had the teleport; the ones before it were not.
            all.indexOfFirst { it[1].asString == "tpme" }.takeIf { it >= 0 }?.let { i -> afterTp.addAll(all.subList(i, all.size).filter(yours)) }
            for ((i, e) in evs.withIndex()) {
                if (e[1].asString == "me") { pose = e; continue }
                EngineerClient.safely("ads replay ${e[1].asString}") { apply(e, catchUp = false, same = evs, at = i) }
            }
            if (pose != null) { sendPose(pose); recPos = Vec3(pose[2].asDouble, pose[3].asDouble, pose[4].asDouble) }
        }
        // The teammates' party records follow their bodies.
        for (m in bodies.all()) run.state.players[m.uuid]?.let { r -> if (!m.x.isNaN()) { r.x = m.x; r.y = m.y; r.z = m.z; r.extras["yaw"] = m.yaw } }
        bodies.place()
        if (hpChecks.isNotEmpty()) {
            val dueHp = hpChecks.filter { it.first <= now }
            hpChecks.removeAll(dueHp)
            for ((due, id, v) in dueHp) diff.hpScale(due - HP_CHECK_AFTER, file.mobs.firstOrNull { it.id == id }?.tag?.let { mobName(it) } ?: "mob $id", v, planned[id]?.let { Services.mobs.mob(it) }?.maxHp)
        }
        if (bloodOwed.isNotEmpty()) EngineerClient.safely("ads replay camp") { payBlood() }
        watchCamp(now)
        while (cryptMobs.isNotEmpty() && cryptMobs.first().k <= now) {
            val c = cryptMobs.removeFirst()
            Services.mobs.spawn(run, com.engineerclient.p3sim.run.api.MobSpec(c.kind, c.x, c.y, c.z, roomIndex = c.room, hp = c.hp))?.let { r -> planned[c.id] = r.id; plannedBack[r.id] = c.id }
                ?: diff.note(now, "mobs", "the recording's ${c.kind} out of a crypt could not be spawned")
        }
        plating?.let { (k, raw) ->
            if (hurtAt >= k - PLATING_NEAR) { Sim.chat(raw); plating = null }
            else if (now > k + PLATING_NEAR) plating = null
        }
        if (witherDebt > 0 || bloodDebt) repayKeys()
        val due = pending.filter { it.due <= now }
        pending.removeAll(due)
        for (p in due) EngineerClient.safely("ads replay force") {
            if (p.done()) diff.note(now, "ok", p.what)
            else { val r = p.force(); diff.forced(now, p.what, r) }
        }
        if (essenceRetries.isNotEmpty()) { val l = essenceRetries.toList(); essenceRetries.clear(); for ((by, n) in l) mateEssence(by, n) }
        selfDeaths(now)
    }

    /** Your packets of the tick (and its mob deaths, read after them), once every module has had the tick. */
    private val late = ArrayList<JsonArray>()

    /** Your packets of the tick that the recording has after its tpme (by identity). */
    private val afterTp = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<JsonArray, Boolean>())

    fun tickEnd() {
        if (ended) return
        val now = run.t
        // ADS moving you during the tick (a pad of the maze, a leap): before your packets of the tick can take it back.
        lastHost?.let { l -> if (teleported(l)) diff.adsTeleport(now, host.position()) }
        val evs = late.toList(); late.clear()
        for ((i, e) in evs.withIndex()) EngineerClient.safely("ads replay ${e[1].asString}") { apply(e, catchUp = false, same = evs, at = i) }
        afterTp.clear()
        lastHost = host.position()
        // The recorded inventory is your client's after its own clicks of the tick (a placed InfiniLeap head is gone from it): set after them.
        if (invDirty) syncInv()
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
                // Your Guided Sheep appears by you: of the 317 sheep followed 138 to 140 or 298 to 300 ticks on by "now available", 306 within
                // 4.5 blocks across and 0.5 down to 2.1 up of where you were (most 0.78 up); a teammate's mostly elsewhere. Its cooldown and
                // your class rule out the rest.
                if (!catchUp && r.type == "minecraft:sheep" && id in yourSheep) com.engineerclient.p3sim.clear.damage.ClassAbilities.recordedBurst(host)
                mate(id)?.let { m -> m.x = r.x; m.y = r.y; m.z = r.z; m.yaw = e.d(9)?.toFloat() ?: m.yaw; m.present = true }
            }
            "hp" -> e.d(3)?.takeIf { it > 100.0 }?.let { v ->
                val id = e.i(2) ?: return
                val l = liveHp.put(id, v)
                if (!catchUp && k > 0 && l != null && v / l > 1.04 && id in planned) hpChecks += Triple(k + HP_CHECK_AFTER, id, v)
            }
            "emv" -> ents[e.i(2)]?.let { r -> r.x = e.d(3) ?: r.x; r.y = e.d(4) ?: r.y; r.z = e.d(5) ?: r.z }
            "name" -> ents[e.i(2)]?.let { r ->
                r.name = e.s(3)
                val n = clean(r.name ?: "")
                if (!catchUp && (n == "Wither Key" || n == "Blood Key")) { diff.recordedKeyStand(k, n, r.x, r.y, r.z); keyStand(r.x, r.z) }
            }
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
            "eev" -> if (!catchUp && e.i(3) == 3) mobDied(k, e.i(2) ?: return, e.o(4))
            "chat" -> if (!catchUp) chat(k, e.s(2) ?: return)
            "room" -> if (!catchUp) room(k, e)
            "blk" -> if (!catchUp) {
                val x = e.i(2) ?: return; val y = e.i(3) ?: return; val z = e.i(4) ?: return
                if (k == recordedPortalK && !portalSaid) {
                    portalSaid = true
                    if (com.engineerclient.p3sim.clear.blood.BloodModule.portalNow()) diff.forced(run.t, "the Blood room's portal opened (recorded k $k)", "ADS's opens now")
                }
                val you = host.position().distanceTo(Vec3(x + 0.5, y + 0.5, z + 0.5))
                val mate = bodies.all().filter { !it.x.isNaN() && it.present }.minOfOrNull { Math.sqrt((it.x - x - 0.5).let { d -> d * d } + (it.y - y).let { d -> d * d } + (it.z - z - 0.5).let { d -> d * d }) } ?: 999.0
                // A crusher's blocks are the recording's, on their own tick (its pace is checked against the recordings outside the game).
                if (TrapsModule.isCrusherCell(x, y, z)) diff.setNow(x, y, z, e.s(5))
                // Away from you it is a teammate's doing or the room's: put in on its own tick, so the replay looks as the run did.
                else if (k > 1 && you > ReplayDiff.NEAR) diff.awayBlock(k, x, y, z, e.s(5), you, mate)
                // Hypixel's start-of-run updates (k 0-1) dress each room a little differently every run (a design shows all of a set, part or
                // none of it): no fixed room has them, so they are put in on their own tick too.
                else if (k <= 1) diff.awayBlock(k, x, y, z, e.s(5), you, mate, cat = "start")
                else diff.recordedBlock(k, x, y, z, e.s(5), you, mate)
            }
            "screen" -> if (!catchUp && e.s(3) == "inventory") SimNet.send(host, NET, "inv 1")
            // Your health as Hypixel's action bar showed it ("§c8,909/8,909"; gold when over the top), every half second or so.
            "bar" -> if (!catchUp) HEALTH.find(e.s(2) ?: "")?.let { m ->
                val cur = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@let
                val max = m.groupValues[2].replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 } ?: return@let
                run.state.players[host.uuid]?.takeIf { !it.ghost }?.let { it.hpPercent = (cur / max * 100.0).coerceIn(1.0, 100.0) }
            }
            "screenClose" -> if (!catchUp && host.containerMenu === host.inventoryMenu) SimNet.send(host, NET, "inv 0")
            "tpme" -> { tpSeenAt = k; if (!catchUp) diff.recordedTeleport(k, e, recPos) }
            "dung" -> { diff.recordedDung(e.o(2)); if (!catchUp) team(k, e.o(2)) }
            // ---- what you sent
            else -> if (!catchUp && e[1].asString.startsWith("o.")) self(e, same, at)
        }
    }

    private fun mate(id: Int?): ReplayBodies.Mate? = id?.let { mateIdName[it] }?.let { mateByName[it] }

    // ------------------------------------------------------------------ you

    /** The tick of the recording's last tpme, of your last click, and of the click ADS's unaccepted teleport is waiting from (null: none). */
    private var tpSeenAt = Int.MIN_VALUE
    private var lastClickK = Int.MIN_VALUE
    private var tpWaitFrom: Int? = null

    private fun self(e: JsonArray, same: List<JsonArray>, at: Int) {
        if (!run.stage.inDungeon) return
        val c = host.connection
        val before = host.position()
        val kind = e[1].asString
        if (kind == "o.use" || kind == "o.useOn" || kind == "o.slot") diff.action(e[0].asInt, "%-8s %s held %s (slot %d) at %s".format(kind, e.s(2) ?: "", SimItems.idOf(host.mainHandItem) ?: host.mainHandItem.item.toString(), host.inventory.selectedSlot, fmt(host.x, host.y, host.z)))
        injecting = this
        try { selfPacket(e, same, at, c) } finally { injecting = null }
        if (e[1].asString != "o.m" && teleported(before)) diff.adsTeleport(e[0].asInt, host.position())
    }

    private fun selfPacket(e: JsonArray, same: List<JsonArray>, at: Int, c: net.minecraft.server.network.ServerGamePacketListenerImpl) {
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
                // A teleport of ADS's is accepted once the recorded client had one (its tpme, on or after the tick of your first move that
                // found it waiting: a click's, or a pad's ADS made during the tick; a tick after the click for most, DR), or stands where it
                // lands, or after [TP_ACCEPT_MAX] ticks. Before that its moves are from where it
                // stood (the server ignores them) and a second click starts from the landing, as Hypixel's did.
                (c as com.engineerclient.mixin.ServerTeleportAccessor).let { acc ->
                    val w = acc.ec_getAwaitingPosition()
                    if (w == null) tpWaitFrom = null
                    else {
                        val k = e[0].asInt
                        val from = tpWaitFrom ?: k.also { tpWaitFrom = it }
                        val there = x != null && Math.hypot(x - w.x, z!! - w.z) < 1.0 && Math.abs(y!! - w.y) < 1.5
                        // A tpme of this tick counts only for the moves sent after it (2026-10-03_19-21-12 k746: the move before it, from
                        // where you stood, was ignored by Hypixel; applied, it stepped you back a cell and failed the Ice Fill).
                        val seen = if (tpSeenAt == k) e in afterTp else tpSeenAt >= from
                        if (seen || there || k - from >= TP_ACCEPT_MAX) {
                            c.handleAcceptTeleportPacket(net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(acc.ec_getAwaitingTeleport(), w.x, w.y, w.z, host.yRot, host.xRot))
                            tpWaitFrom = null
                        }
                    }
                }
                c.handleMovePlayer(pk)
                if (x != null) diff.moved(e[0].asInt, x, y!!, z!!, host)
            }
            "o.slot" -> c.handleSetCarriedItem(ServerboundSetCarriedItemPacket(e.i(2) ?: return))
            "o.use" -> { lastClickK = e[0].asInt; c.handleUseItem(ServerboundUseItemPacket(hand(e.s(2)), seq++, e.d(3)?.toFloat() ?: host.yRot, e.d(4)?.toFloat() ?: host.xRot)) }
            "o.useOn" -> {
                lastClickK = e[0].asInt
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
                val fromUse = same.subList(0, at).any { it[0].asInt == e[0].asInt && it[1].asString in RIGHT_CLICKS }
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
        // A leap to a teammate the recording could not see (out of range, so it has no place for them): they were where Hypixel put you.
        name?.let { mateByName[it] }?.let { m -> leapLanding(e[0].asInt)?.let { at -> m.x = at.x; m.y = at.y; m.z = at.z; run.state.players[m.uuid]?.let { r -> r.x = at.x; r.y = at.y; r.z = at.z } } }
        val input = runCatching { ContainerInput.valueOf(e.s(5)!!) }.getOrNull() ?: ContainerInput.PICKUP
        host.connection.handleContainerClick(ServerboundContainerClickPacket(menu.containerId, menu.stateId, use.toShort(), (e.i(4) ?: 0).toByte(), input, Int2ObjectOpenHashMap(), HashedStack.EMPTY))
    }

    /** Where Hypixel put you within a few ticks after [k] (a leap's landing), or null. */
    private fun leapLanding(k: Int): Vec3? = (k..k + 15).firstNotNullOfOrNull { kk -> file.at(kk).firstOrNull { it[1].asString == "tpme" }?.get(2)?.takeIf { it.isJsonArray }?.asJsonArray?.let { Vec3(it[0].asDouble, it[1].asDouble, it[2].asDouble) } }

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
        // Never an item, an orb or an arrow: the server drops a player who attacks one ("Attempting to attack an invalid entity").
        val all = Sim.level.getEntities(null as Entity?, box) { it !== host && it.isAlive && it !is ServerPlayer && it !is net.minecraft.world.entity.item.ItemEntity &&
            it !is net.minecraft.world.entity.ExperienceOrb && it !is net.minecraft.world.entity.projectile.arrow.AbstractArrow }
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

    /** ADS mobs already matched to a recorded death (one recorded mob, one ADS mob). */
    private val claimed = HashSet<UUID>()
    private var bloodKeyBy: String? = null

    /** A recorded camp death: [head] is the index of the recorded head the mob came down with (-1: not known). */
    private class BloodDeath(val k: Int, val label: String, val x: Double, val z: Double, val room: Int, val head: Int = -1)
    /** Recorded camp deaths ADS has no camp mob for yet. */
    private val bloodOwed = ArrayDeque<BloodDeath>()
    private var lastBlood: BloodDeath? = null
    /** The recorded heads whose mob has died. */
    private val campDied = HashSet<Int>()

    /**
     * Kills ADS's camp mobs for the recorded camp deaths owed. ADS throws the recorded heads in the recorded order, so a death of head n
     * is ADS's n-th camp mob's (already dead in ADS: nothing to do; not down yet: owed until it is). Without the recording's heads, the
     * live camp mob nearest where the recorded one died, oldest death first.
     */
    private fun payBlood() {
        watchCamp(run.t)
        val it = bloodOwed.iterator()
        while (it.hasNext()) {
            val d = it.next()
            val mob = if (d.head >= 0) {
                val id = adsCamp.getOrNull(d.head) ?: continue
                it.remove()
                Services.mobs.mob(id)?.takeIf { m -> m.alive } ?: continue
            } else {
                val m = Services.mobs.inRoom(d.room).filter { m -> m.alive && m.id !in claimed }
                    .minByOrNull { m -> Math.hypot(m.x - d.x, m.z - d.z) + (if (sameMob(m.name, d.label)) 0.0 else 12.0) } ?: return
                it.remove(); m
            }
            claimed += mob.id
            val now = run.t
            forcing = true
            try { Services.damage.hurtMob(run, mob.id, mob.hp + 1e12, DamageSource.OTHER, nearestMate(d.x, d.z)) } finally { forcing = false }
            diff.forced(now, "camp: recorded ${d.label} death (k ${d.k}) on ADS's ${mob.name}${if (now > d.k) ", ${now - d.k} ticks late" else ""}", if (mob.alive) "hurtMob left it alive" else "killed")
        }
    }

    /**
     * A recorded mob died: ADS's mob for it is the one of the same name (the nametag over the recorded body) nearest where it died,
     * not matched before. Dead already in ADS: fine (you or ADS killed it). Alive: it gets [KILL_GRACE] ticks near you (none far from you), then it is killed for the
     * teammate nearest it (a death near you that ADS did not do is ADS's: [ReplayDiff] tells them apart).
     */
    private fun mobDied(k: Int, id: Int, brief: JsonObject?) {
        if (mateIdName.containsKey(id)) return
        val r = ents[id] ?: return
        if (r.type == null || r.type == "minecraft:armor_stand") return
        val rx = r.x; val ry = r.y; val rz = r.z
        // The recorder writes "???" (or "MINIBOSS") when it had not read the tag: the tag stand's own name (body id + 1, a Withermancer's + 3) is the mob's.
        // A tag with no HP is another stand's it read instead (a damage number, a Blessing, the Wither Key, a Watcher line): not read either.
        val briefTag = brief?.get("tag")?.takeIf { it.isJsonPrimitive }?.asString
        val tag = briefTag?.takeIf { it !in UNREAD_TAGS && it.contains("❤") }?.let { mobName(it) }
            ?: standTag(id)?.let { mobName(it) }
            ?: briefTag?.let { if (it.contains("❤")) it else "???" }
        // The Watcher's camp names its mobs by itself, so a recorded camp death takes ADS's nearest live camp mob whatever its name;
        // with none alive yet (ADS's camp behind Hypixel's), the next one ADS throws.
        val blood = run.plan.roomAtBlock(rx, rz)?.takeIf { it.type == com.engineerclient.p3sim.run.RoomType.BLOOD }
        val landing = campMobs[id]
        // A camp mob's name (a prefix word or not) is one wherever it died: one that walked out of the Blood room, or one the recorder only
        // saw once it came into view (2026-10-03_02-56-44: 8 of the camp's throws seen, a Boomer Scarf dying 34 blocks from you).
        val campName = tag != null && CAMP_NAMES.any { tag == it || tag.endsWith(" $it") }
        val campRoom = blood ?: run.plan.rooms.firstOrNull { it.type == com.engineerclient.p3sim.run.RoomType.BLOOD }
        if (campRoom != null && landing != null && (blood != null || campName)) {
            // A camp mob is whatever came down with its head, tagged or not (the Giant's body has no tag); one death per head (a rider's
            // two bodies are one mob).
            if (!campDied.add(landing)) return
            BloodDeath(k, tag ?: "camp mob", rx, rz, campRoom.index, landing).also { lastBlood = it; bloodOwed += it }
            payBlood(); return
        }
        if (campRoom != null && campName && recordedThrows.isNotEmpty()) {
            if (lastBlood?.let { it.label == tag && k - it.k <= 1 && Math.hypot(it.x - rx, it.z - rz) < 2 } == true) return
            BloodDeath(k, tag!!, rx, rz, campRoom.index).also { lastBlood = it; bloodOwed += it }
            payBlood(); return
        }
        if (r.type == "minecraft:bat") { batDied(k, rx, ry, rz); return }
        if (r.type == "minecraft:blaze") {
            val hp = brief?.get("tag")?.takeIf { it.isJsonPrimitive }?.asString?.let { BLAZE_TAG.find(clean(it)) }?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            if (hp != null) { blazeDied(k, hp, rx, ry, rz); return }
        }
        // Only Hypixel's mobs have a tag (a bare body is a part of one: a Giant's, a disguise's).
        if (tag.isNullOrEmpty()) return
        val label = tag
        // Without the recording's throws, any tagged death in the Blood room is a camp death.
        if (blood != null && tag != null && recordedThrows.isEmpty()) {
            // One mob can die as two tagged bodies (a rider): the same name within a tick and two blocks is one death.
            if (lastBlood?.let { it.label == label && k - it.k <= 1 && Math.hypot(it.x - rx, it.z - rz) < 2 } == true) return
            BloodDeath(k, label, rx, rz, blood.index).also { lastBlood = it; bloodOwed += it }
            payBlood(); return
        }
        // The Mimic is the trapped chest's zombie, not one of the mob service's: ADS's is out (fine), or its chest opens now.
        if (label == "Mimic" || label.endsWith(" Mimic")) {
            when (com.engineerclient.p3sim.clear.secrets.SecretsModule.mimicOut(run, nearestMate(rx, rz) ?: host.uuid)) {
                true -> diff.mobOk()
                false -> diff.forced(run.t, "the recording's Mimic died (k $k)", "ADS's trapped chest opened")
                null -> diff.mobNotFound(k, label, rx, ry, rz, Math.hypot(host.x - rx, host.z - rz), "ADS has no trapped chest; its chests near: " +
                    com.engineerclient.p3sim.clear.secrets.SecretsModule.chestSitesNear(run, rx, ry, rz))
            }
            return
        }
        val cands = Services.mobs.all().filter { it.id !in claimed && Math.abs(it.y - ry) < 8 && Math.hypot(it.x - rx, it.z - rz) < 14 }
        // A tag the recorder never read ("???", or a damage number over it): no name to go by, so ADS's nearest mob with the same kind of
        // body (a fake player or not) within the 14 blocks.
        val unread = label in UNREAD_TAGS || label.none { it.isLetter() }
        val mob = if (unread) cands.filter { it.alive && it.disguised == (r.type == "minecraft:player") }.minByOrNull { m -> Math.hypot(m.x - rx, m.z - rz) }
        else cands.minByOrNull { m -> Math.hypot(m.x - rx, m.z - rz) + (if (sameMob(m.name, label)) 0.0 else 12.0) + (if (m.alive) 0.0 else 1.0) }
            ?.takeIf { sameMob(it.name, label) || Math.hypot(it.x - rx, it.z - rz) < 4 }
        val you = Math.hypot(host.x - rx, host.z - rz)
        // The recording's second death of one mob (its body came back under another id): ADS's mob of the name, matched already, where it died.
        if (mob == null && !unread && Services.mobs.all().any { it.id in claimed && sameMob(it.name, label) && Math.hypot(it.x - rx, it.z - rz) < 2.0 && Math.abs(it.y - ry) < 2.0 }) {
            diff.note(k, "mob", "recorded $label at ${fmt(rx, ry, rz)} died again (ADS's matched it already)"); return
        }
        if (mob == null) {
            // Why: ADS's nearest mob of that name anywhere (how far, and whether it is dead or matched already).
            val near = Services.mobs.all().filter { unread || sameMob(it.name, label) }.minByOrNull { Math.hypot(it.x - rx, it.z - rz) }
            val why = near?.let { "ADS's nearest %s %.0f blocks off (dy %.0f)%s%s".format(it.name, Math.hypot(it.x - rx, it.z - rz), it.y - ry, if (it.alive) "" else ", dead", if (it.id in claimed) ", matched already" else "") }
                ?: "ADS has none"
            diff.mobNotFound(k, label, rx, ry, rz, you, why); return
        }
        claimed += mob.id
        if (!unread && !sameMob(mob.name, label)) diff.note(k, "mob", "recorded $label at ${fmt(rx, ry, rz)} matched to ADS's ${mob.name} (no $label within 14)")
        if (!mob.alive) { diff.mobOk(); return }
        val who = nearestMate(rx, rz)
        // Yours only when you hit or used something just before (an arrow flies a few ticks); else a teammate's, done on its tick.
        val yours = you <= FAR && youActed(k)
        if (yours) diff.mobPending(k, label, you, "ADS %s %,.0f/%,.0f hp%s, %.1f blocks from where it died".format(mob.name, mob.hp, mob.maxHp, if (Services.mobs.isAsleep(mob.id)) " asleep" else "", Math.hypot(mob.x - rx, mob.z - rz)))
        // Far from you it was a teammate's kill: ADS cannot make it, so it is done on the tick.
        pending += Pending(if (!yours) k else k + KILL_GRACE, "$label killed (you ${"%.0f".format(you)} blocks away)", { !mob.alive }) {
            forcing = true
            try { Services.damage.hurtMob(run, mob.id, mob.hp + 1e12, DamageSource.OTHER, who) } finally { forcing = false }
            if (mob.alive) "hurtMob left it alive" else "killed by ${who?.let { run.state.players[it]?.name } ?: "?"}"
        }
    }

    /** The name over body [id]: its tag stand's at id + 1, or a Withermancer's at id + 3 behind its two nameless stands. */
    private fun standTag(id: Int): String? {
        ents[id + 1]?.name?.takeIf { it.contains("❤") }?.let { return it }
        val fillers = (1..2).all { d -> ents[id + d]?.let { it.type == "minecraft:armor_stand" && it.name.isNullOrEmpty() } == true }
        return if (fillers) ents[id + 3]?.name?.takeIf { it.contains("❤") } else null
    }

    /**
     * A recorded Higher Or Lower blaze died: ADS's blaze of the same HP in that room ([BlazePuzzle] takes the recorded HPs). Yours (you hit
     * or used something just before, near it) gets [KILL_GRACE] ticks; a teammate's is done on its tick.
     */
    private fun blazeDied(k: Int, hp: Int, rx: Double, ry: Double, rz: Double) {
        val label = "Blaze %,d".format(hp)
        val you = Math.hypot(host.x - rx, host.z - rz)
        val pz = run.plan.roomAtBlock(rx, rz)?.let { PuzzlesModule.puzzleOf(run, it.index) } as? com.engineerclient.p3sim.clear.puzzles.BlazePuzzle
        if (pz == null) { diff.mobNotFound(k, label, rx, ry, rz, you); return }
        val yours = you <= FAR && youActed(k)
        if (!yours && pz.awake && !pz.standing(hp)) { diff.mobOk(); return }
        if (yours) diff.mobPending(k, label, you, "ADS blaze %,d %s".format(hp, if (pz.standing(hp)) "standing" else "not standing"))
        val who = nearestMate(rx, rz)
        pending += Pending(if (!yours) k else k + KILL_GRACE, "$label killed (you ${"%.0f".format(you)} blocks away)", { pz.awake && !pz.standing(hp) }) {
            if (pz.killRecorded(hp, who)) "killed by ${who?.let { run.state.players[it]?.name } ?: "?"}" else "no ADS blaze of $hp standing"
        }
    }

    /** Where the recording's bats died (k, x, z), so their secrets are kept for their kills ([creditSecrets]). */
    private val batDeaths: List<Triple<Int, Double, Double>> by lazy {
        val type = HashMap<Int, String>(); val at = HashMap<Int, DoubleArray>()
        val out = ArrayList<Triple<Int, Double, Double>>()
        for (e in file.events) when (e.s(1)) {
            "spawn" -> { val id = e.i(2) ?: continue; e.s(3)?.let { type[id] = it }; at[id] = doubleArrayOf(e.d(5) ?: continue, e.d(7) ?: continue) }
            "emv" -> { val id = e.i(2) ?: continue; at[id] = doubleArrayOf(e.d(3) ?: continue, e.d(5) ?: continue) }
            "eev" -> { val id = e.i(2) ?: continue; if (e.i(3) == 3 && type[id] == "minecraft:bat") at[id]?.let { out += Triple(e[0].asInt, it[0], it[1]) } }
        }
        out
    }

    /**
     * Which of the recording's bat deaths carried the bonus point, counted from 1, or 0 for none in sight. The dungeon state first says `bat`
     * 0 to 3 ticks after that bat's death (50 of 50 DR runs where it died in view); in the 65 runs where it died out of sight there is no
     * such death, and the bonus is given when the state says it instead ([team]). The first state is the run before's: only those after T0 count.
     */
    /**
     * Your recorded "A Bat has been slain." (only the bat's killer gets it): the bonus is yours on its tick. Without it the bonus bat was a
     * teammate's, given silently when the recorded state says so. ADS's count of bats never decides it in a replay.
     */
    private val yourBatLine: Int? by lazy {
        file.events.firstOrNull { it.size() > 2 && it[1].asString == "chat" && it[2].isJsonPrimitive && clean(it[2].asString).startsWith("A Bat has been slain.") }?.get(0)?.asInt
    }


    /** Whether the recording has a bat die in [room] after now. */
    private fun batLater(room: Int): Boolean = batDeaths.any { (k, x, z) -> k > run.t && run.plan.roomAtBlock(x, z)?.index == room }

    /**
     * A recorded bat died. Every bat of a clear is a bat secret, so ADS's nearest bat secret of that room not found yet is found by whoever
     * killed it: yours (you hit or used something just before, near it) gets [KILL_GRACE] ticks to do it yourself, a teammate's is done on its tick.
     */
    private fun batDied(k: Int, rx: Double, ry: Double, rz: Double) {
        val you = Math.hypot(host.x - rx, host.z - rz)
        val room = run.plan.roomAtBlock(rx, rz) ?: return diff.mobNotFound(k, "Bat", rx, ry, rz, you)
        fun open() = com.engineerclient.p3sim.clear.secrets.SecretsModule.pendingSecrets(run, room.index).filter { it.kind == com.engineerclient.p3sim.run.SecretKind.BAT }
        val site = open().minByOrNull { Math.hypot(it.x + 0.5 - rx, it.z + 0.5 - rz) }
        if (site == null) {
            // Found already in ADS (by you, or credited) is fine; a room without a bat secret is a difference.
            if (com.engineerclient.p3sim.clear.secrets.SecretsModule.hasBat(run, room.index)) diff.mobOk() else diff.mobNotFound(k, "Bat", rx, ry, rz, you)
            return
        }
        val yours = you <= FAR && youActed(k)
        if (yours) diff.mobPending(k, "Bat", you, "ADS bat secret at ${site.x} ${site.y} ${site.z}")
        val who = if (yours) host.uuid else nearestMate(rx, rz)
        pending += Pending(if (!yours) k else k + KILL_GRACE, "Bat killed (you ${"%.0f".format(you)} blocks away)", { open().none { it.x == site.x && it.y == site.y && it.z == site.z } }) {
            if (Services.secrets.found(run, room.index, site.kind, who, site.x, site.y, site.z)) "found by ${who?.let { run.state.players[it]?.name } ?: "?"}" else "not found"
        }
    }

    // ------------------------------------------------------------------ the team's score parts

    private var recSecrets = 0; private var recCrypts = 0; private var recMimic = false; private var recPrince = false; private var recBat = false

    /** The first tick the recording marks each room green. */
    private val greenAt: Map<Int, Int> by lazy {
        val m = HashMap<Int, Int>()
        for (e in file.events) if (e.size() > 5 && e[1].asString == "room" && e.s(3) == "GREEN") {
            val t0 = e.get(5).takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.asJsonArray ?: continue
            val r = run.plan.roomAt(t0[0].asInt, t0[1].asInt) ?: continue
            m.putIfAbsent(r.index, e[0].asInt)
        }
        m
    }

    /**
     * The party's secrets, crypts, Mimic and Prince as the recording counted them: what ADS has not counted [TEAM_GRACE] ticks later
     * (a teammate's find, which nothing else in a replay makes) is credited then, in the room a teammate stands in or the one the
     * recording turns green next. Every credit is listed with the forced work.
     */
    private fun team(k: Int, o: JsonObject?) {
        if (o == null || k <= 0) return
        val led = run.state.secrets
        o.get("secretsFound")?.takeIf { it.isJsonPrimitive }?.asInt?.let { s -> if (s > recSecrets) { recSecrets = s
            pending += Pending(k + TEAM_GRACE, "secrets to $s", { led.teamFound >= s }) { val n = creditSecrets(s); "credited $n, ADS now ${led.teamFound}" } } }
        o.get("crypts")?.takeIf { it.isJsonPrimitive }?.asInt?.let { c -> if (c > recCrypts) { recCrypts = c
            pending += Pending(k + TEAM_GRACE, "crypts to $c", { led.crypts >= c }) { val n = creditCrypts(c); "blew $n, ADS now ${led.crypts}" } } }
        if (o.get("mimic")?.asBoolean == true && !recMimic) { recMimic = true
            pending += Pending(k + TEAM_GRACE, "mimic killed", { led.mimicKilled }) { Services.secrets.bonus(run, com.engineerclient.p3sim.run.BonusKind.MIMIC, null); "ADS mimic ${led.mimicKilled}" } }
        // A teammate's bonus bat, given now and silently (yours comes with your line: [yourBatLine]).
        if (o.get("bat")?.asBoolean == true && !recBat) { recBat = true
            if (yourBatLine == null) pending += Pending(k, "bonus bat", { led.batKilled }) { Services.secrets.bonus(run, com.engineerclient.p3sim.run.BonusKind.BAT, null); "ADS bat ${led.batKilled}" } }
        if (o.get("prince")?.asBoolean == true && !recPrince) { recPrince = true
            pending += Pending(k + TEAM_GRACE, "prince killed", { led.princeKilled }) { Services.secrets.bonus(run, com.engineerclient.p3sim.run.BonusKind.PRINCE, princeKiller); "ADS prince ${led.princeKilled}" } }
    }

    /** The rooms to credit a teammate's find in, best first: where teammates stand (nearest first), then by the recording's next green mark. */
    private fun teamRooms(): List<Int> {
        val now = run.t
        val withMate = bodies.all().filter { !it.x.isNaN() && it.present }.mapNotNull { run.state.roomAt(it.x, it.z)?.index }
        val rest = run.plan.rooms.map { it.index }.filter { it !in withMate }
            .sortedBy { r -> greenAt[r]?.let { g -> if (g >= now - TEAM_GRACE) g else Int.MAX_VALUE / 2 + g } ?: Int.MAX_VALUE }
        return withMate.distinct() + rest
    }

    private fun mateIn(room: Int): UUID? = bodies.all().firstOrNull { !it.x.isNaN() && it.present && run.state.roomAt(it.x, it.z)?.index == room }?.uuid
        ?: bodies.all().firstOrNull()?.uuid

    /**
     * Credits the team's secrets up to [want]. A room's last secret is held back while the recording has not marked that room green
     * (by [TEAM_GRACE] from now): a room Hypixel left white keeps one unfound. Only when no other room has one left does a second pass take it.
     */
    private fun creditSecrets(want: Int): Int {
        var n = 0
        for (strict in listOf(true, false)) for (room in teamRooms()) {
            for (site in com.engineerclient.p3sim.clear.secrets.SecretsModule.pendingSecrets(run, room)) {
                if (run.state.secrets.teamFound >= want) return n
                if (strict && com.engineerclient.p3sim.clear.secrets.SecretsModule.pendingSecrets(run, room).size <= 1 && (greenAt[room] ?: Int.MAX_VALUE) > run.t + TEAM_GRACE) continue
                // A teammate's Wither Essence is always announced: theirs come from the recording's lines, on their tick ([mateEssence]).
                if (site.kind == com.engineerclient.p3sim.run.SecretKind.ESSENCE) continue
                // A bat the recording kills later is found on its tick ([batDied]).
                if (site.kind == com.engineerclient.p3sim.run.SecretKind.BAT && batLater(room)) continue
                // A chest or skull you click later in the recording is yours to find then, not a teammate's now.
                if ((yourClicks[BlockPos.asLong(site.x, site.y, site.z)] ?: Int.MIN_VALUE) > run.t) continue
                if (Services.secrets.found(run, room, site.kind, mateIn(room), site.x, site.y, site.z)) n++
            }
            if (run.state.secrets.teamFound >= want) return n
        }
        return n
    }

    private fun creditCrypts(want: Int): Int {
        var n = 0
        for (room in teamRooms()) for (p in com.engineerclient.p3sim.clear.secrets.SecretsModule.standingCrypts(run, room)) {
            if (run.state.secrets.crypts >= want) return n
            Services.secrets.cryptDestroyed(run, room, mateIn(room), p.x, p.y, p.z); n++
        }
        return n
    }

    private fun nearestMate(x: Double, z: Double): UUID? =
        (bodies.all().filter { !it.x.isNaN() && it.present }.map { it.uuid to Math.hypot(it.x - x, it.z - z) } + (host.uuid to Math.hypot(host.x - x, host.z - z)))
            .minByOrNull { it.second }?.first

    /** The recording's key stand showed up: ADS drops the room's key there (keys never drop on ADS's own kills in a replay). */
    private fun keyStand(x: Double, z: Double) {
        val room = run.plan.roomAtBlock(x, z)?.index ?: return
        if (Services.doors.keyForRoom(run, room) != null) Services.doors.dropKey(run, room, x, z)
    }

    /** The recording says [name] took a [kind] key ("Wither" or "Blood"): they take ADS's, on this tick. */
    private fun keyTaken(name: String, kind: String) {
        val by = run.state.players.entries.firstOrNull { it.value.name == name }?.key ?: return
        val p = run.state.players[by] ?: return
        val k = if (kind == "Blood") com.engineerclient.p3sim.run.KeyKind.BLOOD else com.engineerclient.p3sim.run.KeyKind.WITHER
        if (!Services.doors.pickUpKey(run, by, k, run.plan.roomAtBlock(p.x, p.z)?.index, p.x, p.z)) diff.note(run.t, "keys", "$name took a $kind Key, ADS had none to give")
    }

    private fun chat(k: Int, raw: String) {
        val t = clean(raw)
        diff.recordedChat(k, t)
        WITHER_OPENED.find(t)?.let { m -> door(k, DoorKind.WITHER, m.groupValues[1]); return }
        if (t.startsWith("The BLOOD DOOR has been opened!")) { door(k, DoorKind.BLOOD, null); return }
        // His camp out of the recorded player's sight has no throws or kills to replay: the pass line says when it ended.
        if (t == "[BOSS] The Watcher: " + com.engineerclient.p3sim.clear.blood.WatcherLines.PASS && com.engineerclient.p3sim.clear.blood.BloodModule.passNow()) diff.forced(run.t, "the Watcher let the party pass (recorded k $k)", "ADS's camp had not ended")
        if (t.startsWith("Party >")) return
        // A Crypt Wither Skull's hit on you (DR: 53 in the clear, every one 23,760, an explosion at you of volume 0.7): what fires it is not
        // known from the recordings, so the hit is the recording's (the line, the bone plating line of its tick, the sound; your HP
        // follows the action bar as ever).
        if (t.startsWith(SKULL_HIT) || (t.startsWith(BONE_PLATING) && k in skullHitTicks)) {
            Sim.chat(raw)
            if (t.startsWith(SKULL_HIT)) {
                Sim.sound(net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE, SKULL_HIT_VOLUME, 1f, host.position().add(0.0, 0.6, 0.0), net.minecraft.sounds.SoundSource.BLOCKS)
                diff.forced(run.t, "Crypt Wither Skull hit you (recorded k $k)", "its line and sound")
            }
            return
        }
        // Your armour's bone plating (DR: one line every 300 ticks or more, the first hit after): the recording's line, when ADS hurt you too.
        if (t.startsWith(BONE_PLATING)) { plating = k to raw; return }
        // A Prince's bonus point (ADS's Prince is the recording's, its point the line's).
        if (t.startsWith("A Prince falls.")) {
            pending += Pending(k, "the Prince's bonus", { run.state.secrets.princeKilled }) { Services.secrets.bonus(run, com.engineerclient.p3sim.run.BonusKind.PRINCE, host.uuid); "ADS prince ${run.state.secrets.princeKilled}" }
            return
        }
        if (t.startsWith("A Bat has been slain.") && k == yourBatLine) {
            pending += Pending(k, "your bonus bat", { run.state.secrets.batKilled }) { Services.secrets.bonus(run, com.engineerclient.p3sim.run.BonusKind.BAT, host.uuid); "ADS bat ${run.state.secrets.batKilled}" }
            return
        }
        KEY.find(t)?.let { m -> diff.recordedKeyPick(k, m.groupValues[2] + " Key", m.groupValues[1]); if (m.groupValues[2] == "Blood") bloodKeyBy = m.groupValues[1]; keyTaken(m.groupValues[1], m.groupValues[2]); return }
        SOLVED.find(t)?.let { m -> puzzleSolved(k, m.groupValues[1], m.groupValues[2]) }
        QUIZ_ANSWER.find(t)?.let { m -> quizAnswer(m.groupValues[1], m.groupValues[2] == "answered") }
        FAILED.find(t)?.let { m -> puzzleFailed(k, m.groupValues[1], m.groupValues[2] + if (m.groupValues[3] == "Yikes!") "" else " " + m.groupValues[3]) }
        DIED.find(t)?.let { m -> death(k, m.groupValues[1]); return }
        REVIVED.find(t)?.let { m -> revive(k, m.groupValues[1], m.groupValues[2]); return }
        drop(k, t)
    }

    /** The recording's drops (blessings, Superboom TNT, Revive Stones, chest essence), done by the secrets module on their tick. */
    private fun drop(k: Int, t: String) {
        val sec = com.engineerclient.p3sim.clear.secrets.SecretsModule
        BUFF.find(t)?.let { m ->
            val b = com.engineerclient.p3sim.clear.secrets.Blessing.entries.firstOrNull { it.displayName == m.groupValues[3] } ?: return
            val level = ROMAN.indexOf(m.groupValues[4]) + 1
            val finder = if (m.groupValues[1] == "A") null else uuidOf(m.groupValues[2]) ?: return
            val obtained = file.at(k).any { it.size() > 2 && it[1].asString == "chat" && it.s(2)?.let { l -> clean(l).endsWith("has obtained Blessing of ${b.displayName}!") } == true }
            sec.recordedBlessing(run, finder, b, level, m.groupValues[5].isNotEmpty(), obtained)
            return
        }
        ITEM.find(t)?.let { m ->
            val item = com.engineerclient.p3sim.clear.secrets.LootItem.entries.firstOrNull { it.displayName == m.groupValues[2] } ?: return
            sec.recordedItem(run, uuidOf(m.groupValues[1]) ?: return, item, m.groupValues[3].toIntOrNull() ?: 1)
            return
        }
        WITHER_ESSENCE.find(t)?.let { m -> uuidOf(m.groupValues[1])?.takeIf { it != host.uuid }?.let { mateEssence(it) }; return }
        ESSENCE.find(t)?.let { m -> sec.recordedEssence(run, uuidOf(m.groupValues[1]) ?: return, m.groupValues[3], m.groupValues[2].toIntOrNull() ?: return) }
    }

    /** The ticks you attacked or used an item (main hand) in the recording. */
    private val yourActs: IntArray by lazy {
        // The hotbar as the recording had it, to know what each click held: a click with an item that hurts nothing (an AOTV, a leap) is no kill.
        val hotbar = arrayOfNulls<String>(9); var held = 0
        val out = ArrayList<Int>()
        for (e in file.events) {
            if (e.size() < 3) continue
            when (e[1].asString) {
                "o.slot" -> held = e.i(2)?.takeIf { it in 0..8 } ?: held
                "inv" -> e.get(3).takeIf { it.isJsonArray }?.asJsonArray?.forEach { s ->
                    val a = s.asJsonArray; val slot = a[0].asInt
                    if (slot in 0..8) hotbar[slot] = a[1].takeIf { it.isJsonObject }?.asJsonObject?.get("sb")?.takeIf { it.isJsonPrimitive }?.asString
                }
                // A hit on a teammate hurts no mob (02-36-19 k120: three mobs a mate killed by you while you hit him).
                "o.atk" -> if (e.o(2)?.get("mate")?.takeIf { it.isJsonPrimitive } == null) out += e[0].asInt
                "o.use", "o.useOn" -> if (e.s(2) == "MAIN_HAND" && hotbar[held].let { it == null || it !in HARMLESS }) out += e[0].asInt
            }
        }
        out.toIntArray().also { it.sort() }
    }

    /** Whether you attacked or used an item in the [YOURS_WITHIN] ticks up to [k]. */
    private fun youActed(k: Int): Boolean {
        var i = java.util.Arrays.binarySearch(yourActs, k).let { if (it < 0) -it - 2 else it }
        while (i + 1 < yourActs.size && yourActs[i + 1] <= k) i++
        return i >= 0 && k - yourActs[i] <= YOURS_WITHIN
    }

    /** Each block you right-click in the recording, with the last tick you do. */
    private val yourClicks: Map<Long, Int> by lazy {
        val out = HashMap<Long, Int>()
        for (e in file.events) if (e.size() > 3 && e[1].asString == "o.useOn" && e[3].isJsonArray) e[3].asJsonArray.let { p -> out[BlockPos.asLong(p[0].asInt, p[1].asInt, p[2].asInt)] = e[0].asInt }
        out
    }

    /**
     * A teammate took a Wither Essence: ADS's nearest one still lying is theirs now. Its room may not be ADS's yet on the line's tick (its
     * discovery comes later in the tick): it is tried again each tick, up to [ESSENCE_RETRY].
     */
    private fun mateEssence(by: UUID, tries: Int = ESSENCE_RETRY) {
        if (!essenceOnce(by, last = tries <= 1) && tries > 1) essenceRetries += by to tries - 1
    }
    private val essenceRetries = ArrayList<Pair<UUID, Int>>()

    private fun essenceOnce(by: UUID, last: Boolean): Boolean {
        val at = posOf(by) ?: return false
        var best: Pair<Int, com.engineerclient.p3sim.clear.secrets.SecretSite>? = null
        var bestD = Double.MAX_VALUE
        for (room in run.state.rooms.indices) for (site in com.engineerclient.p3sim.clear.secrets.SecretsModule.pendingSecrets(run, room)) {
            if (site.kind != com.engineerclient.p3sim.run.SecretKind.ESSENCE) continue
            val d = Math.hypot(site.x + 0.5 - at.x, site.z + 0.5 - at.z)
            if (d < bestD) { bestD = d; best = room to site }
        }
        val (room, site) = best ?: return false.also { if (last) diff.note(run.t, "secrets", "a teammate's Wither Essence: ADS has none lying") }
        return Services.secrets.found(run, room, site.kind, by, site.x, site.y, site.z)
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

    /** Keys lent to open a recorded door before ADS had one, owed back from ADS's next key. */
    private var witherDebt = 0
    private var bloodDebt = false

    private fun repayKeys() {
        val keys = run.state.keys
        while (witherDebt > 0 && keys.witherHeld > 0) { keys.witherHeld--; witherDebt--; diff.note(run.t, "keys", "ADS's Wither Key paid back the one lent") }
        if (bloodDebt && keys.bloodHeld) { keys.bloodHeld = false; bloodDebt = false; diff.note(run.t, "keys", "ADS's Blood Key paid back the one lent") }
    }

    private fun door(k: Int, kind: DoorKind, who: String?) {
        val by = uuidOf(who ?: if (kind == DoorKind.BLOOD) bloodKeyBy else null)
        val at = posOf(by) ?: host.position()
        // The nearest door of the kind still shut or opened in the last [DOOR_JUST_OPENED] ticks (ADS's own click a tick before the line);
        // one opened long before would count as this one and leave the real one to you.
        val door = run.plan.doors.filter { it.kind == kind && run.state.doors[it.index].let { d -> d.phase == DoorPhase.CLOSED || (d.openedAt ?: Int.MIN_VALUE) >= run.t - DOOR_JUST_OPENED } }
            .minByOrNull { d -> val c = d.spot; Math.hypot(c.first + 0.5 - at.x, c.second + 0.5 - at.z) } ?: return
        val rec = run.state.doors[door.index]
        // A teammate's door opens on the tick of its line (Hypixel's body is barrier the next tick, air 14 after); yours had better be ADS's own,
        // unless ADS has no key for it (it cannot open it then: waiting only makes it late, and a teleport through it a tick after the air misses).
        val keys = run.state.keys
        val noKey = if (kind == DoorKind.WITHER) keys.witherHeld <= 0 else !keys.bloodHeld
        pending += Pending(if ((by != null && by != host.uuid) || noKey) k else k + DOOR_GRACE, "${kind.name.lowercase()} door ${door.index} opened by ${who ?: "?"}", { rec.phase != DoorPhase.CLOSED }) {
            if (if (kind == DoorKind.WITHER) keys.witherHeld <= 0 else !keys.bloodHeld) {
                // ADS had no key for it (the teammate took one ADS did not drop, or not yet): lent, then opened. The next key ADS
                // gives of that kind pays it back, so ADS never holds one Hypixel's party did not.
                if (kind == DoorKind.WITHER) { keys.witherHeld++; witherDebt++ } else { keys.bloodHeld = true; bloodDebt = true }
                "no key in ADS (lent one): ${Services.doors.tryOpen(run, door.index, by)}"
            } else Services.doors.tryOpen(run, door.index, by).toString()
        }
    }

    /** ADS's last hurt of you (run tick), and the recording's bone plating line waiting for one ([PLATING_NEAR] ticks either side). */
    private var hurtSub: com.engineerclient.p3sim.run.RunBus.Subscription? = null
    private var secretSub: com.engineerclient.p3sim.run.RunBus.Subscription? = null
    private var hurtAt = Int.MIN_VALUE
    private var plating: Pair<Int, String>? = null

    /**
     * Your Guided Sheep (body ids): a sheep that first appears by you (4.5 blocks across, 0.5 down to 2.1 up of where you stood). One that
     * a "now available" line follows is yours only when that line is its cooldown on (DR: 136 to 141 or 288 to 302 ticks, 307 of 318 lines),
     * else a teammate's by you; after the last line, every one by you.
     */
    private val yourSheep: Set<Int> by lazy {
        var at: DoubleArray? = null
        val near = ArrayList<Pair<Int, Int>>()
        val seen = HashSet<Int>()
        for (e in file.events) {
            if (e.size() < 3) continue
            when (e[1].asString) {
                "o.m" -> if (e.size() > 4 && !e[2].isJsonNull) at = doubleArrayOf(e[2].asDouble, e[3].asDouble, e[4].asDouble)
                "spawn" -> if (e.size() > 7 && e.s(3) == "minecraft:sheep" && seen.add(e[2].asInt)) at?.let { p ->
                    if (Math.hypot(e[5].asDouble - p[0], e[7].asDouble - p[2]) < 4.5 && e[6].asDouble - p[1] in -0.5..2.1) near += e[0].asInt to e[2].asInt
                }
            }
        }
        val lines = file.all("chat").filter { clean(it.s(2) ?: "") == "Guided Sheep is now available!" }.map { it[0].asInt }
        val out = HashSet<Int>()
        for ((k, id) in near) {
            val next = lines.firstOrNull { it > k }
            if (next == null) { out += id; continue }
            // The line's own sheep: the last one by you before it at a cooldown's distance.
            val own = near.lastOrNull { (s, _) -> s < next && (next - s in SHEEP_CD_LONE || next - s in SHEEP_CD_MANY) }
            if (own?.second == id) out += id
        }
        out
    }

    /**
     * What broken crypts let out in the recording (DR: 150 seen in 139 runs, each alone and 116 with an explosion at it): a Crypt Undead
     * or a Prince, spawned on the tick and where it was first seen. How often a crypt lets one out is not known (668 crypts in those runs).
     */
    private class CryptMob(val k: Int, val id: Int, val kind: String, val room: Int, val x: Double, val y: Double, val z: Double, val hp: Double?)
    private val cryptMobs = ArrayList<CryptMob>()

    /** The ticks of the recording's Crypt Wither Skull hits on you. */
    private val skullHitTicks: Set<Int> by lazy {
        file.all("chat").filter { clean(it.s(2) ?: "").startsWith(SKULL_HIT) }.mapTo(HashSet()) { it[0].asInt }
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

    /**
     * A recorded room changing mark: for the differ, and ADS's room is brought to it when ADS has not got there by itself in a while
     * (most often the teammates' work, out of the recording's sight): found (on the map), cleared (white) or done (green, its secrets
     * credited; a puzzle reported solved).
     */
    private fun room(k: Int, e: JsonArray) {
        val mark = e.s(3) ?: return
        val tiles = e.get(5).takeIf { it.isJsonArray }?.asJsonArray ?: return
        val t0 = tiles.firstOrNull()?.asJsonArray ?: return
        val placed = run.plan.roomAt(t0[0].asInt, t0[1].asInt) ?: return diff.note(k, "room", "no ADS room at tile ${t0[0]},${t0[1]} (${e.s(2)})")
        diff.recordedRoom(k, placed.index, e.s(2), mark)
        val want = RANK[mark] ?: return
        if (want == 0) return
        val rr = run.state.room(placed.index) ?: return
        val inIt = run.state.roomAt(host.x, host.z)?.index == placed.index
        val c = placed.tiles.first().let { com.engineerclient.p3sim.pack.DungeonFrame.tileCentre(it.x).toDouble() to com.engineerclient.p3sim.pack.DungeonFrame.tileCentre(it.z).toDouble() }
        val by = if (inIt) host.uuid else nearestMate(c.first, c.second)
        // A room with no mob for ADS to kill (the recording saw none there) cannot clear by itself: its white comes on the recorded tick.
        val grace = if (want == 1) 10 else if (want == 2 && rr.mobsRequired == 0) 0 else GRACE
        pending += Pending(k + grace, "${placed.name} ${mark.lowercase()}${if (inIt) " (you were in it)" else ""}", { (RANK[rr.check.odin()] ?: 0) >= want }) {
            when (want) {
                1 -> com.engineerclient.p3sim.clear.state.DoorsModule.controller?.discover(placed.index)
                2 -> { com.engineerclient.p3sim.clear.state.DoorsModule.controller?.discover(placed.index); com.engineerclient.p3sim.clear.state.DoorsModule.completeRoom(run, placed.index) }
                3 -> {
                    com.engineerclient.p3sim.clear.state.DoorsModule.controller?.discover(placed.index)
                    val pz = PuzzlesModule.puzzleOf(run, placed.index)
                    if (pz != null) Services.puzzles.reportSolved(pz, by)
                    else { com.engineerclient.p3sim.clear.state.DoorsModule.completeRoom(run, placed.index); Services.secrets.creditRoom(run, placed.index, by) }
                }
            }
            "ADS now ${rr.check}"
        }
    }

    /**
     * The recording's `PUZZLE SOLVED!` line (the puzzles that print one: Tic Tac Toe, Three Weirdos): ADS's puzzle is solved by the one it
     * names, on its tick (a teammate's) or after [GRACE] ticks to do it yourself (yours), not when the room's mark turns and not by whoever
     * stood nearest.
     */
    private fun puzzleSolved(k: Int, name: String, what: String) {
        val kind = when { what.startsWith("tied Tic Tac Toe") -> "TTT"; what.startsWith("wasn't fooled by") -> "WEIRDOS"; else -> return }
        val pz = run.plan.rooms.firstNotNullOfOrNull { r -> PuzzlesModule.puzzleOf(run, r.index)?.takeIf { it.kind == kind } } ?: return diff.note(k, "puzzle", "no $kind in ADS for \"$name $what\"")
        val by = uuidOf(name)
        pending += Pending(if (by != null && by != host.uuid) k else k + GRACE, "$kind solved by $name", { pz.status == com.engineerclient.p3sim.run.PuzzleStatus.SOLVED }) {
            Services.puzzles.reportSolved(pz, by); "ADS reported it solved"
        }
    }

    /**
     * The recording's `PUZZLE FAIL!` line: ADS's puzzle in the room the recording marks red next (within [GRACE] ticks) fails by the one it
     * names, on its tick (a teammate's, often out of the recording's sight: a blaze killed in the wrong order) or after [GRACE] (yours).
     */
    private fun puzzleFailed(k: Int, name: String, reason: String) {
        val red = file.events.firstOrNull { it.size() > 5 && it[1].asString == "room" && it.s(3) == "RED" && it[0].asInt in k..k + GRACE } ?: return
        val t0 = red.get(5).takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.asJsonArray ?: return
        val room = run.plan.roomAt(t0[0].asInt, t0[1].asInt) ?: return
        val pz = PuzzlesModule.puzzleOf(run, room.index) ?: return diff.note(k, "puzzle", "no puzzle in ADS's ${room.name} for \"$name $reason\"")
        val by = uuidOf(name)
        pending += Pending(if (by != null && by != host.uuid) k else k + GRACE, "${room.name} failed by $name", { pz.status != com.engineerclient.p3sim.run.PuzzleStatus.UNSOLVED }) {
            Services.puzzles.reportFailed(pz, by, reason); "ADS reported it failed"
        }
    }

    /**
     * The recording's quizzes, one per start (a wrong answer starts it over): each question as the statue asked it, one burst of lines on a
     * tick: `Question #n`, the question's line or two, a blank line, the three options (`ⓐ ...`).
     */
    private val recordedQuizzes: List<List<com.engineerclient.p3sim.clear.puzzles.QuizLogic.Question>> by lazy {
        val chat = file.events.filter { it.size() > 2 && it[1].asString == "chat" && it[2].isJsonPrimitive }
        val out = ArrayList<MutableList<com.engineerclient.p3sim.clear.puzzles.QuizLogic.Question>>()
        for ((i, e) in chat.withIndex()) {
            val n = QUIZ_HEADING.find(clean(e[2].asString))?.groupValues?.get(1)?.toInt() ?: continue
            val k = e[0].asInt
            val same = chat.drop(i + 1).takeWhile { it[0].asInt == k }.map { clean(it[2].asString) }
            val text = same.takeWhile { it.isNotEmpty() }.joinToString(" ")
            val options = same.filter { it.length > 2 && it[0] in "ⓐⓑⓒ" && it[1] == ' ' }.map { it.substring(2) }
            if (text.isEmpty() || options.size != 3) continue
            if (n == 1 || out.isEmpty()) out.add(ArrayList())
            out.last().add(com.engineerclient.p3sim.clear.puzzles.QuizLogic.recorded(text, options))
        }
        out
    }

    /** The statue's line for an answer: ADS's quiz takes it, right or wrong, by the one it names. */
    private fun quizAnswer(name: String, right: Boolean) {
        val pz = run.plan.rooms.firstNotNullOfOrNull { r -> PuzzlesModule.puzzleOf(run, r.index) as? com.engineerclient.p3sim.clear.puzzles.QuizPuzzle } ?: return
        pz.recordedAnswer(uuidOf(name) ?: host.uuid, right)
    }

    /**
     * Three Weirdos as the recording had it: the NPC whose named stand was within 1.5 blocks of each of ADS's stand [spots], what each
     * said to you, and who the solve and fail lines name.
     */
    private fun recordedWeirdos(spots: List<DoubleArray?>): com.engineerclient.p3sim.clear.puzzles.WeirdosLogic.Recorded? {
        val all = com.engineerclient.p3sim.clear.puzzles.WeirdosLogic.NAMES.toSet()
        val first = HashMap<Int, JsonArray>()
        val stands = ArrayList<Pair<String, JsonArray>>()
        for (e in file.events) when (e.s(1)) {
            "spawn" -> e.i(2)?.let { first.putIfAbsent(it, e) }
            "name" -> { val n = clean(e.s(3) ?: continue); if (n in all) first[e.i(2) ?: continue]?.let { stands += n to it } }
        }
        val names = spots.map { s ->
            s ?: return@map null
            stands.filter { (_, e) -> Math.hypot((e.d(5) ?: 1e9) - s[0], (e.d(7) ?: 1e9) - s[1]) <= 1.5 }.map { it.first }.distinct().singleOrNull()
        }
        if (names.any { it == null }) return null
        val said = HashMap<String, String>(); var reward: String? = null; val wrong = HashSet<String>()
        for (e in file.all("chat")) {
            val t = clean(e.s(2) ?: continue)
            NPC_LINE.find(t)?.let { m -> if (m.groupValues[1] in names && m.groupValues[2] !in NOT_CLUES) said.putIfAbsent(m.groupValues[1], m.groupValues[2]) }
            SOLVED.find(t)?.groupValues?.get(2)?.removePrefix("wasn't fooled by ")?.removeSuffix("!")?.takeIf { it in names }?.let { reward = it }
            FAILED.find(t)?.groupValues?.get(2)?.takeIf { it.startsWith("was fooled by ") }?.removePrefix("was fooled by ")?.removeSuffix("!")?.let { wrong += it }
        }
        return com.engineerclient.p3sim.clear.puzzles.WeirdosLogic.Recorded(names.map { it!! }, said, reward, wrong)
    }

    private fun CheckMark.odin(): String = when (this) { CheckMark.UNDISCOVERED -> "UNDISCOVERED"; CheckMark.NONE -> "NONE"; CheckMark.WHITE -> "WHITE"; CheckMark.GREEN -> "GREEN"; else -> "FAILED" }

    companion object {
        /** The session whose recorded packet the server is handling right now (server thread). */
        @Volatile private var injecting: ReplaySession? = null

        /**
         * A kick while a recorded packet is handled (AdsReplayKickMixin): the replay keeps going and the report lists it, since a packet
         * Hypixel took that ADS's server refuses is a difference, not a reason to lose the run. False when no recorded packet is in hand.
         */
        @JvmStatic
        fun swallowKick(reason: String): Boolean {
            val s = injecting ?: return false
            s.diff.kick(s.run.t, reason)
            return true
        }

        const val NET = "ads.replay"

        /** Ticks the replay waits for ADS to count a find of the party's itself before crediting it. */
        const val TEAM_GRACE = 20
        private val RANK = mapOf("UNDISCOVERED" to 0, "NONE" to 1, "WHITE" to 2, "GREEN" to 3)
        const val MATE = "replayMate"
        /** The dungeon state says the bonus bat is dead this many ticks after its death at most (50 of 50 DR runs: 0 to 3). */

        /** The recorder's brief tag for a mob whose tag it had not read. */
        private val UNREAD_TAGS = setOf("???", "MINIBOSS")
        /** Items whose click hurts no mob: a death near you after one is a teammate's. */
        private val HARMLESS = setOf("ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END", "INFINITE_SPIRIT_LEAP", "SPIRIT_LEAP", "ENDER_PEARL", "DUNGEONBREAKER",
            "SUPERBOOM_TNT", "INFINITE_SUPERBOOM_TNT", "WITHER_CLOAK", "SKYBLOCK_MENU", "MAP", "MAP:2", "HAUNT_ABILITY", "TACTICAL_INSERTION", "FISHING_ROD",
            "MAXOR_ENERGY_CRYSTAL", "POTION", "STONE_BUTTON", "REVIVE_STONE", "ARCHITECT_FIRST_DRAFT", "ENCHANTED_BOOK")
        /** Hypixel's tab refreshes once a second. */
        private const val TAB_TICKS = 20
        /** The Watcher's camp mobs' names; not the Parasite's (its split silverfish, levelled mobs of their own, have its name). */
        private val CAMP_NAMES = (com.engineerclient.p3sim.clear.blood.camp.WallPlan.WALL_BOSSES + com.engineerclient.p3sim.clear.blood.camp.WallPlan.STANDARD) - "Parasite"
        private const val SKULL_HIT = "A Crypt Wither Skull exploded, hitting you for "
        private const val BONE_PLATING = "Your bone plating reduced the damage you took by "
        private const val SKULL_HIT_VOLUME = 0.7f
        private const val PLATING_NEAR = 3
        private const val DOOR_JUST_OPENED = 20
        private const val CRYPT_UNDEAD = "crypt_undead"
        private val SHEEP_CD_LONE = 136..141
        private val SHEEP_CD_MANY = 288..302
        /** The longest ADS's teleport waits for the recorded client's (DR click to tpme: 1,096 the same tick, 5,802 one, 442 two, 136 three). */
        private const val TP_ACCEPT_MAX = 4
        /** A click this many ticks before a recorded chest's lid sound opened it (854 to 856 measured). */
        private const val CHEST_OPEN_LEAD = 5

        /** Ticks ADS gets to do by itself what the recording did (a kill, a puzzle) before it is done for it. */
        const val GRACE = 20
        const val DOOR_GRACE = 6
        /** Ticks after a recorded WITHER-door HP jump that ADS's mob is checked (ADS's door may open up to DOOR_GRACE later). */
        const val HP_CHECK_AFTER = DOOR_GRACE + 2
        /** The window around your hit in which the recording's HP loss of that mob is yours. */
        const val HIT_BEFORE = 2
        const val HIT_AFTER = 3
        /** A recorded death near you waits this long for your own kill: your hits do the recording's HP loss up to [HIT_AFTER] ticks ahead. */
        const val KILL_GRACE = HIT_AFTER + 1
        /** Farther than this from you, a recorded kill was not yours. */
        const val FAR = 12.0
        /** A death within this many ticks of your hit or use may be yours (an arrow's flight included). */
        const val YOURS_WITHIN = 10

        /** The health part of Hypixel's action bar: current/max, red or gold. */
        private val HEALTH = Regex("^\u00a7[c6]([\\d,]+)/([\\d,]+)")
        /** A grant line's first term at levels I, II and V: boosted (x1.25) or plain. */
        private val BOOSTED_GRANT = Regex("^Granted you \\+(?:7\\.3|14\\.5|36\\.3) &|^Granted you \\+1\\.(?:05|11|27)x HP")
        private val PLAIN_GRANT = Regex("^Granted you \\+(?:5\\.8|11\\.6|29) &|^Granted you \\+1\\.(?:04|09|22)x HP")
        private val RIGHT_CLICKS = setOf("o.use", "o.useOn", "o.int")
        /** "DUNGEON BUFF! X found a / You found a / A Blessing of Life V(! / was found!) (03s)". */
        private val BUFF = Regex("^DUNGEON BUFF! (A|(?:(\\w{1,16}|You) found a)) Blessing of (\\w+) (I|II|III|IV|V)(?: was found)?!( \\(.+\\))?$")
        private val RANKED = Regex("^(?:Party > )?\\[(VIP|VIP\\+|MVP|MVP\\+|MVP\\+\\+)] (\\w{1,16})[ :]")
        /** A rank's bracket and name colour as Hypixel writes them. */
        private val RANK_LOOKS = mapOf("VIP" to ("§a[VIP]" to "§a"), "VIP+" to ("§a[VIP§6+§a]" to "§a"), "MVP" to ("§b[MVP]" to "§b"),
            "MVP+" to ("§b[MVP§c+§b]" to "§b"), "MVP++" to ("§6[MVP§c++§6]" to "§6"))
        private val ROMAN = listOf("I", "II", "III", "IV", "V")
        private val ITEM = Regex("^(?:\\[[^\\]]+] )?(\\w{1,16}) has obtained (Superboom TNT|Revive Stone)(?: x(\\d+))?!$")
        private val WITHER_ESSENCE = Regex("^(\\S+) found a Wither Essence! Everyone gains an extra essence!$")
        /** What a Weirdos NPC says that is not its clue. */
        private val NOT_CLUES = setOf("Talk to all of us first!", "You fool!")
        private val QUIZ_HEADING = Regex("""^Question #(\d)$""")
        private val QUIZ_ANSWER = Regex("""^\[STATUE] Oruo the Omniscient: (\w{1,16}) (answered|chose the wrong answer)""")
        private val NPC_LINE = Regex("""^\[NPC] (\w+): (.+)$""")
        private val FAILED = Regex("""^PUZZLE FAIL! (\w{1,16}) (.+) (Yikes!|That's a whoopsie!)$""")
        private val SOLVED = Regex("""^PUZZLE SOLVED! (\w{1,16}) (.+) Good job!$""")
        private val IMPLOSION = Regex("""^Your Implosion hit (\d+) enem(?:y|ies) for ([\d,.]+) damage\.$""")
        private const val IMPLOSION_NEAR = 4
        private const val PEARL_NEAR = 3
        private const val ESSENCE_RETRY = 10
        private val ESSENCE = Regex("^ESSENCE! (\\w{1,16}) found x(\\d+) (\\w+) Essence!$")
        private val WITHER_OPENED = Regex("^(\\w{1,16}) opened a WITHER door!")
        private val DIED = Regex("☠ (\\w{1,16}) .*became a ghost")
        private val KEY = Regex("^(?:\\[[^\\]]+] )?(\\w{1,16}) has obtained (Wither|Blood) Key!")
        /** A Hypixel nametag as ADS names the mob: no star, no HP, no colour. */
        /** A recorded tag's words: no star, no class icons (the Dungeon Recorder keeps their glyphs, U+E000 on), single spaces. */
        /** A camp mob's tag, bare: modifier, name, HP ("Stealth Spirit Bear 13M"). */
        private val CAMP_TAG = Regex("^(?:(Speedy|Stealth|Golden|Healthy|Boomer|Stormy) )?(.+?) ([0-9][0-9.,]*[kM]?)❤$")

        /** A tag's HP ("316.5k", "15M", "90,000") as a number. */
        fun hpValue(s: String): Double {
            val n = s.trimEnd('k', 'M').replace(",", "").toDoubleOrNull() ?: return 0.0
            return n * when { s.endsWith("k") -> 1e3; s.endsWith("M") -> 1e6; else -> 1.0 }
        }

        fun bareTag(tag: String): String = tag.replace(ICONS, "").trim().replace(Regex("\\s+"), " ")
        private val ICONS = Regex("[✯\uE000-\uF8FF]")

        fun mobName(tag: String): String = bareTag(clean(tag)).replace(Regex("^\\[Lv\\d+]"), "").replace(Regex("\\s*[0-9][0-9.,]*[kMB]?(/[0-9][0-9.,]*[kMB]?)?❤.*$"), "").trim()
        fun sameMob(ads: String, rec: String): Boolean { val a = bareTag(clean(ads)); return a.equals(rec, true) || rec.endsWith(" $a") || a.endsWith(" $rec") }
        private val REVIVED = Regex("❣ (\\w{1,16}) was revived by (\\w{1,16})")
        fun clean(s: String) = s.replace(Regex("§."), "").trim()
        /** A Higher Or Lower blaze's tag, `[Lv11] <icon> Blaze 3,916/3,916❤`: its full HP. */
        private val BLAZE_TAG = Regex("""\bBlaze [\d,]+/([\d,]+)❤""")
        fun fmt(x: Double, y: Double, z: Double) = "%.1f %.1f %.1f".format(x, y, z)
        val hostPlayer: ServerPlayer? get() = SimServer.player
    }
}
