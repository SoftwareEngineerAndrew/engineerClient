# F7 terminals: what the real ones look like on the wire

Spec for p3sim's server-side terminals, measured from Better PF recordings
(`config/engineerclient/betterpf/runs/*.jsonl.gz`, 216 files, 2026-09-23 .. 10-01). Files
whose floor is `unknown` were left out: they hold non-Hypixel test worlds with a different
"starts with" item pool. Hypixel's own lobby **Terminal Simulator** windows also use the
title "Click in order!", but they are not dungeon terminals. They show up as order sessions
with no `slotclick`, and clicked panes lose their name. They were left out too.

Sample sizes: 59-172 windows per type with full slot data, and 41-67 completions each.
Server ticks (`st`) are Odin's ping count. Scripts are in
`/home/cam/.claude/jobs/fffcf85d/tmp/*.js` (node).

Notation: `id "name" xN *` = vanilla item, plain hover name, count N (left out when 1), `*` =
enchantment glint. `.` = filler.

## Common to all six

- **Filler**: `minecraft:black_stained_glass_pane` x1. Its hover name is empty after the
  `§` codes are stripped (the recorder does not trim). It is most likely a custom name of `""`
  or of codes only, e.g. `§r`. Give it a custom name that renders as nothing. Every slot of the
  container that is not playable is filler.
- **Open sequence**: the window opens with the container **empty** (the recorder's dump at
  `gui` has every container slot `""`). The items arrive in a later packet, usually 1 server
  tick later (0-2). That holds for 41 of 41 colours windows and 48 of 59 melody windows (order:
  112 at +0, 51 at +1).
- **Send items as individual `ClientboundContainerSetSlotPacket`s, not only `SetContent`.** Odin
  hooks `AbstractContainerMenu.setItem` (mixin `AbstractContainerMenuMixin.postSetSlot`).
  `TerminalHandler.updateSlot` re-solves on every set-slot whose index is in
  `[0, windowSize-9)` and whose item is not `BLACK_STAINED_GLASS_PANE`. `SetContent`
  (`initializeContents`) does not go through `setItem`, so a terminal filled only that way
  never gets solved. Safe order: open the screen, then (next tick) one SetSlot per container
  slot.
- **Detection by Odin**: a regex on the screen title, plus a fixed window size: order 36,
  panes 45, colours 45, starts 45, select 54, melody 54. Titles have no colour codes.
- **Clicks**: the window is **never reopened**. One `gui` per terminal, and the server
  updates single slots. Odin's GUI sends `button 2, CLONE` (middle click) for everything except
  colours, where it uses `button 1, PICKUP` (right) to go backwards. The server accepts left,
  right and middle on the same slot. Return the clicked slot's real item every time, because
  vanilla clients predict pickup and show the slot empty for a moment (seen with right clicks in
  colours).
- **Click → slot update**: measured from the client's send to the update's arrival, `st` 3
  (2-4, n≈1700). **Click → close** on the finishing click: `st` 2 (1-3). Closing is a tick
  quicker than a slot update, which fits Hypixel handling the click at once and sending the
  container change on its next tick (Spigot-style `detectAndSendChanges`), while it sends the
  close and the chat line straight away. To match: apply the click in the click handler, send
  the changed slot at the end of the tick, and on completion close in the same tick as the click.
- **No server-side first-click lockout.** A click 3 ticks after open was accepted and updated
  normally. First clicks cluster at +7..+9 because of Odin's client-side `firstClickProt`
  (`firstClickProtTicks`), not the server.
- **Wrong clicks**: no real wrong click (clicking the wrong item) was captured in any type. The
  only "out of order" clicks seen were pipelined clicks, where the client clicked the next
  number before the previous update arrived, and they were all accepted. Assume wrong clicks are
  ignored, with no reset and no close, unless measured otherwise.
- **Finishing**: the window closes (`ClientboundContainerClose`) **in the same server tick** as
  the chat line. Close tick − chat tick = 0 in 63/63 order, 59/59 panes, 41/41 colours (one
  at 1), 67/67 starts, 64/64 select and 50/50 melody completions. The chat line, broadcast to
  the whole party:
  - plain: `<name> activated a terminal! (<n>/<N>)`
  - formatted: `§b<name>§r§a activated a terminal! (§r§c<n>§r§a/<N>)`. The name is in its rank
    colour (`§b` MVP+, `§6` MVP++). When the rank colour is `§a` (VIP), the codes merge into
    `§a<name> activated a terminal! (§r§c3§r§a/7)`.
  - `N` is the section's total (terminals + levers + device): 7, or 8 in S2. Levers say
    `activated a lever!` and devices say `completed a device!`, in the same format.
  - Other messages, all `§c`: `This Terminal has already been completed!`,
    `This Terminal doesn't seem to be responsive at the moment.` (a terminal of a section not
    started yet), `This lever has already been used.`. There is also Goldor's
    `§4[BOSS] Goldor§r§c: Stop touching those terminals!`.
- Party-chat lines like "Melody Terminal start!" and "Melody 25%" are players' mods, not
  Hypixel.

## 1. Click in order! (Odin NUMBERS)

- `minecraft:generic_9x4` (36 slots). Playable: **10-16, 19-25** (2 rows × 7). 22 filler.
- 14 × `red_stained_glass_pane`, **count = n and name = "n"** for n = 1..14 (plain name
  `"7"`; the colour code is not recorded), shuffled over the 14 slots.
- Correct click (lowest remaining) → that slot becomes `lime_stained_glass_pane`, keeping
  **the same count n and name "n"**, no glint. The simulator's version differs here: it blanks
  the name.
- 14 clicks; clicking 14 closes the window with the chat line.

```
 0: . | . | . | . | . | . | . | . | .
 9: . | red_pane x8 "8" | red_pane x3 "3" | red_pane "1" | red_pane x10 "10" | red_pane x2 "2" | red_pane x13 "13" | red_pane x5 "5" | .
18: . | red_pane x7 "7" | red_pane x4 "4" | red_pane x12 "12" | red_pane x9 "9" | red_pane x11 "11" | red_pane x14 "14" | red_pane x6 "6" | .
27: . | . | . | . | . | . | . | . | .
click 25 (the 1) -> st+3: [25, lime_stained_glass_pane, 1, "1"] ... click 21 (14) -> close + chat same tick
```

## 2. Correct all the panes! (Odin PANES)

- `minecraft:generic_9x5`. Playable: **11-15, 20-24, 29-33** (3 rows × 5). 30 filler.
- Each cell is `red_stained_glass_pane "Off"` or `lime_stained_glass_pane "On"`, count 1.
- Panes that start On: 0-8, median 3 (n = 54: 0:2, 1:7, 2:12, 3:14, 4:12, 5:4, 6-8: 1 each).
  Uniformly random cells.
- Clicking Off → On. Clicking an On pane was never seen; assume it turns Off (it is a toggle).
- Done when all 15 are On. Completed runs took 7-15 clicks (median 12).

```
 9: . | . | red_pane "Off" | red_pane "Off" | red_pane "Off" | red_pane "Off" | lime_pane "On" | . | .
18: . | . | red_pane "Off" | red_pane "Off" | red_pane "Off" | red_pane "Off" | red_pane "Off" | . | .
27: . | . | lime_pane "On" | red_pane "Off" | lime_pane "On" | red_pane "Off" | red_pane "Off" | . | .
```

## 3. Change all to same color! (Odin RUBIX)

- `minecraft:generic_9x5`. Playable: **12-14, 21-23, 30-32** (3×3). 36 filler.
- Panes: `red/orange/yellow/green/blue_stained_glass_pane`, named `"Red"`, `"Orange"`,
  `"Yellow"`, `"Green"`, `"Blue"`, count 1. There is no glint.
- Cycle, as measured (n≈280 clicks):
  - **left and middle click step forward** Red → Orange → Yellow → Green → Blue → Red;
  - **right click steps backward** Blue → Green → Yellow → Orange → Red → Blue.
- Start: each cell uniform over the 5 colours (O 76, G 75, B 68, Y 59, R 55). 2-5 different
  colours appear.
- Done when all 9 match, whatever the colour. Completed runs took 5-10 clicks (median 8).

```
 9: . | . | . | orange_pane "Orange" | blue_pane "Blue" | green_pane "Green" | . | . | .
18: . | . | . | blue_pane "Blue" | yellow_pane "Yellow" | orange_pane "Orange" | . | . | .
27: . | . | . | blue_pane "Blue" | blue_pane "Blue" | green_pane "Green" | . | . | .
middle on Blue -> Red; right on Yellow -> Orange; right on Orange -> Red
```

## 4. What starts with: 'X'? (Odin STARTS_WITH)

- Title exactly `What starts with: 'E'?` (straight single quotes, capital letter). Odin's regex
  is `^What starts with: '(\w)'\?$`. Letters seen: I G R S C E P M L B F W D A T N.
- `minecraft:generic_9x5`. Playable: **10-16, 19-25, 28-34** (3 × 7 = 21, all filled). 24 filler.
- Items count 1, with **legacy 1.8 English names** (the item's vanilla name gets replaced). Items
  can repeat. Correct = name starts with the letter. Correct per window: 2-12, median 7 (n = 65).
- Correct click → the same item gets **enchantment glint** (`glint 1`; give it
  `enchantment_glint_override: true`), with name and count unchanged. Done when every correct item
  glints.
- Items that glint naturally start with glint: Enchanted Book, Bottle o' Enchanting and the like.
  24 of 1365 items, so keep vanilla foil.
- Observed pool (vanilla id → name). Use this list; it is what Hypixel draws from:

```
acacia_door Acacia Door | apple Apple | armor_stand Armor Stand | arrow Arrow | baked_potato Baked Potato
beef Raw Beef | birch_door Birch Door | blaze_powder Blaze Powder | blaze_rod Blaze Rod | bone Bone
book Book | bow Bow | bowl Bowl | bread Bread | brewing_stand Brewing Stand | brick Brick | bucket Bucket
cake Cake | carrot Carrot | carrot_on_a_stick Carrot on a Stick | cauldron Cauldron
chainmail_boots/chestplate/helmet/leggings Chainmail Boots/Chestplate/Helmet/Leggings
chest_minecart Storage Minecart | chicken Raw Chicken | clay_ball Clay | clock Watch | coal Coal
cod Raw Fish | command_block_minecart Minecart with Command Block | comparator Redstone Comparator
compass Compass | cooked_beef Steak | cooked_chicken Roast Chicken | cooked_cod Cooked Fish
cooked_mutton Cooked Mutton | cooked_porkchop Cooked Porkchop | cooked_rabbit Cooked Rabbit | cookie Cookie
dark_oak_door Dark Oak Door | diamond Diamond | diamond_axe/boots/chestplate/helmet/hoe/leggings/pickaxe/sword Diamond ...
diamond_horse_armor Diamond Horse Armor | egg Egg | emerald Emerald | enchanted_book Enchanted Book
ender_eye Eye of Ender | ender_pearl Ender Pearl | experience_bottle Bottle o' Enchanting | feather Feather
fermented_spider_eye Fermented Spider Eye | filled_map Map | fire_charge Fire Charge
firework_rocket Firework Rocket | firework_star Firework Star | fishing_rod Fishing Rod | flint Flint
flint_and_steel Flint and Steel | flower_pot Flower Pot | furnace_minecart Powered Minecart | ghast_tear Ghast Tear
glass_bottle Glass Bottle | glistering_melon_slice Glistering Melon | glowstone_dust Glowstone Dust
gold_ingot Gold Ingot | gold_nugget Gold Nugget | golden_apple Golden Apple | golden_carrot Golden Carrot
golden_axe/boots/chestplate/helmet/hoe/leggings/pickaxe/shovel/sword Gold Axe/Boots/.../Sword
golden_horse_armor Gold Horse Armor | gunpowder Gunpowder | hopper_minecart Minecart with Hopper | ink_sac Ink Sac
iron_axe/boots/chestplate/helmet/hoe/leggings/pickaxe/shovel/sword Iron ... | iron_door Iron Door
iron_horse_armor Iron Horse Armor | iron_ingot Iron Ingot | item_frame Item Frame | jungle_door Jungle Door
lava_bucket Lava Bucket | lead Lead | leather Leather | leather_boots/chestplate/helmet/leggings Leather ...
magma_cream Magma Cream | map Empty Map | melon_seeds Melon Seeds | melon_slice Melon | milk_bucket Milk Bucket
minecart Minecart | mushroom_stew Mushroom Stew | mutton Raw Mutton | name_tag Name Tag | nether_brick Nether Brick
nether_star Nether Star | nether_wart Nether Wart | oak_boat Boat | oak_door Wooden Door | oak_sign Oak Sign
painting Painting | paper Paper | poisonous_potato Poisonous Potato | polar_bear_spawn_egg Spawn Egg
porkchop Raw Porkchop | potato Potato | potion Water Bottle | prismarine_crystals Prismarine Crystals
prismarine_shard Prismarine Shard | pumpkin_pie Pumpkin Pie | pumpkin_seeds Pumpkin Seeds | quartz Nether Quartz
rabbit Raw Rabbit | rabbit_foot Rabbit Foot | rabbit_hide Rabbit Hide | rabbit_stew Rabbit Stew | red_bed Bed
redstone Redstone | repeater Redstone Repeater | rotten_flesh Rotten Flesh | saddle Saddle | shears Shears
skeleton_skull Skull Item | slime_ball Slime Ball | snowball Snowball | spider_eye Spider Eye
spruce_door Spruce Door | stick Stick | stone_axe/hoe/pickaxe/shovel/sword Stone ... | string String | sugar Sugar
sugar_cane Sugar Cane | tnt_minecart Minecart with TNT | water_bucket Water Bucket | wheat Wheat
wheat_seeds Seeds | wooden_axe/hoe/pickaxe/shovel/sword Wooden ... | writable_book Book and Quill
written_book Written Book
```
(Gold tools and armour are named "Gold X", but `golden_apple`/`golden_carrot` keep "Golden".
Spelling: `ink_sac` is "Ink Sac" here, but "Ink Sack" in select-all.)

```
"What starts with: 'E'?"
 9: . | chicken "Raw Chicken" | egg "Egg" | egg "Egg" | egg "Egg" | emerald "Emerald" | spruce_door "Spruce Door" | chainmail_boots "Chainmail Boots" | .
18: . | bread "Bread" | golden_pickaxe "Gold Pickaxe" | glistering_melon_slice "Glistering Melon" | minecart "Minecart" | chainmail_boots "Chainmail Boots" | brick "Brick" | minecart "Minecart" | .
27: . | chainmail_leggings "Chainmail Leggings" | ender_eye "Eye of Ender" | enchanted_book "Enchanted Book"* | fishing_rod "Fishing Rod" | map "Empty Map" | enchanted_book "Enchanted Book"* | iron_helmet "Iron Helmet" | .
"What starts with: 'I'?" click 21 iron_axe -> st+3 [21, iron_axe, 1, "Iron Axe", glint 1]
```

## 5. Select all the X items! (Odin SELECT)

- Title `Select all the <COLOUR> items!`, colour in capitals with a space:
  `WHITE ORANGE MAGENTA LIGHT BLUE YELLOW LIME PINK GRAY SILVER CYAN PURPLE BLUE BROWN GREEN
  RED BLACK` (all 16 seen; light gray is **SILVER**). Odin's regex is
  `^Select all the ([\w ]+) items!$`.
- `minecraft:generic_9x6`. Playable: **10-16, 19-25, 28-34, 37-43** (4 × 7 = 28, all filled).
  26 filler.
- Each colour has 4 items: `<c>_stained_glass "<C> Stained Glass"`, `<c>_terracotta
  "<C> Stained Clay"`, `<c>_wool "<C> Wool"` and the dye. Light gray is named "Silver ...".
  The dyes are legacy:
  - `white`: `bone_meal "Bone Meal"`, `white_wool "Wool"`, `white_terracotta "White Stained Clay"`
  - `black`: `ink_sac "Ink Sack"`
  - `blue`: `lapis_lazuli "Lapis Lazuli"`
  - `brown`: `cocoa_beans "Cocoa Bean"`
  - `red`: `red_dye "Rose Red"`
  - `green`: `green_dye "Cactus Green"`
  - `yellow`: `yellow_dye "Dandelion Yellow"`
  - `light_gray`: `light_gray_dye "Light Gray Dye"`, `light_gray_wool "Silver Wool"`
  - the others are `<C> Dye` (Cyan Dye, Lime Dye, Pink Dye, ...).
- Count 1. Each window holds **exactly 5 colour families** (59/59), with **5-6 of the target
  colour** (29 × 5, 27 × 6; 2-3 once each). The other 22-23 items come from 4 other colours.
  Kinds are about even (glass 441, clay 410, wool 405, dye 302, special dyes 94). Items repeat.
- Correct click → same item **with glint**. Done when every target item glints. Confirmed matches:
  glass, clay, wool and the dye of each colour. LIGHT BLUE does **not** take plain blue, and GRAY
  does not take SILVER.

```
"Select all the CYAN items!"
 9: . | lime_stained_glass | cyan_terracotta "Cyan Stained Clay" | light_blue_dye | gray_terracotta | gray_terracotta | gray_wool | yellow_dye "Dandelion Yellow" | .
18: . | cyan_dye "Cyan Dye" | lime_dye | gray_stained_glass | yellow_terracotta | lime_dye | cyan_dye "Cyan Dye" | gray_terracotta | .
27: . | light_blue_wool | lime_dye | lime_dye | gray_dye | yellow_dye | cyan_dye "Cyan Dye" | cyan_stained_glass "Cyan Stained Glass" | .
36: . | lime_dye | yellow_dye | light_blue_terracotta | light_blue_stained_glass | yellow_terracotta | yellow_stained_glass | light_blue_wool | .
(5 cyan; colour families cyan, lime, gray, yellow, light blue)
```

## 6. Click the button on time! (Odin MELODY)

- `minecraft:generic_9x6`. 28 filler (columns 0, 6 and 8, the rest of rows 0 and 5).
- Layout (every name `""` except the terracotta):
  - **Target column** `c` ∈ 1..5: `magenta_stained_glass_pane` at slots `c` and `45 + c`
    (top and bottom rows). A new random column is drawn for every row (seen 1:12, 2:8, 3:11,
    4:14, 5:14 for the first row).
  - **Rows 1-4**, columns 1-5: the active row is `red_stained_glass_pane` × 4 plus one moving
    `lime_stained_glass_pane`. The inactive rows (done or not yet) are all
    `white_stained_glass_pane`.
  - **Column 7** (slots 16, 25, 34, 43): the active row has `lime_terracotta "Lock In Slot"`,
    the others `red_terracotta "Row Not Active"`.
- **Movement**: the lime pane starts at column 1 (57/59) and steps one column every **10
  server ticks** (measured gaps: 10 in 283 cases, 9 in 100, 11 in 68; that is tick-count
  jitter). It bounces 1→5→1. Each step is a 2-slot update (old cell red, new cell lime). The first
  step comes 10 ticks after the items appear.
- **Click**: click the active row's Lock In Slot (Odin sends middle click) while the lime is in
  the target column. The click is judged against the server's position when it arrives; with
  latency, clicks shown one step early (st 8-9 after a move) often landed.
  - **The row advances on the next 10-tick step, not at once** (click → row change 1-11 ticks,
    spread evenly). That step is a 16-slot update: the old row turns white with a red
    terracotta, the new row turns red with a lime and a lime terracotta, and the magenta moves
    to the new column in both places. The lime keeps its bounce position and direction in the new
    row.
  - Sometimes the first step after a row change came 20 ticks later (7 of 97).
  - **The 4th correct click finishes at once**: click → close + chat in `st` 1-2, without
    waiting for a step.
- Wrong clicks (on Lock In Slot with the lime off target) mostly did nothing: the next step came
  on time (≈60 cases). A few were followed by a 29-50-tick stall, all in laggy sessions. Not
  confirmed as a penalty, so treat wrong clicks as ignored.
- Solve time is 87-234 ticks (4-7 clicks).

```
 0: . | . | . | . | magenta_pane | . | . | . | .
 9: . | lime_pane | red_pane | red_pane | red_pane | red_pane | . | lime_terracotta "Lock In Slot" | .
18: . | white_pane | white_pane | white_pane | white_pane | white_pane | . | red_terracotta "Row Not Active" | .
27: . | white_pane x5 ...                                     | . | red_terracotta "Row Not Active" | .
36: . | white_pane x5 ...                                     | . | red_terracotta "Row Not Active" | .
45: . | . | . | . | magenta_pane | . | . | . | .
trace (st after open): 1 items; 10 lime->2; 20 ->3; 30 ->4; 39 click 16; 41 ->5; 41 click 25 (early);
51 row 2 (target 1); 71 ->4 ... 100 ->1, click 25; 110 row 3 lime 2 (target 3); 121 ->3, click 34;
130 row 4 lime 4 (target 2); 140 ->5; 151 ->4; 161 ->3; 170 ->2, click 43; 171 close + "activated a terminal! (6/7)"
```

## Distribution

- **Each terminal's type is random every run.** The same stations show every type. The two
  stations seen most:
  - (90,111,92): select 12, panes 11, melody 7, colours 7, starts 7, order 5
  - (90,121,101): order 13, melody 10, starts 9, colours 8, select 7, panes 5
- First opens, matched to a stand, n = 156: order 29, starts 30, panes 27, select 26, melody
  25, colours 19. Completions by type: starts 67, select 64, order 63, panes 59, melody 50,
  colours 41.
- Take the six as **equally likely** (≈1/6). Colours may be a little rarer, but the sample is too
  small to say.
- A terminal reopened after being closed unsolved got a new type in 3 of 14 cases (11 the same).
  That suggests it is re-rolled when reopened, or on a timer. Unconfirmed.

## Terminal stands, devices, levers

All are `minecraft:armor_stand`. There is no block to click: the player interacts with the
stand entity. `sw` (arm swing) comes 1 tick before `gui` in 91 of 170 opens, and 0-5 ticks
before almost all of them.

- **Terminal**: 2 invisible stands (`stand.f = 2`: invisible only, normal size, base plate,
  **not marker**, so they have a hitbox to click), both yaw 0:
  - `§cInactive Terminal` at the station position, e.g. (90.5, 121, 101.5);
  - `§e§lCLICK HERE` 0.375 lower, at (90.5, 120.625, 101.5).
  - On completion: the top stand becomes `§aTerminal Active` and CLICK HERE's name becomes
    `""`. Both names go `""` when the phase ends.
  - The name changes reach the client tens of ticks after the chat line (the stands refresh on
    a 20-tick grid), so the stands are not a timer.
- **Open distance**: eye to the stand's hitbox (0.5 × 1.975) at window open: p50 2.23,
  p90 3.23 blocks (n = 170; a few outliers ≥ 9 come from matching the wrong stand).
  `terminal-roles.md` measured "within 4.5 blocks of the station → window in 6 ticks". Use
  vanilla's entity interaction range (3 blocks to the hitbox) plus a little slack.
- **Device**: 2 stands (flags not checked): top `§cInactive` (y 119) over `§cDevice` (y 118.625), e.g.
  (110.5, 119, 91.5). Done: the top becomes `§aDevice` and the bottom `§aActive`.
- **Lever**: 1 stand, flags 18 (invisible + marker), `§cNot Activated` → `§aActivated`, 1-3
  ticks after the pull, e.g. (94.5, 124.688, 113.5). A second pull says
  `§cThis lever has already been used.`.

## Not measured (decide in p3sim)

- What a real wrong click does in order, starts and select (assumed: ignored).
- Clicking an On pane in panes (assumed: toggles back to Off).
- Two players on one terminal. There is a "Someone has … activated this lever!" line for
  levers. No terminal "in use" line was seen.
