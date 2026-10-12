package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.index
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The sim's own line between each player's game and the sim's server: one payload, a kind and its text. Everyone
 * uses it, the host too (their game talks to their own server the same way), so a joiner's click, drop, menu
 * choice or setting reaches the fight exactly as the host's does. What a player sends is handled as theirs
 * ([Sim.asPlayer]).
 */
object SimNet {
    class Msg(val kind: String, val data: String) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Msg> = TYPE
    }

    val TYPE = CustomPacketPayload.Type<Msg>(Identifier.fromNamespaceAndPath("engineerclient", "p3sim"))
    private const val MAX = 1 shl 21
    private val CODEC: StreamCodec<RegistryFriendlyByteBuf, Msg> = StreamCodec.of(
        { b, m -> b.writeUtf(m.kind, 64); b.writeUtf(m.data, MAX) },
        { b -> Msg(b.readUtf(64), b.readUtf(MAX)) })

    private val serverSide = HashMap<String, (ServerPlayer, String) -> Unit>()
    private val clientSide = HashMap<String, (String) -> Unit>()

    /** What the sim's server does with a [kind] from a player (as theirs, on the server thread). */
    fun onServer(kind: String, run: (ServerPlayer, String) -> Unit) { serverSide[kind] = run }
    /** What a game does with a [kind] from the sim's server (client thread). */
    fun onClient(kind: String, run: (String) -> Unit) { clientSide[kind] = run }

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC)
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC)
        ServerPlayNetworking.registerGlobalReceiver(TYPE) { m, ctx ->
            val p = ctx.player()
            val s = SimServer.server
            if (s == null || p.level().server !== s) return@registerGlobalReceiver
            val run = serverSide[m.kind] ?: return@registerGlobalReceiver
            EngineerClient.safely("p3sim net ${m.kind}") { Sim.asPlayer(p) { run(p, m.data) } }
        }
        ClientPlayNetworking.registerGlobalReceiver(TYPE) { m, _ ->
            clientSide[m.kind]?.let { run -> EngineerClient.safely("p3sim net ${m.kind}") { run(m.data) } }
        }
    }

    /** To the sim's server (client thread): nothing when this game isn't in a sim. */
    fun send(kind: String, data: String = "") {
        if (!P3Sim.inSim) return
        runCatching { if (ClientPlayNetworking.canSend(TYPE)) ClientPlayNetworking.send(Msg(kind, data)) }
    }

    /** To [p]'s game (server thread). */
    fun send(p: ServerPlayer, kind: String, data: String = "") {
        runCatching { if (ServerPlayNetworking.canSend(p, TYPE)) ServerPlayNetworking.send(p, Msg(kind, data)) }
    }

    /** To every game in the sim. */
    fun sendAll(kind: String, data: String = "") = Sim.players().forEach { send(it, kind, data) }
}

/**
 * Who's who in a party, on the sim's server: each player's class (one each: the host's is their menu's, a joiner
 * gets theirs unless someone has it), and a joiner's own settings (speed, pet, masks, ping...) and saved hotbar and
 * loadouts, as their game sends them. The host's are their own settings, read as ever.
 */
object Players {
    class Profile {
        /** Their personal settings ([PartySettings.PERSONAL]'s keys). */
        val vals = ConcurrentHashMap<String, JsonElement>()
        /** Their class here (null until they have one). */
        @Volatile var clazz: DungeonClass? = null
        /** The class their game last asked for. */
        @Volatile var asked: String? = null
        /** Their saved files (hotbar layout, loadouts), by name. */
        val files = ConcurrentHashMap<String, String>()
        /** Their own jobs (their plan), for this skill and class; null: their class's role. */
        @Volatile var mine: Set<String>? = null
        @Volatile var mineFor: String? = null
        /** Their own spawn and early-enter spots (skill/class/key), as their plan has them. */
        val spots = ConcurrentHashMap<String, Spots.Spot>()
        /** Their leap order (the other four classes, slots 1-4), as their menu has it; null: Odin's. */
        @Volatile var order: List<DungeonClass>? = null
    }

    private val profiles = ConcurrentHashMap<UUID, Profile>()

    fun profile(p: ServerPlayer): Profile = profiles.getOrPut(p.uuid) { Profile() }

    /** The acting player's profile when they're a joiner (their settings over the host's), else null. */
    fun joiner(): Profile? = Sim.acting?.takeIf { !Sim.isHost(it) }?.let { profiles[it.uuid] }

    /** [p]'s class: the host's from their menu, a joiner's as given here. */
    fun classOf(p: ServerPlayer): DungeonClass = if (Sim.isHost(p)) P3Sim.ownClass else profiles[p.uuid]?.clazz ?: P3Sim.ownClass

    /** The player playing [c], or null (a bot's, or nobody's). */
    fun playerOf(c: DungeonClass?): ServerPlayer? = if (c == null) null else Sim.players().firstOrNull { classOf(it) == c }

    /** The classes the joiners play (any thread: a copy kept by the server's tick). */
    @Volatile var joinerClasses: Set<DungeonClass> = emptySet()
        private set

    /** Every class a player plays, the host's included. */
    fun playerClasses(): Set<DungeonClass> = joinerClasses + P3Sim.ownClass

    /** Whether a player plays [c] (no bot then). */
    fun isPlayerClass(c: DungeonClass?) = c != null && c in playerClasses()

    /** Their jobs: the host's plan's, a joiner's own (or their class's role). */
    /** [p]'s own [key] spot ("spawn", "ee3"...) for this skill and their class: the host's plan's, a joiner's as their game sent it, else the default. */
    fun spotOf(p: ServerPlayer, key: String): Spots.Spot? {
        if (Sim.isHost(p)) return P3Plan.customSpot(key)
        val c = classOf(p)
        return profiles[p.uuid]?.spots?.get("${P3Plan.skill}/${c.name}/$key") ?: P3Plan.defaultSpot(c, key)
    }

    fun jobsOf(p: ServerPlayer): Set<String> {
        if (Sim.isHost(p)) return P3Plan.mine()
        val c = classOf(p)
        val prof = profiles[p.uuid]
        val key = "${P3Plan.skill}/$c"
        return prof?.mine?.takeIf { prof.mineFor == key } ?: P3Plan.plan().jobsOf[c].orEmpty().toSet()
    }

    /** Whether [job] is a player's (the host's or a joiner's): no bot does it. */
    fun isPlayerJob(job: String): Boolean = Sim.players().any { job in jobsOf(it) }

    /** The player whose jobs include [job], if any. */
    fun playerDoing(job: String): ServerPlayer? = Sim.players().firstOrNull { job in jobsOf(it) }

    /** A joiner came in: a class of their own for now (the one they ask for, if it's free). */
    fun joined(p: ServerPlayer) {
        val prof = profile(p)
        val want = prof.vals["class"]?.let { classByName(it.asString) }
        if (prof.clazz == null || prof.clazz in takenBy(p)) prof.clazz = free(p, want)
        refresh()
    }

    /** A joiner's game asks for [want] as they arrive: theirs if free, else what they have (or the first free). */
    fun request(p: ServerPlayer, want: DungeonClass?): DungeonClass {
        val prof = profile(p)
        val taken = takenBy(p)
        val got = if (want != null && want !in taken) want else prof.clazz?.takeIf { it !in taken } ?: free(p, want)
        prof.clazz = got
        refresh()
        return got
    }

    fun left(p: ServerPlayer) { refresh(p) }

    private fun takenBy(p: ServerPlayer): Set<DungeonClass> = Sim.players().filter { it !== p }.map { classOf(it) }.toSet()

    private fun free(p: ServerPlayer, want: DungeonClass?): DungeonClass {
        val taken = takenBy(p)
        return want?.takeIf { it !in taken } ?: Party.CLASSES.firstOrNull { it !in taken } ?: want ?: Party.CLASSES.first()
    }

    fun classByName(n: String?): DungeonClass? = Party.CLASSES.firstOrNull { it.name == n }

    /** The joiners' classes, kept for other threads. [gone]: a player leaving (still in the list). */
    fun refresh(gone: ServerPlayer? = null) {
        joinerClasses = Sim.players().filter { !Sim.isHost(it) && it !== gone }.mapNotNull { profiles[it.uuid]?.clazz }.toSet()
    }

    /**
     * [p] asks for [want]: theirs if it's free; else (forced) they swap with whoever has it. Both are told, and each
     * game's menu follows ("set").
     */
    fun changeClass(p: ServerPlayer, want: DungeonClass) {
        val mine = classOf(p)
        if (want == mine) return
        val holder = playerOf(want)
        if (holder != null && holder !== p) {
            if (Sim.isHost(holder)) setHostClass(mine) else { profile(holder).clazz = mine; SimNet.send(holder, "set", JsonObject().also { it.addProperty("class", mine.name) }.toString()) }
            Sim.asPlayer(holder) { Sim.note("§f${p.gameProfile.name}§7 took the §f${Roles.label(want)}§7: you're the §f${Roles.label(mine)}§7 now.") }
        }
        if (Sim.isHost(p)) setHostClass(want) else profile(p).clazz = want
        if (!Sim.isHost(p)) SimNet.send(p, "set", JsonObject().also { it.addProperty("class", want.name) }.toString())
        Sim.asPlayer(p) { Sim.note("You're the §f${Roles.label(want)}§7${holder?.takeIf { it !== p }?.let { " (§f${it.gameProfile.name}§7 is the ${Roles.label(mine)} now)" } ?: ""}.") }
        refresh()
        Party.rosterChanged()
        PartyServer.rosterDirty = true
    }

    private var hostClassSeen: DungeonClass? = null

    private fun setHostClass(c: DungeonClass) {
        hostClassSeen = c
        // At once (this thread reads it next), saved on the client's.
        P3Sim.classS.value = Party.CLASSES.indexOf(c).coerceAtLeast(0)
        mc.execute { com.odtheking.odin.features.ModuleManager.saveConfigurations() }
    }

    /** Server tick: the host picked a class in their menu that a joiner has: they swap. */
    fun tick() {
        val now = P3Sim.ownClass
        val was = hostClassSeen
        hostClassSeen = now
        if (was == null || was == now) return
        val holder = Sim.players().firstOrNull { !Sim.isHost(it) && profiles[it.uuid]?.clazz == now } ?: run { Party.rosterChanged(); PartyServer.rosterDirty = true; return }
        profile(holder).clazz = was
        SimNet.send(holder, "set", JsonObject().also { it.addProperty("class", was.name) }.toString())
        Sim.asPlayer(holder) { Sim.note("§f${Sim.hostName}§7 took the §f${Roles.label(now)}§7: you're the §f${Roles.label(was)}§7 now.") }
        refresh()
        Party.rosterChanged()
        PartyServer.rosterDirty = true
    }

    fun clear() { profiles.clear(); joinerClasses = emptySet(); hostClassSeen = null }
}

/**
 * The settings in a party: [SHARED] ones are the fight's (death ticks, terminals, bots, lava...), the host's for
 * everyone, and anyone's menu changes them for all; [PERSONAL] ones are each player's (class, speed, pet, masks,
 * ping...), sent to the server as their own. A joiner's game puts its own shared settings back when it leaves.
 */
object PartySettings {
    class Opt(val key: String, val get: () -> JsonElement, val set: (JsonElement) -> Unit)

    private fun bool(key: String, s: () -> BooleanSetting?) = Opt(key, { JsonPrimitive(s()?.value == true) }, { v -> s()?.let { it.value = v.asBoolean } })
    @Suppress("UNCHECKED_CAST")
    private fun num(key: String, s: () -> NumberSetting<*>?) = Opt(key, { JsonPrimitive(s()?.value?.toDouble() ?: 0.0) }, { v ->
        s()?.let { n -> val cur = n.value; (n as NumberSetting<Number>).value = when (cur) { is Int -> Math.round(v.asDouble).toInt(); is Float -> v.asFloat; is Long -> v.asLong; else -> v.asDouble } }
    })

    val SHARED: List<Opt> by lazy { listOf(
        bool("bots") { P3Sim.botsS },
        Opt("deathTicks", { JsonPrimitive(P3Sim.deathTicksS.value) }, { P3Sim.deathTicksS.value = it.asInt }),
        Opt("terminal", { JsonPrimitive(P3Sim.terminalS.value) }, { P3Sim.terminalS.value = it.asInt }),
        num("goldorKill") { P3Sim.goldorKillS },
        bool("clickLimit") { P3Sim.clickLimitS },
        bool("noMelodies") { P3Sim.noMelodiesS },
        num("termLuck") { P3Sim.termLuckS },
        num("ssLuck") { P3Sim.ssLuckS },
        bool("firstClickMelody") { P3Sim.firstClickMelodyS },
        bool("debugBots") { P3Sim.debugBotsS },
        num("breakerRefill") { P3Sim.breakerRefillS },
        num("breakerRegen") { P3Sim.breakerRegenS },
        bool("breakerPerma") { P3Sim.breakerPermaS },
        bool("realMoves") { P3Sim.realMovesS },
        bool("lava") { P3Sim.lavaS },
        bool("p3Only") { P3Sim.p3OnlyS },
    ) }

    val PERSONAL: List<Opt> by lazy { listOf(
        Opt("class", { JsonPrimitive(P3Sim.ownClass.name) }, { v -> Players.classByName(v.asString)?.let { P3Sim.classS.value = Party.CLASSES.indexOf(it) } }),
        num("speed") { P3Sim.speedS },
        bool("phoenix") { P3Sim.phoenixS },
        bool("realMasks") { P3Sim.realMasksS },
        Opt("wornMask", { JsonPrimitive(P3Sim.wornMaskS.value) }, { P3Sim.wornMaskS.value = it.asInt }),
        num("ping") { P3Sim.pingS },
        bool("jitter") { P3Sim.jitterS },
        num("shortbow") { P3Sim.shortbowCooldownS },
        num("hydra") { P3Sim.hydraStartS },
        bool("mageAll") { P3Sim.mageAllS },
        bool("infinite") { P3Sim.breakerInfiniteS },
        bool("terror") { P3Sim.terrorAtTermsS },
        bool("showTimes") { P3Sim.showTimesS },
    ) }

    fun snapshot(opts: List<Opt>): JsonObject = JsonObject().also { o -> opts.forEach { runCatching { o.add(it.key, it.get()) } } }

    fun apply(opts: List<Opt>, o: JsonObject) { opts.forEach { opt -> o.get(opt.key)?.let { v -> runCatching { opt.set(v) } } } }

    /** The keys of [now] that differ from [was]. */
    fun diff(was: JsonObject?, now: JsonObject): JsonObject = JsonObject().also { d -> now.entrySet().forEach { (k, v) -> if (was?.get(k) != v) d.add(k, v) } }
}

/** The sim server's side of a party: what goes out to the players' games (settings, the roster, the fight's state). */
object PartyServer {
    @Volatile var rosterDirty = true
    private var lastShared: String? = null
    private var lastPlan: String? = null
    private val sentRoster = HashMap<UUID, String>()
    private var lastPerma = false
    private val lastPersonal = HashMap<UUID, String>()

    fun register() {
        SimNet.onServer("hello") { p, data -> hello(p, data) }
        SimNet.onServer("profile") { p, data -> profile(p, data) }
        SimNet.onServer("shared") { p, data -> shared(p, data) }
        SimNet.onServer("plan") { p, data -> plan(p, data) }
        SimNet.onServer("act") { p, data -> SimActions.run(p, data) }
        SimNet.onServer("hit") { p, data -> SimItems.serverHit(p, data) }
        SimNet.onServer("left") { p, _ -> SimItems.serverLeftClick(p) }
        SimNet.onServer("drop") { p, data -> SimItems.serverDrop(p, data == "1") }
    }

    /**
     * A game in the sim says hello (the host's too): its profile, and what it needs to know. A joiner gets the class
     * they play if it's free (else they keep the one they were given, and are told), then is set up with their own
     * hotbar, gear and speed.
     */
    private fun hello(p: ServerPlayer, data: String) {
        lastShared = null; lastPlan = null; sentRoster.remove(p.uuid); rosterDirty = true
        if (!Sim.isHost(p)) {
            val o = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: JsonObject()
            val prof = Players.profile(p)
            o.getAsJsonObject("files")?.entrySet()?.forEach { (k, v) -> prof.files[k] = v.asString }
            o.getAsJsonObject("settings")?.entrySet()?.forEach { (k, v) -> prof.vals[k] = v }
            val want = prof.vals["class"]?.asString?.let { Players.classByName(it) }
            prof.asked = want?.name
            val got = Players.request(p, want)
            if (want != got) SimNet.send(p, "set", JsonObject().also { it.addProperty("class", got.name) }.toString())
            Party.rosterChanged()
            Fight.setup(p)
            BossBar.showTo(p)
            val name = p.gameProfile.name
            Sim.everyone { Sim.note("§e$name§7 joined the party as the §f${Roles.label(got)}§7.") }
            if (want != null && want != got) Sim.note("${Players.playerOf(want)?.gameProfile?.name ?: "Someone"} plays the ${Roles.label(want)}, so you're the §f${Roles.label(got)}§7. Pick a class in the menu: taking someone's swaps you.")
            if (Fight.phase != null) Sim.note("The run is under way: §fRestart§7 (menu or keybind) to play it from the start together.")
        }
        sendState(p)
    }

    private fun profile(p: ServerPlayer, data: String) {
        val o = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return
        if (Sim.isHost(p)) return  // the host's are their own settings, read directly
        val prof = Players.profile(p)
        o.getAsJsonObject("files")?.entrySet()?.forEach { (k, v) -> prof.files[k] = v.asString }
        o.getAsJsonObject("settings")?.entrySet()?.forEach { (k, v) -> prof.vals[k] = v }
        // A class picked in their menu: theirs, swapped with whoever had it. (Not the one they came asking for and were
        // refused, still in their menu until the server's answer reaches it.)
        val want = o.getAsJsonObject("settings")?.get("class")?.asString?.let { Players.classByName(it) }
        if (prof.clazz == null) Players.joined(p)
        else if (want != null && want != prof.clazz && want.name != prof.asked) Players.changeClass(p, want)
        prof.asked = want?.name
    }

    /** Someone changed a shared setting: the host's take it (everyone's follow from there). */
    private fun shared(p: ServerPlayer, data: String) {
        if (Sim.isHost(p)) return
        val o = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return
        mc.execute {
            PartySettings.apply(PartySettings.SHARED, o)
            com.odtheking.odin.features.ModuleManager.saveConfigurations()
        }
        val what = o.keySet().joinToString(", ")
        if (what.isNotEmpty()) Sim.everyone { Sim.note("§f${p.gameProfile.name}§7 changed: $what") }
    }

    /** A joiner's plan: their own jobs and spots; the shared parts (skill, the bots' options) are the party's. */
    private fun plan(p: ServerPlayer, data: String) {
        val o = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return
        if (Sim.isHost(p)) return
        val prof = Players.profile(p)
        o.getAsJsonArray("mine")?.let { a -> prof.mine = a.map { it.asString }.toSet(); prof.mineFor = o.get("mineFor")?.asString }
        o.getAsJsonArray("order")?.let { a -> prof.order = a.mapNotNull { Players.classByName(it.asString) } }
        o.getAsJsonObject("spots")?.let { sp ->
            prof.spots.clear()
            sp.entrySet().forEach { (k, v) ->
                val a = v.asJsonArray
                if (a.size() >= 5) prof.spots[k] = Spots.Spot(k, a[0].asDouble, a[1].asDouble, a[2].asDouble, a[3].asFloat, a[4].asFloat)
            }
        }
        o.getAsJsonObject("shared")?.let { sh -> mc.execute { P3Plan.applyShared(sh, save = true) } }
    }

    /** Everything a game should know now: settings, roster, the fight's state. */
    private fun sendState(p: ServerPlayer) {
        SimNet.send(p, "shared", PartySettings.snapshot(PartySettings.SHARED).toString())
        SimNet.send(p, "planShared", P3Plan.sharedJson().toString())
        roster(p, force = true)
        SimNet.send(p, "phase", phaseName())
    }

    private fun phaseName(): String = when (val ph = Fight.phase) {
        null -> ""
        is P1Maxor -> "P1"; is P2Storm -> "P2"; is P4Necron -> "P4"
        else -> "P3"
    }
    private var lastPhase = ""

    /** Each server tick: the host's settings and plan to the joiners when they change, the roster, personal effects. */
    fun tick() {
        val joiners = Sim.players().filter { !Sim.isHost(it) }
        Players.tick()
        Players.refresh()
        val shared = PartySettings.snapshot(PartySettings.SHARED).toString()
        if (shared != lastShared) { lastShared = shared; joiners.forEach { SimNet.send(it, "shared", shared) } }
        val plan = P3Plan.sharedJson().toString()
        if (plan != lastPlan) { lastPlan = plan; joiners.forEach { SimNet.send(it, "planShared", plan) } }
        val ph = phaseName()
        if (ph != lastPhase) { lastPhase = ph; SimNet.sendAll("phase", ph) }
        // Perma Break turned off (by anyone): every block it kept comes back.
        val perma = P3Sim.breakerPerma
        if (lastPerma && !perma) Sim.forEachPlayer { SimItems.unPerma() }
        lastPerma = perma
        // Each player's speed, pet and masks as their settings say, the moment they change.
        for (p in Sim.players()) Sim.asPlayer(p) {
            val now = "${P3Sim.speed}/${P3Sim.phoenix}/${P3Sim.realMasks}/${P3Sim.wornMaskS.value.takeIf { Sim.isHost(p) } ?: Players.profile(p).vals["wornMask"]}"
            val was = lastPersonal.put(p.uuid, now)
            if (was != null && was != now) {
                val (s0, ph0, rm0, wm0) = was.split('/')
                val (s1, ph1, rm1, wm1) = now.split('/')
                if (s0 != s1 || ph0 != ph1) Fight.applySpeed(p)
                if ((rm0 != rm1 || wm0 != wm1) && !Masks.ghost) Masks.equip(p)
            }
        }
        if (rosterDirty || Fight.serverTick % 20 == 0) { rosterDirty = false; Sim.players().forEach { roster(it) } }
    }

    /**
     * The party as [p] sees it: themselves, then everyone else (players and bots) in their leap order, for Odin's
     * party list and leap menu on their game.
     */
    private fun roster(p: ServerPlayer, force: Boolean = false) {
        val me = Players.classOf(p)
        val a = JsonArray()
        for (m in Party.mates(p)) a.add(JsonObject().also { it.addProperty("n", m.name); it.addProperty("c", m.clazz.name); it.addProperty("p", m.player != null) })
        val o = JsonObject().also { it.addProperty("me", me.name); it.add("team", a); it.addProperty("party", Sim.players().size > 1) }
        val s = o.toString()
        if (!force && sentRoster[p.uuid] == s) return
        sentRoster[p.uuid] = s
        SimNet.send(p, "roster", s)
    }

    fun left(p: ServerPlayer) { sentRoster.remove(p.uuid); lastPersonal.remove(p.uuid); rosterDirty = true }

    fun reset() { lastShared = null; lastPlan = null; sentRoster.clear(); lastPersonal.clear(); rosterDirty = true; lastPhase = "" }
}

/** A player's game in a sim, its side of the party: settings and plan in step with the server's, the roster. */
object PartyClient {
    /** The party as the server sent it (us first): name, class, a player (not a bot). */
    class Mate(val name: String, val clazz: DungeonClass, val player: Boolean)

    @Volatile var me: DungeonClass? = null
        private set
    @Volatile var team: List<Mate> = emptyList()
        private set
    /** More than one player in the sim. */
    @Volatile var party = false
        private set
    /** The fight's phase as the server has it ("" none, P1, P2, P3, P4). */
    @Volatile var phase = ""
        private set

    private var helloSent = false
    private var lastProfile: String? = null
    private var lastShared: JsonObject? = null
    private var lastPlan: String? = null
    /** A joiner's own shared settings and plan from before the party, put back when it leaves. */
    private var ownShared: JsonObject? = null
    private var gotShared = false

    /** Whether this game is a joiner's: in someone else's sim. */
    val joiner: Boolean get() = P3Sim.inSim && !P3Sim.localSim

    fun register() {
        SimNet.onClient("shared") { data ->
            if (!joiner) return@onClient
            val o = JsonParser.parseString(data).asJsonObject
            if (!gotShared) { gotShared = true; ownShared = PartySettings.snapshot(PartySettings.SHARED) }
            PartySettings.apply(PartySettings.SHARED, o)
            lastShared = PartySettings.snapshot(PartySettings.SHARED)
        }
        SimNet.onClient("planShared") { data -> if (joiner) P3Plan.receiveShared(JsonParser.parseString(data).asJsonObject) }
        SimNet.onClient("set") { data ->
            PartySettings.apply(PartySettings.PERSONAL, JsonParser.parseString(data).asJsonObject)
            lastProfile = null
            com.odtheking.odin.features.ModuleManager.saveConfigurations()
        }
        SimNet.onClient("roster") { data ->
            val o = JsonParser.parseString(data).asJsonObject
            me = Players.classByName(o.get("me")?.asString)
            team = o.getAsJsonArray("team").mapNotNull { e -> val m = e.asJsonObject; Players.classByName(m.get("c").asString)?.let { Mate(m.get("n").asString, it, m.get("p").asBoolean) } }
            party = o.get("party")?.asBoolean == true
        }
        SimNet.onClient("phase") { phase = it }
        SimNet.onClient("fx") { fx(it) }
        SimNet.onClient("file") { data ->
            val o = JsonParser.parseString(data).asJsonObject
            PlayerFiles.writeLocal(o.get("name").asString, o.get("text").asString)
            HotbarLayout.forgetLocal(); Loadouts.forgetLocal()
        }
        SimNet.onClient("say") { EngineerClient.msg(it) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> mc.execute { left() } }
    }

    /** What the fight does on your game: the splits, Odin's timers and solvers, Term Info. */
    private fun fx(what: String) {
        val o = runCatching { JsonParser.parseString(what).asJsonObject }.getOrNull() ?: return
        val n = o.get("n")?.asInt ?: 0
        when (o.get("fx")?.asString) {
            "splitsStart" -> {
                val split = o.get("split")?.asString ?: return
                EngineerClient.safely("p3sim splits") { com.engineerclient.splits.DungeonSplits.simStart(split, n) }
                EngineerClient.safely("p3sim arrows device") { Fight.resetArrowsDevice() }
            }
            "splitsStop" -> EngineerClient.safely("p3sim splits stop") { com.engineerclient.splits.DungeonSplits.simStop() }
            "masksReset" -> EngineerClient.safely("p3sim masks") { com.engineerclient.misc.OdinMasksUsed.resetTimers() }
            "termStart" -> EngineerClient.safely("p3sim term info") { com.engineerclient.practice.TermInfo.simStart(n) }
            "termClear" -> EngineerClient.safely("p3sim term info") { com.engineerclient.practice.TermInfo.simClearTime() }
            "sectionTime" -> EngineerClient.safely("p3sim term info") { com.engineerclient.practice.TermInfo.simSectionTime(n) }
        }
    }

    /** Server side: [fx] on every game in the sim (or [to]'s alone), in order with the fight's packets. */
    fun send(fx: String, n: Int = 0, split: String? = null, to: ServerPlayer? = null) {
        val o = JsonObject().also { it.addProperty("fx", fx); it.addProperty("n", n); split?.let { s -> it.addProperty("split", s) } }.toString()
        if (to != null) SimNet.send(to, "fx", o) else SimNet.sendAll("fx", o)
    }

    /** Client tick: say hello once in a sim; send our settings and plan as they change. */
    fun tick() {
        if (!P3Sim.inSim || mc.player == null) { if (helloSent) left(); return }
        if (!helloSent) {
            helloSent = true
            lastProfile = profileJson(withFiles = true)
            SimNet.send("hello", lastProfile!!)
            if (joiner) sendPlan(force = true)
            return
        }
        val prof = profileJson(withFiles = false)
        if (prof != lastProfile?.let { stripFiles(it) }) { lastProfile = prof; SimNet.send("profile", prof) }
        if (joiner && gotShared) {
            val now = PartySettings.snapshot(PartySettings.SHARED)
            val d = PartySettings.diff(lastShared, now)
            if (d.size() > 0) { lastShared = now; SimNet.send("shared", d.toString()) }
            sendPlan()
        }
    }

    private fun stripFiles(json: String): String = runCatching {
        val o = JsonParser.parseString(json).asJsonObject; o.remove("files"); o.toString()
    }.getOrDefault(json)

    private fun profileJson(withFiles: Boolean): String {
        val o = JsonObject()
        o.add("settings", PartySettings.snapshot(PartySettings.PERSONAL))
        if (withFiles) o.add("files", JsonObject().also { f -> PlayerFiles.NAMES.forEach { n -> PlayerFiles.readLocal(n)?.let { f.addProperty(n, it) } } })
        return o.toString()
    }

    /** A joiner's plan to the server: their jobs and spots, and the party's shared part as their menu has it. */
    private fun sendPlan(force: Boolean = false) {
        val j = P3Plan.partyJson().toString()
        if (!force && j == lastPlan) return
        lastPlan = j
        SimNet.send("plan", j)
    }

    /** Out of the sim: a joiner's own settings and plan back. */
    private fun left() {
        if (gotShared) ownShared?.let { PartySettings.apply(PartySettings.SHARED, it) }
        if (helloSent && !P3Sim.localSim) P3Plan.leaveParty()
        helloSent = false; gotShared = false; ownShared = null; lastShared = null; lastProfile = null; lastPlan = null
        team = emptyList(); me = null; party = false; phase = ""
    }
}

/**
 * A player's saved files the server needs (hotbar layout, loadouts): the host's are on this computer; a joiner's
 * come from their game ([PartyClient]'s hello) and go back to it when the server saves them.
 */
object PlayerFiles {
    const val HOTBAR = "p3sim-hotbar.json"
    const val LOADOUTS = "p3sim-loadouts.json"
    val NAMES = listOf(HOTBAR, LOADOUTS)

    private fun file(name: String) = java.io.File(net.minecraft.client.Minecraft.getInstance().gameDirectory, "config/engineerclient/$name")

    fun readLocal(name: String): String? = runCatching { file(name).takeIf { it.exists() }?.readText() }.getOrNull()

    fun writeLocal(name: String, text: String) {
        if (name !in NAMES) return
        EngineerClient.safely("p3sim file $name") { file(name).also { it.parentFile.mkdirs() }.writeText(text) }
    }

    /** [name] for the player it's about ([Sim.player]): the host's from disk, a joiner's as their game sent it. */
    fun read(name: String): String? {
        val p = Sim.acting?.takeIf { !Sim.isHost(it) } ?: return readLocal(name)
        return Players.profile(p).files[name]
    }

    /** Saves [name] for the player it's about: the host's to disk, a joiner's back to their game. */
    fun write(name: String, text: String) {
        val p = Sim.acting?.takeIf { !Sim.isHost(it) } ?: return writeLocal(name, text)
        Players.profile(p).files[name] = text
        SimNet.send(p, "file", JsonObject().also { it.addProperty("name", name); it.addProperty("text", text) }.toString())
    }

    /** Whose files these are (a cache key): the host's or a joiner's. */
    fun owner(): UUID? = Sim.acting?.takeIf { !Sim.isHost(it) }?.uuid
}

/** The menu's and keys' actions, run on the sim's server as whoever chose them. */
object SimActions {
    /** Sends [action] (with [args]) from this game's menu or keys to the sim's server. */
    fun send(action: String, vararg args: Pair<String, Any?>) {
        val o = JsonObject()
        o.addProperty("a", action)
        for ((k, v) in args) when (v) {
            null -> {}
            is Number -> o.addProperty(k, v)
            is Boolean -> o.addProperty(k, v)
            else -> o.addProperty(k, v.toString())
        }
        SimNet.send("act", o.toString())
    }

    fun run(p: ServerPlayer, data: String) {
        val o = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return
        fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString
        fun d(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asDouble
        fun b(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asBoolean == true
        val who = p.gameProfile.name
        when (s("a")) {
            "start" -> {
                val what = runCatching { Fight.Start.valueOf(s("what") ?: "P3") }.getOrNull() ?: return
                if (!Sim.isHost(p)) Sim.everyone { Sim.note("§f$who§7 started §f${what.label}§7.") }
                Fight.start(what)
            }
            "restart" -> {
                if (!Sim.isHost(p)) Sim.everyone { Sim.note("§f$who§7 restarted.") }
                if (Practice.active) Practice.restart() else Fight.start(Fight.lastStart)
            }
            "practiceRestart" -> Practice.restart()
            "stop" -> {
                if (!Sim.isHost(p)) Sim.everyone { Sim.note("§f$who§7 stopped the run.") }
                Fight.end()
            }
            "tp" -> {
                if (Sim.isHost(p)) GhostCapture.invalidate("a menu teleport")
                Sim.tp(p, d("x") ?: return, d("y") ?: return, d("z") ?: return, d("yaw")?.toFloat(), d("pitch")?.toFloat())
            }
            "hotbarSave" -> Sim.chat("§8[§6P3 Sim§8] " + HotbarLayout.save(p, b("p3")))
            "hotbarReset" -> Sim.chat("§8[§6P3 Sim§8] " + HotbarLayout.reset(b("p3")))
            "resetItems" -> SimItems.giveHotbar(p, b("p3"))
            "practiceCustom" -> Practice.startCustom()
            "practicePreset" -> Practice.startPreset(s("name") ?: return)
        }
    }
}
