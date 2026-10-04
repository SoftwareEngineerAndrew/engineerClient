# maxor/bossrec

Maxor (F7 P1) from **Boss Recorder** files (`docs/boss-recorder.md`), behind
`docs/mechanics/maxor-alpha.md`. Python 3 standard library only. Reads the recordings straight
from the "26.1.2 BRW" Prism instance (`mlib.BOSS`) and tells alpha / main / p3sim runs apart from
the same instance's `logs/` (`Connecting to <host>` lines; p3wr's own recordings only).

```
MAXOR_OUT=/tmp/maxor python3 extract.py [SINCE]   # per-run timelines -> $MAXOR_OUT/<run>.json (SINCE: yyyy-mm-dd)
MAXOR_OUT=/tmp/maxor python3 cycle_table.py       # the crystal cycle run by run
MAXOR_OUT=/tmp/maxor python3 losses.py            # each carrier's trip: pickup, leap, at the pylon, placed
MAXOR_OUT=/tmp/maxor python3 ledger.py            # ticks lost against the floor, by cause
MAXOR_OUT=/tmp/maxor python3 placerule.py alpha   # every check a carrier was near a free, open pylon: placed or not
MAXOR_OUT=/tmp/maxor python3 carriers.py alpha    # each carrier's distance to the pylon on every check
```

- `mlib.py` - reading a file, **the corrected server clock** (`Run.srv`: the recorder's `n` counts
  Hypixel's anticheat ping pairs too, so it is rebuilt from `gameTime`), the Maxor window, which
  server a recording was made on.
- `extract.py` - Maxor's window as events: chat (intro, pickups, placements, charging, stuns,
  enrage), end crystals added / removed by place (top W/E, pylon W/E), pylon pressure plates, the
  beam column (73, 221-224, 73), the boss bar, Maxor's synced health (1 / 1000 = damageable),
  Maxor's and every player's positions.
- Other players' positions are the server's own packets; p3wr's are what his client sent, about a
  round trip earlier than the server saw them, so the placement fits leave him out.
