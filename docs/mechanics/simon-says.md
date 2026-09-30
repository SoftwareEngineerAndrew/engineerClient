# Simon Says (F7 P3, section 1's device)

Measured from 208 Better PF recordings that reach P3 (437 F7 runs on undonecoffee.com, one
recording per run, to 2026-09-30); scripts in `tools/boss-mechanics/simon-says/`. Ticks are the
recorder's client ticks (20/s), so single-tick differences are within noise. SS Practice
(`practice/SimonSaysPractice.kt`) plays it this way.

## The device

- Grid: obsidian at x 111, y 120-123, z 92-95. A light is the obsidian turning into a sea lantern.
- Buttons: stone buttons on its face at x 110, one per cell; they come and go (below).
- Start: the stone button at (110, 121, 91), left of the grid as you face it.
- Where it's done from: healers stand on the quartz ledge at about (108.3, 120, 94.05), facing +x,
  1.7 blocks from the buttons (the same spot in every recording of one player).

## A run of it

- **The sequence**: 5 cells, no repeats (201 of 207), new each time.
- **Start**: the first light comes 6 ticks after the first start press. Presses in those 6 ticks
  set the first show: 1 press shows round 1 (one light); 2 presses show a stray light and then the
  first of the sequence; 3 presses (the skip) show a stray light and the first two. The stray
  light isn't part of the sequence: you press only what follows it. Shows seen: 3,3,4,5 in 115 runs
  (the skip), 2,3,4,5 in 15, 2,2,3,4,5 in 12, 1,2,3,4,5 in 7.
- **Lights**: one every 8 ticks (p10 6, p90 9), each out as the next comes on; buttons gone the
  whole time.
- **Buttons back**: all 16, 10 ticks after the last light goes out (18 after it came on). After a
  show that starts with a stray light, 15 come back 5 ticks after the last light comes on; the
  still-lit cell's button waits until 10 ticks after its light goes out, so a quick second click
  there hits the obsidian.
- **Pressing**: a pressed button shows powered for about 3 ticks; clicking it again meanwhile
  does nothing (spam clicks aren't extra presses).
- **Next round**: 6 ticks after the round's last correct press (p10 5, p90 7): buttons gone and
  the sequence one longer, from the start. After the 5-long round that moment is the device done
  ("<name> completed a device! (n/7)").
- **Wrong press**: buttons gone 3 ticks later, and about 25 ticks after the press a new sequence,
  shown like the skip (stray light + first two).

## How fast

Clean runs (the skip, no wrong press), P3's first line to done, medians:

| player | runs | start | first show | r3 | r4 | r5 | total |
|---|---|---|---|---|---|---|---|
| 12heart | 2 | 0.35 | 0.90 | 0.90 | 1.40 | 1.25 | 11.75 |
| Inplse | 6 | 0.30 | 1.10 | 0.75 | 1.05 | 1.25 | 11.85 |
| Joeher47 | 7 | 0.35 | 1.15 | 0.80 | 1.05 | 1.30 | 12.20 |
| TheBadOne | 46 | 0.50 | 1.00 | 1.00 | 1.30 | 1.65 | 12.70 |
| meowinging | 3 | 0.55 | 1.10 | 0.90 | 1.40 | 1.60 | 12.80 |
| BurstFade_ | 4 | 0.60 | 1.30 | 1.10 | 1.20 | 1.35 | 13.05 |
| p3wr | 10 | 0.50 | 1.10 | 1.10 | 1.70 | 2.10 | 14.15 |
| Lentissimo | 8 | 0.50 | 1.10 | 1.25 | 1.55 | 2.20 | 14.20 |
| GaVan92 | 6 | 0.75 | 1.65 | 1.35 | 1.60 | 2.30 | 14.70 |

Start = P3's line to the first light; each round = its buttons coming back to the next round
starting (the clicking, plus the fixed 6 ticks). Everything else is the device's own fixed timing
(about 7.4 s of lights and waits with the skip: 21, 34, 42 and 50 ticks from each show starting to its buttons), so the gaps between players are all in the start
and the clicking.
