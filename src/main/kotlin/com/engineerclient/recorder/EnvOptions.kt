package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.sounds.SoundSource

/**
 * The setup the recording was made with: every video, control, chat and sound option, every key
 * binding, the window, the resource packs and the loaded mods (`opts`, at the start of a session
 * and in every keyframe), then each option that changes (`opt`, polled once a second). Also the
 * vanilla view of the connection's health: every player's latency from the tab list (`net`, once
 * a second when it changed). Odin's ping and TPS are written with the scoreboard state elsewhere.
 *
 * Options are read by reflection over every `OptionInstance` getter on [Options] rather than a
 * hand-kept list, so options added by a game update are not silently missed. Game thread only.
 */
object EnvOptions {

    /** The session the last full `opts` line went to; a new one gets its own. */
    private var forSession: Any? = null
    private var last: Map<String, String> = emptyMap()
    private var ticks = 0
    private var loggedFailure = false

    /** Every public no-argument getter of [Options] that hands out an [OptionInstance], by name. */
    private val getters by lazy {
        Options::class.java.methods.filter { it.parameterCount == 0 && OptionInstance::class.java.isAssignableFrom(it.returnType) }
            .sortedBy { it.name }
    }

    fun install() {
        Rec.onKeyframe("options") { _ -> if (Rec.active) guard("opts") { full(EngineerClient.mc, Rec.keyframeId) } }
        ClientTickEvents.END_CLIENT_TICK.register { mc -> if (Rec.active) guard("poll") { poll(mc) } }
    }

    private fun poll(mc: Minecraft) {
        val s = Rec.session
        if (s !== forSession) { forSession = s; ticks = 0; full(mc, null); return }
        if (++ticks % 20 != 0) return
        val now = snapshot(mc)
        for ((k, v) in diff(last, now)) Rec.emit("opt", "\"name\":${RecorderFiles.q(k)},\"v\":$v")
        last = now
        val net = net(mc)
        if (net != null && Rec.changed("env.net", net)) Rec.emit("net", net)
    }

    /** The full `opts` line; [kf] tags it when written for a keyframe. */
    private fun full(mc: Minecraft, kf: Long?) {
        val snap = snapshot(mc)
        last = snap
        val sb = StringBuilder(8192)
        if (kf != null) sb.append("\"kf\":").append(kf).append(',')
        sb.append("\"o\":{")
        var first = true
        for ((k, v) in snap) {
            if (k.startsWith("key:") || k == "win" || k == "packs") continue
            if (!first) sb.append(','); first = false
            sb.append(RecorderFiles.q(k)).append(':').append(v)
        }
        sb.append("},\"keys\":{")
        first = true
        for ((k, v) in snap) {
            if (!k.startsWith("key:")) continue
            if (!first) sb.append(','); first = false
            sb.append(RecorderFiles.q(k.removePrefix("key:"))).append(':').append(v)
        }
        sb.append("},\"win\":").append(snap["win"] ?: "null")
        sb.append(",\"packs\":").append(snap["packs"] ?: "null")
        sb.append(",\"mods\":[")
        first = true
        for (m in FabricLoader.getInstance().allMods.sortedBy { it.metadata.id }) {
            if (!first) sb.append(','); first = false
            sb.append('[').append(RecorderFiles.q(m.metadata.id)).append(',').append(RecorderFiles.q(m.metadata.version.friendlyString)).append(']')
        }
        sb.append(']')
        Rec.emit("opts", sb.toString())
    }

    /**
     * Name -> JSON value of everything polled: each option, the sound volumes (`volume.<source>`),
     * a few plain fields, each key binding (`key:<name>`, its saved key), the window and the packs.
     */
    fun snapshot(mc: Minecraft): LinkedHashMap<String, String> {
        val o = mc.options
        val m = LinkedHashMap<String, String>()
        for (g in getters) {
            m[g.name] = runCatching { value((g.invoke(o) as OptionInstance<*>).get()) }.getOrElse { "{\"@error\":${RecorderFiles.q(it.toString())}}" }
        }
        m["effectiveRenderDistance"] = runCatching { o.effectiveRenderDistance.toString() }.getOrDefault("null")
        m["cameraType"] = value(o.cameraType)
        m["hideGui"] = o.hideGui.toString()
        m["smoothCamera"] = o.smoothCamera.toString()
        m["pauseOnLostFocus"] = o.pauseOnLostFocus.toString()
        m["advancedItemTooltips"] = o.advancedItemTooltips.toString()
        m["languageCode"] = value(o.languageCode)
        for (src in SoundSource.entries) m["volume.${src.getName()}"] = value(runCatching { o.getSoundSourceVolume(src) }.getOrNull())
        for (k in o.keyMappings) m["key:${k.name}"] = value(k.saveString())
        val w = mc.window
        m["win"] = "[${w.width},${w.height},${w.guiScaledWidth},${w.guiScaledHeight},${w.guiScale},${w.isFullscreen},${w.refreshRate}]"
        m["packs"] = mc.resourcePackRepository.selectedIds.joinToString(",", "[", "]") { RecorderFiles.q(it) }
        return m
    }

    /** `{"lat":[[name, latency ms]], "self": own latency}` from the tab list, or null when not connected. */
    private fun net(mc: Minecraft): String? {
        val conn = mc.connection ?: return null
        val sb = StringBuilder(512).append("\"lat\":[")
        var first = true
        for (pi in conn.onlinePlayers.sortedBy { it.profile.name() }) {
            if (!first) sb.append(','); first = false
            sb.append('[').append(RecorderFiles.q(pi.profile.name())).append(',').append(pi.latency).append(']')
        }
        val self = mc.player?.let { conn.getPlayerInfo(it.uuid) }?.latency
        return sb.append("],\"self\":").append(self?.toString() ?: "null").toString()
    }

    /** An option's value as JSON: numbers exactly, booleans, enums by constant name, anything else as its text. */
    fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Number -> StringBuilder().also { PacketJson.num(it, v) }.toString()
        is Enum<*> -> RecorderFiles.q(v.name)
        else -> RecorderFiles.q(v.toString())
    }

    /** The entries of [now] that are new or differ from [before]; ones gone from [now] come back as `null`. */
    fun diff(before: Map<String, String>, now: Map<String, String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(0)
        for ((k, v) in now) if (before[k] != v) out += k to v
        for (k in before.keys) if (k !in now) out += k to "null"
        return out
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            if (!loggedFailure) { loggedFailure = true; EngineerClient.logger.error("[ec] recorder options $what failed", t) }
            Rec.emit("error", "\"p\":${RecorderFiles.q("env:$what")},\"err\":${RecorderFiles.q(t.toString())}")
        }
    }
}
