#!/usr/bin/env python3
"""Step 3: Goldor's death ticks: when they land, and where a player has to be to be hit.

usage: deathtick.py OUT_DIR

Death tick = "[BOSS] Goldor: What do you think you are doing there!" plus, for the recorder, a
life-saver proc or death in the same tick ("... Mask saved your life!", "Phoenix Pet saved you",
"You died"). Prints the tick grid (server ticks after "Who dares trespass" mod 60), and for every
grid tick the recorder's section versus the section in progress, hit or not.
"""
import bisect
import collections
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glib as G  # noqa: E402

WDYT = "[BOSS] Goldor: What do you think you are doing there!"
SAVED = ("saved your life", "saved you from certain death", "You died and became a ghost")

# P3Sections.kt boxes (x1 y1 z1 x2 y2 z2), plus the core
BOXES = [(113, 160, 48, 89, 100, 122), (91, 160, 145, 19, 100, 121), (-6, 160, 123, 19, 100, 50),
         (17, 160, 27, 90, 100, 50)]


def section_at(x, y, z):
    if 39 <= x < 71 and y < 155.5 and 54 <= z < 118:
        return 'core'
    for i, b in enumerate(BOXES):
        if min(b[0], b[3]) <= x <= max(b[0], b[3]) and min(b[1], b[4]) <= y <= max(b[1], b[4]) \
                and min(b[2], b[5]) <= z <= max(b[2], b[5]):
            return i + 1
    return 0


def active_section(sec, n):
    k = 1
    for s in ('1', '2', '3'):
        d = sec['done'].get(s)
        if d is not None and d <= n:
            k = int(s) + 1
    return k


def main():
    out_dir = sys.argv[1]
    X = G.load_extracts(out_dir, need_st=True)
    T = json.load(open(os.path.join(out_dir, 'sections.json')))
    phase = collections.Counter()
    gaps = collections.Counter()
    table = collections.Counter()
    examples = []
    for rid, x in X.items():
        c = G.Clock(x['st'])
        g = x['goldor']
        n0 = c.n(g)
        sec = T[rid]
        ns = [c.n(t) - n0 for t, m in x['chat'] if m == WDYT and t >= g]
        for n in ns:
            phase[(n + 30) % 60 - 30] += 1
        for a, b in zip(ns, ns[1:]):
            gaps[b - a] += 1
        # recorder's own hits
        hits = [c.n(t) - n0 for t, m in x['chat'] if t >= g and any(s in m for s in SAVED)]
        me = x['players'].get(x['self'], [])
        mts = [r[0] for r in me]
        dead = [c.n(t) - n0 for t, m in x['chat'] if t >= g and 'You died' in m]
        end = sec['core'] if sec['core'] is not None else (c.n(x['window'][1]) - n0)
        if not sec['done'] and sec['core'] is None:
            continue  # no section timing: cannot say which section is in progress
        k = 1
        while 60 * k < end:
            n = 60 * k
            k += 1
            if any(d < n for d in dead):
                break  # a ghost from here on
            t = c.t_of_n(n + n0)
            i = bisect.bisect_right(mts, t) - 1
            if i < 0:
                continue
            where = section_at(*me[i][1:4])
            act = active_section(sec, n)
            hit = any(-2 <= h - n <= 1 for h in hits)
            rel = where if where in ('core', 0) else (where - act) % 4
            gd = sec['gate'].get(str(act))
            gate = 'gate open' if gd is not None and gd <= n else ('gate up' if act < 4 else 'S4')
            table[(rel, gate if rel == 1 else '', hit)] += 1
            if rel == 1 and gate == 'gate open' and hit and len(examples) < 12:
                examples.append((rid, n, act, [round(v, 1) for v in me[i][1:4]]))
    print('death tick phase (server ticks after the first line, mod 60, centred):', sorted(phase.items()))
    print('gaps between consecutive death-tick lines:', sorted(gaps.items()))
    print('recorder at a grid tick: (where relative to the section in progress, hit?) -> count')
    print('  where: 0 = same section, 1 = next, 2/3 = further ahead (= behind), core, 0 box = none')
    for k in sorted(table, key=str):
        print('  ', k, table[k])
    for e in examples:
        print('  hit in the next section with its gate already open:', e)


if __name__ == '__main__':
    main()
