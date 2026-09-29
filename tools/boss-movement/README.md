# boss-movement

Scripts behind `docs/maxor-storm-movement.md`: how Maxor (F7 P1) and Storm (F7 P2) move,
measured from Better PF recordings. Python 3 standard library only.

## Data

A data directory laid out like the one the site's API gives:

```
DATA_DIR/runs.json          the site's run list (id, group, party, cleared, ...)
DATA_DIR/ids.txt            optional: the run ids to use (default: every run in runs.json)
DATA_DIR/runs/<id>.gz       each recording (gzip or xz, despite the name)
```

Fetch a recording with `curl -sS -o DATA_DIR/runs/<id>.gz https://undonecoffee.com/betterpf/api/runs/<id>`
(public API, rate-limited per IP: keep to about 4 requests at a time).

## Running

`OUT_DIR` is any scratch directory; the scripts only read `DATA_DIR`.

```
python3 extract.py DATA_DIR OUT_DIR [--jobs N]   # 1. each recording's Maxor/Storm window -> OUT_DIR/extract/
python3 tracks.py OUT_DIR                       # 2. one timeline per run (siblings merged) -> OUT_DIR/tracks/
python3 analyze.py OUT_DIR [data maxor storm fastest]   # 3. the report's numbers
python3 fit.py OUT_DIR [storm|maxor|all]        # 4. movement model fits -> OUT_DIR/fit_results.json
```

`extract.py` skips recordings whose extract is newer than the recording, so re-running after
downloading more runs only processes the new ones. It takes about 3 minutes for 278 recordings
on 4 cores; the other steps take 1-2 minutes each.

## Files

- `recording.py` - reads a recording; the chat lines that mark the phases.
- `bosslib.py` - timelines (client ticks `t`, server ticks `n` from `st` lines, sibling
  alignment on shared chat lines), boss identification, **de-interpolation** (recovering the
  positions the server sent from the client's 3-step lerp), crusher heights, pillar zones,
  player/ghost helpers.
- `extract.py`, `tracks.py`, `analyze.py`, `fit.py` - the steps above.
- `dynamics.py` - the movement models (pursuit, inertia, vanilla 1.8 wither) and the
  multi-step prediction fitter.

## Caveats

- Positions in the recordings are the client's interpolated ones. `bosslib.delerp` undoes the
  lerp; it assumes a modern client's 3-step `InterpolationHandler`, which checks out: Maxor's
  recovered positions land on the 1/32-block grid of the 1.8 protocol.
- 85 recordings (0.6.11: 47, 0.6.12: 29, early 0.6.13: 9; nothing after 2026-09-24 22:35 is
  affected) have no `st` (server tick) lines. Their runs are on client ticks, which run ahead
  of the server while it lags. Timing statistics use only runs whose every recording has server
  ticks.
- The crush rule itself (checks every 20 ticks, the 6x6 zone, head height, "stepped down within
  60 ticks") comes from `docs/storm-crush.md` / `tools/storm-crush/`; these scripts use it but do
  not re-derive it.
