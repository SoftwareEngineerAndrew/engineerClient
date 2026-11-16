package com.adm.commands

import com.github.stivais.commodore.Commodore
import com.github.stivais.commodore.utils.SyntaxException
import com.adm.AdmMod.mc
import com.adm.AdmMod.scope
import com.adm.utils.modMessage
import com.adm.utils.network.WebUtils.fetchString
import kotlinx.coroutines.launch

val soopyCommand = Commodore("soopycmd", "spcmd", "spc") {
    val commands = listOf(
        "auctions", "bestiary", "bank", "classaverage", "currdungeon", "dojo", "dungeon", "essence", "faction",
        "guildof", "kuudra", "nucleus", "nw", "overflowskillaverage", "overflowskills", "pet", "rtca", "sblvl",
        "secrets", "skillaverage", "skills"
    )

    literal("help").runs {
        modMessage("Available commands for /spcmd:\n ${commands.joinToString()}")
    }

    executable {
        param("command") {
            parser { string: String ->
                if (!commands.contains(string)) throw SyntaxException("Invalid command.")
                string
            }
            suggests { commands }
        }

        runs { command: String, user: String? ->
            val player = user ?: mc.player?.name
            modMessage("Running command...")
            scope.launch {
                fetchString("https://soopy.dev/api/soopyv2/botcommand?m=$command&u=$player").fold(
                    { modMessage(it) },
                    { e -> modMessage("Failed to fetch data: ${e.message}") }
                )
            }
        }
    }
}