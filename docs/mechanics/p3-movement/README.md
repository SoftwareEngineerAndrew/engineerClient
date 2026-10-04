# P3 movement PBs between points of interest

Fastest recorded movement between p3wr's F7 P3 spots (the Odin posmsg boxes in [spots.csv](spots.csv)).
Spots apply retroactively: a leg is timed from box positions, not from the posmsg chat.

## Sources

- **recorder**: 52 Dungeon Recorder runs of 2026-10-03/04 that enter a spot (Hypixel main, alpha and the P3 Sim).
  `run` is the recording's 6-hex id. 1,182 legs.
- **bpf**: 129 new Better PF run logs, 2026-09-23 to 2026-10-03. `run` is the file stamp `yyyy-MM-dd_HH-mm-ss`;
  `server` is blank (not logged). Better PF runs that overlap a Dungeon Recorder recording are dropped, so no run is
  counted twice. 1,169 legs.

2,351 legs in all, over 318 spot pairs. Only p3wr's own movement is included.

## How a leg is timed

- A leg runs from spot A to the next different spot B entered; feet position, boxes inclusive of whole blocks.
- **Settled** start: after at least 3 ticks in A with no movement and no aim burst, the clock starts on the
  first movement or an aim burst (>15° summed over 5 ticks), whichever comes first;
  a teleport out of A before either starts it at the click that caused it.
  - Recorder legs: "movement" is a movement key (W/A/S/D, jump, sprint).
  - Better PF legs: there are no key inputs, so "movement" is the first tick that moves more than 0.05 blocks
    horizontally (teleports excluded). The aim-burst rule is the same. The teleport click is a recorded use-swing
    with a Spirit Leap held.
- **Pass-through** (pt) start: he never stopped in A, so the clock starts the first tick his feet leave A.
  These flatter the time (spots are small and close), so read them as upper-bound speeds.
- The clock stops the first tick his feet are in B. Legs with over 5 s of no input (no movement, for Better PF), death or
  ghost state are dropped.
- **On foot** = no leap or teleport in the leg. Leap/teleport legs are kept separately (`any_pb_*`).
- Method: recorder legs start with `sprint` / `walk` / `no-input` (from the sprint flag and keys). Better PF legs
  start with `sneak` (crouch flag set on more than half the ticks), `move` or `no-input`. Then come the items used
  (`bonzo`, `jerry`, `leap`, `etherwarp/AOTV`, ...). `lava-contact` / `lava-bounce` are recorder only.
- Ties on ticks go to a settled start, then to a recorder leg.

## Files

- [pb_summary.csv](pb_summary.csv): per pair, counts (`count`, `n_recorder`, `n_bpf`, `n_onfoot`, `n_settled`), the
  on-foot PB, the settled-only on-foot PB and the any-method PB, each with run, source, server, start kind and method.
- [legs.csv](legs.csv): every leg, with `source` = `recorder` or `bpf`.
- [tracks.jsonl.gz](tracks.jsonl.gz): the per-tick track of every leg (see Tick logs).
- [spots.csv](spots.csv): the 35 spots.
- Scripts: [tools/p3-movement/](../../../tools/p3-movement/).
  - `build.py` scans the recordings, and `legs.py` cuts the recorder legs.
  - `bpflegs.py` cuts the Better PF legs into `bpf_all.json`.
  - `dedupe.py` flags the Better PF runs that have a recorder twin.
  - `merge.py` writes the merged `legs.csv` and `pb_summary.csv`, plus `review.json`, which holds the top 3 tracks per pair.
  - `export_tracks.py` writes `tracks.jsonl.gz`.
  - Env vars override the paths: `RECORDINGS`, `SPOTS`, `BPF_RUNS`, `P3_EXTRACTS` (the per-recording `<6hex>.jsonl`
    extracts), `P3_DOCS`, `P3_LEGS`, `P3_OUT` and `P3_PAD`.

## Tick logs

`tracks.jsonl.gz` is gzipped JSON Lines with one line per leg, in `legs.csv` order (2,351 lines):

```
{"leg": {...all legs.csv columns, as strings...}, "pad": 10, "track": [[t, x, y, z, yaw, pitch, heldSbId, inputs, sneak, sprint], ...]}
```

- `track` has one row per client tick from `start_t - 10` to `end_t + 10`, on the same tick axis as the leg's
  `start_t` / `end_t`. Find the leg's start and end rows by matching `t` to those values; the 10 rows either side are context.
- `x`, `y`, `z`: feet position, rounded to 3 decimals. `yaw`, `pitch`: degrees, rounded to 2 decimals.
- `heldSbId`: SkyBlock id of the held hotbar item (e.g. `STARRED_BONZO_STAFF`), or null.
- `inputs`: 5 characters for W, S, A, D and Jump, each either the letter (held) or `.` (not held). For example, `W..D.` means forward and right.
  Better PF legs have `?????`, because Better PF records no keys.
- `sneak`: 1 or 0. Recorder legs use the shift key or the crouch flag; Better PF legs use the recorded crouch flag.
- `sprint`: 1 or 0 from the recorder's sprint flag; null for Better PF legs.
- The tracks are cut with the same per-tick data the leg cutter used, so the ticks, positions and held item match the
  leg's timing exactly. For a Better PF tick with no sample, the last known sample is carried forward.

## Gaps

S1 pairs never recorded: t1 → right path, right lever → t1, right lever → t2. t2 → right lever has a leap leg only.
Most PBs are pass-through starts: 4 of the 52 S1 on-foot PBs have a settled start, and 13 S1 pairs have any settled time.

## PBs within each section

### S1

| From → To | Legs | On-foot PB | Settled PB | Run, start | Method |
|---|---|---|---|---|---|
| s1 left lever → s1 right lever | 9 | 9t | - | edfebd, pt | sprint |
| s1 left lever → s1 right path | 7 | 11t | - | edfebd, pt | sprint |
| s1 left lever → s1 t1 | 3 | 47t | - | edfebd, pt | sprint+bonzo |
| s1 left lever → s1 t2 | 3 | 36t | - | edfebd, pt | sprint+bonzo |
| s1 left lever → s1 t3 | 2 | 32t | - | edfebd, pt | sprint |
| s1 left lever → s1 t4 | 20 | 8t | 80t | edfebd, pt | sprint |
| s1 left lever → ss | 13 | 20t | - | edfebd, pt | sprint |
| s1 right lever → s1 left lever | 75 | 8t | 9t | 4007fd, pt | sprint |
| s1 right lever → s1 right path | 10 | 5t | - | edfebd, pt | sprint |
| s1 right lever → s1 t3 | 6 | 27t | - | edfebd, pt | sprint |
| s1 right lever → s1 t4 | 1 | 16t | - | edfebd, pt | sprint |
| s1 right lever → ss | 6 | 15t | - | 0300d7, pt | sprint |
| s1 right path → s1 left lever | 14 | 8t | - | edfebd, pt | sprint |
| s1 right path → s1 right lever | 34 | 4t | - | 7130bd, pt | sprint |
| s1 right path → s1 t1 | 2 | 30t | - | edfebd, pt | sprint |
| s1 right path → s1 t2 | 5 | 25t | - | edfebd, pt | sprint |
| s1 right path → s1 t3 | 3 | 18t | - | edfebd, pt | sprint |
| s1 right path → s1 t4 | 2 | 8t | - | edfebd, pt | sprint |
| s1 right path → ss | 11 | 6t | - | 1a6c46, pt | sprint |
| s1 t1 → s1 left lever | 5 | 76t | - | edfebd, pt | sprint+lava-contact |
| s1 t1 → s1 right lever | 3 | 56t | - | 7130bd, pt | sprint+jerryx2+bonzo |
| s1 t1 → s1 t2 | 9 | 14t | 49t | 2026-09-29_21-48-06, pt | move+jerryx2 |
| s1 t1 → s1 t3 | 21 | 21t | - | 3066a9, pt | sprint |
| s1 t1 → s1 t4 | 5 | 47t | - | edfebd, pt | sprint+bonzo+lava-contact |
| s1 t1 → ss | 4 | 40t | - | 7130bd, pt | sprint+jerryx4 |
| s1 t2 → s1 left lever | 2 | 44t | - | 7130bd, pt | sprint+bonzo+jerry |
| s1 t2 → s1 right lever | 1 | none | - | edfebd, settled | leap only: walk+leap |
| s1 t2 → s1 right path | 7 | 22t | - | 7130bd, pt | sprint+bonzo |
| s1 t2 → s1 t1 | 23 | 8t | 10t | 0300d7, pt | walk |
| s1 t2 → s1 t3 | 12 | 20t | 20t | 7130bd, settled | sprint |
| s1 t2 → s1 t4 | 6 | 33t | - | 7130bd, pt | sprint+bonzo |
| s1 t2 → ss | 4 | 15t | - | 7130bd, pt | sprint |
| s1 t3 → s1 left lever | 5 | 43t | - | edfebd, pt | sprint+lava-contact |
| s1 t3 → s1 right lever | 4 | 47t | - | edfebd, pt | sprint+lava-contact |
| s1 t3 → s1 right path | 3 | 30t | - | edfebd, pt | sprint+lava-contact |
| s1 t3 → s1 t1 | 5 | 26t | 26t | edfebd, settled | sprint+bonzox2 |
| s1 t3 → s1 t2 | 4 | 30t | - | edfebd, pt | sprint+jerryx6 |
| s1 t3 → s1 t4 | 12 | 20t | - | edfebd, pt | walk+lava-contact |
| s1 t3 → ss | 2 | 35t | - | edfebd, pt | sprint+lava-contact |
| s1 t4 → s1 left lever | 7 | 13t | - | edfebd, pt | sprint |
| s1 t4 → s1 right lever | 6 | 14t | 25t | edfebd, pt | sprint |
| s1 t4 → s1 right path | 2 | 31t | - | edfebd, pt | sprint+bonzo |
| s1 t4 → s1 t1 | 2 | 34t | - | edfebd, pt | sprint |
| s1 t4 → s1 t2 | 3 | 24t | - | edfebd, pt | sprint |
| s1 t4 → s1 t3 | 30 | 14t | 17t | edfebd, pt | sprint |
| s1 t4 → ss | 7 | 17t | - | edfebd, pt | sprint |
| ss → s1 left lever | 4 | 19t | 19t | 7130bd, settled | sprint+bonzo |
| ss → s1 right lever | 16 | 13t | - | 7130bd, pt | sprint+bonzo |
| ss → s1 right path | 41 | 4t | 6t | 7130bd, pt | sprint+bonzo |
| ss → s1 t1 | 5 | 17t | 23t | 7130bd, pt | sprint |
| ss → s1 t2 | 8 | 7t | 23t | 7130bd, pt | sprint |
| ss → s1 t3 | 6 | 17t | 17t | 7130bd, settled | sprint |
| ss → s1 t4 | 13 | 14t | - | 7130bd, pt | sprint |

### S2

| From → To | Legs | On-foot PB | Settled PB | Run, start | Method |
|---|---|---|---|---|---|
| ee2 → s2 high path | 32 | 22t | 24t | edfebd, pt | sprint+bonzo |
| ee2 → s2 left lever | 3 | none | - | 3e7a14, pt | leap only: sprint+leap |
| ee2 → s2 mid high | 4 | 14t | 20t | edfebd, pt | sprint |
| ee2 → s2 mid low | 1 | 55t | - | ed1c25, pt | sprint+lava-contact |
| ee2 → s2 right lever | 3 | 39t | - | edfebd, pt | sprint+bonzo |
| ee2 → s2 t1 | 13 | 28t | - | edfebd, pt | sprint |
| ee2 → s2 t2 | 57 | 17t | 20t | edfebd, pt | sprint |
| ee2 → s2 t3 | 2 | 62t | - | 1762cc, pt | sprint+bonzo+jerry |
| ee2 → s2 t4 | 4 | 37t | 37t | edfebd, settled | sprint |
| ee2 → s2 t5 | 17 | 25t | - | 2b8212, pt | sprint |
| s2 high path → ee2 | 11 | 22t | - | 2026-10-02_16-16-48, pt | move+bonzox2 |
| s2 high path → s2 left lever | 31 | 4t | 8t | edfebd, pt | sprint |
| s2 high path → s2 mid high | 1 | 116t | - | 207123, pt | walk+jerryx2 |
| s2 high path → s2 right lever | 17 | 11t | 130t | 3066a9, pt | sprint |
| s2 high path → s2 t1 | 1 | 47t | - | edfebd, pt | sprint |
| s2 high path → s2 t2 | 1 | 32t | - | edfebd, pt | sprint |
| s2 high path → s2 t3 | 1 | 195t | - | 2026-10-02_23-16-30, pt | move+bonzo+jerryx3 |
| s2 high path → s2 t5 | 2 | 16t | - | edfebd, pt | sprint |
| s2 left lever → ee2 | 5 | none | - | edfebd, pt | leap only: walk+leap |
| s2 left lever → s2 high path | 13 | 7t | 13t | 2026-09-23_15-05-56, pt | move |
| s2 left lever → s2 right lever | 17 | 9t | 10t | 2026-10-02_16-08-20, pt | move |
| s2 left lever → s2 t3 | 2 | 44t | - | 2026-09-23_14-41-18, pt | move |
| s2 left lever → s2 t4 | 1 | 28t | 28t | 2026-10-02_16-16-48, settled | move |
| s2 left lever → s2 t5 | 6 | 21t | - | edfebd, pt | sprint |
| s2 mid high → ee2 | 2 | 56t | - | edfebd, pt | sprint+jerry |
| s2 mid high → s2 left lever | 1 | 42t | - | edfebd, pt | sprint+jerryx3+bonzo |
| s2 mid high → s2 mid low | 1 | 55t | - | 1a6c46, pt | walk |
| s2 mid high → s2 right lever | 1 | 26t | - | edfebd, pt | walk |
| s2 mid high → s2 t1 | 9 | 41t | - | edfebd, pt | sprint+bonzox2 |
| s2 mid high → s2 t2 | 3 | 18t | 24t | edfebd, pt | sprint |
| s2 mid high → s2 t3 | 2 | 22t | - | edfebd, pt | sprint |
| s2 mid high → s2 t4 | 2 | 24t | - | edfebd, pt | sprint |
| s2 mid high → s2 t5 | 4 | 9t | 10t | edfebd, pt | sprint |
| s2 mid low → ee2 | 1 | none | - | 2026-09-29_21-27-25, pt | leap only: move+leap |
| s2 mid low → s2 right lever | 3 | 53t | - | 1a6c46, pt | sprint+bonzo |
| s2 mid low → s2 t1 | 3 | 13t | - | edfebd, pt | sprint |
| s2 mid low → s2 t2 | 1 | none | - | 2026-10-02_16-16-48, pt | leap only: etherwarp/AOTV |
| s2 mid low → s2 t3 | 6 | 15t | - | 3066a9, pt | sprint |
| s2 mid low → s2 t4 | 1 | 24t | - | ed1c25, pt | sprint |
| s2 mid low → s2 t5 | 2 | 58t | - | 2026-09-23_13-59-09, pt | move |
| s2 right lever → ee2 | 3 | 95t | - | 1a6c46, pt | sprint+jerry+lava-contact |
| s2 right lever → s2 left lever | 8 | 17t | - | edfebd, pt | sprint+jerryx2 |
| s2 right lever → s2 mid low | 2 | 49t | - | edfebd, pt | walk+bonzo |
| s2 right lever → s2 t1 | 1 | none | - | 2026-09-23_11-28-01, pt | leap only: move+leap |
| s2 right lever → s2 t2 | 3 | 35t | - | 2026-10-02_16-16-48, pt | move |
| s2 right lever → s2 t3 | 12 | 27t | - | 2026-10-02_23-06-36, pt | move |
| s2 right lever → s2 t5 | 37 | 13t | - | 3066a9, pt | sprint |
| s2 t1 → ee2 | 5 | none | - | 2026-09-23_14-46-28, pt | leap only: move+leap |
| s2 t1 → s2 mid high | 1 | none | - | edfebd, pt | leap only: leap |
| s2 t1 → s2 mid low | 8 | 13t | - | 2026-09-23_12-27-16, pt | move |
| s2 t1 → s2 t2 | 2 | 67t | - | 1a6c46, pt | walk+lava-contact |
| s2 t1 → s2 t3 | 13 | 29t | 36t | 2026-09-23_11-55-41, pt | move |
| s2 t1 → s2 t5 | 1 | 61t | - | 2026-10-02_17-28-36, pt | move+bonzo |
| s2 t2 → ee2 | 5 | 62t | - | edfebd, pt | sprint+jerryx7+lava-contact |
| s2 t2 → s2 mid high | 1 | none | - | edfebd, settled | leap only: leap |
| s2 t2 → s2 mid low | 1 | 19t | - | 2026-10-02_16-16-48, pt | move |
| s2 t2 → s2 right lever | 3 | 50t | - | edfebd, pt | sprint+jerryx2+bonzo |
| s2 t2 → s2 t1 | 4 | 16t | - | edfebd, pt | walk |
| s2 t2 → s2 t3 | 36 | 19t | 26t | 2026-10-02_19-03-10, pt | move |
| s2 t2 → s2 t4 | 2 | 26t | - | edfebd, pt | sprint |
| s2 t2 → s2 t5 | 6 | 28t | - | edfebd, pt | sprint+jerryx2+bonzo |
| s2 t3 → ee2 | 1 | 1307t | - | 207123, pt | sprint+bonzox42+jerryx6+lava-contact |
| s2 t3 → s2 mid low | 1 | 7t | - | 207123, pt | sprint |
| s2 t3 → s2 right lever | 20 | 34t | 37t | 2026-09-23_14-05-13, pt | move |
| s2 t3 → s2 t1 | 3 | 31t | - | 2026-09-23_15-05-56, pt | move |
| s2 t3 → s2 t2 | 8 | 40t | - | 3e7a14, pt | sprint+lava-contact |
| s2 t3 → s2 t4 | 1 | 39t | 39t | 1a6c46, settled | sprint |
| s2 t3 → s2 t5 | 11 | 21t | - | 2026-10-02_23-55-30, pt | move |
| s2 t4 → s2 left lever | 1 | none | - | 3066a9, settled | leap only: sprint+teleport(DUNGEONBREAKER)+lava-contact |
| s2 t4 → s2 right lever | 5 | 32t | - | 2026-09-23_11-28-01, pt | move |
| s2 t4 → s2 t1 | 1 | 43t | 43t | edfebd, settled | walk+bonzo |
| s2 t4 → s2 t3 | 1 | 20t | 20t | 2026-10-02_16-16-48, settled | move |
| s2 t4 → s2 t5 | 1 | 91t | - | 1a6c46, pt | walk+lava-contact |
| s2 t5 → ee2 | 2 | 65t | - | edfebd, pt | walk+jerryx5+lava-contact |
| s2 t5 → s2 high path | 1 | none | - | edfebd, pt | leap only: leap |
| s2 t5 → s2 left lever | 5 | 41t | - | edfebd, pt | sprint+jerryx3+bonzo |
| s2 t5 → s2 mid high | 3 | 13t | - | edfebd, pt | sprint |
| s2 t5 → s2 right lever | 24 | 8t | - | edfebd, pt | sprint |
| s2 t5 → s2 t2 | 8 | 15t | - | edfebd, pt | sprint |
| s2 t5 → s2 t3 | 43 | 19t | 25t | edfebd, pt | walk |
| s2 t5 → s2 t4 | 2 | 22t | - | edfebd, pt | sprint |

### S3

| From → To | Legs | On-foot PB | Settled PB | Run, start | Method |
|---|---|---|---|---|---|
| arrows → s3 left lever | 1 | 167t | - | 1a6c46, pt | walk+lava-contact |
| arrows → s3 t1 | 1 | none | - | 2026-09-23_14-20-18, pt | leap only: move+leap |
| arrows → s3 t3 | 3 | none | - | edfebd, pt | leap only: walk+leap |
| arrows → s3 t4 | 2 | 13t | - | 2026-09-23_11-18-49, pt | move |
| ee3 → arrows | 1 | 128t | - | 2026-10-02_17-38-12, pt | move+bonzox2 |
| ee3 → s3 left lever | 1 | 83t | - | 2026-10-02_16-16-48, pt | move+bonzo |
| ee3 → s3 right lever | 1 | 219t | 219t | 2026-09-23_14-41-18, settled | move |
| ee3 → s3 t1 | 13 | 4t | 8t | ae3d84, pt | sprint |
| ee3 → s3 t2 | 11 | 40t | 40t | 2026-09-23_15-44-55, settled | move |
| ee3 → s3 t3 | 3 | 45t | - | afb98f, pt | sprint+bonzo+lava-contact |
| ee3 → s3 t4 | 9 | 32t | 47t | 2026-09-23_13-50-28, pt | move |
| s3 left lever → arrows | 1 | 134t | 134t | 1a6c46, settled | sprint+lava-contact |
| s3 left lever → ee3 | 2 | 165t | - | 2026-09-29_21-48-06, pt | move+bonzox2 |
| s3 left lever → s3 right lever | 30 | 5t | - | 2026-09-23_11-18-49, pt | move |
| s3 left lever → s3 t1 | 1 | none | - | 3ee9c0, pt | leap only: walk+leap+lava-contact |
| s3 left lever → s3 t3 | 1 | none | - | edfebd, pt | leap only: walk+leap+bonzo+jerry |
| s3 left lever → s3 t4 | 3 | 148t | - | 1a6c46, pt | walk+lava-contact |
| s3 right lever → s3 left lever | 7 | 17t | - | 1a6c46, pt | sprint+bonzox2 |
| s3 right lever → s3 t2 | 1 | none | - | b3d41a, pt | leap only: walk+etherwarp/AOTVx4 |
| s3 right lever → s3 t3 | 3 | 186t | - | 207123, pt | sprint+jerryx2+bonzox6+lava-contact |
| s3 right lever → s3 t4 | 4 | 62t | - | 2026-10-02_16-16-48, pt | move+bonzo |
| s3 t1 → ee3 | 12 | 3t | - | 2026-10-02_16-16-48, pt | move |
| s3 t1 → s3 left lever | 3 | 76t | 76t | 2026-09-23_11-49-05, settled | move |
| s3 t1 → s3 right lever | 1 | none | - | ae3d84, pt | leap only: walk+leap+lava-contact |
| s3 t1 → s3 t3 | 1 | 142t | - | 1a6c46, pt | walk+bonzo+jerryx2+lava-contact |
| s3 t1 → s3 t4 | 6 | 4t | 4t | 2026-09-23_12-22-07, settled | no-input |
| s3 t2 → arrows | 3 | 25t | - | 2026-09-23_14-25-35, pt | move |
| s3 t2 → s3 left lever | 2 | 98t | - | 2026-09-23_15-22-54, pt | move |
| s3 t2 → s3 right lever | 4 | 98t | - | 2026-09-29_21-16-59, pt | move |
| s3 t2 → s3 t3 | 3 | none | - | b3d41a, settled | leap only: etherwarp/AOTV |
| s3 t2 → s3 t4 | 3 | 23t | 23t | b3d41a, settled | sprint |
| s3 t3 → arrows | 6 | 29t | 30t | edfebd, pt | sprint+bonzo |
| s3 t3 → s3 left lever | 2 | 68t | 68t | edfebd, settled | sprint+bonzox2 |
| s3 t3 → s3 right lever | 1 | 91t | 91t | edfebd, settled | walk+bonzox3+lava-contact |
| s3 t3 → s3 t1 | 3 | 26t | - | edfebd, pt | sprint |
| s3 t3 → s3 t2 | 6 | 15t | 16t | 2026-09-29_21-16-59, pt | move+bonzox2 |
| s3 t3 → s3 t4 | 5 | 32t | 36t | edfebd, pt | sprint+bonzo |
| s3 t4 → s3 left lever | 32 | 27t | - | 2026-10-02_16-16-48, pt | move |
| s3 t4 → s3 right lever | 1 | 42t | - | 2026-09-28_20-40-07, pt | move+bonzo |
| s3 t4 → s3 t3 | 2 | 97t | - | edfebd, pt | walk+lava-contact |

### S4 + core

| From → To | Legs | On-foot PB | Settled PB | Run, start | Method |
|---|---|---|---|---|---|
| ee4 → s4 left lever | 1 | 95t | 95t | edfebd, settled | walk+lava-contact |
| ee4 → s4 t1 | 29 | 16t | 17t | 207123, pt | sprint |
| ee4 → s4 t2 | 6 | none | - | 2026-09-29_18-31-00, settled | leap only: move+teleport(WITHER_CLOAK) |
| ee4 → s4 t3 | 17 | 17t | 17t | 207123, settled | sprint |
| ee4 → s4 t4 | 22 | 18t | 18t | 2026-10-02_23-22-51, settled | move |
| ee4 → in core | 195 | 1t | 3t | 3066a9, pt | sprint |
| s4 left lever → ee4 | 2 | 50t | - | 2026-09-23_13-50-28, pt | move |
| s4 left lever → s4 right lever | 4 | 8t | - | 2026-09-23_15-16-31, pt | move |
| s4 left lever → s4 t1 | 1 | 647t | - | 1a6c46, pt | sprint+bonzox19+lava-contact |
| s4 left lever → in core | 17 | none | - | 2026-09-23_11-55-41, pt | leap only: move+leap |
| s4 right lever → ee4 | 1 | 48t | - | 207123, pt | sprint+bonzox2 |
| s4 right lever → s4 left lever | 18 | 5t | - | 2026-09-23_12-32-15, pt | move |
| s4 right lever → s4 t4 | 1 | 194t | - | 2026-10-02_16-16-48, pt | move+bonzox8 |
| s4 right lever → in core | 10 | none | - | a75187, settled | leap only: leap |
| s4 t1 → ee4 | 11 | 25t | - | 207123, pt | sprint+jerry+bonzo |
| s4 t1 → s4 right lever | 1 | none | - | 2026-09-23_13-50-28, settled | leap only: move+leap |
| s4 t1 → s4 t2 | 4 | 9t | - | 3d6f29, pt | sprint |
| s4 t1 → s4 t3 | 12 | 10t | 35t | 2026-09-29_21-52-48, pt | move |
| s4 t1 → s4 t4 | 1 | none | - | 2b8212, pt | leap only: walk+leap+lava-contact |
| s4 t1 → in core | 1 | none | - | 2026-10-02_17-38-12, pt | leap only: move+leap |
| s4 t2 → ee4 | 3 | 48t | - | 207123, pt | walk |
| s4 t2 → s4 t1 | 2 | 51t | - | 1a6c46, pt | walk |
| s4 t2 → s4 t3 | 2 | none | - | 2026-09-29_18-25-07, pt | leap only: no-input+leap |
| s4 t2 → s4 t4 | 1 | 91t | 91t | 7130bd, settled | walk+jerry+lava-contact |
| s4 t2 → in core | 3 | none | - | 2026-09-29_18-31-00, pt | leap only: move+leap |
| s4 t3 → ee4 | 2 | 66t | - | 2026-09-23_11-18-49, pt | move |
| s4 t3 → s4 right lever | 24 | 23t | - | edfebd, pt | sprint+lava-contact |
| s4 t3 → s4 t1 | 1 | 146t | - | 1a6c46, pt | walk+bonzo+lava-contact |
| s4 t3 → s4 t4 | 3 | 31t | - | 2026-09-23_14-15-07, pt | move |
| s4 t3 → in core | 9 | none | - | a72d42, pt | leap only: leap |
| s4 t4 → ee4 | 8 | 16t | - | 2026-10-02_22-40-27, pt | move |
| s4 t4 → s4 left lever | 8 | 36t | - | 2026-09-23_14-15-07, pt | move |
| s4 t4 → s4 right lever | 4 | 28t | - | 2026-09-23_15-27-53, pt | move |
| s4 t4 → s4 t3 | 4 | 21t | - | 2b8212, pt | sprint |
| s4 t4 → in core | 11 | 3t | - | 1a6c46, pt | sprint |
| in core → ee4 | 211 | 1t | 2t | 182db7, pt | sprint |
| in core → s4 left lever | 1 | 429t | - | 1a6c46, pt | walk+bonzo+lava-contact |
| in core → s4 t4 | 5 | 4t | 4t | 1a6c46, settled | sprint |

Cross-section legs are in pb_summary.csv.
