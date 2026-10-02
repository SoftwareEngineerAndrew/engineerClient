# Dungeon Recorder

A module (Engineer Client category, off by default) that records everything that happens in a
dungeon, as context for building mods with an LLM. Better PF records a replay and the Boss Recorder
the boss fights; this keeps the lot, readably:

- **every packet the server sends**, and **every packet you send**, written out field by field
  under their real names (26.1 is unobfuscated), each with the server tick it arrived on;
- **your own state** every tick it changes, and the game's derived state (Odin's area, floor, room
  and party, your effects, the sidebar) when it changes.

So a question like "what does the server send when a terminal opens", "what does Hypixel put in a
blood mob's metadata" or "what packet tells me the gate blew" is answered by looking, not guessing.

## Settings

| setting | default | |
|---|---|---|
| Where | Dungeons | Dungeons only, Dungeons + Hub (party finder, queueing), or Everywhere |
| Server Packets / Your Packets | on | each direction |
| Entity Movement | on | other entities' moves and head turns: the bulk of the packets |
| Particles And Sounds | on | the particle and sound packets |
| Played Sounds | on | the `snd` and `sndstop` lines below: every sound the client played or tried to, its own and mods' included |
| Spawned Particles | on | the `ptc` lines below: every particle requested and spawned, client-made ones included |
| Chunk Data | off | full chunk loads (large); off, a chunk load is just its x, z |
| Client State | on | the `me`, `game` and `sidebar` lines below |
| Typed Chat | off | what you type in chat and commands; off, the line says `redacted` |
| Hide Private Chats | on | private, guild, officer, co-op and friend lines are left out |
| Max MB Per Hour | 1000 | compressed size cap per clock hour; past 90% of it, entity movement, particles and sounds are left out until the hour turns |
| Open Folder | | |

## Size

Packets are gzipped JSON. A dungeon run is mostly entity movement; expect roughly 20-150 MB an hour
compressed with everything on (far under the cap), much less with Entity Movement off. Lines are built
and written on a background thread, so recording costs the game and network threads only a queue push.

## Files

`.minecraft/config/engineerclient/recorder/<yyyy-MM-dd_HH-mm-ss>_<floor>_part<N>.jsonl.gz`, one
recording per world (a dungeon run), a new part every hour or 256 MB. A recording starts buffering as
the world loads, so its first packets (the entities and blocks already there) are kept, and is only
written once the world turns out to be one to record. `.part` while being written.

Every line is one JSON object with `k` (kind). `t` is client ticks since the recording started, `n`
the server's tick count (one per ping, as Odin counts them), `ms` wall-clock milliseconds.

| k | fields | |
|---|---|---|
| `meta` | `format: "recorder-1", part, mod, mc, self, selfId, server, t, n, ms` | first line of every part; `selfId` is your entity id |
| `in` | `p, t, n, ms, e?, self?, f` | a packet from the server: `p` its protocol id (`minecraft:set_entity_data`...), `e` the entities it is about, `f` its fields |
| `out` | `p, t, n, ms, e?, self?, f` | a packet you sent |
| `commands` | `tree` | the client's command tree after a commands packet was applied |
| `me` | `t, n, pos, rot, vel, ground, hp, abs, food, slot, held, keys, screen` | you, every tick anything in it changed. `keys` the controls held (`w a s d jump sneak sprint attack use`); `screen` the open screen's class and title |
| `game` | `t, n, area, floor, boss, room, party: [[name, class, dead]], effects: [[id, amplifier, ticks]], fps` | Odin's view, when it changes (checked twice a second) |
| `sidebar` | `t, n, title, lines` | the sidebar's lines, plain, when they change |
| `snd` | `id, file, path, src, pos, vol, pitch, att, rel, loop, delay, range?, sub?, cls, res` | a sound the engine was asked to play (the server's, the client's own and mods'): `id` the sound event, `file`/`path` the variant it resolved to, `cls` the sound instance class, `res` `STARTED`, `STARTED_SILENTLY` or `NOT_STARTED`; `range` and `sub` (subtitle) when it got far enough to reach the listeners |
| `sndstop` | `what: inst\|match\|all, id, src, cls?, active?` | the engine stopping one sound instance (`active`: it was playing), every sound matching an id and/or source (null = any), or all |
| `ptc` | `opts, req?, spawned?, emit?` | one per tick with particles: `req` rows `[type, x, y, z, dx, dy, dz, force, always, o, made]` (asked of the level; `made` how many the Particles option and distance let through, null if the call never returned), `spawned` rows `[class, x, y, z, xd, yd, zd, lifetime]` (every particle added to the engine; vanilla classes without their package), `emit` rows `[entityId, entityType, o, lifetime]` (tracking emitters, -1 = default), `o` an index into `opts` (`{type, opts}` per distinct options object that tick) |
| `world` / `end` | `t, ms` | a world loaded / the recording ended |
| `budget` | `ms, note` | the hour's size budget is nearly used: bulk lines are being left out |
| `dropped` | `lines, ms` | the writer fell behind and dropped this many lines (a slow disk) |
| `error` | `what` | a packet that couldn't be written out |

### How fields are written (`f`)

Every instance field of the packet, by name, recursively, losing nothing (recorder/PacketJson.kt,
RichJson.kt, PacketDecode.kt):

- every reflected object starts with `"@c"`, its concrete class (`ClientboundMoveEntityPacket$Pos`);
- text as `{"t": plain, "j": json}`: `j` is the game's own JSON (styles, click and hover events,
  translation keys), left out when it is just the plain string;
- items as `{id, count, sb, name, lore, full, cd}`: `sb` the Skyblock id, `lore` every line plain,
  `full` the whole stack through the game's item codec (or `patch`, its components, when the codec
  refuses an odd stack), `cd` the custom data as SNBT; an empty stack is `null`. Recipe results
  (`ItemStackTemplate`) are tagged with `"@c"`;
- particles as `{type, opts}`; blocks, items, entity types, block entity types, menus, sounds,
  effects and component types by registry id; block states as `minecraft:stone[...]`; positions as
  `[x, y, z]`; NBT as `{"snbt": ...}`; player profiles as `{id, name, props: [{name, value, sig}]}`;
- entity data as `[[index, serializerId, value], ...]`;
- bytes (`byte[]`, buffers) as `{"len", "b64"}`; the login encryption handshake as
  `{"len", "withheld": "crypto"}`; server cookies as `{"len", "sha256"}` unless Cookie Payloads is on;
  bit sets as `{"bits": [longs]}`;
- numbers exactly: doubles in their shortest round-trip form, `"NaN"`/`"Infinity"`/`"-Infinity"` as
  strings, longs beyond 2^53 as strings.

Nothing is cut. A real reference cycle is written as `{"@cycle": class}` and anything nested over 64
deep as `{"@depth": text}`; a field that fails to read is `{"@error": ...}` and a JDK object whose
fields are closed `{"@c", "@inaccessible": true, "str"}`. Packets holding items or entity data are
written on the network thread as they arrive, before the game applies (and changes) them.

Decoded members added beside the fields: `changes: [[x, y, z, state]]` (section_blocks_update, in
place of the packed arrays), `colorPatch: {x, y, w, h, len, b64, full}` (map_item_data), `light:
{sky, block}` each `{mask, empty, arrays: {"<bit>": b64}}` with `y0` the section of bit 0
(light_update, level_chunk_with_light), `etype, deg, dataState, facing` (add_entity), `state`
(level_event 2001, block broken), `eventName` (game_event), `snbt` (block_entity_data), `names`
(update_tags: tag members by name for the built-in registries). A packet line also carries
`e: [entity ids]` for the packets about entities, and `self: true` when you are one of them.

The command tree, tags, recipes and advancements are written in full. After the client applies a
command tree, a `commands` line holds the resulting tree (`tree`, vanilla's own JSON form).
Bundled packets (how the server sends a new entity with its data) are written as their separate
packets. Left out entirely: keep-alives, pongs, bundle markers, chunk-batch markers.

## Using it as LLM context

A run is far too big to paste whole. Slice it with `tools/recorder/read.py`:

```
python3 tools/recorder/read.py summary  FILE...                    # time span and every packet type by count
python3 tools/recorder/read.py example  FILE... set_entity_data    # one full packet of a type
python3 tools/recorder/read.py timeline FILE... --from 12000 --to 12400 --no-movement
python3 tools/recorder/read.py timeline FILE... --types system_chat,open_screen,container_set_content
python3 tools/recorder/read.py timeline FILE... --grep "Simon Says"
```

A good habit: find the moment with `--grep` or `--types`, then give the model the timeline around
it (a few hundred server ticks, movement left out) plus one `example` of each packet type involved.

## Privacy

Recordings stay on your computer. Private chats are left out and typed chat is redacted unless you
turn those settings off. The server address and the names of players around you are in the file,
as they are in the game; check before sharing one.
