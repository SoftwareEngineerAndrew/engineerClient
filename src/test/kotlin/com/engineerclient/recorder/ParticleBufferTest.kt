package com.engineerclient.recorder

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParticleBufferTest {

    private fun parse(members: String) = JsonParser.parseString("{$members}").asJsonObject

    @Test
    fun emptyTickWritesNothing() {
        assertNull(ParticleBuffer { "{}" }.drain())
    }

    @Test
    fun requestCountsWhatItMadeAndOptionsAreSharedPerInstance() {
        var built = 0
        val b = ParticleBuffer { built++; "{\"type\":\"minecraft:flame\"}" }
        val flame = Any()
        b.request("minecraft:flame", flame, 1.0, 2.5, -3.0, 0.0, 0.1, 0.0, false, true)
        b.spawned("FlameParticle", 1.0, 2.5, -3.0, 0.0, 0.1, 0.0, 20)
        b.requestDone()
        // Filtered away by the Particles option: nothing spawned between head and return.
        b.request("minecraft:flame", flame, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, true, false)
        b.requestDone()
        val o = parse(b.drain()!!)
        assertEquals(1, built)
        assertEquals(1, o["opts"].asJsonArray.size())
        val req = o["req"].asJsonArray
        assertEquals(2, req.size())
        assertEquals("[\"minecraft:flame\",1.0,2.5,-3.0,0.0,0.1,0.0,false,true,0,1]", req[0].toString())
        assertEquals(0, req[1].asJsonArray[10].asInt)
        assertEquals("[\"FlameParticle\",1.0,2.5,-3.0,0.0,0.1,0.0,20]", o["spawned"].asJsonArray[0].toString())
    }

    @Test
    fun requestThatNeverReturnedIsClosedWithNull() {
        val b = ParticleBuffer { "{}" }
        b.request("minecraft:crit", Any(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false, false)
        b.request("minecraft:crit", Any(), Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0, false, false)
        val o = parse(b.drain()!!)
        val req = o["req"].asJsonArray
        assertTrue(req[0].asJsonArray[10].isJsonNull)
        assertTrue(req[1].asJsonArray[10].isJsonNull)
        assertEquals("NaN", req[1].asJsonArray[1].asString)
        assertEquals(2, o["opts"].asJsonArray.size())
    }

    @Test
    fun drainStartsTheNextTickClean() {
        val b = ParticleBuffer { "{\"type\":\"minecraft:crit\"}" }
        val key = Any()
        b.emitter(42, "minecraft:zombie", key, -1)
        val o = parse(b.drain()!!)
        assertEquals("[42,\"minecraft:zombie\",0,-1]", o["emit"].asJsonArray[0].toString())
        assertTrue(!o.has("req") && !o.has("spawned"))
        assertNull(b.drain())
        b.spawned("a.b.Custom", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1)
        val o2 = parse(b.drain()!!)
        assertEquals(0, o2["opts"].asJsonArray.size())
    }

    @Test
    fun theTakenSnapshotIsUnaffectedByTheNextTick() {
        val b = ParticleBuffer { "{}" }
        b.request("minecraft:crit", Any(), 1.0, 2.0, 3.0, 0.0, 0.0, 0.0, false, false)
        b.requestDone()
        val snap = b.take()!!
        // The game thread carries on before the writer formats the taken tick.
        b.request("minecraft:flame", Any(), 9.0, 9.0, 9.0, 0.0, 0.0, 0.0, true, true)
        val o = parse(snap.json())
        assertEquals("[\"minecraft:crit\",1.0,2.0,3.0,0.0,0.0,0.0,false,false,0,0]", o["req"].asJsonArray.single().toString())
    }

    @Test
    fun vanillaClassNamesDropThePackage() {
        assertEquals("FlameParticle", ParticleBuffer.className("net.minecraft.client.particle.FlameParticle"))
        assertEquals("CritParticle\$Provider", ParticleBuffer.className("net.minecraft.client.particle.CritParticle\$Provider"))
        assertEquals("com.mod.MyParticle", ParticleBuffer.className("com.mod.MyParticle"))
    }
}
