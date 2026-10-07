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
 * F7's six terminals as Hypixel serves them (measured from Better PF and Dungeon Recorder runs, see
 * `tools/p3sim/research/terminals.md` and the terminals audit): the same titles, window sizes, items,
 * names and counts, so Odin's solver and custom GUI take them for the real thing.
 *
 * As on Hypixel: the items come in the same tick as the window, one slot update each (no full
 * refill; Odin only solves on those), and one full refill ~5 ticks later; a click is answered by a
 * pling at once and its slot update the tick after; at most 5 clicks count in any 10 ticks (the
 * rest get no answer at all); left, right and middle clicks all count (Odin sends middle); a wrong
 * Melody lock stalls it 20 ticks; every open is a fresh puzzle of the terminal's type; the
 * finishing click closes the window (then the chat line, then a close of window 0) in one tick.
 */
object Terminals {
    enum class Type(val rows: Int) { ORDER(4), PANES(5), RUBIX(5), STARTS(5), SELECT(6), MELODY(6) }

    /** A random draw: the six are equally likely (first opens per station, n = 1164: 182-211 each). */
    fun randomType(): Type = Type.entries.filter { it != Type.MELODY || !P3Sim.noMelodies }.random()

    fun <T> weighted(w: List<Pair<T, Int>>): T {
        var r = kotlin.random.Random.nextInt(w.sumOf { it.second })
        for ((v, n) in w) { if (r < n) return v; r -= n }
        return w.last().first
    }

    /** Item with a non-italic name (in [color] if given, as Hypixel colours most terminal items) and [count]. */
    fun named(item: Item, name: String, count: Int = 1, glint: Boolean = false, color: net.minecraft.ChatFormatting? = null): ItemStack {
        val s = ItemStack(item, count)
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle { st -> st.withItalic(false).let { if (color != null) it.withColor(color) else it } })
        if (glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        return s
    }

    /** Hypixel's filler: a nameless black pane whose tooltip is hidden (TERM-10). */
    val FILLER: ItemStack get() = named(Items.STAINED_GLASS_PANE.black(), "").also {
        it.set(DataComponents.TOOLTIP_DISPLAY, net.minecraft.world.item.component.TooltipDisplay(true, java.util.LinkedHashSet()))
    }

    private fun item(id: String): Item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id))

    /**
     * Opens [station]'s terminal for [player]. As on Hypixel, every open deals a fresh puzzle: a
     * terminal closed unsolved comes back with new items and no progress, always of the same type
     * (41 of 41 reopens on main).
     */
    fun open(player: ServerPlayer, station: Station, type: Type? = null) {
        val keep = station.term?.type?.takeIf { Fight.forcedTerminal == null }
        val term = Term.create(type ?: keep ?: station.nextType()).also { station.term = it }
        player.openMenu(SimpleMenuProvider({ id, inv, _ -> TerminalMenu(id, inv, term, station) }, Component.literal(term.title)))
        // A lever click at you as the window opens (vol 0.5, pitch 1, blocks; TERM-08).
        Sim.sound(net.minecraft.sounds.SoundEvents.LEVER_CLICK, 0.5f, 1f, source = net.minecraft.sounds.SoundSource.BLOCKS)
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
            slots.shuffled().forEachIndexed { i, s -> items[s] = named(Items.STAINED_GLASS_PANE.red(), "${i + 1}", i + 1, color = net.minecraft.ChatFormatting.GREEN) }
        }
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            val it = items[slot]
            if (it.item != Items.STAINED_GLASS_PANE.red() || it.count != next) return false
            items[slot] = named(Items.STAINED_GLASS_PANE.lime(), "$next", next, color = net.minecraft.ChatFormatting.GREEN)
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
            // 0-9 start On, median 3 (measured, n = 181).
            val on = weighted(listOf(0 to 8, 1 to 24, 2 to 36, 3 to 44, 4 to 40, 5 to 16, 6 to 5, 7 to 4, 8 to 3, 9 to 1))
            val lit = slots.shuffled().take(on).toSet()
            slots.forEach { items[it] = pane(it in lit) }
        }
        private fun pane(on: Boolean) = if (on) named(Items.STAINED_GLASS_PANE.lime(), "On", color = net.minecraft.ChatFormatting.GREEN) else named(Items.STAINED_GLASS_PANE.red(), "Off", color = net.minecraft.ChatFormatting.RED)
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            if (slot !in slots) return false
            items[slot] = pane(items[slot].item == Items.STAINED_GLASS_PANE.red())
            return true
        }
        override fun solved() = slots.all { items[it].item == Items.STAINED_GLASS_PANE.lime() }
    }

    /** "Change all to same color!": 3 x 3 panes, left/middle step forward R-O-Y-G-B, right back. */
    class Rubix : Term(Type.RUBIX) {
        override val title = "Change all to same color!"
        // Slot 32 last: Odin locks its target colour on that slot's update.
        private val slots = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)
        private val cycle = listOf(Items.STAINED_GLASS_PANE.red() to "Red", Items.STAINED_GLASS_PANE.orange() to "Orange", Items.STAINED_GLASS_PANE.yellow() to "Yellow", Items.STAINED_GLASS_PANE.green() to "Green", Items.STAINED_GLASS_PANE.blue() to "Blue")
        private val colour = IntArray(45)
        init {
            // Boards drawn to main's fewest-clicks spread (n = 33: 4:3 5:4 6:4 7:6 8:7 9:7 10:2; mean
            // 7.2, easier than 9 free draws' 8.1): free draws until one needs the drawn count.
            val want = weighted(listOf(4 to 3, 5 to 4, 6 to 4, 7 to 6, 8 to 7, 9 to 7, 10 to 2))
            do { slots.forEach { colour[it] = Random.nextInt(5) } } while (solved() || minClicks() != want)
            slots.forEach { set(it) }
        }
        private fun set(slot: Int) { val (i, n) = cycle[colour[slot]]; items[slot] = named(i, n, color = net.minecraft.ChatFormatting.GREEN) }
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            if (slot !in slots) return false
            colour[slot] = (colour[slot] + if (button == 1 && input == ContainerInput.PICKUP) 4 else 1) % 5
            set(slot)
            return true
        }
        override fun solved() = slots.all { colour[it] == colour[slots[0]] }
        /** Fewest clicks to one colour: each pane forward (left) or back (right), the best target. */
        fun minClicks() = (0 until 5).minOf { t -> slots.sumOf { val f = (t - colour[it] + 5) % 5; minOf(f, 5 - f) } }
    }

    /** "What starts with: 'X'?": 21 items (1.8 names); click every one starting with X (it glints). */
    class Starts : Term(Type.STARTS) {
        private val slots = (10..16) + (19..25) + (28..34)
        private val letter: Char
        override val title: String
        init {
            // The letter is a random pool item's initial: seen about as often as the pool has names
            // with it (186 windows: S 23 for 20 names, C 22/16, G 20/19, B 17/16 ... A 2/4, O 2/1).
            letter = STARTS_POOL.random().second[0]
            title = "What starts with: '$letter'?"
            val right = STARTS_POOL.filter { it.second[0] == letter }
            val wrong = STARTS_POOL.filter { it.second[0] != letter }
            // Items with the letter per window, as measured (n = 186); each slot's item is uniform.
            val n = weighted(listOf(2 to 1, 3 to 5, 4 to 14, 5 to 21, 6 to 32, 7 to 43, 8 to 27, 9 to 20, 10 to 16, 11 to 3, 12 to 4))
            val picks = (List(n) { right.random() } + List(slots.size - n) { wrong.random() }).shuffled()
            slots.forEachIndexed { i, s -> val (id, name) = picks[i]; items[s] = named(item(id), name, color = net.minecraft.ChatFormatting.GREEN) }
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

    /**
     * "Select all the X items!": 28 items of 5 colours; click every X one (it glints). Each colour
     * has 5 items and 3 of the 5 one more: all 175 windows split 6-6-6-5-5, the target taking a 6
     * in 110 (the 3 in 5 odds) and a 5 in 65.
     */
    class Select : Term(Type.SELECT) {
        private val slots = (10..16) + (19..25) + (28..34) + (37..43)
        private val target = COLOURS.random()
        override val title = "Select all the ${target.title} items!"
        init {
            val families = listOf(target) + (COLOURS - target).shuffled().take(4)
            val sixes = families.shuffled().take(3).toSet()
            val picks = families.flatMap { c -> List(if (c in sixes) 6 else 5) { c.items.random() } }.shuffled()
            slots.forEachIndexed { i, s -> val (id, name) = picks[i]; items[s] = named(item(id), name, color = net.minecraft.ChatFormatting.GREEN) }
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
     * the 4th lock finishes at once. Each new row's magenta column differs from the last (409 of
     * 409). A wrong lock (the lime off target, or another row's Lock In Slot, e.g. clicked ahead)
     * freezes the lime for two steps: lone wrong clicks were followed by a +30 step in 8 of 9, and a
     * row change after a click ahead did not move the lime (42 of 43), its next step +20.
     */
    class Melody : Term(Type.MELODY) {
        override val title = "Click the button on time!"
        private var row = 0
        private var target = Random.nextInt(1, 6)
        private var lime = 1
        private var dir = 1
        private var locked = false
        private var lastStep = -1
        /** Steps the lime still sits out after a wrong click. */
        private var frozen = 0
        init { draw() }
        private fun draw() {
            for (i in items.indices) items[i] = FILLER
            // The black panes the magenta moves along (rows 0 and 5, columns 1-5) keep their tooltip.
            for (c in 1..5) { items[c] = named(Items.STAINED_GLASS_PANE.black(), ""); items[45 + c] = named(Items.STAINED_GLASS_PANE.black(), "") }
            items[target] = named(Items.STAINED_GLASS_PANE.magenta(), "")
            items[45 + target] = named(Items.STAINED_GLASS_PANE.magenta(), "")
            for (r in 0 until 4) {
                for (c in 1..5) {
                    val slot = (r + 1) * 9 + c
                    items[slot] = when {
                        r != row -> named(Items.STAINED_GLASS_PANE.white(), "")
                        c == lime -> named(Items.STAINED_GLASS_PANE.lime(), "")
                        else -> named(Items.STAINED_GLASS_PANE.red(), "")
                    }
                }
                items[(r + 1) * 9 + 7] = if (r == row) button(named(Items.DYED_TERRACOTTA.lime(), "Lock In Slot", color = net.minecraft.ChatFormatting.GREEN)) else button(named(Items.DYED_TERRACOTTA.red(), "Row Not Active", color = net.minecraft.ChatFormatting.RED))
            }
        }
        /** Both terracotta buttons carry Hypixel's two gray, non-italic lore lines. */
        private fun button(s: ItemStack) = s.also {
            it.set(DataComponents.LORE, net.minecraft.world.item.component.ItemLore(listOf("Click this button when the Green", "lines up with the Purple!")
                .map { l -> Component.literal(l).withStyle { st -> st.withItalic(false).withColor(net.minecraft.ChatFormatting.GRAY) } as Component }))
        }
        override fun tick(t: Int) {
            // t = ticks since the items appeared: a step every 10.
            if (t <= 0) lastStep = -1
            if (t <= 0 || t % 10 != 0 || t == lastStep) return
            lastStep = t
            if (frozen > 0) frozen--
            else {
                if (lime + dir !in 1..5) dir = -dir
                lime += dir
            }
            if (locked) { locked = false; row++; target = ((1..5) - target).random() }
            draw()
        }
        override fun click(slot: Int, button: Int, input: ContainerInput): Boolean {
            val lock = slot % 9 == 7 && slot / 9 in 1..4
            if (!lock) return false
            if (slot != (row + 1) * 9 + 7 || locked || lime != target) {
                // Hypixel shows nothing for it, but the lime sits out its next two steps.
                if (!(locked && slot == (row + 1) * 9 + 7)) frozen = 2
                return false
            }
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
        private val opened = Fight.serverTick
        /** The server tick a click was answered on: its slot updates go out the tick after (TERM-04). */
        private var answeredAt = -1
        /** Ticks of the clicks that counted, for the 5-per-10-ticks limit (TERM-01). */
        private val counted = ArrayDeque<Int>()
        private var sync: net.minecraft.world.inventory.ContainerSynchronizer? = null
        private var refilled = false

        init {
            Terminals.open += this
            // The puzzle is in the window from the start: its items go out with the window (TERM-03).
            for (i in 0 until term.size) container.setItem(i, term.items[i].copy())
        }

        /**
         * Hypixel opens a window with one slot update per slot (stateId from 1), the player's
         * inventory slots included, never a full content packet; that follows ~5 ticks later (TERM-14).
         */
        override fun setSynchronizer(s: net.minecraft.world.inventory.ContainerSynchronizer) {
            sync = s
            super.setSynchronizer(object : net.minecraft.world.inventory.ContainerSynchronizer by s {
                override fun sendInitialData(menu: net.minecraft.world.inventory.AbstractContainerMenu, items: List<ItemStack>, carried: ItemStack, data: IntArray) {
                    // Empty player-inventory slots get none (the client's copy of them is empty already).
                    items.forEachIndexed { i, it -> if (i < term.size || !it.isEmpty) s.sendSlotChange(menu, i, it) }
                }
            })
        }

        /** Called every server tick while open. */
        fun tick() {
            if (player.containerMenu !== this) { open -= this; return }
            val age = Fight.serverTick - opened
            term.tick(age)
            if (Fight.serverTick > answeredAt) { copyIn(); broadcastChanges() }
            if (!refilled && age >= 5) {
                refilled = true
                sync?.sendInitialData(this, slots.map { it.item }, carried, IntArray(0))
                player.connection.send(net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket(ItemStack.EMPTY))
            }
        }

        /** Copies the puzzle into the container (sent as single slot changes). */
        private fun copyIn() {
            for (i in 0 until term.size) {
                if (!ItemStack.matches(container.getItem(i), term.items[i])) container.setItem(i, term.items[i].copy())
            }
        }

        override fun clicked(slot: Int, button: Int, input: ContainerInput, p: Player) {
            // A refused click gets no reply of its own (Hypixel sends no set_slot, no cursor). A vanilla client's guess (the
            // item on its cursor, the slot emptied) is still put back: handleContainerClick takes the guess as the remote
            // state and its broadcastChanges right after this sends the real slot and cursor. Odin's clicks guess nothing,
            // so they get nothing back, as on Hypixel.
            if (term.done || slot !in 0 until term.size) return
            Fight.afterPing("terminal click") {
                if (player.containerMenu !== this || term.done) return@afterPing
                val now = Fight.serverTick
                // Past 5 in the last 10 ticks: dropped, no answer of any kind.
                if (P3Sim.clickLimit) {
                    while (counted.isNotEmpty() && counted.first() <= now - 10) counted.removeFirst()
                    if (counted.size >= 5) return@afterPing
                    counted.addLast(now)
                }
                if (!term.click(slot, button, input)) return@afterPing
                // A counted click: a pling at you at once (TERM-07), the slot change next tick.
                Sim.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f, source = net.minecraft.sounds.SoundSource.BLOCKS)
                answeredAt = now
                if (term.solved()) {
                    term.done = true
                    // One tick: close the window, the chat line (titles, pling), close window 0 (TERM-15).
                    player.closeContainer()
                    station.complete(Sim.me)
                    player.connection.send(net.minecraft.network.protocol.game.ClientboundContainerClosePacket(0))
                }
            }
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

    /** Someone else finished [station]: your window on it closes (Hypixel has no terminal lock). */
    fun closeFor(station: Station) {
        val p = Sim.player ?: return
        if ((p.containerMenu as? TerminalMenu)?.station === station) p.closeContainer()
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
        lead=Lead|leather=Leather|leather_boots=Leather Boots|leather_chestplate=Leather Chestplate|leather_helmet=Leather Helmet|leather_leggings=Leather Leggings
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
