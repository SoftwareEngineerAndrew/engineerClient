# Tic Tac Toe solver: handoff (paused 2026-11-12, waiting for more recordings)

Goal: a Tic Tac Toe solver whose next move is known **before the AI's reply arrives**, so the player can spam-click that
square while the AI "thinks" (pre-fire). Paused because there are only 4 recorded games: too few to model the AI.

## What the recordings show (devgineer recordings 2026-10-12..16, 25 rooms seen, 4 games played in view)

**The room**
- The board is a 3x3 of `stone_button[face=wall]` on one wall (e.g. x -89..-91, y 70..72, z -160 for a room facing
  SOUTH). Each mark is an `item_frame` spawned half a block in front of its button, holding a `filled_map`.
- A move: the cell's button goes to air (block update) and a frame with the mover's map is added.
- The AI always uses map id **30876**; the player's marks are 30877. Same ids in every room seen.
- The AI moves **first**. Its opening is already on the board when the room is first loaded.
- Game over (a draw): every frame removed, then the iron wall behind the board (z -161, y 69..72) turns to `barrier`
  and then to `air`. That's the solve.
- Frames are re-sent whenever the client re-enters tracking range, so frame spawn times are useless. The button
  disappearing gives the move's true time. Entity ids are allocated in creation order.

**Timing**
- The AI replies a fixed **~2.95 s** (2.92-3.05, so a 60-tick timer) after the player's move, every time.
- Players reply anywhere from 0.05 to 1.65 s. Several replies came 1-3 ticks after the AI's mark, i.e. pre-fired.

**Pre-fire (from the recorder's own play, room cce77b, 45 clicks)**
- Holding right-click on a button sends a `use_item_on` every 200 ms (4 ticks).
- Clicks the server gets **before** the AI has moved are ignored: the button stays. The first click it gets **after**
  the AI's move is accepted. A click can even leave before the AI's mark arrives at the client and still be accepted,
  as long as it reaches the server after the AI moved.
- A click on a square the AI just took does nothing. So a wrong pre-fire on the AI's square is harmless. A wrong
  pre-fire on a still-free square that no longer blocks a threat is **accepted**, and can lose the game.

**The AI**
- All 13 AI decisions after its opening were minimax-optimal: no missed win, no missed block.
- Not deterministic. The same board (AI corner 22, player centre 11) got reply 20 in one game and 01 in another.
  Openings seen: 22, 00, 11.
- Best current model: perfect play, picking at random among the equally good moves. But in every position where it had
  a free choice, *all* moves drew, so the data can't tell this apart from "win, else block, else random". A random bot
  could be beaten with forks.

## What the next session needs to decide from more data

1. **Is the AI perfect, or win/block/random?** Look for positions where some free choice loses (e.g. the player makes a
   fork threat). If it ever takes a losing move, it's not minimax.
2. **How does it choose among equal moves?** Uniform? A preference order (centre, corners, edges)? A fixed order by
   cell? Count the choices per position across many games. If there is a rule, the AI becomes predictable and
   pre-fire becomes guaranteed.
3. **Is the opening random or tied to something** (room rotation, run, party)?
4. **The exact reply delay in ticks**, from the server-tick clock: possibly a fixed 60 ticks after the player's
   button press.

## Pre-fire results so far (prefire2.py; AI model = perfect, uniform among optimal)

A **safe pre-fire** is a next square that keeps the draw for every AI reply, except the AI taking that square (then
the click is just ignored). Playing to maximise how often one exists:

| AI opens | best first move | later moves safely pre-fireable |
|---|---|---|
| corner | centre | 67% |
| edge | centre | 87% |
| centre | a corner | 51% |

A guarantee on **every** move is impossible under this model: the AI's second mark can't be forced. If the AI turns
out to follow a rule (point 2 above), redo this with that rule: it may reach 100%.

Proposed solver (not started): track the board from button and frame packets. After each player move, show the safe
pre-fire square when one exists (marked as safe to spam). Otherwise show the move the moment the AI's mark lands.
Choose moves that maximise safe pre-fires without risking the draw. Show a countdown to the AI's reply (fixed delay).

## Tools here (Python 3, run with -I on recording data)

- `games.py REC` -> one JSON per recording: grid, frames (entity id, cell, map id), events (`button_gone`, `click`
  (own `use_item_on`), `removed`, `solved`), cells as the player sees them (row 0 top, col 0 left).
  Needs the recording's `part*.jsonl.gz`; raw frames are not needed. Run it over every recording into `games/`:
  `for d in REC_ROOT/*/; do python3 -I games.py $d > games/$(basename $d).json; done`
- `analyse.py`: rebuilds each game from `games/*.json` (move order from the button times, AI = map 30876).
- `ai.py`: checks each AI move against minimax (prints the optimal set; flags `!SUBOPT`).
- `prefire.py` / `prefire2.py`: pre-fire analysis (strict, and with ignored collisions).
- `timeline.py REC TILE_X TILE_Z`: raw room timeline (frames, maps, buttons, own clicks) for eyeballing.

Known rough edges: `games.py`'s X/O picture classifier doesn't work (use the map ids). Some marks can't be assigned
(`?`) when a frame was never tracked: assign by move order (the AI moves first). A cell can appear both as already
there and as a button event: de-duplicate (`ai.py` does).

## Data needed

Recordings (Dungeon Recorder, `part*.jsonl.gz` is enough) of many Tic Tac Toe games played **in view**: ideally 50+
games, including deliberate fork attempts and different openings, and some games where you pre-fire by holding
right-click.
