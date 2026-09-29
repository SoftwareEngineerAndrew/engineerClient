# boss-mechanics/necron

Scripts behind `docs/mechanics/necron.md`: Necron (F7 phase 4), from his first line to "All this,
for nothing..." and the end-of-run stats, measured from Better PF recordings. Python 3 standard
library only. They reuse `tools/boss-movement/` (`recording.py` for reading recordings and the
alpha-server list, `bosslib.delerp` for undoing the client's 3-tick position interpolation).

## Data

`DATA_DIR` is laid out as for `tools/boss-movement` (see its README): `runs.json`, optional
`ids.txt`, and `runs/<id>.gz`. `OUT_DIR` is any scratch directory; nothing is written to `DATA_DIR`.

## Running

```
python3 extract.py DATA_DIR OUT_DIR [--jobs N]   # 1. each recording's Necron window -> OUT_DIR/extract/<id>.json
python3 events.py OUT_DIR                       # 2. one row of events per recording -> OUT_DIR/events.json
python3 analyze.py OUT_DIR [section ...]        # 3. the report's numbers
```

Sections of `analyze.py`: `data dialogue movement dps attacks floor end fastest` (default: all).
`extract.py` skips recordings whose extract is newer than the recording (about 5 minutes for 278
recordings on 4 cores); the other two steps take seconds.

## Files

- `necronlib.py` - chat lines, Necron's wither id (the wither whose name-tag stand, id + 1, says
  Necron, or the wither that appears exactly at mid (54, 66, 76)), server-tick clock, de-interpolated
  track, trips off mid (`excursions`), small stats helpers.
- `extract.py` - window from 300 client ticks before the first line to 400 after "All this, for
  nothing...": chat, entities (spawn/move/gone), name changes, block changes (without the
  sea-lantern/iron-block light show, which is only counted per tick), players, own teleports,
  arm swings. Every line gets `n`, the server tick of the last `st` line before it in file order.
- `events.py` - per recording: Necron's lines, Goldor's lines, spawn/despawn, trips off mid
  (leave tick L, back-at-mid tick B, path), fireball/skull/TNT spawns (distance from mid and from
  Necron), lava-platform and Goldor-core floor removals, the "Team Score" line, Nuclear Frenzy hits.
- `analyze.py` - the numbers. One recording per run (the one with server ticks and the most
  Necron packets); timing statistics use only runs with server ticks.

## Caveats

- Alpha-server recordings (`recording.ALPHA_RUNS`) are left out.
- 82 recordings (63 runs) have no `st` lines; they count for positions and platform choice, not
  for timing.
- Chat lines are +-1 tick; a trip's L and B are the ticks the recording received the move /
  teleport packet (+-1-2 against the server).
- Other players' positions are the recorder's interpolated view; only the recorder's own are exact.
