package com.adm.clickgui.settings

import com.google.gson.Gson
import com.google.gson.JsonElement

internal interface Saving {
    fun read(element: JsonElement, gson: Gson)
    fun write(gson: Gson): JsonElement
}