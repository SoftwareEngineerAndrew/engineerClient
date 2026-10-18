package com.engineerclient.p3sim.clear.ui

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.SimNet
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Client side: sends a `/ads run <name>` server part from a key or a button, the same message the command line sends (kind `adscmd`). */
object UiServer {
    fun send(name: String, vararg args: String) {
        if (!P3Sim.inSim) return
        EngineerClient.safely("ads run $name") {
            SimNet.send("adscmd", JsonObject().also { o ->
                o.addProperty("group", "run"); o.addProperty("name", name)
                o.add("args", JsonArray().also { a -> args.forEach(a::add) })
            }.toString())
        }
    }
}
