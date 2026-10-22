# Secrets (work item `secrets`)

What the secrets module does in a clear, what it leaves to others, and the numbers it uses that were not measured. The code is
`src/p3sim/clear/secrets/`; the pure parts (tables, timings, lines, the plan) are unit-tested headless, the world side
(`SecretsModule`, `SecretWorld`) is checked in game (steps at the end).

## What a player sees

- **Chests and levers stand from the start of the run.** `placeChests` queues every chest, the locked chests and the key rooms' socket
  blocks when the run starts; a chunk gets them as it loads (the fixes queue, applied every 20 ticks). One chest of about seven
  dungeons in ten is a trapped chest (the Mimic's, `TrappedChest`).
- **Chest.** The click counts the secret at once. A plain chest (46 percent) only raises its lid (a block event to everyone, once;
  the lid never closes) and plays one opening sound at its centre. A reward chest (54 percent: a blessing 40, a lump of x10 essence 13,
  an item line 1) plays a corner sound, the lid and centre sound, five harp notes five ticks apart, and gives its loot with the fifth
  note, 24 ticks after the click (`SecretChestFinale`). A repeat click during the roll is silent; afterwards, and on a plain chest,
  it answers "This chest has already been searched!". The loot is drawn from a per-chest generator, so a restore rolls the same chest.
- **Chest blessings** are level I, II or V (46 / 30 / 24 percent), never timed, with no "has obtained" line. Chest essence goes on every
  player's action bar. Superboom TNT and Revive Stones are not chest loot: a cleared room may drop one for whoever dealt its last
  required kill (`LootTables.roomClear`, 4 and 1 percent), with the "has obtained" line for everyone.
- **Locked chest.** "That chest is locked!" with an enderman teleport at volume 8 and pitch 0, once per player per 12 ticks.
- **Trapped chest.** Never opens: no sound, no lid, no loot; the secret counts, a zombie stands there a tick later and falls 4 to 11
  ticks after that, which scores the Mimic. A dungeon with a trapped chest scores its Mimic only this way.
- **Wither essence skull.** The first click (or Dungeonbreaker hit) counts the secret; the finder hears a pling, everyone near hears
  five harp notes, and 20 ticks after the click the skull goes, the line appears, everyone hears the orb ping (the finder twice) and the
  finder's bar shows "+1 Wither Essence". The head carries the real essence skin, the rooms pack's `essence_skin` texture (a pack without it gives a plain head that Odin still matches).
- **Redstone Key** (three rooms: Redstone Crypt, Redstone Key, Golden Oasis; `RedstoneKeys`). Picking the key skull up (click or
  Dungeonbreaker hit) counts nothing and gives no item: a line, the item-pickup sound, the skull gone a tick later. Using the key on
  the socket (a redstone block) counts the secret: a piston sound, "You hear something open..." in gray italics, a head on the socket,
  the room's redstone door gone, its hidden chests there, its locked chests open.
- **Bat.** No AI, no gravity, 100 health, fire resistance; any hit the sim hears about kills it (a melee hit, an arrow, an Implosion, a
  beam). The body falls and stays 20 ticks; the death sound is the bat's own voice (0.95 of the pitch). The run's bonus point is on the
  1st, 2nd, 3rd or 4th counted bat (70 / 24 / 5 / 1 percent), by anyone, with two orb pings.
- **Floor items** are Hypixel's stacks (Spirit Leap x5, Inflatable Jerry x2, skull skins, models, rarity styles) and are picked up
  inside the game's own box, with the server's pickup sound.
- **Appearing.** Floor items 32 blocks from the nearest party member (sideways), bats 14, skulls 16, on a 20-tick pass, in any room or
  gap.
- **Levers.** A used secret lever always says "This lever has already been used."; a sneaking player gets that line as well as the
  opening line; the wall ticks nine times, five ticks apart, in every room but Buttons, Old Trap and Bridges.
- **Teammates.** A bot's find runs the same ceremonies: its chest opens (lid, harp if a human is near, the lines at the reveal), its
  skull plays the sequence, its bat falls, its floor item flies to it, its key opens the socket.

## Numbers that were not measured

| constant | value | where |
|---|---|---|
| which room clears drop Superboom TNT / Revive Stone, and to whom | rate 4 / 1 percent, the last required killer | `LootTables.roomClear` |
| who may use a picked-up Redstone Key | the party | `pickUpKey` / `socketClick` |
| a click on a socket without the key | nothing happens | `socketClick` |
| the trapped chest's share of dungeons | 72 percent (151 of 211 loaded at the start) | `TrappedChest.DEFAULT_CHANCE` |
| the quiet time after a locked-chest refusal | 12 ticks (the smallest gap seen) | `LockedChest.QUIET_TICKS` |
| the gap between a teammate's several chests | 6 ticks | `SecretsModule.CHEST_STAGGER` |
| the head's skin on a used socket | the key skull's | `openSocket` |
| a repeat click on the Mimic's chest | silent | `chestClick` |

## Not done

- Lever walls that open in several batches (21 of 89 recorded pulls): needs the batches' delays and cells per room in the rooms pack.
- Item secrets that sit in a chest and open a 9x3 window (6 of 952 clicks): needs a flag per site in the rooms pack, and the moment the
  secret counts is not visible in the samples.
- The spawn eggs' `entity_data`, and a random rotation 0 to 15 for each essence skull (the real rotation is part of the room).
- The lid of an opened chest is sent again after a savepoint restore, not when a chunk is re-sent to a client that walked away.

## In-game checks

1. A chest you walk up to is already there; open it: the lid goes up, one sound (plain) or corner sound, lid, centre sound and five
   harp notes with the loot 24 ticks after the click (a reward chest). A second click during the roll is silent.
2. A wither essence skull: click it: the secrets figure rises at once, a pling, five notes, and 20 ticks later the skull goes with the
   line and two pings. Hit one with the Dungeonbreaker: the same, and no "mystical force" line.
3. In a key room: pick the key up (a line and a sound, no item, the figure does not move), carry it to the socket and click it: the
   figure rises, the door opens.
4. A bat hangs still; one arrow kills it; it falls; "A Bat has been slain" appears on the 1st or 2nd bat of most runs.
5. Teammates clear a room: you see their blessing and essence lines, hear the harp when you are near, and the room's chests are open.
