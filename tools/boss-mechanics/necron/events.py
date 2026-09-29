"""Step 2: one row of Necron events per recording -> OUT_DIR/events.json.

    python3 events.py OUT_DIR

Times are server ticks relative to Necron's first line ("You went further than any human before,
congratulations."), from the recording's own `st` lines (`has_st` false: client ticks instead,
not used for timing). Each row:
  lines       [[n, text]]   Necron's [BOSS] lines;  goldor [[n, text]]
  spawn, gone               Necron's wither appearing / going (n)
  exc         [[L, B, max distance from mid, lowest y, [[n, x, y, z], ...]]] trips off mid
  shots       {fireball|wither_skull|tnt: [[n, distance from mid, distance from Necron, flight yaw]]}
              (flight yaw: Minecraft yaw of the first 4+ ticks of flight, client view; None if unseen)
  breaks      [[n, blocks, platform]] ticks where >= 300 blocks of a lava platform (y 59-63)
              turned to air; core [n, blocks] the Goldor-core floor (y >= 100) going
  score, frenzy               "Team Score" line (n); "Necron's Nuclear Frenzy hit you" lines (n)
"""
import bisect
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import necronlib as N  # noqa: E402

# The six lava platforms (block x, z ranges of their footprint) and the one Necron hovers over.
PLATFORMS = {'N': (39, 69, 32, 52), 'S': (39, 69, 100, 120), 'NW': (14, 42, 36, 64),
             'NE': (66, 94, 36, 64), 'SW': (14, 42, 88, 116), 'SE': (66, 94, 88, 116)}


def platform_of(x, z):
    best = None
    for k, (a, b, c, d) in PLATFORMS.items():
        cx, cz = (a + b) / 2, (c + d) / 2
        dd = math.hypot(x - cx, z - cz)
        if best is None or dd < best[0]:
            best = (dd, k)
    return best[1] if best and best[0] < 12 else None


def row(x):
    st = N.start_line(x)
    s0 = st[1] if x['has_st'] else st[0]

    def rel(t, n):
        return (n if x['has_st'] else t) - s0

    nid = sorted(N.necron_ids(x))[0]
    ev = x['ents'][nid]['ev']
    r = {'id': x['id'], 'group': x['group'], 'self': x['self'], 'has_st': x['has_st'],
         'party': len(x['party'] or []), 'npk': sum(1 for v in ev if v[2] == 'e')}
    r['lines'] = [[rel(t, n), m] for t, n, m in N.dialogue(x)]
    r['goldor'] = [[rel(t, n), m] for t, n, m in N.dialogue(x, 'Goldor')]
    r['spawn'] = rel(ev[0][0], ev[0][1]) if ev[0][2] == 's' else None
    g = [v for v in ev if v[2] == 'g']
    r['gone'] = rel(g[-1][0], g[-1][1]) if g else None

    exc = N.excursions(x)
    r['exc'] = [[L, B, round(max(math.dist(p[1:], N.MID) for p in P), 2), round(min(p[2] for p in P), 2),
                 [[p[0], round(p[1], 3), round(p[2], 3), round(p[3], 3)] for p in P[:200]]] for L, B, P in exc]

    # Necron's position at a relative tick (mid unless on a trip)
    def necron_at(n):
        for L, B, P in exc:
            if L <= n and (B is None or n < B):
                ns = [p[0] for p in P]
                i = max(0, bisect.bisect_right(ns, n) - 1)
                return P[i][1:]
        return N.MID

    shots = {}
    for e in x['ents'].values():
        typ = e['type'].split(':')[1]
        if typ not in ('fireball', 'wither_skull', 'tnt') or e['ev'][0][2] != 's':
            continue
        s = e['ev'][0]
        n = rel(s[0], s[1])
        later = [v for v in e['ev'] if v[2] == 'e' and v[0] - s[0] >= 4]
        yaw = None
        if later:
            v = later[0]
            yaw = round(math.degrees(math.atan2(-(v[3] - s[3]), v[5] - s[5])), 1)
        shots.setdefault(typ, []).append([n, round(math.dist(s[3:6], N.MID), 2),
                                          round(math.dist(s[3:6], necron_at(n)), 2), yaw])
    for v in shots.values():
        v.sort(key=lambda s: (s[0], s[1]))
    r['shots'] = shots

    per = {}
    core = {}
    for b in x['blocks']:
        if b[5] != 'minecraft:air':
            continue
        n = rel(b[0], b[1])
        if 59 <= b[3] <= 63:
            p = platform_of(b[2], b[4])
            if p:
                per.setdefault((n, p), 0)
                per[(n, p)] += 1
        elif b[3] >= 100 and 39 <= b[2] <= 69 and 99 <= b[4] <= 129:
            core[n] = core.get(n, 0) + 1
    r['breaks'] = sorted([n, k, p] for (n, p), k in per.items() if k >= 300)
    r['core'] = max(core.items(), key=lambda kv: kv[1]) if core else None
    r['score'] = next((rel(t, n) for t, n, m in x['chat'] if 'Team Score:' in m and rel(t, n) > 0), None)
    r['frenzy'] = [rel(t, n) for t, n, m in x['chat'] if "Necron's Nuclear Frenzy hit you" in m]
    return r


def main():
    out_dir = sys.argv[1]
    X = N.load_extracts(out_dir)
    rows = [row(x) for _, x in sorted(X.items())]
    json.dump(rows, open(os.path.join(out_dir, 'events.json'), 'w'))
    print(len(rows), 'recordings,', sum(r['has_st'] for r in rows), 'with server ticks,',
          len({r['group'] for r in rows}), 'runs')


if __name__ == '__main__':
    main()
