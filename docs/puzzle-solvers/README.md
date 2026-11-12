# Dungeon puzzle solvers: what the server really does, and the best solver for each

Research from 89 Dungeon Recorder recordings (Hypixel, 2026-10-12..16): each puzzle's mechanics read from the packets,
what is fixed vs random, what the server times, how players actually solved it, what Odin 0.4.0 gets wrong, and the
recommended design. Detailed notes per puzzle sit next to this file. **Data is thin for most puzzles**: most rooms were
solved by teammates, often out of the recorder's range. Each note ends with the data still needed.

| Puzzle | Rooms seen / solved in view | Status |
|---|---|---|
| Water Board | 26 / 6 boards with lever activity | **built**: `src/waterboard/` (plan table + live recovery) |
| Tic Tac Toe | 25 / 4 games | paused, needs more games: `tools/tictactoe/HANDOFF.md` |
| Higher / Lower Blaze | 51 / 7 kill sequences | design: [blaze.md](blaze.md) |
| Creeper Beams | 22 / 11 | design: [beams-boulder.md](beams-boulder.md) |
| Boulder | 24 / 10 | design: [beams-boulder.md](beams-boulder.md) |
| Ice Fill | 18 / 6 full solves | design: [ice-fill-ice-path.md](ice-fill-ice-path.md) |
| Ice Path (silverfish) | 21 / 2 with slide data | design: [ice-fill-ice-path.md](ice-fill-ice-path.md) |
| Three Weirdos | 23 / 6 (statements never in range) | design: [weirdos-quiz.md](weirdos-quiz.md) |
| Quiz | 19 / 11 quiz runs, 2 own | design: [weirdos-quiz.md](weirdos-quiz.md) |
| Teleport Maze | 22 / 2 in range | design: [teleport-maze.md](teleport-maze.md) |

## The main finding per puzzle

- **Water Board.** Vanilla 1.8 water and pistons, matched packet for packet. Water gets random ticks, so it can only
  ever run early. A fixed set of 4 layouts allows a precomputed robust plan per board, rotation and gate set, with a
  live planner taking over when anything goes off-plan.
- **Tic Tac Toe.** The AI replies after a fixed ~3 s. Clicks before its move are ignored, so spam-clicking is safe.
  It plays perfectly but picks randomly among equal moves, so the next move can be pre-fired only 51-87% of the time.
  This needs more games to settle.
- **Blaze.** Lower = highest HP first; Higher = lowest first. All 10 healths are known 1 tick after the room activates.
  Completion is set by a chest that descends 1 block every 5 ticks after the first kill (about 12 s), not by kill speed.
  Odin trusts nametags, which often don't arrive.
  Best: read HP from the blaze's own data, show "?" for any blaze not seen, a wrong-target guard, a chest timer.
- **Creeper Beams.** 4 player links complete it. The pairs come from a fixed server table, not from beam geometry.
  Links are visible from anywhere (guardian spawns).
  Best: plan 4 pairs (the centre pair first), track the selected lantern from its sound, a fuse countdown.
- **Boulder.** Exactly 8 layouts, all of them Odin's. A push is a single 3-block move. A button means the cell behind is
  empty; a sign means a box or the edge. Pushing into a box destroys it. Odin ignores teammates' pushes and isn't
  optimal on 2 layouts.
  Best: a live grid from block updates, with a precomputed time-optimal plan per layout.
- **Ice Fill.** Three floors, layouts drawn independently. A mistake drops the floor for exactly 3.0 s. The only
  confirmed fail cause is a diagonal move packet. Turns cost about 1.8 ticks each.
  Best: a minimum-turn path, live progress, a rebuild timer, replanning after a fall.
- **Ice Path.** One fixed board in all 21 rooms and a single 10-hit route. Hits while the silverfish slides are refused
  with a message. It slides at a constant 0.4 blocks per tick, so the stop tick is predictable.
  Best: the fixed route plus facing and a "now" cue.
- **Three Weirdos.** Nothing exists until someone walks in. The solve line names the right NPC for everyone. Odin
  doesn't eliminate (two lies should make the third right). The statement texts were never recorded.
- **Quiz.** A fixed server schedule: Q1 160 ticks after the intro, then 80 ticks per question. A ~58-tick lockout drops
  early presses with no penalty, so pre-fire works. The minimum is about 25 s. Odin's database covered every
  question seen, but has a substring-match bug.
- **Teleport Maze.** Each landing's yaw points exactly at the final pad's cell, and the pad wiring is random per run.
  The cost is the route there. The repo's team solver is the right design; it needs better origin estimates, routing
  by walking distance, and a stand-still cue.

## Common threads

1. **Read the server's state, not the player's actions.** Odin advances on the local player's clicks in Boulder, Ice
   Path and Blaze, and so goes wrong when a teammate acts or a click doesn't register. Every recommended design tracks
   the puzzle from server packets (blocks, entity data, sounds), which also covers teammates.
2. **Server-timed puzzles:** Quiz lockout, Tic Tac Toe AI, Blaze chest, Beams fuse, Ice Fill rebuild, Ice Path slide.
   Show countdowns in server ticks, and give a "now" cue when spam-clicking is harmless. Where it is refused (Ice
   Path) or risky (Blaze arrows), don't.
3. **Fixed layouts make precomputation worthwhile:** Water Board (4), Boulder (8), Ice Fill (per floor), Ice Path (1).
   Read the layout from the world, match all of it, rescan when chunks arrive, and replan live from any state.
4. **Guards over automation.** A client-side guard that cancels a wrong hit (Blaze) prevents real fails. Nothing here
   automates clicks or aim.
