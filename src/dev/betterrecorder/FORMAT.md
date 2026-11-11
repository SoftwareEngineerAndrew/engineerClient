# Better Recorder format (v4)

Two kinds of recording, both in `devgineerclient-recordings/better/`:

- **An ADR run's**, on its own: one folder per Hypixel dungeon run, `<ADR stamp>.br/`, named after the ADR run it
  belongs to (`yyyy-MM-dd_HH-mm-ss`).
- **A manual one**, from `/brec start [note]` until `/brec stop`, anywhere: any Hypixel server, any other server,
  singleplayer (ADS). Its folder is `manual-<yyyy-MM-dd_HH-mm-ss>[-note].br/`; the note is cut to letters, digits, `_`
  and `-`, at most 40. While one runs, an ADR run that starts isn't recorded a second time; `/brec start` during an ADR
  run's recording is refused (use `/brec mark`).

The folder is renamed when the recording closes. A recording a crash cut short keeps the session's own name,
`<stamp>_brec_<hex>`; its `meta` line and `manifest.json` still say which it was. Local only.

The layout is the Dungeon Recorder's (`src/dev/recorder/RecorderSession.kt`, `RecorderFiles.kt`):

- `manifest.json`: the meta below plus counts, gaps, marks, parts and sizes.
- `partNNNN.jsonl.gz`: gzip members of JSON lines.
- `partNNNN.raw.gz`: the raw packet sidecar. Its members line up with the JSON members.
- `partNNNN.idx.jsonl`: one line per member, with offsets and seq/tick/time ranges, for seeking.
- `schema.json`: the Dungeon Recorder's schema; not used here.

## Lines

Every line starts `{"k":kind,"seq":S,"t":T,"n":N,"ms":wall clock,"ns":ns since the session started, ...}`:

- **seq** is global. Raw frames take theirs on the network thread as they are copied, so seq is the true order of
  frames and lines together.
- **t** is ADR's tick for the run (the `t` in the ADR file). A manual recording has no ADR run: its `t` is the
  recording's own client ticks since `/brec start`.
- **n** is the client tick (counted since the game started).

The kinds:

- **`meta`**: first line of each part.
  - `brec` (4), `brecFormat` (this summary), `adr` (stamp, null for a manual one)
  - `manual` (true for `/brec start`), `note` (manual only: the note as typed, or null)
  - `server` (the address joined, null in singleplayer), `singleplayer`, `world` (singleplayer only: the save's name)
  - `mc`, `protocol` (the protocol version)
  - `uuid`, `name`, `profileId`, `profileName`
  - the session's `startMs`/`startNs`
- **`pre`** `{"fr":[seq, us, clientTick, ...]}`: the frames written before the recording started (the configuration
  prelude and this world's packets since login or respawn). `us` is µs relative to the session start, so it's
  negative. `dropped` counts frames the 48 MB world buffer had no room for (the newest). Since v4 the world buffer is
  kept on every server, not only Hypixel, so a manual recording has the world from before it too.
- **`tk`**: one per client tick while recording.
  - `dt`: µs since the last tick.
  - `fr` `[seq, us, ...]`: the live frames copied since the last tick, both directions.
  - `in` `{...}`: only what changed, from these fields:
    - `k`: key bits held at the tick's start. In order: forward, back, left, right, jump, sneak, sprint, use, attack,
      drop, ctrl (ctrl + drop = drop stack).
    - `c` `[use, attack, drop]`: presses waiting for this tick. Written whenever any is non-zero, not just on change.
    - `sl`: hotbar slot.
    - `h` (v4): the item in your main hand, `[minecraft id, SkyBlock id or null]`, or null for none.
    - `r` `[yaw, pitch]`, `p` `[x, y, z]`, `v` `[vx, vy, vz]`: the client's own, at the tick's end, at full precision.
    - `g`: on ground.
    - `st`: sprinting | sneaking<<1 | using item<<2.
    - `ping`: your tab latency.
    - `scr`: `null` or `[screen class, title]`.
    - `fps`.
  - `use` (v4) `[[minecraft id, SkyBlock id or null], ticks using, ticks left, hand]`: every tick you're using an item
    (drawing a bow), at the tick's end. `ticks using` is the client's use ticks so far (what vanilla bow power comes
    from); `ticks left` the use duration remaining; hand 0 main, 1 off. Absent when not using.
  - `ar` (v4) `[[id, type, x, y, z, vx, vy, vz, yaw, pitch, age, inGround, inGroundTicks, owner], ...]`: every arrow
    entity the client has within 32 blocks of you, every tick one exists, as the client has it at the tick's end (what
    you see, after the client's own interpolation and movement, not the packets): entity id, type (`arrow`,
    `spectral_arrow`, ...), position and motion at full precision, its rotation, its client age in ticks, stuck in the
    ground (1/0) and for how many ticks, and its owner's entity id when the client knows it (-1 when not). Absent when
    there are none.
- **`prof`**: at the start.
  - `profileId`, `profileName` (from Hypixel's join chat)
  - `hp`, `maxHp`, `abs`, `food`, `sat`, `xp`
  - `inv`: 41 slots. 0-8 hotbar, 9-35 inventory, 36-39 armour feet→head, 40 offhand. Each is the full item with
    every component, or null.
  - `attrs` `[[id, base, value, [[modifier, amount, op]]]]`, `fx` `[[effect, amplifier, duration]]`
  - `tab`: the rows as drawn; `tabHeader`, `tabFooter`.
- **`mark`** `{"note"}`: from `/brec mark [note]`.
- **`end`** `{"ticks"}`: the last line.
- **`gap` / `stopped`**: from the session. A gap means the writer queue overflowed and lines/frames were skipped,
  counted per type.

## Raw sidecar

Concatenated gzip members of records:

    [u32 BE len][varlong seq][u8 dir: 0 clientbound, 1 serverbound][u8 phase: 3 configuration, 4 play][u8 flags][bytes]

- **bytes** is the frame as the game's PacketDecoder/PacketEncoder sees it: a varint packet id, then the payload.
  It's uncompressed and unencrypted.
- **flags** bit0 = withheld. The bytes are left out and len is still the original length. Only cookie responses are
  withheld. The login phase (encryption keys) is never kept.
- **The prelude** is the configuration phase both ways (registries, tags, known packs, feature flags). With the play
  login packet and `protocol`, it's what a decoder needs to read every frame after it, so a recording stays
  readable after a Minecraft update.
- **Singleplayer has none.** The integrated server hands packets to the client as objects in memory, never encoded,
  so a singleplayer recording (`"singleplayer":true`) has no raw frames: its ticks, inputs, `use` and `ar` lines are
  the whole of it.

`BrecRaw.read(dir)` reads the sidecar back. Match a frame to its tick by seq: from the `pre` and `tk` lines' `fr`.

## Changes

- **v4**: manual recordings (`/brec start [note]`, `/brec stop`; meta `manual`, `note`, `singleplayer`, `world`;
  `t` is the recording's own ticks); `tk` gains `use`, `ar` and `in.h`; the world buffer is kept on every server.
- **v3**: the raw sidecar, the configuration prelude and the world buffer.
