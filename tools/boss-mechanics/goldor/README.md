# boss-mechanics/goldor

Scripts behind `docs/mechanics/goldor.md`: F7 phase 3 (the terminal sections) and Goldor, from
"Who dares trespass into my domain?" to Necron's first line, measured from Better PF recordings.
Python 3 standard library only.

## Data

Same layout as `tools/boss-movement`:

```
DATA_DIR/runs.json          the site's run list (id, group, party, cleared, ...)
DATA_DIR/ids.txt            optional: the run ids to use (default: every run in runs.json)
DATA_DIR/runs/<id>.gz       each recording (gzip or xz, despite the name)
```

`OUT_DIR` is any scratch directory; nothing is written to `DATA_DIR`.

## Running

```
python3 extract.py DATA_DIR OUT_DIR [--jobs N]  # 1. each recording's Goldor window -> OUT_DIR/extract/ (~5 min, 278 runs, 4 cores)
python3 doors.py DATA_DIR OUT_DIR               # 2. the section doors' barrier blocks -> OUT_DIR/doors.json
python3 sections.py OUT_DIR                     # 3. per-recording section timeline -> sections.json; section stats
python3 deathtick.py OUT_DIR                    # 4. the 60-tick death tick and who it hits
python3 goldor.py OUT_DIR                       # 5. Goldor's track, speeds, catch-up, flight, death -> goldor_packets.json
python3 splits.py OUT_DIR                       # 6. split distributions and the fastest runs -> splits.json
python3 roles.py OUT_DIR [--restarts N --iters N] # 7. static terminal roles: measurements + schedule search -> roles.json (~10 min)
```

`extract.py` skips recordings whose extract is newer than the recording.

## Files

- `glib.py` - reading recordings, the phase's chat lines, server-tick clocks; imports `Clock` and
  `delerp` (mob de-interpolation) from `tools/boss-movement/bosslib.py`.
- `extract.py` - the window (300 ticks before Goldor's first line to 100 after Necron's), with
  chat, every block change, GUI/click/use/teleport lines, party positions, entities (withers with
  their whole history, so de-lerping starts at a spawn).
- `doors.py` - reads the barrier blocks of the three section doors from a recording that captured
  the boss arena (`lib` lines); the doors turning to air time each section's end in every recording.
- `sections.py` - completion lines, doors, gates, the core door; section contents and counting,
  the gate/door rule. `active_section()` is used by the later steps.
- `deathtick.py`, `goldor.py`, `splits.py` - the steps above.
- `roles.py` (with `roledata.py`: per-recording job timelines, the arena's numbered stations;
  `rolemeasure.py`: solve / open / lever / gate / leap / device / walking times from the fastest
  runs; `roleplan.py`: the schedule model and the plan search) - behind
  `docs/mechanics/terminal-roles.md`.

## Caveats

- The four alpha-server recordings (`glib.ALPHA_RUNS`) are left out; timing uses only recordings
  with server ticks (`st` lines), `n` = server ticks after the tick "Who dares trespass" arrived.
- Hypixel's `(n/n)` completion lines are hidden by a chat cleaner in many recordings; section ends
  therefore come from the door blocks. Goldor's "section complete" reaction lines are not a timer
  (often delayed to his 62-tick dialogue slot).
- Goldor is visible only within ~45-60 blocks of the recorder, other players' positions are the
  recorder's stale view, and the recorder's own position is the only exact one; the scripts say
  which they use.
- Goldor's health, hits on him and the boss bar are not in the recordings.
