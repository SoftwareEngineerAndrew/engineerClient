package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.clear.replay.ReplayFile
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tags as ADR (with colour codes) and the Dungeon Recorder (plain) recorded them. */
class LevelLooksTest {
    private fun arch(name: String) = assertNotNull(LevelLooks.all.firstOrNull { it.name == name })

    @Test
    fun tagsAreHypixels() {
        val prime = arch("Skeletor Prime")
        assertEquals("§8[§r§7Lv277§r§8] §r§f §r§cSkeletor Prime§r §a3.1M§r§f/§r§a3.1M§r§c❤", LevelLooks.tag(prime, 3_100_000.0, 3_100_000.0))
        assertEquals("§8[§r§7Lv277§r§8] §r§f §r§cSkeletor Prime§r §e0§r§f/§r§a3.1M§r§c❤", LevelLooks.tag(prime, 0.0, 3_100_000.0))
        assertEquals("§8[§r§7Lv277§r§8] §r§f §r§cSkeletor Prime§r §a1.6M§r§f/§r§a3.1M§r§c❤", LevelLooks.tag(prime, 1_600_000.0, 3_100_000.0))
        assertEquals("§8[§r§7Lv277§r§8] §r§f §r§cSkeletor Prime§r §e1.5M§r§f/§r§a3.1M§r§c❤", LevelLooks.tag(prime, 1_500_000.0, 3_100_000.0))
        assertEquals("[Lv173]  Undead Skeleton 360k/360k❤", MobTags.plain(LevelLooks.tag(arch("Undead Skeleton"), 360_000.0, 360_000.0)))
        // ADR's Parasite (Lv30 then; Lv79 since).
        assertEquals("§8[§r§7Lv79§r§8] §r§4 §r§cParasite§r §a33,333§r§f/§r§a33,333§r§c❤", LevelLooks.tag(arch("Parasite"), 33_333.0, 33_333.0))
        assertTrue(LevelLooks.levelled(arch("Parasite")))
    }

    @Test
    fun aLonelySpiderIsTaggedLikeAPackMob() {
        val spider = arch("Lonely Spider")
        assertTrue(!LevelLooks.levelled(spider))
        // The Dungeon Recorder's text: icon, name, HP.
        assertEquals(" Lonely Spider 120k❤", MobTags.plain(MobTags.nametagLegacy(assertNotNull(spider.tag), spider.name, null, 120_000.0, 120_000.0, false)))
        assertEquals("minecraft:spider", spider.entity)
        assertEquals(1.1875, spider.tag?.dy)
        assertNull(LevelLooks.hand(spider))
        assertEquals(spider, LevelLooks.byId("lv:lonely_spider"))
    }

    @Test
    fun aPrinceIsTaggedLikeACryptUndead() {
        val prince = arch("Prince Friedrich")
        assertTrue(LevelLooks.isPrince(prince))
        assertTrue(!LevelLooks.levelled(prince))
        // The Dungeon Recorder's text, icons and all.
        assertEquals(" Prince Friedrich 60,000❤", MobTags.plain(MobTags.nametagLegacy(assertNotNull(prince.tag), prince.name, null, 60_000.0, 60_000.0, false)))
        assertEquals("minecraft:player", prince.entity)
        assertTrue(prince.disguise)
        assertEquals("4999123cf0a41477f706fc6a50891463e8cb50dcbdd2c81c652fcef6fdbde21c", prince.skin?.id)
        assertEquals("minecraft:bone", LevelLooks.hand(prince))
        assertEquals(60_000L, prince.hpFor("F7"))
        assertEquals(8, LevelLooks.all.count { LevelLooks.isPrince(it) })
    }

    @Test
    fun bodiesAreHypixels() {
        val undead = arch("Undead Skeleton")
        assertEquals("minecraft:skeleton", undead.entity)
        assertEquals(360_000L, undead.hpFor("F7"))
        assertEquals(2.1875, undead.tag?.dy)
        assertEquals("minecraft:bow", LevelLooks.hand(undead))
        val prime = arch("Skeletor Prime")
        assertTrue(prime.disguise)
        assertEquals("1bd3a4f8e22db15e486acac5d456ff35f34ed9ab5c64eec057afd2866d559401", prime.skin?.id)
        assertEquals("minecraft:bone", LevelLooks.hand(prime))
        assertEquals(prime, LevelLooks.archetype(prime.index))
        assertNull(LevelLooks.archetype(0))
    }

    @Test
    fun aReplayTakesItsLevelledMobsFromTheTags() {
        val ev = listOf(
            """[119,"spawn",149097,"minecraft:skeleton",4,-78.5,71.719,-118.5,null,59.1,"b"]""",
            """[119,"spawn",149098,"minecraft:armor_stand",0,-78.5,73.906,-118.5,null,59.1,"t"]""",
            """[119,"name",149098,"[Lv173]  Undead Skeleton 360k/360k❤"]""",
            """[130,"spawn",149097,"minecraft:skeleton",4,-70.5,71.0,-118.5,null,0,"b"]""",
            """[140,"name",149098,"[Lv173]  Undead Skeleton 200k/360k❤"]""",
            """[200,"spawn",5,"minecraft:player",2,-152.4,74.281,-80.5,null,-120.9,"p"]""",
            """[200,"name",6,"[Lv277]  Skeletor Prime 3.1M/3.1M❤"]""",
            """[300,"spawn",7,"minecraft:zombie",0,1.5,70.0,1.5,null,0,"z"]""",
            """[300,"name",8,"✯ Zombie Knight 1.3M❤"]""",
        ).map { JsonParser.parseString(it).asJsonArray }
        val got = ReplayFile.levelled(ev, emptyList())
        assertEquals(listOf(149097, 5), got.map { it.id })
        val u = got[0]
        assertEquals("Undead Skeleton", u.tag)
        assertEquals(360_000.0, u.hp)
        assertEquals(-78.5, u.x)
        assertEquals(119, u.k)
        assertEquals("minecraft:skeleton", u.type)
        assertEquals(3_100_000.0, got[1].hp)
        // A body the recording's own list has already.
        assertEquals(listOf(5), ReplayFile.levelled(ev, listOf(ReplayFile.Mob(149097, "minecraft:skeleton", 119, -78.5, 71.719, -118.5, 0f, "Undead Skeleton", 360_000.0))).map { it.id })
    }
}
