# ADS data packs

These six files are the recorded data behind the Advanced Dungeon Simulator's full run: the room library, the dungeon layouts, the mob
tables, the run-state tables and the trap timings. They are built from recordings by tooling that is kept outside this repository, and
copied in here with `tools/bundle-ads-packs.sh <folder with the built files>`.

| File | Holds |
|---|---|
| `rooms.index.json.gz` and `rooms.blocks.bin` | every room design: its blocks, doors, secrets and features (the two files belong together) |
| `layouts.json.gz` | the recorded dungeon layouts |
| `mobs.json.gz` | mob kinds, health and where they spawn |
| `runstate.json.gz` | door, key, timing and score tables |
| `traps.json` | trap timings |

They ship inside the mod jar and load from there. To try a different build without rebuilding the mod, put a file of the same name in
`<game folder>/config/engineerclient/ads/packs/`; that copy wins for that file (put both room files together). `/ads dev packs` says where
each file came from.
