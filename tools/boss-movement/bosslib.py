"""Shared helpers for the Maxor/Storm movement analysis (standard library only).

Works on the per-recording extracts written by extract.py (OUT_DIR/extract/<id>.json).

Timelines
  t   client ticks of one recording (its own origin: that client's world load)
  n   server ticks as counted by that client (Odin's per-tick ping); `st` lines give n(t)
A group (several recordings of one run) is put on the reference recording's server-tick
timeline: each sibling gets an offset c so that n_ref = n_sib + c, measured from the chat lines
both recorded (the spread of those differences is reported as the alignment check).
"""
import bisect
import json
import math
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import recording as R  # noqa: E402

MAXOR_SPAWN = (73.0, 226.0, 53.0)
STORM_SPAWN = (103.0, 188.0, 53.0)


def load_extracts(out_dir):
    X = {}
    d = os.path.join(out_dir, 'extract')
    for f in sorted(os.listdir(d)):
        if not f.endswith('.json'):
            continue
        x = json.load(open(os.path.join(d, f)))
        if 'skip' in x:
            continue
        X[x['id']] = x
    return X


class Clock:
    """n(t) for one recording: the server tick count at the end of client tick t."""

    def __init__(self, st):
        self.ts = [a for a, _ in st]
        self.ns = [b for _, b in st]

    def n(self, t):
        if not self.ts:
            return t  # no server tick lines (older recorder): client ticks stand in
        i = bisect.bisect_right(self.ts, t) - 1
        if i < 0:
            return self.ns[0] - (self.ts[0] - t)  # before the first st line: assume no lag
        return self.ns[i]

    def t_of_n(self, n):
        """First client tick whose server count reached n."""
        i = bisect.bisect_left(self.ns, n)
        if i >= len(self.ns):
            return self.ts[-1] + (n - self.ns[-1]) if self.ts else None
        return self.ts[i]

    def lag_ratio(self, t0, t1):
        """Server ticks per client tick over [t0, t1]."""
        a, b = self.n(t0), self.n(t1)
        if a is None or b is None or t1 <= t0:
            return None
        return (b - a) / (t1 - t0)


def chat_events(x):
    """[(t, message, occurrence index of that message)] for [BOSS] and enrage lines."""
    seen = {}
    out = []
    for t, m in x['chat']:
        if m.startswith('[BOSS]') or 'enraged' in m:
            k = seen.get(m, 0)
            seen[m] = k + 1
            out.append((t, m, k))
    return out


def first_line(x, msgs, after=None):
    if isinstance(msgs, str):
        msgs = (msgs,)
    for t, m in x['chat']:
        if m in msgs and (after is None or t >= after):
            return t
    return None


def all_lines(x, msgs, after=None, before=None):
    if isinstance(msgs, str):
        msgs = (msgs,)
    return [t for t, m in x['chat'] if m in msgs and (after is None or t >= after) and (before is None or t <= before)]


def groups(X):
    g = {}
    for rid, x in X.items():
        g.setdefault(x['group'], []).append(x)
    return g


def align(ref, sib):
    """Offsets from sib to ref measured on shared chat lines: (client dt list, server dn list)."""
    cr, cs = Clock(ref['st']), Clock(sib['st'])
    a = {(m, k): t for t, m, k in chat_events(ref)}
    dts, dns = [], []
    for t, m, k in chat_events(sib):
        if (m, k) in a:
            tr = a[(m, k)]
            dts.append(tr - t)
            nr, ns = cr.n(tr), cs.n(t)
            if nr is not None and ns is not None:
                dns.append(nr - ns)
    return dts, dns


def boss_ids(x, name):
    """Wither ids this recording names as [name] ('Maxor'/'Storm') via the name tag (tag id - 1,
    or the tag line's 'of')."""
    ids = set()
    for k, e in x['ents'].items():
        if e['type'] == 'minecraft:armor_stand' and name in e['name']:
            cand = str(int(k) - 1)
            if cand in x['ents'] and x['ents'][cand]['type'] == 'minecraft:wither':
                ids.add(cand)
    for t, stand, of, dx, dy, dz in x['tags']:
        if of is not None and str(stand) in x['ents'] and name in x['ents'][str(stand)]['name']:
            ids.add(str(of))
    return ids


def spawn_ids(x, where, tol=0.6):
    """Withers first seen spawning exactly at [where] (the boss's fixed spawn point)."""
    out = set()
    for k, e in x['ents'].items():
        if e['type'] != 'minecraft:wither':
            continue
        for ev in e['ev']:
            if ev[1] == 's':
                if abs(ev[2] - where[0]) < tol and abs(ev[3] - where[1]) < tol and abs(ev[4] - where[2]) < tol:
                    out.add(k)
                break
    return out


def group_boss_id(members, name, spawn):
    ids = {}
    for x in members:
        for i in boss_ids(x, name) | spawn_ids(x, spawn):
            ids[i] = ids.get(i, 0) + 1
    if not ids:
        return None
    return max(ids, key=ids.get)


def dist(a, b):
    return math.sqrt(sum((p - q) ** 2 for p, q in zip(a, b)))


def hdist(a, b):
    return math.hypot(a[0] - b[0], a[2] - b[2])


def q32(v):
    return round(v * 32) / 32


def median(v):
    return statistics.median(v) if v else None


def pct(v, p):
    if not v:
        return None
    s = sorted(v)
    i = min(len(s) - 1, max(0, int(round(p / 100 * (len(s) - 1)))))
    return s[i]


def fmt(v, nd=3):
    return 'NA' if v is None else ('%.*f' % (nd, v))


# ---------------------------------------------------------------- de-interpolation
#
# The recorder writes the client's entity position at the end of each client tick. A modern
# client does not jump a mob to a new server position: each move packet starts a 3-step lerp
# (InterpolationHandler, 3 steps: pos += (target - pos) / stepsLeft each tick). Hypixel's server
# is 1.8, so the packet position is on a 1/32-block grid. Inverting the lerp recovers the packet
# position: on the tick a packet arrives, pos_t = pos_{t-1} + (T - pos_{t-1}) / 3, so
# T = pos_{t-1} + 3 (pos_t - pos_{t-1}). Ticks that follow the running lerp are not packets.
#
# Returns [(t, x, y, z, kind)] with kind 's' (spawn: the packet position itself) or 'p' (a
# recovered move packet); also the rms distance of the recovered x/z from the 1/32 grid.

def delerp(ev, decimals=3):
    tol = 0.0026 if decimals >= 3 else 0.011
    out = []
    grid_err = []
    segs = []
    for e in ev:
        if e[1] == 's':
            segs.append([e])
        elif e[1] == 'e' and segs:
            segs[-1].append(e)
        elif e[1] == 'g' and segs:
            segs[-1].append(e)
    for seg in segs:
        P = list(seg[0][2:5])
        T = P[:]
        s = 0
        out.append((seg[0][0], P[0], P[1], P[2], 's'))
        ob = {e[0]: e[2:5] for e in seg[1:] if e[1] == 'e'}
        if not ob:
            continue
        t = seg[0][0]
        tend = max(ob)
        while t < tend:
            t += 1
            pred = [p + (q - p) / s for p, q in zip(P, T)] if s > 0 else P[:]
            o = ob.get(t)
            if o is None:
                P = pred
                s = max(0, s - 1)
                continue
            if max(abs(a - b) for a, b in zip(o, pred)) <= tol:
                P = list(o)
                s = max(0, s - 1)
                continue
            Tn = [p + 3 * (a - p) for p, a in zip(P, o)]
            for v in (Tn[0], Tn[2]):
                grid_err.append(v * 32 - round(v * 32))
            out.append((t, Tn[0], Tn[1], Tn[2], 'p'))
            T = Tn
            P = list(o)
            s = 2
    rms = math.sqrt(sum(g * g for g in grid_err) / len(grid_err)) / 32 if grid_err else None
    return out, rms


def rec_decimals(x):
    """3 for recordings that wrote positions to 1/1000 block, 2 for the newer 1/100 ones."""
    n3 = n2 = 0
    for e in x['ents'].values():
        for ev in e['ev'][:200]:
            if ev[1] != 'e':
                continue
            for v in (ev[2], ev[4]):
                s = repr(v)
                d = len(s.split('.')[1]) if '.' in s else 0
                if d == 3:
                    n3 += 1
                elif d == 2:
                    n2 += 1
    return 3 if n3 > 0 else 2


def visible_spans(ev):
    """[(t_first, t_last)] spans between a spawn and its gone (the boss in the recorder's view)."""
    spans = []
    cur = None
    for e in ev:
        if e[1] == 's':
            if cur:
                spans.append(tuple(cur))
            cur = [e[0], e[0]]
        elif cur:
            cur[1] = e[0]
            if e[1] == 'g':
                spans.append(tuple(cur))
                cur = None
    if cur:
        spans.append(tuple(cur))
    return spans


# ---------------------------------------------------------------- Storm's crushers
#
# Two 7x7-circle (37 blocks a layer) polished_diorite pillars pushed down by pistons.
# Named as in docs/storm-crush.md (colour of the terracotta under each). x0, x1, z0, z1 (blocks,
# inclusive) of the 7x7 square; Red never moved in any recorded run.
PILLARS = {'purple': (97, 103, 62, 68), 'yellow': (43, 49, 62, 68), 'green': (43, 49, 38, 44), 'red': (97, 103, 38, 44)}
PILLAR_CENTRE = {k: ((v[0] + v[1] + 1) / 2, (v[2] + v[3] + 1) / 2) for k, v in PILLARS.items()}
# The crush check's zone (docs/storm-crush.md, StormCrush.kt): feet in [x0, x0 + 6] x [z0, z0 + 6],
# head (y + 2.975) at or above the pillar's lowest block, pillar stepped down within 60 ticks,
# on a check every 20 server ticks from Storm's wither appearing (1 tick before his first line).
HEAD = 2.975


def in_crush_zone(pillar, x, z):
    x0, _, z0, _ = PILLARS[pillar]
    return x0 <= x <= x0 + 6 and z0 <= z <= z0 + 6
SOLID = ('polished_diorite', 'moving_piston', 'piston', 'piston_head', 'diorite')


def pillar_of(x, z):
    for k, (x0, x1, z0, z1) in PILLARS.items():
        if x0 <= x <= x1 and z0 <= z <= z1:
            return k
    return None


def crusher_bottoms(blocks, rec=None):
    """{pillar: [(n, bottom_y)]} the lowest solid layer of each pillar (y 165-191, a layer counts
    once at least 20 of its 37 blocks are solid) after each change. Uses one recording's block
    lines (they are not deduplicated across recordings)."""
    cells = {k: {} for k in PILLARS}
    layers = {k: {} for k in PILLARS}
    out = {k: [] for k in PILLARS}
    for n, x, y, z, s, r in sorted(blocks, key=lambda b: b[0]):
        if rec is not None and r != rec:
            continue
        if not (165 <= y <= 191):
            continue
        k = pillar_of(x, z)
        if k is None:
            continue
        name = s.split('[')[0].split(':')[-1]
        solid = name in SOLID
        was = cells[k].get((x, y, z), False)
        cells[k][(x, y, z)] = solid
        if solid != was:
            layers[k][y] = layers[k].get(y, 0) + (1 if solid else -1)
        full = [yy for yy, c in layers[k].items() if c >= 20]
        b = min(full) if full else None
        if not out[k] or out[k][-1][1] != b:
            if out[k] and out[k][-1][0] == n:
                out[k][-1] = (n, b)
            else:
                out[k].append((n, b))
    return out


def value_at(series, n):
    v = None
    for a, b in series:
        if a <= n:
            v = b
        else:
            break
    return v


def dedupe_packets(pk, tol_n=2, tol_d=0.06):
    """Move packets seen by several recordings of the same run appear once per recording: keep
    the first of any that are within tol_n ticks and tol_d blocks of the one kept before."""
    out = []
    for p in sorted(pk, key=lambda r: r[0]):
        if out and abs(p[0] - out[-1][0]) <= tol_n and dist(p[1:4], out[-1][1:4]) < tol_d:
            continue
        out.append(p)
    return out


def pos_at(pk, n, max_gap=6):
    """Boss position at server tick n from packets (the last packet at or before n; None when
    the nearest packet is more than max_gap ticks away and he was not standing still)."""
    lo, hi = 0, len(pk) - 1
    if not pk or n < pk[0][0]:
        return None
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if pk[mid][0] <= n:
            lo = mid
        else:
            hi = mid - 1
    p = pk[lo]
    if n - p[0] <= max_gap:
        return p[1:4]
    if lo + 1 < len(pk) and dist(pk[lo + 1][1:4], p[1:4]) < 0.2:
        return p[1:4]  # next packet is at the same place: he stood still in between
    return None


# ---------------------------------------------------------------- players
DEATH = __import__('re').compile(r"^ ☠ (\w+) (?:was killed by .*|was crushed|died.*|disconnected) and became a ghost\.$")
DEATH_YOU = __import__('re').compile(r"^ ☠ You (?:were killed by .*|were crushed|died.*) and became a ghost\.$")
REVIVED = __import__('re').compile(r"^ ❣ (\w+) was revived by \w+!$|^ ☠ (\w+) reconnected\.$")


def ghost_spans(tr, members_self):
    """{name: [(n_from, n_to or None)]} when each player was a ghost (dead/disconnected), from the
    death and revive lines of the reference timeline. members_self maps recording id -> its
    player (for 'You ...' lines)."""
    # tr['chat_all'] is not kept; the tracks keep phase events only, so build from 'deaths'
    out = {}
    for n, who, what in tr.get('deaths', []):
        s = out.setdefault(who, [])
        if what == 'dead':
            if not s or s[-1][1] is not None:
                s.append([n, None])
        elif s and s[-1][1] is None:
            s[-1][1] = n
    return out


def alive(ghosts, name, n):
    for a, b in ghosts.get(name, []):
        if a <= n and (b is None or n < b):
            return False
    return True


def player_at(rows, n, max_age=40):
    """Last recorded position of a player at or before n (rows [[n, x, y, z, rec]])."""
    lo, hi = 0, len(rows) - 1
    if not rows or n < rows[0][0]:
        return None
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if rows[mid][0] <= n:
            lo = mid
        else:
            hi = mid - 1
    r = rows[lo]
    # a player keeps his entry until the next one, so an old entry is still where he is,
    # unless the recording lost him (pgone) - callers check that separately
    return r[1:4]


def velocity(pk, i, w=4):
    """Least-squares velocity (per server tick) over packets within +-w ticks of packet i."""
    n0 = pk[i][0]
    pts = [p for p in pk[max(0, i - 3 * w):i + 3 * w + 1] if abs(p[0] - n0) <= w]
    if len(pts) < 3:
        return None
    tm = sum(p[0] for p in pts) / len(pts)
    den = sum((p[0] - tm) ** 2 for p in pts)
    if den == 0:
        return None
    v = []
    for k in (1, 2, 3):
        m = sum(p[k] for p in pts) / len(pts)
        v.append(sum((p[0] - tm) * (p[k] - m) for p in pts) / den)
    return v


def bearing(dx, dz):
    """Minecraft yaw of a direction: 0 = +z (south), 90 = -x (west)."""
    return math.degrees(math.atan2(-dx, dz))


def angdiff(a, b):
    return (a - b + 180) % 360 - 180
