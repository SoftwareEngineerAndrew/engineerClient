# Better PF run recordings

Goal: record everything about a dungeon run (all 5 players) and replay/simulate it exactly in the
browser. Step 1 is the recorder (`src/main/kotlin/com/engineerclient/betterpf/`); the viewer is
next.

`clearsim/` is a copy of PrecisionSnipes' ClearSim viewer (WebGL, single HTML file, one simulated
room per run). It is the starting point for the Better PF viewer and is not wired up to this
format yet.

## Where runs go

`.minecraft/config/engineerclient/betterpf/runs/<yyyy-MM-dd_HH-mm-ss>_<floor>.jsonl.gz`
(`recording-*.jsonl.gz.part` while a run is still in progress).

## Format (version 1)

Gzipped JSON Lines. Every line is one object with `k` (kind). Lines for things that happen during
the run carry `t`: ticks since the world loaded (20 per second). Lines are in the order they
happened, so a reader can play the file start to finish.

| k | fields | meaning |
|---|---|---|
| `meta` | `format, mod, mc, self, startMs, confirmedAtTick, geometry` | first line; `startMs` is wall-clock time at tick 0 |
| `time` | `t, ms` | wall-clock sync every 20 ticks |
| `floor` | `t, floor` | floor once known (e.g. `F7`, `M7`) |
| `party` | `t, m: [[name, class], ...]` | party and classes, rewritten whenever they change |
| `p` | `t, d: [[name, x, y, z, yaw, pitch, heldItemId, uuidVersion], ...]` | every player, every tick |
| `spawn` | `t, id, type, name, x, y, z, yaw` | non-player entity appeared (`type` e.g. `minecraft:zombie`) |
| `e` | `t, d: [[id, x, y, z, yaw], ...]` | non-player entities that moved this tick |
| `name` | `t, id, name` | an entity's custom name changed (Hypixel nametags/health bars) |
| `gone` | `t, id` | entity despawned |
| `pal` | `i, s` | block-state palette entry, e.g. `minecraft:oak_stairs[facing=north,...]` |
| `chunk` | `t, cx, cz, b: [x, y, z, pal, x, y, z, pal, ...]` | a chunk's surface blocks (solid blocks touching air) |
| `block` | `t, x, y, z, s` | a block changed (doors, levers, secrets...); `s` is a palette index |
| `chat` | `t, m` | chat line, formatting stripped |
| `room` | `t, name` | you entered a room |
| `rooms` | `t, r: [[name, type, shape, rotation, checkmark, [[tx, tz], ...]], ...]` | Odin's classification of every room it knows (map grid tiles), rewritten when anything changes |
| `end` | `t, ms` | last line |

A `pal` line always comes before the first line that uses its index.

## Not captured yet

- Other players' clicks, abilities and held-item swaps beyond what `heldItemId` shows.
- Your own inputs (keys, clicks, look deltas finer than per tick).
- Entity health beyond what's in their nametag, and entity equipment.
- Anything before you load into the instance (party finder, queueing).
