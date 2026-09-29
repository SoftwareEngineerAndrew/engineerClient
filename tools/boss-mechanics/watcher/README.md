# boss-mechanics/watcher

Scripts behind `docs/mechanics/watcher.md`: the Watcher (F7 blood room) measured from Better PF
recordings. Python 3 standard library only.

## Data

`DATA_DIR` is laid out like the site's API gives it (same as `tools/boss-movement`):

```
DATA_DIR/runs.json      the run list (id, group, party, ...); recordings of one run share `group`
DATA_DIR/ids.txt        optional: the recording ids to use (default: every one in runs.json)
DATA_DIR/runs/<id>.gz   each recording (gzip or xz, despite the name)
```

## Running

`OUT_DIR` is any scratch directory; nothing is written to `DATA_DIR`.

```
python3 extract.py DATA_DIR OUT_DIR [--jobs N] [--force]   # 1. blood-room window per recording -> OUT_DIR/extract/ (~7 min for 278 on 6 cores)
python3 runs.py DATA_DIR OUT_DIR                          # 2. one server-tick timeline per run -> OUT_DIR/runs.json
python3 analyze.py OUT_DIR [data dialogue mobs watcher end fastest]   # 3. the report's numbers
python3 sim.py [--runs N] [--move T] [--pdouble P]         # 4. Monte Carlo of the split floor
```

## Files

- `wlib.py` - reading recordings, server-tick clock (`st` lines), de-interpolation of the
  client's 3-step lerp, the Watcher's chat lines, mob names, excluded (alpha) recordings.
- `extract.py`, `runs.py` - steps 1-2. `runs.py` merges siblings: chat lines align the timelines,
  entity ids (the server's) merge the wall skulls, names merge the blood mobs.
- `model.py` - per-run events: the Watcher's legs, skull launches, blood mob spawns and deaths.
- `analyze.py`, `sim.py` - steps 3-4.

## Caveats

- Timing uses only runs whose every recording has `st` lines (152 of 218 runs).
- Other players' and far entities' positions are the recorder's interpolated view; skull and
  Watcher positions are de-lerped (`wlib.delerp`), spawn times of player-shaped blood mobs are the
  tick the recorder first saw them (a mob that spawned out of view is dropped, not guessed).
- A mob's death is its removal minus 20 server ticks (measured 19-21 from the 0-health name tag).
- `20260928-204121-d4bd4083` is left out as suspected alpha (same server as an alpha recording and
  the alpha-only one-line dialogue).
