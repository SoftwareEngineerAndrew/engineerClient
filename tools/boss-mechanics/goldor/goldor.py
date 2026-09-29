#!/usr/bin/env python3
"""Step 5: Goldor himself - his track, speeds, the catch-up, the flight into the core, his death.

usage: goldor.py OUT_DIR

Needs extract/ and sections.json. Goldor is the wither seen on the P3 track (y >= 117.3 on one of
the four track lines) - not Storm (spawned at 102.4, 183, 52.4), not Necron (54, 66, 76), and not
the second wither that waits at the core entrance (~55.9, 115.06, 50.7) late in P3. Positions are
de-lerped (tools/boss-movement/bosslib.py) and timed on the recording's server ticks, n = 0 at
"Who dares trespass". Only recordings with server ticks are used.

The track is a loop; `s` is the distance along it from the S4/S1 corner (99.5, 40.6):
S1 line x = 99.5 (s 0-90.7), S2 line z = 131.3 (s 90.7-182.1), S3 line x = 8.1 (s 182.1-272.8),
S4 line z = 40.6 (s 272.8-364.2).
"""
import bisect
import collections
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glib as G  # noqa: E402
import sections as SEC  # noqa: E402

X1, Z2, X3, Z4 = 99.5, 131.3, 8.1, 40.6
L1 = Z2 - Z4
L2 = X1 - X3
LOOP = 2 * (L1 + L2)
SEG_START = {1: 0.0, 2: L1, 3: L1 + L2, 4: 2 * L1 + L2}
FLIGHT_TARGET = (54.5, 40.5)
CORE_BOX = (39, 71, 54, 118)   # x0, x1, z0, z1 (y < 155.5): DungeonSplits.everyoneInCore


def sloop(x, z):
    c = []
    if abs(x - X1) < 1.5 and Z4 - 2 < z < Z2 + 2:
        c.append(z - Z4)
    if abs(z - Z2) < 1.5 and X3 - 2 < x < X1 + 2:
        c.append(L1 + (X1 - x))
    if abs(x - X3) < 1.5 and Z4 - 2 < z < Z2 + 2:
        c.append(L1 + L2 + (Z2 - z))
    if abs(z - Z4) < 1.5 and X3 - 2 < x < X1 + 2:
        c.append(2 * L1 + L2 + (x - X3))
    return c[0] % LOOP if len(c) == 1 else None


def segment(s):
    return 1 if s < L1 else 2 if s < L1 + L2 else 3 if s < 2 * L1 + L2 else 4


def goldor_packets(x, core):
    """{wither id: [(n, x, y, z, s or None)]} for the withers that walk the track before the core opens."""
    c = G.Clock(x['st'])
    g = x['goldor']
    n0 = c.n(g)
    out = {}
    for k, e in x['ents'].items():
        if e['type'] != 'minecraft:wither':
            continue
        pk, _ = G.delerp(e['ev'])
        pts = [(c.n(p[0]) - n0, p[1], p[2], p[3]) for p in pk if p[0] >= g - 20]
        pts = [(n, a, b, d, sloop(a, d) if b >= 117.3 else None) for n, a, b, d in pts]
        on = [p for p in pts if p[4] is not None and (core is None or p[0] < core)]
        if len(on) >= 2:
            out[k] = pts
    return out


def speeds(pts, core, w=10):
    """[(n, s, v along the loop per server tick)] over windows of >= w ticks, on the track only."""
    tr = [p for p in pts if p[4] is not None and (core is None or p[0] < core)]
    out = []
    i = 0
    while i < len(tr):
        j = i + 1
        while j < len(tr) and tr[j][0] - tr[i][0] < w:
            j += 1
        if j >= len(tr):
            break
        a, b = tr[i], tr[j]
        if b[0] - a[0] <= w + 6:
            ds = (b[4] - a[4]) % LOOP
            if ds > LOOP / 2:
                ds -= LOOP
            out.append((a[0], a[4], ds / (b[0] - a[0])))
        i = j
    return out


def main():
    out_dir = sys.argv[1]
    X = G.load_extracts(out_dir, need_st=True)
    T = json.load(open(os.path.join(out_dir, 'sections.json')))
    geo = collections.defaultdict(list)
    dn_hist = collections.Counter()
    vh = collections.Counter()
    starts = []
    fast_rows, fast_end = [], []
    fam = {}
    flights = []
    per_run = {}
    for rid, x in X.items():
        sec = T[rid]
        core = sec['core']
        P = goldor_packets(x, core)
        if not P:
            continue
        per_run[rid] = P
        anyfast = anyslow_behind = False
        for k, pts in P.items():
            prev = None
            for p in pts:
                if p[4] is not None and (core is None or p[0] < core):
                    seg = segment(p[4])
                    geo[('S%d line' % seg, 'x' if seg in (1, 3) else 'z')].append(p[1] if seg in (1, 3) else p[3])
                    geo[('S%d line' % seg, 'y')].append(p[2])
                    if prev is not None and prev[4] is not None:
                        dn_hist[p[0] - prev[0]] += 1
                    if p[0] <= 15:
                        starts.append((p[0], round(p[1], 2), round(p[2], 2), round(p[3], 2), round(p[4], 1)))
                prev = p
            sp = speeds(pts, core)
            for i, (n, s, v) in enumerate(sp):
                vh[round(v, 2) if v < 0.1 else round(v, 1)] += 1
                act = SEC.active_section(sec, n)
                seg = segment(s)
                if v > 0.3:
                    anyfast = True
                    fast_rows.append((act, seg, n))
                    if i + 1 < len(sp) and sp[i + 1][2] < 0.1:
                        fast_end.append((round(sp[i + 1][1], 1), act))
                elif 0.045 < v < 0.1 and seg < act and seg in (1, 2, 3):
                    anyslow_behind = True
            # the flight into the core
            if core is not None:
                fl = [p for p in pts if p[0] >= core - 5]
                for a, b in zip(fl, fl[1:]):
                    v = ((b[1] - a[1]) ** 2 + (b[3] - a[3]) ** 2) ** .5 / max(1, b[0] - a[0])
                    if v > 0.3 and a[4] is not None:
                        flights.append((rid, k, a, fl))
                        break
        d1 = sec['door'].get('1')
        if anyfast:
            fam[rid] = ('catches up', d1)
        elif anyslow_behind:
            fam[rid] = ('never fast', d1)

    print('recordings with Goldor seen on the track: %d' % len(per_run))
    print('\ntrack lines (median [p10-p90]):')
    for k in sorted(geo):
        print('  %s %s: %s' % (k[0], k[1], G.summary(geo[k], 2)))
    print('\nserver ticks between consecutive position packets on the track:', sorted(dn_hist.items())[:10])
    print('\nearliest sightings (n <= 15):', sorted(starts)[:12], '... n=%d' % len(starts))
    print('\nspeed along the loop over >= 10-tick windows (blocks per server tick): histogram')
    print('  ', sorted(vh.items()))
    print('\nfast (> 0.3) windows: (section in progress, Goldor\'s segment) ->',
          sorted(collections.Counter((a, s) for a, s, _ in fast_rows).items()))
    print('where a fast stretch ends (s, section in progress):', sorted(fast_end))
    print('  segment starts: S2 %.1f, S3 %.1f, S4 %.1f' % (SEG_START[2], SEG_START[3], SEG_START[4]))
    c = collections.defaultdict(list)
    for rid, (f, d1) in fam.items():
        if d1 is not None:
            c[f].append(d1)
    print('\nS1 door time (server ticks) in runs where Goldor was seen catching up vs only seen walking behind:')
    for f in c:
        print('  %-11s %s' % (f, G.summary(c[f], 0)))

    # flight
    print('\nflight into the core:')
    vs, ys, lag, arr, deaths = [], [], [], [], collections.defaultdict(list)
    for rid, k, a, fl in flights:
        sec = T[rid]
        seg = [p for p in fl if p[0] >= a[0]]
        for p, q in zip(seg, seg[1:]):
            dt = q[0] - p[0]
            if 1 <= dt <= 3 and q[0] - a[0] < 40:
                vs.append(((q[1] - p[1]) ** 2 + (q[3] - p[3]) ** 2 + (q[2] - p[2]) ** 2) ** .5 / dt)
                ys.append((q[2] - p[2]) / dt)
        dist = ((a[1] - FLIGHT_TARGET[0]) ** 2 + (a[3] - FLIGHT_TARGET[1]) ** 2) ** .5
        e = sec['ends']
        if e['doneit'] is not None:
            arr.append(e['doneit'] - a[0] - dist / 0.8)
        dead = e['dots'] if e['dots'] is not None and e['doneit'] is None else None
        if dead is not None:
            deaths['killed in flight: "...." minus departure'].append(dead - a[0])
            deaths['killed in flight: "...." minus the time to reach the target'].append(dead - a[0] - dist / 0.8)
        elif e['doneit'] is not None and e['necron'] is not None:
            deaths['reached the core: Necron line - 82 minus departure'].append(e['necron'] - 82 - a[0])
        # everyone in the core box (anyone's view) before the departure
        x = X[rid]
        cc = G.Clock(x['st'])
        n0 = cc.n(x['goldor'])
        last = []
        for nm, rw in x['players'].items():
            state, t_in = False, None
            for r in rw:
                n = cc.n(r[0]) - n0
                if n > a[0] + 1:
                    break
                ic = CORE_BOX[0] <= r[1] < CORE_BOX[1] and r[2] < 155.5 and CORE_BOX[2] <= r[3] < CORE_BOX[3]
                if ic and not state:
                    t_in = n
                state = ic
            last.append((t_in if state else None, nm == x['self']))
        if len(last) >= 5 and all(t is not None for t, _ in last):
            tl, me = max(last)
            lag.append((a[0] - max(tl, sec['core']), me))
    print('  speed (3D, blocks per server tick):', G.summary(vs, 3))
    print('  vertical speed:', G.summary(ys, 3))
    print('  "You have done it..." minus (departure + distance to (54.5, 40.5) / 0.8):', G.summary(arr, 1))
    for k, v in deaths.items():
        print('  %s: %s' % (k, G.summary(v, 0)))
    print('  departure (last track packet) minus the last player entering the core box:',
          sorted(collections.Counter(d for d, _ in lag).items()))
    print('    ... where that last player was the recorder itself (exact position):',
          sorted(collections.Counter(d for d, me in lag if me).items()))
    json.dump({rid: {k: pts for k, pts in P.items()} for rid, P in per_run.items()},
              open(os.path.join(out_dir, 'goldor_packets.json'), 'w'))


if __name__ == '__main__':
    main()
