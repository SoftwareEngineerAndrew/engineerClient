package com.engineerclient

import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConfigMigrationTest {
    private fun migrate(brSettings: String): com.google.gson.JsonObject {
        val odin = Files.createTempDirectory("odin")
        Files.createDirectories(odin.resolve("addons"))
        val file = odin.resolve("addons").resolve("engineerclient.json")
        Files.writeString(file, """[{"name":"BR Roles","enabled":true,"settings":$brSettings}]""")
        ConfigMigration.run(odin)
        val modules = JsonParser.parseString(Files.readString(file)).asJsonArray
        return modules.map { it.asJsonObject }.first { it["name"].asString == "BR Roles" }.getAsJsonObject("settings")
    }

    @Test fun doorerKillsOnIsAlways() {
        val s = migrate("""{"Doorer Kills":true}""")
        assertEquals("ALWAYS", s["Door Kills"].asString)
        assertFalse(s.has("Doorer Kills"))
    }

    @Test fun doorerKillsOffIsNever() {
        assertEquals("NEVER", migrate("""{"Doorer Kills":false}""")["Door Kills"].asString)
    }

    @Test fun doorKillsAlreadySetIsKept() {
        assertEquals("AFTER_FAIRY", migrate("""{"Doorer Kills":true,"Door Kills":"AFTER_FAIRY"}""")["Door Kills"].asString)
    }

    @Test fun necronDropHudIsNecronDps() {
        val odin = Files.createTempDirectory("odin")
        Files.createDirectories(odin.resolve("addons"))
        val file = odin.resolve("addons").resolve("engineerclient.json")
        Files.writeString(file, """[{"name":"Timers","enabled":true,"settings":{"Necron Drop":{"enabled":false,"x":12}}}]""")
        ConfigMigration.run(odin)
        val t = JsonParser.parseString(Files.readString(file)).asJsonArray.first().asJsonObject.getAsJsonObject("settings")
        assertFalse(t.has("Necron Drop"))
        assertEquals(12, t.getAsJsonObject("Necron DPS")["x"].asInt)
    }

    private fun migrateAds(adsSettings: String): com.google.gson.JsonObject {
        val odin = Files.createTempDirectory("odin")
        Files.createDirectories(odin.resolve("addons"))
        val file = odin.resolve("addons").resolve("engineerclient.json")
        Files.writeString(file, """[{"name":"ADS","enabled":true,"settings":$adsSettings}]""")
        ConfigMigration.run(odin)
        return JsonParser.parseString(Files.readString(file)).asJsonArray.first().asJsonObject.getAsJsonObject("settings")
    }

    @Test fun shortbowCooldownOfTwoIsDropped() {
        val s = migrateAds("""{"Shortbow Cooldown":2.0}""")
        assertFalse(s.has("Shortbow Cooldown"))
        assertFalse(s.has("Shortbow Shot Cooldown"))
    }

    @Test fun shortbowCooldownSetByHandIsKept() {
        assertEquals(7, migrateAds("""{"Shortbow Cooldown":7.0}""")["Shortbow Shot Cooldown"].asInt)
    }
}
