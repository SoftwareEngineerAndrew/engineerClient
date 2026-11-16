package com.adm.events.core

import com.adm.utils.logError

interface Event {

    fun postAndCatch(): Boolean {
        runCatching {
            EventBus.post(this)
        }.onFailure {
            logError(it, this)
        }
        return false
    }
}