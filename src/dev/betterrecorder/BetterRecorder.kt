package com.devgineerclient.betterrecorder

import com.devgineerclient.DevgineerClient
import com.engineerclient.adr.ADR
import com.engineerclient.adr.RunRecorder
import com.mojang.brigadier.arguments.StringArgumentType
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.itemId
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/**
 * Better Recorder: what All Data (undonecoffee.com/alldata) still needs and ADR doesn't record - the numbers behind
 * combat. A layer on ADR: it records only while ADR is recording a Hypixel dungeon run, counts ticks with ADR's own
 * clock, and its file is named after ADR's, so each line lines up with the ADR run it belongs to. Local only,
 * nothing is uploaded. Off by default.
 *
 * Files: <game dir>/devgineerclient-recordings/better/<ADR's yyyy-MM-dd_HH-mm-ss>.br.jsonl.gz, gzipped JSON Lines,
 * every line {"k":kind,"t":ADR's tick,...}. Only changes are written:
 *
 *  - meta {"adr":stamp,"startMs","v":2}; mark {"m":note} (/brec mark [note])
 *  - hp {"d":[[entity id, health, max health on first sight], ...]}: every mob's health as the server sends it
 *    (players and armor stands left out)
 *  - dmg {"id","type","by","via"}: the server's damage event - who was hurt, the damage type, the entity it came
 *    from and the one that hit (a projectile); -1 none
 *  - atk {"id"}: you attacked that entity
 *  - dtag {"id","x","y","z","m"}: a damage number (an armor stand's name of digits) as it appeared
 *  - me {"hp","food","sat","abs"}: your health, hunger and absorption
 *  - att {"d":[[attribute, base, [[modifier, amount, operation], ...]], ...]}: your attributes that changed
 *  - fx {"e","a","d"} an effect on you (amplifier, ticks); {"e","x":1} removed
 *  - vel {"v":[x,y,z]}: the server set your motion (knockback); boom {"at","r","kb"}: an explosion
 *  - item {"s","id","name","lore":[...]}: your hotbar (0-8), armour (36 feet - 39 head) and offhand (40) item,
 *    with its lore, when it changes
 *  - snd {"d":[[sound, x, y, z, volume, pitch] or [sound, entity id, volume, pitch], ...]}: every sound the
 *    server played that tick
 */
object BetterRecorder : Module(
    name = "Better Recorder",
    key = null,
    category = Category.custom("Devgineer Client", 860, 10),
    description = "Records the numbers behind combat that ADR doesn't (mob health, damage events, your stats and gear, sounds) into a small file next to each ADR run, for All Data. Records only while ADR does; local only.",
) {
    private val openFolder by ActionSetting("Open Folder", desc = "Opens the folder the recordings are saved in.") {
        DevgineerClient.safely("better recorder folder") { Files.createDirectories(dir); com.mojang.blaze3d.Blaze3D.openPath(dir) }
    }

    private val dir get() = DevgineerClient.mc.gameDirectory.toPath().resolve("devgineerclient-recordings").resolve("better")

    private class Session(val run: RunRecorder, val writer: Writer) {
        val hp = HashMap<Int, Float>()
        val names = HashMap<Int, String>()
        val items = HashMap<Int, ItemStack>()
        val itemText = HashMap<Int, String>()
        val attrs = HashMap<String, String>()
        var abs = -1f
        val sounds = StringBuilder()
        var lines = 0L
    }

    private var session: Session? = null
    private val io = Executors.newSingleThreadExecutor { Thread(it, "better-recorder-writer").apply { isDaemon = true } }
    private val pending = StringBuilder()

    init {
        on<TickEvent.End> { DevgineerClient.safely("better recorder tick") { tick() } }
        ClientLifecycleEvents.CLIENT_STOPPING.register { DevgineerClient.safely("better recorder exit") { stop() } }

        // Packets: read on the network thread, written on the game thread at ADR's tick.
        onReceive<ClientboundDamageEventPacket> {
            val id = entityId(); val type = sourceType().unwrapKey().map { it.identifier().path }.orElse("?"); val by = sourceCauseId(); val via = sourceDirectId()
            later { line("dmg", ""","id":$id,"type":${q(type)},"by":$by,"via":$via""") }
        }
        onSend<ServerboundAttackPacket> { val id = entityId(); later { line("atk", ""","id":$id""") } }
        onReceive<ClientboundSetHealthPacket> {
            val h = health; val f = food; val s = saturation
            later { line("me", ""","hp":${f1(h)},"food":$f,"sat":${f1(s)}""") }
        }
        onReceive<ClientboundUpdateAttributesPacket> {
            val id = entityId; val values = values
            later { if (id == DevgineerClient.mc.player?.id) attributes(values) }
        }
        onReceive<ClientboundUpdateMobEffectPacket> {
            val id = entityId; val e = effect.unwrapKey().map { it.identifier().path }.orElse("?"); val a = effectAmplifier; val d = effectDurationTicks
            later { if (id == DevgineerClient.mc.player?.id) line("fx", ""","e":${q(e)},"a":$a,"d":$d""") }
        }
        onReceive<ClientboundRemoveMobEffectPacket> {
            val id = entityId(); val e = effect().unwrapKey().map { it.identifier().path }.orElse("?")
            later { if (id == DevgineerClient.mc.player?.id) line("fx", ""","e":${q(e)},"x":1""") }
        }
        onReceive<ClientboundSetEntityMotionPacket> {
            val id = id(); val v = movement()
            later { if (id == DevgineerClient.mc.player?.id) line("vel", ""","v":[${f3(v.x)},${f3(v.y)},${f3(v.z)}]""") }
        }
        onReceive<ClientboundExplodePacket> {
            val c = center(); val r = radius(); val kb = playerKnockback().orElse(null)
            later { line("boom", ""","at":[${f2(c.x)},${f2(c.y)},${f2(c.z)}],"r":${f2(r.toDouble())}""" + (kb?.let { ""","kb":[${f3(it.x)},${f3(it.y)},${f3(it.z)}]""" } ?: "")) }
        }
        onReceive<ClientboundSoundPacket> {
            val row = "[${q(sound.unwrapKey().map { it.identifier().path }.orElse("?"))},${f2(x)},${f2(y)},${f2(z)},${f2(volume.toDouble())},${f2(pitch.toDouble())}]"
            later { sound(row) }
        }
        onReceive<ClientboundSoundEntityPacket> {
            val row = "[${q(sound.unwrapKey().map { it.identifier().path }.orElse("?"))},$id,${f2(volume.toDouble())},${f2(pitch.toDouble())}]"
            later { sound(row) }
        }

        ClientCommandRegistrationCallback.EVENT.register { d, _ ->
            d.register(literal("brec")
                .executes { DevgineerClient.msg(status()); 1 }
                .then(literal("mark").executes { mark(null); 1 }.then(argument("note", StringArgumentType.greedyString()).executes { c -> mark(StringArgumentType.getString(c, "note")); 1 })))
        }
    }

    override fun onDisable() {
        stop()
        super.onDisable()
    }

    private fun later(block: () -> Unit) {
        if (session == null) return
        DevgineerClient.mc.execute { DevgineerClient.safely("better recorder") { if (session != null) block() } }
    }

    private fun tick() {
        val run = if (enabled) ADR.recording else null
        if (session?.run !== run) { stop(); if (run != null) start(run) }
        val s = session ?: return
        health(s)
        me(s)
        gear(s)
        if (s.sounds.isNotEmpty()) { line("snd", ""","d":[${s.sounds}]"""); s.sounds.setLength(0) }
        if (pending.isNotEmpty()) {
            val chunk = pending.toString(); pending.setLength(0)
            val w = s.writer
            io.execute { runCatching { w.write(chunk) } }
        }
    }

    private fun start(run: RunRecorder) {
        Files.createDirectories(dir)
        val out = Files.newOutputStream(dir.resolve("${run.stamp}.br.jsonl.gz"))
        session = Session(run, OutputStreamWriter(GZIPOutputStream(out, 1 shl 16), Charsets.UTF_8))
        line("meta", ""","adr":${q(run.stamp)},"startMs":${System.currentTimeMillis()},"v":2""")
    }

    private fun stop() {
        val s = session ?: return
        session = null
        val chunk = pending.toString(); pending.setLength(0)
        io.execute { runCatching { s.writer.write(chunk); s.writer.close() } }
    }

    private fun line(kind: String, body: String) {
        val s = session ?: return
        s.lines++
        pending.append("{\"k\":\"").append(kind).append("\",\"t\":").append(s.run.currentTick).append(body).append("}\n")
    }

    private fun sound(row: String) {
        val s = session ?: return
        if (s.sounds.isNotEmpty()) s.sounds.append(',')
        s.sounds.append(row)
    }

    /** Every mob's health, when it changes; damage numbers as they appear. */
    private fun health(s: Session) {
        val level = DevgineerClient.mc.level ?: return
        val rows = StringBuilder()
        for (e in level.entitiesForRendering()) {
            if (e is ArmorStand) { damageTag(s, e); continue }
            if (e !is LivingEntity || e is Player) continue
            val h = e.health
            val was = s.hp.put(e.id, h)
            if (was == h) continue
            if (rows.isNotEmpty()) rows.append(',')
            rows.append('[').append(e.id).append(',').append(f1(h.toDouble()))
            if (was == null) rows.append(',').append(f1(e.maxHealth.toDouble()))
            rows.append(']')
        }
        if (rows.isNotEmpty()) line("hp", ""","d":[$rows]""")
    }

    private fun damageTag(s: Session, e: ArmorStand) {
        val name = e.customName?.string ?: return
        if (s.names.put(e.id, name) == name) return
        if (!DAMAGE.matches(name)) return
        line("dtag", ""","id":${e.id},"x":${f2(e.x)},"y":${f2(e.y)},"z":${f2(e.z)},"m":${q(name)}""")
    }

    private fun me(s: Session) {
        val abs = DevgineerClient.mc.player?.absorptionAmount ?: return
        if (abs == s.abs) return
        s.abs = abs
        line("me", ""","abs":${f1(abs.toDouble())}""")
    }

    private fun attributes(values: List<ClientboundUpdateAttributesPacket.AttributeSnapshot>) {
        val s = session ?: return
        val rows = values.mapNotNull { a ->
            val name = a.attribute().unwrapKey().map { it.identifier().path }.orElse("?")
            val mods = a.modifiers().joinToString(",") { m -> "[${q(m.id().path)},${f3(m.amount())},${m.operation().ordinal}]" }
            val row = "[${q(name)},${f3(a.base())},[$mods]]"
            if (s.attrs.put(name, row) == row) null else row
        }
        if (rows.isNotEmpty()) line("att", ""","d":[${rows.joinToString(",")}]""")
    }

    /** Your hotbar, armour and offhand, with lore, when an item changes (the same stack object is skipped). */
    private fun gear(s: Session) {
        val player = DevgineerClient.mc.player ?: return
        for (slot in GEAR_SLOTS) {
            val stack = when (slot) {
                in 0..8 -> player.inventory.getItem(slot)
                40 -> player.getItemBySlot(EquipmentSlot.OFFHAND)
                else -> player.getItemBySlot(ARMOR[slot - 36])
            }
            if (s.items.put(slot, stack) === stack) continue
            val lore = stack.get(DataComponents.LORE)?.lines()?.joinToString(",") { q(it.string) } ?: ""
            val text = if (stack.isEmpty) "" else ""","id":${q(stack.itemId)},"name":${q(stack.hoverName.string)},"lore":[$lore]"""
            if (s.itemText.put(slot, text) == text) continue
            line("item", ""","s":$slot$text""")
        }
    }

    private fun mark(note: String?) {
        if (session == null) { DevgineerClient.msg("§7Better Recorder: not recording."); return }
        line("mark", ""","m":${q(note ?: "")}""")
        DevgineerClient.msg("§aMarked§7 at tick ${session?.run?.currentTick}${if (note != null) ": $note" else ""}")
    }

    private fun status(): String {
        val s = session ?: return "§7Better Recorder: not recording${if (!enabled) " (off)" else " (it records while ADR records a Hypixel dungeon run)"}."
        return "§aBetter Recorder§7: ${s.run.stamp}.br.jsonl.gz, tick ${s.run.currentTick}, ${s.lines} lines."
    }

    private val DAMAGE = Regex("""^[^\d\s]*[\d,]+[^\d\s]*$""")
    private val GEAR_SLOTS = (0..8) + (36..40)
    private val ARMOR = arrayOf(EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)

    private fun f1(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun f1(v: Float) = f1(v.toDouble())
    private fun f2(v: Double) = String.format(Locale.ROOT, "%.2f", v)
    private fun f3(v: Double) = String.format(Locale.ROOT, "%.3f", v)
    private fun q(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch < ' ' -> sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }
}
