"""Shared helpers for the Necron (F7 phase 4) analysis. Standard library only.

Reuses tools/boss-movement (recording reader, de-interpolation)."""
import bisect
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', '..', 'boss-movement'))
import recording as R  # noqa: E402
import bosslib as B  # noqa: E402

START = ("[BOSS] Necron: You went further than any human before, congratulations.",
         "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.")
DEAD = "[BOSS] Necron: All this, for nothing..."
MID = (54.0, 66.0, 76.0)


def start_line(x):
    """(t, n) of Necron's first line."""
    for t, n, m in x['chat']:
        if m in START:
            return t, n
    return None


def necron_ids(x):
    """Wither ids that are Necron: the wither whose name-tag stand (id + 1) says Necron, or a wither
    first seen at mid while Necron is up."""
    ids = set()
    st = start_line(x)
    for k, e in x['ents'].items():
        if e['type'] == 'minecraft:armor_stand' and 'Necron' in (e['name'] or ''):
            c = str(int(k) - 1)
            if c in x['ents'] and x['ents'][c]['type'] == 'minecraft:wither':
                ids.add(c)
    for k, e in x['ents'].items():
        if e['type'] != 'minecraft:wither' or not e['ev']:
            continue
        s = e['ev'][0]
        if s[2] == 's' and st and s[0] >= st[0] - 5 and math.dist(s[3:6], MID) < 0.01:
            ids.add(k)
    return ids


class Clock:
    """n(t) from a recording's `st` lines (the server tick of client tick t)."""

    def __init__(self, st):
        self.ts = [a for a, _ in st]
        self.ns = [b for _, b in st]

    def n(self, t):
        i = bisect.bisect_right(self.ts, t) - 1
        return self.ns[max(i, 0)] - (self.ts[0] - t if i < 0 else 0)


def track(x, eid):
    """De-interpolated server positions of entity eid: [(t, n, x, y, z, kind)] (kind 's' spawn,
    'p' a recovered packet position). Uses bosslib.delerp (3-step client lerp)."""
    ev = [[v[0], v[2], v[3], v[4], v[5]] for v in x['ents'][eid]['ev']]
    pts, _ = B.delerp(ev)
    ck = Clock(x['st']) if x['st'] else None
    return [(t, ck.n(t) if ck else None, a, b, c, k) for t, a, b, c, k in pts]


def dialogue(x, who='Necron'):
    """[(t, n, line)] of [BOSS] <who> lines."""
    p = '[BOSS] %s: ' % who
    return [(t, n, m[len(p):]) for t, n, m in x['chat'] if m.startswith(p)]


def load_extracts(out_dir):
    """{id: extract} for every recording with a Necron window, alpha-server runs left out."""
    X = {}
    d = os.path.join(out_dir, 'extract')
    for f in sorted(os.listdir(d)):
        if not f.endswith('.json'):
            continue
        x = json.load(open(os.path.join(d, f)))
        if 'skip' in x or x['id'] in R.ALPHA_RUNS:
            continue
        X[x['id']] = x
    return X


def excursions(x):
    """Necron's trips off mid: [(L, B, [(n, x, y, z)])], n relative to his first line (server
    ticks; client ticks for recordings without `st`). L = first packet more than 0.2 from mid,
    B = the packet that puts him back within 0.05 of it (None if he never came back)."""
    st = start_line(x)
    s0 = st[1] if st[1] is not None else st[0]
    nid = sorted(necron_ids(x))[0]
    out, cur = [], None
    for t, n, a, b, c, k in track(x, nid):
        n = (n if n is not None else t) - s0
        d = math.dist((a, b, c), MID)
        if cur is None and d > 0.2:
            cur = [n, None, [(n, a, b, c)]]
        elif cur is not None:
            if d < 0.05:
                cur[1] = n
                out.append(cur)
                cur = None
            else:
                cur[2].append((n, a, b, c))
    if cur:
        out.append(cur)
    return out


def pct(v, p):
    v = sorted(v)
    if not v:
        return None
    return v[min(len(v) - 1, int(p / 100 * len(v)))]


def summ(v):
    """'median (p10-p90, min-max, n)'."""
    v = [a for a in v if a is not None]
    if not v:
        return 'n/a'
    return '%s (p10-p90 %s-%s, range %s-%s, n=%d)' % (pct(v, 50), pct(v, 10), pct(v, 90), min(v), max(v), len(v))


def hist(v):
    c = {}
    for a in v:
        c[a] = c.get(a, 0) + 1
    return ' '.join('%s:%d' % (k, c[k]) for k in sorted(c, key=lambda q: (q is None, q)))
