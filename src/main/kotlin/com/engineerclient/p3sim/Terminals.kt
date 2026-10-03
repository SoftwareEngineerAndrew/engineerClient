package com.engineerclient.p3sim

import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.random.Random

/**
 * F7's six terminals as Hypixel serves them (measured from Better PF recordings, see
 * `tools/p3sim/research/terminals.md`): the same titles, window sizes, items, names and counts, so
 * Odin's solver and custom GUI take them for the real thing.
 *
 * As on Hypixel: the window opens empty and its items follow a tick later, one slot update each
 * (Odin only solves on those); the window is never reopened, clicks change single slots; left,
 * right and middle clicks all count (Odin sends middle); a wrong click does nothing; the finishing
 * click closes the window in the same tick as the chat line.
 */
object Terminals {
    enum class Type(val rows: Int) { ORDER(4), PANES(5), RUBIX(5), STARTS(5), SELECT(6), MELODY(6) }

    /** Item with a plain, non-italic name (and [count]). */
    fun named(item: Item, name: String, count: Int = 1, glint: Boolean = false): ItemStack {
        val s = ItemStack(item, count)
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { it.withItalic(false) })
        if (glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        return s
    }

    val FILLER: ItemStack get() = named(Items.BLACK_STAINED_GLASS_PANE, "")

    private fun item(id: String): Item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id))

    /** Opens [type] (random when null) for [player] at [station]. */
    fun open(player: ServerPlayer, station: Station, type: Type? = null) {
        val t = type ?: station.nextType()
        val term = station.term?.takeIf { it.type == t && !it.done } ?: Term.create(t).also { station.term = it }
        player.openMenu(SimpleMenuProvider({ id, inv, _ -> TerminalMenu(id, inv, term, station) }, Component.literal(term.title)))
    }

    // ------------------------------------------------------------------ the puzzles

    /** One terminal's puzzle: its window contents and what a click does. */
    abstract class Term(val type: Type) {
        abstract val title: String
        val size get() = type.rows * 9
        /** The window's items (index = slot). */
        val items = Array(type.rows * 9) { FILLER }
        var done = false
        abstract fun click(slot: Int, button: Int, input: ContainerInput): Boolean
        abstract fun solved(): Boolean
        open fun tick(t: Int) {}

        companion object {
            fun create(t: Type): Term = when (t) {
                Type.ORDER -> Order()
                Type.PANES -> Panes()
                Type.RUBIX -> Rubix()
                Type.STARTS -> Starts()
                Type.SELECT -> Select()
                Type.MELODY -> Melody()
            }
        }
    }

    /** "Click in order!": 14 red panes, count and name 1..14, in the 2 x 7 middle. */
    class Order : Term(Type.ORDER) {
        override val title = "Click in order!"
        private val slots = (10..16) + (19..25)
        private var next = 1
        init {
            slots.shuffled().forEachIndexed { i, s -> items[s] = named(Items.RED_STAINED_GLASS_PANE, "${i + 1}", i + 1) }
        }
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            val it = items[slot]
            if (it.item != Items.RED_STAINED_GLASS_PANE || it.count != next) return false
            items[slot] = named(Items.LIME_STAINED_GLASS_PANE, "$next", next)
            next++
            return true
        }
        override fun solved() = next > 14
    }

    /** "Correct all the panes!": 15 panes, Off (red) or On (lime), clicks toggle. */
    class Panes : Term(Type.PANES) {
        override val title = "Correct all the panes!"
        private val slots = (11..15) + (20..24) + (29..33)
        init {
            // 0-8 start On, median 3 (measured).
            val on = listOf(0, 1, 1, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 5, 6).random()
            val lit = slots.shuffled().take(on).toSet()
            slots.forEach { items[it] = pane(it in lit) }
        }
        private fun pane(on: Boolean) = if (on) named(Items.LIME_STAINED_GLASS_PANE, "On") else named(Items.RED_STAINED_GLASS_PANE, "Off")
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            if (slot !in slots) return false
            items[slot] = pane(items[slot].item == Items.RED_STAINED_GLASS_PANE)
            return true
        }
        override fun solved() = slots.all { items[it].item == Items.LIME_STAINED_GLASS_PANE }
    }

    /** "Change all to same color!": 3 x 3 panes, left/middle step forward R-O-Y-G-B, right back. */
    class Rubix : Term(Type.RUBIX) {
        override val title = "Change all to same color!"
        // Slot 32 last: Odin locks its target colour on that slot's update.
        private val slots = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)
        private val cycle = listOf(Items.RED_STAINED_GLASS_PANE to "Red", Items.ORANGE_STAINED_GLASS_PANE to "Orange", Items.YELLOW_STAINED_GLASS_PANE to "Yellow", Items.GREEN_STAINED_GLASS_PANE to "Green", Items.BLUE_STAINED_GLASS_PANE to "Blue")
        private val colour = IntArray(45)
        init {
            do { slots.forEach { colour[it] = Random.nextInt(5) } } while (solved())
            slots.forEach { set(it) }
        }
        private fun set(slot: Int) { val (i, n) = cycle[colour[slot]]; items[slot] = named(i, n) }
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            if (slot !in slots) return false
            colour[slot] = (colour[slot] + if (button == 1 && input == ContainerInput.PICKUP) 4 else 1) % 5
            set(slot)
            return true
        }
        override fun solved() = slots.all { colour[it] == colour[slots[0]] }
    }

    /** "What starts with: 'X'?": 21 items (1.8 names); click every one starting with X (it glints). */
    class Starts : Term(Type.STARTS) {
        private val slots = (10..16) + (19..25) + (28..34)
        private val letter: Char
        override val title: String
        init {
            val letters = STARTS_POOL.groupBy { it.second[0] }.filter { it.value.size >= 3 }.keys.toList()
            letter = letters.random()
            title = "What starts with: '$letter'?"
            val right = STARTS_POOL.filter { it.second[0] == letter }
            val wrong = STARTS_POOL.filter { it.second[0] != letter }
            val n = listOf(2, 3, 4, 5, 5, 6, 6, 7, 7, 7, 8, 8, 9, 10, 11, 12).random()
            val picks = (List(n) { right.random() } + List(slots.size - n) { wrong.random() }).shuffled()
            slots.forEachIndexed { i, s -> val (id, name) = picks[i]; items[s] = named(item(id), name) }
        }
        private fun want(s: ItemStack) = s.hoverName.string.startsWith(letter)
        // By slot: some items glint on their own (Enchanted Book, Bottle o' Enchanting, Nether Star).
        private val picked = HashSet<Int>()
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            val s = items[slot]
            if (slot !in slots || !want(s) || slot in picked) return false
            picked += slot
            items[slot] = s.copy().also { it.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
            return true
        }
        override fun solved() = slots.all { !want(items[it]) || it in picked }
    }

    /** "Select all the X items!": 28 items of 5 colours, 5-6 of X; click them all (they glint). */
    class Select : Term(Type.SELECT) {
        private val slots = (10..16) + (19..25) + (28..34) + (37..43)
        private val target = COLOURS.random()
        override val title = "Select all the ${target.title} items!"
        init {
            val others = (COLOURS - target).shuffled().take(4)
            val n = if (Random.nextInt(56) < 29) 5 else 6
            val picks = (List(n) { target.items.random() } + List(slots.size - n) { others.random().items.random() }).shuffled()
            slots.forEachIndexed { i, s -> val (id, name) = picks[i]; items[s] = named(item(id), name) }
        }
        private val wanted = target.items.map { item(it.first) }.toSet()
        private fun want(s: ItemStack) = s.item in wanted
        // By slot: some items glint on their own (Enchanted Book, Bottle o' Enchanting, Nether Star).
        private val picked = HashSet<Int>()
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            val s = items[slot]
            if (slot !in slots || !want(s) || slot in picked) return false
            picked += slot
            items[slot] = s.copy().also { it.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
            return true
        }
        override fun solved() = slots.all { !want(items[it]) || it in picked }
    }

    /**
     * "Click the button on time!": a lime pane bounces along the active row (one column every 10
     * ticks); Lock In Slot while it is in the magenta column. The row moves on at the next step;
     * the 4th lock finishes at once.
     */
    class Melody : Term(Type.MELODY) {
        override val title = "Click the button on time!"
        private var row = 0
        private var target = Random.nextInt(1, 6)
        private var lime = 1
        private var dir = 1
        private var locked = false
        private var lastStep = -1
        init { draw() }
        private fun draw() {
            for (i in items.indices) items[i] = FILLER
            items[target] = named(Items.MAGENTA_STAINED_GLASS_PANE, "")
            items[45 + target] = named(Items.MAGENTA_STAINED_GLASS_PANE, "")
            for (r in 0 until 4) {
                for (c in 1..5) {
                    val slot = (r + 1) * 9 + c
                    items[slot] = when {
                        r != row -> named(Items.WHITE_STAINED_GLASS_PANE, "")
                        c == lime -> named(Items.LIME_STAINED_GLASS_PANE, "")
                        else -> named(Items.RED_STAINED_GLASS_PANE, "")
                    }
                }
                items[(r + 1) * 9 + 7] = if (r == row) named(Items.LIME_TERRACOTTA, "Lock In Slot") else named(Items.RED_TERRACOTTA, "Row Not Active")
            }
        }
        override fun tick(t: Int) {
            // t = ticks since the items appeared: a step every 10.
            if (t <= 0) lastStep = -1
            if (t <= 0 || t % 10 != 0 || t == lastStep) return
            lastStep = t
            if (lime + dir !in 1..5) dir = -dir
            lime += dir
            if (locked) { locked = false; row++; target = Random.nextInt(1, 6) }
            draw()
        }
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            if (slot != (row + 1) * 9 + 7 || locked || lime != target) return false
            if (row == 3) { row = 4; return true }
            locked = true
            return true
        }
        override fun solved() = row >= 4
    }

    // ------------------------------------------------------------------ the window

    class TerminalMenu(id: Int, inv: Inventory, val term: Term, val station: Station) :
        ChestMenu(menuType(term.type.rows), id, inv, SimpleContainer(term.size), term.type.rows) {
        private val player = inv.player as ServerPlayer
        private var opened = Fight.serverTick
        private var filled = false

        init { Terminals.open += this }

        /** Called every server tick while open. */
        fun tick() {
            if (player.containerMenu !== this) { open -= this; return }
            val age = Fight.serverTick - opened
            // Hypixel fills the window a tick after opening it.
            if (!filled && age >= 1) { filled = true; sync() }
            if (filled) {
                term.tick(age - 1)
                sync()
            }
        }

        /** Copies the puzzle into the container; the game sends the changed slots one by one. */
        private fun sync() {
            for (i in 0 until term.size) {
                if (!ItemStack.matches(container.getItem(i), term.items[i])) container.setItem(i, term.items[i].copy())
            }
        }

        override fun clicked(slot: Int, button: Int, input: ContainerInput, p: Player) {
            if (!filled || term.done || slot !in 0 until term.size) { undo(slot); return }
            Fight.afterPing("terminal click") {
                if (player.containerMenu !== this || term.done) return@afterPing
                if (!term.click(slot, button, input)) { undo(slot); return@afterPing }
                sync()
                if (term.solved()) {
                    term.done = true
                    station.complete(Sim.me)
                    player.closeContainer()
                }
            }
        }

        /** A refused click: the client's guess (the item on its cursor, the slot emptied) is put back. */
        private fun undo(slot: Int) {
            player.connection.send(net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket(ItemStack.EMPTY))
            if (slot in 0 until slots.size) player.connection.send(net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(containerId, incrementStateId(), slot, getSlot(slot).item.copy()))
        }

        /** A click from a client a step behind: answer slot by slot, never with a full refill (Odin ignores those). */
        override fun broadcastFullState() = broadcastChanges()

        override fun quickMoveStack(p: Player, slot: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(p: Player) = !term.done
        override fun removed(p: Player) { super.removed(p); open -= this }
    }

    private fun menuType(rows: Int): MenuType<ChestMenu> = when (rows) {
        4 -> MenuType.GENERIC_9x4
        5 -> MenuType.GENERIC_9x5
        else -> MenuType.GENERIC_9x6
    }

    private val open = ArrayList<TerminalMenu>()

    fun tick() { open.toList().forEach { it.tick() } }

    fun closeAll() {
        Sim.player?.let { if (it.containerMenu is TerminalMenu) it.closeContainer() }
        open.clear()
    }

    /** Is the player in [station]'s terminal right now. */
    fun inUse(station: Station) = open.any { it.station === station }

    // ------------------------------------------------------------------ item pools (Hypixel's, 1.8 names)

    class Colour(val title: String, val items: List<Pair<String, String>>)

    private fun colour(id: String, title: String, name: String, dye: Pair<String, String>, wool: String = "$name Wool") =
        Colour(title, listOf("${id}_stained_glass" to "$name Stained Glass", "${id}_terracotta" to "$name Stained Clay", "${id}_wool" to wool, dye))

    val COLOURS = listOf(
        colour("white", "WHITE", "White", "bone_meal" to "Bone Meal", wool = "Wool"),
        colour("orange", "ORANGE", "Orange", "orange_dye" to "Orange Dye"),
        colour("magenta", "MAGENTA", "Magenta", "magenta_dye" to "Magenta Dye"),
        colour("light_blue", "LIGHT BLUE", "Light Blue", "light_blue_dye" to "Light Blue Dye"),
        colour("yellow", "YELLOW", "Yellow", "yellow_dye" to "Dandelion Yellow"),
        colour("lime", "LIME", "Lime", "lime_dye" to "Lime Dye"),
        colour("pink", "PINK", "Pink", "pink_dye" to "Pink Dye"),
        colour("gray", "GRAY", "Gray", "gray_dye" to "Gray Dye"),
        colour("light_gray", "SILVER", "Silver", "light_gray_dye" to "Light Gray Dye"),
        colour("cyan", "CYAN", "Cyan", "cyan_dye" to "Cyan Dye"),
        colour("purple", "PURPLE", "Purple", "purple_dye" to "Purple Dye"),
        colour("blue", "BLUE", "Blue", "lapis_lazuli" to "Lapis Lazuli"),
        colour("brown", "BROWN", "Brown", "cocoa_beans" to "Cocoa Bean"),
        colour("green", "GREEN", "Green", "green_dye" to "Cactus Green"),
        colour("red", "RED", "Red", "red_dye" to "Rose Red"),
        colour("black", "BLACK", "Black", "ink_sac" to "Ink Sack"),
    )

    /** "What starts with" pool, as seen on Hypixel (vanilla id to its 1.8 name). */
    val STARTS_POOL: List<Pair<String, String>> = """
        acacia_door=Acacia Door|apple=Apple|armor_stand=Armor Stand|arrow=Arrow|baked_potato=Baked Potato|beef=Raw Beef|birch_door=Birch Door
        blaze_powder=Blaze Powder|blaze_rod=Blaze Rod|bone=Bone|book=Book|bow=Bow|bowl=Bowl|bread=Bread|brewing_stand=Brewing Stand|brick=Brick
        bucket=Bucket|cake=Cake|carrot=Carrot|carrot_on_a_stick=Carrot on a Stick|cauldron=Cauldron|chainmail_boots=Chainmail Boots
        chainmail_chestplate=Chainmail Chestplate|chainmail_helmet=Chainmail Helmet|chainmail_leggings=Chainmail Leggings|chest_minecart=Storage Minecart
        chicken=Raw Chicken|clay_ball=Clay|clock=Watch|coal=Coal|cod=Raw Fish|command_block_minecart=Minecart with Command Block
        comparator=Redstone Comparator|compass=Compass|cooked_beef=Steak|cooked_chicken=Roast Chicken|cooked_cod=Cooked Fish|cooked_mutton=Cooked Mutton
        cooked_porkchop=Cooked Porkchop|cooked_rabbit=Cooked Rabbit|cookie=Cookie|dark_oak_door=Dark Oak Door|diamond=Diamond|diamond_axe=Diamond Axe
        diamond_boots=Diamond Boots|diamond_chestplate=Diamond Chestplate|diamond_helmet=Diamond Helmet|diamond_hoe=Diamond Hoe|diamond_leggings=Diamond Leggings
        diamond_pickaxe=Diamond Pickaxe|diamond_sword=Diamond Sword|diamond_horse_armor=Diamond Horse Armor|egg=Egg|emerald=Emerald|enchanted_book=Enchanted Book
        ender_eye=Eye of Ender|ender_pearl=Ender Pearl|experience_bottle=Bottle o' Enchanting|feather=Feather|fermented_spider_eye=Fermented Spider Eye
        filled_map=Map|fire_charge=Fire Charge|firework_rocket=Firework Rocket|firework_star=Firework Star|fishing_rod=Fishing Rod|flint=Flint
        flint_and_steel=Flint and Steel|flower_pot=Flower Pot|furnace_minecart=Powered Minecart|ghast_tear=Ghast Tear|glass_bottle=Glass Bottle
        glistering_melon_slice=Glistering Melon|glowstone_dust=Glowstone Dust|gold_ingot=Gold Ingot|gold_nugget=Gold Nugget|golden_apple=Golden Apple
        golden_carrot=Golden Carrot|golden_axe=Gold Axe|golden_boots=Gold Boots|golden_chestplate=Gold Chestplate|golden_helmet=Gold Helmet|golden_hoe=Gold Hoe
        golden_leggings=Gold Leggings|golden_pickaxe=Gold Pickaxe|golden_shovel=Gold Shovel|golden_sword=Gold Sword|golden_horse_armor=Gold Horse Armor
        gunpowder=Gunpowder|hopper_minecart=Minecart with Hopper|ink_sac=Ink Sac|iron_axe=Iron Axe|iron_boots=Iron Boots|iron_chestplate=Iron Chestplate
        iron_helmet=Iron Helmet|iron_hoe=Iron Hoe|iron_leggings=Iron Leggings|iron_pickaxe=Iron Pickaxe|iron_shovel=Iron Shovel|iron_sword=Iron Sword
        iron_door=Iron Door|iron_horse_armor=Iron Horse Armor|iron_ingot=Iron Ingot|item_frame=Item Frame|jungle_door=Jungle Door|lava_bucket=Lava Bucket
        lead=Lead|leather=Leather|leather_boots=Leather Boots|leather_chestplate=Leather Tunic|leather_helmet=Leather Cap|leather_leggings=Leather Pants
        magma_cream=Magma Cream|map=Empty Map|melon_seeds=Melon Seeds|melon_slice=Melon|milk_bucket=Milk Bucket|minecart=Minecart|mushroom_stew=Mushroom Stew
        mutton=Raw Mutton|name_tag=Name Tag|nether_brick=Nether Brick|nether_star=Nether Star|nether_wart=Nether Wart|oak_boat=Boat|oak_door=Wooden Door
        oak_sign=Oak Sign|painting=Painting|paper=Paper|poisonous_potato=Poisonous Potato|polar_bear_spawn_egg=Spawn Egg|porkchop=Raw Porkchop|potato=Potato
        potion=Water Bottle|prismarine_crystals=Prismarine Crystals|prismarine_shard=Prismarine Shard|pumpkin_pie=Pumpkin Pie|pumpkin_seeds=Pumpkin Seeds
        quartz=Nether Quartz|rabbit=Raw Rabbit|rabbit_foot=Rabbit Foot|rabbit_hide=Rabbit Hide|rabbit_stew=Rabbit Stew|red_bed=Bed|redstone=Redstone
        repeater=Redstone Repeater|rotten_flesh=Rotten Flesh|saddle=Saddle|shears=Shears|skeleton_skull=Skull Item|slime_ball=Slime Ball|snowball=Snowball
        spider_eye=Spider Eye|spruce_door=Spruce Door|stick=Stick|stone_axe=Stone Axe|stone_hoe=Stone Hoe|stone_pickaxe=Stone Pickaxe|stone_shovel=Stone Shovel
        stone_sword=Stone Sword|string=String|sugar=Sugar|sugar_cane=Sugar Cane|tnt_minecart=Minecart with TNT|water_bucket=Water Bucket|wheat=Wheat
        wheat_seeds=Seeds|wooden_axe=Wooden Axe|wooden_hoe=Wooden Hoe|wooden_pickaxe=Wooden Pickaxe|wooden_shovel=Wooden Shovel|wooden_sword=Wooden Sword
        writable_book=Book and Quill|written_book=Written Book
    """.trimIndent().split('|', '\n').map { it.trim() }.filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
}
