package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * Every block the fight changes goes through here, so a restart (or leaving the world) can put the
 * arena back exactly as built. Also plays the arena's scripted animations (gates, doors, the core,
 * the floors between phases) frame by frame as recorded on Hypixel (`anims-*.json`, extracted from
 * Better PF runs).
 */
object Blocks {
    private val touched = LinkedHashSet<BlockPos>()

    fun set(pos: BlockPos, state: BlockState) {
        val level = SimServer.level ?: return
        touched += pos.immutable()
        Arena.set(level, pos, state)
    }

    fun set(x: Int, y: Int, z: Int, state: BlockState) = set(BlockPos(x, y, z), state)

    fun get(pos: BlockPos): BlockState? = SimServer.level?.getBlockState(pos)

    /** Puts back every block the fight changed. */
    fun restoreAll() {
        val level = SimServer.level ?: return
        anims.clear()
        touched.forEach { Arena.restore(level, it) }
        touched.clear()
    }

    // ------------------------------------------------------------------ recorded animations

    class Frame(val dt: Int, val pos: BlockPos, val state: BlockState)
    class Anim(val name: String, val event: String, val frames: List<Frame>) {
        val positions: Set<BlockPos> by lazy { frames.mapTo(HashSet()) { it.pos } }
        val length: Int get() = frames.lastOrNull()?.dt ?: 0
    }

    private val library: Map<String, Anim> by lazy {
        val out = HashMap<String, Anim>()
        for (file in listOf("anims-p3.json", "anims-p124.json")) EngineerClient.safely("p3sim $file") {
            val text = Blocks::class.java.getResourceAsStream("/assets/engineerclient/p3sim/$file")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@safely
            val root = JsonParser.parseString(text).asJsonObject
            for ((name, v) in root.entrySet()) {
                if (name.startsWith("_") || !v.isJsonObject) continue
                val o = v.asJsonObject
                val fr = o.getAsJsonArray("frames") ?: continue
                val states = HashMap<String, BlockState>()
                val frames = fr.map { e ->
                    val a = e.asJsonArray
                    val s = a[4].asString
                    Frame(a[0].asInt, BlockPos(a[1].asInt, a[2].asInt, a[3].asInt), states.getOrPut(s) { Arena.parse(if (':' in s) s else "minecraft:$s") })
                }.sortedBy { it.dt }
                out[name] = Anim(name, o.get("event")?.asString ?: "", frames)
            }
        }
        out
    }

    fun anim(name: String): Anim? = library[name]

    /** Extra data in an animation file (pillar structure and the like). */
    fun extra(file: String, key: String): JsonObject? = try {
        Blocks::class.java.getResourceAsStream("/assets/engineerclient/p3sim/$file")?.use {
            JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject.getAsJsonObject(key)
        }
    } catch (t: Throwable) { null }

    private class Playing(val anim: Anim, val start: Int, var next: Int = 0)
    private val anims = ArrayList<Playing>()

    /** Starts [name] now (its frame 0 this tick). [skip]: start that many ticks in (catching up). */
    fun play(name: String, skip: Int = 0) {
        val a = library[name] ?: run { EngineerClient.logger.warn("[p3sim] no animation {}", name); return }
        val p = Playing(a, Fight.serverTick - skip)
        anims += p
        advance(p)
    }

    /** Jumps [name] to its end state at once (starting a phase past it). */
    fun finish(name: String) {
        val a = library[name] ?: return
        a.frames.forEach { set(it.pos, it.state) }
    }

    fun isPlaying(name: String) = anims.any { it.anim.name == name }

    fun tick() {
        if (anims.isEmpty()) return
        anims.toList().forEach { advance(it) }
        anims.removeAll { it.next >= it.anim.frames.size }
    }

    private fun advance(p: Playing) {
        val now = Fight.serverTick - p.start
        val f = p.anim.frames
        while (p.next < f.size && f[p.next].dt <= now) { set(f[p.next].pos, f[p.next].state); p.next++ }
    }
}
