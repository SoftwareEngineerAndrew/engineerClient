package com.engineerclient

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigMigrationTest {

    private fun dir(ec: String, odin: String? = null): Path {
        val d = Files.createTempDirectory("ecmig")
        Files.createDirectories(d.resolve("addons"))
        Files.writeString(d.resolve("addons/engineerclient.json"), ec)
        if (odin != null) Files.writeString(d.resolve("odin-config.json"), odin)
        return d
    }

    private fun modules(d: Path): JsonArray = JsonParser.parseString(Files.readString(d.resolve("addons/engineerclient.json"))).asJsonArray
    private fun JsonArray.named(name: String): JsonObject? = firstOrNull { it.asJsonObject["name"].asString == name }?.asJsonObject
    private fun JsonObject.s(key: String) = getAsJsonObject("settings")[key]

    private val EC = """[
        {"name":"Lowest BIN","enabled":false,"settings":{"Stack Total":true,"Keybind":"key.keyboard.unknown"}},
        {"name":"Random Stuff","enabled":true,"settings":{"Hide Chat":false}},
        {"name":"BR Waypoints 2","enabled":true,"settings":{"Killers":"Duo","My Role":"Role 2"}}
    ]"""

    private val ODIN = """[
        {"name":"Leap Menu","enabled":true,"settings":{"Click Delay":3,"Map Leap":false,"Leap Outline":true}},
        {"name":"Player Display","enabled":true,"settings":{"Health Bar HUD":{"x":868,"y":1001,"scale":3.2,"enabled":true},"Health Bar Width":60,"Health Bar Height":8,"Mana Bar HUD":{"x":868,"y":959,"scale":3.2,"enabled":true},"Mana Bar Width":70,"Mana Bar Height":9}}
    ]"""

    @Test
    fun `renames, folds and copies everything, keeping values`() {
        val d = dir(EC, ODIN)
        assertTrue(ConfigMigration.run(d))
        val m = modules(d)
        assertNull(m.named("BR Waypoints 2"))
        assertEquals("Role 2", m.named("BR Roles")!!.s("My Role").asString)
        assertNull(m.named("Lowest BIN"))
        val rs = m.named("Random Stuff")!!
        assertFalse(rs.s("Lowest BIN").asBoolean) // it was off
        assertTrue(rs.s("Lowest BIN Stack Total").asBoolean)
        assertFalse(rs.s("Hide Chat").asBoolean) // untouched
        assertEquals(868, rs.s("Health Bar HUD").asJsonObject["x"].asInt)
        assertEquals(70, rs.s("Mana Bar Width").asInt)
        val leap = m.named("Leap Extras")!!
        assertTrue(leap["enabled"].asBoolean)
        assertEquals(3, leap.s("Click Delay").asInt)
        assertTrue(leap.s("Leap Outline").asBoolean)
    }

    @Test
    fun `runs once`() {
        val d = dir(EC, ODIN)
        ConfigMigration.run(d)
        val after = Files.readString(d.resolve("addons/engineerclient.json"))
        assertFalse(ConfigMigration.run(d))
        assertEquals(after, Files.readString(d.resolve("addons/engineerclient.json")))
    }

    @Test
    fun `Map Leap comes across even when Click Delay already did`() {
        val ec = """[{"name":"Leap Extras","enabled":true,"settings":{"Click Delay":2,"Leap Outline":false}}]"""
        val odin = """[{"name":"Leap Menu","enabled":true,"settings":{"Click Delay":5,"Map Leap":true,"Map Leap Size":0.65,"Blood Room":{"r":200,"g":0,"b":0,"a":1.0}}}]"""
        val d = dir(ec, odin)
        assertTrue(ConfigMigration.run(d))
        val leap = modules(d).named("Leap Extras")!!
        assertEquals(2, leap.s("Click Delay").asInt) // the earlier migration's value stays
        assertTrue(leap.s("Map Leap").asBoolean)
        assertEquals(0.65, leap.s("Map Leap Size").asDouble)
        assertEquals(200, leap.s("Blood Room").asJsonObject["r"].asInt)
        assertFalse(ConfigMigration.run(d))
    }

    @Test
    fun `a stock Odin config copies nothing`() {
        val d = dir("""[{"name":"Random Stuff","enabled":true,"settings":{}}]""", """[{"name":"Leap Menu","enabled":true,"settings":{"Render Scale":1.0}}]""")
        assertFalse(ConfigMigration.run(d))
        assertNull(modules(d).named("Leap Extras"))
    }

    @Test
    fun `the old Engineer Splits HUD being on picks the Engineer look for Odin's Splits, once`() {
        val ec = """[{"name":"Sub Splits","enabled":true,"settings":{"Splits":{"x":530,"y":255,"scale":2,"enabled":true}}}]"""
        val odin = """[{"name":"Splits","enabled":true,"settings":{"Fixed Width":true}}]"""
        val d = dir(ec, odin)
        assertTrue(ConfigMigration.run(d))
        val splits = JsonParser.parseString(Files.readString(d.resolve("odin-config.json"))).asJsonArray.named("Splits")!!
        assertEquals("Engineer Splits", splits.s("Look").asString)
        assertTrue(splits.s("Fixed Width").asBoolean)
        // Picked Odin's look back since: left alone.
        Files.writeString(d.resolve("odin-config.json"), """[{"name":"Splits","enabled":true,"settings":{"Look":"Odin Splits"}}]""")
        ConfigMigration.run(d)
        assertEquals("Odin Splits", JsonParser.parseString(Files.readString(d.resolve("odin-config.json"))).asJsonArray.named("Splits")!!.s("Look").asString)
    }

    @Test
    fun `the old HUD off leaves Odin's look`() {
        val d = dir("""[{"name":"Sub Splits","enabled":true,"settings":{"Splits":{"x":1,"y":1,"scale":2,"enabled":false}}}]""", """[{"name":"Splits","enabled":true,"settings":{}}]""")
        assertFalse(ConfigMigration.run(d))
    }

    @Test
    fun `detail levels - Extreme is Debug, Off is the HUD off`() {
        val d = dir("""[{"name":"Sub Splits","enabled":true,"settings":{"Maxor Detail":"Extreme","Storm Detail":"Off","Storm Sub Splits":{"x":1,"y":2,"scale":1,"enabled":true},"Goldor Detail":"Detailed"}}]""")
        assertTrue(ConfigMigration.run(d))
        val sub = modules(d).named("Sub Splits")!!
        assertEquals("Debug", sub.s("Maxor Detail").asString)
        assertEquals("Compact", sub.s("Storm Detail").asString)
        assertFalse(sub.s("Storm Sub Splits").asJsonObject["enabled"].asBoolean)
        assertEquals("Detailed", sub.s("Goldor Detail").asString)
        assertFalse(ConfigMigration.run(d))
    }

    @Test
    fun `no config yet is left alone`() {
        val d = Files.createTempDirectory("ecmig")
        assertFalse(ConfigMigration.run(d))
    }
}
