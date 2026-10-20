# Better Recorder (Devgineer Client)

Code: `src/dev/betterrecorder/`. One gzipped JSON Lines file per dungeon in `<game dir>/devgineerclient-recordings/better/`,
named `<date>_<time>_<HYP|ADS>_<floor>.brec.jsonl.gz` (`.part` while it is written; the gzip stream is sync-flushed every second, so a
`.part` reads fine up to its last second). On by default; `Where` also records ADS runs, in the same format, so a sim run can be compared
line for line with a Hypixel one.

What it is for: the Dungeon Recorder keeps every packet's bytes (gigabytes a day, read back with a decoder). Better Recorder keeps what the
clear is made of, decoded, placed in rooms and small (a few MB a run), so it can stay on in every run, and every party member can run it.

## Every line

`{"k": kind, "t": client ticks since the start, "n": server ticks since the start (Hypixel's ping packets, one a server tick), ...}`.
Positions are world blocks to 1/1000. Where a line has `"r"`, it is the room it happened in: `["Name", rx, ry, rz]`, the block relative to
Odin's clay corner turned to north (Odin's `getRelativeCoords`, the frame of the ADS rooms pack), or `["Name"]` while the room's rotation is
not known yet. Text keeps its colours as § codes (§#rrggbb for other colours). An item is
`[skyblock id or item id, count, name, dye rgb, head texture value, glint, item id when a skyblock id was given]`.

| kind | when | members |
|---|---|---|
| `meta` | first line | format, where (HYP/ADS), date, me, uuid, floor, server brand, mod version, probes on |
| `clk`, `stime` | every 20 ticks / every 20 server time packets | wall ms, level game time |
| `me` | any tick you changed | `s`: [x, y, z, yaw, pitch, flags (1 ground, 2 sneak, 4 sprint, 8 water, 16 lava, 32 flying), held slot, hp, absorption] |
| `enter` | you go into another room (Odin's current room) | from, to, room (Odin's whole record), r |
| `inv` | inventory slots that changed | `s`: [[slot, item or null], ...] |
| `hp`, `xp`, `fx`, `fxEnd`, `cd`, `tp` | packets: health and food, the XP bar, effects (any entity), item cooldowns, the server moving you | as named |
| `use`, `useOn`, `attack`, `interact`, `swing`, `act`, `slot`, `click`, `input`, `cmd`, `say`, `guiClose` | what you send: right click (yaw, pitch, held), right click on a block (block, face, the exact point, held, r), left click on an entity (target [id, type, name, distance]), swing, dig / drop / swap (`act`, with the block when digging), hotbar slot, window clicks, movement keys, the first word of each command (nothing typed is kept), that a chat line was sent | |
| `spawn` | an entity appears | e (id), type, uuid, uv (uuid version: 2 = Hypixel's fake players), p, yaw, pitch, s (state, below), eq (6 slots: main, off, head, chest, legs, feet), item (item entities), pname/tex/sig (players: the signed skin), dist (from you), r |
| `mv` | every tick: each entity that moved or turned | `m`: [[id, x, y, z, body yaw, head yaw, pitch], ...] (the position the server sent, not the drawn one) |
| `ed` | an entity's state changed | e, `s`: [custom name, name visible 0/1, invisible 0/1, hp, max hp, flags (1 glowing, 2 fire, 4 sneaking, 8 sprinting, 16 baby, 32 no gravity, 64 marker stand, 128 small stand, 256 dying), pose, vehicle id or -1] |
| `eq` | an entity's gear changed | e, eq |
| `gone` | an entity is no longer in the world | e, why (removal reason), rmN (server tick of the remove packet), hp, dead, name |
| `eev`, `anim`, `hurt`, `dmg`, `take`, `ride` | entity events (3 death, 2 hurt...), animations (0 swing, 1 hurt, 4 crit, 5 magic crit), hurt animation, damage events (type, cause and direct entity ids), item pickups (item id, by, count), passengers | |
| `blk`, `blks` | block changes (one, or a section's batch `b`: [[x, y, z, state], ...]) | p, s, r (of the first block) |
| `bev`, `lev`, `dig`, `boom` | block events (chest lids, notes, pistons: block, a, v), level events (2001 block break and the like), break progress by entity, explosions | |
| `snd`, `snde` | sounds at a point (id, category, p, volume, pitch, r) and on an entity | |
| `ptc` | particles (once per type and 0.1-block spot a server tick) | id, p, count, speed, spread, options |
| `chat`, `bar`, `title`, `subtitle` | system chat; the action bar when it changes; titles | m (private chats are written as `"private":true` only) |
| `tab`, `tabHF` | tab rows that changed every 5 ticks (`rows`: [[profile name, text]], `gone`), header and footer | |
| `side` | the sidebar when it changes (every 5 ticks) | title, lines |
| `boss` | boss bars | op (add, remove, v, name), id, name, v, color |
| `map`, `mapIcons` | the dungeon map: a changed patch (box [x, y, w, h], px = the colour bytes, base64), the icons ([type, x, y, rot, name]) | |
| `gui`, `guiItems`, `guiSlot` | a window opened (id, type, title), its items, one slot changed | |
| `skin` | each signed skin the first time it is seen (fake players included: mobs, NPCs, the blood camp) | name, uuid, tex, sig |
| `room` | an Odin room record changed (every 10 ticks) | id, room: name, type, shape, rot, rotKnown, clay, tiles, check, found, max, crypts, trapped, walked, highest |
| `doors`, `puzzles`, `dungeon` | Odin's doors ([x, z, type, rotation, from tile, to tile]), puzzle states, the run's numbers (secrets, crypts, deaths, score, mimic, prince) | |
| `snap` | a room the first time it is seen with its rotation known (puzzle and trap rooms again when done) | why, room, box [x0, y0, z0, x1, y1, z1], top, pal (block states), order "y,z,x", rle ("i" or "ixN", comma separated, palette indices) |
| `probe`, `mark`, `wanted`, `hit`, `tagsee` | probe on/off, `/brec mark`, a wanted sighting, the melee probe's hits (you, with every living thing within 6 blocks: [id, distance, line of sight]), the tags probe's name stands every 2 ticks ([id, visible, distance, line of sight]) | |
| `gap`, `end` | lines dropped for want of queue room; the last line (why, lines) | |

## Commands

`/brec` status · `/brec mark [note]` · `/brec probes` · `/brec probe <id>` · `/brec stop [id]` · `/brec wanted` · `/brec folder`.

## Reading it

Node: `zlib.gunzipSync` and split on new lines, or stream with `readline` over `zlib.createGunzip()`. A room snapshot decodes to a
`(x1-x0+1) * (z1-z0+1) * (y1-y0+1)` array in y, z, x order. Turn world positions into a room's frame with the room's `rot` and `clay`
(`"r"` already does it for events).
