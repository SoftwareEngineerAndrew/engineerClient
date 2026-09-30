# Simon Says (F7 P3, S1 device)

Scripts behind [docs/mechanics/simon-says.md](../../../docs/mechanics/simon-says.md). Node, no
dependencies. They expect every F7 run from undonecoffee.com, one recording per run (the first of
each group), decompressed to `runs/<id>.jsonl`, in the directory they run in:

    curl -s https://undonecoffee.com/betterpf/api/runs > runs.json
    # one id per group, then for each: curl .../betterpf/api/runs/<id> | xz -dc > runs/<id>.jsonl

1. `ssextract.mjs` - every run's Simon Says events (lights, buttons, presses, your own clicks,
   device chat) to `ss.json`; `ssextract2.mjs` - who stood at the device, to `near.json`.
2. `stats.mjs` - the mechanics: show shapes, light and button timings, wrong presses.
3. `times2.mjs` - completion times per player (to `times.json`); `phases.mjs` - split into start,
   first show and rounds 3-5.
4. `gen.mjs` + `world.mjs` - the device's blocks from the arena capture (`Boss|F7|6,5` and its
   neighbours, from `/betterpf/api/room?key=`), as SS Practice's `PALETTE` / `CELLS`.
