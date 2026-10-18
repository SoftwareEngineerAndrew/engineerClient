# ADS mobs: how they work and what is not measured

Files: `src/p3sim/clear/mobs/`. The module is `MobsModule`. It owns the mob records other modules read (`MobService`), the entities that show
them, the key drop on a room's last required kill, and AI v0.

## What a player sees

- Every room has its mobs before the party gets there. They appear once a player is within `Mobs Spawn Distance` (48 blocks; recorded bodies first
  appear at a median of 41) and the chunk is loaded, and never before the run clock reaches 0.
- A starred mob is a body with an invisible armor stand over it whose name is the tag: icons, the star, the name, the HP (`1.3M`, `862.5k`,
  `94,500`). The HP text and colour follow the HP the damage module sets.
- Crypt Dreadlords, Souleaters, Zombie Commanders, the three adventurer minibosses, Crypt Undead, King Midas and the Shadow Assassin reach the
  client as real players (UUID version 2) so Odin's and Blade Addons' star ESP draws them. The server keeps a light mob.
- A Fels sleeps (invisible through a real Invisibility effect, wearing nothing, cannot be hurt) until a player body comes within 9 blocks (3D).
- The last required mob of a room drops the room's key where it died, if the door module's key plan gives that room one.
- About 40 percent of the ordinary mobs walk at a player who comes close and hit or shoot; the rest stand still. Each archetype has its own measured
  walking speed. The Shadow Assassin walks (its HP is 4.2M in the clear). Minibosses walk their patrol loop.
- A miniboss room holds a miniboss only about half the time (`Mobs Miniboss Chance`, unmeasured), and its type (Lost, Angry, Frozen) is drawn per run.
- Starred and unstarred mobs both draw a prefix word. Mobs spawn facing yaw 0.
- A human's kill shows the Combat XP line on the action bar (`+655.4 Combat (total/0)`). The XP per kill and the starting total are unmeasured constants.

## Decisions

- **Rolled at run start, built on demand.** `onRunStart` rolls every room's table (`MobPlanner`) into `PlannedMob`s: ids and numbers. The
  records, and the room counters in `RunState`, exist at once, so a far room already has its required count. A body is built when a player is
  near and its chunk is loaded, and built again from the record if the chunk was unloaded meanwhile (bodies are not saved with the chunk:
  `shouldBeSaved` is false, so no stale vanilla copy ever comes back).
- **The seam to the world is `MobStage`.** The rules (counters, kills, keys, sleepers, when to build) run against a stand-in in
  `MobsModuleTest`; `RealStage` is the Minecraft side.
- **Fake players.** `AdsDisguise` extends `Avatar` (the base of Player and Mannequin). Its synched data stops at id 16, exactly what Hypixel
  sends for a fake player, so the client's `RemotePlayer` never sees a Mannequin-only id (17 to 19, the crash in the seam report): no packet
  filter is needed. `getAddEntityPacket` is overridden to add a `PLAYER` with the body's version-2 UUID. The player-info entry and the
  name-hiding team are broadcast when the mob is made (`Disguise.announce`), before any client can track the body, and retracted at its death.
  A client that joins later is told again on `PlayerJoined`.
- **Ids.** The tag is body id + 1 (+3 for the Withermancer, with two invisible nameless stands between). Entity ids come from one counter the
  client thread also uses, so `MobBodies.build` throws a set away and tries again when another thread drew an id in the middle.
- **Death.** The kill tick sends the hurt animation (yaw 0) and entity event 3 (a fake player's equipment is emptied first), no zero-health packet.
  Body, fillers and tag are removed together 2 ticks later, or 21 for the slow-dying kinds (`MobWorld.LONG_DEATH`), whose tag shows 0 HP meanwhile.
  The record is dead at once. The fake player's info is retracted, `MobKilled` is posted after the room counter dropped.
- **Vanilla stays out of it.** Bodies are invulnerable to vanilla damage, silent, fire immune, have no goals, and cannot teleport (Enderman);
  nothing in the world targets a player on its own. All damage is the damage module's, through `setHp`.

## Not measured (named constants; each is a setting "Mobs ...")

Aggro radius, lose radius, melee reach and cadence, ranged range and cadence, standing-mob reach, the miniboss chance, Combat XP per kill. The
chase speeds (per archetype, q75 of approaching windows), the Fels wake radius (9, 3D), the mover share (40 %) and the miniboss type weights are measured.
Not done yet: HP rising with clear progress and mid-life HP steps (the per-run scale is still one draw), the real weapons and armour, skins, sounds,
per-room activation of a whole room at once, idle wandering and looking around, visible arrows.
Not settings: the speed-attribute conversion (2.159 blocks per tick per attribute unit, from the player's walking speed), the navigation
re-path interval (8 ticks), the 3-block vertical reach, the size of the hurt flash.
