# Dungeon Recorder

A module (Engineer Client category, **off by default**) that records everything that happens in a
dungeon, losslessly, as context for building mods with an LLM. Better PF records a replay and the
Boss Recorder the boss fights; this keeps the lot:

- **every packet both ways**, field by field under their real names (26.1 is unobfuscated), with
  the exact bytes of every frame in a sidecar, and what became of each one (applied, cancelled,
  rejected, threw);
- **the world as the client held it**: every chunk decoded block for block, every block change
  with where it came from, the dungeon map as assembled, every entity every tick it moved;
- **you**: every key, click, scroll and look turn, the actions they started and what they came to,
  your full state every tick, the camera in every frame, your inventory, screens and HUD as drawn;
- **what the mods made of it**: Odin's dungeon state, events and private solver state, and Engineer
  Client's own splits, rotation and trackers.

So "what does the server send when a terminal opens", "what does Hypixel put in a blood mob's
metadata", "what packet tells me the gate blew" or "what did Odin think the room was" are answered by
looking, not guessing. Nothing is thrown away to keep the files small: 1-2 GB an hour is expected
with everything on, and only disk safety ever stops a recording (and says so in the file).

## Settings

| setting | default | |
|---|---|---|
| Where | Dungeons | Dungeons only, Dungeons + Hub (party finder, queueing), or Everywhere |
| Server Packets / Your Packets | on | `in` / `out` lines (the raw sidecar keeps every frame either way) |
| Entity Movement | on | other entities' move, head-turn, motion, sync and teleport packets as `in` lines |
| Particles And Sounds | on | the particle and sound packets as `in` lines |
| Played Sounds | on | `snd` / `sndstop`: every sound the client played or tried to (its own and mods' included) |
| Spawned Particles | on | `ptc`: every particle requested and every particle that spawned, client-made ones included |
| Chunk Data | on | chunk packets decoded block for block, and the chunks in keyframes; off, a chunk packet is `{x, z, off: true}` |
| Client State | on | `me` every tick, `effects`, `inv`, `cd` |
| Per-Frame Camera | on | `frames`: the camera in every rendered frame (the `tick` line is written either way) |
| Typed Chat | **off** | what you type: off, a command keeps only its name (`{"command", "args": "<redacted>"}`), a chat message its length and signing data (`{"redacted": true, "len", ...}`), keys typed into text fields are `{"redacted": true}` and their raw frames are withheld |
| Hide Private Chats | **on** | private, guild, officer, co-op and friend lines are written as `{"hidden": "private"}` everywhere they appear (packets, chat box, Odin's chat) and their raw bytes withheld |
| Input | on | the input lines (key, btn, scroll, look, act, attempt, aim, use, ...) |
| Cursor Moves | on | `cur`: every cursor move (the biggest part of the input lines) |
| Cookie Payloads | off | server cookies in full; off, their length and SHA-256 only, raw bytes withheld |
| Min Free Disk GB | 10 | stops the recording (with a `stopped` line) when the disk has less free space |
| Max Recordings Folder GB | 0 | 0 = no limit; stops the recording when the folder grows past it |
| Delete Oldest When Full | off | at the folder limit, deletes the oldest finished recordings instead (never the current one) |
| Entity Ticks | on | `ent` rows every tick and `emove` |
| Rendered Entities | on | `drawn`: which entities were rendered, with name tags and outlines |
| Compact Entity Rows | off | `ent` lines go to a separate `partNNNN.ent.xz` instead of the main stream |
| Raw Packets | on | the raw sidecar: every frame's exact bytes, both ways |
| Odin Internals | on | Odin's private solver, boss-tracker and helper state by reflection (version-fragile, read-only) |
| Frame Thumbnails | **off** | small JPEGs of the screen as you saw it (`thumb`, files in `thumbs/`); they show private chat and cannot be redacted; 100-400 MB an hour |
| Thumbnail FPS | 1 | 0.5-4 a second, plus one after each screen opens and each title |
| Bookmark | unbound | a key that writes a `mark` line and a keyframe; also `/ecrec mark [note]` |
| Open Folder | | opens the recordings folder |

Every setting is in the session's `meta` and the manifest; a change mid-recording is a `settings`
line with what changed.

## Files

`<game dir>/engineerclient-recordings/<yyyy-MM-dd_HH-mm-ss>_<label>_<6 hex>/`, one directory per
recording. It is named `.pending-<id>` until Odin confirms this is a place to record (the label,
usually the floor, is fixed then); a pending recording that never confirms (a minute, or Odin knows
it is somewhere else) is deleted. Writing starts at the first line, not at confirmation.

| file | |
|---|---|
| `partNNNN.jsonl.gz` | the lines: JSON Lines, as independent gzip members of about 1 s or 1 MiB each. A new part every hour or 2 GB uncompressed; each part starts with its own `meta` line and a keyframe, so it stands alone |
| `partNNNN.idx.jsonl` | one line per member: `{off, len, raw, lines, seq: [a, b], t: [a, b], n: [a, b], ms: [a, b], kf, rawOff?, rawLen?, rawRaw?, ent?: [off, len, lines], types: {type: count}}`. `types` counts packet lines by packet type (`minecraft:...`) and every other line by kind. An index line is written only after its member is whole on disk |
| `partNNNN.raw.gz` | the raw sidecar (Raw Packets): gzip members aligned with the JSON members (`rawOff`/`rawLen` in the index), holding records `[u32 len][varint seq][u8 dir][u8 phase][u8 flags][bytes]` (big-endian length; dir 0 in, 1 out; phase 0 handshaking, 1 status, 2 login, 3 configuration, 4 play; flags bit 0 = withheld, the bytes left out and `len` their original length). A frame is `[packet id varint][payload]`, after decryption and decompression |
| `partNNNN.ent.xz` | Compact Entity Rows only: the `ent` lines, one xz block per member |
| `manifest.json` | rewritten every 10 s and at the end: `format: "recorder-2"`, id, label, the session meta (versions, self, uuid, server, settings, filters), start/end, startNs/startT/startN, `parts: [{name, seq, t, n, ms, bytes, rawBytes, lines}]`, `openPart`, `counts` per type, `errors` per type, `gaps`, `marks: [{seq, ms, t, note}]`, lines, gzBytes, rawBytes, complete, crashed, closed, stoppedReason, ioError, lastGoodSeq, lostAfterSeq |
| `schema.json` | every class the field writer reflected, with its field names in written order, and every enum's constants by ordinal |
| `entities.jsonl` | one line per entity as it entered the client level (the `espawn` body, `of` the line's seq) |
| `events.jsonl` | the moments a reader looks for first: `mark`, `world`, `end`, `death`, `revive`, `leap`, `ec.split`, `settings` (each with `of`, the line's seq, and `kind`) |
| `thumbs/partNNNN/<seq>.jpg` | Frame Thumbnails only |

Files being written end in `.part`. **Crash recovery**: at the next start, every `.part` left behind
is cut back to the end of the last member its index names, renamed, and the manifest marked
`crashed: true` with `lostAfterSeq`; stale pending directories (over an hour old) are deleted. A
crash loses at most the member being built (about a second).

**Disk safety**: every 10 s the free space and folder size are checked against the settings; past
them the recording stops whole with `{"k": "stopped", "why": "disk_free_below" | "folder_cap",
"freeBytes", "limitBytes"}` and a chat warning. A disk error that persists stops it with `why:
"io_error"`. Files are never truncated or replaced while writing.

## Every line

```
{"k": kind, "seq": S, "t": T, "n": N, "ms": MS, "ns": NS, ...}
```

- `seq`: a global sequence number taken on the thread where the thing happened (network, game,
  render). Lines reach the file in whatever order the writer gets them; **seq is the true order**.
- `t`: client ticks (END_LEVEL_TICK, absolute). `n`: server ticks seen on the game connection (ping
  packets, as Odin counts them, absolute). `meta` gives both at the session's start.
- `ms`: wall clock. `ns`: monotonic nanoseconds since the session started (negative for config lines
  replayed from before it).
- Packet lines add `ph`, the protocol phase (`login`, `configuration`, `play`).
- No member name of any body repeats `k`, `seq`, `t`, `n`, `ms` or `ns`.

Mutable things (items, entities, Odin and EC objects, live chunks) are turned into finished strings
on the thread that owns them before they are queued; only immutable inputs are formatted later on
the writer thread. One writer thread (`ec-recorder-writer`) serializes and gzips, one IO thread
(`ec-recorder-io`) writes, so the game and network threads only push onto a queue. The queue holds
up to 512 MB; anything that does not fit is counted and written as a `gap` line. Nothing is dropped
without a line saying so.

## When a recording starts and ends

A session opens at the world's login packet, on the network thread, before the packet itself is
written, so the world's very first packets are in it, and the login and configuration that came
before (registries, tags, resource packs) are replayed into it as `config` lines with their original
seq and time. A respawn (Hypixel's server switches) does not split a recording: one follows you from
the lobby into the run (a `world` line marks each dimension). It ends when the connection starts over
(a reconfiguration or a new login), when the client leaves the world, or when the module is turned
off. Turned on mid-world, it starts there (the world's first packets are gone; a keyframe stands in).

## Keyframes

A `keyframe` line `{kf, reason}` (reasons `confirm`, `part`, `enable`, `respawn`, `mark`, `gap`...;
at most one every 10 s except confirm/part/enable) is followed by every unit's full snapshot, each
line tagged `"kf": id`: the loaded chunks (`kfchunk`, `kfbe`, `kfchunks`, 64 chunks a tick, so they
trail by a few ticks), every map (`mapfull`), `env`, every entity (`kfent`, 100 a tick), your
inventory, effects and cooldowns, the options (`opts`), the screen and container, the HUD state, tab
list, scoreboards (`kfboard`, `board`) and boss bars, the aim, Odin's whole state and room grid.
"Only when it changed" lines start over at every keyframe and every part. A reader can start at any
keyframe and needs nothing before it. Every 60 s the chunks changed since their last copy are written
again (`kfchunk` with `dirty: true`).

## Line kinds

### Recording

| k | fields | |
|---|---|---|
| `meta` | `format: "recorder-2", rec, part, firstSeq, prevPart, tz, startMs, startNs, startT, startN, via, mod, odin, fabricApi, mc, self, uuid, selfId, server?, settings?, filters?` | first line of every part. `filters` says which packet groups are left out of the lines |
| `meta2` | `server, settings, filters` | the part of the meta read on the game thread, when the session opened on the network thread |
| `settings` | `changed: {name: value}` | a setting changed mid-recording |
| `keyframe` | `kf, reason` | a full snapshot follows (above) |
| `mark` | `note` | a bookmark (keybind or `/ecrec mark [note]`) |
| `gap` | `range: [seqA, seqB], lines, msRange, why, types` | lines that were not recorded: `queue_full` (the writer fell behind), `config_cache` (the between-worlds cache overflowed 64 MB), `stopped`, `after_close` |
| `stopped` | `why, freeBytes?, limitBytes?, error?` | the recording stopped early for disk safety |
| `rec` | `q, qBytes, lagMs, lines, rawBytes, gzBytes, serNs, gzNs, ioNs, ioQueueBytes, freeDisk, linesPerS` | the writer's health, every 10 s |
| `error` | `p, err` | a line that failed to build (`p` its type) or a capture hook that threw (`p` like `player:me`, `hud:tab`, `keyframe:world`); the line's seq is kept |
| `end` | `why` | the recording ended: `reconfigure`, `reconnect`, `disconnect`, `disabled`, `replaced` |

### The connection and its packets

| k | fields | |
|---|---|---|
| `in` | `p, ph, raw?, b?, bi?, e?, self?, f, abs?, deg?, why?, absPending?, etype?, absErr?` | a packet from the server: `p` its id (`minecraft:set_entity_data`), `f` its fields, `raw` the seq range of its frames in the sidecar, `e` the entities it is about, `self` when you are one. Entity moves carry `abs` (where the entity really is, from a mirror of the client's position codec), `deg` (angles in degrees), `etype` (set_entity_data) at the line level |
| `out` | `p, ph, cancelled, f` | a packet the client tried to send (Odin's send event, last listener): `cancelled` if some mod stopped it |
| `wire_out` | `p, ph, len, rawSeq?` | a packet that actually left (the encoder) |
| `config` | `dir, ph, p, raw?, len?, f` | a login/configuration packet from before the world's login (or a `disconnect`/`conn_error` from between worlds), replayed with its original seq |
| `bundle` | `b, count, ph, raw?` | a bundle of `count` packets (how the server sends an entity with its data); each is its own `in` line with `b` (this line's seq) and `bi` (its index); the bundle's frames are on this line |
| `world` | `via (login\|respawn), selfId, dimension, dimType, minY, height, logicalHeight, sky, ceiling, seaLevel, gameType, previousGameType, debug, flat, chunkRadius?, simDistance?, hardcore?, enforcesSecureChat?, dataToKeep?` | the dimension the server put you in, from the login or respawn packet itself |
| `disconnect` | `by (client\|server\|error), reason, report, bugReport` | the game connection closed |
| `conn_error` | `error, stack` | the exception that broke the connection |
| `commands` | `tree` | the client's command tree after a commands packet was applied (vanilla's JSON form) |
| `applied` | `on, seqs, dur?` | server packets taking effect. `on: "game"`: one line per drain of the game thread's packet queue, `seqs` the `in` lines applied (runs of 3+ as `[first, last]`), `dur` nanoseconds in their handlers; its `t`/`ns` are when they applied, not when they arrived. A bundle applies as one. `on: "netty"`: handled on the network thread |
| `fate` | `seqs, p, fate, at?, err?` | a packet that did not simply apply: `cancelled_odin` (on Odin's bus), `cancelled_read0` (another mod cancelled channelRead0), `rejected` (`at` read0 or game), `error` (its handler threw, `err` the stack trace) or `expired` (nothing seen of it for 30 s) |

Nothing is left out: keep-alives, pongs, chunk-batch markers and bundle delimiters are lines like any
other (SKIP is empty). The raw sidecar holds every frame of the game connection both ways; frames
are withheld (length only) for the login encryption handshake (`crypto`), cookies without Cookie
Payloads, hidden private chats and what you typed without Typed Chat.

### The world

A `level_chunk_with_light` line's `f` is the chunk decoded: `{x, z, minSy, s, be, hm, light}`. `s`
holds every section bottom up, `{i, y, n, fl, pal, rle?, bio, bioRle?}`: `y` the section's y
(`minSy + i`), `n`/`fl` its non-air and fluid block counts, `pal` its block states
(`minecraft:oak_stairs[facing=east,...]`) and `rle` `[count, paletteIndex, ...]` over its 4096 blocks
in index order y, z, x (x fastest; left out when the palette has one entry); `bio`/`bioRle` the same
for its 64 biome cells (4x4x4). `be` is `[[x, y, z, type, snbt]]` (skull textures included), `hm`
`{type: [256 absolute heights]}` in order x + 16z, `light` as for light_update. `chunks_biomes` lines
decode as `{chunks: [{x, z, minSy, s: [{i, y, bio, bioRle?}]}]}`.

| k | fields | |
|---|---|---|
| `cchunk` | `ev, x, z` | the client loaded (`load`) or dropped (`unload`) a chunk; `ignored`: a chunk packet that never loaded within 2 ticks (outside the view range) |
| `blk` | `p: [x, y, z], old, new, src` | every block change the client applied: `src` `server` (block/section update), `ack` (the server settling your predicted blocks) or `local` (your prediction, client-side effects) |
| `mapfull` | `kf?, id, scale, locked, sha1, b64, dec: [[type, x, y, rot, name]], odinIgnored` | a map's whole 128x128 picture (packed colours, base*4+shade) after a map packet changed it: the dungeon map as assembled |
| `env` | `kf?, clock, gameTime, rain, thunder, border: [x, z, size], gameMode, minY, height` | polled each second, written when changed |
| `kfchunk` | `kf, dirty?, x, z, minSy, s` | a keyframe's copy of one loaded chunk, sections as above (no counts) |
| `kfbe` | `kf, dirty?, x, z, d: [[x, y, z, type, state, snbt]]` | that chunk's block entities |
| `kfchunks` | `kf, scan: [cx, cz, r], count, chunks: [[x, z]]` | every chunk the client held at the keyframe (found by scanning radius `r` around your chunk) |

### Entities

| k | fields | |
|---|---|---|
| `espawn` | `id, uuid, type, pos, base, rot: [yRot, xRot, yHeadRot, yBodyRot], vel, bb, pose, name, display, data, eq, vehicle, pass, hp?, maxHp?, hurt?: [hurtTime, deathTime], age, extra?, src (packet\|client), at (load\|tick)` | an entity entering the client level, in full; again at the tick's end (`at: "tick"`) once the rest of its spawn bundle applied |
| `ent` | `d: [[id, x, y, z, xo, yo, zo, yRot, xRot, yHeadRot, yBodyRot, vx, vy, vz, onGround, interp, baseX, baseY, baseZ, hp, (ix, iy, iz)]]` | every rendered entity whose numbers changed this tick, bit-exact; `ix, iy, iz` the interpolation target when `interp` is 1; non-living entities have null yBodyRot and hp. The rendered position of a frame is lerp(xo, x, partialTick) with the partial tick from `frames` |
| `emove` | `d: [[id, x, y, z, yRot, xRot, interp]], thread?` | each move the client applied (moveOrInterpolateTo), whatever asked for it; nulls for what the move left alone |
| `egone` | `id, reason, src (level\|unload), found?, etype?` | an entity removed, with its reason |
| `edata` | `id, etype, d` | an entity's data after the game applied it (`[[index, serializerId, value]]`) |
| `eeq` | `id, slot, item` | an entity's equipment after the game applied it |
| `kfent` | `kf, i, of, d: [{entity as espawn}], skipped?` | a keyframe's entities, 100 a tick (`i` of `of` lines) |
| `drawn` | `ids, tags?: [[id, component\|null]], outline?: [[id, rgb, appearsGlowing]]` | which entities were rendered this tick; tags and outlines only when they change |

### You, the camera and the clock

| k | fields | |
|---|---|---|
| `me` | `pos, old, rot: [yRot, xRot, yHeadRot, yBodyRot], vel, ground, hcol, vcol, vcolBelow, fall, sprint, crouch, shift, pose, in: [fwd, back, left, right, jump, shift, sprint], mv, use, swing, swingArm, swingTime, atkAnim, hurt, hp, abs, maxHp, food, sat, xp, air, armor, water, lava, vehicle, abil, cam, atk, slot, keys?, mine?` | you, every tick, exact (never deduplicated). `keys` (every key mapping's held state) only when it changed; `mine` `{pos, stage, progress}` while you break a block |
| `effects` | `kf?, d: [[id, amplifier, duration, ambient, visible, showIcon]]` | your effects when they change |
| `inv` | `kf?, full?, sel, s: [[slot, item]]` | inventory slots that changed (all of them in keyframes) |
| `cd` | `kf?, s: [[slot, group, percentLeft]]` | item cooldowns when they change |
| `frames` | `d: [[ns, partialTick, yaw, pitch, x, y, z, fov, detached, fluid, cameraEntity, frameTimeNs]]` | the camera of every frame this tick (Per-Frame Camera); passes from other cameras are skipped |
| `tick` | `gt?, clock?, rate?, frozen?, dur, frames, fps, rt, focus, active, gpu, mem: [used, total, max], gc: [count, ms]` | one per client tick: game time, tick rate, how long the tick took (ns), frames drawn |
| `opts` | `kf?, o: {option: value}, keys: {mapping: key}, win, packs, mods` | every option (all OptionInstance getters), key binding, the window, resource packs and loaded mods, at the start and in keyframes |
| `opt` | `name, v` | one option, key binding (`key:<mapping>`), `win` or `packs` changed (polled each second) |
| `net` | `lat: [[name, ms]], self` | every player's latency from the tab list, when it changed |

### Input and what it came to

All on the game thread. Discrete events are written as they happen, so their `seq` sits exactly
between the packets around them; cursor and look samples are batched per tick with their own `ns`,
and a batch is written before the next discrete event.

| k | fields | |
|---|---|---|
| `key` | `key, code, scan, mods, act, screen, maps` | every key event (`act` 0 release, 1 press, 2 repeat; `maps` the key mappings it matches). In a chat, sign or book screen or a focused text field: only `{redacted, screen}` unless Typed Chat is on |
| `char` | `cp, s, screen` | a typed character; only with Typed Chat on |
| `btn` | `b, act, mods, gx, gy, screen, maps` | a mouse button |
| `scroll` | `dx, dy, screen` | the wheel, before anything cancels it |
| `cur` | `d: [[ns, x, y, grabbed]]` | cursor moves this tick (Cursor Moves) |
| `look` | `d: [[ns, dYaw, dPitch]]` | Entity.turn deltas for you (after sensitivity and smoothing; 0.15 degrees per unit) |
| `act` | `what, arg?, aim, done, r?` | the game started `startAttack`, `startUseItem`, `continueAttack` or `pickBlockOrEntity`; `done` false: a mod cancelled it before its end (before any packet). `seq` is taken at the start |
| `bind` | `key, cancelled` | Odin's InputEvent after every module (cancelled = a module ate it) |
| `attempt` | `what, cancelled\|consumed, ...` | `slot_click`, `block_interact`, `entity_interact`, `screen_click`, `screen_release`, `screen_key` on Odin's bus; `hotbar_scroll`; Fabric's `screen.after*` key and mouse events |
| `aim` | `type, pos?, face?, hit, inside?, border?, id?, etype?, pick?, kf?` | what the crosshair is on, when it changes and at keyframes |
| `use` / `interact` / `attack` / `useon` | `hand, result` / `id, etype, hand, hit, result` / `id, etype` / `hand, pos, face, hit, inside, result` | what an item use, entity interaction, attack or block use came to on the client |
| `typed` | `kind, text` or `kind, redacted, len, root?`, `cancelled?` | chat and commands as sent (`kind` chat, command, odin), including ones cancelled before a packet |
| `break` | `by, self, pos, stage` | a block-breaking stage drawn, yours or anyone's |
| `broke` | `pos, state` | a block you broke, on the client |

### Screens, chat and HUD as drawn

| k | fields | |
|---|---|---|
| `screen` | `open, class, reinit?, title, w, h, gw, gh, scale, menu?, cid?, state?, left?, top?, iw?, ih?, slots?: [[index, x, y, containerSlot, containerClass, active]], kf?` | a screen opened (with its layout, so a reader can redraw it) or closed (`open: false`) |
| `slots` | `kf?, cid, state, s: [[i, item]]` | container slots whose item changed (the client's prediction included) |
| `carried` / `hover` / `drag` | `item` / `slot` / `on, slots` | the item on the cursor, the slot under the mouse, a drag in progress |
| `tooltip` | `slot, x, y, lines` | the tooltip exactly as drawn after every mod |
| `gmouse` | `d: [[ns, x, y]]` | the mouse inside a screen every frame (flushed per tick) |
| `chatui` | `stage (offered\|shown), src, tag, sig?, added?, c` or `stage, hidden: "private"` | what reached the chat box (`offered`) and what it displayed (`shown`) |
| `chatdel` / `chatclear` | `sig` / `history` | a chat line deleted (by signature, base64) / the chat cleared |
| `chat.dropped` | `src (chat\|game), overlay, c\|hidden` | a message a mod cancelled before the chat box |
| `hud` | `what, c?, anim?, prevRepeats?, times?` | titles, subtitles, the action bar (identical repeats counted in `prevRepeats` of the next different one), timers, clears; `what: "state"` at keyframes |
| `toast` | `class, token?, w, h` | a toast shown |
| `tab` | `open, count, header?, footer?, rows: [[i, uuid, name, display, latency, gameMode, team, order]]` | the tab list: only changed rows, `count` the row count (rows at index >= count are gone) |
| `board` | `kf?, slot, obj, title, lines: [[owner, score, line, display?]]` | each scoreboard display slot when it changes (the sidebar as vanilla draws it); `obj: null` when a slot lost its objective |
| `kfboard` | `kf, objectives: [{name, criteria, title, render, scores}], teams: [[name, display, prefix, suffix, color, players]]` | the whole scoreboard at keyframes |
| `bars` | `kf?, d: [[uuid, name, progress, color, overlay, darken, music, fog, drawn]]` | boss bars when they change; `drawn` false when a mod hid it |
| `thumb` | `file, w, h, why, fw, fh, q, skipped?` | a frame thumbnail (Frame Thumbnails): `file` relative to the recording, `why` `fps`, `screen`, `title`, `subtitle` |

### Sounds and particles

| k | fields | |
|---|---|---|
| `snd` | `id, file, path, src, pos, vol, pitch, att, rel, loop, delay, range?, sub?, cls, res` | a sound the engine was asked to play: `res` `STARTED`, `STARTED_SILENTLY` or `NOT_STARTED` |
| `sndstop` | `what (inst\|match\|all), id, src, cls?, active?` | the engine stopping sounds |
| `ptc` | `opts, req?, spawned?, emit?` | one per tick with particles: `req` `[type, x, y, z, dx, dy, dz, force, always, o, made]` (asked of the level; `made` how many got through), `spawned` `[class, x, y, z, xd, yd, zd, lifetime]`, `emit` `[entityId, entityType, o, lifetime]`; `o` indexes `opts` |

### Odin's dungeon state and events

Polled every tick and written when changed (plus at keyframes, with `kf`). Each carries `thr`
(`main`, or `net` for the snapshot taken right after Odin parsed a tab update on the network thread),
and `cause` where several sources write it.

| k | fields | |
|---|---|---|
| `odin.dungeon` | `secretsFound, secretsPercent, knownSecrets, crypts, openedRooms, completedRooms, deaths, percentCleared, elapsed, mimic, prince, bat, doorOpener, bloodDone, puzzleCount, ...` | Odin's score inputs |
| `team` | `players: [{name, clazz, clazzLvl, isDead, deathsOdin, deaths, eid, loaded, pos, yaw, ...}], leap, customOrder` | teammates and where the map puts them |
| `rooms` / `room` | `grid, tiles, pathHints, rooms` / `id, name, tiles, walkedInto, known1x1, cores, ...` or `id, gone` | the room grid (keyframes, every 5 min) / one room when it changes |
| `doors` | `doors, viewable` | doors and the viewable ones with colours |
| `where` | `bp, tile, tileRoom, odinRoom, rel, rotKnown` | where you are on Odin's grid: `tileRoom`/`odinRoom` `[roomId, name]`, `rel` your position in the room's own (rotated) coordinates |
| `puzzles` | `[{name, display, status, player, timeToBeat}]` | puzzle states |
| `term` | `open, timeOpened, ticksOpened, solution, clickedSlots, lastClickTime` | the open terminal as Odin sees it |
| `sb` | `hp, maxHp, mana, maxMana, overflow, speed, defense, ehp, vitality, maxVitality, vitalityShown` | the action-bar stats |
| `loc` | `area, areaName, lobby, skyblock, party, leader, inParty, isLeader` | location and party |
| `modules` | `modules: [{name, enabled, settings}], mods` | every Odin and EC module and its settings (secret-named strings `<redacted>`), every 20 ticks when changed |
| `death` / `revive` | `name, src (tab\|chat), deaths, text?, as?` | a teammate died or came back |
| `leap` | `via (teleported\|party), from, to, order, self, target, thr` | a leap seen in chat, with your position and the target's |
| `odin.chat` | `text, c, cancelled, overlay?, thr` or `hidden: "private", cancelled, thr` | chat and action bar as Odin's bus delivered them, with whether a module cancelled them |
| `ev` | `e, thr, cancelled?, ...` | Odin's own events (room entered, secret picked up, terminal opened/clicked, score changed, ...); `cancelled` is the final verdict of every mod on the bus |

### Odin's internal state (Odin Internals)

Read on the game thread at each tick's end by reflection and written when a group changes; counters
that tick on their own are written but do not count as a change (`odin.clocks` anchors them at each
world load and every minute). A field missing in another Odin version writes one `odin.priv
{mod, unavailable}` line and the rest carries on.

| k | | |
|---|---|---|
| `odin.solver` | `mod, ...` | each puzzle solver's working state (Quiz, Water, Blaze, Boulder, IceFill, TPMaze, Weirdos, Beams, puzzle timers) |
| `odin.boss` | `dragonsOn, ...` | the dragon tracker and boss state |
| `odin.priv` | `mod, ...` | TickTimers, TerminalTimes, KingRelics, LividSolver, SpiritBear, TerracottaTimer |
| `odin.bloodcamp` | | the blood camp predictor (watcher, first spawns, move time) |
| `label` | `by: "odin", label (watcher\|livid\|dragon:<Name>), eid, ...` | the entity Odin picked out (eid null when it went) |
| `odin.p3` | `mod, ...` | ArrowAlign, ArrowsDevice, InactiveWaypoints, SimonSays, BreakerDisplay |
| `odin.splits` / `odin.special` / `odin.endstats` | | Odin's splits, the special-column guess, the end-of-run stats |
| `odin.sync` / `odin.sync.state` | `ev, connected, room, found, msg` / | the map-sync websocket's messages both ways and its state |
| `odin.paul` / `odin.roomdb` | | Paul's perk, the room library |
| `room.wp` | `room, core, wps: [{pos, title, type, clicked, secret}]`, `mod?` | Odin's waypoints per scanned room, and SecretClicked's list |
| `odin.clocks` | | Odin's tick counters against `n` |

### Engineer Client's own modules (`ec.*`)

Every line from an EC moment carries EC's stamp `"at": {"ms", "tick"}` (DungeonSplits' clock).

| k | |
|---|---|
| `ec.split` / `ec.sub` / `ec.detail` | a split or sub split reached (`idx, label, msg` / `idx, id, split, label, source, current, ticks`), a split detail step |
| `ec.card` | the scorecard moment (`what`, watcher, portal, Maxor, Storm states) |
| `ec.bloodroom` / `ec.door` | the blood rush room by room (`why, index, name, mapId, toFairy, ...`) / a door coming down (`by, phase, ...`) |
| `ec.obs` | every world observation the splits act on, with how and who |
| `ec.pace` | the run's pace against the best |
| `label` / `label.gone` | `by: "ec", what, id, name, dist`: entities EC picked out |
| `ec.p3` / `ec.engine` / `eclog` | the P3 rotation state, its decision trail, every EcLog line |
| `ec.storm.check` | Storm's crush checks (`tick, count, offset, phaseStart`), found or not |
| `ec.ss` / `ec.terms` / `ec.brw` / `ec.leap` / `ec.profile` | the Simon Says solver, Term Info, Blood Rush Waypoints 2, Leap Extras' menu, the class/rush profile |
| `ec.clocks` | the offsets of EC's tick counters (DungeonSplits, StormPhase, TermInfo, SS solver) to `n` |

## How fields are written (`f`)

Every instance field of the packet, by name, recursively, losing nothing (recorder/PacketJson.kt,
RichJson.kt, PacketDecode.kt):

- every reflected object starts with `"@c"`, its concrete class (`ClientboundMoveEntityPacket$Pos`);
  `schema.json` lists each class's fields;
- text as `{"t": plain, "j": json}`: `j` the game's own JSON (styles, click and hover events,
  translation keys), left out when it is just the plain string;
- items as `{id, count, sb, name, lore, full, cd}`: `sb` the Skyblock id, `lore` every line plain,
  `full` the whole stack through the game's item codec (or `patch`, its components, when the codec
  refuses an odd stack), `cd` the custom data as SNBT; an empty stack is `null`. Recipe results
  (`ItemStackTemplate`) are tagged with `"@c"`;
- particles as `{type, opts}`; registry objects by id; block states as `minecraft:stone[...]`;
  positions as `[x, y, z]`; NBT as `{"snbt": ...}`; player profiles as `{id, name, props}`;
- entity data as `[[index, serializerId, value], ...]`;
- bytes as `{"len", "b64"}`; the login encryption handshake as `{"len", "withheld": "crypto"}`;
  server cookies as `{"len", "sha256"}` unless Cookie Payloads is on; bit sets as `{"bits": [longs]}`;
- numbers exactly: doubles in their shortest round-trip form, `"NaN"`/`"Infinity"`/`"-Infinity"` as
  strings, longs beyond 2^53 as strings.

Nothing is cut: there are no length caps or `...` markers. A real reference cycle is `{"@cycle":
class}`, anything nested over 64 deep `{"@depth": text}`, a field that fails to read `{"@error":
...}` and a JDK object whose fields are closed `{"@c", "@inaccessible": true, "str"}`. Packets
holding items or entity data are written on the network thread as they arrive, before the game
applies (and changes) them.

Decoded members added beside the fields: `changes: [[x, y, z, state]]` (section_blocks_update),
`colorPatch: {x, y, w, h, len, b64, full}` (map_item_data), `light: {sky, block}` each `{mask, empty,
arrays: {"<bit>": b64}}` with `y0` the section of bit 0 (light_update, level_chunk_with_light),
`etype, deg, dataState, facing` (add_entity), `state` (level_event 2001), `eventName` (game_event),
`snbt` (block_entity_data), `names` (update_tags: tag members by name). The command tree, tags,
recipes and advancements are written in full.

## Privacy

Recordings stay on your computer. With the defaults:

- private messages, guild, officer, co-op and friend lines are `{"hidden": "private"}` in packet
  lines, `chatui`, `chat.dropped` and `odin.chat`, and their raw frames are withheld;
- what you type is redacted: commands keep their name (`/warp`, args `<redacted>`), chat its length,
  keys typed into text fields are `{"redacted": true}`, typed characters are not written, and the
  raw frames of chat and command packets are withheld;
- the login encryption handshake is only a length (`crypto`), cookies only a length and hash;
- Odin settings whose name looks secret are `<redacted>`.

The server address, your account name and uuid, and the names of players around you are in the
file, as they are in the game; check before sharing one. Frame Thumbnails (off by default) are
pictures of your screen: whatever was on it, private chat included, is in them.

## Threads

Network-thread lines (packets, frames, `wire_out`, `disconnect`) and game-thread lines (everything
the client did with them) interleave by `seq`. A packet's `in` line is when it arrived; its
`applied` line is when it took effect on the game thread (usually the next frame), and lines the
game thread wrote in between (`ent`, `blk`, `me`) still show the state before it. Odin's tab-list
state is also snapshotted on the network thread (`thr: "net"`). Every capture hook is wrapped so a
failure becomes an `error` line and never reaches the game, and every hook returns at once when no
recording is open.

## What cannot be captured

- Server state the client is never sent: other entities' potion effects (only their particle
  swirls), server RNG and AI targets, other players' inputs, server tick timing beyond ping packets.
- Hardware input time finer than one rendered frame (GLFW is polled once a frame).
- Cancellations inside other mods' individual ClientPacketListener handler hooks (only cancels at
  channelRead0 or on Odin's bus are visible), and which mod cancelled something (the `modules`
  snapshot stands in).
- What other mods draw on screen, except through the opt-in frame thumbnails.
- Hypixel Mod API data, unless the client subscribes to it (any `hypixel:*` payload that does
  arrive is recorded).
- The login encryption handshake bytes (presence and length only, on purpose).
- Frames Netty drops before the packet decoder (malformed or oversized); only the resulting
  exception (`conn_error`) is logged.

## Using it as LLM context

A run is far too big to paste whole. Slice it with `tools/recorder/read.py` (Python 3, standard
library only). It reads through the index, decompresses only the members a filter can match (in
parallel), puts the lines in seq order, reads a crashed part up to its last whole line, and always
prints gaps, stops, crashes and connection errors.

```
python3 tools/recorder/read.py summary  REC                     # manifest, parts, every kind and packet type by count
python3 tools/recorder/read.py events   REC                     # marks, worlds, deaths, splits
python3 tools/recorder/read.py example  REC set_entity_data 2   # full lines of a packet type or kind
python3 tools/recorder/read.py timeline REC --from 12000 --to 12400 --no-movement
python3 tools/recorder/read.py timeline REC --kinds 'ec.*,odin.dungeon,in' --types system_chat,open_screen
python3 tools/recorder/read.py timeline REC --entity 1234       # everything about one entity
python3 tools/recorder/read.py timeline REC --around 553210 --window 100 --regex "Simon Says"
python3 tools/recorder/read.py state    REC --at 12000 --box 100,60,100,110,70,110 --map
python3 tools/recorder/read.py raw      REC 553210              # a line's raw frames as hex
python3 tools/recorder/read.py context  REC --budget 200000 --mark 1
```

REC is the recording's directory (or its manifest, or part files). The timeline leaves chunk data,
light, keyframe copies (`kf*`), particles, frames and cursor samples out unless `--all` or `--kinds`
asks for them. `state` starts at the nearest keyframe and applies every line after it up to the
moment (packets at their `applied` line), then prints the world, entities, HUD, tab list, Odin's
rooms and dungeon state; `--box`/`--pos` print blocks, `--room NAME` a room, `--map` the dungeon map
as ASCII. `context` builds a pack for a model: the recording, the state rebuilt at the moment, the
timeline nearest the moment that fits the budget, and notes on what was left out.

A good habit: find the moment with `events`, `--regex` or a bookmark, then give the model the
`context` pack around it plus one `example` of each packet type involved.
