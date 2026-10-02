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
| Particles And Sounds | on | |
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
| `in` | `p, t, n, ms, f` | a packet from the server: `p` its protocol id (`minecraft:set_entity_data`...), `f` its fields |
| `out` | `p, t, n, ms, f` | a packet you sent |
| `me` | `t, n, pos, rot, vel, ground, hp, abs, food, slot, held, keys, screen` | you, every tick anything in it changed. `keys` the controls held (`w a s d jump sneak sprint attack use`); `screen` the open screen's class and title |
| `game` | `t, n, area, floor, boss, room, party: [[name, class, dead]], effects: [[id, amplifier, ticks]], fps` | Odin's view, when it changes (checked twice a second) |
| `sidebar` | `t, n, title, lines` | the sidebar's lines, plain, when they change |
| `world` / `end` | `t, ms` | a world loaded / the recording ended |
| `budget` | `ms, note` | the hour's size budget is nearly used: bulk lines are being left out |
| `dropped` | `lines, ms` | the writer fell behind and dropped this many lines (a slow disk) |
| `error` | `what` | a packet that couldn't be written out |

### How fields are written (`f`)

Every instance field of the packet, by name, recursively:

- text (chat, names, titles) as plain text;
- items as `{id, count, name, sb, lore}` (`sb` the Skyblock id);
- block states as `minecraft:stone[...]`, positions as `[x, y, z]`, registry entries by name;
- entity data as `[[index, value], ...]`;
- bulk data (byte buffers, chunk payloads) as `{"bytes": n}`.

Lists longer than 128 and strings longer than 4000 are cut, with `"..."` marking the cut. Bundled
packets (how the server sends a new entity with its data) are written as their separate packets.

Left out entirely: keep-alives, pongs, bundle markers, light updates, chunk-batch markers. Written
as a name only: the command tree, tags, recipes, advancements.

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
