# ADS full run: the in-game test script

For the maintainer. Nothing in the full run has been played yet: every stage was built and tested headless (unit tests, the real data packs,
real Minecraft chunks written in tests), none in the running game. So this script goes in the order the work stands on itself:
if a gate fails, stop there and send me the log lines named under it, because everything after it depends on it.

Where the integration changed what an implementer's note said, this script follows the code as merged on `ads/run`.

## 0. Before you start

**The jar.** Build the ADS branch (`gradle build`, MC 26.3 line) and put the jar where you test. The line you test on
(26.1.2) needs the usual port first; the full run adds four mixins (`AdsEngineChunkPaceMixin`, `AdsBotsBodyMixin`, `AdsTrapsQuietHookMixin`,
`AdsTrapsQuietDispenserMixin`) and writes a data pack into the world folder at run start (the sky-light-free dimension type), so the port must check the mixin
targets, the `packVersion(PackType.SERVER_DATA)` call and the dimension-type file keys. `ViewPin`, the HUD's tab
packet (a reflective call into `ClientboundPlayerInfoUpdatePacket`) and the lag layer (a reflective write of
`MinecraftServer.nextTickTimeNanos`) touch Minecraft internals that can differ between versions. Each of the three logs a plain warning and
switches itself off instead of failing, so the first thing to read after any run is `latest.log` for lines starting `[ads]`.

**The data packs.** Nothing to copy: the six pack files (`layouts.json.gz`, `rooms.index.json.gz`, `rooms.blocks.bin`, `mobs.json.gz`,
`runstate.json.gz`, `traps.json`) are inside the jar. The first run unpacks them once into
`<instance>/minecraft/config/engineerclient/ads/packs-bundled/<hash>/` and reuses them after that.

To try a different build of a pack without rebuilding the mod, put the file with the same name in
`<instance>/minecraft/config/engineerclient/ads/packs/` (make the folder). That copy wins for that file; put `rooms.index.json.gz` and
`rooms.blocks.bin` in together. Delete the file to go back to the bundled one.

`/ads dev packs` reloads and says what loaded and, for each of the six files, "the copy bundled in the jar" or "your copy in <folder>
(overrides the jar)". Expected with a fresh instance: all six from the jar, rooms 144 designs and 257 cores, layouts with the default pool,
mobs 22 archetypes, run-state loaded, no "could not load" message. A packs folder that is missing or empty is normal now. The only way to see the
"could not load its data packs" message is a damaged jar, a config folder the game cannot write to (the message then says to check the folder and the disk, not to
reinstall) or a bad override file (which names the file), and it must be ONE plain message, never a crash. Test the override first (Gate A).

**Odin.** Use your normal Odin with the ADS module on (the full run turns it on itself) and the dungeon features you want to check:
map, room names, Highlight (star ESP), Puzzle Solvers (Weirdos, Quiz, Beams, Boulder, Ice Fill, Tic Tac Toe, Blaze, Water Board,
Teleport Maze), Terminal/leap features. Devonian and Blade Addons are optional but worth having for the HUD gate.

**Reading results.** `/ads dev events` lists the last bus events; `/ads dev runinfo` says stage, clock, party, lag, view-distance pin and
the world regions; `latest.log` has every module's warnings with `[ads]`. Dev commands need you inside the ADS world (they say
"Open ADS first" otherwise), except the ones that read files or start a run.

**Starting.** Three ways, all the same call: the small **Run** button beside **ADS** on the title screen; **Full Run** in the ADS menu
(Esc, ADS Menu); `/ads run start`, `/ads run random`, `/ads run seed <n>`, `/ads run layout <id>`. Options after the layout:
`bots on|off, party 1-5, lag off|good|typical|bad|highslot, nodeaths, hp base|pack, strategy auto|neutral|<id>`. Starting rebuilds the world (a few seconds).

## 1. Commands at a glance

Run commands (`/ads run ...`): `start` (the screen), `random`, `seed <n>`, `layout <id>`, `restart`, `restart room`, `savepoint`,
`savepoints`, `restore <number>`.

Dev commands (`/ads dev ...`), by module:

| module | commands |
|---|---|
| engine | `packs` `events` `difficulty` `cost` `costnow` `lag` `launch` `stubrun` `stage` `boss` `savepoint` `savepoints` `restore` `runinfo` |
| layout | `rooms` (the three-room spike) `layout` `probe` `resync` |
| doors | `doors` `roomstate` `givekey` `opendoor` `clearroom` |
| mobs | `mobs` `disguise` |
| damage | `hurt` `heal` `cast` `mobhit` `ledger` |
| secrets, score | `secrets` `secretgo` `secretgive` `secretcrypts` `secretboom` `secretbonus` `scorenow` `scorecalc` `scoreend` |
| puzzles, hard puzzles, traps | `puzzles` `puzzlesolve` `puzzlereset` `draft` `puzzledraw` `hard` `traps` |
| bots | `bots` `botplan` `botreplan` `botsim` |
| blood | `blood` |
| hud | `hud` |

`/ads dev rooms` is the layout builder's three-room spike. The doors module's room list is `/ads dev roomstate` (it was `rooms` in the
doors note; renamed because two modules had the same name).

## 2. The gates, in order

### Gate A: nothing changed for the boss practice, and missing packs are polite

1. Title screen: the ADS button is a little narrower, with **Run** beside it. Click ADS: the boss practice opens as before.
2. In it: `/ads p1`, play into P2, use Restart and Stop. No `[ads]` errors in `latest.log`, Odin splits as before, a party joiner over the
   LAN relay still joins. The practice's Superboom is still infinite; the end of P4 still prints the made-up stats block (a full run prints
   its own instead).
3. Leave the packs folder missing or empty. `/ads run random` starts a run from the bundled packs, and `/ads dev packs` lists all six files
   as "bundled in the jar".
4. Make the packs folder and put a damaged `mobs.json.gz` in it (for example a text file with that name). `/ads dev packs` names the mob
   tables as unreadable, in plain words, and a run still starts (without mobs). Delete the file: `/ads dev packs` is clean again.

### Gate B: the engine (stage 3), no packs needed

(Open ADS from the title screen for 1 to 3, then use `/ads dev` for the rest.)

1. `/ads dev difficulty` with P1 running (start `/ads p1` first). Expect "Difficulty PEACEFUL -> NORMAL", then 20 s later "Difficulty audit,
   20 s: N sim entities seen (...), K gone, T mobs had a target. Your health 40.0 -> lowest 40.0." Pass: Maxor and the crystals are still
   there, 0 mobs had a target, health unchanged. Anything under "gone" is a sim mob that vanished on NORMAL. Repeat in P2, P3, P4.
   `/ads dev difficulty peaceful` puts it back.
2. `/ads dev lag typical 60`, then `good 60`, `bad 60`, `slot 60`. The game should stutter: the world freezes, then rushes. After the
   seconds: "Ticks, last 60 s: N ran, X lost = Y a minute (target 30.3)". Y near the target (one minute is noisy; repeat 3 to 5 minutes).
   "Freezes of 100 ms or more: n, longest ...ms" and "Freeze-then-burst: yes" after a freeze of 500 ms or more; each listed freeze shows a
   burst of ticks about 25 ms apart. If it says the tick clock cannot be reached, lost ticks are caught up in full: send me the log line
   `[ads] the lag layer cannot reach the server's tick clock`. `/ads dev lag report 120` reprints; `/ads dev lag off` stops. The Odin
   setting "Lag Preset" does the same in a boss phase.
3. `/ads dev cost` rebuilds the world with a made-up 169-chunk roofed dungeon beside the arena. Stand still when it opens. About 5 s after
   joining you are moved to a stub room and the report appears (chat and log): chunk load time, MSPT (mean, p95, max) with 0, 300 and 750
   static mobs at distance 6, client FPS with 430 entities, `Arena.resync` ms, and the "Time to playable" step times. Then
   `/ads dev cost base` (plain arena) for the comparison; `cost own` keeps your view distance, `cost quick` shortens the windows,
   `/ads dev costnow` measures the world you are in. **Write the numbers down: none of them are known yet.**
4. `/ads dev stubrun` (no packs): the world rebuilds to the stone-brick stub dungeon and you spawn in the entrance room (tile 0,0, you stand
   at y 69). `/ads dev runinfo`: "Run run-...: COUNTDOWN ... Regions: stub-dungeon, arena" and "View pin: on (yours were R/S)". Esc video
   settings show render and simulation distance 6 while it runs and your values back after Stop. `/ads dev stage clear`, `blood`, `entry`
   each say "Stage is now ..."; a repeated `clear` is refused.
5. Savepoints in the stub run: `/ads dev savepoints` lists "sp1 ... Start". Walk into another room, `/ads dev savepoint test`, move on,
   change your hotbar, `/ads dev restore sp1`: you are back where you were, the hotbar is back, "Restored sp1.", the clock continues from
   the saved value (`/ads dev runinfo` shows t). Room savepoints are taken when a human enters a room (needs the doors module).
6. Boss handoff: in the stub run `/ads dev boss`. Stage BOSS, Maxor's first line about 2 ticks later, your hotbar NOT replaced, the dungeon
   still standing, Odin's boss features on. Kill Necron: about 27 ticks after he is removed the run ends (stage END, the end screen; no
   made-up stats block). Starting a boss phase from the menu during a run ends the run cleanly (view distance restored).

### Gate C: the layout builder and Odin (stage 4). THE gate for everything that follows

1. `/ads dev rooms`: "Building the three-room spike: 3 rooms, N door and wall edits. Rebuilding the ADS world...". About 2 s after joining
   you are in the Andesite room (tile 1,1). Within about 20 s the probe prints "Odin recognises all 3 built rooms" with three green lines:
   Andesite SOUTH on (1,1); Blue Skulls WEST on (2,1); Pirate NORTH L on (1,2) (2,2) (2,3).
2. Look at the walls: a closed wither door of coal blocks with a stone-brick frame, 3 wide and 4 tall, on the east wall of Andesite; a plain
   doorway with a frame between Blue Skulls and Pirate; every other side solid, no holes to the void.
3. `/ads dev probe` any time: "in dungeon true", the room you stand in, rooms entered. (In the spike no run exists, so the bridge still says
   "in boss" and Odin reports no room entries; names and rotations must not depend on that.)
4. `/ads dev layout random`: "Layout <id> (seed N): ~21 rooms, ~20 doorways", world rebuild, you in the entrance room. `/ads dev probe`:
   "Odin recognises X of ~21" (only rooms whose centre chunk is loaded can be named: walk and run it again). Note the printed fill timing
   (headless 50 to 100 ms for 169 chunks). `/ads dev layout <id>` and `/ads dev layout seed 42` twice: the same seed gives the same layout.
5. `/ads dev resync` right after a build: "Resynced N chunks ... 0 blocks put back". `/setblock` a stone inside a room, `resync`: 1 block put
   back and the block restored.
6. Skulls show textures (about 80 percent of the pack's skulls have one), signs show their text, chests exist but are empty.
7. Leave and open ADS from the title screen normally: the boss arena only, no dungeon blocks at x,z below -8 (the region must not leak).

**If Odin does not name the rooms in a real run (Gate D)** read `latest.log` and run `/ads dev probe`: the usual cause is chunks that loaded
before Odin believed it was in a dungeon. The client now sets Odin's area (Dungeon) and floor (F7) at join to prevent exactly that; the
probe says how often it had to re-offer a chunk.

### Gate D: a real run starts (engine + layout + doors, stage 4 and the countdown)

Start `/ads run random` (or the Run button). Expected sequence:

1. "Loading the data packs..." then "Building <layout> (seed N)..." then a "Building the dungeon..." screen, the world opens on NORMAL difficulty
   with the party at the entrance room (facing south, tile centre, standing at y 69). `/ads dev runinfo`: stage COUNTDOWN, the world regions
   `dungeon` and `arena`, view pin on.
2. About 2 s later "Starting in 4 seconds." with a lever click, then 3, 2, 1, one per second (the wait before the 4 is the setting "Doors
   Countdown Delay", 40 ticks). Ready lines come just before the 4. About 1 s after "Starting in 1 second." Mort says three lines
   (40 and 30 ticks apart) with a villager sound each; the stage turns to CLEAR on the first.
3. About 2 ticks after Mort's first line the entrance door turns to barrier (one tick earlier the free Fairy door), 14 to 15 ticks later
   both are air. No "opened a WITHER door" line for either. `/ads dev doors` lists both OPEN. **A short hitch when the far Fairy door's
   chunk is written is expected; tell me how long.**
4. `/ads dev roomstate` right after the start: the entrance NONE then GREEN 5 ticks later, the Fairy room GREEN, the rooms past the entrance
   door and before the Fairy room NONE; "Opened 3, completed 1" and the sidebar `Cleared` at 3 percent.
5. Odin: the map starts filling in, the room names appear as you walk, the entrance is named at once. At this point Odin's leap menu lists
   the party (it reads the tab list; the bridge no longer writes the party).

### Gate E: the HUD channels (stage 4b)

1. Sidebar during the countdown: title `SKYBLOCK`, a date line, `The Catacombs (F7)`, party lines like `[M] Name [Lv50]`, `Starting in: 0:04`
   counting down, a `www.hypixel.net` footer. At Mort's first line it switches to the running layout: a `Keys:` line, `Time Elapsed: 00s`
   ticking, `Cleared: 0% (0)`, only your teammates listed (not you), the footer. The `Keys:` line goes when the blood door opens.
2. Tab list: exactly 80 rows in 4 columns, no real player names visible. Column 1: `Party (N)`, then `[250] Name (Class L)` rows with `Ultimate` and
   `Revive Stones`, then `Player Stats`. Column 2: team deaths, damage and healing, milestone, discoveries, secrets, crypts, `Dungeon:
   Catacombs`, `Opened Rooms`, `Completed Rooms`, `Secrets Found: P%`, `Time`, `Puzzles: (n)` rows. `/ads dev hud dump` writes
   `config/engineerclient/ads/hud-dump.txt` with the raw rows, the sidebar lines and an ASCII map. If the tab list does not show, the log
   says `[ads] hud:` and the rest keeps working.
3. Hold the Magical Map after Mort speaks: the entrance green with the next room grey and a `?`; a door that falls or a room entered takes its
   colour; wither and blood doors show black and red until opened; a cleared room gets a check mark (white for mobs, green for all done);
   your marker is a frame and each living teammate a blue marker; markers update every 5 ticks without redrawing everything.
4. With Odin's map and room features on: the map, the room names and the check marks all show; the leap menu shows the teammates, their
   classes and the sorting from the tab list; Odin's secrets counter follows the action bar.
5. Action bar in a room with secrets: `<hp>/<max>  <defense>  <mana>/<max>` (three glyphs), ten spaces, `N/M Secrets`.
   `/ads dev hud bar Gold Essence +10` replaces the defense slot for 3 seconds. `/ads dev hud chat hello`, `/ads dev hud title hi`.
6. Turn each of the four `Hud ...` settings off in turn: the matching channel stops. End the run (`/ads stop` or finish): the sidebar and fake
   tab rows vanish, real players are listed again, no `[ads] hud:` warnings.

### Gate F: mobs (stage 5)

1. Fake-player spike, no run needed: `/ads dev disguise`. Chat: a Crypt Dreadlord stands 4 blocks ahead, body id N, tag id N+1, uuid version
   2, body class AdsDisguise. About 1.5 s later the client lines: "Body: RemotePlayer ... uuid version 2"; "Tag at id N+1 ... matches the star
   regex: true"; "Odin star highlight (isValidEntity): accepts it: true"; "Blade Addons look: kind STAR (or TANK)". A player-shaped figure
   with a floating gold-star tag `... Crypt Dreadlord 840k` and NO vanilla name tag. **The game must not crash on spawn** (that was the
   Mannequin data-id crash). `/ads dev disguise clear` removes it.
2. In a run: bodies appear when you are within about 80 blocks (not before), each with a tag; starred mobs get Odin boxes, the disguised ones
   (Dreadlords, Souleaters, Commanders) and the miniboss included. `/ads dev mobs` lists each room as "N/M required alive, B/A bodies built"
   and any key room with no required mob.
3. Tag text: the prefix word recolours the name; HP is green above half and yellow at or below half; hits update the number
   (`1.3M` -> `862.5k` -> `94,500`).
4. Fels: nothing visible; hitting where it should be does nothing; stepping within 5 blocks makes it appear, after that it takes damage and
   chases. A teammate bot clearing a Fels room wakes it (no body needed).
5. Movers: stand 8 to 12 blocks from ordinary mobs: about one in four walks to you and hits at melee reach; skeleton types stop at range and
   shoot (a hit through the damage ledger, no arrow entity). A miniboss walks its loop until it sees you. The Shadow Assassin is invisible
   until it notices you, then teleports behind you about every 5 s. Setting "Mobs Attack" off keeps this without damage.
6. Walk 100+ blocks away and back: far mobs vanish and return at their last position with their current HP; no duplicates, no orphan tags.
7. No `mobs.json.gz`: one chat line naming the file and folder; no mobs; no crash.

### Gate G: damage (stage 5b)

1. `/ads dev ledger`: everyone at 100.0 percent, deaths 0, "No deaths off", 20 hearts.
2. `/ads dev hurt 15`: "took 15.0", health 85.0 percent, the bar at 34 of 40, a hurt sound. Again within half a second: "ignored=true". After
   more than half a second it takes another 15. Health comes back about 1 percent a second (setting "Clear Health Regen").
3. `/ads dev hurt 100` with a Spirit, Bonzo or Phoenix ready: the mask's line and sound, health back to full, no death, no hits for about 3 s.
   With every mask on cooldown: "You died and became a ghost." (the Revive Stone line in about 71 percent), the ghost kit with Haunt in slot
   1, Team Deaths 1, "was revived by" about 6 s later (120 ticks) with your gear back and health 100.
4. "No deaths" on (start option `nodeaths`): `/ads dev hurt 100` several times 10 ticks apart: health sits at 2.5 percent, no ghost.
5. Near mobs: `/ads dev cast`: "Implosion hit N mobs ... N died" on F7 with the nametags gone and the room's required count dropping. A real
   Hyperion right click: the particles and Wither Shield as before, then the ding and "Your Implosion hit N enemies for X damage." with X
   about 22.9M to 29.2M each on F7. A sleeping Fels does not die to an Implosion; walk within 5 blocks to wake it, then cast.
6. Terminator arrows take 1.2M each (`/ads dev mobhit 1000000` shows the drop on the nearest mob) and count Hydra stacks. A Mage's Hyperion
   beam takes 1.0M and the sword does not also hit. A Guided Sheep: "Your Guided Sheep hit N enemies for X damage." 3M each. A plain sword left
   click: 500k a hit, no vanilla damage or knockback. A secret bat dies to an Implosion or a beam.
7. Spirit Leap in the clear lists the party members the bots module gives (in your leap order), leaping takes you to their position, 2 s
   cooldown. As a ghost, Haunt lists the same, no cooldown.
8. In a trap room right click an AOTV, Hyperion or Bonzo staff: the red "A mystical force in this room..." line and nothing else.
9. Revive Stone (from a chest): a right click with a dead teammate revives them and uses the stone; with nobody dead "There is nobody to revive."
10. Dungeonbreaker: a block the secrets module refuses shows its line and stays; an allowed block breaks through and returns after the usual
    regen; each secret you find gives it 5 charges. Superboom on a crypt wall blasts it; each use takes one off the stack.

### Gate H: doors, keys, rooms (stage 4c)

1. Kill the last required mob of a key room: a "Wither Key" stand and a head stand appear at about y 68.53 over the floor. Within about 2.5
   blocks (or if you made the kill) you get "<you> has obtained Wither Key!", the RIGHT CLICK line, an orb sound, and `/ads dev doors`
   shows 1 key held. Right click the next wither door: "<you> opened a WITHER door!" with a high pling, barrier then air 14 ticks later,
   held drops to 0.
2. Right click a closed wither door with no key: "You do not have the key for this door!" and the enderman sound, door unchanged. A LEFT click
   (swing) at it does the same.
3. The blood key (last key room) and the blood door: "The BLOOD DOOR has been opened!" and "A shiver runs down your spine..." in the same
   tick, a ghast hurt sound; `BloodDoorOpened` in `/ads dev events`. The blood room turns RED on the BLOOD stage and GREEN on the Watcher's end.
4. Walk into rooms: `/ads dev roomstate` shows you inside. A room with no required mob turns WHITE or GREEN about 50 ticks after discovery
   (setting "Doors Synthesise Clears"). `/ads dev clearroom <n>`, `opendoor <n>` and `givekey` are shortcuts.
5. A key room that rolled no required mob still pays its key (5 ticks after the room's objective is done, at the room centre).

### Gate I: secrets, crypts, score (stage 6)

1. `/ads dev secrets` in the first room with secrets: each secret's kind, x y z, found/to find/locked; the count matches the action bar
   `N/M Secrets`, and M equals the room's secretsMax.
2. Chest: `/ads dev secretgo <n>`, step off, right click. No window, a loot line (or nothing for an empty chest), the bar +1, `secrets` shows it
   found. A second click: "This chest has already been searched!". Chests appear within about 10 blocks (a 20-tick pass).
3. Locked chest: "That chest is locked!" until its lever is pulled; the lever stays on, its wall (if any) turns barrier then air after about
   25 ticks; pulling it again says "This lever has already been used.".
4. Wither essence skull: there as soon as you are in the room. Right click: it vanishes, "<you> found a Wither Essence! Everyone gains an extra
   essence!", a `+1 Wither Essence` action-bar segment, the bar +1. **Check Odin's own secret detection reacts to the skull** (a wrong head
   profile makes Odin ignore it).
5. Item secret: walk onto the floor item (taken within about 2.5 blocks), the bar +1, Odin's item-secret event fires. Bat: one hit, the bar +1,
   Odin's bat event (sound at volume 0.1).
6. Room green: `/ads dev secretgive 99` after clearing the mobs: M/M and the room turns green on the map. In a room the data cannot fully
   place (Redstone Key), finding its one placeable secret jumps the count to M/M.
7. Crypts: `/ads dev secretcrypts` lists tombs and walls; look at one and `/ads dev secretboom`, or use a real Superboom: the whole tomb
   disappears and the Crypts count goes up by 1; a wall just opens. The same group cannot be blown twice.
8. Dungeonbreaker rules: the room's outer wall, a door frame, a standing tomb, a chest, an uncollected skull each give "A mystical force
   prevents you from digging that block!"; an inner block breaks.
9. Score: `/ads dev scorenow`; at run tick 220 the sidebar `Cleared: P% (S)` changes from (0) and steps every 10 s.
   `/ads dev scorecalc 36 44 53 6 0 0 1 0 1` reads S+ (300 or more); `scorecalc 36 20 53 0 0 0` about 275 (S). `/ads dev scoreend` prints the
   end screen (Team Score with its letter, the Defeated line, EXTRA STATS, Enemies Killed, Deaths, Secrets Found). `/ads dev secretbonus prince`
   prints "A Prince falls. +1 Bonus Score" and the next step is 1 higher.
10. A puzzle room counts its reward chest as its secret: solving the puzzle also credits the room's one secret, so the room can go green.

### Gate J: bots (stage 7)

1. At the start four teammate Mannequins stand in the entrance room; the tab and the Spirit Leap window list the same four, with the boss sim's
   names and heads (Mage, Berserk, Archer, Tank for a Healer player).
2. `/ads dev botplan`: after "Starting in" the entrance door falls at about T0+2. At the first door's planned tick one bot is at the first
   key room; a wither key stand appears when its mobs die, the bot steps onto it, walks to the door and the door falls at the "opens t=" tick.
   Wither doors fall at medians of about 60, 140 and 210 ticks after T0; the blood door at about 310 (median 336).
3. Rooms turn white then green on the map without you doing anything (the bots kill each room's required mobs through the damage service, find
   its secrets, solve its puzzle; where nothing could produce a mark the doors module is told with a `clear.*` notice).
4. `/ads dev bots` lists each bot's class, room, position, whether it is drawn and its next action; bots farther than "Bots Show Distance" are
   "not drawn" but still have a position. Leaping to a far bot puts you on the floor beside it. The bots' map markers face the way they walk.
5. Walk into a room a bot was heading for: within about half a second `/ads dev bots` shows "Last re-plans: ... the player took room N" and that
   bot's next action is a different room. Open a wither door yourself: the plan is redrawn. Stand still: after "Bots Cover After" ticks the
   bots clear everything. `/ads dev botreplan` then `botplan` shows a new plan.
6. At BOSS_ENTRY the clear bodies disappear and the boss sim's own bots appear with the same names and heads; the tab still lists five.
7. `/ads dev botsim 500` (needs the real packs): five rows all marked OK and "All within tolerance.".

### Gate K: puzzles and traps (stage 8)

(`/ads dev puzzles` lists every puzzle room: number, tab name, kind, status, room, centre x z, built or not. `/ads dev puzzlesolve <n>`
solves any of them, `puzzlereset <n>` resets, `/ads dev draft` gives the Architect's First Draft.)

1. Walk into a puzzle room: its tab row loses the `???`. A puzzle builds itself when a player is within 48 blocks.
2. **Three Weirdos**: three NPC stands beside three chests, speaking in order within about 7 blocks. Odin's Weirdos solver highlights a chest; opening
   it: "PUZZLE SOLVED! <you> wasn't fooled by <NPC>! Good job!", the room green, the tab row Completed about 10 ticks later. A wrong chest: "PUZZLE
   FAIL! ... was fooled by ... Yikes!", the cross in the tab, you get the Architect's First Draft; using it resets and the NPCs say new lines.
3. **Quiz**: the intro line, Question #1 with three lettered options; Odin highlights the right pillar. A stone button beside it: "<you> answered
   Question #1 correctly!" ... "answered the final question correctly!", then Completed. A wrong button: "chose the wrong answer!" (no PUZZLE FAIL
   line), the cross, a restart with new questions about 3 s later.
4. **Creeper Beams**: Odin draws lantern pairs. Right click (or shoot) a lantern: it turns to prismarine; its partner within 5 s keeps both; a
   lone hit reverts after 5 s. All pairs done: a chest appears and the room solves. **Arrows count (the hit is reported to the puzzle).**
5. **Boulder**: Odin shows the click sequence; right click the highlighted sign or button for each click; after the last the chest is signalled
   open. A log warning says if the room's boulder pattern is not in Odin's table (known: Boulder v1 and two other variants).
6. **Ice Fill**: Odin draws three paths; walking turns ice to packed ice; after the third floor the gate opens and two chests appear.
7. **Tic Tac Toe**: press a wall button; your cell turns red wool, the room answers blue; a tie solves ("tied Tic Tac Toe! Good job!"), a loss
   fails and gives the draft.
8. **Higher Or Lower**: ten blazes `[Lv0]  Blaze N/N`; Odin's solver draws the order (lowest first in the Higher room, highest first in the Lower
   room). Kill in order: the tab says Completed at once, about 6 s later the room turns green and the chest appears. Out of order: "PUZZLE FAIL! ...
   killed a Blaze in the wrong order!".
9. **Water Board**: `/ads dev hard tp water` puts you at the levers. Three of five wool pits closed; Odin shows its lever times. Pull the water
   lever (15,60,5 in the room's frame): a source at the plug within a tick, then the water spreads one block per 5 ticks; pull the gate levers at
   Odin's times; pistons move 2 ticks after each pull; a closed pit opens when water reaches its drain; all three open: PUZZLE SOLVED.
   `/ads dev hard hint water` prints closed and opened pits. A second pull of the water lever does nothing.
10. **Teleport Maze**: `/ads dev hard tp maze`, `hint maze`. Stand on the start pad (canonical 15,12): you land on a slab beside another pad
    (y 69.5) looking at that group's "way on" pad; Odin's TP Maze solver draws the same pad. Follow the pads in order to pad 28's patch: solved.
    Wrong pads send you elsewhere; never teleported twice within 1 s.
11. **Ice Path**: `/ads dev hard tp ice`, `hint ice`. Left click the silverfish from the west: it slides east at 0.4 blocks per tick and stops
    mid-cell. Click while it moves: "You cannot hit the silverfish while it's moving!"; toward a wall: "You cannot move the silverfish in that
    direction!". Follow the hint: it disappears in the exit corridor and the puzzle solves. Into a trap cell: the fail line and a fresh fish 2 s later.
12. **Trap rooms**: `/ads dev traps tp`, `traps status`. Stand in the path of a crusher: you lose 15 percent (6.0 of the 40 vanilla points) about
    every 10 ticks while it overlaps you. Cross a tripwire string: about 5 ticks later, if you are still in its corridor, 15 percent and "The
    Tripwire Trap hit you for N damage!"; the same string does not fire again for 30 ticks; jumping over it does not trigger it. Abilities are off
    in the trap rooms (setting "Abilities Off In Trap Rooms"). Hard puzzle rooms do not place a reward chest (known gap: not needed by Odin).
13. A savepoint taken after pulling the Water Board's levers, then `restart room`: levers, gates, pits and water come back as saved.

### Gate L: the blood room and the boss (stage 9)

1. `/ads dev blood status`: stage, blood phase CLOSED, camp "none yet". In the CLEAR stage `/ads dev blood open` (or open the real door): stage
   BLOOD, the blood room's map mark RED, a zombie named The Watcher in the middle, 30 heads on the walls in three rows (textured when the
   rooms pack has the wall heads, plain otherwise), and `/ads dev blood status` says "the Watcher's camp". 3 ticks after the door he
   says his opening line.
2. Watch the camp play out (details in `docs/ads/blood.md`): from 4.5 s he flies to a wall head and throws it, four times (a giant, a
   revived boss and two standard mobs, one hop every 1.5 s or so), then flies home; each thrown head spins across the room for 4 s and its mob appears at
   its end, high up, and falls to the floor. The giant is the first. Later he leaves from 21 s on, a head at a time, and each later mob appears 2 s after its
   throw. Mobs have a name tag with a modifier word and their HP. Each kill steps his boss bar down by a fifteenth (counted on the next half-second
   beat); at the fifteenth, "[BOSS] The Watcher: You have proven yourself. You may pass." prints within 1 s, the room turns GREEN, `WatcherDone` is in
   `/ads dev events`. The mobs look like plain stand-in mobs until the mob module reads the camp's extras (known gap, see 4).
2b. A blood room without the pack's wall heads still plays; the heads are plain.
2c. A room with too few wall heads (Blood Wall Heads setting does not affect this): a chat note says a plain stand-in runs, and a mob every 2 s (6 by default).
3. Stand in the blood room. About 92 ticks after the pass line, in ONE tick: lightning at your feet, thunder and an explosion, the Maxor bar, a
   lever click, you on the P1 spot (73.5, 221.5, 14.5), hotbar unchanged. Odin's bridge flips to boss. `/ads dev events`: BossEntry, StageChanged to
   BOSS_ENTRY, then BOSS two ticks later with Maxor's first line. Walk out before the delay ends: the jump waits until you are back (at most 600 ticks).
4. `/ads dev blood pass` ends the camp at once; `/ads dev blood enter` ends it and jumps with no wait; `/ads dev blood heads` lists the slots (each
   on a wall, facing into the room).
5. P1 to P4 play as in the practice with your clear gear. Kill Necron: about 27 ticks after he is removed the end screen prints with the REAL
   score, letter, time, secrets and deaths from the run (compare with the sidebar), then stage END.
6. Savepoint during the Watcher stage, kill some mobs, restore: the Watcher and heads are back, the camp carries on from where it was (no mob is made twice, the next throw comes on the tick it would have). Not played in the game yet.

### Gate M: savepoints and the run UI (stage 9b)

1. `/ads run` lists the run commands. `/ads run start` opens the start screen: Floor (F7), Layout (Random / Seed with a New button / Recorded with
   the browser), Party (1 to 5 plus Bots, and a Strategy button that shows only when a plug-in offers a preset; the default is Auto), Lag (Off, Good, Typical, Bad, High slot), Rules (No deaths, Mob HP per run), Start run, Close. Nothing
   overlaps at GUI scale 2 and 3; tooltips on hover.
2. The recorded layout browser: about 400 layouts with the complete-only switch on (567 off); search, sort (Newest, Fastest, Puzzles, Secrets);
   clicking one returns to the start screen with its date in green.
3. The choices are the Odin settings "Run Lag", "Run Party Size", "Run Bots", "Run No Deaths", "Run Mob HP" and survive a game restart. Four keybinds:
   Run Start Screen, Run Restart Room, Run Restart, Run Savepoint (check they appear and fire).
4. Typing mistakes (`seed`, `seed abc`, `random party 9`, `random lag awful`, `layout zzz`) give a one-line correction; `layout 2026-09` that matches
   several says so.
5. `/ads run random` prints "Starting a F7 run on a random layout (seed N), 5 players with bots, lag Off, base mob HP." and rebuilds; `seed N` twice gives
   the same layout. `/ads run layout <start of an id>` starts it (a layout that is not complete warns first).
6. In a run: walk into two rooms; `/ads run savepoints` lists Start and the rooms numbered with the run clock. `restart room` puts you at the entry of
   the room you stand in (in a doorway it says it used the latest room savepoint). `restart` goes to the Start; `restore 2` to the second. The menu has
   **Restart Room** and **Restart Run** while a run is on. With no run: "No full run is on. Start one with /ads run start."
7. After a restore the doors, keys, mobs, secrets, crypts, walls and puzzles are as saved, the clock continues from the saved value, bots re-plan.

### Gate N: the whole run, once, in one go

Start `/ads run random lag typical`. Play countdown, clear (open doors, kill, find some secrets, blow a crypt, solve whatever puzzles come), blood
room, Maxor to Necron. Things to watch for the whole time: the sidebar `Cleared` and score climbing, the tab list, Odin's map matching the real rooms,
nothing duplicated in chat, no `[ads]` error in `latest.log`, the lag layer's stutter without a crash, and the mod's CPU and memory over twenty
minutes. At the end note: total time, final score and letter, deaths, and anything that stalled.

### Round 2 checks (after the first sim sessions)

The fixes made after the first sim sessions each came with in-game steps in their module note. Work through them in this order, one note at a time:
`engine.md` ("In-game checks (round 2)"), `layout.md`, `doors.md`, `hud.md`, `bots.md` ("In-game checks"), `damage.md` ("Testing in game"),
`secrets.md` ("In-game checks"), `puzzles.md` ("In-game test steps"), `traps.md`, `blood.md`, `ui.md`. Two things first: the packs are bundled, so a jar built after
the pack rebuild already has the new `runstate.json.gz` and `rooms.index.json.gz` (check with `/ads dev packs`; an old override copy in
`<gameDir>/config/engineerclient/ads/packs/` would hide them: delete it), and the four new mixins are listed in `engineerclient.mixins.json`, so a mixin that misses
its target on 26.1.2 stops the game at start: read `latest.log` first.

## 3. The plug-in seam

A separate plug-in mod can supply the blood room's camp and party strategies (see ARCHITECTURE section 13). What a plug-in does is documented with the plug-in, not here.

1. With no plug-in installed, everything above works with the Watcher's own camp and the neutral party strategy, and nothing crashes.
2. With a plug-in installed, `latest.log` shows its load line from `[ads]`. Remove the plug-in again and start a run: the load line is gone and the Watcher's own camp runs.
3. A plug-in that throws, or whose API version is newer than the game's, is logged and ignored; the run carries on with the neutral defaults.

## 4. Numbers nobody has measured (record them when you see them)

Time to playable and MSPT with 169 roofed chunks, 300 and 750 mobs; FPS with 430 entities; `Arena.resync` time (`/ads dev cost`). The hitch when a far chunk is
written at the start (the Fairy door, hard puzzle pits, trap rooms). The lag layer's burst rate (setting "Lag Burst Rate", default 2) and whether the reflective
tick clock write works in the live game. How the 26.x client renders the fake tab rows, the unlisted real players, the map id 1024 and the disguised mobs'
unsigned skins. Whether real Hypixel skulls carry the profile id Odin expects for essence and key heads (check Gate I, 4). The arrow, beam, sword and blast damage
numbers (settings "Clear Arrow/Beam/Sword/Blast Damage"). Mob aggro and speed settings. Everything marked "unmeasured" in the module notes in this folder is a named
constant or a setting; if one feels wrong in play, change the setting first and tell me the value that felt right.

## 5. Known gaps (not bugs to report, unless they get in your way)

- Mimic and Prince are never spawned in version 1: each run rolls from the pack's `bonus` odds whether it scores them and at which tick, and awards the point without a mob (`/ads dev secretbonus` still scores one by hand); King Midas is not spawned by any room.
- The blessings give no stat changes; the tab's `Dungeon Buffs` footer is the only trace. The loot items other than the Revive Stone, Superboom and Spirit Leap
  heads are plain items.
- Hard puzzle rooms (Water Board, Teleport Maze) have no reward chest. Only one of the four Water Board layouts exists in the data.
- The camp mobs are stand-in bodies: the mob module does not yet read the camp's extras (the Giant body, the fake player named Undead with its stone sword and chainmail, the tag with icons, modifier word and HP, the walking speed, the 21-tick death), so they look like the pack's plain mob until it does. Mana is not modelled (the bar always shows full mana). Team Healing Done stays 0.
- Bots wander over each room's real floor and route round its walls, but never fight: a room's mobs die when the plan says so. The party never has two bots of one class (the boss sim needs one per class).
- M7 is not built. Early-run score reads about 14 points low at 2 to 5 completed tiles (the pack's own weak spot).
