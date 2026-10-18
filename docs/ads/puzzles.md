# Puzzles (easy and medium): design notes

Owner: the `puzzles` work item (`src/p3sim/clear/puzzles/` except `hard/`). Everything here comes from recordings of the real rooms; numbers that
are only a guess are listed at the end as UNMEASURED.

## The frame (`PuzzlesModule`, `WorldPuzzle`)

- **Two steps.** A room is read (`activate`: its blocks, its plan) when a player comes within 48 blocks, because only the chunks near a player
  are loaded. What the room shows (`wake`: entities, chests, the room's first move) is made `wakeDelayTicks` after the first human steps into
  the room (measured from the room-enter event: Ice Fill 0, Higher Or Lower 7, Tic Tac Toe 24). A room that is already solved when it is built
  never wakes; its end state is applied instead.
- **Timed steps.** `WorldPuzzle.after(ticks, tag) { ... }` counts server ticks and is pumped by the module every tick for every built puzzle,
  whether or not a player is inside. `cancelTimers(tag)` drops a group of steps. They are forgotten when the puzzle is deactivated (run end,
  savepoint restore).
- **Solve timetable.** A puzzle calls `solve(by)`; the module then does three things after delays the puzzle can choose (`SolveTiming`): the
  `PUZZLE SOLVED!` line, the room's check mark (`PuzzleSolved`, which the state module turns GREEN, or WHITE while a secret is unfound), and
  Completed (the tab row shows it at its next refresh). The default derives them from `greenLeadTicks` as before. A puzzle can also say
  it sends the reward chest's cherry-log signal itself (`rewardSignalAtGreen = false`) and that it leaves the room's secret to the player
  (`countsRewardAsSecret = false`).
- **The draft.** The Architect's First Draft is a dark purple, non-italic paper with Hypixel's lore. After a fail the failing player gets the
  sack line and the item together, one tick later (recorded: the chat line and the inventory slot update arrive in the same tick). The line printed on use colours the item and puzzle names.

## Higher Or Lower

- Ten blazes in a spiral round the centre pillar (canonical column 15, 15): blaze `k` at y = 27 + 4k (Lower) or 77 + 4k (Higher), a quarter turn
  per blaze, 2 blocks out, on the block corner; the name stand is 2 blocks above. The names carry the blaze icon glyph (U+E07C), which Odin's
  blaze solver needs. Health: lowest 1000 to 2000, then steps of 1 to 450.
- The rail chest: from the first correct kill it moves one block every 5 ticks, 49 moves, the first on the kill tick. Lower goes 69 to 20 and
  leaves a bar in each cell; Higher goes 70 to 119 and eats the column of bars above it, leaving air. The chest faces south in the world
  whichever way the room is turned. The room turns green 244 ticks after the first kill (4 after the last move), at once if the last kill
  comes later; the puzzle counts as Completed at the last kill.
- A wrong kill: the chest is taken off the rail, the rest of the blazes vanish 2 ticks later. The draft brings back the same ten, with their
  health, one tick after it is used, and puts the rail back.
- The room's reward chest is the pack's one secret; the secrets module owns it. When a player opens it (after the last kill) the puzzle sends
  Odin's chest-open signal and the five harp notes.

## Ice Fill

- Rules (`IceFillLogic`): the first step on a floor may be any cell; every later one must be next to the previous (else "Don't move
  diagonally! Bad!") and on an uncovered cell (else "Oops! You stepped on the wrong block!"). Either collapses the floor being filled for
  60 ticks (every ice block of the floor and the obstacles on it become air in one go, with a glass-break puff per ice cell and a falling
  block per obstacle); only the player who did it is told. A step is counted when the player moves onto a different column at floor level.
- Each step puffs wool-break particles; each finished floor plays three rising harp notes at the cell.
- The chests are in place from the wake. The last step: gate bars (y 74 to 79) to barrier, to air 26 ticks later; a pressure-plate click every
  5 ticks; the east chest signals and opens at +9, the other at +12; the room turns green at +15.

## Tic Tac Toe

- The room plays X and moves first (24 ticks after the wake clock starts); the party plays O. The room answers 60 ticks after the party's press with
  a best move chosen at random among equals; presses meanwhile only click the button. All nine buttons are put in at the wake (captured rooms
  can come with the first mark's button already gone).
- Marks are item frames facing +x in the button cell, holding map 30876 (X) or 30877 (O). The maps are registered on the server level and sent
  to the players; their pictures are drawn from a few numbers (`TttArt`), not shipped.
- Tie: `PUZZLE SOLVED!` on the tick of the ninth mark, the chest at canonical (2, 70, 16) facing east, green at +2, Completed at +13, marks gone
  at +29, wall to barrier at +30 (a click every 5 ticks) and to air at +56, the chest opens at +62. Loss: `PUZZLE FAIL!` at once, the marks stay
  until the draft is used; they go one tick after, and the room opens again.

## In-game test steps (26.1.2 build)

Start a run, then `/ads dev puzzles` lists the rooms. `/ads dev puzzlewake <n>` builds and wakes one without walking there (stand in or near it).

1. Higher Or Lower: wake it; ten blazes in a spiral, names readable by Odin's blaze solver (it should draw the kill order). Kill the right one:
   the chest slides down (Lower) or up (Higher); the kill order is highest-first for Lower, lowest-first for Higher. Kill a wrong one: the other
   blazes vanish two ticks later and the chest disappears. `/ads dev draft` and use it in the room: the blazes and the chest come back.
   Finish the room: the tab says Completed at the last kill, the room turns green about 12 seconds after the first kill, or white until you
   open the chest in the wall (then green and the harp jingle).
2. Ice Fill: wake it (the two chests are behind the gate). Step on a cell, then step diagonally: the red line and the floor falls for three
   seconds and comes back. Fill all three floors in a single walk: the gate goes dark and then open, the chests open, the room turns green.
3. Tic Tac Toe: wake it; an X frame (a map) appears. Press a button: it pings, an O appears, and three seconds later the room answers. Press
   again during the wait: the button just clicks. Tie it: the chest appears, the marks go, the wall dissolves, the chest opens. Lose it
   (press badly): PUZZLE FAIL, marks stay, `/ads dev draft` and use it to start over.

## UNMEASURED

- Which Ice Fill chest opens first is taken from one recorded room; whether stepping the exit cell early completes a floor.
- How the real Tic Tac Toe room chooses among equally good cells after its first mark, and whether it takes a win at once (the sim does).
- Whether the Higher Or Lower chest can be opened before the last kill; the blazes' client-side health (Hypixel shows 20, the sim keeps 1).
- The Higher Or Lower ride runs on server ticks even with nobody inside; recorded rides that last longer than 244 ticks were probably frozen
  while the room was empty.
- The shape of the two Tic Tac Toe maps (drawn from a few numbers, within about 6 per cent of the recorded pixels).
