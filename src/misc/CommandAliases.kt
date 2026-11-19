package com.engineerclient.misc

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.StringSetting
import com.adm.features.Category
import com.adm.features.Module
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import java.util.concurrent.CompletableFuture

object CommandAliases : Module(
    name = "Command Aliases",
    category = Category.SKYBLOCK,
    description = "Your own short commands: /alias runs its command, with anything typed after it.",
    key = null,
) {
    private const val SLOTS = 20

    private class Slot(val alias: StringSetting, val command: StringSetting) {
        fun used() = alias.value.isNotBlank() || command.value.isNotBlank()
        fun name() = alias.value.trim().removePrefix("/")
    }

    private val slots = ArrayList<Slot>()
    private var running = false
    private var suggesting = false
    private var lastTyped = ""
    private var lastRunAt = 0L

    init {
        for (i in 1..SLOTS) {
            val previous = slots.lastOrNull()
            val shown = { previous == null || previous.used() }
            val alias = StringSetting("Alias $i", "", 32, desc = "What you type, without the slash.", placeholder = "d")
            val command = StringSetting("Command $i", "", 200, desc = "What Alias $i runs.", placeholder = "warp dungeon_hub")
            registerSetting(alias.withDependency(shown))
            registerSetting(command.withDependency(shown))
            slots += Slot(alias, command)
        }

        ClientSendMessageEvents.ALLOW_COMMAND.register { typed -> !(enabled && !running && run(typed)) }

        // Registered as client commands only for tab completion; a changed alias completes after the next join.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            for (slot in slots) {
                val name = slot.name()
                if (name.isEmpty() || ' ' in name || slot.command.value.isBlank() || dispatcher.root.getChild(name) != null) continue
                dispatcher.register(literal(name)
                    .executes { if (enabled) run(name); 1 }
                    .then(argument("args", StringArgumentType.greedyString())
                        .suggests { _, builder -> if (enabled) suggest(slot, builder) else builder.buildFuture() }
                        .executes { ctx -> if (enabled) run("$name ${StringArgumentType.getString(ctx, "args")}"); 1 }))
            }
        }
    }

    private fun target(slot: Slot) = com.engineerclient.EngineerClient.admCommand(slot.command.value.trim().removePrefix("/"))

    // Completes the target command with what was typed after the alias, then moves the results back under the alias.
    private fun suggest(slot: Slot, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val connection = mc.connection ?: return builder.buildFuture()
        if (suggesting) return builder.buildFuture()
        val target = target(slot)
        val start = target.length + 1
        val shift = builder.start - start
        val commands = connection.commands
        suggesting = true
        val future = try {
            commands.getCompletionSuggestions(commands.parse("$target ${builder.remaining}", connection.suggestionsProvider))
        } finally { suggesting = false }
        return future.thenApply { found ->
            val moved = found.list.filter { it.range.start >= start }
                .map { Suggestion(StringRange(it.range.start + shift, it.range.end + shift), it.text, it.tooltip) }
            if (moved.isEmpty()) Suggestions.empty().join()
            else Suggestions(StringRange(moved.minOf { it.range.start }, moved.maxOf { it.range.end }), moved)
        }
    }

    private fun run(typed: String): Boolean {
        val word = typed.substringBefore(' ')
        val rest = typed.substringAfter(' ', "")
        val slot = slots.firstOrNull { it.name().equals(word, ignoreCase = true) && it.command.value.isNotBlank() } ?: return false
        // The send hook and the client command can both see one typed alias.
        val now = System.currentTimeMillis()
        if (typed == lastTyped && now - lastRunAt < 100) return true
        lastTyped = typed
        lastRunAt = now
        val target = target(slot)
        // An alias whose command is itself an alias would loop.
        running = true
        try { mc.player?.connection?.sendCommand(if (rest.isBlank()) target else "$target $rest") } finally { running = false }
        return true
    }
}
