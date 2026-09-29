# boss-mechanics/maxor

Scripts behind `docs/mechanics/maxor.md`: how Maxor (F7 P1, "WELL! WELL! WELL! LOOK WHO'S
HERE!" until Storm's first line) works, measured from Better PF recordings. Python 3 standard
library only.

## Inputs

- `DATA_DIR`: the recordings, laid out as for `../../boss-movement` (`runs.json`, optional
  `ids.txt`, `runs/<id>.gz`; see its README for downloading).
- `MOVE_OUT`: the output directory of `../../boss-movement/extract.py` + `tracks.py`. Its
  `tracks/<group>.json` give every run on one server-tick timeline and Maxor's de-lerped move
  packets; these scripts reuse them (and `bosslib.py` / `recording.py` from there).

## Running

```
python3 ../../boss-movement/extract.py DATA_DIR MOVE_OUT     # if not done already
python3 ../../boss-movement/tracks.py MOVE_OUT
python3 extract.py DATA_DIR MAXOR_OUT [--jobs N]              # Maxor's window of each recording -> MAXOR_OUT/extract/
python3 analyze.py MAXOR_OUT MOVE_OUT [section ...]           # the report's numbers
```

Sections: `data timeline grid crystals laser stun death targeting movement fastest` (default: all).
`extract.py` takes about 3 minutes for 278 recordings on 4 cores and skips recordings whose
extract is newer; `analyze.py` about half a minute.

## Files

- `extract.py`: per recording, from 200 client ticks before Maxor's first line to 100 after
  Storm's: server ticks (`st`), wall clock (`time`), chat, party positions, withers, end crystals,
  the phase's named armor stands, damage-number stands, and the block changes of Maxor's arena
  (without the conveyor-belt animation). Alpha-server recordings (`recording.ALPHA_RUNS`) are
  skipped.
- `maxorlib.py`: a run on the reference recording's server-tick timeline (`Run`): chat lines,
  wall-clock time of a server tick (`ms`), the laser column's block changes (`column`), this run's
  10-tick check residue (`grid`), end crystals, laser hits from their discharge signature
  (`hits`), Maxor's position (`maxor_at`, `pos_interp`), when he is free to move (`free_spans`).
- `analyze.py`: every number in the report, section by section.

## Caveats

- Timed statistics use only runs whose every recording has `st` lines (server ticks); older
  recorders only have client ticks.
- The stun cooldown is in wall-clock time. It is measured from the recorders' `time` lines
  (every 20 client ticks), so it is good to about 0.05-0.1 s.
- Maxor's health is not in the recordings (no boss bar, no metadata; damage-number stands only
  appear near the recorder). What ends a stun and what kills him are therefore inferred from
  timing (off the 10-tick grid, faster with more players), not seen.
- Other players' positions are the recorder's view (interpolated, stale when out of range).
  Movement laws use only targets whose own recording is in the run.
