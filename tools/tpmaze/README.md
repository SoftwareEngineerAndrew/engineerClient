# Teleport Maze: the analysis

How the Teleport Maze is drawn, from the public ADR recordings: every player's position inside the maze, put into the room's
canonical frame (the placement law in `../secret-spawns/common.mjs`), read as teleports. Node 20+, `xz` and `gzip` on the PATH.
Run from an empty data folder outside the repo:

    node <tools>/secret-spawns/list.mjs 2026-10-16    # list-all.json: every public run (older than a week opens 30 a day)
    node <tools>/tpmaze/extract.mjs list-all.json maze.jsonl 4   # one recording per group; runs without the maze stop at the header
    node <tools>/tpmaze/analyze.mjs maze.jsonl mazes.json        # the checks below; mazes.json: every maze's hops, final pad, pairs

Pads are Odin's order (TpMazeModel's): seven cells of four, the end pad 28 and the start pad 29. `analyze.mjs` names them
`1NW`..`7SE` (cell 1-7 around the U from the start's left, corner by the canonical compass: N is the start's side, W is -x).

How a teleport is read: a sample on a landing cell's middle at slab height (y 69.5), coming from elsewhere. Teammates are drawn
eased, a tick or two of samples on the straight line to the landing, so the jump starts at the sample before those; the pad
stood on is the nearest frame of the cell there ('high': on top of it, 'mid': within 1.3 blocks, 'low': a gap in the track,
not used for pairs). A landing whose yaw looks at nothing is Hypixel putting the player back (a setback), not a pad.

## What every maze does (7,694 groups, Oct 17-24 2026: 2,185 Teleport Mazes, 868 seen in use, 4,604 teleports)

- **Every landing looks at the final pad.** The yaw a pad gives you points at the middle of the landing cell beside the one pad
  that leads to the end (95% within 0.04 degrees, all within 0.3): 4,090 of 4,092 landings agree within their maze, and 241 of 241 walks into the
  end left from that pad. One landing names it alone 59% of the time; the first two landings 96%. Not the frame: the landing cell.
- **Pads are two-way pairs.** A drops you beside B, B drops you beside A: 102 of 102 pads seen both ways agree, no contradiction
  in 3,753 pairs. So the pad you land beside takes you back. The final pad is one way: nothing lands you beside it (0 of 3,830
  landings), and the end pad sends you out to the room's entrance (449 of the recorder's own teleports). So the start pad's twin
  and 13 pairs use the other 27 pads.
- **No pad pairs inside its own cell** (0 of 3,753). A cell's pads can lead into the same cell (21% of cells with 2+ known).
- **The final pad's cell is at least two steps from the start's.** Of 584 teleports out of the cell the start pad leads into, none
  landed in the final pad's cell (pairs drawn at random: 12.2%; 41% of random mazes would be 0 or 1 steps). 39 mazes are settled at
  2 steps, 6 at 3. So every maze takes 4 teleports at least: start pad, two pads, final pad.
- **Nothing repeats.** Same final pad in 3.57% of maze pairs (1 in 28); the start pad's destination and the final pad are uniform
  over the 28 pads (chi-square 29.8 and 26.7, 27 dof). Two mazes give a pad they both saw the same partner 4.1% of the time
  (relabelled mazes: 3.9%). Two mazes with one layout would show 4+ identical pairs about a quarter of the time; 3 maze pairs
  do (1.7 by chance), so a fixed list would need over 11,000 layouts to hide. Single pad pairs are a little lumpier than uniform
  (chi-square 398 over 336 pairs, largest z 3.2): nothing a player could use.
- Walks seen from the start pad to the end: 4 to 13 teleports, mean 6.8 (players following a solver).

The model in `src/p3sim/clear/puzzles/hard/TpMazeModel.kt` predates this: its landings look at a way on in their own cell and its
wrong pads send you anywhere, not to their twin.
