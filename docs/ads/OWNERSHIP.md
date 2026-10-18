# ADS full run: who owns which files

One owner per file. If you need a change in a file you do not own, put the exact change in your final report under "integration
requests" (file, what, why, the code); the integrator applies it. Prefer a hook in your own files. The architect commits the skeleton;
after it, only the integrator commits on `ads/run`, from the work items' branches `ads/<key>` (one worktree each).

## 1. The work items (the table from the task; additions made by the skeleton review are marked _(review)_)

| key | owns |
|---|---|
| engine | Owns `src/p3sim/run/**` (after the skeleton), and edits to EXISTING files `P3Sim.kt`, `Sim.kt`, `SimWorld.kt`, `SimServer.kt`, `Arena.kt`, `ArenaFixes.kt`, `Fight.kt`, `Phases.kt`, `Spots.kt`, `Practice.kt`, `Stats.kt`, `Recorder.kt`, `P3Party.kt`, `SimParty.kt`, `Party.kt` (Party only where the Run needs it). |
| layout | Owns `src/p3sim/clear/layout/**`. |
| doors | Owns `src/p3sim/clear/state/**`. |
| hud | Owns `src/p3sim/clear/hud/**`. |
| mobs | Owns `src/p3sim/clear/mobs/**`. |
| damage | Owns `src/p3sim/clear/damage/**`, and edits to EXISTING files `SimItems.kt` _(review: the Spirit Leap and Haunt target lists included; while a run is in a dungeon stage they ask `Services.bots.leapTargets`, ARCHITECTURE 8)_, `Bows.kt`, `Mage.kt`, `Masks.kt`. (`GhostPlayer.kt` is the recorded-run replay bot and `GhostGuard.kt` the ghost-block fixer, unrelated to death ghosts: no damage change is needed there.) |
| secrets | Owns `src/p3sim/clear/secrets/**`, `src/p3sim/clear/score/**`. |
| bots | Owns `src/p3sim/clear/bots/**` (reuse `Party`/`Roles` APIs read-only, request any change). |
| puzzles | Owns `src/p3sim/clear/puzzles/**` except `src/p3sim/clear/puzzles/hard/**`. |
| traps | Owns `src/p3sim/clear/puzzles/hard/**`, `src/p3sim/clear/traps/**`. |
| blood | Owns `src/p3sim/clear/blood/**`. |
| plug-in | Owns the optional plug-in mod's own repository only, which is not part of this repository. Never pushes. |
| savepoints | Owns `src/p3sim/clear/ui/**` (reads `SimRestartScreen`, requests edits rather than editing it). |

## 2. Skeleton files and who owns them after the architect

"Frozen" means the public signatures are a contract that other work items compile against. The owner may change bodies and add members that have
default bodies (interfaces) or are new (classes), but may not change or remove an existing public signature or event field without an
integration request to the integrator. "Free" means the owner may reshape it.

### `src/p3sim/run/`

| file | owner | status |
|---|---|---|
| `RunStage.kt`, `RunConfig.kt`, `RunEvents.kt`, `RunBus.kt`, `RunSystem.kt` (`RunSystem`, `AdsModule`, `AdsModules`), `DungeonPlan.kt`, `RunState.kt` (record shapes and `setCheck`), `WorldRegions.kt`, `Savepoints.kt` (the types), `plugin/*.kt`, `api/*.kt` | engine | frozen (additive only; `RunEvents.kt`: new event types by request) |
| `Run.kt`, `Runs.kt`, `AdsBootstrap.kt`, `AdsCommands.kt` (internals), `ClientRun.kt` (internals), `RunState.snapshot/restore` | engine | free inside the frozen signatures; fills the stubs marked "engine fills" |
| `LayoutPick.kt` (pure; `LayoutPick.pick` is the one place a `LayoutSource` becomes a layout) | engine | frozen signature |
| `RunLauncher.kt` (`launch` is the engine's stub to fill; `pending`, `setPending`, `takePending`, `cancel` are the contract), `RunInput.kt` (the router; the four `AdsModule` hooks it calls are frozen), `RunBlocks.kt` | engine | frozen signatures |
| `EngineModule.kt` | engine | free |
| new files under `run/` (arena region, lag layer, savepoint store, spikes) | engine | free |

### `src/p3sim/pack/`

| file | owner | status |
|---|---|---|
| `rooms/**` (`RoomPack.kt`, `RoomVolume.kt`, `Placement.kt`) and `layouts/**` (`LayoutPack.kt`) | layout | bugs found against the real packs are the layout item's to fix; signatures frozen as above |
| `mobs/**` (`MobPack.kt`) | mobs | same |
| `runstate/**` (`RunStatePack.kt`, `Dist.kt`) | doors | same |
| `Json.kt`, `PackTypes.kt`, `Packs.kt`, `PackSource.kt`, `DungeonFrame.kt`, `DoorModel.kt` | integrator | additive requests only |
| `rooms/OdinCore.kt` | layout | frozen (it is checked against the built pack by `RealPackTest`) |

### `src/p3sim/clear/<area>/<Area>Module.kt` (the empty module objects)

Each is owned and filled by the work item that owns its folder: `layout/LayoutModule` (layout), `state/DoorsModule` (doors), `hud/HudModule` (hud),
`mobs/MobsModule` (mobs), `damage/DamageModule` (damage), `secrets/SecretsModule` and `score/ScoreModule` (secrets), `bots/BotsModule` (bots),
`puzzles/PuzzlesModule` (puzzles), `puzzles/hard/HardPuzzlesModule` and `traps/TrapsModule` (traps), `blood/BloodModule` (blood), `ui/UiModule`
(savepoints). Keep the `object` name, `id` and the interfaces it implements; everything else is theirs. `AdsModules.all` is never edited.

### Elsewhere

| file | owner |
|---|---|
| `src/p3sim/P3Sim.kt` | engine. The skeleton added three lines (see ARCHITECTURE section 12); keep them |
| `src/p3sim/Arena.kt` | engine. The skeleton added one line to `fill` (`WorldRegions.fillAll(chunk)` first); keep it |
| `build.gradle.kts` (JUnit 5, `test/` source set) | integrator. Extra test dependencies: integration request |
| `docs/ads/ARCHITECTURE.md`, `docs/ads/OWNERSHIP.md` | integrator. Corrections: integration request |
| `docs/ads/<key>.md` (optional design notes for your own module) | the work item `<key>` |
| `test/p3sim/run/**` | engine, except `PluginSeamTest.kt` and `TestPlans.kt` (integrator) |
| `test/p3sim/pack/**` | `RoomPackTest`, `PlacementTest`, `LayoutPackTest`, `OdinCoreTest`, `Fixtures`: layout; `MobPackTest`: mobs; `RunStatePackTest`: doors; `PacksTest`, `BundledPacksTest`, `DungeonFrameTest`, `DoorModelTest`, `SamplePackTest`, `RealPackTest`: integrator |
| `test/p3sim/<area>/**` | the work item that owns `src/p3sim/clear/<area>` |
| `src/mixin/Ads<Key><What>Mixin.java` (new files only; `<Key>` is the work item's key as in the table above) | the work item `<key>`; the line in `engineerclient.mixins.json` is an integration request |
| the rest of `src/mixin/**`, `src/resources/**` (including `fabric.mod.json` and the mixin lists) | nobody: integration requests |
| the optional plug-in's repository | plug-in only |

## 2b. Rules that close the gaps between the rows

- A pack folder belongs to the item in section 2; another item that needs a field its loader does not model reads the pack's `raw` JSON
  (`RunStatePack.section(name)`, `RunStatePack.raw`) instead of editing it. A real bug in a loader is an integration request to the integrator.
- Existing sim files that no row names (`Blocks.kt`, `Roles.kt`, `Loadouts.kt`, `Devices.kt`, `Terminals.kt`, `HotbarLayout.kt`...) are nobody's: a change there is an
  integration request. The run never uses `Blocks.set` or `Arena.set` (they belong to the boss arena); it uses `RunBlocks`.
- The module objects (`XxxModule`) are the work item's; the interfaces they implement (`api/*.kt`) are the integrator's, frozen for everyone.
- Nobody owns `src/p3sim/clear/` itself: a file at that level is an integration request; shared code a few items need goes through the integrator
  (duplicate a few lines in your own folder rather than wait).
- A work item's own design note is `docs/ads/<key>.md`; nothing else under `docs/`.

## 3. Reminders

- Never push. Never touch `main` or the `mc/*` branches. Never copy a jar into a launcher instance. Never run `deploy.sh`.
- Build with the serialising build script kept outside this repository (it takes the worktree path) only; your branch must build (tests included) before you finish.
- This repository is public: no player data and no strategy findings anywhere; mechanisms are fine.
