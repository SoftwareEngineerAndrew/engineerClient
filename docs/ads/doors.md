# Doors, keys and room states (the `doors` module)

Code: `src/p3sim/clear/state/`. Tests: `test/p3sim/state/`. The module object is `DoorsModule`; the logic of one run is `StateController`
(headless: it talks to the world only through `StateEffects`, which `McStateEffects` implements and the tests record).

## What it does

- **Countdown (run clock `t`, T0 = Mort's first line).** `start()` sets T0 = run start + `Doors Countdown Delay` (setting, default 40) + 60 + 20.
  "Starting in 4/3/2/1" at `-80/-60/-40/-20` (measured: 20 ticks apart, 20 ticks before Mort), the ready lines just before the 4, Mort at
  `0/+40/+70`, the CLEAR stage at `0`. The lever-click and villager sounds are the recorded ones.
- **Doors.** `entrance` falls 22 ticks after the "1 second" line (T0+2), the free Fairy door one tick earlier, no chat, no key. A wither
  or blood door opens on a right click when the party has the key. Barrier for 14 ticks (15 for the entrance) in one tick, then air.
  The room on each side of a door that falls is discovered. Without a key: the no-key line and sound.
- **Keys.** `KeyPlans.of(plan)`: rooms 1..n-2 of the entrance -> blood path, minus the Fairy room, drop a wither key; the room right before
  the blood room drops the blood key. A stand appears where the mobs module says (`dropKey`), the killer takes it at once on the measured
  share, otherwise the first party member within `Doors Key Pickup Radius`. Count is party-shared; obtained = used at the end.
- **Room marks.** The only writer of `RoomRecord.check`. Discovered by a fallen door beside it or a party member in it (position). WHITE when
  the required mobs are dead (event or poll), a puzzle is solved, or - for a room with no required mob - on the measured NONE to WHITE time
  (`Doors Synthesise Clears`). GREEN at WHITE when no secret is left, else when `found == max`. RED on a failed puzzle and during the
  Watcher stage. Counters in tile units, entrance excluded.
- **Trap rooms.** No mob has to die in a trap room: it turns WHITE 0 to 5 ticks (median 2) after the party opens its end chest, so the time from
  NONE to WHITE is the walk to that chest. The run-state pack's `chestClears` section names the chest per room (secret id and canonical cell);
  `StateController` turns the cell with the room's placement and watches `SecretFound` for it. The room's other chests change nothing, standing
  still finishes nothing, and the "Synthesise Clears" setting does not apply. A trap room the pack has no rule for falls back to the pooled timer.
- **The blood room's check.** The room is RED from the blood door to the portal. `WatcherDone` (the pass line) does not turn it green: the green
  check comes 90 ticks after the pass line (the median gap from the Watcher's last kill; measured 80 to 94), or one tick after the blood module posts
  `ModuleEvent(t, "blood", "portal")` when its portal blocks appear (measured: the check came -1 to 4 ticks around the portal), whichever is first. The
  boss entry turns it green at once if it is still waiting. Completed Rooms and Cleared % follow the mark.

## For other modules

- Blood module: post `ModuleEvent(run.t, "blood", "portal")` in the tick the portal blocks are written. It is optional: without it the check
  follows the pass line by 90 ticks.

- `DoorsModule.completeRoom(run, room)`: finish a room's objective now (the bots' measured schedule).
- `DoorsModule.touchBlock(run, x, y, z, by)`: a swing at a door block (no-key line); needs the client hit message (integration request).
- `/ads dev doors | rooms | givekey | opendoor | clearroom` for probing.
