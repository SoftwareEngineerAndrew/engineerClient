# Engine work item: the Run container, the world regions, the lag layer, the spikes

This is the design note of the `engine` work item (stage 3). The contract is `ARCHITECTURE.md`; this file says how the engine
fills it and where the parts live. Everything is under `src/p3sim/run/` unless a path says otherwise.

## What a run is, in code

| piece | file | notes |
|---|---|---|
| `Run`, the stage machine | `Run.kt` | `beginBoss` walks the stages forward to BOSS_ENTRY, moves to BOSS, then calls `bossStarter` |
| the current run, start and stop | `Runs.kt` | `start(config, plan)` on the server thread; `stop` is idempotent; `onStopped` is the Minecraft-side cleanup hook |
| starting from the client | `RunLauncher.kt` | packs off-thread, plan, regions registered, world opened or rebuilt; `abort` takes a never-finished launch back |
| the hub's savepoint form | `RunStateJson.kt` | `RunState.snapshot/restore`; records keep their identity, no events are posted |
| savepoints | `RunSavepoints.kt`, `MinecraftSavepointWorld.kt` | order of a restore is tested (`RunSavepointsTest`) |
| the boss seam | `BossHandoff.kt`, `Fight.startRunBoss` | P1 starts as a menu start does, with the dungeon left standing and the gear left alone |
| the arena region | `ArenaRegion.kt`, `Arena.fillBlocks`, `ArenaFixes.patch` | clipped to x, z >= -8 while registered |
| view/simulation distance 6 | `ViewPin.kt`, `ChunkWindow.kt` | writes the client options (the integrated server copies them every tick) and gives them back when the run stops |
| the module | `EngineModule.kt` | human party records and positions, room savepoints, the end of the run, lag settings, persistent mobs |
| the lag layer | `Lag.kt` (model), `LagStats.kt` (evidence), `LagRuntime.kt` (the server thread) | works in the boss practice too |
| spikes and dev tools | `EngineSpikes.kt`, `EngineCommands.kt`, `StubDungeon.kt` | `/ads dev difficulty cost costnow lag launch stubrun stage boss savepoint savepoints restore runinfo` |

## Starting a run

1. Client thread: `RunLauncher.launch(config, reply)` loads the packs, `Services.layout.plan`, `Services.layout.region`,
   `registerDungeon` (the dungeon region and `ArenaRegion`), `setPending`, then `SimWorld.rebuild/open(forRun = true)`. The world is created on
   NORMAL when a launch is pending.
2. The world's chunks generate: `Arena.fill` calls `WorldRegions.fillAll`, which fills the dungeon and the clipped arena; the legacy arena
   pass is skipped while the arena region is registered.
3. Server thread, `Fight.join` of the host: `Runs.start`. NORMAL difficulty, persistent mobs (any `Mob` carrying the sim tag, in
   `EngineModule.onInit`), view and simulation distance 6, the lag the config asks for, a running clock at a random time of day, `Fight.setup(giveHotbar = false)` and the clear's
   hotbar for each human, the party at the middle of the entrance room, the `Run`, `begin`, and a savepoint labelled "Start".

## The world from regions

`Arena.fill` is unchanged for the boss practice. With a dungeon in the world `ArenaRegion` is registered, `ArenaRegion.active` is true, and:
the arena extension (`arena-ext.bin`) is clipped when the arena data is read (`ArenaFixes.patch`), `ArenaRegion.fill` writes the arena through
`Arena.fillBlocks`, and `ArenaRegion.resync` compares only the clipped box, and only after the boss has started (`markDirty`), because nothing in
the clear changes an arena block. A savepoint restore calls `WorldRegions.resyncAll`, so the dungeon region puts its own blocks back.
`WorldRegions.worldStopped()` runs at `SERVER_STOPPED`, so a run's regions never reach the next boss practice.

## Savepoints

`RunSavepoints.take` = `RunState.snapshot` + each system's `snapshot` + the humans' positions and every inventory slot (the game's own item
codec). `restore` runs: stage back, clock back (`Run.restoreClock`), `RunState.restore`, regions resync, `Sim.clearEntities`, each
module's `restore` in module order, the players, then `SavepointRestored`. Taken on the first entry of a human into each room
(`EngineModule`), by `Run.savepoint`, and once at the end of `Runs.start`. Only the dungeon stages can be saved or restored; the boss has its own
restart. At most 120 are kept (the first, "Start", is never dropped).

## The Odin bridge (`P3Sim.bridge`)

In the countdown, the clear and the blood room (`ClientRun.inClear`) the bridge writes only once, on entering: the area goes back to
`SinglePlayer` (Odin treats it as every area; setting `Dungeon` loses that), `inBoss = false`, the party lists emptied, and the floor set to F7 when
Odin has none. After that it writes nothing: Odin derives the rooms, the map and `inBoss` itself, and the party from the tab list the HUD module
sends. In BOSS_ENTRY, BOSS and with no run it is what it was. At END the bridge keeps the mode of the stage before.

## The lag layer

Model (`Lag.kt`): freeze sizes drawn from the measured "freezes per hour of at least g ms" table, log-uniform inside each bin; ticks missed = gap/50 - 1;
ticks recovered from the measured catch-up curve (nothing for a quarter-second freeze, 6.4 of 12.2, 9.9 of 38.6, 8.6 of 78.2); freezes of 250 ms or more
cluster (31.3 % start within 5 s of another). The arrival rate is scaled so the net loss is exactly the preset's ticks per minute.
Runtime (`LagRuntime.kt`), once per sim server tick: the server thread sleeps for the freeze; the ticks it fell behind by are split into the recovered
ones (run at `Lag Burst Rate` times the tick rate, paced by sleeping) and the rest, which are forgotten by moving the server's next-tick time forward
(reflection on `MinecraftServer.nextTickTimeNanos`; without it the freeze still happens but vanilla catches up in full, and `/ads dev lag` says so).
`Run.t`, pack durations and server-tick schedules are untouched: they just happen later.
Preset in force: `/ads dev lag` override, else the run's `RunConfig.lag`, else the "Lag Preset" setting.

## Spikes

`/ads dev difficulty`, `cost`, `costnow`, `lag`: see the work item report for the steps and what to expect. They log `[ads spike]` lines.

## The world's shape and sky (round 2)

Hypixel's dungeon world is the overworld dimension type at y 0 to 255 (16 chunk sections) with sky light 0 in every cell, plains biome, no
structures. The sim's was a vanilla overworld (-64 to 319, sky light on), so a third to a half of all room cells were brighter than on Hypixel.

| piece | file | notes |
|---|---|---|
| the dimension type | `WorldEnv.kt`, `SimWorld.installEnvPack` | a data pack in the world's own `datapacks/ads-env` folder that replaces `minecraft:overworld` with the game's copy edited to no sky light, `min_y` 0, height 256, and the cave mood sound taken out. The world data configuration lists `file/ads-env`. A dimension type is a registry entry that the login sends by id, so a direct holder (`Holder.direct`) cannot be used: the login packet would not encode it |
| the biome | `SimWorld.create` | plains. Left alone, a flat world uses every structure set its biome allows, so the structure overrides are set to an explicit empty set (otherwise villages and mineshafts grow into the dungeon) |
| the clock | `Runs.startClock`, `AdsBootstrap.onStopped` | a run turns `advance_time` on and sets a random time of day from the run's seed; stopping the run puts back the frozen noon the boss practice has |
| music | `HypixelModApi.install` (client) | `MusicManager.stopPlaying()` once at run start draws the next track's delay as the game does between tracks (10 to 20 minutes), instead of a first track five seconds in |

`WORLD_VERSION` in `SimWorld` is 3. On a port to another game version the points to look at are the pack format call
(`SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA)`), the file `data/minecraft/dimension_type/overworld.json` in the jar (read, edited and
written back, so new fields come along), and the `has_skylight` / `min_y` / `height` / `logical_height` / `ambient_light` keys.

## The chunk window (round 2)

Hypixel holds a fixed window of 15 x 15 chunks round the player (211 to 225 of them in steady state, 112 blocks out), sends them 5 a server tick nearest first, and never
changes the window's size, not even at the jump to the boss arena. `ChunkWindow` has the game's own rule for which chunks are tracked: distance 6 gives 15 wide and 213
chunks (5 gave 13 and 157, 7 gives 17 and 265), so `ViewPin.DISTANCE` is 6.

- The pin lasts the whole run, the boss included (`BossHandoff` no longer releases it; `Runs.stop` does). The boss sim's parts far from every player keep ticking because
  `EngineModule` forces every chunk of the arena's box (10 x 11) from the blood room on, and lets them go when the run ends.
- `Runs.start` sends a forget for every chunk of the login's window (the player's own render distance, round the world's spawn) that the player's new window does not hold, 3 and 40
  ticks after the teleport (`ChunkWindow.leftBehind`); without it about 25 to 30 chunks of the boss arena stay on the client for minutes.
- `AdsEngineChunkPaceMixin` (a mixin that is inert until its line is in `engineerclient.mixins.json`) makes a run's sender work like a remote one at exactly 5 chunks a tick.
  Nothing changes outside a run (`ViewPin.pacingChunks()`).

## What the mods that read Hypixel's mod API are told (round 2)

Blade Addons, DTMap and Devonian read three payloads that Hypixel sends in the login tick: `hypixel:hello`, `hyevent:location` (skyblock, mode `dungeon`) and, when asked,
`hypixel:party_info`. `HypixelWire` (pure) has the messages and the measured values (server name `mini` plus digits and capitals; the answer 0 to 3 ticks after the request);
`HypixelModApi` does the rest. The mod API is reached by reflection (no build dependency); without the mod nothing happens.

1. At run start the server sends every game a SimNet message (`ads.engine.hypixel`) and the server brand. The game builds the API's own packets, writes them behind the
   success flag, reads them back with the API's codec and hands the payload to its connection as if it had arrived, so the API's receivers, callbacks and every mod's
   handlers run as for Hypixel's (checked against the real mod API jars with the packets' fields read back).
2. The API's serverbound payloads (`hypixel:register`, `hypixel:party_info`) cannot be read by the integrated server (their codec has no decoder, so the connection would be
   dropped). They are cancelled on the way out through Odin's send event while a sim is open (`P3Sim.inSim`, so never on a real server), and a party request goes to the sim
   server (`ads.engine.partyreq`) which answers from the run (`ads.engine.party`, leader first, then members, then the bots).
3. The host gets a keep-alive 14 server ticks after the run starts and every 11 after (ids 1, 2, 3...). A LAN joiner does not: their connection times out on an answer the
   server did not ask for.

## No advancements (round 2)

`Fight.join` empties each player's advancement triggers (`PlayerAdvancements.clearTriggers`), so the blaze rod in the hotbar no longer completes "Into Fire" and a beacon no longer
completes "Bring Home the Beacon". The two advancement packets of the login still go out; the client drops every `ClientboundUpdateAdvancementsPacket` while a sim is open.

## Tests against the real game (round 2)

These read the game on the test classpath, so a renamed name or a changed format fails the build instead of the launch or the world: `ChunkPaceMixinTest` (the fields, constructor
and methods the pace mixin reaches), `WorldEnvGameFileTest` (the keys of the game's own overworld dimension type that `WorldEnv` edits), `WorldEnvDecodeTest` (the edited type goes
through the game's own `DimensionType` codec), `WorldEnvPackLoadTest` (a pack repository over a world's `datapacks` folder finds the pack as `file/ads-env`, compatible, and serves
its `overworld.json` above the game's) and `WorldHeightTest` (both arena files fit y 0 to 255, and a 16-section chunk's section lookups). They skip where the game cannot be
bootstrapped headless.

## In-game checks (round 2)

`/ads dev worldenv` prints both sides: the dimension (y range and sky light), biome, chunks held, the server brand, what the Hypixel mod API was told, the view and
simulation distance, the sky and block light at your feet, the held arena chunks and the keep-alives sent.

1. Start a run (`/ads run random`). Expected from `worldenv`: dimension y 0..255, sky light off, biome plains, view and simulation distance 6, sky light 0 at your feet, server
   brand "Hypixel BungeeCord ...", "Hypixel mod API told: hello, then location miniNNNX (SKYBLOCK, dungeon)", keep-alives counting up (one every 11 ticks, about 1.8 a second).
2. With Blade Addons open its location reads DUNGEON; Devonian's party shows the run's party (after a few seconds).
3. No advancement toast at the start of the run, and none when a beacon room completes.
4. No music for the first minutes; no cave sound in dark rooms.
5. With the Dungeon Recorder on: 211 to 225 chunks held once the first 45 ticks have passed (5 chunk packets a tick, nearest first, once the pacing mixin is listed), and no chunk at x >= 0 held while the party is in the dungeon far from the arena.
6. Go through to the boss: the window does not change size at the jump (no chunk radius or simulation distance packet), and the far parts of P3 keep working.
