# boss-mechanics/storm

Scripts behind `docs/mechanics/storm.md`: Storm's (F7 phase 2) pads and pillars, lightning and
other attacks, movement after the flight to Yellow, the pin, taunts and death. They are measured
from Better PF recordings. Python 3 standard library only.

They build on `tools/boss-movement` (Storm's de-lerped track, head yaw, player positions, the
server-tick timelines): run its first two steps first.

## Data

`DATA_DIR` is laid out as for `tools/boss-movement`: `runs.json`, optional `ids.txt`,
`runs/<id>.gz`. `OUT_DIR` is any scratch directory, shared with boss-movement.

## Running

```
python3 tools/boss-movement/extract.py DATA_DIR OUT_DIR       # boss-movement step 1
python3 tools/boss-movement/tracks.py OUT_DIR                 # boss-movement step 2 -> OUT_DIR/tracks
python3 tools/boss-mechanics/storm/extract.py DATA_DIR OUT_DIR   # -> OUT_DIR/storm-mech/<id>.pkl
python3 tools/boss-mechanics/storm/mech.py OUT_DIR [pillars pads lightning taunts later pin death]
python3 tools/boss-mechanics/storm/mech.py OUT_DIR geometry DATA_DIR   # the pads' blocks
```

- `extract.py` keeps each recording's Storm window: all chat, player entries with held items, arm
  swings and arena block changes. It takes about 7 minutes on 4 cores for 278 recordings and skips
  recordings already extracted.
- `mech.py` joins those with the tracks and caches the result in `OUT_DIR/storm-mech/runs.pkl`.
  Delete that file after re-extracting. Each section then takes seconds and prints the numbers the
  doc quotes.

## Files

- `stormlib.py`:
  - joining runs, events on Storm's timeline (server ticks after his first line);
  - pillar bottoms from block changes (a layer counts once 20 of its 37 blocks are solid), descents,
    resets, pillar states;
  - pad squares, the crush rule of `docs/storm-crush.md` (`rule_at`), phase segments after the
    lightning;
  - the recorder-Mage's on-target beams (arm swings with the view within 3° of Storm, ≤ 45 blocks).
- `extract.py`, `mech.py`: the steps above.

## Caveats

- Timing uses only runs whose every recording has server ticks (`st` lines). Alpha-server runs
  (`recording.ALPHA_RUNS`) are left out.
- Pillar heights come from the recording with the most arena block lines; a recorder far away may
  miss a layer, so single odd values happen.
- Other players' positions are the recorder's (lerped, sometimes stale) view. Only recorders'
  own positions are exact, so the pad test uses recorders only.
- Nothing about health is recorded. The pin and death sections can show when "enraged"/death
  follows a hit, not the damage itself.
