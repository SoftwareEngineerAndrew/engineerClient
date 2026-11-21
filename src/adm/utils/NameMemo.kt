package com.adm.utils

import com.adm.events.LevelEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import java.util.concurrent.CopyOnWriteArrayList

/** One answer per entity name: a new name always arrives as a new Component, so the same reference keeps its answer. */
class NameMemo<T>(private val compute: (Component) -> T, private val cap: Int) {
    constructor(compute: (Component) -> T) : this(compute, 4096)

    private val names = Int2ObjectOpenHashMap<Component>()
    private val answers = Int2ObjectOpenHashMap<T>()

    init { all += this }

    operator fun get(entity: Entity, name: Component): T {
        val id = entity.id
        if (names.get(id) === name) return answers.get(id)
        if (names.size >= cap) clear()
        val answer = compute(name)
        names.put(id, name)
        answers.put(id, answer)
        return answer
    }

    fun clear() {
        names.clear()
        answers.clear()
    }

    private companion object {
        val all = CopyOnWriteArrayList<NameMemo<*>>()

        init {
            on<LevelEvent.Load> { for (m in all) m.clear() }
            EventBus.subscribe(this)
        }
    }
}
