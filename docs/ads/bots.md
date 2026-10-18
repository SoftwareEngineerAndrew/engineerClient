# ADS bots: teammate bots in the clear

Owner: the `bots` work item (`src/p3sim/clear/bots`). Code and tests are in `src/p3sim/clear/bots` and `test/p3sim/bots`.

## What the player sees

Up to four teammates (the classes no human plays, taken in the boss sim's leap order) run the dungeon around the human. Each is a player of its own on
the client: a name from a built-in list of invented ones, a rank (MVP+ 69%, VIP 12%, VIP+ 12%, MVP++ 6%), a SkyBlock level and class level from the
recorded spread, an optional glyph after the name, and a version 4 profile id like a real account (`BotIdentity`, drawn from `run.subRandom("bots.identity")`).
The nametag reads `[level] name glyph` the way Hypixel draws it, from a scoreboard team (prefix, suffix and name colour). The setting `Bots Use Player Names`
off gives them the class word as the name (Healer, Mage...) as the boss practice names them. `PlayerRecord.extras` carries `sbLevel`, `classLevel`, `rank`,
`rankBracket`, `nameColour` and `glyph` for the tab, chat and map; `PartyStyle` reads them.

**Bodies.** `AdsBotBody` is a light server mob (an `Avatar`, like the mobs module's fake players) that clients are told is a `minecraft:player` with the bot's
profile id (`getAddEntityPacket`), so Odin's teammate binding and class glow see it. Before the body spawns, each human's client gets the unlisted tab entry
(the client refuses a player add without one) and the name team (collision rule never, so nobody is pushed). The setting `Bots Are Players` off goes back to
a mannequin with a custom name. `AdsBotsBodyMixin` (needs its line in the mixin list) makes the bots unpickable on the client, so the crosshair, clicks and
arrows go through them as before; `BotRegistry` holds the run's bot ids for it. A body that moves more than six blocks in one tick (a blink or a leap) is
removed from the clients' world and added again at the destination in the same tick (same entity id). A body that the plan takes through a wall stops at it
and slides along it; it goes to the plan only after 40 ticks or when the plan is 16 blocks away.

**Life of the body** (`BotMotion`): arm swings 18 a minute while it works in a room (and one on each kill, with the mob's blast and now and then the bot's own
teleport tick); the main hand changes about 12 times a minute (Aspect of the Void 33%, Hyperion 32%, Dungeonbreaker 15%, Bat Wand 9%, Terminator 5%, Spirit
Leap 4%, Superboom TNT 2%; which item goes with which work is an unmeasured bias); sprinting above 0.6 blocks a tick; crouching in a fifth of the standing
stretches of one to three seconds; the head turns on its own (standing: every 20 to 60 ticks, within 60 degrees; walking: short glances about the heading).
Footsteps are one every 14 ticks while walking, the glass tick on most floors and the floor's own step on gravel, wool, wood and grass (`StepSounds`; the
mapping from floor to sound is assumed).

**Countdown** (`Countdown`): the bots appear on the block the human spawned on, drop to the floor, and walk straight across the entrance room to a spot
two blocks inside the exit door, in two or three stretches with pauses; the first plan starts them there.

**Health and deaths** (`BotHealth`, `TeamDeaths`): each bot has its own full-health number (`extras["maxHp"]`, from the class table); a mob lands an
ordinary hit about 2.2 times a minute while it fights (never below 25%), with the hurt animation. Teammate deaths in the clear are rare (0, 1 or 2 with
the weights 409, 19, 5 of the 433 recorded clears): a death waits for a bot in a room with a living mob, hits it two to four times in the last 40 ticks,
and kills it by that mob ("was killed by X and became a ghost"). A ghost has no body, makes no sound, does no work and keeps its place on the map; the plan is
redrawn without it and takes it back at the revive.

One of them is the door crew: it goes straight to the door and is within five blocks of it when the door falls (the executor holds the open until it is).
The player who picks up the wither key is another bot in three doors out of four (`KEY_SPLIT_CHANCE`): the nearest free bot clears the key room as a normal
room visit, so its mobs die when the key is due and its secrets follow. Two bots share the doors in about four runs in ten (the recordings: one player opens
all doors in 58% of games, two in 36%). The others clear rooms mobs first and then their secrets, and a room's mark turns white and green on the measured
schedule. There is no bot combat: the plan says when a room's required mobs die and the bot's hands (`BotWorldImpl`) kill them through the damage service, so
every kill is a real `MobKilled` (the room's mark changes, the key drops). A bot's body is only drawn while a human is near; the plan runs whether or not
anyone sees it.

When the human walks into a room a bot was about to clear, the bot gives it up and everything is redrawn. A room the plan gave to the human
and nobody has been into `Bots Cover After` ticks after its time is covered by the bots from then on ("Bots Cover Your Rooms"). While the
human stands in a room a bot was about to finish, the bot holds back the last mob (`Bots Wait For You`).

## How the plan is made (`ClearPlanner`, pure)

1. Door and key schedule. The k-th paid wither door falls at a time drawn from the pack's per-door table (`timing.classRoles.doorOpenAfterT0Ticks`),
   the blood key from `splits.F7.bloodKey`, the blood door a drawn `doorAfterKey` later. One "pace of this party" draw (a Gaussian copula,
   `BotTuning.PACE_CORRELATION`) moves them together so the marginals stay exact while a fast party is fast everywhere. Each key is due
   `keyObtainToUseTicks` before its door; its key room (the path rooms from the one after the entrance to the one before the blood-key room, minus the
   Fairy room) is whitened by the door crew at that tick. Every time is held to what is physically possible: a room is reachable only when the
   doors on the way can be passed (`pass` = fall tick + 15), the opener needs the walk, a door follows the last by at least 12 ticks.
2. Doors become duties (`crewDuty`): with `KEY_SPLIT_CHANCE` another bot takes the key room (a normal room visit whose mobs die at the key tick) and the crew bot walks
   straight to the door and stands 1 to 5 blocks from it (`OPENER_STAND_BLOCKS`); otherwise one bot does both. `OpenDoor` carries the door spot, and the executor waits
   until the opener is within `OPEN_REACH` (5 blocks) of it (a late walker delays the door).
3. Rooms by list scheduling: the seat that is free soonest takes the room it can start soonest (a puzzle room prefers a puzzle class). Mobs first:
   a room whose mobs are not dead beats one that only has secrets left; a seat leaves a room after the mobs when other rooms wait, and the secrets
   go back on the list (`GREEN_ONLY`). A seat's occupancy in a room is the pack's first-entry-to-green time times `BotTuning.ROOM_WORK_SCALE` (see below).
4. Walks go through the door graph at the class's moving speed from the pack, held at closed doors. A leg that has to be faster than a walk is played
   as blink hops of 10 blocks. Inside a room the walk goes round what is in the room: `RoomGrids` reads the room's block volume into a `RoomGrid` of walkable
   columns (a block to stand on, two free blocks above; liquids, walls, bars and fences are in the way), `PackRoomAreas` keeps one per placed room, and
   `RouteExpander` replaces each stretch inside a room by the shortest path pulled taut (A* over the columns). In a room a bot wanders (`RoomWalk`): the next place is
   the room's next secret now and then, else anywhere on the floor, each stretch at a log-normal speed (median 0.38 blocks a tick, 90th percent 1.3), a stop of
   half a second to five seconds after about half the stretches; it fills the room's time exactly. A secret in a closet is approached as far as the wall. Where
   the optional walks pack has recorded walks for the room design, one is played instead (mapped through the room's placement, fitted to the time the plan gives
   the room). A room without block data is wandered over its tiles (`FreeArea`).
5. The party strategy (`RunConfig.strategy`, default `neutral`) says which seat goes to the blood room and by when (`BloodAssignment`). The neutral
   strategy sends nobody. Presets come from the plug-in seam; a plug-in that throws or names the human gets the neutral answer.

The plan also draws the Watcher-done and Maxor ticks from the pack's milestone tables. Those two are not the bots' to decide (the blood module owns the
camp); they are in the plan so the headless gate can check the whole milestone chain, and so the room schedule knows the clear's expected end
(a seat short of time takes the mobs and leaves the secrets).

Re-planning (`ReplanState`, then the same planner): the human takes a room, opens a door, finishes a room early, leaves, the bots start covering,
a savepoint is restored, or `/ads dev botreplan`. The new plan starts now from the run's real state: finished rooms are dropped, a white room only
needs its secrets, open doors are open, a bot part-way through a room keeps it, the door crew stays the door crew. At most one re-plan per 10 ticks.

## Calibration and the gate

`ClearSim.gate` builds N plans (500 by default, each its own seed and layout) with five bots (the last seat parked in the blood room, so the room schedule is checked with one seat busy there), compares the five percentiles of: blood door open,
tiles completed at Maxor, share of rooms green at Maxor, with the pack's recorded ones. The real-pack check runs with
`ADS_REAL_PACKS=<packs/out> ./gradlew test` (it is skipped without it) and in game with `/ads dev botsim [plans] [work scale]`. Both read the rooms' floors, so the
longer walks round the rooms' walls are part of what is checked.

Result against the real pack (2026-10-17, 500 plans over the 400-layout default pool, scale 0.3, floors read from the rooms):

```
blood door open        simulated 219, 284, 339, 425, 534    recorded 199, 256, 310, 383, 536
tiles done at Maxor    simulated 34, 34, 34, 35, 35         recorded 34, 34, 34, 35, 35
rooms green at Maxor   simulated 0.57, 0.71, 0.81, 0.90, 1.00  recorded 0.67, 0.76, 0.81, 0.86, 0.91
```

`ROOM_WORK_SCALE = 0.3` is calibrated, not measured. The pack's room durations run from the first entry of anyone to green, and teammates join a room
part-way, so one seat's own time in it is shorter. The scale was 0.5 when the door crew idled through every key room, 0.4 when it visited them as real work, and
0.3 now that the walks between rooms are longer (they go round the walls). Tiles completed are right from about 0.8 down. Re-run the gate after changing a table.

Movement against the recordings (real packs, `RealRoomWalkTest`): inside rooms about a quarter of all seconds are still (recorded 25.5% over the whole clear), more than
half are slow (0.1 to 0.6 blocks a tick); the dashes of 20 blocks a second that make 11.7% of the recorded seconds are the walks between rooms, which are
planned at the pack's moving speed (1.2 blocks a tick, blink hops included) and come out as a third of all seconds in the plan. That is a travel-pace question for the
door schedule (bots idle less between rooms than recorded teammates), not the wander's.

## Messages to other modules

- `ModuleEvent(source = "bots", name = "clear.white" | "clear.green" | "clear.puzzle", data = {room: Int, seat: Int, player: String?})` is posted once per room and kind when
  the services could not produce the mark (a room with no mobs ever spawned, a puzzle room with no puzzle object, a room whose placeable secrets run out before its printed
  maximum). The doors/state module should answer: `clear.white` -> `setCheck(room, WHITE)` (GREEN straight away if the room's `secretsMax` is 0); `clear.green` -> GREEN;
  `clear.puzzle` -> GREEN for a puzzle room (status SOLVED if the puzzles module has none).
- The bots call `Services.doors.tryOpen` only when the party holds the key (`keys.witherHeld` / `keys.bloodHeld`), so the no-key line is never spammed.
- On `KeyDropped` a bot that is already within 6 blocks of the stand steps onto it for 4 ticks, at walking pace, so a pickup by position (`RunState.players`) sees it; nobody is sent
  from across the dungeon. If nobody has taken the stand 20 ticks later and no human is at it, the nearest bot within 12 blocks walks there; otherwise the doors module's own pickup
  rules (and its 200-tick auto-collect) apply.
- Bots keep `RunState.players` records (`isBot = true`, each with its own version 4 profile id; the class-word look keeps the boss sim's ids); position is refreshed every tick by this module. `PlayerJoined` is posted for each
  bot on the first tick.

## The optional walks pack `bots.traversals.json.gz`

Not in the jar or the repository. A tool outside the repository builds it from a corpus of recorded rooms. gzip JSON:

```
{"format": "ads-bot-traversals", "version": 1,
 "rooms": {"<design id from the rooms pack>": [ {"ticks": 120, "pts": [[0, x, z], [5, x, z], ...]}, ... ]}}
```
`pts` are `[ticks since the walk began, x, z]` in the room's CANONICAL frame (the rooms pack's), a few ticks apart. Missing file: plain routes. A walk is used when its
length fits the time the plan gives the room (60% to 140% of recorded); the planner picks among the three whose start is nearest to where the bot comes in.

## Settings

`Bots Show Distance`, `Bots Speed Scale`, `Bots Cover Your Rooms`, `Bots Cover After`, `Bots Wait For You`, `Bots Use Player Names`, `Bots Are Players` (all in the ADS module).

## Dev commands

`/ads dev bots`, `/ads dev botplan [rooms]`, `/ads dev botreplan`, `/ads dev botsim [plans] [work scale]`.

## In-game checks (expected results)

1. `/ads run random`: at the start four teammates appear on your block, drop to the floor and walk to the entrance room's exit door; the tab and the leap window list the same four, with their own names, ranks and levels. With Odin's class glow on, a teammate in view glows in its class colour (needs `AdsBotsBodyMixin` in the mixin list for the crosshair to go through them).
2. After "Starting in" the entrance door falls; a bot kills the first key room's last mob, a key stand appears, a bot already near it steps onto it, and the opener (usually another bot)
   stands within five blocks of the door when it falls at the planned tick (`/ads dev botplan` lists `opens t=...`). Wither doors open about 60, 140, 210 ticks after T0 at the median.
3. The blood door falls at about 310 ticks after T0 (median), the clear continues; rooms turn white and then green on the map without the human.
4. Walk into a room a bot is heading for: within half a second `/ads dev bots` shows a new plan (`Last re-plans: ... took room N`), and that bot has a different room next.
5. Stand still in the entrance room for `Bots Cover After` ticks past your first room's time: the bots cover your rooms (the room list in `/ads dev botplan rooms` has no `(you)` rooms).
6. Spirit Leap lists the bots; leaping to a far bot lands on the floor next to it.
7. At the boss entry the clear bodies are gone and the boss sim's own bots take over (same names and heads).
8. `/ads dev botsim` prints the five rows above and `All within tolerance.` with the real packs.
