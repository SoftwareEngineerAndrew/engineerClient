"""Shared helpers for the Storm mechanics analysis (standard library only).

A "run" joins one boss-movement track (OUT_DIR/tracks/<group>.json: Storm's de-lerped packets, his
head yaw, every player's position, boss lines, deaths - see tools/boss-movement) with this tool's
own extracts (OUT_DIR/storm-mech/<id>.pkl: all chat, arm swings, held items, arena blocks).
Every time is in server ticks after Storm's first line ("Pathetic Maxor, just like expected.") on
the track's reference timeline. A crush check is t = 19 (mod 20).
"""
import bisect
import collections
import json
import math
import os
import pickle
import statistics
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'boss-movement'))
import bosslib as B  # noqa: E402
import recording as R  # noqa: E402

PILLARS = B.PILLARS                     # 7x7 squares (x0, x1, z0, z1), inclusive
POINT = {'yellow': (46.0, 65.0), 'green': (46.0, 41.0)}   # the flight target over a pillar
DEST = {'purple': 'yellow'}             # the only scripted flight (Yellow -> Green etc. are chases)
PADS = {'purple': (111, 91), 'yellow': (29, 91), 'green': (29, 9), 'red': (111, 9)}   # 7x7, -x/-z corner, top y 169
SOLID = ('polished_diorite', 'moving_piston', 'piston', 'piston_head', 'diorite')
BEAM_YAW, BEAM_PITCH, BEAM_RANGE = 3.0, 3.0, 45.0   # "on target" for a mage's beam


def med(v):
    return statistics.median(v) if v else None


def pct(v, p):
    return B.pct(v, p)


def spearman(a, b):
    def rank(v):
        o = sorted(range(len(v)), key=lambda i: v[i])
        r = [0.0] * len(v)
        i = 0
        while i < len(o):
            j = i
            while j + 1 < len(o) and v[o[j + 1]] == v[o[i]]:
                j += 1
            for k in range(i, j + 1):
                r[o[k]] = (i + j) / 2
            i = j + 1
        return r
    ra, rb = rank(a), rank(b)
    n = len(a)
    ma, mb = sum(ra) / n, sum(rb) / n
    den = math.sqrt(sum((x - ma) ** 2 for x in ra) * sum((y - mb) ** 2 for y in rb))
    return sum((x - ma) * (y - mb) for x, y in zip(ra, rb)) / den if den else float('nan')


# ------------------------------------------------------------------ loading

def build(out_dir):
    ext = {}
    d = os.path.join(out_dir, 'storm-mech')
    for f in os.listdir(d):
        if f.endswith('.pkl') and f != 'runs.pkl':
            o = pickle.load(open(os.path.join(d, f), 'rb'))
            if 'skip' not in o:
                ext[o['id']] = o
    runs = []
    td = os.path.join(out_dir, 'tracks')
    for f in sorted(os.listdir(td)):
        tr = json.load(open(os.path.join(td, f)))
        recs = [r for r in tr['recs'] if r in ext]
        ev = tr['events']
        if not recs or 'storm' not in tr['bosses'] or R.STORM_START not in ev:
            continue
        n0 = ev[R.STORM_START][0]
        clocks = {r: B.Clock([list(x) for x in ext[r]['st']]) for r in recs}

        def N(r, t):
            return clocks[r].n(t) + tr['offsets'][r]['dn'] - n0
        s = tr['bosses']['storm']
        run = {'group': tr['group'], 'recs': recs, 'self': {r: ext[r]['self'] for r in recs},
               'has_st': all(len(ext[r]['st']) > 10 for r in recs),
               'events': {m: [v - n0 for v in vs] for m, vs in ev.items()},
               'deaths': [[x[0] - n0, x[1], x[2]] for x in tr['deaths']],
               'pkt': [[p[0] - n0] + p[1:] for p in B.dedupe_packets(s['pkt'])],
               'obs': [[p[0] - n0] + p[1:] for p in s['obs']],
               'players': {k: [[q[0] - n0] + q[1:] for q in v] for k, v in tr['players'].items()},
               'classes': {}, 'chat': [], 'sw': [], 'p': {}, 'blocks': {}}
        for r in recs:
            x = ext[r]
            for _, m in x['party']:
                for nm, c in m:
                    run['classes'][nm] = c
            run['chat'] += [(N(r, t), m, r) for t, m in x['chat']]
            run['sw'] += [(N(r, t), who, r) for t, who in x['sw']]
            run['p'][r] = [(N(r, q[0]),) + tuple(q[1:]) for q in x['p']]
            run['blocks'][r] = [(N(r, b[0]),) + tuple(b[1:]) for b in x['blocks']]
        run['chat'].sort(key=lambda z: z[0])
        run['sw'].sort(key=lambda z: z[0])
        runs.append(run)
    return runs


def load(out_dir):
    cache = os.path.join(out_dir, 'storm-mech', 'runs.pkl')
    if os.path.exists(cache):
        return pickle.load(open(cache, 'rb'))
    runs = build(out_dir)
    pickle.dump(runs, open(cache, 'wb'))
    return runs


# ------------------------------------------------------------------ events

def ev(run, msgs, k=0):
    v = evs(run, msgs)
    return v[k] if len(v) > k else None


def evs(run, msgs):
    msgs = (msgs,) if isinstance(msgs, str) else msgs
    return sorted(x for m in msgs for x in run['events'].get(m, []))


def crushes(run):
    return evs(run, R.STORM_CRUSHED)


def grid_of(n):
    """The check tick (19 mod 20) at or before n."""
    return n - ((n + 1) % 20)


# ------------------------------------------------------------------ Storm and the players

def storm_pos(run, n, max_gap=6):
    return B.pos_at(run['pkt'], n, max_gap)


def headyaw_at(run, n):
    if '_hy' not in run:
        run['_hy'] = [(o[0], o[5]) for o in run['obs'] if o[5] is not None]
        run['_hyn'] = [x[0] for x in run['_hy']]
    i = bisect.bisect_right(run['_hyn'], n) - 1
    return run['_hy'][i][1] if i >= 0 and n - run['_hy'][i][0] < 10 else None


def living(run, n):
    """[(name, (x, y, z), is a recorder, age)] living players at n."""
    if '_gh' not in run:
        run['_gh'] = B.ghost_spans({'deaths': run['deaths']})
        run['_own'] = set(run['self'].values())
        run['_pn'] = {k: [q[0] for q in v] for k, v in run['players'].items()}
    out = []
    for nm, rows in run['players'].items():
        if not B.alive(run['_gh'], nm, n):
            continue
        i = bisect.bisect_right(run['_pn'][nm], n) - 1
        if i >= 0:
            q = rows[i]
            out.append((nm, tuple(q[1:4]), nm in run['_own'], n - q[0]))
    return out


def self_rows(run, rec):
    """The recorder's own entries [(n, name, x, y, z, yaw, pitch, held)] (exact)."""
    key = '_self_' + rec
    if key not in run:
        me = run['self'][rec]
        rows = [q for q in run['p'][rec] if q[1] == me]
        run[key] = (rows, [q[0] for q in rows])
    return run[key]


def self_at(run, rec, n):
    rows, ns = self_rows(run, rec)
    i = bisect.bisect_right(ns, n) - 1
    return rows[i] if i >= 0 else None


# ------------------------------------------------------------------ pillars

def bottoms(blocks):
    """{pillar: [(n, bottom)]}: the lowest layer (y 165-200) with >= 20 of its 37 blocks solid, after
    each change; None = fully drawn up."""
    cells = {k: {} for k in PILLARS}
    layers = {k: {} for k in PILLARS}
    out = {k: [] for k in PILLARS}
    for b in sorted(blocks, key=lambda b: b[0]):
        n, x, y, z, s = b[:5]
        if not 165 <= y <= 200:
            continue
        k = B.pillar_of(x, z)
        if k is None:
            continue
        solid = s.split('[')[0].split(':')[-1] in SOLID
        was = cells[k].get((x, y, z), False)
        cells[k][(x, y, z)] = solid
        if solid != was:
            layers[k][y] = layers[k].get(y, 0) + (1 if solid else -1)
        full = [yy for yy, c in layers[k].items() if c >= 20]
        bt = min(full) if full else None
        if not out[k] or out[k][-1][1] != bt:
            if out[k] and out[k][-1][0] == n:
                out[k][-1] = (n, bt)
            else:
                out[k].append((n, bt))
    return out


def run_bottoms(run):
    if '_bot' not in run:
        run['_bot'] = bottoms(max(run['blocks'].values(), key=len))
    return run['_bot']


def bottom_at(bt, p, n):
    v = [b for c, b in bt[p] if c <= n]
    return v[-1] if v else 175           # every pillar hangs at 175 until it first moves


def resets(bt, p):
    """Ticks at which the pillar's extended layers vanish at once (a jump up of >= 2 layers)."""
    s = bt[p]
    return [n for (a, b0), (n, b1) in zip(s, s[1:]) if b0 is not None and b0 < 189 and (b1 is None or b1 - b0 >= 2)
            and not (b1 is None and b0 >= 177)]


def batches(s):
    """Runs of one-layer down-steps no more than 6 ticks apart: [(first change n, from, to, steps)]."""
    s = [(n, 190 if b is None else b) for n, b in s]
    out = []
    i = 1
    while i < len(s):
        if s[i][1] - s[i - 1][1] == -1 and (i == 1 or s[i - 1][1] - s[i - 2][1] != -1 or s[i][0] - s[i - 1][0] > 6):
            j = i
            while j + 1 < len(s) and s[j + 1][1] - s[j][1] == -1 and s[j + 1][0] - s[j][0] <= 6:
                j += 1
            out.append((s[i][0], s[i - 1][1], s[j][1], j - i + 1))
            i = j + 1
        else:
            i += 1
    if s and s[0][1] == 174:              # the first step from 175 is the series' first entry
        j = 0
        while j + 1 < len(s) and s[j + 1][1] - s[j][1] == -1 and s[j + 1][0] - s[j][0] <= 6:
            j += 1
        out.insert(0, (s[0][0], 175, s[j][1], j + 1))
    return out


def pad_starts(bt, p):
    """Check ticks at which a descent began (its first layer seen 0-3 ticks after the check)."""
    return {grid_of(b[0]) for b in batches(bt[p]) if b[1] not in (189, 190) and (b[0] + 1) % 20 <= 3}


def pillar_state(bt, p, g):
    s = [(n, b) for n, b in bt[p] if n <= g]
    if not s:
        return 'init', 175
    n, b = s[-1]
    for (a, b0), (c, b1) in zip(s, s[1:]):
        if b0 is not None and b0 < 189 and b1 is not None and b1 - b0 >= 2:
            return 'spent', b
    if b is None:
        return 'retracted', b
    if g - n <= 5:
        return 'moving', b
    if b == 169:
        return 'floor', b
    if len(s) >= 2 and s[-2][1] is not None and b - s[-2][1] == 1:
        return 'retracting', b
    return 'armed', b


def last_down_step(bt, p, n):
    last = None
    s = bt[p]
    for (a, b0), (c, b1) in zip(s, s[1:]):
        if c > n:
            break
        if b0 is not None and b1 is not None and b1 < b0:
            last = c
    if s and s[0][0] <= n and s[0][1] == 174 and last is None:
        last = s[0][0]
    return last


def crush_pillar(run, n):
    """The pillar whose crush zone holds Storm a few ticks after a crush at n."""
    for dn in (3, 4, 5, 6, 2, 8, 10):
        p = storm_pos(run, n + dn)
        if p:
            for k in PILLARS:
                if B.in_crush_zone(k, p[0], p[2]):
                    return k
            k = min(PILLARS, key=lambda q: math.hypot(p[0] - B.PILLAR_CENTRE[q][0], p[2] - B.PILLAR_CENTRE[q][1]))
            if math.hypot(p[0] - B.PILLAR_CENTRE[k][0], p[2] - B.PILLAR_CENTRE[k][1]) < 6:
                return k
            break
    # Storm out of view: the pillar that resets at the next check is the one that crushed him
    bt = run_bottoms(run)
    for k in PILLARS:
        if any(15 <= r - n <= 25 for r in resets(bt, k)):
            return k
    return None


def rule_at(run, g, dn=3):
    """(pillar or None, why): whether docs/storm-crush.md's rule holds at check g."""
    bt = run_bottoms(run)
    P = storm_pos(run, g + dn, 10)
    if not P:
        return None, 'no position'
    for p in PILLARS:
        if not B.in_crush_zone(p, P[0], P[2]):
            continue
        bot = bottom_at(bt, p, g)
        if bot is None:
            return None, p + ' drawn up'
        ls = last_down_step(bt, p, g)
        head = P[1] + B.HEAD - bot
        ok = head >= 0 and ls is not None and g - ls <= 62
        return (p if ok else None), '%s head %+.2f, stepped %s ago' % (p, head, None if ls is None else g - ls)
    return None, 'outside every zone'


def on_pad(p, x, y, z):
    x0, z0 = PADS[p]
    return x0 <= x <= x0 + 7 and z0 <= z <= z0 + 7 and 169.9 <= y <= 170.6


# ------------------------------------------------------------------ phases after the lightning

def segments(run, arrive=3.0):
    """[(phase, n0, n1, info)]: chase (first), pinned, flight (Purple -> Yellow's point), later
    (from arriving within `arrive` blocks of that point, or straight after an enrage that has no
    scripted flight, until the next crush or death)."""
    L = ev(run, R.STORM_LIGHTNING)
    if L is None:
        return []
    cr = crushes(run)
    en = evs(run, R.STORM_ENRAGED)
    d = ev(run, R.STORM_DEAD)
    end = d if d is not None else L + 3000
    out = [('chase', L + 139, cr[0] if cr else end, {'k': 1})]
    for k, c in enumerate(cr):
        nxt = cr[k + 1] if k + 1 < len(cr) else end
        e = next((x for x in en if c <= x < nxt), None)
        free = min(nxt, e + 2) if e is not None else nxt
        out.append(('pinned', c, free, {'k': k + 1}))
        pil = crush_pillar(run, c)
        dest = DEST.get(pil)
        arr = free
        if dest:
            pt = POINT[dest]
            arr = next((q[0] for q in run['pkt'] if free <= q[0] < nxt and math.hypot(q[1] - pt[0], q[3] - pt[1]) < arrive), nxt)
            out.append(('flight', free, arr, {'k': k + 1, 'from': pil, 'to': dest}))
        if arr < nxt:
            out.append(('later', arr, nxt, {'k': k + 1, 'from': pil, 'at': dest}))
    return [s for s in out if s[2] > s[1]]


# ------------------------------------------------------------------ the mage's beams

def look_error(q, P):
    """(|yaw error|, pitch error) of a player entry q (n, name, x, y, z, yaw, pitch, ...) at Storm P
    (aiming at his body's middle, 1.5 above his feet)."""
    dx, dy, dz = P[0] - q[2], P[1] + 1.5 - (q[3] + 1.62), P[2] - q[4]
    return abs(B.angdiff(q[5], B.bearing(dx, dz))), q[6] + math.degrees(math.atan2(dy, math.hypot(dx, dz)))


def mage_beams(run, a, b):
    """{rec: [(n, on_target)]} arm swings of recorders playing Mage in [a, b]."""
    out = {}
    for rec in run['recs']:
        me = run['self'][rec]
        if run['classes'].get(me) != 'MAGE':
            continue
        v = []
        for n, who, r in run['sw']:
            if r != rec or me not in who or not a <= n <= b:
                continue
            P = storm_pos(run, n, 60)      # no packets arrive while he is pinned
            q = self_at(run, rec, n)
            if not P or not q:
                continue
            ye, pe = look_error(q, P)
            v.append((n, ye <= BEAM_YAW and abs(pe) <= BEAM_PITCH and math.dist(q[2:5], P) <= BEAM_RANGE))
        out[rec] = v
    return out
