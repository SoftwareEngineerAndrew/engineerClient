# Secret spawn ranges: the analysis

Rebuilds `src/resources/assets/engineerclient/ads/secret-spawns.json` and the table in `docs/ads/secret-spawns.md` (what they say and
how: `docs/ads/secret-spawns.md`) from the public ADR recordings. Node 20+, `xz` and `gzip` on the PATH. Run from an empty data folder
outside the repo (the scripts read and write the current folder):

    node <tools>/list.mjs 2026-10-01                     # list-all.json: every public run, a day at a time back to that date
    node <tools>/extract.mjs list-all.json ex 6          # ex/<id>.json.gz: players' paths, item/bat spawns, skulls (no run file kept)
    node <tools>/sites.mjs <rooms.index.json.gz> ex sites.json        # clusters every secret site and fits its radius
    node <tools>/export.mjs sites.json secret-spawns.json ex          # the repo's file
    node <tools>/table.mjs secret-spawns.json <repo>/docs/ads/secret-spawns.md

`<rooms.index.json.gz>` is the bundled rooms pack (`src/resources/assets/engineerclient/ads/packs/`): sites are matched to its secret ids
in its canonical frame. `extract.mjs` takes only runs that started in the last week (older ones open 30 a day); about 100 KB a run kept.

Checks behind the rule (same arguments as `sites.mjs`, plus an optional recording limit):

- `tpdelay.mjs`: ticks from the recorder teleporting close to the item appearing (1 to 5 about equally: a 5-tick check).
- `direction.mjs`: each site's radius fitted separately per side players come from (a sphere: within about a block).
- `fit.mjs`: which distance (3D from the feet / eyes, sideways, box) the spawns agree with best.

Files: `decode.mjs` reads ADR run files (the viewer's unpacking of "cols", "grp" and "blocks" lines), `common.mjs` places rooms
(ADS's placement law) and reads players' paths, `events.mjs` finds first sights, `fit.mjs` bounds and fits a radius.
