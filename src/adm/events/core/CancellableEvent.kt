package com.adm.events.core

import com.adm.utils.logError

abstract class CancellableEvent : Event {
    var isCancelled = false
        private set

    fun cancel() {
        isCancelled = true
    }

    override fun postAndCatch(): Boolean {
        runCatching {
            EventBus.post(this)
        }.onFailure {
            logError(it, this)
        }
        return isCancelled
    }
}