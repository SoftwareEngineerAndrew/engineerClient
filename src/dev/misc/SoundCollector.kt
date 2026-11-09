package com.devgineerclient.misc

import com.devgineerclient.DevgineerClient
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/**
 * Every sound the game plays, in chat (the server's, the client's own and every mod's: SoundPlayTapMixin on SoundEngine.play).
 * Each sound gets a number, counting up from the first one heard this launch; /sound <number> stops (or brings back) its lines;
 * /sound <number> a hides it and adds it to the mute list ([isMuted], which Sound Editor uses); /sound mutes lists it.
 */
object SoundCollector : Module(
    name = "Sound Collector",
    key = null,
    category = Category.custom("Devgineer Client", 860, 10),
    description = "Puts every sound the game plays in chat, each with a number. /sound <number> hides that sound (again to show it).",
) {
    private val ids = HashMap<String, Int>()
    private val names = ArrayList<String>()
    private val hidden = HashSet<Int>()
    /**
     * The mute list (/sound <number> a), by name, kept in config/devgineerclient/muted-sounds.txt: Coffee Client's Sound Editor,
     * while on, stops these sounds from playing at all.
     */
    private val mutedFile by lazy { DevgineerClient.mc.gameDirectory.toPath().resolve("config/devgineerclient/muted-sounds.txt") }
    @Volatile private var muted: Set<String> = emptySet()
    @JvmStatic fun isMuted(id: String): Boolean = muted.isNotEmpty() && id.removePrefix("minecraft:") in muted
    private fun saveMuted(next: Set<String>) {
        muted = next
        runCatching { java.nio.file.Files.createDirectories(mutedFile.parent); java.nio.file.Files.writeString(mutedFile, next.joinToString("\n", postfix = if (next.isEmpty()) "" else "\n")) }
    }

    /** SoundPlayTapMixin, as each play returns: only sounds that really started (not muted, silent or unknown ones). */
    @JvmStatic
    fun played(inst: SoundInstance?, result: SoundEngine.PlayResult?) {
        if (!enabled || inst == null || result != SoundEngine.PlayResult.STARTED) return
        val name = inst.identifier.toString().removePrefix("minecraft:")
        val id = ids.getOrPut(name) { names += name; names.size }
        if (id in hidden) return
        val line = Component.literal("§7[$id] §f$name §8(${inst.source.name.lowercase()}, vol %.2f, pitch %.2f)".format(java.util.Locale.ROOT, inst.volume, inst.pitch))
            .withStyle { it.withClickEvent(ClickEvent.RunCommand("/sound $id")).withHoverEvent(HoverEvent.ShowText(Component.literal("§7Click to hide this sound"))) }
        DevgineerClient.chat(line)
    }

    fun install() {
        muted = runCatching { java.nio.file.Files.readAllLines(mutedFile).map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // /sound mutes: the mute list, each name clickable to take it off.
            dispatcher.register(literal("sound").then(literal("mutes").executes {
                if (muted.isEmpty()) DevgineerClient.msg("§7The mute list is empty.")
                else {
                    DevgineerClient.msg("§7Muted (click one to unmute it):")
                    for (name in muted) DevgineerClient.chat(Component.literal("§8 - §f$name").withStyle {
                        it.withClickEvent(ClickEvent.RunCommand("/sound unmute $name")).withHoverEvent(HoverEvent.ShowText(Component.literal("§7Click to unmute")))
                    })
                }
                1
            }).then(literal("unmute").then(argument("name", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes { c ->
                val name = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "name").trim().removePrefix("minecraft:")
                if (name !in muted) DevgineerClient.msg("§c$name isn't muted.") else { saveMuted(muted - name); DevgineerClient.msg("§aUnmuted $name.") }
                1
            })))
            dispatcher.register(literal("sound").then(argument("id", IntegerArgumentType.integer(1)).executes { c ->
                val id = IntegerArgumentType.getInteger(c, "id")
                val name = names.getOrNull(id - 1)
                when {
                    name == null -> DevgineerClient.msg("§cNo sound $id yet.")
                    hidden.remove(id) -> DevgineerClient.msg("§aShowing [$id] $name again.")
                    else -> { hidden += id; DevgineerClient.msg("§7Hiding [$id] $name. §8(/sound $id to show it again)") }
                }
                1
            }.then(literal("a").executes { c ->
                val id = IntegerArgumentType.getInteger(c, "id")
                val name = names.getOrNull(id - 1)
                if (name == null) DevgineerClient.msg("§cNo sound $id yet.")
                else { hidden += id; saveMuted(muted + name); DevgineerClient.msg("§7Hiding [$id] $name and added it to the mute list. §8(/sound mutes to see or undo)") }
                1
            })))
        }
    }
}
