# P3 movement PBs between points of interest

Fastest recorded movement between p3wr's F7 P3 spots (the Odin posmsg boxes in [spots.csv](spots.csv)),
from every Dungeon Recorder recording of 2026-10-03/04 that enters a spot (Hypixel main, alpha and the P3 Sim).
Spots apply retroactively: a leg is timed from box positions, not from the posmsg chat.

## How a leg is timed

- A leg runs from spot A to the next different spot B entered; feet position, boxes inclusive of whole blocks.
- **Settled** start: after at least 3 ticks in A with no movement input and no aim burst, the clock starts on the
  first movement key (W/A/S/D, jump, sprint) or an aim burst (>15° summed over 5 ticks), whichever comes first;
  a teleport out of A before either starts it at the click that caused it.
- **Pass-through** (pt) start: he never stopped in A, so the clock starts the first tick his feet leave A.
  These flatter the time (spots are small and close), so read them as upper-bound speeds.
- The clock stops the first tick his feet are in B. Legs with over 5 s of no input, death or ghost state are dropped.
- **On foot** = no leap or teleport in the leg. Leap/teleport legs are kept separately (`any_pb_*`).

## Files

- [pb_summary.csv](pb_summary.csv): per pair, counts, the on-foot PB, the settled-only on-foot PB, the any-method PB,
  each with run id (the recording's 6-hex id), server, start kind and method.
- [legs.csv](legs.csv): every leg.
- [spots.csv](spots.csv): the 35 spots.
- Scripts: [tools/p3-movement/](../../../tools/p3-movement/) (`build.py` scans the recordings, `legs.py` cuts legs;
  `RECORDINGS` and `SPOTS` env vars override the paths).

## Gaps

S1 pairs with no on-foot time yet: t1 → right path, right lever → t1, right lever → t2 (never recorded) and
t2 → right lever (leap only). Most PBs are pass-through starts; only about 10 S1 pairs have a settled start.

## PBs within each section

### S1

| From → To | Legs | On-foot PB | Settled PB | Run, start | Method |
|---|---|---|---|---|---|
| s1 left lever → s1 right lever | 9 | 9t | - | edfebd, pt | sprint |
| s1 left lever → s1 right path | 7 | 11t | - | edfebd, pt | sprint |
| s1 left lever → s1 t1 | 3 | 47t | - | edfebd, pt | sprint+bonzo |
| s1 left lever → s1 t2 | 3 | 36t | - | edfebd, pt | sprint+bonzo |
| s1 left lever → s1 t3 | 1 | 32t | - | edfebd, pt | sprint |
| s1 left lever → s1 t4 | 11 | 8t | 80t | edfebd, pt | sprint |
| s1 left lever → ss | 13 | 20t | - | edfebd, pt | sprint |
| s1 right lever → s1 left lever | 28 | 8t | - | 4007fd, pt | sprint |
| s1 right lever → s1 right path | 9 | 5t | - | edfebd, pt | sprint |
| s1 right lever → s1 t3 | 5 | 27t | - | edfebd, pt | sprint |
| s1 right lever → s1 t4 | 1 | 16t | - | edfebd, pt | sprint |
| s1 right lever → ss | 5 | 15t | - | 0300d7, pt | sprint |
| s1 right path → s1 left lever | 14 | 8t | - | edfebd, pt | sprint |
| s1 right path → s1 right lever | 33 | 4t | - | 7130bd, pt | sprint |
| s1 right path → s1 t1 | 1 | 30t | - | edfebd, pt | sprint |
| s1 right path → s1 t2 | 5 | 25t | - | edfebd, pt | sprint |
| s1 right path → s1 t3 | 3 | 18t | - | edfebd, pt | sprint |
| s1 right path → s1 t4 | 2 | 8t | - | edfebd, pt | sprint |
| s1 right path → ss | 11 | 6t | - | 1a6c46, pt | sprint |
| s1 t1 → s1 left lever | 3 | 76t | - | edfebd, pt | sprint+lava-contact |
| s1 t1 → s1 right lever | 3 | 56t | - | 7130bd, pt | sprint+jerryx2+bonzo |
| s1 t1 → s1 t2 | 7 | 16t | 49t | 7130bd, pt | sprint+jerryx2 |
| s1 t1 → s1 t3 | 6 | 21t | - | 3066a9, pt | sprint |
| s1 t1 → s1 t4 | 4 | 47t | - | edfebd, pt | sprint+bonzo+lava-contact |
| s1 t1 → ss | 4 | 40t | - | 7130bd, pt | sprint+jerryx4 |
| s1 t2 → s1 left lever | 2 | 44t | - | 7130bd, pt | sprint+bonzo+jerry |
| s1 t2 → s1 right lever | 1 | none | - | edfebd, settled | leap only: walk+leap |
| s1 t2 → s1 right path | 7 | 22t | - | 7130bd, pt | sprint+bonzo |
| s1 t2 → s1 t1 | 12 | 8t | 10t | 0300d7, pt | walk |
| s1 t2 → s1 t3 | 8 | 20t | 20t | 7130bd, settled | sprint |
| s1 t2 → s1 t4 | 6 | 33t | - | 7130bd, pt | sprint+bonzo |
| s1 t2 → ss | 4 | 15t | - | 7130bd, pt | sprint |
| s1 t3 → s1 left lever | 4 | 43t | - | edfebd, pt | sprint+lava-contact |
| s1 t3 → s1 right lever | 4 | 47t | - | edfebd, pt | sprint+lava-contact |
| s1 t3 → s1 right path | 3 | 30t | - | edfebd, pt | sprint+lava-contact |
| s1 t3 → s1 t1 | 4 | 26t | 26t | edfebd, settled | sprint+bonzox2 |
| s1 t3 → s1 t2 | 4 | 30t | - | edfebd, pt | sprint+jerryx6 |
| s1 t3 → s1 t4 | 11 | 20t | - | edfebd, pt | walk+lava-contact |
| s1 t3 → ss | 2 | 35t | - | edfebd, pt | sprint+lava-contact |
| s1 t4 → s1 left lever | 7 | 13t | - | edfebd, pt | sprint |
| s1 t4 → s1 right lever | 6 | 14t | 25t | edfebd, pt | sprint |
| s1 t4 → s1 right path | 2 | 31t | - | edfebd, pt | sprint+bonzo |
| s1 t4 → s1 t1 | 2 | 34t | - | edfebd, pt | sprint |
| s1 t4 → s1 t2 | 3 | 24t | - | edfebd, pt | sprint |
| s1 t4 → s1 t3 | 22 | 14t | 17t | edfebd, pt | sprint |
| s1 t4 → ss | 7 | 17t | - | edfebd, pt | sprint |
| ss → s1 left lever | 4 | 19t | 19t | 7130bd, settled | sprint+bonzo |
| ss → s1 right lever | 15 | 13t | - | 7130bd, pt | sprint+bonzo |
| ss → s1 right path | 40 | 4t | 6t | 7130bd, pt | sprint+bonzo |
| ss → s1 t1 | 3 | 17t | 23t | 7130bd, pt | sprint |
| ss → s1 t2 | 8 | 7t | 23t | 7130bd, pt | sprint |
| ss → s1 t3 | 6 | 17t | 17t | 7130bd, settled | sprint |
| ss → s1 t4 | 13 | 14t | - | 7130bd, pt | sprint |

### S2

| From → To | Legs | On-foot PB | Settled PB | Run, start | Method |
|---|---|---|---|---|---|
| ee2 → s2 high path | 23 | 22t | 24t | edfebd, pt | sprint+bonzo |
| ee2 → s2 left lever | 1 | none | - | 3e7a14, pt | leap only: sprint+leap |
| ee2 → s2 mid high | 4 | 14t | 20t | edfebd, pt | sprint |
| ee2 → s2 mid low | 1 | 55t | - | ed1c25, pt | sprint+lava-contact |
| ee2 → s2 right lever | 3 | 39t | - | edfebd, pt | sprint+bonzo |
| ee2 → s2 t1 | 2 | 28t | - | edfebd, pt | sprint |
| ee2 → s2 t2 | 18 | 17t | - | edfebd, pt | sprint |
| ee2 → s2 t3 | 2 | 62t | - | 1762cc, pt | sprint+bonzo+jerry |
| ee2 → s2 t4 | 2 | 37t | 37t | edfebd, settled | sprint |
| ee2 → s2 t5 | 12 | 25t | - | 2b8212, pt | sprint |
| s2 high path → ee2 | 8 | 23t | - | b3d41a, pt | sprint+bonzo |
| s2 high path → s2 left lever | 22 | 4t | 42t | edfebd, pt | sprint |
| s2 high path → s2 mid high | 1 | 116t | - | 207123, pt | walk+jerryx2 |
| s2 high path → s2 right lever | 2 | 11t | - | 3066a9, pt | sprint |
| s2 high path → s2 t1 | 1 | 47t | - | edfebd, pt | sprint |
| s2 high path → s2 t2 | 1 | 32t | - | edfebd, pt | sprint |
| s2 high path → s2 t5 | 1 | 16t | - | edfebd, pt | sprint |
| s2 left lever → ee2 | 3 | none | - | edfebd, pt | leap only: walk+leap |
| s2 left lever → s2 high path | 9 | 8t | 13t | 207123, pt | sprint |
| s2 left lever → s2 right lever | 6 | 10t | 10t | 1a6c46, settled | walk |
| s2 left lever → s2 t3 | 1 | 51t | - | 3e7a14, pt | sprint+bonzo |
| s2 left lever → s2 t5 | 5 | 21t | - | edfebd, pt | sprint |
| s2 mid high → ee2 | 2 | 56t | - | edfebd, pt | sprint+jerry |
| s2 mid high → s2 left lever | 1 | 42t | - | edfebd, pt | sprint+jerryx3+bonzo |
| s2 mid high → s2 mid low | 1 | 55t | - | 1a6c46, pt | walk |
| s2 mid high → s2 right lever | 1 | 26t | - | edfebd, pt | walk |
| s2 mid high → s2 t1 | 9 | 41t | - | edfebd, pt | sprint+bonzox2 |
| s2 mid high → s2 t2 | 3 | 18t | 24t | edfebd, pt | sprint |
| s2 mid high → s2 t3 | 2 | 22t | - | edfebd, pt | sprint |
| s2 mid high → s2 t4 | 2 | 24t | - | edfebd, pt | sprint |
| s2 mid high → s2 t5 | 4 | 9t | 10t | edfebd, pt | sprint |
| s2 mid low → s2 right lever | 2 | 53t | - | 1a6c46, pt | sprint+bonzo |
| s2 mid low → s2 t1 | 2 | 13t | - | edfebd, pt | sprint |
| s2 mid low → s2 t3 | 3 | 15t | - | 3066a9, pt | sprint |
| s2 mid low → s2 t4 | 1 | 24t | - | ed1c25, pt | sprint |
| s2 mid low → s2 t5 | 1 | none | - | 408abb, pt | leap only: sprint+teleport(DUNGEONBREAKER)x2+lava-contact |
| s2 right lever → ee2 | 3 | 95t | - | 1a6c46, pt | sprint+jerry+lava-contact |
| s2 right lever → s2 left lever | 3 | 17t | - | edfebd, pt | sprint+jerryx2 |
| s2 right lever → s2 mid low | 2 | 49t | - | edfebd, pt | walk+bonzo |
| s2 right lever → s2 t2 | 2 | 132t | - | 1a6c46, pt | walk |
| s2 right lever → s2 t3 | 4 | 44t | - | fd7887, pt | walk+bonzo |
| s2 right lever → s2 t5 | 12 | 13t | - | 3066a9, pt | sprint |
| s2 t1 → ee2 | 3 | none | - | db7289, pt | leap only: sprint+teleport(DUNGEONBREAKER)+lava-contact |
| s2 t1 → s2 mid high | 1 | none | - | edfebd, pt | leap only: leap |
| s2 t1 → s2 mid low | 1 | none | - | edfebd, pt | leap only: leap+bonzo |
| s2 t1 → s2 t2 | 2 | 67t | - | 1a6c46, pt | walk+lava-contact |
| s2 t1 → s2 t3 | 1 | 34t | - | edfebd, pt | sprint+lava-contact |
| s2 t2 → ee2 | 4 | 62t | - | edfebd, pt | sprint+jerryx7+lava-contact |
| s2 t2 → s2 mid high | 1 | none | - | edfebd, settled | leap only: leap |
| s2 t2 → s2 right lever | 1 | 50t | - | edfebd, pt | sprint+jerryx2+bonzo |
| s2 t2 → s2 t1 | 4 | 16t | - | edfebd, pt | walk |
| s2 t2 → s2 t3 | 17 | 26t | 26t | 6e38dd, settled | sprint |
| s2 t2 → s2 t4 | 1 | 26t | - | edfebd, pt | sprint |
| s2 t2 → s2 t5 | 5 | 28t | - | edfebd, pt | sprint+jerryx2+bonzo |
| s2 t3 → ee2 | 1 | 1307t | - | 207123, pt | sprint+bonzox42+jerryx6+lava-contact |
| s2 t3 → s2 mid low | 1 | 7t | - | 207123, pt | sprint |
| s2 t3 → s2 right lever | 9 | 37t | 37t | a72d42, settled | sprint+lava-contact |
| s2 t3 → s2 t2 | 5 | 40t | - | 3e7a14, pt | sprint+lava-contact |
| s2 t3 → s2 t4 | 1 | 39t | 39t | 1a6c46, settled | sprint |
| s2 t3 → s2 t5 | 5 | 39t | - | 1762cc, pt | sprint+lava-contact |
| s2 t4 → s2 left lever | 1 | none | - | 3066a9, settled | leap only: sprint+teleport(DUNGEONBREAKER)+lava-contact |
| s2 t4 → s2 t1 | 1 | 43t | 43t | edfebd, settled | walk+bonzo |
| s2 t4 → s2 t5 | 1 | 91t | - | 1a6c46, pt | walk+lava-contact |
| s2 t5 → ee2 | 2 | 65t | - | edfebd, pt | walk+jerryx5+lava-contact |
| s2 t5 → s2 high path | 1 | none | - | edfebd, pt | leap only: leap |
| s2 t5 → s2 left lever | 4 | 41t | - | edfebd, pt | sprint+jerryx3+bonzo |
| s2 t5 → s2 mid high | 3 | 13t | - | edfebd, pt | sprint |
| s2 t5 → s2 right lever | 14 | 8t | - | edfebd, pt | sprint |
| s2 t5 → s2 t2 | 7 | 15t | - | edfebd, pt | sprint |
| s2 t5 → s2 t3 | 17 | 19t | 25t | edfebd, pt | walk |
| s2 t5 → s2 t4 | 2 | 22t | - | edfebd, pt | sprint |

### S3

| From → To | Legs | On-foot PB | Settled PB | Run, start | Method |
|---|---|---|---|---|---|
| arrows → s3 left lever | 1 | 167t | - | 1a6c46, pt | walk+lava-contact |
| arrows → s3 t3 | 3 | none | - | edfebd, pt | leap only: walk+leap |
| ee3 → s3 t1 | 4 | 4t | - | ae3d84, pt | sprint |
| ee3 → s3 t3 | 2 | 45t | - | afb98f, pt | sprint+bonzo+lava-contact |
| ee3 → s3 t4 | 3 | 44t | 47t | 1a6c46, pt | sprint+lava-contact |
| s3 left lever → arrows | 1 | 134t | 134t | 1a6c46, settled | sprint+lava-contact |
| s3 left lever → s3 right lever | 6 | 6t | - | 1a6c46, pt | walk+bonzo |
| s3 left lever → s3 t1 | 1 | none | - | 3ee9c0, pt | leap only: walk+leap+lava-contact |
| s3 left lever → s3 t3 | 1 | none | - | edfebd, pt | leap only: walk+leap+bonzo+jerry |
| s3 left lever → s3 t4 | 1 | 148t | - | 1a6c46, pt | walk+lava-contact |
| s3 right lever → s3 left lever | 1 | 17t | - | 1a6c46, pt | sprint+bonzox2 |
| s3 right lever → s3 t2 | 1 | none | - | b3d41a, pt | leap only: walk+etherwarp/AOTVx4 |
| s3 right lever → s3 t3 | 3 | 186t | - | 207123, pt | sprint+jerryx2+bonzox6+lava-contact |
| s3 t1 → ee3 | 1 | 6t | - | afb98f, pt | sprint |
| s3 t1 → s3 left lever | 1 | none | - | 6c45a1, pt | leap only: no-input+leap+lava-contact |
| s3 t1 → s3 right lever | 1 | none | - | ae3d84, pt | leap only: walk+leap+lava-contact |
| s3 t1 → s3 t3 | 1 | 142t | - | 1a6c46, pt | walk+bonzo+jerryx2+lava-contact |
| s3 t2 → s3 right lever | 2 | 147t | - | 92d73f, pt | sprint+bonzox2+lava-contact |
| s3 t2 → s3 t3 | 3 | none | - | b3d41a, settled | leap only: etherwarp/AOTV |
| s3 t2 → s3 t4 | 2 | 23t | 23t | b3d41a, settled | sprint |
| s3 t3 → arrows | 5 | 29t | - | edfebd, pt | sprint+bonzo |
| s3 t3 → s3 left lever | 2 | 68t | 68t | edfebd, settled | sprint+bonzox2 |
| s3 t3 → s3 right lever | 1 | 91t | 91t | edfebd, settled | walk+bonzox3+lava-contact |
| s3 t3 → s3 t1 | 2 | 26t | - | edfebd, pt | sprint |
| s3 t3 → s3 t2 | 5 | 16t | 16t | b3d41a, settled | sprint |
| s3 t3 → s3 t4 | 5 | 32t | 36t | edfebd, pt | sprint+bonzo |
| s3 t4 → s3 left lever | 7 | 31t | - | ed1c25, pt | sprint+lava-contact |
| s3 t4 → s3 t3 | 2 | 97t | - | edfebd, pt | walk+lava-contact |

### S4 + core

| From → To | Legs | On-foot PB | Settled PB | Run, start | Method |
|---|---|---|---|---|---|
| ee4 → s4 left lever | 1 | 95t | 95t | edfebd, settled | walk+lava-contact |
| ee4 → s4 t1 | 11 | 16t | 17t | 207123, pt | sprint |
| ee4 → s4 t2 | 1 | none | - | ed1c25, pt | leap only: sprint+teleport(DUNGEONBREAKER)+lava-contact |
| ee4 → s4 t3 | 12 | 17t | 17t | 207123, settled | sprint |
| ee4 → in core | 66 | 1t | 3t | 3066a9, pt | sprint |
| s4 left lever → s4 t1 | 1 | 647t | - | 1a6c46, pt | sprint+bonzox19+lava-contact |
| s4 left lever → in core | 5 | none | - | 3ee9c0, pt | leap only: walk+leap |
| s4 right lever → ee4 | 1 | 48t | - | 207123, pt | sprint+bonzox2 |
| s4 right lever → s4 left lever | 7 | 6t | - | 1a6c46, pt | sprint |
| s4 right lever → in core | 4 | none | - | a75187, settled | leap only: leap |
| s4 t1 → ee4 | 8 | 25t | - | 207123, pt | sprint+jerry+bonzo |
| s4 t1 → s4 t2 | 4 | 9t | - | 3d6f29, pt | sprint |
| s4 t1 → s4 t3 | 4 | 225t | - | 1a6c46, pt | walk+bonzo+lava-contact |
| s4 t1 → s4 t4 | 1 | none | - | 2b8212, pt | leap only: walk+leap+lava-contact |
| s4 t2 → ee4 | 1 | 48t | - | 207123, pt | walk |
| s4 t2 → s4 t1 | 2 | 51t | - | 1a6c46, pt | walk |
| s4 t2 → s4 t3 | 1 | none | - | 3d6f29, settled | leap only: sprint+leap+bonzo+lava-contact |
| s4 t2 → s4 t4 | 1 | 91t | 91t | 7130bd, settled | walk+jerry+lava-contact |
| s4 t2 → in core | 1 | none | - | ed1c25, settled | leap only: no-input+leap+lava-contact |
| s4 t3 → s4 right lever | 12 | 23t | - | edfebd, pt | sprint+lava-contact |
| s4 t3 → s4 t1 | 1 | 146t | - | 1a6c46, pt | walk+bonzo+lava-contact |
| s4 t3 → s4 t4 | 1 | 729t | - | 1a6c46, pt | walk+lava-contact |
| s4 t3 → in core | 4 | none | - | a72d42, pt | leap only: leap |
| s4 t4 → ee4 | 1 | 31t | - | 207123, pt | sprint |
| s4 t4 → s4 right lever | 1 | 172t | - | 1a6c46, pt | sprint+lava-contact |
| s4 t4 → s4 t3 | 3 | 21t | - | 2b8212, pt | sprint |
| s4 t4 → in core | 5 | 3t | - | 1a6c46, pt | sprint |
| in core → ee4 | 75 | 1t | 3t | 182db7, pt | sprint |
| in core → s4 left lever | 1 | 429t | - | 1a6c46, pt | walk+bonzo+lava-contact |
| in core → s4 t4 | 5 | 4t | 4t | 1a6c46, settled | sprint |

Cross-section legs are in pb_summary.csv.
