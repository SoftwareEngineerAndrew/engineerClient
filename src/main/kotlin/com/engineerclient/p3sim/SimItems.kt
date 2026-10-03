package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import kotlin.random.Random
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractBannerBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.TripWireHookBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sin

/**
 * The boss hotbar most Better PF recordings show (tools/p3sim/research/hotbars.md), working the
 * way the items do on Hypixel:
 *
 * 1 Superboom TNT (Hyperion outside P3) · 2 ⚚ Bonzo's Staff · 3 Spirit Shortbow · 4 Dungeonbreaker
 * · 5 Ender Pearls · 6 Infinileap · 7 Jerry-chine Gun · 8 Wither Cloak Sword · 9 SkyBlock Menu
 * (opens the sim menu); in the inventory Hyperion, an Aspect of the Void (etherwarp merged) and a
 * Terminator.
 *
 * Teleports follow PrecisionSnipes' measured rules (item-mechanics.md): etherwarp is a voxel DDA
 * from the sneak eye over 61 blocks onto a block with room above (+0.5, +1.05, +0.5), blinks step
 * whole blocks (AOTV 12, Hyperion 10) checking every quarter; look kept, velocity zeroed.
 */
object SimItems {
    // ------------------------------------------------------------------ the items

    private fun item(base: Item, id: String, name: String, lore: List<String> = emptyList(), glint: Boolean = false, extra: (CompoundTag) -> Unit = {}): ItemStack {
        val s = ItemStack(base)
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { it.withItalic(false) })
        if (lore.isNotEmpty()) s.set(DataComponents.LORE, ItemLore(lore.map { l -> Component.literal(l).withStyle { it.withItalic(false) } }))
        if (glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        s.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE)
        val tag = CompoundTag()
        tag.putString("id", id)
        tag.putBoolean("p3sim", true)
        extra(tag)
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        return s
    }

    fun idOf(s: ItemStack): String? = s.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")?.takeIf { it.isNotEmpty() }

    private const val LEAP_TEX = "ewogICJ0aW1lc3RhbXAiIDogMTY1MjE0NjYxMjc0MiwKICAicHJvZmlsZUlkIiA6ICI5ZWU3NTUxOGQyZWE0Y2Q4OGJiNGI1YTZkNmVhNTFjYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaWNyb3MxMTgyIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzM3N2Q0YTIwNmQ3NzU3ZjQ3OWYzMzJlYzFhMmJiYmVlNTdjZWY5NzU2OGRkODhkZjgxZjQ4NjRhZWU3ZDNkOTgiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ=="

    fun head(tex: String, name: String): ItemStack {
        val s = ItemStack(Items.PLAYER_HEAD)
        val profile = GameProfile(UUID.nameUUIDFromBytes(tex.toByteArray()), "p3sim", PropertyMap(ImmutableMultimap.of("textures", Property("textures", tex))))
        s.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { it.withItalic(false) })
        return s
    }

    val SUPERBOOM get() = item(Items.PAPER, "SUPERBOOM_TNT", "§9Superboom TNT", listOf("§7Right-click a gate (or a crack) to", "§7blow it up.")).also { it.count = 64 }
    val HYPERION get() = item(Items.IRON_SWORD, "HYPERION", "§dHeroic Hyperion §6✪✪✪✪✪", listOf("§6Ability: Wither Impact §e§lRIGHT CLICK", "§7Teleports §a10 blocks§7 ahead and implodes."), glint = true)
    val BONZO get() = item(Items.BLAZE_ROD, "STARRED_BONZO_STAFF", "§9⚚ Bonzo's Staff §6✪✪✪✪✪", listOf("§6Ability: Showtime §e§lRIGHT CLICK", "§7Shoots balloons that knock you back."))
    val SPIRIT_BOW get() = item(Items.BOW, "ITEM_SPIRIT_BOW", "§5Spirit Shortbow", listOf("§7Shortbow: instantly shoots!"), glint = true)
    val DUNGEONBREAKER get() = item(Items.DIAMOND_PICKAXE, "DUNGEONBREAKER", "§6Dungeonbreaker", listOf("§7Breaks most dungeon blocks instantly.", "§7Uses a charge per block (§a${MAX_CHARGES}§7 max,", "§7refills every second); blocks come back."), glint = true)
    val PEARLS get() = item(Items.ENDER_PEARL, "ENDER_PEARL", "§fEnder Pearl").also { it.count = 16 }
    val LEAP get() = head(LEAP_TEX, "§5Infinileap").also { s ->
        s.set(DataComponents.LORE, ItemLore(listOf(Component.literal("§7Right-click to leap to a teammate.").withStyle { it.withItalic(false) })))
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putString("id", "INFINITE_SPIRIT_LEAP"); it.putBoolean("p3sim", true) }))
    }
    val JERRY get() = item(Items.GOLDEN_HORSE_ARMOR, "JERRY_STAFF", "§6Jerry-chine Gun", listOf("§6Ability: Rapid-fire §e§lRIGHT CLICK", "§7Jerries that knock you up."))
    val CLOAK get() = item(Items.STONE_SWORD, "WITHER_CLOAK", "§5Wither Cloak Sword", listOf("§6Ability: Creeper Veil §e§lRIGHT CLICK", "§7Immune to damage (death ticks) while on."))
    val MENU get() = item(Items.NETHER_STAR, "SKYBLOCK_MENU", "§aSkyBlock Menu §7(Click)", listOf("§7Opens the §aP3 Sim§7 menu: start any", "§7phase or section, teleport, change", "§7settings.", "", "§eClick to open!"))
    val AOTV get() = item(Items.DIAMOND_SHOVEL, "ASPECT_OF_THE_VOID", "§5Heroic Aspect of the Void", listOf("§6Ability: Instant Transmission §e§lRIGHT CLICK", "§6Ability: Ether Transmission §e§lSNEAK RIGHT CLICK"), glint = true) { it.putInt("ethermerge", 1); it.putInt("tuned_transmission", 4) }
    val TERMINATOR get() = item(Items.BOW, "TERMINATOR", "§dTerminator §6✪✪✪✪✪", listOf("§7Shortbow: instantly shoots 3 arrows!"), glint = true)

    /** The boss hotbar (P3's, or P1/P2's with a Hyperion in slot 1), and the extras in the inventory. */
    fun giveHotbar(p: ServerPlayer, p3: Boolean = true) {
        val inv = p.inventory
        inv.clearContent()
        val bar = listOf(if (p3) SUPERBOOM else HYPERION, BONZO, TERMINATOR, DUNGEONBREAKER, PEARLS, LEAP, JERRY, CLOAK, MENU)
        bar.forEachIndexed { i, s -> inv.setItem(i, s) }
        inv.setItem(9, if (p3) HYPERION else SUPERBOOM); inv.setItem(10, AOTV); inv.setItem(11, SPIRIT_BOW)
        Masks.equip(p)
        inv.selectedSlot = 3
        p.connection.send(net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(3))
        p.containerMenu.broadcastChanges()
        p.inventoryMenu.broadcastChanges()
    }

    // ------------------------------------------------------------------ hooks

    private fun simServer(level: Level) = !level.isClientSide && level is ServerLevel && level.server === SimServer.server && SimServer.server != null
    private fun simClient(level: Level) = level.isClientSide && P3Sim.inSim

    fun register() {
        UseItemCallback.EVENT.register { player, level, hand ->
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            val id = idOf(stack) ?: return@register InteractionResult.PASS
            if (simClient(level)) {
                // The menu opens here (client side); everything else is the server's.
                if (id == "SKYBLOCK_MENU") { mc.execute { mc.setScreen(SimScreen()) }; return@register InteractionResult.FAIL }
                return@register InteractionResult.PASS
            }
            if (!simServer(level) || player !is ServerPlayer) return@register InteractionResult.PASS
            var result: InteractionResult = InteractionResult.PASS
            EngineerClient.safely("p3sim use $id") { result = use(player, id) }
            result
        }
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            val id = idOf(player.getItemInHand(hand))
            if (simClient(level)) {
                if (id == "SKYBLOCK_MENU") { mc.execute { mc.setScreen(SimScreen()) }; return@register InteractionResult.FAIL }
                return@register InteractionResult.PASS
            }
            if (!simServer(level) || player !is ServerPlayer) return@register InteractionResult.PASS
            var result: InteractionResult = InteractionResult.PASS
            EngineerClient.safely("p3sim use block") { result = useBlock(player, hit.blockPos, id) }
            result
        }
        UseEntityCallback.EVENT.register { player, level, hand, entity, _ ->
            if (hand != InteractionHand.MAIN_HAND || !simServer(level) || player !is ServerPlayer) return@register InteractionResult.PASS
            var result: InteractionResult = InteractionResult.PASS
            EngineerClient.safely("p3sim use entity") { result = useEntity(player, entity) }
            result
        }
        AttackEntityCallback.EVENT.register { player, level, _, entity, _ ->
            if (simClient(level)) return@register if (entity is ItemFrame) InteractionResult.FAIL else InteractionResult.PASS
            if (!simServer(level) || player !is ServerPlayer) return@register InteractionResult.PASS
            var result: InteractionResult = InteractionResult.PASS
            EngineerClient.safely("p3sim hit entity") { result = useEntity(player, entity, left = true) }
            result
        }
    }

    /**
     * A block hit on the client (DungeonbreakerSimMixin: adventure mode drops it before Fabric's
     * callback). In the sim with the Dungeonbreaker: mined on the sim's server; true = handled.
     */
    @JvmStatic
    fun clientHitBlock(pos: BlockPos): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        if (!simClient(level) || idOf(player.mainHandItem) != "DUNGEONBREAKER") return false
        val at = pos.immutable()
        val state = level.getBlockState(at)
        if (state.isAir) return true
        // The client breaks it at once (as Hypixel's do); the server keeps it or sends it back.
        if (state.getDestroySpeed(level, at) >= 0) level.destroyBlock(at, false)
        SimServer.run("dungeonbreaker") { Sim.player?.let { p -> Fight.afterPing("dungeonbreaker") { mine(p, at) } } }
        return true
    }

    /** A left click on the client (ShortbowSimMixin): the shortbows shoot on it too, as on Hypixel. */
    @JvmStatic
    fun clientLeftClick() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (!simClient(level)) return
        val n = when (idOf(player.mainHandItem)) { "TERMINATOR" -> 3; "ITEM_SPIRIT_BOW" -> 1; else -> return }
        SimServer.run("left click") { Sim.player?.let { p -> asClicked(p, "shortbow") { shoot(p, n) } } }
    }

    /** Runs [run] after the ping, aimed where [p] looked when they clicked (as Hypixel gets it from the click's packets). */
    private fun asClicked(p: ServerPlayer, what: String, run: () -> Unit) {
        val xRot = p.xRot; val yRot = p.yRot
        Fight.afterPing(what) {
            if (p.isRemoved || Sim.player !== p) return@afterPing
            val nowX = p.xRot; val nowY = p.yRot
            p.xRot = xRot; p.yRot = yRot
            try { run() } finally { p.xRot = nowX; p.yRot = nowY }
        }
    }

    /** The cloak and the arrows: nothing carries over from an earlier sim server. */
    fun reset() { resetBreaker(); cloakUntil = 0; cloakReady = 0; lastHype = -100; bowReady = 0; leapReady = 0; arrows.clear(); lastMotion.clear(); lastPos.clear() }

    /** A right click with [id] in the air (or on a block that isn't the sim's). */
    private fun use(p: ServerPlayer, id: String): InteractionResult {
        (Fight.phase as? P1Maxor)?.let { if (it.usePylon(p.position())) return InteractionResult.SUCCESS }
        if (id == "HYPERION") (Fight.phase as? P2Storm)?.beam()
        when (id) {
            "ASPECT_OF_THE_VOID" -> { val sneak = p.isShiftKeyDown; asClicked(p, "aotv") { if (sneak) etherwarp(p) else blink(p, 12) } }
            "HYPERION" -> asClicked(p, "hype") { if (hypeReady()) { blink(p, 10); implode(p) } }
            "STARRED_BONZO_STAFF" -> asClicked(p, "bonzo") { bonzo(p) }
            "JERRY_STAFF" -> asClicked(p, "jerry") { jerry(p) }
            "WITHER_CLOAK" -> asClicked(p, "cloak") { cloak(p) }
            "INFINITE_SPIRIT_LEAP" -> openLeap(p)
            "SUPERBOOM_TNT" -> asClicked(p, "superboom") { superboom(p, null) }
            "TERMINATOR" -> asClicked(p, "term") { shoot(p, 3) }
            "ITEM_SPIRIT_BOW" -> asClicked(p, "spirit bow") { shoot(p, 1) }
            else -> return InteractionResult.PASS
        }
        // Keep the client's copy of the stack (the Infinileap is a head, a block item it may think it placed).
        p.containerMenu.broadcastChanges()
        return InteractionResult.SUCCESS
    }

    private fun useBlock(p: ServerPlayer, pos: BlockPos, id: String?): InteractionResult {
        val phase = Fight.phase
        if (phase is P1Maxor && phase.usePylon(Vec3.atCenterOf(pos))) return InteractionResult.SUCCESS
        if (phase is GoldorPhase) {
            phase.leverAt(pos)?.let { st -> Fight.afterPing("lever") { phase.pullLever(st, Sim.me) }; return InteractionResult.SUCCESS }
            if (phase.devices.use(pos)) return InteractionResult.SUCCESS
            if (id == "SUPERBOOM_TNT") { Fight.afterPing("superboom") { superboom(p, pos) }; return InteractionResult.SUCCESS }
        }
        val state = Sim.level.getBlockState(pos)
        // Anything else interactable (a stray lever or button) stays as built.
        if (state.block is LeverBlock || state.block is ButtonBlock) return InteractionResult.SUCCESS
        if (id != null) return use(p, id)
        return InteractionResult.SUCCESS
    }

    private fun useEntity(p: ServerPlayer, e: net.minecraft.world.entity.Entity, left: Boolean = false): InteractionResult {
        if (e is net.minecraft.world.entity.boss.enderdragon.EndCrystal) { (Fight.phase as? P1Maxor)?.useCrystal(e); return InteractionResult.SUCCESS }
        if (left && idOf(p.mainHandItem) == "HYPERION") (Fight.phase as? P2Storm)?.beam()
        val phase = Fight.phase as? GoldorPhase
        if (phase != null) {
            if (e is ItemFrame && phase.devices.arrows.owns(e)) { if (!left) phase.devices.arrows.use(e); return InteractionResult.SUCCESS }
            if (e is ArmorStand) {
                val st = phase.stations.firstOrNull { it.owns(e) }
                    ?: phase.stations.filter { it.kind == Station.Kind.TERMINAL }.minByOrNull { it.at.distanceToSqr(e.position()) }?.takeIf { it.at.distanceToSqr(e.position()) < 4.0 }
                if (st != null && st.kind == Station.Kind.TERMINAL) { Fight.afterPing("terminal") { phase.useTerminal(st) }; return InteractionResult.SUCCESS }
                return InteractionResult.SUCCESS
            }
        }
        if (e is ItemFrame || e is ArmorStand) return InteractionResult.SUCCESS
        if (e.entityTags().contains(Sim.TAG)) return InteractionResult.SUCCESS
        return InteractionResult.PASS
    }

    // ------------------------------------------------------------------ teleports

    private fun look(p: Player): Vec3 {
        val yaw = Math.toRadians(p.yRot.toDouble()); val pitch = Math.toRadians(p.xRot.toDouble())
        return Vec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
    }

    private fun state(x: Int, y: Int, z: Int): BlockState = Sim.level.getBlockState(BlockPos(x, y, z))

    private fun collides(s: BlockState, pos: BlockPos) = !s.getCollisionShape(Sim.level, pos, CollisionContext.empty()).isEmpty

    /** The top of a cell's collision under its centre (0 = none). */
    private fun top(x: Int, y: Int, z: Int): Double {
        val pos = BlockPos(x, y, z)
        val shape = state(x, y, z).getCollisionShape(Sim.level, pos, CollisionContext.empty())
        if (shape.isEmpty) return 0.0
        var best = 0.0
        for (b in shape.toAabbs()) if (b.minX <= 0.5 && b.maxX >= 0.5 && b.minZ <= 0.5 && b.maxZ >= 0.5) best = maxOf(best, b.maxY)
        return if (best > 0) best else shape.max(net.minecraft.core.Direction.Axis.Y)
    }

    private fun passThrough(s: BlockState) = s.block is SkullBlock || s.block is WallSkullBlock || s.block is LadderBlock || s.block is FlowerPotBlock || s.block is ButtonBlock || s.block is LeverBlock
    private fun stopsAnyway(s: BlockState) = s.block is SignBlock || s.block is AbstractBannerBlock || s.block is TripWireHookBlock

    /** Etherwarp's ray stops at this cell. */
    private fun rayStops(x: Int, y: Int, z: Int): Boolean {
        val s = state(x, y, z)
        if (passThrough(s)) return false
        if (stopsAnyway(s)) return true
        return collides(s, BlockPos(x, y, z))
    }

    /** A body fits in this cell. */
    private fun bodyPasses(x: Int, y: Int, z: Int): Boolean {
        val s = state(x, y, z)
        if (stopsAnyway(s)) return true
        if (s.block is SkullBlock || s.block is WallSkullBlock || s.block is LadderBlock || s.block is FlowerPotBlock) return false
        return !collides(s, BlockPos(x, y, z))
    }

    fun etherwarp(p: ServerPlayer, range: Double = 61.0) {
        val eye = Vec3(p.x, p.y + 1.27, p.z)
        val dir = look(p)
        val hit = dda(eye, dir, range)
        if (hit == null) { return }
        val (x, y, z) = hit
        val s = state(x, y, z)
        val above = state(x, y + 1, z)
        val bad = stopsAnyway(s) || s.block is SkullBlock || s.block is LadderBlock || s.block is FlowerPotBlock ||
            above.block is SkullBlock || above.block is WallSkullBlock || above.block is LadderBlock || above.block is FlowerPotBlock || top(x, y, z) < 0.03
        val need = if (top(x, y, z) > 1.0) 3 else 2
        if (bad || (1..need).any { !bodyPasses(x, y + it, z) } || rayStops(floor(eye.x).toInt(), floor(eye.y).toInt(), floor(eye.z).toInt())) {
            Sim.chat("§cThere are blocks in the way!")
            return
        }
        Sim.tp(p, x + 0.5, y + 1.05, z + 0.5)
        Sim.sound(SoundEvents.ENDER_DRAGON_HURT, 1f, 0.53f, p.position())
    }

    /** Amanatides-Woo DDA with the corner guard; the cell the ray stops in, or null. */
    private fun dda(eye: Vec3, dir: Vec3, range: Double): Triple<Int, Int, Int>? {
        var bx = floor(eye.x).toInt(); var by = floor(eye.y).toInt(); var bz = floor(eye.z).toInt()
        val sx = sign(dir.x).toInt(); val sy = sign(dir.y).toInt(); val sz = sign(dir.z).toInt()
        val dx = if (dir.x != 0.0) abs(1 / dir.x) else Double.MAX_VALUE
        val dy = if (dir.y != 0.0) abs(1 / dir.y) else Double.MAX_VALUE
        val dz = if (dir.z != 0.0) abs(1 / dir.z) else Double.MAX_VALUE
        fun first(o: Double, b: Int, s: Int, d: Double) = if (s > 0) (b + 1 - o) * d else if (s < 0) (o - b) * d else Double.MAX_VALUE
        var tx = first(eye.x, bx, sx, dx); var ty = first(eye.y, by, sy, dy); var tz = first(eye.z, bz, sz, dz)
        repeat(250) {
            val t = minOf(tx, ty, tz)
            if (t > range) return null
            val cx = tx <= t + 1e-4; val cy = ty <= t + 1e-4; val cz = tz <= t + 1e-4
            if ((if (cx) 1 else 0) + (if (cy) 1 else 0) + (if (cz) 1 else 0) >= 2) {
                if (cx && rayStops(bx + sx, by, bz)) return Triple(bx + sx, by, bz)
                if (cy && rayStops(bx, by + sy, bz)) return Triple(bx, by + sy, bz)
                if (cz && rayStops(bx, by, bz + sz)) return Triple(bx, by, bz + sz)
            }
            if (cx) { bx += sx; tx += dx }
            if (cy) { by += sy; ty += dy }
            if (cz) { bz += sz; tz += dz }
            if (rayStops(bx, by, bz)) return Triple(bx, by, bz)
        }
        return null
    }

    private fun blinkBlocks(x: Int, y: Int, z: Int): Boolean {
        val s = state(x, y, z)
        if (s.block is SignBlock || s.block is AbstractBannerBlock) return false
        if (rayStops(x, y, z) && top(x, y, z) >= 0.5) return true
        return top(x, y - 1, z) > 1.0
    }

    private fun feetPass(x: Int, y: Int, z: Int) = (bodyPasses(x, y, z) || top(x, y, z) < 0.5) && top(x, y - 1, z) <= 1.0

    /** AOTV (12) / Hyperion (10): whole-block steps, every quarter checked; "There are blocks in the way!" when cut short. */
    fun blink(p: ServerPlayer, range: Int) {
        val eye = p.eyePosition
        val dir = look(p)
        var last = 0
        var px = floor(eye.x).toInt(); var pz = floor(eye.z).toInt()
        var cut = false
        loop@ for (i in 1..range) {
            for (k in 1..4) {
                val q = eye.add(dir.scale((i - 1) + k * 0.25))
                if (blinkBlocks(floor(q.x).toInt(), floor(q.y).toInt(), floor(q.z).toInt())) { cut = true; break@loop }
            }
            val c = eye.add(dir.scale(i.toDouble()))
            val cx = floor(c.x).toInt(); val cy = floor(c.y).toInt(); val cz = floor(c.z).toInt()
            if (cx != px && cz != pz && blinkBlocks(px, cy, cz) && blinkBlocks(cx, cy, pz)) { cut = true; break }
            last = i; px = cx; pz = cz
        }
        if (last == 0) { Sim.chat("§cThere are blocks in the way!"); return }
        val c = eye.add(dir.scale(last.toDouble()))
        val cx = floor(c.x).toInt(); val cy = floor(c.y).toInt(); val cz = floor(c.z).toInt()
        val feetY = if (feetPass(cx, cy - 1, cz)) cy - 1 else cy
        if (cx == floor(p.x).toInt() && cz == floor(p.z).toInt() && (feetY == floor(p.y + 0.05).toInt() || Vec3(cx + 0.5, feetY.toDouble(), cz + 0.5).distanceTo(p.position()) < 1.5)) {
            Sim.chat("§cThere are blocks in the way!"); return
        }
        if (cut) Sim.chat("§cThere are blocks in the way!")
        Sim.tp(p, cx + 0.5, feetY.toDouble(), cz + 0.5)
        Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, p.position())
    }

    /** Wither Impact's server cooldown: a second cast within 2 server ticks is discarded, blink and Implosion both (item-mechanics.md §3, SRV-Q15/16). */
    private var lastHype = -100

    private fun hypeReady(): Boolean {
        val now = Fight.serverTick
        if (now - lastHype < 2) return false
        lastHype = now
        return true
    }

    /** A Hyperion Implosion's hit, for the chat line (no damage model in the sim). */
    private const val IMPLOSION_DAMAGE = 1_846_213.4

    /**
     * Implosion (item-mechanics.md §3): at your final position, every mob whose hitbox is within
     * ±6 x/z, +7 up and -6 down of your eye, through walls, full damage each. The boss withers are
     * the only mobs here; with none in the box there is no message.
     */
    private fun implode(p: ServerPlayer) {
        Sim.level.sendParticles(ParticleTypes.EXPLOSION, p.x, p.eyeY, p.z, 1, 0.0, 0.0, 0.0, 0.0)
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 1f, 1f, p.position())
        val box = net.minecraft.world.phys.AABB(p.x - 6, p.eyeY - 6, p.z - 6, p.x + 6, p.eyeY + 7, p.z + 6)
        val n = Sim.level.getEntitiesOfClass(net.minecraft.world.entity.boss.wither.WitherBoss::class.java, box) { it.isAlive }.size
        if (n > 0) Sim.chat("§7Your Implosion hit §c$n§7 ${if (n == 1) "enemy" else "enemies"} for §c${"%,.1f".format(n * IMPLOSION_DAMAGE)}§7 damage.")
    }

    // ------------------------------------------------------------------ movement items

    private fun push(p: ServerPlayer, v: Vec3) {
        p.deltaMovement = v
        p.hurtMarked = true
    }

    /**
     * Bonzo's Staff (tools/p3sim/research/knockback.md): an invisible balloon flies along your look,
     * ~0.9 a tick, no gravity, bursting (a firework) on the first tick it's inside a block, 0.5 into
     * it; gone after 5 ticks if it hits nothing. On the burst tick (or the next) your motion is
     * *replaced* by 1.5 flat away from the burst and 0.5 up, from where you are then: ~4 ticks
     * after the click. Out of ~5 blocks: nothing.
     */
    private fun bonzo(p: ServerPlayer) {
        Sim.sound(SoundEvents.GHAST_AMBIENT, 0.6f, 1.5f + Random.nextFloat() * 0.25f, p.position())
        val eye = p.eyePosition
        val dir = look(p)
        val hit = Sim.level.clip(net.minecraft.world.level.ClipContext(eye, eye.add(dir.scale(BONZO_SPEED * BONZO_LIFE)), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
        if (hit.type == net.minecraft.world.phys.HitResult.Type.MISS) return
        val burst = hit.location.add(dir.scale(0.5))
        // The first whole tick the balloon is inside the block (at least 2: the spawn tick and one move).
        val ticks = Math.ceil(eye.distanceTo(burst) / BONZO_SPEED).toInt().coerceIn(2, BONZO_LIFE)
        Fight.later(ticks, "bonzo burst") {
            Sim.level.sendParticles(ParticleTypes.FIREWORK, burst.x, burst.y, burst.z, 20, 0.1, 0.1, 0.1, 0.15)
            Sim.sound(SoundEvents.FIREWORK_ROCKET_BLAST, 1f, 1f, burst)
            Fight.later(if (Random.nextBoolean()) 0 else 1, "bonzo boost") {
                val away = p.position().subtract(burst).multiply(1.0, 0.0, 1.0)
                if (away.length() > BONZO_REACH) return@later
                val h = if (away.lengthSqr() < 1e-4) dir.multiply(-1.0, 0.0, -1.0).normalize() else away.normalize()
                push(p, Vec3(h.x * 1.5, 0.5, h.z * 1.5))
            }
        }
    }

    private const val BONZO_SPEED = 0.9
    private const val BONZO_LIFE = 5
    private const val BONZO_REACH = 5.0

    /**
     * Jerry-chine Gun (knockback.md §Jerry): a Jerry lands where you look; 1-3 ticks later (mostly
     * 2) your motion is replaced by vy 0.6 and a flat push away from it (0.5 x the 3D direction's
     * flat part), with villager.yes.
     */
    private fun jerry(p: ServerPlayer) {
        Sim.sound(SoundEvents.VILLAGER_TRADE, 0.6f, 1f, p.position())
        val eye = p.eyePosition
        val hit = Sim.level.clip(net.minecraft.world.level.ClipContext(eye, eye.add(look(p).scale(5.0)), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
        if (hit.type == net.minecraft.world.phys.HitResult.Type.MISS) return
        val at = hit.location
        val r = Random.nextDouble()
        Fight.later(if (r < 0.2) 1 else if (r < 0.8) 2 else 3, "jerry boost") {
            if (at.distanceTo(p.position()) > 3.5) return@later
            val d = p.position().subtract(at)
            val n = if (d.lengthSqr() < 1e-6) Vec3.ZERO else d.normalize()
            push(p, Vec3(n.x * 0.5, 0.6, n.z * 0.5))
            Sim.sound(SoundEvents.VILLAGER_YES, 0.6f, 1f, p.position())
        }
    }

    /** Creeper Veil: on until used again (or 10 s), then 10 s of cooldown; death ticks don't hit while it's on. */
    var cloakUntil = 0
        private set
    private var cloakReady = 0
    val cloaked get() = Fight.serverTick < cloakUntil

    private fun cloak(p: ServerPlayer) {
        val now = Fight.serverTick
        if (cloaked) { cloakUntil = now; cloakReady = now + 200; Sim.chat("§cCreeper Veil De-activated!"); return }
        if (now < cloakReady) { Sim.chat("§cThis ability is on cooldown for ${(cloakReady - now + 19) / 20}s."); return }
        cloakUntil = now + 200; cloakReady = now + 400
        Fight.later(200, "cloak expired") { if (cloakUntil == now + 200) Sim.chat("§cCreeper Veil De-activated! (Expired)") }
        Sim.chat("§aCreeper Veil Activated!")
        Sim.sound(SoundEvents.CREEPER_PRIMED, 0.6f, 1f, p.position())
    }

    // ------------------------------------------------------------------ Superboom, Dungeonbreaker, bows

    /** Superboom TNT: blows the gate it's used on (or near where you look within 5), once that gate's section has started. */
    private fun superboom(p: ServerPlayer, on: BlockPos?) {
        val phase = Fight.phase as? GoldorPhase ?: return
        val at = on?.let { Vec3.atCenterOf(it) } ?: run {
            val eye = p.eyePosition
            Sim.level.clip(net.minecraft.world.level.ClipContext(eye, eye.add(look(p).scale(5.0)), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p)).location
        }
        Sim.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
        Sim.sound(SoundEvents.GENERIC_EXPLODE, 1f, 1f, at)
        val gate = phase.gateNear(at, 1.5)
        if (gate > 0) phase.blowGate(gate, Sim.me)
    }

    /** Blocks the Dungeonbreaker never mines: the shell, gates, doors, the core's gold and anything the fight uses. */
    // ------------------------------------------------------------------ Dungeonbreaker (dungeonbreaker.md)

    private const val THAT_BLOCK = "§cA mystical force prevents you from digging that block!"
    private const val INNER_CHAMBER = "§cA mystical force prevents you from leaving the inner chamber!"
    private const val NO_CHARGES = "§cYou don't have enough charges to break this block right now!"
    const val MAX_CHARGES = 20

    /** The core entrance's gold door (the only gold you can mine). */
    private val CORE_DOOR = AABB(52.0, 115.0, 54.0, 57.0, 122.0, 55.0)
    /** The inner chamber under the core platform: you can mine into it, not out of it. */
    private val INNER = AABB(39.0, 0.0, 99.0, 70.0, 113.0, 130.0)

    /** Why Hypixel refuses [pos]: a chat line, "" (silently), or null (it breaks). */
    private fun refusal(p: ServerPlayer, pos: BlockPos, s: BlockState): String? {
        val b = s.block
        val c = Vec3.atCenterOf(pos)
        // Out of the core (into it is fine).
        if (INNER.contains(p.position()) && !INNER.contains(c)) return INNER_CHAMBER
        if (s.getDestroySpeed(Sim.level, pos) < 0) return THAT_BLOCK
        if (b == net.minecraft.world.level.block.Blocks.BARRIER || b == net.minecraft.world.level.block.Blocks.BEDROCK) return THAT_BLOCK
        if (b is net.minecraft.world.level.block.CommandBlock) return THAT_BLOCK
        if (b == net.minecraft.world.level.block.Blocks.GOLD_BLOCK && !CORE_DOOR.contains(c)) return THAT_BLOCK
        if (b is LeverBlock || b is ButtonBlock) return THAT_BLOCK
        // Out of reach (4.5 from the eyes): the server just puts it back.
        if (p.eyePosition.distanceTo(c) > 5.2) return ""
        return null
    }

    /** Charges (max 20), refilled in a batch every second; blocks broken, oldest first, and when. */
    var charges = MAX_CHARGES; private set
    private var refillAt = 0
    private class Broken(val pos: BlockPos, val state: BlockState, val at: Int)
    private val broken = ArrayDeque<Broken>()
    private var refusedSaidAt = -100
    private var noChargesSaidAt = -100

    private fun resetBreaker() { charges = MAX_CHARGES; refillAt = 0; broken.clear(); refusedSaidAt = -100; noChargesSaidAt = -100 }

    /**
     * A hit with the Dungeonbreaker reaching the server (after the ping): breaks that one block for
     * a charge, or refuses it and sends it back (the client already broke it, as on Hypixel).
     */
    private fun mine(p: ServerPlayer, pos: BlockPos) {
        val level = Sim.level
        val s = level.getBlockState(pos)
        if (s.isAir) return
        val now = Fight.serverTick
        val why = refusal(p, pos, s)
        if (why != null || charges <= 0) {
            p.connection.send(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(level, pos))
            if (why == null) { if (now - noChargesSaidAt >= 20) { noChargesSaidAt = now; Sim.chat(NO_CHARGES) } }
            else if (why.isNotEmpty() && now - refusedSaidAt >= 20) { refusedSaidAt = now; Sim.chat(why) }
            return
        }
        charges--
        Blocks.set(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
        broken.addLast(Broken(pos, s, now))
        // The 21st block broken brings the first back.
        while (broken.size > MAX_CHARGES) restore(broken.removeFirst())
    }

    private fun restore(b: Broken) {
        if (Sim.level.getBlockState(b.pos).isAir) Blocks.set(b.pos, b.state)
    }

    private fun tickBreaker() {
        val now = Fight.serverTick
        if (charges < MAX_CHARGES && now >= refillAt) { charges = (charges + P3Sim.breakerRefill).coerceAtMost(MAX_CHARGES); refillAt = now + 20 }
        else if (charges >= MAX_CHARGES) refillAt = now + 20
        val regen = (P3Sim.breakerRegen * 20).toInt()
        while (broken.isNotEmpty() && now - broken.first().at >= regen) restore(broken.removeFirst())
    }

    /** The next tick a shortbow can fire (P3Sim's Terminator Cooldown apart). */
    private var bowReady = 0

    /**
     * Shortbows: [n] arrows at once, 3.0 a tick with vanilla gravity. The Terminator's side arrows
     * are Terminator Spread degrees of yaw off the middle one; a click inside the cooldown does nothing.
     */
    private fun shoot(p: ServerPlayer, n: Int) {
        val now = Fight.serverTick
        if (now < bowReady) return
        bowReady = now + P3Sim.termCooldown
        val level = Sim.level
        val spread = P3Sim.termSpread
        val yaws = if (n == 3) listOf(-spread, 0f, spread) else listOf(0f)
        for (dy in yaws) {
            val a = Arrow(level, p, ItemStack(Items.ARROW), null)
            a.shootFromRotation(p, p.xRot, p.yRot + dy, 0f, 3.0f, 0f)
            a.pickup = AbstractArrow.Pickup.DISALLOWED
            Sim.spawn(a)
            arrows += a
        }
        Sim.sound(SoundEvents.ARROW_SHOOT, 1f, 1.2f, p.position())
    }

    private val arrows = ArrayList<AbstractArrow>()
    private val lastMotion = HashMap<AbstractArrow, Vec3>()

    /** Arrows that hit something: the target device's blocks count, every arrow goes away after 3 s. */
    fun tick() {
        val level = SimServer.level ?: return
        tickBreaker()
        // Vanilla bow arrows too.
        level.getEntitiesOfClass(AbstractArrow::class.java, AABB(-20.0, 0.0, -20.0, 160.0, 256.0, 160.0)) { it.owner is Player && it !in arrows }.forEach { arrows += it }
        // Each arrow's path since last tick (and a little on), traced against the blocks: the first
        // block on it is what it hit, however vanilla left the arrow (stuck, or still moving).
        val it = arrows.iterator()
        while (it.hasNext()) {
            val a = it.next()
            if (a.isRemoved) { it.remove(); lastMotion.remove(a); lastPos.remove(a); continue }
            val v = a.deltaMovement
            if (v.lengthSqr() > 1e-3) lastMotion[a] = v
            val dir = (lastMotion[a] ?: v).let { if (it.lengthSqr() < 1e-6) Vec3.ZERO else it.normalize() }
            val from = lastPos[a] ?: a.position().subtract(dir)
            val to = a.position().add(dir.scale(0.6))
            lastPos[a] = a.position()
            val hit = level.clip(net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, a))
            if (hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                (Fight.phase as? GoldorPhase)?.devices?.target?.hit(hit.blockPos)
                a.discard()
                it.remove(); lastMotion.remove(a); lastPos.remove(a)
                continue
            }
            if (a.tickCount > 100 || v.lengthSqr() < 1e-3) { a.discard(); it.remove(); lastMotion.remove(a); lastPos.remove(a) }
        }
    }

    private val lastPos = HashMap<AbstractArrow, Vec3>()

    // ------------------------------------------------------------------ Spirit Leap

    /** Spirit Leap's 2 s cooldown (items-timing.md). */
    private var leapReady = 0

    private fun openLeap(p: ServerPlayer) {
        val now = Fight.serverTick
        if (now < leapReady) { Sim.chat("§cThis ability is on cooldown for ${(leapReady - now + 19) / 20}s."); return }
        val bots = Party.bots().filter { it.entity != null }
        p.openMenu(SimpleMenuProvider({ id, inv, _ -> LeapMenu(id, inv, bots) }, Component.literal("Spirit Leap")))
    }

    /** Hypixel's Spirit Leap window: teammates' heads in slots 11-15, a click leaps (8 ticks, as measured). */
    class LeapMenu(id: Int, inv: Inventory, val bots: List<Party.Bot>) : ChestMenu(MenuType.GENERIC_9x4, id, inv, SimpleContainer(36), 4) {
        init {
            for (i in 0 until 36) container.setItem(i, Terminals.FILLER)
            // In the plan's leap slot order: slots 1-4 = chest slots 11, 12, 14, 15.
            bots.sortedBy { it.slot }.forEachIndexed { i, b ->
                val h = ItemStack(Items.PLAYER_HEAD)
                h.set(DataComponents.CUSTOM_NAME, Component.literal(b.name).withStyle { it.withItalic(false).withColor(net.minecraft.ChatFormatting.GREEN) })
                h.set(DataComponents.LORE, ItemLore(listOf(Component.literal("§7Class: §e${b.clazz.name}").withStyle { it.withItalic(false) })))
                container.setItem(listOf(11, 12, 14, 15).getOrElse(i) { 16 }, h)
            }
        }

        override fun clicked(slot: Int, button: Int, input: ContainerInput, p: Player) {
            if (slot !in 11..16) return
            val name = net.minecraft.ChatFormatting.stripFormatting(container.getItem(slot).hoverName.string)
            val bot = bots.firstOrNull { it.name == name } ?: return
            val sp = p as ServerPlayer
            sp.closeContainer()
            Fight.afterPing("leap") {
                val e = bot.pos
                leapReady = Fight.serverTick + 40
                // You land on them exactly, facing as they face.
                Sim.tp(sp, e.x, e.y, e.z, bot.yaw, bot.entity?.xRot ?: sp.xRot)
                Sim.chat("§aYou have teleported to §r§b${bot.name}§r§a!")
                Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, sp.position())
            }
        }

        override fun quickMoveStack(p: Player, slot: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(p: Player) = true
    }
}
