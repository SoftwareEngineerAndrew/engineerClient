# ADS full run: architecture

This is the contract for the full-run work (stages 3 to 9 of the ADS plan). It is written for implementers who build in
parallel, each in their own worktree, and cannot talk to each other: whatever is not written here or in the skeleton code is
not promised. The skeleton is the commit this file sits in; read the code next to this text, it is the precise form.

ADS is the Advanced Dungeon Simulator. Its code still lives in `src/p3sim` and the package `com.engineerclient.p3sim` (not
renamed on purpose). New code goes in sub-packages: `...p3sim.run` (the container and the shared contracts), `...p3sim.pack`
(the data packs), `...p3sim.clear.<area>` (one folder per work item).

Hard rules that apply to every line (they are in the brief too):

- This repository is PUBLIC. No player data and no strategy findings in code, comments, tests, fixtures, docs or commit messages;
  mechanisms are fine (how the server plays the blood room out is built here exactly). Fixtures are synthetic. Hypixel's own
  generic system lines may appear as format strings with `{placeholders}`.
- The jar ships no recorded data. Layouts, rooms, mobs and tables are a local data pack read at runtime from
  `<gameDir>/config/engineerclient/ads/packs/`. A missing pack is a clear chat message with that folder path, never a crash.
- Do not change what already works. Today's boss sim (P1 to P4 practice, parties over the LAN relay, plans, PBs, the restart
  screen) keeps working exactly; starting the boss directly stays available and creates no `Run`.
- Build only with the serialising build script kept outside this repository (it takes the worktree path). It runs `gradle build` including the tests.

## 1. The picture

```
                                  client thread                        |            sim server thread (integrated server)
                                                                       |
  Odin / Devonian / Blade Addons  <-- sidebar, tab, map, action bar,   |   Fight.tick -> Runs.tick -> Run.tick
  (unmodified, believe they are       chat, titles (REAL packets)      |        |
  on Hypixel)                                                          |        +-> every RunSystem.onTick, in module order
                                                                       |        +-> RunBus (events) -> every RunSystem.onEvent
  P3Sim.bridge()  <--  ClientRun.stage  <-- "runstage" (SimNet) --------+--- Run.requestStage / forceStage
  /ads dev, /ads run  --- "adscmd" (SimNet) ---------------------------->   AdsCommand.server
  run start screen    --- its own SimNet kind ------------------------->   Runs.start(RunConfig)
                                                                       |
                                                                       |   RunState (the hub)   DungeonPlan (immutable)   PackSet (data)
                                                                       |   WorldRegions: dungeon region + clipped arena region
```

One run is one continuous session of the ADS world. A `Run` owns the clock, the stage machine, `RunState` and the `RunBus`, and
calls fourteen modules (`AdsModules.all`). Modules never call each other directly; they read `RunState`, post events, and call
the small service interfaces in `run/api` (section 8). The client never owns run state; it only draws what the server sends.

## 2. World frame and regions

The dungeon sits at Hypixel's real coordinates so Odin's scanner works unmodified (`pack/DungeonFrame.kt`, tested):

- 6x6 tiles; tile `(tx, tz)` has min corner `(-200 + 32 tx, -200 + 32 tz)` and covers 31 blocks; the 32nd column is the door gap.
  Tile centre column `-185 + 32 t`: the column Odin hashes to name a room. +x east, +z south, y absolute (0..255).
- The dungeon box is x,z in `[-200, -10]`. Door slot `(axis, tx, tz)`: `'E'` joins `(tx,tz)` and `(tx+1,tz)`, `'S'` joins `(tx,tz)`
  and `(tx,tz+1)`; 60 slots; centre `E: (-169+32tx, -185+32tz)`, `S: (-185+32tx, -169+32tz)`; the door body is x±1, z±1, y 69..72.
- The boss arena lives at x,z >= -8 (`DungeonFrame.ARENA_MIN`), as today.

**The seam bug and its fix.** `arena-ext.bin` writes 2,156 real blocks (stone, stone bricks, bedrock) at x,z in `[-16,-10]`,
y 56..99: that is the south-east corner of dungeon tile (5,5), and `Arena.resync` would clear dungeon blocks there. With a
dungeon loaded the arena must be CLIPPED to x,z >= -8. Chunk (-1,-1) (x,z -16..-1) is shared: the dungeon owns its columns up to
-10, the arena from -8, nothing writes the same cell.

**Regions.** The world is built from `WorldRegion`s (`run/WorldRegions.kt`): the DUNGEON region (layout module, per run) and the
ARENA region (engine, clipped). Each fills its own part of a chunk during generation (straight into the sections, no block
updates; Odin scans a room once, when its centre chunk loads, so blocks written later are invisible to it) and each resyncs on
its own (`resync(level)`); a region never reads another's blocks. The existing generator hook (`P3SimWorldMixin` ->
`Arena.fill`) stays; the skeleton already made `Arena.fill` call `WorldRegions.fillAll(chunk)` first (a no-op while no region is registered, so
nothing changes today), which also primes the heightmaps once after all regions have filled. The engine's `ArenaRegion` clips the arena; `RunLauncher.registerDungeon` registers the dungeon region and the arena region together, and `ArenaRegion.active` (true while it is registered) is what makes `Arena.fill` leave the arena to the region and clip its extension. Never use
`LevelChunk.setBlockState` during generation (Odin's `BlockUpdateEvent` hook would fire from a worker thread).

Lifecycle: a region that has filled a chunk is "used" by the running world; the engine calls `WorldRegions.worldStopped()` from `SERVER_STOPPED`, which
drops the used regions and keeps those registered for the next world (a run's dungeon is registered before the old server has finished stopping, so a
blanket `clear()` there would erase it). A spike's dev region is registered right before `SimWorld.rebuild()` and goes the same way.

At boss entry nothing is removed: Hypixel keeps the dungeon blocks standing. The client just forgets far chunks. Pin view and
simulation distance to 6 during a run (the window Hypixel holds, 213 chunks): `ViewPin` does it by writing the client's render and simulation distance options in memory (the integrated server copies them every tick, so a server-side setting would be undone), not with a mixin, and puts the player's values back when the run stops.

## 3. The Run, its stages and its clock

`Run` (`run/Run.kt`) is created by `Runs.start(config)` (engine) once the dungeon is planned and its region is registered.
Stages (`run/RunStage.kt`), forward one at a time, or to `END` from anywhere:

```
COUNTDOWN -> CLEAR -> BLOOD -> BOSS_ENTRY -> BOSS -> END
```

| stage | begins | meaning | Odin sees |
|---|---|---|---|
| COUNTDOWN | `Run.begin` | class lines, "Starting in N seconds", the entrance door falls ~23 ticks after the "1 second" line | dungeon, not boss |
| CLEAR | the tick of Mort's first line (T0) | rooms, doors, keys, mobs, secrets, puzzles, score | dungeon, not boss |
| BLOOD | the blood door opens | the Watcher stage until "You may pass." | dungeon, not boss |
| BOSS_ENTRY | the blood module starts the entry | the one-tick jump, one tick after a body first touches the blood room's portal (the portal opens a fixed 80 or 90 ticks after the Watcher's last counted kill): Maxor's bar, the party lands at P1 | boss |
| BOSS | `Run.beginBoss(...)` | the existing `Fight.Phase` chain (P1 Maxor ... P4 Necron) runs; the run only watches | boss |
| END | the boss is dead, or the run was stopped | end screen up | unchanged |

Who requests what: the doors/state module `requestStage(CLEAR)` when Mort speaks (and calls `run.setT0(...)` when it plans the
countdown); the doors module only POSTS `BloodDoorOpened`, and the blood module answers it with `requestStage(BLOOD)` (so exactly one
module asks), then `BOSS_ENTRY`, then calls `run.beginBoss(options)` which the engine turns into `BOSS`;
the engine ends the run (`END`) when Necron dies or the player stops. A refused request is logged and returns false.
`forceStage` is the engine's, for a savepoint restore going back.

Every stage, the first one included, is announced to the clients with SimNet kind `runstage` (`Run.begin` and every stage change send
it; `PlayerJoined` sends the current one again for a late joiner; `Runs.stop` sends "no run"). Until the first message arrives
`ClientRun.bridgeInBoss` is true, which is exactly why the first stage must not be skipped.

**Clock.** Two numbers, both server ticks (20 per second): `run.serverTick` (absolute, `Fight.serverTick`) and `run.t`, the run clock,
ticks since T0 (the tick of Mort's first line), NEGATIVE during the countdown, 0 until T0 is planned. Every pack duration, every
recorded tick and every schedule is in the `t` frame. Pack durations are lag-free "sim ticks": the lag layer stretches wall time
by skipping and bursting server ticks, never `t`; applying lag to a pack duration again is a bug. Anything timed (bots, mobs,
doors, the score step) schedules on server ticks so it stretches with the stalls, as real teammates do.

`run.random` and `run.subRandom("mobs")` are seeded from `RunConfig.seed`: the same seed gives the same builds, rolls and HP scale.
Use a salted sub-random per purpose so one module's draws do not shift another's.

**Today's boss practice has no Run.** `Runs.current` is null there and every module must cope with that. The Odin bridge's rule:
`ClientRun.bridgeInBoss` is true when there is no run or the stage is BOSS_ENTRY/BOSS, false otherwise.

## 4. Threading

- Everything under `run/` and every module's run logic is SERVER THREAD ONLY: `Run`, `RunState`, `RunBus`, the services. The sim
  server thread is the integrated server's thread; `RunBus.post` refuses other threads.
- The CLIENT thread owns: commands, screens, the Odin bridge, client item prediction, anything that reads Odin or the player's
  game options. `ClientRun` (volatile fields) is the only run state a client thread reads; the server sends the stage with SimNet
  kind `runstage` on every stage change.
- Client to server: `SimNet.send(kind, json)`; the receiver registered with `SimNet.onServer(kind)` runs on the server thread as the
  sending player (`Sim.asPlayer`). Commands use kind `adscmd`. A module registers its own kinds in `AdsModule.onInit()`.
  Server to client: vanilla packets (sidebar, tab, map, chat...) where Hypixel uses them, else `SimNet.send(player, kind, json)`.
- Chunk generator WORKER threads run only `WorldRegion.fill` and what it calls. Nothing there may touch the level, `RunState`, the
  bus or any module state that is not immutable or concurrent. A pack volume is decoded on demand and cached thread-safely
  (`RoomPack.volume`); decode the volumes a layout needs before generation if you want the cost off the workers.
- Data packs load off-thread (`Packs.loadAsync`); the result is immutable apart from the lazily decoded volumes.
- Do not assume a far chunk is loaded. With view and simulation distance 6 (about 112 blocks; the dungeon is 190 across) only the chunks near a player are,
  unless the engine decides after its `/ads dev cost` spike to hold more with tickets (it will say so here). Mobs, secrets, puzzle
  blocks and Mannequins of far-away rooms do not exist as entities or blocks until their chunk loads, and a bot three rooms from the human does not
  tick. Truth about a far room lives in `RunState` and the module's own records (ids and numbers); the world is brought in line when the chunk loads
  (`RunBlocks.isLoaded`), and a deliberate `RunBlocks.set` on an unloaded chunk loads it synchronously. Bots move on their plan whether or not anyone
  can see them; their bodies are shown only when near.
- Fabric callbacks for player clicks (`UseBlockCallback`, `UseEntityCallback`, `AttackEntityCallback`, `UseItemCallback`) run on the
  server thread for a `ServerPlayer` and on the client thread for the local player. A module does NOT register its own: it
  overrides `AdsModule.onUseBlock/onUseEntity/onAttackEntity/onUseItem`, which `run/RunInput` calls (section 8.4).
- `SimNet` kinds are global strings (at most 64 characters). A module names its kinds `ads.<moduleId>.<name>`; `adscmd` and `runstage`
  are the skeleton's. Registering the same kind twice silently replaces the first handler.
- Pack sets are shared: the generator's workers read a run's room volumes for as long as the run lasts. A run pins its `PackSet`
  (`Run.begin` / end), and `Packs.load` retires the previous set (closed once no run uses it) instead of closing it under the run.
- Headless tests have no Minecraft: keep pure logic in classes that import no `net.minecraft` and no Odin (the `pack` package and
  the pure parts of `run` are examples), and test those.

## 5. RunState: the hub

`run/RunState.kt`, created by the engine from the `DungeonPlan` (`Run.state`). It holds facts; modules hold rules. Four rules:
(1) ONE WRITER PER FIELD (table below); anyone may read. (2) A writer changes the field and posts the matching event in the same call,
so a reader that misses the event can poll the field and a reader that gets the event sees the field already changed. (3) Nothing
in it talks to Minecraft: ids and numbers only. (4) All of it is saved by a savepoint (`snapshot`/`restore`, engine fills).

| part | what it holds | single writer |
|---|---|---|
| `rooms[i].check` (+ `tDiscovered/tWhite/tGreen/tRed`) | `UNDISCOVERED NONE WHITE GREEN RED`, via `state.setCheck(room, mark)` which posts `RoomDiscovered/RoomCleared/RoomGreen/RoomFailed` | doors/state module (the ONLY writer) |
| `rooms[i].occupants/visitors`, `players[].roomIndex` | who stands in a room, by position | doors/state |
| `rooms[i].mobsRequired/mobsRequiredAlive` | mobs that must die for WHITE | mobs |
| `rooms[i].secretsFound`, `secrets.*` (team found/max, per player, crypts, bonus flags, blessings) | secret and crypt counts | secrets |
| `doors[i].phase/openedAt/openedBy` | `CLOSED FALLING OPEN` | doors/state |
| `keys` | party-shared wither key count, blood key flag | doors/state |
| `counters` | `Opened Rooms`, `Completed Rooms` in 36 tile units, entrance excluded | doors/state |
| `score` | team score, `Cleared %`, history | score (secrets work item) |
| `deaths`, `players[].hpPercent/ghost/deaths/reviveAtT/lastHitT` | the percent ledger, ghosts | damage |
| `players` (membership) | adds/removes the records | engine (humans), bots (bots) |
| `players[].x/y/z` | position, refreshed at the start of each tick | engine (humans), bots (bots) |
| `puzzles[]` | one `PuzzleRecord` per puzzle room: status, revealed, failures | puzzles (and hard puzzles/traps for theirs, through the host) |
| `milestones` | T0, blood key/open, Watcher done, boss entry/defeat, end | whoever owns the event (doors: T0; blood: blood/Watcher/entry; engine: end) |

Counters are in TILE UNITS (a 2x2 room is 4, an L is 3), total 36 from the first tick, the entrance excluded from both. The
secret maximum a room prints is `plan.rooms[i].secretsMax` (run-state pack first), never what the rooms pack can place.

## 6. The event bus

`run/RunBus.kt`, vocabulary in `run/RunEvents.kt` (sealed `RunEvent`, each with the run clock `t`). Synchronous, deterministic:
subscribers run by priority then subscription order; an event posted by a handler waits in a FIFO queue until the current one has
reached everyone (no recursion); a throwing handler is logged and skipped. The run delivers every event to every module's
`onEvent` in module order (one subscriber at priority 0); a module may also `bus.on<T>(...)`. An unknown event subclass must be ignored.

| group | events |
|---|---|
| lifecycle | `RunStarted`, `StageChanged(from,to)`, `RunEnded(reason)`, `SavepointTaken`, `SavepointRestored`, `PlayerJoined`, `PlayerLeft` |
| rooms | `RoomDiscovered`, `RoomEntered(room, player)`, `RoomCleared` (white), `RoomGreen`, `RoomFailed` (red) |
| doors, keys | `DoorOpened(door, kind, by)`, `DoorFell`, `DoorLocked(door, player)`, `KeyDropped(kind, room, x,y,z)`, `KeyPicked(kind, player)` |
| secrets | `SecretFound(room, kind, player, x,y,z)`, `CryptDestroyed`, `BonusScored(kind)`, `BlessingGranted` |
| mobs | `MobSpawned(MobRef)`, `MobDamaged(mob, amount, remaining, by)`, `MobKilled(mob, by)` |
| players | `PlayerDamaged(player, percent, hpAfter, source, attacker, true)`, `PlayerDied`, `PlayerRevived(player, by)` |
| puzzles | `PuzzleSolved(puzzle, room, player)`, `PuzzleFailed(puzzle, room, player, reason)` |
| blood, boss | `BloodDoorOpened`, `WatcherDone`, `BossEntry` |
| score | `ScoreUpdated(score, clearedPct)` |
| escape hatch | `ModuleEvent(source, name, data)` for a notice only one module family cares about |

New event types are additive edits to `RunEvents.kt` (an integration request to the architect/integrator). Rooms and doors are named by
index in the `DungeonPlan`, players and mobs by UUID, mobs in events by the small `MobRef`.

## 7. Modules, the RunSystem lifecycle, savepoints

`run/RunSystem.kt`. A work item's code is one `object XxxModule : AdsModule` (already created, empty, by the skeleton, under
`clear/<area>/`). It is "registered" by existing in `AdsModules.all`, an ordered list that is fixed and NEVER edited by a work item:

`engine, layout, doors, mobs, damage, secrets, score, puzzles, hardpuzzles, traps, bots, blood, hud, ui`

The order is the contract: earlier modules have seen an event before later ones, `onTick` runs top to bottom. (The engine first so
positions are fresh; layout; the state module before anything that reacts to its marks; mobs then damage which kills them; secrets and
score which read the marks; puzzles and traps; bots which read all of it; blood; the HUD last so every surface draws the end-of-tick state.)

Per run, in call order: `onRunStart` (once; the dungeon is planned, the region registered, the world built) -> `onStage` at each stage
change -> `onTick` every server tick -> `onEvent` for every bus event -> `onRunEnd` (once). A throwing system is logged and skipped.
A module keeps its state in its own object, resets it in `onRunStart`, and holds no entity references in anything it snapshots.

Module hooks that avoid editing shared files: `onInit()` (once at game start, client thread: register `SimNet.onServer/onClient`
kinds and Fabric callbacks), `registerSettings(host)` (add Odin settings to the ADS module, `P3Sim`; every unmeasured constant a player
might want to change is a setting), `devCommands()`, `runCommands()`, `puzzleTypes()`. A module object's initialisation must be
cheap and must not touch Minecraft, Odin, `P3Sim` or `Fight` statics (the registry is read while those start up, and the headless
`ModulesTest` instantiates every module). So Odin settings are created inside `registerSettings(host)` and kept in a `lateinit var`:

```kotlin
object MobsModule : AdsModule, MobService {
    override val id = "mobs"; override val owner = "mobs"
    lateinit var wakeRadius: NumberSetting
    override fun registerSettings(host: Module) { wakeRadius = host.registerSetting(NumberSetting("Fels Wake Radius", 5.0, 1.0..16.0, 0.5, desc = "...")) }
}
```
Pure logic never reads a setting: it takes the value as a parameter, which is also what makes it testable.

**Savepoints** (`run/Savepoints.kt`; engine fills the store). A savepoint is `RunState.snapshot()` plus one entry per system
(`RunSystem.snapshot(run)`: small JSON, ids and numbers) plus the players' positions and gear. Restoring, in this order: stage back
(`forceStage`); the clock back (`run.setT0(run.serverTick - savepoint.t)`: `t` reads the saved value, the absolute tick keeps going);
`RunState.restore`; every region `resync` (its blocks return to the START state as generated: doors closed, chests unopened, walls
whole); every system `restore(run, entry)` in module order; then `SavepointRestored` is posted. `onRunStart` is not called again.
Taken automatically when the party enters a room and by `/ads run savepoint`; the engine also takes one labelled "Start" at the end of
`Run.begin`, so `/ads run restart` always has one. Restarting a room is restoring its entry savepoint.

What this asks of a module:
- **Blocks you change, you re-apply in `restore`.** The resync has just put the dungeon back to its generated state; the doors module
  re-opens the doors that were open, the secrets module the opened chests and broken walls, the puzzles their own blocks. Change
  blocks only through `RunBlocks.set` (never `Blocks.set` or `Arena.set`: those belong to the boss arena and a boss restart would put
  such a position back to air).
- **Entities you spawn, you re-create in `restore`** from ids and numbers you saved. Spawn through `Sim.spawn(entity)` so the entity
  carries the sim's tag and `Sim.clearEntities()` (the engine calls it on restore, on stop and on a boss start) removes it. Nothing
  entity-shaped goes in a snapshot.
- **Absolute server ticks are not restored.** Keep timers as "ticks left" or "tick at which, relative to `run.t`" and re-schedule in
  `restore`. Do not use `Fight.later` for run logic: it is a boss-sim queue, cleared whenever a boss phase is started or stopped.
- A restore is not a new run: counters, scores, deaths and keys come back from the snapshot, and nothing is announced to the player
  except what a module chooses to say in `restore`.

## 8. Services: who implements what

`run/api/Services.kt` is the fixed wiring: `Services.mobs` is `MobsModule`, and so on. Every service interface has harmless defaults, so a
half-built tree still runs (nothing spawns, nothing is hurt). Interfaces here are FROZEN: implementers may add members with default
bodies, never change or remove one (section 15).

| `Services.` | interface | implemented by (work item) | callers |
|---|---|---|---|
| `layout` | `LayoutService.plan/region` | layout | engine at run start |
| `doors` | `DoorService.keyPlan/keyForRoom/dropKey/tryOpen/doorAtBlock` | doors | mobs (key on last kill), items/clicks, bots |
| `mobs` | `MobService.spawnRoom/spawn/mob/inRoom/requiredAlive/setHp/remove` | mobs | damage, doors, blood, secrets (bonus mobs), bots |
| `damage` | `DamageService.hurtMob/implode/meleeHit/hurtPlayer/kill/revive` | damage | items (Implosion, bows, Mage), mob AI, traps, bots |
| `secrets` | `SecretService.found/cryptDestroyed/bonus/roomMax` | secrets | puzzles (reward), mobs (bonus mobs), clicks |
| `score` | `ScoreService.recompute/endScreen/showEndScreen` | secrets (score) | engine at END, blood |
| `puzzles` | `PuzzleHost.reportSolved/reportFailed/rewardChestOpened/puzzleOf` | puzzles | puzzle implementations (also hard puzzles) |
| `bots` | `BotService.botIds/ownerOfRoom/requestReplan/leapTargets` | bots | doors (a player took a room), blood, the leap and Haunt windows in `SimItems` |
| `traps` | `TrapService.abilitiesDisabled` | traps | the item handlers (damage item edits `SimItems.kt` to ask it) |
| `channels` | `ChannelService.chat/title/actionBarSegment` | hud | everyone who talks to the player |

**Item hooks.** `SimItems.kt` belongs to the damage work item, but two rules behind it belong to others. The damage item adds the calls:
the Dungeonbreaker handler asks `Services.secrets.breakerRefusal(run, x,y,z)` (null = may break; else a reason to show) and tells
`onBreakerBreak`; the Superboom handler calls `onSuperboom`; every ability handler (teleports, blinks) first asks
`Services.traps.abilitiesDisabled(run, player)`. All default to "no opinion", and every call is guarded by `Runs.current != null` so the
boss practice is untouched.

**Spirit Leap and Haunt in the clear.** `SimItems.openLeap/openHaunt` list `Party.mates(...)`, which only knows the P3 bots, so in a run they
would show nobody. While a run is in `COUNTDOWN`, `CLEAR` or `BLOOD` the damage work item makes them ask `Services.bots.leapTargets(run, viewer)`
instead (members other than the viewer in leap order, humans included), and the bots module answers from `RunState.players`; a member with
`here = false` (their chunk is not loaded) is left out of the window. The leap itself is a teleport to that position.

`ChannelService.chat/title` default to the existing `Sim.chat/Sim.title`, so every module can speak from day one.

### 8.1 Damage API (`run/api/DamageApi.kt`)

Everything that hurts or kills goes through `DamageService`. Mob side: `hurtMob(mob, amount, source, by)` (SkyBlock-scale HP, arrows,
beams, melee) and `implode(caster, box, damage?)` (hits every alive mob in the box for the floor profile; F7: one cast kills; M7: 1 to 13
casts). Both end in `MobService.setHp`, the hook that updates the nametag, posts `MobDamaged`, and on 0 HP kills (posts `MobKilled`,
removes stand and body on the same tick, asks `DoorService.keyForRoom` whether a key drops). Player side: a percent-of-max-HP ledger
(vanilla health is `40 * pct / 100`): `hurtPlayer(player, percent, source, attacker, trueDamage)`, `meleeHit(mob, player)` (size rolled from
the mob pack's melee table, per archetype when present, 10-tick immunity), traps exactly 15.0 true damage, death -> ghost for about 6 s
(120 ticks), `Team Deaths` +1, `revive`. `RunConfig.noDeaths` clamps the ledger at 1 hp. In the BOSS stage the boss sim's existing damage
behaviour is unchanged and the hub only observes.

### 8.2 Mob registry API (`run/api/MobApi.kt`)

`MobRecord` (UUID, kind, name, room, starred, required, hp/maxHp as SkyBlock numbers, alive, position) is the entity-free view of a mob;
`MobSpec` asks for a mob outside the room tables (camp mobs, Mimic, specials). The mobs module is the only writer of records. A starred
mob is an armor-stand nametag plus a body one block below (the stand at body id + `tag.idOffset`); disguised mobs are fake players on the
wire (UUID version 2) while the server keeps a light mob (the mobs work item owns that, including the Mannequin data-id mismatch).

### 8.3 Puzzle interface (`run/api/PuzzleApi.kt`)

A `PuzzleType(kind, tabName, families, create)` is provided by a module's `puzzleTypes()`; the puzzles module collects every module's
types at run start, creates one `PuzzleRecord` and one `Puzzle` per puzzle room (matching the room design's `puzzle.kind` or family),
and hosts the shared frame (tab rows, reward chest, solved/failed lines, reset item, click routing). A `Puzzle` has `onRunStart`,
`onRoomEntered`, `onTick`, `onInteract(player, x,y,z)`, `onNpcClick`, `onReset`, `snapshot/restore`, and reports through
`PuzzleHost.reportSolved/reportFailed`. Its in-world state (blocks, entities, NPC names, chat) must match exactly what Odin's solver for
that puzzle reads from Hypixel. Answer tables may come from Odin (BSD-3; credit in KDoc). Pure state machines live in their own tested classes.

### 8.4 Player interaction (`run/RunInput.kt`)

Every click that means something in the dungeon reaches the modules through four `AdsModule` hooks, called by ONE Fabric callback per event
that `RunInput` installs in a phase before the default one (before `SimItems` and `Sim.guardBlocks`, which answers FAIL to every block
click that reaches it): `onUseBlock(run, player, hand, hit)`, `onUseEntity(...)`, `onAttackEntity(...)`, `onUseItem(...)`. They run
on the server thread, as the clicking player (`Sim.asPlayer`), for the MAIN hand only, only while a `Run` exists in `COUNTDOWN`, `CLEAR` or
`BLOOD`. Modules are asked in `AdsModules.all` order; the first answer that is not null ends the question (SUCCESS consumes the click,
FAIL refuses it, PASS stops the modules and lets the sim's own handlers go on); all-null means "not mine" and the sim's item and block handlers
run exactly as in the boss practice. In the boss stages and with no run, nothing here answers. Typical owners: doors (a click on a door
body, `DoorService.tryOpen`), secrets (chests, levers, skulls, bats), puzzles (their blocks and NPCs), mobs and damage (a hit on a mob),
traps (nothing; they are tick-driven). Proximity things (key stands, item secrets, the walk-in trap triggers) are `onTick` checks against
`RunState.players` positions, not click hooks.

## 9. Client channels (the HUD)

The sim sends the REAL packets to each real player so unmodified Odin, Devonian and Blade Addons believe they are on Hypixel:
the sidebar as `team_N` teams, the 80 tab entries refreshed every second, the dungeon map item as full 128x128 frames (map id 1024, about
every 5 ticks), the action bar every 10 ticks, chat and titles. All of it is drawn from `RunState` by the HUD module (never from other
modules' internals). Others post through `Services.channels`; extra content comes through `HudRegistry.sidebar/tab/map` contributors.
`ChannelService.actionBarSegment(key, text, priority, ttl)` adds a segment; the HUD joins health, defense, mana and, when in a room,
`N/M Secrets` (M is the room's `secretsMax`; Odin stops tracking if it differs) with these. Chat text is Hypixel's legacy `§` form: the
plain string is what Odin and Devonian parse, so it must read exactly as Hypixel's line does. Formats are checked against Odin's
(`libs/odin`), Devonian's and Blade Addons' parsers; pure formatting (sidebar lines, tab rows, map pixels from a `RunState`) is unit-tested.

## 10. The Odin bridge

`P3Sim.bridge()` currently forces `inBoss = true`, the floor and the area every client tick. That kills Odin's room scan and map outside the
boss (`WorldScan` and `MapScan` return early when `inBoss`). The engine changes it to follow the stage: while `ClientRun.stage` is
`COUNTDOWN`, `CLEAR` or `BLOOD` it keeps only the party in sync (teammates, leap list) and lets Odin derive the rest (the HUD module's sidebar
carries `The Catacombs (F7)`; in the ADS singleplayer world Odin's area is `SinglePlayer`, which counts as every area); in `BOSS_ENTRY`/`BOSS`,
and with no run at all, it behaves as today. Keep `BlockUpdateServerThreadMixin` (it stops the integrated server's block updates reaching Odin twice).
In the clear, Odin derives the party from the tab list the HUD module sends. As built: the bridge writes ONCE when it enters the clear (area `SinglePlayer`, `inBoss` false, the floor F7 if Odin has none, the party lists emptied) and never again until the stage turns to the boss; the `END` stage keeps the mode of the stage before it. Separately `P3Sim` sets the area to Dungeon and the floor to F7 at the client's join, before the first chunk packet, because Odin queues every chunk that loads before it believes it is in a dungeon and never flushes the queue (the sim posts no location change).
Room recognition needs: the centre column of a room's tile holds exactly the captured blocks from y=140 down (nothing added above a roof,
bedrock floor present), the blue terracotta marker where the rooms pack puts it, each design used at most once per dungeon, no
`structure_void` leftovers.

## 11. Data packs

Four packs, read from `<gameDir>/config/engineerclient/ads/packs/` by `pack/Packs.kt` (`Packs.load/loadAsync/current`):

| file | loader | holds |
|---|---|---|
| `rooms.index.json.gz` + `rooms.blocks.bin` | `RoomPack` | every room design in a canonical frame: volumes (`RVL1`/`RPT1` zlib blobs), door slots, block entities, secrets, levers, crypts, puzzle anchors, variants, Odin cores |
| `layouts.json.gz` | `LayoutPack` | recorded layouts: rooms with rotation, doors with final kinds, path, keys, recorded check-mark ticks |
| `mobs.json.gz` | `MobPack` | archetypes, modifiers, per-run HP scale, per-room spawn cells and recorded visits, player damage tables |
| `runstate.json.gz` | `RunStatePack` | door/key rules, room durations, check-state tables, score model, class roles, puzzle odds, SB stat presets, lag presets, named defaults |

The specs (`FORMAT-rooms.md`, `FORMAT-layouts.md`, `FORMAT-mobs.md`, `FORMAT-runstate.md`) are kept outside this repository; the loaders are
written exactly against them and take a `PackReport` for messages (never chat, never exceptions). Every loader returns null with a plain
reason when a file is missing, unreadable, the wrong `format`, or a `version` it does not read (a newer version names both). Loaders
ignore unknown fields and tolerate nulls. The run-state pack's sections are independent: a malformed section is null and named in
`problems`, and only the feature that needs it falls back to the documented default constants. `Packs.userLines(set)` is the text for the
player: one message naming the folder and the five files when the packs are not there at all.

Frames: world frame as section 2; canonical room frame (Odin SOUTH, x east, z south, y ABSOLUTE) for everything inside a design;
`Placement.of(shape, w, d, rot, worldTiles, fairy)` is the position law (4.2) and the tile law (4.3), pure; `quarterTurns` is how many
turns every block STATE must also be rotated (`BlockState.rotate`). Fairy is always SOUTH. `RoomDesign.place(rot, tiles)` also checks the
design's own `rotations` list. `DungeonPlan.build(layout, seed, roomPack, runstate)` resolves a recorded layout into the immutable plan
(design choice per room: the layout's `design`, else the `core` lookup, else a seeded pick among the family's builds; placement; secrets
maxima) and returns a plain-English `PlanFailure` instead of throwing.

Further shared pure helpers, all tested: `pack/DoorModel.kt` (the 3 x 4 x 3 door body, its blocks per kind, the falling block;
`PlacedDoor.bodyCells/contains/bodyBlock`, `DungeonPlan.doorContaining`), `pack/rooms/OdinCore.kt` (Odin's room core of a tile centre column,
so the layout module can assert headless that a pasted room is recognised; it reproduces all 145 base cores and 19 variant cores of the built pack),
`run/LayoutPick.kt` (`RunConfig.layout` to a recorded layout: random and seed by the pool and the seed, a recorded id with warnings; use it, do not re-implement the choice), `RoomPack.volumeFor(design, variantId)` (build a room from `PlacedRoom.variantId`, the variant its recorded Odin core selects, or Odin names it differently).

**There is no doors pack.** FORMAT-rooms mentions one, but none exists: the doorways of the rooms are in the rooms pack (`slots`, with `seal`
cells for walls) and the door BODY and its states are `DoorModel`. The layout module writes every door closed while the chunks generate
(body block per kind, a plain stone-brick frame and lintel at y 73 where a slot needs one, the wall `seal` where the slot is `NONE`), the
doors module only ever changes the 36 body blocks (coal, then barrier, then air). The eight decorative door styles of Hypixel are cosmetic and are not reproduced.

A typed field the loaders do not model (a new column in a pack) is read from the pack's `raw` JSON (`RunStatePack.section(name)`,
`RunStatePack.raw`), never by editing another work item's pack folder.

`RunStatePack` constants documented as unmeasured (`START_LEAD_TICKS = 20`, `UNMEASURED_PUZZLE_PENALTY = 10`, `BLESSING_BOOST_FRAC = 0`,
`CATCHUP_BURST_RATE = 2`, `SYNTHESISE_ROOM_CLEARS = true`, `AUTO_CREDIT_MISSING_SECRETS = true`, `CRYPT_COUNT_ON = tomb-block-destroyed`) are
named constants in the module that uses them, and every one a player would want to change is a setting.

## 12. Commands and settings

`/ads dev <name> [args]` and `/ads run <name> [args]` (also under `/p3sim`) are built from `AdsCommand`s that modules return from
`devCommands()` / `runCommands()`; `/ads dev` and `/ads run` list them. An `AdsCommand` has an optional client part (runs at once on the
client thread: probes of Odin, screens, reading the packs folder) and an optional server part (runs on the sim server thread as the player,
arguments carried by SimNet kind `adscmd`). `requiresSim = false` only for commands that read files. Built in: `/ads dev packs` (reloads and
reports the packs), `/ads dev events` (the last events the bus delivered). Two commands with one name: the first wins, the rest are logged.

Starting a run is `RunLauncher.launch(config, reply)` on the client thread (section 14): `/ads run random`, `/ads run seed <n>`,
`/ads run layout <id>` and the start screen all call it. `/ads run` with no name lists the run commands. Settings names must be unique across
the whole ADS module (Odin keys a setting by its name, a second one with the same name replaces the first): start a module's names with its
area word ("Mobs Wake Radius", "Doors Fall Ticks").

The existing code is touched in exactly four places by the skeleton, all additive: `P3Sim.init()` calls `AdsBootstrap.init()`, `P3Sim`'s
`init` block calls `AdsBootstrap.registerSettings(this)`, the `/ads` command builder calls `AdsCommands.attach(cmd)`, and `Arena.fill` calls `WorldRegions.fillAll(chunk)` first. Settings are added to
the ADS Odin module (`P3Sim`), consistent with its other settings, by each module's `registerSettings(host)`.

## 13. The plug-in seam

`run/plugin/`. Public ADS has hooks only; anything that encodes strategy lives in an optional plug-in mod. A plug-in implements `AdsPlugin` and
declares a Fabric entrypoint `engineerclient:ads` (Kotlin: `{ "adapter": "kotlin", "value": "pkg.MyPlugin" }`); `AdsPlugins.load()` reads it with
`FabricLoader.getEntrypoints` inside a try/catch, ignores a plug-in whose `apiVersion` is newer than `AdsPlugins.API_VERSION`, and logs failures.
Everything is optional. Two hooks:

- `AdsPlugin.bloodCamp(): BloodCampFactory?` -> `create(host: BloodCampHost): BloodCamp`. The public blood module builds the room, the wall heads,
  the Watcher entity and his generic lines, the blood door, and hands the room (`BloodRoomSpec`, world coordinates) and a `BloodCampHost`
  (spawn/remove mobs through the mob service, set a wall head or throw it with `flyHead`, make the Watcher speak and move (`moveWatcher`, with a yaw), report the
  counted kills with `watcherKills` (the fifteenth is his defeat and starts the exit's clock), red/green room mark, seeded random, ticks since open; the Watcher hangs 4 blocks over the floor)
  to the first factory that `supports` it. The camp ticks until `finished`; then the blood module ends the Watcher stage ("You may pass."). With
  no plug-in factory the blood room runs `WatcherCamp` (`clear/blood/camp/`): the Watcher's own camp as the real server plays it out, exact. Only a
  room with fewer than 15 wall heads gets the plain stand-in `NeutralCamp` (mobs released on a plain fixed timer, with a chat note).
- `AdsPlugin.partyStrategies(): List<PartyStrategy>` -> `bloodAssignment(ctx): BloodAssignment(seat, arriveAtT, extras)`. The bots module
  builds each ClearPlan from the role tables and asks the chosen strategy (`RunConfig.strategy`, default `"neutral"`, the bots module's own) who goes
  to the blood room and by when.

Compatibility: interfaces a plug-in implements (`AdsPlugin`, `BloodCampFactory`, `BloodCamp`, `PartyStrategy`) only ever gain members with default
bodies; interfaces the public side implements and the plug-in calls (`BloodCampHost`, `StrategyContext`, the data classes) may gain members freely.
Breaking either bumps `AdsPlugins.API_VERSION`. A plug-in's camp replaces `WatcherCamp`; what a plug-in does with the room beyond that is its own business.

## 14. Flows across modules (the integration points)

**Run start** (engine). The ADS world is built fresh on every open and the dungeon is written while its chunks generate, so the order
matters (`run/RunLauncher.kt`): on the CLIENT thread `RunLauncher.launch` loads the packs async (a pack problem is `Packs.userLines` in chat, then
stop) -> `Services.layout.plan(config, packs)` -> register `Services.layout.region(plan)` and the clipped arena region -> keep config and plan as
`RunLauncher.pending` -> open or rebuild the world (`SimWorld`). Once the sim server is up and the player has joined, on the SERVER thread: take
`RunLauncher.takePending()` -> `Runs.start`: NORMAL difficulty, mobs `setPersistenceRequired`, view/simulation distance 6, `Fight.setup(player)` for each
player (game mode, speed, health, the hotbar with the Magical Map in slot 9; the boss does not give it again) -> `Run(bus with the real server-thread
check, ...)`, `Runs.install`, `run.begin` (every module `onRunStart`: mobs spawn their rooms, the state module plans the countdown and `setT0`, bots
plan, puzzles create theirs), then the "Start" savepoint. The player is already unhurtable in vanilla terms (`Fight.setup` sets it); the percent
ledger is the damage module's. Classes: a human's `PlayerRecord.dungeonClass` is `Players.classOf(player)` (the ADS menu's class setting); the bots take
the classes no human plays, in the boss sim's own leap order (`P3Plan.botOrder()`), and `RunConfig.partySize` caps the party (fewer bots, never fewer humans).

**A kill:** damage `hurtMob` -> `MobService.setHp` -> `MobDamaged`/`MobKilled` -> mobs lowers `rooms[i].mobsRequiredAlive`; the state module on
`MobKilled` sees `requiredAlive == 0` and `setCheck(WHITE)` (or straight to GREEN if the room has no secrets), and asks `DoorService.keyForRoom`;
mobs call `DoorService.dropKey` on the last required kill where it says so.

**A secret:** a click/pickup -> `SecretService.found` -> `SecretFound`, room and team counts up, action bar -> the state module on `SecretFound`
turns the room GREEN when `secretsFound == secretsMax` and the room is WHITE (rooms with 0 secrets go NONE -> GREEN at WHITE). `ScoreModule`
recomputes at `T0 + 200k`.

**A door:** a click on a door volume -> `DoorService.tryOpen` -> with a key in the party count: `KeyState` down, first block barrier, the rest next
tick, all air 14 ticks later, `DoorOpened` then `DoorFell`, Hypixel's "opened a WITHER door" line; without: the no-key line and sound,
`DoorLocked`. The entrance and the free Fairy door fall by themselves ~23 ticks after the "1 second" line. The blood door opens with the blood key,
posts `BloodDoorOpened`, and the run goes to BLOOD.

**Blood to boss:** blood module runs the camp (a plug-in's, else `WatcherCamp`) -> `finished` -> the Watcher's "You may pass.", room GREEN, `WatcherDone`
-> a lightning bolt, then the portal at D+80/90 (D is the tick the camp reports the Watcher defeated, his fifteenth counted kill), then on first contact the one-tick entry (bar, party teleported to P1, nothing re-given), posts `BossEntry`,
`requestStage(BOSS_ENTRY)`, then `run.beginBoss(BossEntryOptions(giveHotbar = false, firstLineDelayTicks = 2))`; the engine starts `P1Maxor` (its
first line two ticks after the jump) and flips Odin's `inBoss` through the stage message. At Necron's death the engine ends the run: stage END,
`Services.score.showEndScreen(run)`, `RunEnded`. The end screen shows the last published score (there is no extra recompute at the end).
At `BOSS_ENTRY` the bots module removes its clear bodies and the state of its plan (the boss sim spawns its own bots for the classes no player plays,
exactly as in the boss practice), and the mobs module removes whatever is left of the clear's mobs; the dungeon's blocks stay standing.

**A death:** `hurtPlayer` reaches 0 -> ledger clamp (No deaths) or `PlayerDied`, ghost state, `deaths.teamDeaths++`, the ghost line, revive after ~120
ticks or a Revive Stone -> `PlayerRevived`; the score loses `max(0, 2d-1)`.

## 15. Rules for implementers

- Edit only the files your work item owns (`OWNERSHIP.md`). Anything else is an INTEGRATION REQUEST in your final report: file, the exact change,
  why, the code. Prefer a hook in your own files.
- The API in `run/` and `pack/` is FROZEN for you: add members with default bodies or new files in your own folder; never change or remove a
  signature other work items compile against. The engine owns `run/**` after the skeleton but is bound by the same rule for every file listed as
  frozen in `OWNERSHIP.md`.
- Every pure-logic piece (state machines, keys, score, transforms, damage maths, schedules) gets a headless JUnit 5 test under `test/p3sim/<area>/`.
  What cannot be tested headless gets an `/ads dev ...` command and exact in-game steps with expected results in your report.
- Every constant that is not measured is a named, documented constant (a setting where a player would want it) and goes on your report's
  "unmeasured" list. Use the measured numbers in the packs and the reports; do not invent mechanics.
- Fixtures are synthetic (see `test/p3sim/pack/Fixtures.kt` for an independent encoder of the room blob format). Never copy recorded data in.
- MC 26.3 Mojang names; look signatures up in the remapped jar (`~/.gradle/caches/fabric-loom/`) and Odin in `libs/odin`; do not guess.
- Mixins: a mixin class is a NEW file `src/mixin/Ads<Key><What>Mixin.java` (the name carries your key, so two items never collide) and belongs to you; its line in `engineerclient.mixins.json` is an integration request (the config is shared and an unlisted mixin is
  inert, so committing the class alone breaks nothing). Verify every target method and descriptor with `javap` on the remapped 26.3 jar, never from
  memory: a mixin that misses its target stops the game at start (`defaultRequire` is 1). To try one in your own worktree you may add the line
  locally; do not commit the config.
- Shared sim state you may read but not rewrite: `Sim`, `SimServer`, `Fight` (its `serverTick` and `later` included), `Blocks`, `Arena`. They are the
  boss sim's. The run reaches the world only through `RunBlocks`, `Sim.spawn`, `Services` and the bus.
- A Kotlin block comment nests: never write `/**` or `/*` inside a KDoc (a glob such as `clear/hud/**` opens a new comment). Write "and below".
- Do not push, do not touch `main` or `mc/*`, do not copy a jar into a launcher instance, do not run `deploy.sh`.

## 16. Where each requirement lands (checklist)

| requirement | where | work item |
|---|---|---|
| Run container, stage machine, savepoints, region resync | `run/Run.kt`, `Savepoints.kt`, `WorldRegions.kt` | engine |
| Arena clipped to x,z >= -8, resynced alone | an `ArenaRegion` in `run/`, `Arena.fill` calls `WorldRegions.fillAll` | engine |
| Bridge follows the stage | `P3Sim.bridge()` reads `ClientRun.bridgeInBoss` | engine |
| NORMAL difficulty, persistent mobs, distance 6 | run start | engine |
| Lag layer (Good/Typical/Bad/High slot) | `EngineModule` + `Fight.tick` | engine |
| Pasting rooms, doors carved, roofs, skulls | `LayoutModule.region` | layout |
| Countdown, doors, keys, check marks, counters | `DoorsModule` | doors |
| Sidebar, tab, map, action bar, chat | `HudModule` | hud |
| Mob factory, spawning, disguises | `MobsModule` | mobs |
| Mob HP, Implosion, bows, player ledger, death/ghost | `DamageModule` (+ existing item files) | damage |
| Secrets, crypts, Dungeonbreaker rules, score, end screen | `SecretsModule`, `ScoreModule` | secrets |
| Bots, ClearPlan, strategy hook | `BotsModule` | bots |
| Puzzle frame and easy puzzles | `PuzzlesModule` | puzzles |
| Hard puzzles, trap room | `HardPuzzlesModule`, `TrapsModule` | traps |
| Blood room (public), boss seam | `BloodModule` | blood |
| Run UI, `/ads run`, settings | `UiModule` | savepoints |
| Party presets (optional plug-in, not in this repository) | a separate plug-in mod | plug-in |

## 17. As integrated (where the merged code differs from, or settles, the sections above)

- **Run commands and dev commands are unique by name per group.** `ModulesTest` fails if two modules define the same `/ads dev` or `/ads run` name (the registry would keep the first and
  silently hide the rest). The doors module's room list is `/ads dev roomstate`; `/ads dev rooms` is the layout builder's three-room spike.
- **Class names.** `PlayerRecord.dungeonClass` is Odin's enum name in capitals (`HEALER`, `MAGE`, `BERSERK`, `ARCHER`, `TANK`) for humans and bots alike, so the leap order and the
  roster compare equal. The HUD and the leap window read any case.
- **Who writes the room marks.** The doors module only. The blood module no longer marks the blood room (the doors module answers the BLOOD stage change with RED and `WatcherDone`
  with GREEN, and keeps the counters in line).
- **Bot room notices.** A bot that could not get a mark from a service posts `ModuleEvent("bots", "clear.white" | "clear.green" | "clear.puzzle", {room})`; the doors module answers.
- **The end of the run.** `EngineModule` prints the end screen through `Services.score.showEndScreen` about 27 ticks after Necron is removed; `P4Necron` prints nothing of its own in a run
  (its made-up stats block belongs to the practice). The blood module's `BossEndNumbers` helper is therefore not used by the engine.
- **The view distance pin lasts through the boss** (Hypixel sends no chunk-radius or simulation-distance packet at the jump, and neither does the sim). The boss sim was built for the player's own distances (its bosses, crystals
  and devices are up to 150 blocks from each other), and pinned to 6 they would stop ticking when no player is within 112 blocks, so every chunk of the arena box is force-loaded from the BLOOD stage on. The pin is released when the run stops.
- **Stage message.** The run's stage is sent again every `EngineModule.STAGE_RESEND_TICKS` (100) ticks: a client still joining when the first message went out would believe there is no run.
- **Puzzle rooms count their reward chest as the room's secret.** On `PuzzleSolved` the puzzles module calls `Services.secrets.found` for the room when it prints one, except Higher Or Lower (`countsRewardAsSecret = false`): its reward is the pack's own secret chest, which the secrets module counts when a player opens it.
- **Sleeping Fels.** Only a teammate bot can hurt it (it wakes it): bots clear rooms nobody is near, where no player body exists to wake it.
- **Camp mobs whose kinds the pack lacks** (`WatcherCamp`'s `blood_giant`, `blood_boss`, `blood_mob`, and a plug-in camp's) are spawned as a stand-in mob from the pack (or the spec's `standIn` extra); their HP, modifier word and the other camp extras travel with the spec.
- **Rejected or deferred integration requests:** the `LeapTarget.profile` field (the bots' bodies use the same bare profile the fallback builds, so nothing changes); the blood module's
  `BossEndNumbers` use in `P4Necron` (replaced by skipping the made-up block, see above); a `traps.json` entry in `PackFiles` (optional file, read quietly); the hard puzzles' reward chest (not
  needed by Odin's solvers); a plug-in's `moveWatcher` host call (a no-op default); the HUD's tab-list guard for unlisted entries (the HUD builds the party from `RunState`, never from player-info packets).

