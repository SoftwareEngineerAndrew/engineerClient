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
 * Each sound gets a number, counting up from the first one heard this launch; /sound <number> stops (or brings back) its lines.
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
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("sound").then(argument("id", IntegerArgumentType.integer(1)).executes { c ->
                val id = IntegerArgumentType.getInteger(c, "id")
                val name = names.getOrNull(id - 1)
                when {
                    name == null -> DevgineerClient.msg("§cNo sound $id yet.")
                    hidden.remove(id) -> DevgineerClient.msg("§aShowing [$id] $name again.")
                    else -> { hidden += id; DevgineerClient.msg("§7Hiding [$id] $name. §8(/sound $id to show it again)") }
                }
                1
            }))
        }
    }
}
