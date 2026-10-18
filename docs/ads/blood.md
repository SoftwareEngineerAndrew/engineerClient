# Blood room and boss seam (work item `blood`)

What the blood module builds, the Watcher's camp (exact, in `camp/`), and the seam a plug-in can use to replace the camp.

## Flow

```
BloodDoorOpened (doors module)
  -> BLOOD stage, blood room RED, the Watcher (+2 Watchful Eyes, name tags) hangs over the middle, 30 wall-head stands, camp starts
  -> a human steps into the room: the red "The Watcher" boss bar (fill = his health), resent every 20 ticks
  -> the camp ticks until it says it is finished (a plug-in's camp if one supports the build, else WatcherCamp; NeutralCamp only for a room with under 15 wall heads)
       its counted kills (watcherKills) lower his health 200 each; the 15th kill is the DEFEAT (D)
  -> the camp ends: the Watcher says his pass line (D .. D+20), WatcherDone
  -> D+79 / D+89   lightning bolt entity at the alcove
     D+80 / D+90   thunder + explosion at the strike, 12 nether portal blocks appear, ModuleEvent("blood", "portal")
     D+81          the Watcher, his Eyes and tags are removed
  -> one tick after a living member's body first touches the portal: BossEntry.enter, the whole party to P1,
     Maxor's bar renames the Watcher's bar in place, stage BOSS_ENTRY, Run.beginBoss(no hotbar, first line +2)
```

Nobody has to be near the portal and no timer sends the party: it waits for the first touch. The pass line is a different clock
(the camp's chat queue, D to D+20) and starts nothing. A camp that never reports kills (an older plug-in) is taken
to be defeated at its pass line.

`BloodFlow` is the pure state machine for this (phases CLOSED, CAMP, PASSED, PORTAL, ENTERED; several steps can fall due in one
tick, the module asks again until it answers NONE); `BloodExit` is the measured clock; `BloodModule` wires them to the world.

## Pieces

- `BloodGeometry`: floor height, the 30 wall-head stations and the exit alcove. Every Blood build has the same fixed stations (canonical offsets from the middle column (-12,-4) (-12,0) (-12,4) (12,-4) (12,0) (12,4) (-9,-12) (9,-12) (-9,12) (9,12), three rows at cells 71/75/79), so nothing is searched for; the portal is x14..16, y69..72 at z29 and the strike on the middle column of it.
- `WallHeads`: one invisible armor stand per station with a player head on it (no skull blocks), made with `Sim.spawn`; a thrown head flies on a `HeadFlight` (straight line, spinning, position sync every 3 ticks) and is gone on arrival. Clicks on a stand are swallowed.
- `HeadItems` / `HeadTextures`: a camp's key (texture id or pack role) becomes a player head item.
- `WatcherEntity`: invisible zombie 4 blocks over the floor facing the door (3000 health, swirl = endless Invisibility with particles), two Watchful Eyes (1000 health) a couple of blocks away, a marker name-tag stand over each. Heads come from the rooms pack by role `watcher` / `watchful_eye` (`packs/rooms/add_blood_textures.py`). He speaks in chat only.
- `BossEntry`: `strike` (the bolt), `openPortal` (sounds + blocks), `enter` (bar, teleport, stage). `BossEntry.clearPortal` makes the alcove an open doorway when the door opens (the recorded build `Blood__b2` has the fight's portal standing in it).
- `BloodHost`: the camp's view (`BloodCampHost`). The seam carries `watcherKills`, `flyHead` and `moveWatcher` with a yaw as members with default bodies, so a camp written against the older seam keeps working.
- `camp/` (package `clear.blood.camp`): the Watcher's camp as the real server plays it. See below.
- `NeutralCamp`: the stand-in for a room with under 15 wall heads, a fixed number of ordinary mobs on a fixed timer; reports its dead mobs as counted kills.
- `BossEndStats`: the real score, grade, time, secrets and deaths for the boss's end chat.

## The Watcher's camp (`camp/`)

One server task, every 10 ticks from the blood door's click ("the loop"; ticks below are door ticks). `CampMech` holds the constants and the
rules (flight rule, chat queue); `CampEngine` is the state machine (no Minecraft); `WatcherCamp` puts it into the room through `BloodCampHost`;
`WallPlan` is the wall and the throw order; `CampMobs` what a camp mob is made of; `CampLines` his words; `WallLook` the head textures.

- **The wall.** 30 stands: one giant, the four revived bosses, the 17 standard mobs once each, the Wandering Soul and 7 heads that make
  nothing, redrawn onto the stands every camp. The wall's textures are NOT in the mod: the local rooms pack carries them under the roles
  `blood_giant`, `blood_boss_1..4`, `blood_mob_<name>`, `blood_soul`, `blood_decor_N` (`packs/rooms/add_blood_textures.py`); a pack without them
  gives plain heads and the camp plays out the same.
- **His opening line** comes 3 ticks after the door.
- **The opening.** From 90, every 30 ticks while he is idle, he flies at 0.70 blocks a tick to one block over a wall head and throws it 10 ticks
  after coming to rest: a giant, a revived boss and two standard mobs. Each thrown head flies 80 ticks along a straight line toward the room centre
  (16 blocks, spinning) and the mob is added where it ends, 5.5 to 7.6 blocks over the floor, falling to it. Then he flies home at 0.50.
- **Later throws.** 11 more standard heads, each drawn uniformly from the standard heads still on the wall (a 12th, the Wandering Soul's, in about 3% of
  camps; its mob is never counted). From 420 on a loop tick where he is idle: with no camp mob alive he leaves FAST (0.6125) on the ticks that are
  multiples of 40; with any alive, SLOW (0.4375) on multiples of 60. A mob counts alive on a decision tick if it is dead only on that tick or
  later. The first later leg also needs 40 ticks at home (SLOW) or no line of his around it (FAST). A later head flies 40 ticks (12 blocks); its mob is added
  39 ticks after the throw. After the last throw he flies home on the next idle multiple of 40.
- **Kills.** A mob that dies on tick d is counted on the first loop tick after d; the Wandering Soul is not counted. Each count takes 200 off his
  3000 health (`watcherKills` to the host); the fifteenth is his defeat.
- **His chat queue** is first in, first out; each line keeps it busy 42 ticks (a reaction to a kill, the opening line) or 62 (a line on a throw, the line when he
  flies home, the farewell). A kill gets a reaction line half the time and a throw a line half the time. The pass line prints on the defeat's loop tick, or when
  the queue frees up within 20 ticks after it.
- **The mobs.** Fake players (profile "Undead", stone sword and chainmail, walking 0.31) and the giants; HP uniform 248,000 to 256,000 for the
  standard mobs, 10M for a boss and 15M for a giant on a x1.3 ladder, a modifier word (Speedy, Healthy, Stormy, Stealth, Golden, Boomer at the measured shares),
  Healthy x1.3, Ooze x1.25. They go to the mob service as `blood_giant` / `blood_boss` / `blood_mob` with the HP and the look in the spec's extras.
- **Savepoints.** `WatcherCamp.snapshot` keeps the whole engine state (wall, throw order, mobs and their ticks, the queue); `restore` brings it back and
  `BloodHost.adopt` takes the mobs the mob service restored as the camp's again. Not played in the game yet.
- A plug-in's `BloodCampFactory` takes the place of all of this when it supports the room.

## Settings

`Blood Camp Timeout`, `Blood Wall Heads`, `Blood Placeholder Mobs`, `Blood Placeholder First Mob`, `Blood Placeholder Interval` (the last three only for a room the Watcher's camp cannot run).
(The old Blood Entry Delay / Wait For Party / Wait Max settings are gone: the real entry is the portal.)

## Dev commands

`/ads dev blood status|open|pass|kills N|enter|heads`. `kills 15` is the defeat (portal 80 or 90 ticks later); `enter` skips every wait.

## Unmeasured

The camp timeout; the rung a giant or boss sits on of its HP ladder (drawn from weights, the cause is not known); which of the four boss textures goes with which boss name; the camp mobs' look until the mob module reads the extras; the spin of a flying head (4 degrees a tick, read off one recorded stand); the stand-in's numbers; Eye drift (they keep their offset while he flies); the sound volumes are recorded, the Eyes' random offsets follow the recorded sides.
