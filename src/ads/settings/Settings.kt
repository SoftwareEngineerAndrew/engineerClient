package com.engineerclient.ads.settings

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * ADS's own settings, so ADS runs without Odin. The shape follows Odin's settings (what the sim's code was written
 * against): a [Settings] owner adds each with `+`, the code reads `.value` (selectors also `.index`), and a setting
 * declared with `by` reads as its value. Each owner saves to its own file in config/engineerclient.
 */
abstract class Setting<T>(val name: String, val default: T, val description: String) : ReadWriteProperty<Any?, T> {
    open var value: T = default
    /** Shown in the menus only while this is true (Odin's withDependency). */
    var shown: () -> Boolean = { true }

    abstract fun write(): JsonElement
    abstract fun read(json: JsonElement)
    open fun reset() { value = default }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { this.value = value }
}

fun <S : Setting<*>> S.withDependency(shown: () -> Boolean): S = also { it.shown = shown }

class BooleanSetting(name: String, default: Boolean = false, desc: String = "") : Setting<Boolean>(name, default, desc) {
    override fun write(): JsonElement = JsonPrimitive(value)
    override fun read(json: JsonElement) { value = json.asBoolean }
}

/**
 * A number in [min]..[max] in steps of [step]; Int or Double, whichever the default is. Made as
 * `NumberSetting(name, default, range, ...)` (the companion's invoke, so the type comes from the default).
 */
class NumberSetting<T : Number> private constructor(
    name: String, default: T, val min: Double, val max: Double, val step: Double, val unit: String, desc: String,
    private val of: (Double) -> T,
) : Setting<T>(name, default, desc) {
    companion object {
        operator fun invoke(name: String, default: Int, range: IntRange, step: Int = 1, unit: String = "", desc: String = ""): NumberSetting<Int> =
            NumberSetting(name, default, range.first.toDouble(), range.last.toDouble(), step.toDouble(), unit, desc) { it.roundToInt() }
        operator fun invoke(name: String, default: Double, range: ClosedFloatingPointRange<Double>, step: Double = 0.1, unit: String = "", desc: String = ""): NumberSetting<Double> =
            NumberSetting(name, default, range.start, range.endInclusive, step, unit, desc) { it }
    }

    override var value: T = default
        set(v) { field = of(snap(v.toDouble())) }

    /** [v] clamped to the range and rounded to a step. */
    fun snap(v: Double): Double {
        val stepped = min + Math.round((v.coerceIn(min, max) - min) / step) * step
        return stepped.coerceIn(min, max)
    }

    fun set(v: Double) { value = of(v) }
    val isInt: Boolean get() = default is Int

    override fun write(): JsonElement = JsonPrimitive(value)
    override fun read(json: JsonElement) { set(json.asDouble) }
}

/** One of an enum's constants; [index] is its position, as the menus and party sync use it. */
class SelectorSetting<E : Enum<E>>(name: String, default: E, desc: String = "") : Setting<E>(name, default, desc) {
    val options: List<E> = default.declaringJavaClass.enumConstants.toList()
    var index: Int
        get() = options.indexOf(value)
        set(i) { value = options[Math.floorMod(i, options.size)] }

    override fun write(): JsonElement = JsonPrimitive(value.name)
    override fun read(json: JsonElement) { options.firstOrNull { it.name == json.asString }?.let { value = it } }
}

/** A key; [onPress] runs when it goes down in game (not while a screen is open). */
class KeybindSetting(name: String, default: InputConstants.Key, desc: String = "") : Setting<InputConstants.Key>(name, default, desc) {
    var action: () -> Unit = {}
    fun onPress(action: () -> Unit): KeybindSetting = also { it.action = action }

    override fun write(): JsonElement = JsonPrimitive(value.name)
    override fun read(json: JsonElement) { value = runCatching { InputConstants.getKey(json.asString) }.getOrDefault(default) }
}

/** An owner of settings, saved together to config/engineerclient/[file]. */
abstract class Settings(val title: String, private val file: String) {
    val all = mutableListOf<Setting<*>>()

    operator fun <S : Setting<*>> S.unaryPlus(): S = also { all += it }

    /** For settings declared with `by`: adds it to [all] too. */
    operator fun <T, S : Setting<T>> S.provideDelegate(thisRef: Any?, property: KProperty<*>): S = also { if (it !in all) all += it }

    private val path: Path get() = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve(file)

    /** Reads the file; false when there wasn't one yet (a first start, or settings still in Odin's file). */
    fun load(): Boolean {
        try {
            if (!Files.exists(path)) return false
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            readFrom(json)
            return true
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ads] failed to load $file, keeping defaults", t)
            return false
        }
    }

    /** Applies the values in [json] (by setting name) that it has. */
    fun readFrom(json: JsonObject) {
        for (s in all) json.get(s.name)?.let { runCatching { s.read(it) } }
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val json = JsonObject()
            for (s in all) json.add(s.name, s.write())
            val tmp = path.resolveSibling("$file.tmp")
            Files.writeString(tmp, GSON.toJson(json))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ads] failed to save $file", t)
        }
    }

    /** Polls this owner's keybinds every client tick, so a key bound here works without Odin's keybind system. */
    fun registerKeybinds() {
        val down = HashSet<KeybindSetting>()
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            for (k in all) {
                if (k !is KeybindSetting || k.value == InputConstants.UNKNOWN) continue
                val pressed = mc.gui.screen() == null && isDown(mc, k.value)
                if (pressed && down.add(k)) EngineerClient.safely("ads key ${k.name}") { k.action() }
                if (!pressed) down.remove(k)
            }
        }
    }

    private fun isDown(mc: Minecraft, key: InputConstants.Key): Boolean = when (key.type) {
        // 26.3 reads the mouse through SDL: left, middle, right are 1, 2, 3; the mouse handler only knows those three.
        InputConstants.Type.MOUSE -> when (key.value) {
            InputConstants.MOUSE_BUTTON_LEFT -> mc.mouseHandler.isLeftPressed
            InputConstants.MOUSE_BUTTON_MIDDLE -> mc.mouseHandler.isMiddlePressed
            InputConstants.MOUSE_BUTTON_RIGHT -> mc.mouseHandler.isRightPressed
            else -> false
        }
        else -> InputConstants.isKeyDown(key.value)
    }

    private companion object {
        val GSON = GsonBuilder().setPrettyPrinting().create()
    }
}
