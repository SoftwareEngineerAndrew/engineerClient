# Blood rush instaclears: the analysis

An instaclear is a wither door opened into a room that goes white on the map at once, before its
mobs are out. Its mobs still spawn, but the room is already cleared, so its key never drops and the
run has to be restarted. These scripts take public ADR recordings and find what causes it and what a
client can see beforehand. Node 20+, with `xz` and `gzip` on the PATH. Run them from an empty data
folder outside the repo, because they read and write the current folder:

    node <tools>/fetch.mjs 1000          # runs/<id>: F7/M7 uploads that ended early (not cleared, < 2000 ticks)
    node <tools>/doors.mjs               # doors.tsv (one line per wither door) and the tables below

`read.mjs` reads the current upload format, which `../secret-spawns/decode.mjs` doesn't fully cover.
It handles uploads packed after the shared dictionary (`ADRd`), table-numbered values,
`flat` move columns and every grouped kind. It fetches `dict-1.bin` and `values-3.json.xz` from the
site once.

## What causes it

1. **Hypixel makes a room's mobs lazily, one neighbour at a time.** The mobs of room B are created
   server-side shortly after a player first stands in a **map tile** (32x32 cell) next to B. It is
   the tile next to B that counts, not just any tile of the room: in a 1x3 or L room, the tiles that
   don't touch B don't load it. Measured from the first moment a tracked player stood in the tile in
   front of a door, the room behind's mobs appear 0-11 ticks later (fairly flat over 0-9, so probably
   a check about every 10 ticks, plus lag). Measured from entering the room as a whole, the delay is
   spread over 0-40+ ticks.
   The proof that these are new mobs, not mobs coming into view: entity ids only go up, so each
   mob's id against the highest id the client had seen dates when the server made it.
2. **Opening a wither door into a room whose mobs don't exist yet clears it on the spot.** The door
   opening also makes the room load: its mobs appear 0-5 ticks after the door, but by then the room
   has already been checked with no mobs and marked cleared. The key drops when the room's last mob
   dies (`BloodRunDetail`), so a room that is already cleared never drops it.

So an instaclear is a race. The rusher reaches the door tile and opens the door before the server's
next check has spawned the room behind. Etherwarping or leaping onto the door does it, and so does
walking the length of a long room straight to its far door. Doors out of the fairy room are a common
case (8% of them instaclear, against 3% for the rest).

## How to recognise it (1708 wither doors into normal rooms, 70 instaclears, Nov 2026)

When the room behind's mobs were made, in ticks after the door opened:

| | instaclear | fine | rate |
|---|---|---|---|
| more than 100 before | 2 | 105 | 1.9% |
| 100 to 1 before | 0 | 1401 | 0.0% |
| 0 to 5 after | 52 | 14 | 78.8% |
| 6+ after | 6 | 17 | 26.1% |

How long a player had stood in the tile in front of the door when it was opened:

| ticks | instaclear | fine | rate |
|---|---|---|---|
| 0-4 | 17 | 12 | 58.6% |
| 5-9 | 29 | 79 | 26.9% |
| 10-14 | 4 | 145 | 2.7% |
| 15-19 | 1 | 204 | 0.5% |
| 20+ | 1 | 1012 | 0.1% |

What the client itself sees, for doors opened within 20 blocks of the recording player (as the
opener would be):

| before the door opened | instaclear | fine |
|---|---|---|
| a mob (its entity or its `✯ … ❤` name stand) seen inside the room behind | 0 | 772 |
| no mob seen there | 25 | 16 |

## The rule for the feature

Hold a right-click on a wither door (coal blocks in a door gap) **while both** of these are true:

- no dungeon mob is loaded inside the tiles of the room behind the door. That means a living mob
  entity, or an armor stand whose name has `❤`, inside the room's tiles. Odin's `DungeonScan` tiles
  give the room, and the side away from the player is the room behind;
- the player (or any teammate the client can see) has been standing in the near tile in front of
  the door, not the gap itself, for fewer than 15 ticks.

On the same doors this would have held 24 of the 25 instaclears and only 3 of 788 safe opens. In
practice it is a wait of under half a second, and only when the door is reached quickly. If one
signal is enough, mobs seen in the room behind is the stronger: 0 instaclears in 772 opens.

Points for the implementation:

- The door gap is at `-169 + 32t` and tile centres at `-185 + 32t` (`BrWaypoints2.worldDoors`).
  The tile in front of a door is the near room's tile whose centre is 16 blocks from the door.
- Don't hold doors into the fairy room (it has no mobs) or the blood door (the Watcher works
  differently).
- Mobs show up on the client within a tick of being made if they are in tracking range. From the
  door, the room behind's nearest mobs nearly always are, which is why the mob check works for the
  opener. The timer covers rooms whose mobs are all out of range.
