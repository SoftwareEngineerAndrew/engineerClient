# Vendored code — update procedure

This package is a renamed copy of Odin's DungeonWaypoints module, kept file-for-file
parallel to upstream so an Odin update is a re-diff, not a rewrite.

**Upstream pin: Odin 0.3.1 (release jar in `libs/`), source at
`odtheking/Odin` master `2e4ae96` (2026-08-23).** License: BSD 3-Clause.

| Ascent file | Upstream file(s) |
|---|---|
| `AscentWaypoints.kt` | `dungeonwaypoints/DungeonWaypoints.kt` |
| `AscentWaypointPacks.kt` | `dungeonwaypoints/DungeonWaypointPacks.kt` |
| `AscentWaypointEditor.kt` | `dungeonwaypoints/DungeonWaypointEditor.kt` + `DungeonWaypointHud.kt` |
| `AscentSecretWaypoints.kt` | `dungeonwaypoints/SecretWaypoints.kt` |
| `AscentPackFiles.kt` | `config/WaypointPackFileUtils.kt` + serializers from `config/DungeonWaypointConfig.kt` |

NOT vendored (reused from Odin as public API): `DungeonWaypoints.DungeonWaypoint` /
`WaypointType` (types — required so `drawBoxes` and the pack file format stay shared),
`TextPromptScreen`, `WaypointPackState`/`normalized`, `Module`/`Category`/settings,
the render helpers (`drawBoxes`/`drawStyledBox`/`drawText`/`textDim`), `Etherwarp.getEtherPos`,
`DungeonUtils`/`DungeonRoom`, `EventBus`.

Deliberate divergences from upstream (re-apply these when re-vendoring):
1. Packs live in `config/ascent/waypoints/`, module config in `config/odin/addons/ascent.json`.
2. Room waypoints live in `AscentWaypoints.roomWaypoints`, NOT Odin's `room.waypoints`
   field (that field is Odin's renderer's input — writing it would double-render).
3. `createPack` is silent when the pack exists (profiles ensure 40 packs per launch).
4. The editor stands down with a one-time warning when Odin's DungeonWaypoints edit
   mode is active, so one right-click never places into both systems.
5. `devMessage`/`modMessage` calls became `AscentMod.logger` / `AscentMod.chat`.

To update after an Odin release: diff each upstream file against its 0.3.1 version,
apply the same delta here, re-apply the divergences above, swap `libs/Odin-<ver>.jar`,
and update this pin.
