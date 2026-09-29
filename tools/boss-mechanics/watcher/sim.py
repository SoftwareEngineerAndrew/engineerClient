"""Step 4: Monte Carlo of the part of the camp after the Watcher's move, with every mob killed the
moment it lands (so the Watcher is always in his no-mobs-alive mode). It gives the spread that the
random skull order alone causes, and the floor of the Watcher split.

    python3 sim.py [--runs N] [--move T] [--pdouble P]

Model (measured, see docs/mechanics/watcher.md; the double-launch rule is a fit, not a mechanism):
  - 30 wall skulls: 10 niches (NICHES, room-relative, one of the two room orientations) x 3
    heights; 19 of them are targets, 4 already fetched before the move, 15 after, in random order.
  - The Watcher leaves at D+move from the middle, flies 0.61 blocks/tick to a spot 0.5 block out
    of the target niche and 1 block above the skull, launches it 9 ticks after arriving, and
    leaves for the next target on the first D+40k tick after he arrived.
  - A next target in the same or the neighbouring niche (<= 4.1 blocks away sideways) is launched
    from the same stop 8 ticks later with probability pdouble (measured ~half of such pairs).
  - A skull flies 0.3 blocks/tick to a random point within 3 blocks of the middle; the mob appears
    when it lands.
Prints the last mob's spawn, relative to the door (D), as percentiles.
"""
import math
import random
import sys

NICHES = [(-12.5, -9.5), (-12.5, 8.5), (11.5, -9.5), (11.5, 8.5), (-4.5, -12.5), (-0.5, -12.5), (3.5, -12.5),
          (-4.5, 11.5), (-0.5, 11.5), (3.5, 11.5)]
SLOTS = [(x, y, z) for (x, z) in NICHES for y in (71.75, 75.75, 79.75)]
V_WATCHER = 0.61
V_SKULL = 0.30
LAUNCH_DELAY = 9
STEP = 40


def stop_for(s):
    x, y, z = s
    if abs(x + 0.5) > abs(z + 0.5):
        x -= math.copysign(0.5, x + 0.5)
    else:
        z -= math.copysign(0.5, z + 0.5)
    return (x, y + 1.0, z)


def last_spawn(move, pdouble, rng):
    targets = rng.sample(SLOTS, 19)[4:]
    pos, g, launches, i = (-0.6, 73.2, -0.5), move, [], 0
    while i < len(targets):
        s = targets[i]
        p = stop_for(s)
        arr = g + math.dist(pos, p) / V_WATCHER
        launches.append((arr + LAUNCH_DELAY, s))
        pos, i = p, i + 1
        while i < len(targets) and math.hypot(targets[i][0] - s[0], targets[i][2] - s[2]) <= 4.1 and rng.random() < pdouble:
            launches.append((launches[-1][0] + 8, targets[i]))
            s, i = targets[i], i + 1
        g = math.floor(arr / STEP) * STEP + STEP
    t, s = launches[-1]
    dest = (-0.5 + rng.uniform(-3, 3), 73.3, -0.5 + rng.uniform(-3, 3))
    return t + math.dist(s, dest) / V_SKULL


def main():
    n = int(sys.argv[sys.argv.index('--runs') + 1]) if '--runs' in sys.argv else 20000
    move = int(sys.argv[sys.argv.index('--move') + 1]) if '--move' in sys.argv else 480
    pds = [float(sys.argv[sys.argv.index('--pdouble') + 1])] if '--pdouble' in sys.argv else [0.0, 0.5, 1.0]
    rng = random.Random(1)
    for pd in pds:
        v = sorted(last_spawn(move, pd, rng) for _ in range(n))
        q = lambda p: round(v[int(p * (len(v) - 1))])  # noqa: E731
        print('move D+%d, pdouble %.1f: last spawn D+ min %d  p1 %d  p10 %d  median %d  p90 %d'
              % (move, pd, q(0), q(0.01), q(0.1), q(0.5), q(0.9)))


if __name__ == '__main__':
    main()
