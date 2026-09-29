#!/usr/bin/env python3
"""Step 3: every number in docs/maxor-storm-movement.md (except the model fits, see fit.py).

usage: analyze.py OUT_DIR [section ...]      sections: data maxor storm fastest (default: all)

Reads OUT_DIR/extract (extract.py) and OUT_DIR/tracks (tracks.py). Times are server ticks from
the boss's first line unless a line says otherwise; "server-timed" runs are those whose every
recording has `st` lines (older recorders only have client ticks, which run fast when the server
lags, so they are left out of anything timed to the tick).
"""
import collections
import json
import math
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bosslib as B  # noqa: E402
import recording as R  # noqa: E402

YELLOW_POINT = (46.0, 65.0)
REST = (102.38, 183.0, 52.38)
WAYPOINTS = [(103, 188, 53), (73, 183, 83), (43, 183, 53), (73, 183, 23), (103, 183, 53)]


# ------------------------------------------------------------------ helpers

def stats(v, nd=1):
    v = [x for x in v if x is not None]
    if not v:
        return 'n=0'
    f = '%.' + str(nd) + 'f'
    return ('n=%d median ' + f + ' p10 ' + f + ' p90 ' + f + ' min ' + f + ' max ' + f) % (
        len(v), statistics.median(v), B.pct(v, 10), B.pct(v, 90), min(v), max(v))


def counts(v, top=10):
    return ', '.join('%s: %d' % (k, c) for k, c in collections.Counter(v).most_common(top))


class Data:
    def __init__(self, out_dir):
        self.X = B.load_extracts(out_dir)
        self.T = {}
        for f in sorted(os.listdir(os.path.join(out_dir, 'tracks'))):
            self.T[f[:-5]] = json.load(open(os.path.join(out_dir, 'tracks', f)))
        self.server = sorted(g for g, tr in self.T.items() if all(len(self.X[r]['st']) > 10 for r in tr['recs']))
        self._pk = {}

    def ev(self, g, msgs, k=0):
        if isinstance(msgs, str):
            msgs = (msgs,)
        v = sorted(x for m in msgs for x in self.T[g]['events'].get(m, []))
        return v[k] if len(v) > k else None

    def all_ev(self, g, msgs):
        if isinstance(msgs, str):
            msgs = (msgs,)
        return sorted(x for m in msgs for x in self.T[g]['events'].get(m, []))

    def packets(self, g, boss, one_recording=False):
        key = (g, boss, one_recording)
        if key not in self._pk:
            tr = self.T[g]
            pk = tr['bosses'].get(boss, {}).get('pkt', [])
            if one_recording and pk:
                rec = collections.Counter(p[4] for p in pk).most_common(1)[0][0]
                pk = [p for p in pk if p[4] == rec]
            else:
                pk = B.dedupe_packets(pk)
            self._pk[key] = pk
        return self._pk[key]

    def candidates(self, g, n):
        tr = self.T[g]
        ghosts = B.ghost_spans(tr, None)
        c = {}
        for name, rows in tr['players'].items():
            if rows and B.alive(ghosts, name, n):
                q = B.player_at(rows, n)
                if q:
                    c[name] = q
        return c


def storm_pillar_at(pk, n):
    p = B.pos_at(pk, n, max_gap=40)
    if not p:
        return None
    for k, (x0, _, z0, _) in B.PILLARS.items():
        if x0 - 1 <= p[0] <= x0 + 7 and z0 - 1 <= p[2] <= z0 + 7:
            return k
    return None


# ------------------------------------------------------------------ data section

def section_data(D):
    print('\n######## DATA')
    X, T = D.X, D.T
    print('recordings with a Maxor/Storm window: %d; runs (groups): %d; server-timed runs: %d' % (len(X), len(T), len(D.server)))
    mods = collections.Counter(x['mod'] for x in X.values())
    print('recorder versions:', counts(mods))
    nost = [r for r, x in X.items() if len(x['st']) <= 10]
    print('recordings without server ticks (client timebase only): %d' % len(nost))
    dec = collections.Counter(B.rec_decimals(x) for x in X.values())
    print('position precision (decimals written):', dict(dec))
    sizes = collections.Counter(len(tr['recs']) for tr in T.values())
    print('recordings per run:', dict(sizes))
    spread_n, spread_t = [], []
    for tr in T.values():
        for rid, o in tr['offsets'].items():
            if o['shared']:
                spread_n.append(o['spread_n'])
                spread_t.append(o['spread_t'])
    print('sibling alignment on shared chat lines: max deviation (server ticks)', counts(spread_n), '| (client ticks)', counts(spread_t))
    for boss in ('maxor', 'storm'):
        have = [g for g in T if boss in T[g]['bosses']]
        how = counts(T[g]['bosses'][boss]['how'] for g in have)
        npk = [sum(1 for p in T[g]['bosses'][boss]['pkt'] if p[6] == 'p') for g in have]
        print('%s identified in %d runs (%s); recovered move packets per run: %s' % (boss, len(have), how, stats(npk, 0)))
        grid = []
        for g in have:
            for rid, pr in T[g]['bosses'][boss]['per_rec'].items():
                if pr['packets'] >= 30 and pr['grid_rms'] is not None and pr['decimals'] >= 3:
                    grid.append(pr['grid_rms'] * 32)
        print('   distance of recovered packet x/z from the 1/32 grid, rms in 1/32 units (uniform would be 0.289): %s' % stats(grid, 3))
    # packet cadence
    for boss in ('maxor', 'storm'):
        gaps = collections.Counter()
        for g in D.server:
            if boss not in T[g]['bosses']:
                continue
            pk = [p for p in D.packets(g, boss, True) if p[6] == 'p']
            for a, b in zip(pk, pk[1:]):
                if B.dist(a[1:4], b[1:4]) > 0.1 and b[0] - a[0] <= 10:
                    gaps[b[0] - a[0]] += 1
        tot = sum(gaps.values()) or 1
        print('%s: server ticks between move packets while moving: %s' % (boss, ', '.join('%d: %.3f' % (k, v / tot) for k, v in sorted(gaps.items()) if v / tot >= 0.001)))
    others = []
    for g, tr in T.items():
        for o in tr['others']:
            others.append((o[2], o[4]))
    ss_rel = []
    for g, tr in T.items():
        ss = D.ev(g, R.STORM_START)
        if ss is None:
            continue
        for o in tr['others']:
            if 160 <= o[4] <= 180:
                ss_rel.append(o[2] - ss)
    print('other (untagged) withers first seen at y 160-180 during Storm: %d, first seen %s ticks after Storm\'s first line' % (len(ss_rel), stats(ss_rel, 0)))


# ------------------------------------------------------------------ Maxor

def section_maxor(D):
    print('\n######## MAXOR')
    T = D.T
    rel = collections.defaultdict(list)
    for g in D.server:
        ms = D.ev(g, R.MAXOR_START)
        if ms is None:
            continue
        for m, vs in T[g]['events'].items():
            if 'Maxor' in m or m == R.STORM_START:
                for k, v in enumerate(vs[:2]):
                    rel[(m, k)].append(v - ms)
    print('lines after "WELL! WELL! WELL!" (server-timed runs):')
    for (m, k), v in sorted(rel.items(), key=lambda kv: statistics.median(kv[1])):
        if len(v) >= 8:
            print('   %-70s #%d %s' % (m[:70], k + 1, stats(v, 0)))
    first, spawn = [], collections.Counter()
    for g in D.server:
        if 'maxor' not in T[g]['bosses']:
            continue
        intro = D.ev(g, R.MAXOR_INTRO_END)
        pk = D.packets(g, 'maxor')
        s0 = [p for p in T[g]['bosses']['maxor']['pkt'] if p[6] == 's']
        if s0:
            spawn[tuple(round(v, 2) for v in s0[0][1:4])] += 1
        if intro is None:
            continue
        f = next((p for p in pk if B.dist(p[1:4], B.MAXOR_SPAWN) > 0.2), None)
        seen = [o for o in T[g]['bosses']['maxor']['obs'] if intro <= o[0] <= intro + 45]
        if f and seen:
            first.append(f[0] - intro)
    print('Maxor spawns at:', counts(spawn, 3))
    print('first move after "DON\'T DISAPPOINT ME" (he was in view):', counts(first, 8))
    # stuns
    stopped, resume, dur, where = collections.Counter(), [], [], collections.Counter()
    for g in D.server:
        if 'maxor' not in T[g]['bosses']:
            continue
        pk = D.packets(g, 'maxor')
        enr = D.all_ev(g, R.MAXOR_ENRAGED)
        for s in D.all_ev(g, R.MAXOR_STUN):
            p0 = B.pos_at(pk, s + 3, max_gap=60)
            if not p0:
                continue
            where[tuple(round(v) for v in p0)] += 1
            before = [p for p in pk if s - 12 <= p[0] < s]
            if len(before) < 2 or B.dist(before[0][1:4], before[-1][1:4]) < 0.5:
                continue   # was not moving before: nothing to stop
            res = next((p for p in pk if p[0] > s + 2 and B.dist(p[1:4], p0) > 0.3), None)
            e = next((x for x in enr if x >= s - 5), None)
            if res and e:
                resume.append(res[0] - e)
                dur.append(e - s)
            stopped[res is None or res[0] - s > 5] += 1
    print('Maxor position 3 ticks after a stun line:', counts(where, 6))
    box = sum(v for k, v in where.items() if 71 <= k[0] <= 75 and 71 <= k[2] <= 76 and 224 <= k[1] <= 227)
    print('   %d of %d within x 71-75, z 71-76, y 224-227' % (box, sum(where.values())))
    print('stun line -> "Maxor is enraged!": %s' % stats(dur, 0))
    print('first move after the enrage line: %s' % stats(resume, 0))
    # targeting
    targeting_table(D, 'maxor', lambda g, n: 'maxor')
    speed_vs_distance(D, 'maxor', lambda g, n: True)
    maxor_hover(D)
    retarget(D, 'maxor')


def targeting_table(D, boss, phase_of):
    """Heading vs bearing to candidate targets, where the 3D- and horizontally-closest differ."""
    res = collections.defaultdict(lambda: collections.defaultdict(list))
    for g in D.server:
        tr = D.T[g]
        if boss not in tr['bosses']:
            continue
        pk = D.packets(g, boss)
        for i, p in enumerate(pk):
            ph = phase_of(g, p[0])
            if not ph:
                continue
            v = B.velocity(pk, i)
            if not v or math.hypot(v[0], v[2]) < 0.2:
                continue
            hd = B.bearing(v[0], v[2])
            c = D.candidates(g, p[0])
            if len(c) < 2:
                continue
            c3 = min(c, key=lambda k: B.dist(c[k], p[1:4]))
            ch = min(c, key=lambda k: B.hdist(c[k], p[1:4]))
            err = lambda q: abs(B.angdiff(hd, B.bearing(q[0] - p[1], q[2] - p[3])))
            res[ph]['closest 3D'].append(err(c[c3]))
            res[ph]['closest horizontal'].append(err(c[ch]))
            res[ph]['Yellow (46,65)'].append(err((YELLOW_POINT[0], 0, YELLOW_POINT[1])))
            if c3 != ch:
                res[ph]['closest 3D, where they differ'].append(err(c[c3]))
                res[ph]['closest horizontal, where they differ'].append(err(c[ch]))
            y = (YELLOW_POINT[0], 0, YELLOW_POINT[1])
            if abs(B.angdiff(B.bearing(c[c3][0] - p[1], c[c3][2] - p[3]), B.bearing(y[0] - p[1], y[2] - p[3]))) > 20:
                res[ph]['closest 3D, where it and Yellow are >20 deg apart'].append(err(c[c3]))
                res[ph]['Yellow, where it and closest 3D are >20 deg apart'].append(err(y))
    for ph, d in res.items():
        print('%s targeting: |heading - bearing| in degrees' % ph)
        for k, v in d.items():
            print('   %-40s median %5.1f  p75 %5.1f  within 10 deg %.2f  (n=%d)' % (k, statistics.median(v), B.pct(v, 75), sum(1 for a in v if a < 10) / len(v), len(v)))


def speed_vs_distance(D, boss, keep, target='closest'):
    bins = collections.defaultdict(list)
    vy = collections.defaultdict(list)
    for g in D.server:
        tr = D.T[g]
        if boss not in tr['bosses']:
            continue
        pk = [p for p in D.packets(g, boss, True) if p[6] == 'p']
        for a, b in zip(pk, pk[1:]):
            dn = b[0] - a[0]
            if dn < 1 or dn > 4 or not keep(g, a[0]):
                continue
            if target == 'closest':
                c = D.candidates(g, a[0])
                if not c:
                    continue
                q = min(c.values(), key=lambda q: B.dist(q, a[1:4]))
            else:
                q = target
            d = B.dist(q, a[1:4]) if target == 'closest' else math.hypot(a[1] - q[0], a[3] - q[1])
            sp = B.dist(a[1:4], b[1:4]) / dn
            if sp < 0.02:
                continue
            bins[min(int(d // 4) * 4, 44)].append(sp)
            if target == 'closest':
                vy[max(-12, min(12, round((q[1] - a[2]) / 3) * 3))].append((b[2] - a[2]) / dn)
    print('%s speed (blocks per server tick) vs distance to %s:' % (boss, 'the 3D-closest player' if target == 'closest' else 'the fixed target'))
    for k in sorted(bins):
        v = bins[k]
        if len(v) >= 15:
            print('   d %2d-%2d  n=%4d  median %.3f  p25 %.3f  p75 %.3f' % (k, k + 4, len(v), statistics.median(v), B.pct(v, 25), B.pct(v, 75)))
    if vy:
        print('   vertical speed vs (target y - boss y): ' + ', '.join('%+d: %+.3f' % (k, statistics.median(v)) for k, v in sorted(vy.items()) if len(v) >= 15))


# ------------------------------------------------------------------ Storm

def storm_phase(D, g):
    """Which movement phase Storm is in at tick n (None = ignore)."""
    ss = D.ev(g, R.STORM_START)
    li = D.ev(g, R.STORM_LIGHTNING)
    cr = D.all_ev(g, R.STORM_CRUSHED)
    dead = D.ev(g, R.STORM_DEAD) or 10 ** 9
    pk = D.packets(g, 'storm')

    def ph(g_, n):
        if ss is None or li is None or n >= dead or n < li + 139:
            return None
        if not cr or n < cr[0]:
            return 'chase'
        if storm_pillar_at(pk, cr[0] + 4) != 'purple':
            return None
        far = B.pos_at(pk, n, 4)
        if far and n < cr[0] + 140 and math.hypot(far[0] - YELLOW_POINT[0], far[2] - YELLOW_POINT[1]) > 8:
            return 'after crush 1, far from Yellow'
        return 'after crush 1, near Yellow or later'
    return ph


def section_storm(D):
    print('\n######## STORM')
    T = D.T
    # timeline
    rel = collections.defaultdict(list)
    for g in D.server:
        ss = D.ev(g, R.STORM_START)
        if ss is None:
            continue
        for m, vs in T[g]['events'].items():
            if 'Storm' in m or m == R.GOLDOR_START:
                for k, v in enumerate(vs[:2]):
                    if v >= ss:
                        rel[(m, k)].append(v - ss)
    print('lines after "Pathetic Maxor" (server-timed runs):')
    for (m, k), v in sorted(rel.items(), key=lambda kv: statistics.median(kv[1])):
        if len(v) >= 8:
            print('   %-60s #%d %s' % (m[:60], k + 1, stats(v, 0)))
    # spawn
    sp = collections.Counter()
    for g in T:
        if 'storm' in T[g]['bosses']:
            s0 = [p for p in T[g]['bosses']['storm']['pkt'] if p[6] == 's']
            ss = D.ev(g, R.STORM_START)
            if s0 and ss is not None and abs(s0[0][0] - ss) <= 3:
                sp[(tuple(round(v, 2) for v in s0[0][1:4]), s0[0][0] - ss)] += 1
    print('Storm spawn lines within 3 ticks of his first line (position, tick):', counts(sp, 4))
    opening(D)
    after_lightning(D)
    ph = {g: storm_phase(D, g) for g in D.server}
    targeting_table(D, 'storm', lambda g, n: ph[g](g, n) if g in ph else None)
    speed_vs_distance(D, 'storm', lambda g, n: ph[g](g, n) == 'chase')
    speed_vs_distance(D, 'storm', lambda g, n: ph[g](g, n) == 'after crush 1, far from Yellow', target=YELLOW_POINT)
    speed_vs_distance(D, 'storm', lambda g, n: ph[g](g, n) == 'after crush 1, near Yellow or later')
    pillar_sequences(D)
    transfer(D)
    steps(D)
    storm_extras(D)
    retarget(D, 'storm')


def ideal_opening(n, speed=0.4):
    s = n * speed
    for a, b in zip(WAYPOINTS, WAYPOINTS[1:]):
        L = B.dist(a, b)
        if s <= L:
            return tuple(a[i] + (b[i] - a[i]) * s / L for i in range(3))
        s -= L
    return WAYPOINTS[-1]


def opening(D):
    L = sum(B.dist(a, b) for a, b in zip(WAYPOINTS, WAYPOINTS[1:]))
    print('opening: waypoints %s, path %.1f blocks = %.1f ticks at 0.4' % (WAYPOINTS, L, L / 0.4))
    resid, offs, arrive, spot = [], [], [], collections.Counter()
    for g in D.server:
        if 'storm' not in D.T[g]['bosses']:
            continue
        ss = D.ev(g, R.STORM_START)
        if ss is None:
            continue
        pk = D.packets(g, 'storm')
        op = [p for p in pk if ss - 2 <= p[0] <= ss + 420]
        if len(op) < 30:
            continue
        o = []
        for p in op:
            n = int(round(p[0] - ss))
            best = min(range(max(0, n - 20), n + 21), key=lambda m: B.dist(ideal_opening(m), p[1:4]))
            o.append(best - n)
            resid.append(B.dist(ideal_opening(best), p[1:4]))
        offs.append(statistics.median(o))
        rp = [p for p in pk if p[0] > ss + 380 and B.dist(p[1:4], REST) < 0.15]
        if rp:
            arrive.append(rp[0][0] - ss)
            spot[tuple(round(v, 2) for v in rp[0][1:4])] += 1
    print('   distance of each packet from the ideal path (best time within +-20): %s' % stats(resid, 3))
    print('   time offset of the ideal path per run (ticks):', counts(offs))
    print('   parked at %s from tick %s' % (counts(spot, 3), stats(arrive, 0)))


def after_lightning(D):
    dep, dep_li, pins, short = [], [], [], []
    for g in D.server:
        if 'storm' not in D.T[g]['bosses']:
            continue
        ss = D.ev(g, R.STORM_START)
        li = D.ev(g, R.STORM_LIGHTNING)
        if ss is None or li is None:
            continue
        pk = D.packets(g, 'storm')
        # seen parked within 12 ticks before he leaves
        d = None
        spans = D.T[g]['bosses']['storm']['spans']
        for p in pk:
            if p[0] > li and B.dist(p[1:4], REST) > 0.25:
                # trusted only if some recording had him in view (parked) for the 12 ticks before
                if any(a <= p[0] - 12 and p[0] <= b for a, b, _ in spans):
                    d = p[0]
                break
        if d:
            dep.append(d - ss)
            dep_li.append(d - li)
            if d - li < 130:
                short.append((g, d - li, len(D.T[g]['party'])))
        cr = D.all_ev(g, R.STORM_CRUSHED)
        en = D.ev(g, R.STORM_ENRAGED)
        if cr and en:
            pins.append(en - cr[0])
    print('leaves his parking spot: %s after his first line, %s after the lightning line' % (stats(dep, 0), stats(dep_li, 0)))
    print('   short lightning phases (< 130 ticks): %s' % short)
    print('crush 1 -> "Storm is enraged!" (the pin): %s' % stats(pins, 0))


def pillar_sequences(D):
    seq = collections.Counter()
    after = collections.defaultdict(list)
    for g, tr in D.T.items():
        if 'storm' not in tr['bosses']:
            continue
        pk = D.packets(g, 'storm')
        cr = D.all_ev(g, R.STORM_CRUSHED)
        s = []
        for c in cr:
            s.append(storm_pillar_at(pk, c + 4) or '?')
            p0 = B.pos_at(pk, c + 4, max_gap=40)
            if not p0:
                continue
            nxt = [p for p in pk if p[0] > c and B.dist(p[1:4], p0) > 6]
            if nxt:
                after[s[-1]].append(round(B.bearing(nxt[0][1] - p0[0], nxt[0][3] - p0[2])))
        seq[' -> '.join(s)] += 1
    print('pillar crush sequences (pillar = where Storm is pinned 4 ticks after the line):', counts(seq, 12))
    for k, v in after.items():
        print('   after a %s crush he first heads (bearing, 0 = +z, 90 = -x): %s' % (k, counts([b // 10 * 10 for b in v], 6)))


def transfer(D):
    rows = []
    for g in D.server:
        tr = D.T[g]
        if 'storm' not in tr['bosses']:
            continue
        cr = D.all_ev(g, R.STORM_CRUSHED)
        if not cr:
            continue
        pk = D.packets(g, 'storm')
        c1 = cr[0]
        p0 = B.pos_at(pk, c1 + 4, max_gap=40)
        if not p0 or storm_pillar_at(pk, c1 + 4) != 'purple':
            continue
        seg = [p for p in pk if p[0] > c1 and (len(cr) < 2 or p[0] <= cr[1] + 1)]
        dep = next((p for p in seg if B.dist(p[1:4], p0) > 0.3), None)
        if not dep:
            continue
        arr = next((p for p in seg if B.in_crush_zone('yellow', p[1], p[3])), None)
        sub = [p for p in seg if dep[0] <= p[0] <= (arr[0] if arr else seg[-1][0])]
        gap = max((b[0] - a[0] for a, b in zip(sub, sub[1:])), default=99)
        # departure is only trusted if some recording had him in view from the crush to it
        pinned_seen = any(a <= c1 and dep[0] <= b for a, b, _ in D.T[g]['bosses']['storm']['spans'])
        fl = [p for p in sub if 55 <= p[1] <= 95]
        sp = None
        if len(fl) > 5 and fl[-1][0] > fl[0][0]:
            sp = sum(B.dist(a[1:4], b[1:4]) for a, b in zip(fl, fl[1:])) / (fl[-1][0] - fl[0][0])
        rows.append(dict(g=g, pin=dep[0] - c1, arr=(arr[0] - c1) if arr else None, gap=gap, sp=sp, ok=pinned_seen,
                         c2=(cr[1] - c1) if len(cr) > 1 else None))
    ok = [r for r in rows if r['gap'] <= 6 and r['arr'] and r['ok']]
    print('Purple -> Yellow flights watched from start to the zone: %d' % len(ok))
    print('   crush 1 line -> first move: %s' % stats([r['pin'] for r in ok], 0))
    print('   first move -> feet inside Yellow\'s crush zone: %s' % stats([r['arr'] - r['pin'] for r in ok], 0))
    print('   crush 1 line -> inside Yellow\'s zone: %s' % stats([r['arr'] for r in ok], 0))
    print('   mean 3D speed while x 95 -> 55: %s' % stats([r['sp'] for r in ok], 3))


def steps(D):
    for label, base, keep in (('opening (tick 80-400)', 0.4, lambda n, cr, p: 80 <= n <= 400),
                              ('Purple -> Yellow flight', 0.72, lambda n, cr, p: cr and cr[0] + 5 < n < cr[0] + 70 and 55 < p[1] < 97)):
        ratio = collections.Counter()
        moves = ticks = 0
        resid = []
        for g in D.server:
            if 'storm' not in D.T[g]['bosses']:
                continue
            ss = D.ev(g, R.STORM_START)
            cr = [c - ss for c in D.all_ev(g, R.STORM_CRUSHED)]
            pk = [p for p in D.packets(g, 'storm', True) if p[6] == 'p']
            sel = [p for p in pk if keep(p[0] - ss, cr, p)]
            for a, b in zip(sel, sel[1:]):
                dn = b[0] - a[0]
                if dn <= 0 or dn > 6:
                    continue
                k = B.dist(a[1:4], b[1:4]) / base
                m = round(k)
                resid.append(k - m)
                ratio[(dn, m)] += 1
                moves += m
                ticks += dn
        rms = math.sqrt(sum(r * r for r in resid) / len(resid)) if resid else float('nan')
        print('%s: packet steps in units of %.2f blocks: rms distance from a whole number %.3f; moves per server tick %.3f; commonest (ticks, moves): %s' % (
            label, base, rms, moves / max(1, ticks), ', '.join('%s: %d' % kv for kv in ratio.most_common(6))))


# ------------------------------------------------------------------ fastest Storm

def section_fastest(D):
    print('\n######## FASTEST STORM')
    rows = []
    for g in D.server:
        tr = D.T[g]
        ss = D.ev(g, R.STORM_START)
        gs = D.ev(g, R.GOLDOR_START)
        dead = D.ev(g, R.STORM_DEAD)
        li = D.ev(g, R.STORM_LIGHTNING)
        if None in (ss, gs, dead, li) or len(tr['party']) < 5:
            continue
        cr = [c - ss for c in D.all_ev(g, R.STORM_CRUSHED)]
        en = D.ev(g, R.STORM_ENRAGED)
        d = dead - ss
        c2 = cr[1] if len(cr) > 1 else d
        rows.append(dict(g=g, split=gs - ss, li=li - ss, c1=cr[0] if cr else None, c2=c2, pin=(en - ss - cr[0]) if (cr and en) else None,
                         dead=d, gold=gs - dead, ncr=len(cr)))
    S = [r['split'] for r in rows]
    print('5-player server-timed runs from Storm to Goldor: %d; Storm split %s' % (len(rows), stats(S, 0)))
    L = collections.defaultdict(list)
    for r in rows:
        if r['c1'] is None:
            continue
        L['lightning line (vs 548)'].append(r['li'] - 548)
        L['crush 1 (vs 699)'].append(r['c1'] - 699)
        L['crush 2 (vs crush 1 + 100)'].append(r['c2'] - r['c1'] - 100)
        L['death (vs crush 2 + 1)'].append(r['dead'] - r['c2'] - 1)
        L['Goldor line (vs death + 101)'].append(r['gold'] - 101)
    print('ticks lost against the best case, per step:')
    for k, v in L.items():
        print('   %-30s median %+4d  mean %+6.1f  runs losing > 5: %4.0f%%  total %5d' % (k, statistics.median(v), statistics.mean(v), 100 * sum(1 for x in v if x > 5) / len(v), sum(max(0, x) for x in v)))
    print('crush 2 - crush 1:', counts([r['c2'] - r['c1'] for r in rows if r['c1']], 10))
    a = [r['pin'] for r in rows if r['c1'] and r['pin'] is not None and 98 <= r['c2'] - r['c1'] <= 102]
    b = [r['pin'] for r in rows if r['c1'] and r['pin'] is not None and r['c2'] - r['c1'] >= 118]
    print('pin when crush 2 came at +100: %s' % stats(a, 0))
    print('pin when crush 2 came later:   %s' % stats(b, 0))
    print('death - crush 2: %s' % stats([r['dead'] - r['c2'] for r in rows if r['ncr'] >= 2], 0))
    print('Goldor line - death: %s' % stats([r['gold'] for r in rows], 0))
    # why +100 was missed
    why = collections.Counter()
    for r in rows:
        if r['c1'] is None or r['c2'] - r['c1'] <= 102:
            continue
        g = r['g']
        ss = D.ev(g, R.STORM_START)
        chk = 19 + 20 * round((r['c1'] + 100 - 19) / 20)
        pk = D.packets(g, 'storm')
        p = B.pos_at(pk, chk + ss + 1, max_gap=8)
        c = collections.Counter(b[5] for b in D.T[g]['blocks'] if B.pillar_of(b[1], b[3]))
        cb = B.crusher_bottoms(D.T[g]['blocks'], c.most_common(1)[0][0]) if c else {}
        ser = cb.get('yellow', [])
        bot = B.value_at(ser, chk + ss)
        last = max([x for x, y in ser if x <= chk + ss and y is not None], default=None)
        w = []
        if p is None:
            w.append('Storm not in view')
        else:
            if not B.in_crush_zone('yellow', p[0], p[2]):
                w.append('not in the zone yet')
            if bot is None or p[1] + B.HEAD < bot:
                w.append('head below the pillar')
        if last is None or chk + ss - last > 60:
            w.append('pillar not stepped in 60 ticks')
        why[('pin > 15' if (r['pin'] or 0) > 15 else 'pin <= 15') + ': ' + (', '.join(w) or '?')] += 1
    print('why crush 2 missed the +100 check:')
    for k, v in why.most_common():
        print('   %3d  %s' % (v, k))
    short = [r for r in rows if r['c1'] and r['pin'] is not None and r['pin'] <= 3]
    print('runs with a pin <= 3: %d, of which crush 2 at +98..102: %d' % (len(short), sum(1 for r in short if r['c2'] - r['c1'] <= 102)))
    best = sorted(rows, key=lambda r: (r['split'], r['g']))[:10]
    print('fastest runs (split, lightning, crush 1, pin, crush 2, death, Goldor - death):')
    for r in best:
        print('   %s  %d  %d  %s  %s  %s  %d  %d' % (r['g'], r['split'], r['li'], r['c1'], r['pin'], r['c2'], r['dead'], r['gold']))
    roles(D, best)
    yellow_margin(D)
    pads(D)
    correlations(D)


def roles(D, best):
    """Where the fastest runs' players stood at crush 1 and crush 2."""
    print('player positions in the fastest runs (3 ticks before each crush; * = Storm\'s 3D-closest):')
    for r in best:
        g = r['g']
        ss = D.ev(g, R.STORM_START)
        pk = D.packets(g, 'storm')
        for label, n in (('crush 1', r['c1']), ('crush 2', r['c2'])):
            if n is None:
                continue
            p = B.pos_at(pk, ss + n - 3, max_gap=20)
            c = D.candidates(g, ss + n - 3)
            if not c:
                continue
            c3 = min(c, key=lambda k: B.dist(c[k], p)) if p else None
            print('   %s %s: %s' % (g[-8:], label, '  '.join('%s%s(%.0f,%.0f,%.0f)' % ('*' if k == c3 else '', k[:8], *q) for k, q in c.items())))


# ------------------------------------------------------------------ extra checks quoted in the report

def pillar_series(D, g):
    tr = D.T[g]
    c = collections.Counter(b[5] for b in tr['blocks'] if B.pillar_of(b[1], b[3]))
    return B.crusher_bottoms(tr['blocks'], c.most_common(1)[0][0]) if c else None


def last_step(ser, n):
    return max([a for a, b in ser if a <= n and b is not None], default=None)


def maxor_hover(D):
    """Where Maxor stands still while free (not in his intro, not stunned)."""
    d3, dh, dy = [], [], []
    for g in D.server:
        if 'maxor' not in D.T[g]['bosses']:
            continue
        intro = D.ev(g, R.MAXOR_INTRO_END)
        stuns = D.all_ev(g, R.MAXOR_STUN)
        enr = D.all_ev(g, R.MAXOR_ENRAGED)
        pk = [p for p in D.packets(g, 'maxor', True) if p[6] == 'p']
        for a, b in zip(pk, pk[1:]):
            if b[0] - a[0] < 8 or B.dist(a[1:4], b[1:4]) > 0.3:
                continue
            n = a[0] + 4
            if intro is None or n < intro + 50:
                continue
            ls = [s for s in stuns if s <= n]
            if ls and not any(ls[-1] <= e <= n for e in enr):
                continue
            c = D.candidates(g, n)
            if not c:
                continue
            q = min(c.values(), key=lambda q: B.dist(q, a[1:4]))
            d3.append(B.dist(q, a[1:4]))
            dh.append(B.hdist(q, a[1:4]))
            dy.append(a[2] - q[1])
    print('Maxor standing still while free: 3D distance to the closest player %s; horizontal %s; his y - their y %s' % (stats(d3), stats(dh), stats(dy)))


def retarget(D, boss):
    """When the closest player switches to one >60 degrees away, how soon the heading follows."""
    delays = []
    for g in D.server:
        if boss not in D.T[g]['bosses']:
            continue
        pk = [p for p in D.packets(g, boss, True) if p[6] == 'p']
        if len(pk) < 20:
            continue
        ph = storm_phase(D, g) if boss == 'storm' else None
        intro = D.ev(g, R.MAXOR_INTRO_END)
        prev = None
        skip_until = -1
        for i in range(2, len(pk) - 6):
            n = pk[i][0]
            if n < skip_until:
                continue
            if boss == 'storm' and ph(g, n) not in ('chase', 'after crush 1, near Yellow or later'):
                prev = None
                continue
            if boss == 'maxor' and (intro is None or n < intro + 50):
                continue
            p = pk[i][1:4]
            c = D.candidates(g, n)
            if len(c) < 2:
                prev = None
                continue
            k = min(c, key=lambda k: B.dist(c[k], p))
            if prev and k != prev:
                qo, qn = c.get(prev), c[k]
                if qo and B.hdist(qn, p) > 6 and abs(B.angdiff(B.bearing(qo[0] - p[0], qo[2] - p[2]), B.bearing(qn[0] - p[0], qn[2] - p[2]))) > 60:
                    bn = B.bearing(qn[0] - p[0], qn[2] - p[2])
                    got = None
                    for j in range(i, min(len(pk) - 1, i + 20)):
                        if pk[j][0] - n > 25:
                            break
                        dx, dz = pk[j + 1][1] - pk[j][1], pk[j + 1][3] - pk[j][3]
                        if math.hypot(dx, dz) > 0.2 and abs(B.angdiff(B.bearing(dx, dz), bn)) < 25:
                            got = pk[j][0] - n
                            break
                    delays.append(got)
                    skip_until = n + 30
            prev = k
    d = [x for x in delays if x is not None]
    print('%s: closest player switched by >60 deg %d times; heading within 25 deg of the new one after %s ticks; never within 25 ticks: %d' % (
        boss, len(delays), counts(d, 8), len(delays) - len(d)))


def storm_extras(D):
    T = D.T
    # break free: first move after crush 1 vs the enrage line (in view for the whole pin)
    d = []
    for g in D.server:
        cr = D.all_ev(g, R.STORM_CRUSHED)
        en = D.ev(g, R.STORM_ENRAGED)
        if not cr or en is None or 'storm' not in T[g]['bosses']:
            continue
        pk = D.packets(g, 'storm')
        p0 = B.pos_at(pk, cr[0] + 4, max_gap=40)
        if not p0:
            continue
        mv = next((p for p in pk if p[0] > cr[0] + 1 and B.dist(p[1:4], p0) > 0.3), None)
        if mv and any(a <= cr[0] and mv[0] <= b for a, b, _ in T[g]['bosses']['storm']['spans']):
            d.append(mv[0] - en)
    print('first move after crush 1 minus the enrage line: %s (%s)' % (stats(d, 0), counts(d, 6)))
    # taunts
    fixed = {R.STORM_START, R.STORM_DEAD, R.STORM_ENRAGED, *R.STORM_CRUSHED, *R.STORM_LIGHTNING,
             '[BOSS] Storm: At least my son died by your hands.'}
    pool, first_c1, gaps = collections.Counter(), [], []
    for g in D.server:
        ss = D.ev(g, R.STORM_START)
        if ss is None:
            continue
        lines = sorted((v, m) for m, vs in T[g]['events'].items() for v in vs if m.startswith('[BOSS] Storm') and m not in fixed and v >= ss + 500)
        for v, m in lines:
            pool[m[14:]] += 1
        cr = D.all_ev(g, R.STORM_CRUSHED)
        if lines and cr:
            first_c1.append(lines[0][0] - cr[0])
        gaps += [b[0] - a[0] for a, b in zip(lines, lines[1:])]
    print('taunt pool after the lightning:', counts(pool, 12))
    print('   first taunt - crush 1: %s; gaps between taunts: %s' % (stats(first_c1, 0), counts(gaps, 6)))
    # repeated lightning
    rep = []
    for g in T:
        ls = D.all_ev(g, R.STORM_LIGHTNING)
        rep += [b - a for a, b in zip(ls, ls[1:])]
    print('lightning line repeated after: %s ticks' % sorted(rep))
    # short lightning phases and the recorder's Giga Lightning hits
    for g in D.server:
        li = D.ev(g, R.STORM_LIGHTNING)
        if li is None or 'storm' not in T[g]['bosses']:
            continue
        pk = D.packets(g, 'storm')
        dep = next((p[0] for p in pk if p[0] > li and B.dist(p[1:4], REST) > 0.25), None)
        if dep and dep - li < 130 and any(a <= dep - 12 and dep <= b for a, b, _ in T[g]['bosses']['storm']['spans']):
            hits = 0
            for rid in T[g]['recs']:
                x = D.X[rid]
                cl = B.Clock(x['st'])
                off = T[g]['offsets'][rid]['dn']
                hits += sum(1 for t, m in x['chat'] if li <= cl.n(t) + off <= li + 140 and 'Giga Lightning' in m)
            print('   short lightning phase: %s left %d ticks after the line, party %d, Giga Lightning hits on the recorder(s): %d' % (g, dep - li, len(T[g]['party']), hits))
    # where Storm stops heading for Yellow's point (closest player >30 deg off)
    bins = collections.defaultdict(lambda: [0, 0])
    for g in D.server:
        cr = D.all_ev(g, R.STORM_CRUSHED)
        if not cr or 'storm' not in T[g]['bosses']:
            continue
        pk = D.packets(g, 'storm')
        if storm_pillar_at(pk, cr[0] + 4) != 'purple':
            continue
        end = cr[1] if len(cr) > 1 else cr[0] + 300
        for i, p in enumerate(pk):
            if not (cr[0] + 3 < p[0] < min(end, cr[0] + 250)):
                continue
            v = B.velocity(pk, i)
            if not v or math.hypot(v[0], v[2]) < 0.15:
                continue
            c = D.candidates(g, p[0])
            if not c:
                continue
            q = min(c.values(), key=lambda q: B.dist(q, p[1:4]))
            by, bp = B.bearing(46 - p[1], 65 - p[3]), B.bearing(q[0] - p[1], q[2] - p[3])
            if abs(B.angdiff(by, bp)) < 30:
                continue
            k = min(int(math.hypot(p[1] - 46, p[3] - 65) // 2) * 2, 30)
            bins[k][0] += 1
            bins[k][1] += abs(B.angdiff(B.bearing(v[0], v[2]), by)) < 12
    print('after a Purple crush, share of samples heading at (46, 65) (closest player >30 deg away) by horizontal distance to it: ' +
          ', '.join('%d-%d: %.2f (n=%d)' % (k, k + 2, a / n, n) for k, (n, a) in sorted(bins.items()) if n >= 10))
    # re-crush while pinned: the check 20 ticks after a Purple crush
    rule = collections.Counter()
    for g in D.server:
        ss = D.ev(g, R.STORM_START)
        cr = D.all_ev(g, R.STORM_CRUSHED)
        if ss is None or not cr or 'storm' not in T[g]['bosses']:
            continue
        pk = D.packets(g, 'storm')
        if storm_pillar_at(pk, cr[0] + 4) != 'purple':
            continue
        chk = ss + 19 + 20 * round((cr[0] - ss - 19) / 20) + 20
        p = B.pos_at(pk, chk + 1, max_gap=30)
        cb = pillar_series(D, g)
        if not p or not cb:
            continue
        ser = cb['purple']
        bot, last = B.value_at(ser, chk), last_step(ser, chk)
        ok = B.in_crush_zone('purple', p[0], p[2]) and bot is not None and p[1] + B.HEAD >= bot and last is not None and chk - last <= 60
        rule[(ok, any(abs(x - chk) <= 3 for x in cr))] += 1
    print('check 20 ticks after a Purple crush: (crush rule satisfied, crushed): %s' % dict(rule))
    # a pillar stepping onto Storm pushes him down
    push = []
    for g in D.server:
        if 'storm' not in T[g]['bosses']:
            continue
        cb = pillar_series(D, g)
        if not cb:
            continue
        pk = D.packets(g, 'storm')
        for pil in ('purple', 'yellow'):
            ser = cb[pil]
            for (a, ya), (b, yb) in zip(ser, ser[1:]):
                if ya is None or yb is None or yb != ya - 1:
                    continue
                p0, p1 = B.pos_at(pk, b - 1, max_gap=4), B.pos_at(pk, b + 4, max_gap=4)
                if p0 and p1 and B.in_crush_zone(pil, p0[0], p0[2]) and p0[1] + 3.5 > yb and p0[1] > 169.5:
                    push.append((p0[1] + 3.5 - yb, p1[1] - p0[1]))
    print('pillar steps into Storm\'s hitbox (off the floor): %d; his y change over the next 5 ticks: %s; overlap %s' % (
        len(push), stats([b for a, b in push], 2), stats([a for a, b in push], 2)))
    # lag: transfer speed per server tick vs the recording's server/client tick ratio
    rows = []
    for g in D.server:
        cr = D.all_ev(g, R.STORM_CRUSHED)
        if not cr or 'storm' not in T[g]['bosses']:
            continue
        pk = [p for p in D.packets(g, 'storm', True) if p[6] == 'p']
        seg = [p for p in pk if cr[0] < p[0] < cr[0] + 140 and 62 <= p[1] <= 92 and 60 < p[3] < 70]
        if len(seg) < 12 or seg[-1][5] == seg[0][5]:
            continue
        dn, dt = seg[-1][0] - seg[0][0], seg[-1][5] - seg[0][5]
        rows.append(((seg[0][1] - seg[-1][1]) / dn, dn / dt, (seg[0][1] - seg[-1][1]) / dt))
    if len(rows) > 2:
        xs, ys = [r[1] for r in rows], [r[0] for r in rows]
        mx, my = statistics.mean(xs), statistics.mean(ys)
        den = math.sqrt(sum((a - mx) ** 2 for a in xs) * sum((b - my) ** 2 for b in ys)) or 1
        cor = sum((a - mx) * (b - my) for a, b in zip(xs, ys)) / den
        print('transfer x-speed per server tick %s; sd per server tick %.3f vs per client tick %.3f; correlation with server/client tick ratio %.2f' % (
            stats(ys, 3), statistics.pstdev(ys), statistics.pstdev([r[2] for r in rows]), cor))


def yellow_margin(D):
    """Storm's height at crush 2 on Yellow, and how the Yellow pad's timing decided the +100 check."""
    ys, heads = [], []
    tab = collections.Counter()
    for g in D.server:
        ss = D.ev(g, R.STORM_START)
        cr = D.all_ev(g, R.STORM_CRUSHED)
        dead = D.ev(g, R.STORM_DEAD)
        if ss is None or not cr or 'storm' not in D.T[g]['bosses']:
            continue
        pk = D.packets(g, 'storm')
        cb = pillar_series(D, g)
        if not cb or storm_pillar_at(pk, cr[0] + 4) != 'purple':
            continue
        ser = cb['yellow']
        if len(cr) > 1 and 98 <= cr[1] - cr[0] <= 102 and storm_pillar_at(pk, cr[1] + 4) == 'yellow':
            p = B.pos_at(pk, cr[1], max_gap=6)
            if p:
                ys.append(p[1])
                heads.append(p[1] + B.HEAD - B.value_at(ser, cr[1]))
        chk = ss + 19 + 20 * round((cr[0] - ss - 19) / 20) + 100
        p = B.pos_at(pk, chk + 1, max_gap=8)
        if not p or not B.in_crush_zone('yellow', p[0], p[2]) or dead is None:
            continue
        bot, last = B.value_at(ser, chk), last_step(ser, chk)
        if bot is None or last is None or chk - last > 60:
            continue
        enter = next((q[0] for q in pk if q[0] > cr[0] and B.in_crush_zone('yellow', q[1], q[3])), None)
        hit = any(abs(x - chk) <= 3 for x in cr) or abs(dead - chk) <= 6
        tab[('bottom <= 175' if bot <= 175 else 'bottom 176+', 'stepped after he arrived' if enter is not None and last >= enter else 'finished before he arrived', 'crushed' if hit else 'missed')] += 1
    print('Storm y at crush 2 on Yellow (+100): %s; head - Yellow bottom: %s' % (stats(ys, 2), stats(heads, 2)))
    print('Storm in Yellow\'s zone and Yellow armed at the +100 check:')
    for k, v in sorted(tab.items()):
        print('   %-14s %-27s %-8s %d' % (k[0], k[1], k[2], v))


def pads(D):
    """Pads from the recorders' own positions while a pillar stepped down, and pad timing."""
    where = collections.defaultdict(collections.Counter)
    for rid, x in D.X.items():
        ss = B.first_line(x, R.STORM_START)
        me = x['players'].get(x['self'], [])
        if ss is None or not me:
            continue
        cb = B.crusher_bottoms([[b[0], b[1], b[2], b[3], b[4], rid] for b in x['blocks']])
        rows = [[r[0], r[1], r[2], r[3], rid] for r in me]
        for pil, ser in cb.items():
            for (a, ya), (b, yb) in zip(ser, ser[1:]):
                if ya is None or yb is None or yb != ya - 1 or b - ss < 200 or yb > 187:
                    continue
                q = B.player_at(rows, b)
                if q and q[1] > 160:
                    where[pil][(math.floor(q[0]), math.floor(q[1]), math.floor(q[2]))] += 1
    for pil, c in where.items():
        print('recorder standing at while %s stepped down (arena only): %s' % (pil, counts(c, 6)))
    rows = []
    for g in D.server:
        ss = D.ev(g, R.STORM_START)
        cr = [c - ss for c in D.all_ev(g, R.STORM_CRUSHED)] if ss is not None else []
        cb = pillar_series(D, g)
        if len(cr) < 2 or len(D.T[g]['party']) < 5 or not cb:
            continue

        def steps(pil, a, b):
            return [(y - ss, yb) for (x, ya), (y, yb) in zip(cb[pil], cb[pil][1:]) if ya is not None and yb is not None and yb == ya - 1 and a <= y - ss <= b]
        p = steps('purple', 300, cr[0])
        y = [s for s in steps('yellow', cr[0] - 400, cr[1]) if s[0] > 320]
        if p and y:
            rows.append((cr[0] <= 701 and cr[1] - cr[0] <= 102, p[0][0], p[-1][0], p[-1][1], y[0][0], y[-1][0], y[-1][1]))
    for ok in (True, False):
        r = [x for x in rows if x[0] == ok]
        print('%s: Purple first step %s, last %s, bottom %s; Yellow first step %s, last %s, bottom %s' % (
            '699 and +100 runs' if ok else 'other runs', stats([x[1] for x in r], 0), stats([x[2] for x in r], 0), counts([x[3] for x in r], 3),
            stats([x[4] for x in r], 0), stats([x[5] for x in r], 0), counts([x[6] for x in r], 3)))


def correlations(D):
    def rank(v):
        # average ranks for ties
        order = sorted(range(len(v)), key=lambda i: v[i])
        r = [0.0] * len(v)
        k = 0
        while k < len(order):
            j = k
            while j + 1 < len(order) and v[order[j + 1]] == v[order[k]]:
                j += 1
            for m in range(k, j + 1):
                r[order[m]] = (k + j) / 2
            k = j + 1
        return r

    def spearman(a, b):
        ra, rb = rank(a), rank(b)
        ma, mb = statistics.mean(ra), statistics.mean(rb)
        den = math.sqrt(sum((x - ma) ** 2 for x in ra) * sum((y - mb) ** 2 for y in rb)) or 1
        return sum((x - ma) * (y - mb) for x, y in zip(ra, rb)) / den
    a, b, c, d = [], [], [], []
    for g in D.server:
        st = D.ev(g, R.MAXOR_STUN)
        me = [e for e in D.all_ev(g, R.MAXOR_ENRAGED) if st and e >= st - 5]
        cr = D.all_ev(g, R.STORM_CRUSHED)
        se = D.ev(g, R.STORM_ENRAGED)
        dead = D.ev(g, R.STORM_DEAD)
        if st and me and cr and se:
            a.append(me[0] - st)
            b.append(se - cr[0])
            if dead and len(cr) > 1:
                c.append(se - cr[0])
                d.append(dead - cr[1])
    if len(a) > 3:
        print('Spearman(Maxor stun length, Storm pin) = %.2f (n=%d); Spearman(Storm pin, death - crush 2) = %.2f (n=%d)' % (spearman(a, b), len(a), spearman(c, d), len(c)))
    # travel time vs crush-1 x
    xs, ys = [], []
    for g in D.server:
        cr = D.all_ev(g, R.STORM_CRUSHED)
        if not cr or 'storm' not in D.T[g]['bosses']:
            continue
        pk = D.packets(g, 'storm')
        p0 = B.pos_at(pk, cr[0] + 4, max_gap=40)
        if not p0 or storm_pillar_at(pk, cr[0] + 4) != 'purple':
            continue
        dep = next((p for p in pk if p[0] > cr[0] and B.dist(p[1:4], p0) > 0.3), None)
        if not dep or dep[0] - cr[0] > 40:
            continue
        arr = next((p for p in pk if p[0] > dep[0] and B.in_crush_zone('yellow', p[1], p[3])), None)
        sub = [p for p in pk if dep[0] <= p[0] <= (arr[0] if arr else dep[0])]
        if arr and max((q[0] - p[0] for p, q in zip(sub, sub[1:])), default=99) <= 6:
            xs.append(p0[0])
            ys.append(arr[0] - dep[0])
    if len(xs) > 3:
        mx, my = statistics.mean(xs), statistics.mean(ys)
        k = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
        rms = math.sqrt(statistics.mean([(y - (my + k * (x - mx))) ** 2 for x, y in zip(xs, ys)]))
        print('flight time vs crush-1 x: %+.2f ticks per block (rms %.1f), crush x %.1f-%.1f, n=%d' % (k, rms, min(xs), max(xs), len(xs)))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    D = Data(sys.argv[1])
    todo = sys.argv[2:] or ['data', 'maxor', 'storm', 'fastest']
    for s in todo:
        {'data': section_data, 'maxor': section_maxor, 'storm': section_storm, 'fastest': section_fastest}[s](D)


if __name__ == '__main__':
    main()
