# Better Recorder format (v3)

One folder per Hypixel dungeon run, `devgineerclient-recordings/better/<ADR stamp>.br/`, named after the ADR run it
belongs to (`yyyy-MM-dd_HH-mm-ss`). It's renamed when the recording closes. A recording a crash cut short keeps the
session's own name, `<stamp>_brec_<hex>`; its `meta` line and `manifest.json` still carry `"adr"`. Local only.

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
- **t** is ADR's tick for the run (the `t` in the ADR file).
- **n** is the client tick (counted since the game started).

The kinds:

- **`meta`**: first line of each part.
  - `brec` (3), `brecFormat` (this summary), `adr` (stamp)
  - `mc`, `protocol` (the protocol version)
  - `uuid`, `name`, `server`, `profileId`, `profileName`
  - the session's `startMs`/`startNs`
- **`pre`** `{"fr":[seq, us, clientTick, ...]}`: the frames written before the run was known (the configuration
  prelude and this world's packets since login or respawn). `us` is µs relative to the session start, so it's
  negative. `dropped` counts frames the 48 MB world buffer had no room for (the newest).
- **`tk`**: one per client tick while recording.
  - `dt`: µs since the last tick.
  - `fr` `[seq, us, ...]`: the live frames copied since the last tick, both directions.
  - `in` `{...}`: only what changed, from these fields:
    - `k`: key bits held at the tick's start. In order: forward, back, left, right, jump, sneak, sprint, use, attack,
      drop, ctrl (ctrl + drop = drop stack).
    - `c` `[use, attack, drop]`: presses waiting for this tick. Written whenever any is non-zero, not just on change.
    - `sl`: hotbar slot.
    - `r` `[yaw, pitch]`, `p` `[x, y, z]`, `v` `[vx, vy, vz]`: the client's own, at the tick's end, at full precision.
    - `g`: on ground.
    - `st`: sprinting | sneaking<<1 | using item<<2.
    - `ping`: your tab latency.
    - `scr`: `null` or `[screen class, title]`.
    - `fps`.
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

`BrecRaw.read(dir)` reads the sidecar back. Match a frame to its tick by seq: from the `pre` and `tk` lines' `fr`.
