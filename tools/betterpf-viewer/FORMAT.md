# Better PF run recordings

Goal: record everything about a dungeon run (all 5 players) and replay/simulate it exactly in the
browser. The recorder is `src/main/kotlin/com/engineerclient/betterpf/`; the viewer lives on the
website (`/betterpf`), which also keeps the room library.

`clearsim/` is a copy of PrecisionSnipes' ClearSim viewer (WebGL, single HTML file, one simulated
room per run). It is the starting point for the Better PF viewer and is not wired up to this
format yet.

## Where runs go

`.minecraft/config/engineerclient/betterpf/runs/<yyyy-MM-dd_HH-mm-ss>_<floor>.jsonl.gz`
(`recording-*.jsonl.gz.part` while a run is still in progress).

## Format (version 2)

Gzipped JSON Lines. Every line is one object with `k` (kind). Lines for things that happen during
the run carry `t`: ticks since the world loaded (20 per second). Lines are in the order they
happened, so a reader can play the file start to finish.

| k | fields | meaning |
|---|---|---|
| `meta` | `format, mod, mc, self, startMs, confirmedAtTick, geometry` | first line; `startMs` is wall-clock time at tick 0 |
| `time` | `t, ms` | wall-clock sync every 20 ticks |
| `floor` | `t, floor` | floor once known (e.g. `F7`, `M7`) |
| `party` | `t, m: [[name, class], ...]` | party and classes, rewritten whenever they change |
| `p` | `t, d: [[name, x, y, z, yaw, pitch, heldItemId, uuidVersion, vanillaItem, crouching], ...]` | players whose entry changed since their last one (a player keeps their last entry until the next) (`heldItemId` is the Skyblock id when there is one; `vanillaItem` is always the game item, e.g. `minecraft:iron_sword`; `crouching` is 1 while sneaking, else 0 (newer runs) - the item it is drawn as when it has a vanilla `item_model`: Hypixel builds many items on another base item, like paper drawn as TNT) |
| `held` | `t, name, tex` | the skin of the player head a player is holding (the leap item, for one), when it changes; `""` when they stop holding one |
| `hotbar` | `t, items: [9 vanilla ids], sel, tex: {slot: skin}` | your own hotbar (vanilla ids as in `p`), the selected slot 0-8, and the skins of player heads in it by slot; written when any of it changes |
| `pgone` | `t, name` | player no longer in the world |
| `skin` | `t, name, tex` | a player's skin, once: their profile's `textures` property (base64 JSON with the skin URL and model) |
| `eq` | `t, name` or `t, id`, `eq: [mainHand, head, chest, legs, feet], headTex?` | a player's or mob's held item and armour changed (vanilla item ids, with `#rrggbb` appended for dyed items like leather armour); `headTex` is the skin of a worn player head |
| `sw` | `t, d: [name, ...]` | players who started an arm swing this tick (left click, or a right click that hit something - opening a terminal swings) |
| `mp` | `t, d: [[name, x, z, yaw], ...]` | teammates the game isn't rendering: their position from the dungeon map (clear only, about 1.6 blocks per map pixel), written when it changes |
| `cam` | `t, d: [[partialTick, yaw, pitch], ...]` | your own look direction at every rendered frame (at most 60 a second) since the last tick (frames that didn't move are left out, except the one just before it moves again). A frame's time on the `p` timeline is `t - 1 + partialTick`: it was drawn between your `p` entries at `t - 1` and `t` |
| `stand` | `t, id, f, pose: [18 numbers]` | an armor stand's look, on spawn and when it changes: `f` flags (1 small, 2 invisible, 4 arms shown, 8 no base plate, 16 marker); `pose` is head, body, left arm, right arm, left leg, right leg, each x, y, z in degrees |
| `skull` | `t, x, y, z, tex` | a player head placed as a block: its skin (`textures` property, base64), once per position (again if it changes); heads within 12 chunks of you, checked every second |
| `bev` | `t, x, y, z, b` | a chest, trapped chest or ender chest lid event: `b` players have it open now (0 = it closes) |
| `gui` / `guiclose` | `t, title` / `t` | a container screen you opened / closed (terminal GUIs have fixed titles) |
| `spawn` | `t, id, type, name, c?, x, y, z, yaw, baby?, headYaw?, block?` | non-player entity appeared (`type` e.g. `minecraft:zombie`; falling blocks also have `block`, the block state; `baby: 1` for baby mobs) |
| `e` | `t, d: [[id, x, y, z, yaw, headYaw?], ...]` | non-player entities that moved (or turned their head) this tick; `headYaw` (also on `spawn`) is where a mob's head faces, for living entities only |
| `name` | `t, id, name, c?` | an entity's custom name changed (Hypixel nametags/health bars); `c` is the name with its § colour codes when it has any (also on `spawn`) |
| `gone` | `t, id` | entity despawned |
| `pal` | `i, s` | block-state palette entry, e.g. `minecraft:oak_stairs[facing=north,...]` |
| `lib` | `t, key, x0, y0, z0, w, h, d, pal, rle` | every block of a room the library didn't have yet; `key` is `Name\|ROTATION`, or `Boss\|FLOOR\|cx,cz` for one 16x16 chunk column of the boss arena (a volume, see below; the column the arena starts in is only captured from the room grid's last gap on, so it can be narrower) |
| `vol` | `t, x0, y0, z0, w, h, d, pal, rle` | (no longer written) the 1-block gaps between rooms; the viewer ignores it and leaves gaps as air |
| `door` | `t, x0, y0, z0, w, h, d, pal, rle` | a box where a door can be (middle of a tile edge; now 7 along the wall, 2 blocks into each room, y 67-76 - older runs: 3x3, y 69-72), air included: the door, the opening, or the wall filling it |
| `block` | `t, x, y, z, s` | a block changed (doors, levers, secrets...); `s` is a palette index |
| `chat` | `t, m, c?` | chat line: `m` with formatting stripped; `c` the same line with `§` codes (`§0`-`§f` colours, `§#rrggbb` for other colours, `§k§l§m§n§o`, `§r` reset between styled parts), only when it has formatting |
| `room` | `t, name` | you entered a room |
| `rooms` | `t, r: [[name, type, shape, rotation, checkmark, [[tx, tz], ...], secretsFound, secretsTotal, libraryKey?], ...]` | Odin's classification of every room it knows (map grid tiles), rewritten when anything changes; `libraryKey` once the room's rotation is really known (1x1 rooms: Odin found its blue clay) and, for 1x1 rooms with several variants, which one (`Name\|ROTATION\|core`) |
| `end` | `t, ms` | last line |

A `pal` line always comes before the first line that uses its index.

### Geometry

Rooms are identical in every run, so the website keeps one copy of each (the room library, keyed
`Name|ROTATION`). At the start of a run the mod fetches the library's keys
(`GET /betterpf/api/rooms`) and only captures rooms that aren't in it; those `lib` lines are added
to the library when the run is uploaded (first copy wins). A run therefore only needs its `rooms`
lines (where each room is) and `block` changes; the 1-block gaps between rooms are left as air.
The viewer fetches rooms with `GET /betterpf/api/rooms/data?keys=<JSON array>` and places each at the grid position of its
lowest tile: `x = -200 + 32 * minTx`, `z = -200 + 32 * minTz`. Boss chunk columns are captured as their
chunks load (air-only chunks and sections skipped) and sit at their own `x0, y0, z0`.

A volume is a `w`×`h`×`d` box starting at `(x0, y0, z0)`. `pal` is its own palette of block states
and `rle` is run-length encoded palette indices `[count, index, count, index, ...]` in y, z, x order
(x fastest). Index 0 is always `""`: not part of this volume (outside an L-shaped room's footprint),
so it never overwrites anything.

Format 1 had no `lib`/`vol`/`pgone`, wrote every player every tick and sent `chunk` lines
(`t, cx, cz, b: [x, y, z, pal, ...]`, surface blocks only). The viewer still reads it.

## Not captured yet

- Other players' clicks, abilities and held-item swaps beyond what `heldItemId` shows.
- Your own inputs (keys, clicks).
- Entity health beyond what's in their nametag.
- Anything before you load into the instance (party finder, queueing).
