package com.engineerclient.p3sim.run

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chunk pace mixin's target (a mixin is only looked at when the game starts, so a renamed field would show up as a crash on launch): the game on the
 * test classpath still has every field, constructor and method the mixin reaches.
 */
class ChunkPaceMixinTest {
    private fun game(name: String): Class<*> = Class.forName(name, false, javaClass.classLoader)

    @Test fun theTargetStillHasEverythingTheMixinTouches() {
        val target = game("net.minecraft.server.network.PlayerChunkSender")
        val mixin = game("com.engineerclient.mixin.AdsEngineChunkPaceMixin")
        // Every field the mixin shadows is a field of the target, of the same type.
        val shadowed = mixin.declaredFields.filter { !it.isSynthetic }
        assertTrue(shadowed.isNotEmpty())
        for (f in shadowed) assertEquals(f.type, target.getDeclaredField(f.name).type, "field ${f.name}")
        // The constructor and the batch acknowledgement the injections hook.
        target.getDeclaredConstructor(java.lang.Boolean.TYPE)
        target.getDeclaredMethod("onChunkBatchReceivedByClient", java.lang.Float.TYPE)
        target.getDeclaredMethod("sendNextChunks", game("net.minecraft.server.level.ServerPlayer"))
    }
}
