package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.adm.events.BlockInteractEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.core.on
import com.adm.utils.Color
import com.adm.utils.render.drawFilledBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.world.phys.AABB
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

// Subscribed while Waypoints is on.
object BossWaypoints {
    private var editMode = false
    private val nextColor = Color(0, 247, 247)

    private class Waypoint(val x: Double, val y: Double, val z: Double, val dx: Double, val dy: Double, val dz: Double, val color: String, val throughWall: Boolean) {
        @Transient private var box: AABB? = null
        @Transient private var parsed: Color? = null
        fun box() = box ?: AABB(x, y, z, x + dx, y + dy, z + dz).also { box = it }
        fun color() = parsed ?: Color(color).also { parsed = it }
        fun at(px: Double, py: Double, pz: Double) = x == px && y == py && z == pz
        fun near(px: Double, py: Double, pz: Double, r: Double) = (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz) < r * r
    }

    private val waypoints = CopyOnWriteArrayList<Waypoint>()
    private val gson = GsonBuilder().create()
    private val dir get() = EngineerClient.mc.gameDirectory.toPath().resolve("config")
    private val file get() = dir.resolve("engineerclient").resolve("boss-waypoints.json")

    private fun shown() = DungeonUtils.inDungeons && DungeonUtils.inBoss

    init {
        load()

        on<RenderExtractEvent> {
            if (!shown()) return@on
            for (w in waypoints) drawFilledBox(w.box(), w.color(), depth = !w.throughWall)
        }

        on<BlockInteractEvent> {
            if (!editMode || !shown()) return@on
            val x = pos.x.toDouble(); val y = pos.y.toDouble(); val z = pos.z.toDouble()
            val existing = waypoints.firstOrNull { it.at(x, y, z) }
            if (existing != null) waypoints.remove(existing) else add(x, y, z, 1.0, 1.0, 1.0)
            save()
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            for (name in EngineerClient.COMMANDS) dispatcher.register(literal(name).then(literal("waypoint")
                .then(literal("add").then(xyz(
                    { (x, y, z) -> add(x, y, z, 1.0, 1.0, 1.0); save() },
                    num("dx").then(num("dy").then(num("dz").executes { ctx ->
                        val (x, y, z) = coords(ctx)
                        add(x, y, z, d(ctx, "dx"), d(ctx, "dy"), d(ctx, "dz")); save(); 1
                    }))
                )))
                .then(literal("remove").then(xyz(
                    { (x, y, z) -> remove(x, y, z, 0.5) },
                    num("radius").executes { ctx -> val (x, y, z) = coords(ctx); remove(x, y, z, d(ctx, "radius")); 1 }
                )))
                .then(literal("edit").executes { toggleEdit(); 1 })
                .then(literal("clear").executes { waypoints.clear(); save(); EngineerClient.msg("§7Boss waypoints cleared."); 1 })))
        }
        EngineerClient.HELP += "§f /adm waypoint§7 add|remove <x y z> [dx dy dz | radius], edit, clear — boss waypoints (Waypoints)"
    }

    private fun d(ctx: CommandContext<FabricClientCommandSource>, name: String) = DoubleArgumentType.getDouble(ctx, name)
    private fun coords(ctx: CommandContext<FabricClientCommandSource>) = Triple(d(ctx, "x"), d(ctx, "y"), d(ctx, "z"))

    private fun num(name: String) = argument(name, DoubleArgumentType.doubleArg())

    private fun xyz(run: (Triple<Double, Double, Double>) -> Unit, more: ArgumentBuilder<FabricClientCommandSource, *>) =
        num("x").then(num("y").then(num("z").executes { ctx -> run(coords(ctx)); 1 }.then(more)))

    private fun toggleEdit() {
        editMode = !editMode
        EngineerClient.msg("§7Waypoint edit mode: ${if (editMode) "§aon" else "§coff"}")
    }

    private fun add(x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double) {
        if (waypoints.any { it.at(x, y, z) }) return
        waypoints += Waypoint(x, y, z, dx, dy, dz, nextColor.hex(true), false)
    }

    private fun remove(x: Double, y: Double, z: Double, radius: Double) {
        val before = waypoints.size
        waypoints.removeIf { it.near(x, y, z, radius) }
        save()
        EngineerClient.msg("§7Removed ${before - waypoints.size} waypoint(s).")
    }

    private fun save() {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(mapOf("waypoints" to waypoints)))
        }
    }

    private fun load() {
        runCatching {
            if (Files.exists(file)) {
                gson.fromJson(Files.readString(file), Saved::class.java)?.waypoints?.let { waypoints += it }
                return
            }
            // First run: bring over ADM's waypoints, which stored colors as 0..1 floats.
            val legacy = dir.resolve("blade-addons-waypoints.json")
            if (!Files.exists(legacy)) return
            for (e in JsonParser.parseString(Files.readString(legacy)).asJsonObject.getAsJsonArray("waypoints")) {
                val o = e.asJsonObject
                fun f(k: String) = o.get(k).asDouble
                fun c(k: String) = (o.get(k).asFloat * 255).toInt().coerceIn(0, 255)
                val color = Color(c("r"), c("g"), c("b"), o.get("a").asFloat).hex(true)
                waypoints += Waypoint(f("x"), f("y"), f("z"), f("dx"), f("dy"), f("dz"), color, o.get("throughWall")?.asBoolean ?: false)
            }
            save()
        }
    }

    private class Saved(val waypoints: List<Waypoint>?)
}
