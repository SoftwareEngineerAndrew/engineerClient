#!/usr/bin/env python3
"""The proposed sub splits of docs/mechanics/sub-splits.md, measured on Better PF recordings.

    python3 subsplits.py DATA_DIR [--cache DIR] [--jobs N] [--old-until ISO] [section ...]

DATA_DIR is laid out like the site's API (see tools/boss-movement/README.md): runs.json,
optional ids.txt, runs/<id>.gz (gzip or xz). Sections: data alpha watcher maxor storm
goldor necron fixes bands (default: all). --cache DIR keeps one small extract per recording (re-used while it is
newer than the recording). --old-until (default 2026-09-29T12:00:00Z, the end of the data the
mechanics reports used) splits "old" runs from the rest by upload time, for the old vs all
comparison. Standard library only; nothing is written to DATA_DIR.

Every boundary is detected from what a live client sees: chat lines, block changes, and the
boss wither's positions as recorded (de-interpolated as in tools/boss-movement/bosslib.py, i.e.
the packets the client received). Times are server ticks: `st` lines, or a chat line's own `n`
(the server tick on arrival) when the recorder wrote it. Only runs whose every recording has
server ticks are timed. Alpha-server runs (recording.ALPHA_RUNS, the Watcher report's suspect,
and any recording showing the alpha signature) are left out.
"""
import base64
import bisect
import collections
import json
import math
import os
import pickle
import re
import sys
from multiprocessing import Pool

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'boss-movement'))
import recording as R  # noqa: E402
import bosslib as B  # noqa: E402

OLD_UNTIL = '2026-09-29T12:00:00Z'
SUSPECT_ALPHA = {'20260928-204121-d4bd4083'}      # docs/mechanics/watcher.md: same server, alpha-only line
ALPHA_LINE = 'Ah, we meet again. As I foresaw'

# ---------------------------------------------------------------- chat lines
DOOR = 'The BLOOD DOOR has been opened!'
W = '[BOSS] The Watcher: '
W_HANDLE = W + "Let's see how you can handle this."
W_PROVEN = W + 'You have proven yourself. You may pass.'
REGULAR = {'Parasite', 'Freak', 'Revoker', 'Mr. Dead', 'Leech', 'Psycho', 'Ooze', 'Reaper', 'Putrid',
           'Cannibal', 'Mute', 'Frost', 'Vader', 'Tear', 'Flamer', 'Walker', 'Skull'}
MINI = {'Scarf', 'Bonzo', 'Livid', 'Spirit Bear'}
WATCHER_SKIN = '5662b6fb4b8b'
CHARGING = 'The Energy Laser is charging up!'
M_TOO_YOUNG = R.MAXOR_DEAD
STORM_TAUNTS_ADV = ('[BOSS] Storm: THAT WAS ONLY IN MY WAY!', R.STORM_FREE,
                    '[BOSS] Storm: This factory is too small for me!', R.STORM_PILLAR)
N_START = ("[BOSS] Necron: You went further than any human before, congratulations.",
           "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.")
N_ARGH = '[BOSS] Necron: ARGH!'
N_END = '[BOSS] Necron: All this, for nothing...'
G_START = R.GOLDOR_START
CORE_OPEN = 'The Core entrance is opening!'
KEEP = ('[BOSS]', DOOR, '⚠', CHARGING, CORE_OPEN, ' ☠ ', ' ❣ ')
EXTRACT_VERSION = 2

# ---------------------------------------------------------------- places
MAXOR_SPAWN = (73.0, 226.0, 53.0)
STORM_SPAWN = (103.0, 188.0, 53.0)
STORM_PARK = (102.375, 183.0, 52.375)
YELLOW_POINT = (46.0, 65.0)
NECRON_MID = (54.0, 66.0, 76.0)
BEACON = (73, 221, 73)
PYLONS = ((52.5, 41.5), (94.5, 41.5))
PILLARS = B.PILLARS
# Goldor's section doors (docs/mechanics/goldor.md, tools/boss-mechanics/goldor/doors.py): search
# boxes (x0, x1, y0, y1, z0, z1); each door's 228 barrier blocks turn to air in the tick its
# section ends (max(last completion, gate)).
DOOR_BOXES = {1: (90, 112, 110, 140, 118, 126), 2: (12, 22, 105, 140, 122, 142), 3: (0, 16, 110, 140, 45, 54)}


def in_core(x, y, z):
    """DungeonSplits.everyoneInCore's box."""
    return 39 <= x < 71 and y < 155.5 and 54 <= z < 118


def skin(tex):
    try:
        m = re.findall(r'texture/([0-9a-f]+)', base64.b64decode(tex).decode('utf-8', 'replace'))
        return m[0] if m else ''
    except Exception:
        return ''


# ================================================================ step 1: one recording

def extract(path):
    """The few things the sub splits need from one recording, every time as (n, t)."""
    lines = R.read_lines(path)
    st = [(l['t'], l['n']) for l in lines if l['k'] == 'st']
    has_st = len(st) > 10
    ts, ns = [a for a, _ in st], [b for _, b in st]

    def N(t):
        if not has_st:
            return t
        i = bisect.bisect_right(ts, t) - 1
        return ns[i] if i >= 0 else ns[0] - (ts[0] - t)

    pal, chat, times, blood = {}, [], [], None
    ents = {}          # wither / end crystal / giant / watcher-candidate zombies: id -> {type, ev}
    zskin = {}
    mobs = {}          # blood mob (player-shaped) name -> first sighting / pgone
    beacon, pillar_air = [], collections.Counter()
    door_air = collections.Counter()
    inside = {}                       # real player -> [(n, t, in the core box)] on change
    alpha_line = False
    for l in lines:
        k = l['k']
        if k == 'pal':
            pal[l['i']] = l['s']
            continue
        t = l.get('t')
        if k == 'chat':
            m = l['m']
            if ALPHA_LINE in m:
                alpha_line = True
            if m.startswith(KEEP):
                n = l['n'] if (has_st and l.get('n') is not None) else N(t)
                chat.append((n, t, m))
        elif k == 'time':
            times.append((t, l['ms']))
        elif k == 'rooms' and blood is None:
            for r in l['r']:
                if r[1] == 'BLOOD':
                    tl = r[5]
                    x0, z0 = -200 + 32 * min(a for a, b in tl), -200 + 32 * min(b for a, b in tl)
                    x1, z1 = -200 + 32 * max(a for a, b in tl) + 31, -200 + 32 * max(b for a, b in tl) + 31
                    blood = ((x0 + x1 + 1) / 2, (z0 + z1 + 1) / 2)
        elif k == 'spawn':
            if l['type'] in ('minecraft:wither', 'minecraft:end_crystal', 'minecraft:giant', 'minecraft:zombie'):
                e = ents.setdefault(l['id'], {'type': l['type'], 'name': l.get('name', ''), 'ev': []})
                e['ev'].append((t, 's', l['x'], l['y'], l['z']))
        elif k == 'e':
            for d in l['d']:
                e = ents.get(d[0])
                if e is not None:
                    e['ev'].append((t, 'e', d[1], d[2], d[3]))
        elif k == 'gone':
            e = ents.get(l['id'])
            if e is not None:
                e['ev'].append((t, 'g'))
        elif k == 'eq' and 'id' in l and l.get('headTex'):
            zskin.setdefault(l['id'], skin(l['headTex']))
        elif k == 'p':
            for d in l['d']:
                if not ((len(d) > 7 and d[7] == 2) or '#' in d[0]):
                    v = in_core(d[1], d[2], d[3])
                    h = inside.setdefault(d[0], [])
                    if not h or h[-1][2] != v:
                        h.append((N(t), t, v))
                    continue
                base = d[0].split('#')[0].strip()
                if (base in REGULAR or base in MINI) and d[0] not in mobs:
                    mobs[d[0]] = {'name': base, 't': t, 'pos': (d[1], d[2], d[3]), 'gone': None}
        elif k == 'pgone':
            m = mobs.get(l['name'])
            if m is not None and m['gone'] is None:
                m['gone'] = t
        elif k == 'block':
            x, y, z = l['x'], l['y'], l['z']
            s = pal.get(l['s'], '')
            if (x, y, z) == BEACON:
                beacon.append((N(t), t, s))
            if 105 <= y <= 140 and s.split('[')[0] == 'minecraft:air':
                for dn, (a0, a1, b0, b1, c0, c1) in DOOR_BOXES.items():
                    if a0 <= x <= a1 and b0 <= y <= b1 and c0 <= z <= c1:
                        door_air[(t, dn)] += 1
            elif 170 <= y <= 205 and s.split('[')[0] in ('minecraft:air', ''):
                for name, (a0, a1, c0, c1) in PILLARS.items():
                    if a0 <= x <= a1 and c0 <= z <= c1:
                        pillar_air[(t, name)] += 1
    # keep the withers, crystals, the Giant and the Watcher (a zombie with his head)
    keep = {}
    for i, e in ents.items():
        if e['type'] == 'minecraft:zombie' and not zskin.get(i, '').startswith(WATCHER_SKIN):
            continue
        if e['type'] == 'minecraft:giant' and e['name'] == 'Dinnerbone':
            continue
        keep[i] = {'type': e['type'], 'ev': [(N(v[0]),) + tuple(v) for v in e['ev']]}
    resets = sorted((N(t), t, p) for (t, p), c in pillar_air.items() if c >= 50)
    return {'has_st': has_st, 'chat': chat, 'times': times, 'blood': blood, 'ents': keep,
            'mobs': [dict(m, n=N(m['t']), ngone=N(m['gone']) if m['gone'] is not None else None) for m in mobs.values()],
            'beacon': beacon, 'resets': resets, 'alpha_line': alpha_line, 'version': EXTRACT_VERSION,
            'doors': sorted((N(t), t, dn, c) for (t, dn), c in door_air.items() if c >= 100), 'inside': inside,
            'st_first': st[0] if st else None}


def extract_cached(args):
    path, cache = args
    if cache:
        c = os.path.join(cache, os.path.basename(path)[:-3] + '.pkl')
        if os.path.exists(c) and os.path.getmtime(c) > os.path.getmtime(path):
            x = pickle.load(open(c, 'rb'))
            if x.get('version') == EXTRACT_VERSION:
                return x
    x = extract(path)
    if cache:
        pickle.dump(x, open(c, 'wb'))
    return x


# ================================================================ helpers

def first(chat, msgs, after=None, before=None):
    msgs = (msgs,) if isinstance(msgs, str) else msgs
    for n, t, m in chat:
        if m in msgs and (after is None or n >= after) and (before is None or n <= before):
            return n
    return None


def first_t(chat, msgs, after=None):
    msgs = (msgs,) if isinstance(msgs, str) else msgs
    for n, t, m in chat:
        if m in msgs and (after is None or n >= after):
            return n, t
    return None


def all_n(chat, msgs, after=None, before=None):
    msgs = (msgs,) if isinstance(msgs, str) else msgs
    return [n for n, t, m in chat if m in msgs and (after is None or n >= after) and (before is None or n <= before)]


def packets(e):
    """De-lerped packets [(n, x, y, z)] of one entity (the positions the server sent)."""
    ev = [(v[1],) + tuple(v[2:]) for v in e['ev']]
    nmap = {v[1]: v[0] for v in e['ev']}
    # newer recorders write positions to 1/100 block (the lerp test needs a looser tolerance)
    dec = 2
    for v in ev[:300]:
        if v[1] == 'e' and any(len(repr(c).split('.')[1]) == 3 for c in (v[2], v[4]) if '.' in repr(c)):
            dec = 3
            break
    pk, _ = B.delerp([list(v) for v in ev], dec)
    out = []
    for t, x, y, z, kind in pk:
        out.append((nmap.get(t, None), x, y, z, kind, t))
    # n for ticks that are not exactly an ev tick (shouldn't happen: packets sit on ev ticks)
    return [(n, x, y, z, kind) for n, x, y, z, kind, t in out if n is not None]


def raw(e):
    """The positions as the client drew them: [(n, x, y, z)], None where the entity left view.

    Recorders from 0.6.15 on write mobs over 32 blocks away on even ticks only, which breaks the
    de-interpolation (packets()), so the boundaries use these: a lerp starts on the tick its
    packet arrives, so the first tick a position changes is when the client learnt of the move."""
    out = []
    for v in e['ev']:
        if v[2] == 'g':
            out.append(None)
        else:
            if v[2] == 's':
                out.append(None)
            out.append((v[0], v[3], v[4], v[5]))
    return out


def withers_near(x, point, tol, after=None, before=None):
    out = []
    for i, e in x['ents'].items():
        if e['type'] != 'minecraft:wither':
            continue
        for v in e['ev']:
            if v[2] == 'g':
                continue
            if (after is None or v[0] >= after) and (before is None or v[0] <= before) and math.dist(v[3:6], point) <= tol:
                out.append(i)
                break
    return out


def ms_at(times, t):
    """Wall-clock ms at client tick t (time lines every 20 client ticks)."""
    if not times:
        return None
    i = bisect.bisect_right([a for a, _ in times], t) - 1
    if i < 0 or i + 1 >= len(times):
        return None
    (t0, m0), (t1, m1) = times[i], times[i + 1]
    return m0 + (m1 - m0) * (t - t0) / (t1 - t0) if t1 > t0 else m0


def pct(v, p):
    v = sorted(v)
    if not v:
        return None
    return v[min(len(v) - 1, max(0, int(round(p / 100 * (len(v) - 1)))))]


def med(v):
    v = sorted(v)
    if not v:
        return None
    k = len(v)
    return v[k // 2] if k % 2 else (v[k // 2 - 1] + v[k // 2]) / 2


def fmt(v):
    if v is None:
        return '-'
    if isinstance(v, float):
        return ('%.2f' % v).rstrip('0').rstrip('.')
    return str(v)


def stats(v):
    v = [a for a in v if a is not None]
    if not v:
        return 'n=0'
    return 'n=%3d  min %s  p10 %s  median %s  p90 %s  max %s' % (len(v), fmt(min(v)), fmt(pct(v, 10)), fmt(med(v)), fmt(pct(v, 90)), fmt(max(v)))


def hist(v, top=12):
    c = collections.Counter(v)
    return ' '.join('%s:%d' % (fmt(k), c[k]) for k in sorted(c, key=lambda k: -c[k])[:top])


# ================================================================ step 2: per recording, per boss

def watcher_events(x):
    ch = x['chat']
    D = first(ch, DOOR)
    if D is None:
        return None
    H = first(ch, W_HANDLE, after=D)
    P = first(ch, W_PROVEN, after=D)
    ev = {'D': D, 'H': H, 'P': P}
    # the Watcher's move: the first tick his position changes after he has hovered (two or more
    # recorded ticks at one spot, 6+ ticks long) within 2 blocks of the middle, from "handle this"
    # or from his arrival just after it. That is the packet's arrival: the departure on the server
    # is 1-3 ticks earlier.
    if x['blood'] and H is not None:
        cx, cz = x['blood']
        mid = (cx - 0.5, cz - 0.5)
        best = None
        for i, e in x['ents'].items():
            if e['type'] != 'minecraft:zombie':
                continue
            prev, s0_, s1_ = None, None, None
            for p in raw(e):
                if p is None:
                    prev = s0_ = s1_ = None
                    continue
                if p[0] < H - 300:
                    continue
                if prev is not None and math.dist(p[1:], prev[1:]) <= 0.02:
                    s1_ = p[0]
                    prev = p
                    continue
                if (prev is not None and s0_ is not None and s1_ is not None and s1_ > s0_ and p[0] >= H
                        and p[0] - s0_ >= 6 and s0_ <= H + 10 and math.hypot(prev[1] - mid[0], prev[3] - mid[1]) <= 2.0):
                    if best is None or p[0] < best:
                        best = p[0]
                    break
                if p[0] > H + 10 and prev is not None:
                    break           # he moved on after "handle this" without a hover seen: not this leg
                s0_, s1_ = p[0], None
                prev = p
        ev['move'] = best
    # blood mobs: first sighting falling over the middle (a fresh spawn), 19 = 17 + Giant + 1 mini-boss
    if x['blood']:
        cx, cz = x['blood']
        fresh, seen = {}, {}
        for m in x['mobs']:
            if m['n'] < D:
                continue
            a, b, c = m['pos']
            seen[m['name']] = min(seen.get(m['name'], 1e9), m['n'])
            if abs(a - cx) < 7 and abs(c - cz) < 7 and b > 70.5:
                fresh[m['name']] = min(fresh.get(m['name'], 1e9), m['n'])
        for i, e in x['ents'].items():
            if e['type'] != 'minecraft:giant':
                continue
            v = e['ev'][0]
            if v[0] < D or v[2] != 's':
                continue
            seen['Giant'] = min(seen.get('Giant', 1e9), v[0])
            if abs(v[3] - cx) < 7 and abs(v[5] - cz) < 7 and v[4] > 70.5:
                fresh['Giant'] = min(fresh.get('Giant', 1e9), v[0])
        ev['fresh'] = fresh
        ev['seen'] = seen
    return ev


def maxor_events(x):
    ch = x['chat']
    s0 = first(ch, R.MAXOR_START)
    if s0 is None:
        return None
    ev = {'s0': s0}
    ev['charging'] = first(ch, CHARGING, after=s0)
    stuns = all_n(ch, R.MAXOR_STUN, after=s0, before=s0 + 3000)
    ev['stun_lines'] = stuns
    ev['too_young'] = first(ch, M_TOO_YOUNG, after=s0)
    ev['storm'] = first(ch, R.STORM_START, after=s0)
    # the kill: beacon at (73, 221, 73) turns to bedrock
    k = [n for n, t, s in x['beacon'] if n >= s0 and 'bedrock' in s]
    ev['kill'] = k[0] if k else None
    # placed crystals (on the pylons, y 224.375) vanishing: hit + 42 (or the kill). A crystal also
    # "goes" when the recorder moves out of range, so a hit needs both pylons' crystals gone within
    # 2 ticks of each other and the top crystals respawning (hit + 41) a tick before.
    van, top = [], []
    for i, e in x['ents'].items():
        if e['type'] != 'minecraft:end_crystal':
            continue
        for v in e['ev']:
            if v[2] == 's' and abs(v[4] - 238.375) < 0.1 and v[0] >= s0 + 20:
                top.append((v[0], v[1]))
        sp = e['ev'][0]
        if sp[2] != 's' or abs(sp[4] - 224.375) > 0.1 or not any(abs(sp[3] - px) < 0.6 and abs(sp[5] - pz) < 0.6 for px, pz in PYLONS):
            continue
        g = [(v[0], v[1]) for v in e['ev'] if v[2] == 'g']
        if g and g[0][0] >= s0:
            van.append(g[0])
    van.sort()
    pairs = []
    for a, b in zip(van, van[1:]):
        if b[0] - a[0] <= 2 and (not pairs or a[0] - pairs[-1][0] > 5):
            pairs.append(a)
    top.sort()
    tp2 = []
    for a, b in zip(top, top[1:]):
        if b[0] - a[0] <= 1 and (not tp2 or a[0] - tp2[-1][0] > 5):    # both top crystals back
            tp2.append(a)
    vt = [a for a in pairs if any(-4 <= a[0] - tp[0] <= 2 for tp in tp2)]
    ev['vanish'] = [a[0] for a in pairs]
    ev['vanish_top'] = [a[0] for a in vt]
    ev['vanish_top_ms'] = [ms_at(x['times'], a[1]) for a in vt]
    ev['top'] = [a[0] for a in tp2]
    ev['top_ms'] = [ms_at(x['times'], a[1]) for a in tp2]
    # real time of the stun lines
    ev['stun_ms'] = [ms_at(x['times'], t) for n, t, m in ch if m in R.MAXOR_STUN and s0 <= n <= s0 + 3000]
    ev['times'] = x['times']
    ev['taunts'] = [n for n, t, m in ch if m.startswith('[BOSS] Maxor:') and n >= s0 and m not in R.MAXOR_STUN]
    return ev


def storm_events(x):
    ch = x['chat']
    t0 = first(ch, R.STORM_START)
    if t0 is None:
        return None
    ev = {'t0': t0}
    ev['lightning'] = first(ch, R.STORM_LIGHTNING, after=t0)
    crush = all_n(ch, R.STORM_CRUSHED, after=t0)
    # one line per crush (a sibling line within 5 ticks is the same)
    cr = []
    for n in crush:
        if not cr or n - cr[-1] > 5:
            cr.append(n)
    ev['crush'] = cr
    ev['enrage'] = first(ch, R.STORM_ENRAGED, after=t0)
    ev['dead'] = first(ch, R.STORM_DEAD, after=t0)
    ev['goldor'] = first(ch, R.GOLDOR_START, after=t0)
    ev['taunts_adv'] = [n for n, t, m in ch if m in STORM_TAUNTS_ADV and n >= t0]
    ev['slow'] = first(ch, R.STORM_FREE, after=t0)
    ev['resets'] = [(n, p) for n, t, p in x['resets'] if n >= t0]
    # Storm's wither: the one seen at his spawn or parking spot
    ids = withers_near(x, STORM_SPAWN, 1.5, t0 - 5, t0 + 30) + withers_near(x, STORM_PARK, 0.6, t0 + 400, t0 + 700)
    dep = arr = None
    if ids:
        tr = []
        for i in set(ids):
            tr += [p for p in raw(x['ents'][i]) if p is not None]
        tr.sort()
        prev = None
        for p in tr:
            if p[0] >= t0 + 500 and prev is not None and math.dist(prev[1:], STORM_PARK) < 0.15 and math.dist(p[1:], STORM_PARK) > 0.15:
                dep = p[0]
                break
            prev = p
        if ev['enrage'] is not None:
            for p in tr:
                if p[0] > ev['enrage'] and math.hypot(p[1] - YELLOW_POINT[0], p[3] - YELLOW_POINT[1]) <= 2.4:
                    arr = p[0]
                    break
    ev['dep'] = dep
    ev['arr'] = arr
    return ev


def necron_events(x):
    ch = x['chat']
    n0 = first(ch, N_START)
    if n0 is None:
        return None
    ev = {'n0': n0}
    ev['argh'] = all_n(ch, N_ARGH, after=n0)
    ev['end'] = first(ch, N_END, after=n0)
    ids = withers_near(x, NECRON_MID, 0.01, n0 - 5, n0 + 100)
    trips = []
    if ids:
        tr = []
        for i in set(ids):
            tr += [p for p in raw(x['ents'][i]) if p is not None]
        tr.sort()
        cur = None
        for n, a, b, c in tr:
            if n < n0:
                continue
            d = math.dist((a, b, c), NECRON_MID)
            if cur is None and d > 0.02:
                cur = [n, None]
            elif cur is not None and d < 0.01:
                cur[1] = max(cur[0] + 1, n - 2)   # the teleport's 3-tick lerp lands 2 ticks after its packet
                trips.append(cur)
                cur = None
        if cur:
            trips.append(cur)
    ev['trips'] = trips
    return ev


DEATH = re.compile(r"^ ☠ (\w+) (?:was killed by .*|was crushed|died.*|disconnected) and became a ghost\.$")
REVIVED = re.compile(r"^ ❣ (\w+) was revived by \w+!$|^ ☠ (\w+) reconnected\.$")


def goldor_events(x, party=None):
    """Section doors, the core opening, everyone in the core, Necron's first line; each as
    server tick plus wall-clock ms (the terminal sections are shown in real seconds)."""
    ch = x['chat']
    g0 = first_t(ch, G_START)
    if g0 is None:
        return None
    n0, t0 = g0
    ev = {'g0': n0, 'ms0': ms_at(x['times'], t0)}
    for dn in (1, 2, 3):
        d = [(n, t) for n, t, k, c in x['doors'] if k == dn and c >= 185 and n >= n0]
        src = 'barrier'
        if not d:
            d = [(n - 6, t - 6) for n, t, k, c in x['doors'] if k == dn and 150 <= c < 185 and n >= n0]
            src = 'portcullis-6'
        ev['door%d' % dn] = d[0][0] if d else None
        ev['door%d_ms' % dn] = ms_at(x['times'], d[0][1]) if d else None
        ev['door%d_src' % dn] = src if d else None
    c = first_t(ch, CORE_OPEN, after=n0)
    ev['core'] = c[0] if c else None
    ev['core_ms'] = ms_at(x['times'], c[1]) if c else None
    nl = first_t(ch, N_START, after=n0)
    ev['necron'] = nl[0] if nl else None
    # everyone in: the first tick from the core opening with every living party member's last
    # seen position inside DungeonSplits.everyoneInCore's box
    ev['in'] = None
    if c is not None and party:
        ghost = set()
        events = []
        for n, t, m in ch:
            if n0 <= n:
                mm = DEATH.match(m)
                if mm:
                    events.append((n, 'dead', mm.group(1)))
                mm = REVIVED.match(m)
                if mm:
                    events.append((n, 'alive', mm.group(1) or mm.group(2)))
        state = {}
        for name, h in x['inside'].items():
            for n, t, v in h:
                events.append((n, 'pos', name, v))
        events.sort(key=lambda e: e[0])
        seen = set()
        for e in events:
            if e[1] == 'dead':
                ghost.add(e[2])
            elif e[1] == 'alive':
                ghost.discard(e[2])
            else:
                state[e[2]] = e[3]
                seen.add(e[2])
            if e[0] >= c[0]:
                live = [p for p in party if p not in ghost]
                if live and all(state.get(p) for p in live):
                    ev['in'] = max(e[0], c[0])
                    break
    return ev


BOSSES = (('watcher', watcher_events, 'D'), ('maxor', maxor_events, 's0'), ('storm', storm_events, 't0'),
          ('goldor', goldor_events, 'g0'), ('necron', necron_events, 'n0'))


def rel(ev, anchor):
    """Shift every int time in ev to be relative to ev[anchor]."""
    a = ev[anchor]

    def f(v):
        if isinstance(v, bool) or v is None:
            return v
        if isinstance(v, (int, float)):
            return v - a
        if isinstance(v, list):
            return [f(u) for u in v]
        if isinstance(v, tuple):
            return tuple(f(u) for u in v)
        if isinstance(v, dict):
            return {k: f(u) for k, u in v.items()}
        return v
    out = {}
    for k, v in ev.items():
        if k in ('times', 'stun_ms', 'near', 'pk_after_enrage') or k.endswith('_ms') or k == 'ms0' or k.endswith('_src'):
            out[k] = v
        elif k == 'resets':
            out[k] = [(n - a, p) for n, p in v]
        else:
            out[k] = f(v)
    out['abs'] = a
    return out


# ================================================================ step 3: runs

def load(data_dir, cache, jobs):
    info = {r['id']: r for r in R.run_list(data_dir)}
    ids = [i for i in R.wanted_ids(data_dir) if os.path.exists(R.run_path(data_dir, i))]
    if cache:
        os.makedirs(cache, exist_ok=True)
    with Pool(jobs) as p:
        xs = p.map(extract_cached, [(R.run_path(data_dir, i), cache) for i in ids], chunksize=1)
    return info, dict(zip(ids, xs))


def alpha_signature(x):
    """Storm leaving his parking spot well before lightning + 139 (alpha: +98-113)."""
    s = storm_events(x)
    if not s or s['lightning'] is None or s['dep'] is None:
        return None
    return s['dep'] - s['lightning']


class Run:
    pass


def build_runs(info, xs, old_until):
    groups = collections.defaultdict(list)
    for i in xs:
        groups[info[i]['group']].append(i)
    runs = []
    excluded = {}
    for g, ids in sorted(groups.items()):
        r = Run()
        r.id = g
        r.ids = sorted(ids)
        r.party = max(len(info[i]['party']) for i in ids)
        r.old = min(info[i]['uploadedAt'] for i in ids) <= old_until
        r.timed = all(xs[i]['has_st'] for i in ids)
        r.ev = {}
        bad = [i for i in ids if i in R.ALPHA_RUNS or i in SUSPECT_ALPHA or xs[i]['alpha_line']]
        sig = [alpha_signature(xs[i]) for i in ids]
        if any(s is not None and 90 <= s <= 125 for s in sig):
            bad += [i for i, s in zip(ids, sig) if s is not None and 90 <= s <= 125]
        if bad:
            excluded[g] = (sorted(set(bad)), sig, [xs[i]['alpha_line'] for i in ids])
            continue
        for boss, fn, anchor in BOSSES:
            evs = []
            for i in r.ids:
                e = fn(xs[i], [m[0] for m in info[i]['party']]) if boss == 'goldor' else fn(xs[i])
                if e is not None and e[anchor] is not None:
                    e = rel(e, anchor)
                    e['rid'] = i
                    evs.append(e)
            r.ev[boss] = evs
        runs.append(r)
    return runs, excluded


def pick(evs, key, f=None):
    """A value from the run's recordings: the median of the ones that have it."""
    v = []
    for e in evs:
        a = e.get(key) if f is None else f(e)
        if a is not None:
            v.append(a)
    if not v:
        return None
    v.sort()
    return v[(len(v) - 1) // 2]


# ================================================================ step 4: the sub splits

def watcher_splits(r):
    evs = r.ev.get('watcher') or []
    if not evs:
        return None
    s = {}
    H = pick(evs, 'H')
    P = pick(evs, 'P')
    move = pick(evs, 'move')
    fresh, seen = {}, {}
    for e in evs:
        for k, v in (e.get('fresh') or {}).items():
            fresh[k] = min(fresh.get(k, 1e9), v)
        for k, v in (e.get('seen') or {}).items():
            seen[k] = min(seen.get(k, 1e9), v)
    reg = [k for k in fresh if k in REGULAR]
    last = None
    lastkind = None
    if len(reg) == 17 and 'Giant' in fresh and any(k in MINI for k in fresh):
        last, lastkind = max(fresh.values()), 'fresh'
    elif len(reg) == 17:
        # the Giant and the mini-boss come in the dialogue phase; the last mob is always a regular
        last, lastkind = max(fresh[k] for k in reg), 'fresh17'
    elif len([k for k in seen if k in REGULAR]) == 17 and 'Giant' in seen and any(k in MINI for k in seen):
        last, lastkind = max(seen.values()), 'seen'
    s['H'], s['P'], s['move'], s['last'], s['lastkind'] = H, P, move, last, lastkind
    s['Dialogue'] = H
    s['Wait'] = move - H if (move is not None and H is not None) else None
    s['move_abs'] = move
    ok = lastkind in ('fresh', 'fresh17')
    s['Camp'] = last - move if (ok and last is not None and move is not None) else None
    s['Clear'] = P - last if (ok and P is not None and last is not None) else None
    s['total'] = P - 2 if P is not None else None
    return s


def maxor_splits(r):
    evs = r.ev.get('maxor') or []
    if not evs:
        return None
    s = {}
    s['charging'] = pick(evs, 'charging')
    s['kill'] = pick(evs, 'kill')
    s['storm'] = pick(evs, 'storm')
    s['too_young'] = pick(evs, 'too_young')
    # hits. Every hit shows as the top crystals coming back at hit + 41 and (unless the kill came
    # first) the placed crystals vanishing at hit + 42. The stun line comes on the hit, or later
    # when an ability holds it (taunt + 62/82).
    lines = pick(evs, None, lambda e: tuple(e['stun_lines'][:3]) if e['stun_lines'] else None)
    lines = list(lines) if lines else []
    kill = s['kill']
    evid = []
    for e in evs:
        evid += [(v - 42, 'vanish') for v in e['vanish_top']]
        evid += [(v - 41, 'top') for v in e['top'] if kill is None or v - 41 <= kill + 2]
    evid.sort()
    hv = []          # clustered crystal evidence: [tick, kinds]
    for t, k in evid:
        if t < 150:
            continue
        if hv and t - hv[-1][0] <= 3:
            hv[-1][1].add(k)
        else:
            hv.append([t, {k}])
    hits, src = [], []
    used = set()
    prev = 150
    for j in range(2):
        ev_ = next((h for h in hv if h[0] > prev + 20), None) if j else next((h for h in hv if h[0] >= prev), None)
        ln = next((x_ for x_ in lines if x_ not in used and x_ > (prev + 20 if j else prev)), None)
        if ev_ is not None and ln is not None and abs(ln - ev_[0]) <= 3:
            hits.append(ln); src.append('line'); used.add(ln)
        elif ev_ is not None and (ln is None or ev_[0] < ln - 3):
            hits.append(ev_[0]); src.append('silent:' + ('vanish' if 'vanish' in ev_[1] else 'top'))
            if ln is not None and ln - ev_[0] < 120:
                used.add(ln)        # the held line of this hit
        elif ln is not None:
            hits.append(ln); src.append('line-only'); used.add(ln)
        else:
            hits.append(None); src.append(None)
        if hits[-1] is None:
            break
        prev = hits[-1]
    while len(hits) < 2:
        hits.append(None); src.append(None)
    s['hit1'], s['hit2'], s['src'] = hits[0], hits[1], src
    s['Crystals'] = s['charging']
    s['Lure'] = hits[0] - s['charging'] if (hits[0] is not None and s['charging'] is not None) else None
    s['Cooldown'] = hits[1] - hits[0] if (hits[0] is not None and hits[1] is not None) else None
    s['Kill'] = kill - hits[1] if (kill is not None and hits[1] is not None) else None
    s['Animation'] = s['storm'] - kill if (s['storm'] is not None and kill is not None) else None
    s['total'] = s['storm']
    # real seconds between the two hits, from the recorder's clock: a hit seen as its stun line
    # takes the line's time; a silent one the crystals' time less 42 / 41 ticks at 50 ms
    def hit_ms(e, h, sr):
        if sr in ('line', 'line-only'):
            for n, m in zip(e['stun_lines'], e['stun_ms']):
                if abs(n - h) <= 3:
                    return m
        elif sr == 'silent:vanish':
            for n, m in zip(e['vanish_top'], e['vanish_top_ms']):
                if abs(n - 42 - h) <= 3 and m is not None:
                    return m - 42 * 50
        elif sr == 'silent:top':
            for n, m in zip(e['top'], e['top_ms']):
                if abs(n - 41 - h) <= 3 and m is not None:
                    return m - 41 * 50
        return None
    s['Cooldown_s'] = None
    if hits[0] is not None and hits[1] is not None:
        v = []
        for e in evs:
            a_, b_ = hit_ms(e, hits[0], src[0]), hit_ms(e, hits[1], src[1])
            if a_ is not None and b_ is not None:
                v.append((b_ - a_) / 1000)
        if v:
            s['Cooldown_s'] = sorted(v)[(len(v) - 1) // 2]
    return s


def storm_splits(r):
    evs = r.ev.get('storm') or []
    if not evs:
        return None
    s = {}
    for k in ('lightning', 'enrage', 'dead', 'goldor', 'dep', 'arr'):
        s[k] = pick(evs, k)
    cr = pick(evs, None, lambda e: tuple(e['crush']) if e['crush'] else None)
    cr = list(cr) if cr else []
    s['crush1'] = cr[0] if cr else None
    s['crush2'] = cr[1] if len(cr) > 1 else None
    s['crush2_src'] = 'line' if s['crush2'] is not None else None
    # resets: (n, pillar); a reset is crush + 20
    rs = []
    for e in evs:
        rs += e['resets']
    rs.sort()
    s['resets'] = rs
    if s['crush2'] is None and s['crush1'] is not None and s['dead'] is not None:
        later = [n for n, p in rs if n > s['crush1'] + 25 and n <= s['dead'] + 25]
        if later:
            s['crush2'] = later[0] - 20
            s['crush2_src'] = 'reset'
    s['opening_line'] = s['lightning'] + 139 if s['lightning'] is not None else None
    s['Opening'] = s['opening_line'] if s['opening_line'] is not None else s['dep']
    s['Opening_src'] = 'lightning' if s['opening_line'] is not None else ('position' if s['dep'] is not None else None)
    s['Opening_pos'] = s['dep']
    s['Crush1_pos'] = s['crush1'] - s['dep'] if (s['crush1'] is not None and s['dep'] is not None) else None
    # the pillar that crushed: the one that resets on the next check
    s['pillar1'] = next((p for n, p in rs if s['crush1'] is not None and 15 <= n - s['crush1'] <= 25), None)
    s['Crush1'] = s['crush1'] - s['Opening'] if (s['crush1'] is not None and s['Opening'] is not None) else None
    s['check1'] = 699 + 20 * round((s['crush1'] - 699) / 20) if s['crush1'] is not None else None
    s['Pin'] = s['enrage'] - s['crush1'] if (s['enrage'] is not None and s['crush1'] is not None and s['enrage'] >= s['crush1'] - 2) else None
    purple = s['pillar1'] == 'purple'
    s['Flight'] = s['arr'] - s['enrage'] if (purple and s['arr'] is not None and s['enrage'] is not None) else None
    s['Crush2'] = s['crush2'] - s['arr'] if (purple and s['crush2'] is not None and s['arr'] is not None and s['Flight'] is not None) else None
    s['FlightCrush2'] = s['crush2'] - s['enrage'] if (s['crush2'] is not None and s['enrage'] is not None) else None
    s['Kill'] = s['dead'] - s['crush2'] if (s['dead'] is not None and s['crush2'] is not None) else None
    s['Animation'] = s['goldor'] - s['dead'] if (s['goldor'] is not None and s['dead'] is not None) else None
    s['total'] = s['goldor']
    s['taunts_adv'] = sorted(set(pick(evs, None, lambda e: tuple(e['taunts_adv'])) or ()))
    return s


def goldor_splits(r):
    evs = r.ev.get('goldor') or []
    if not evs:
        return None
    s = {}
    for k in ('door1', 'door2', 'door3', 'core', 'in', 'necron'):
        s[k] = pick(evs, k)
    marks = [('S1', None, 'door1'), ('S2', 'door1', 'door2'), ('S3', 'door2', 'door3'), ('S4', 'door3', 'core')]
    for name, a, b in marks:
        s[name] = (s[b] - (s[a] if a else 0)) if (s[b] is not None and (a is None or s[a] is not None)) else None

        def sec(e, a=a, b=b):
            ma = e['ms0'] if a is None else e.get(a + '_ms')
            mb = e.get(b + '_ms')
            return (mb - ma) / 1000 if (ma is not None and mb is not None) else None
        s[name + '_s'] = pick(evs, None, sec)
    s['Leaps'] = s['in'] - s['core'] if (s['in'] is not None and s['core'] is not None) else None
    s['Kill'] = s['necron'] - s['in'] if (s['necron'] is not None and s['in'] is not None) else None
    s['door_src'] = [pick(evs, None, lambda e, k=k: e.get(k)) for k in ('door1_src', 'door2_src', 'door3_src')]
    return s


def necron_splits(r):
    evs = r.ev.get('necron') or []
    if not evs:
        return None
    s = {}
    ar = pick(evs, None, lambda e: tuple(e['argh']) if e['argh'] else None)
    ar = list(ar) if ar else []
    s['argh1'] = ar[0] if ar else None
    s['argh2'] = ar[1] if len(ar) > 1 else None
    s['end'] = pick(evs, 'end')

    a1 = s['argh1']

    def trip(j, k):
        def f(e):
            if a1 is None:
                return None
            tr = [t for t in e['trips'] if (t[0] < a1 if j == 0 else t[0] > a1)]
            if not tr:
                return None
            t = tr[0]
            if j == 0 and (t[0] > 200 or (t[1] is not None and t[1] > a1)):
                return None     # he left mid out of view: not trip 1's start
            return t[k]
        return pick(evs, None, f)
    s['L1'], s['B1'], s['L2'], s['B2'] = trip(0, 0), trip(0, 1), trip(1, 0), trip(1, 1)
    if s['B2'] is not None and s['argh2'] is not None and s['B2'] > s['argh2']:
        s['B2'] = None
    g = lambda a, b: (s[b] - s[a]) if (s[a] is not None and s[b] is not None) else None  # noqa: E731
    s['Intro'] = s['L1']
    s['Trip1'] = g('L1', 'B1')
    s['Lock1'] = g('B1', 'argh1')
    s['Space'] = g('argh1', 'L2')
    s['Trip2'] = g('L2', 'B2')
    s['Lock2'] = g('B2', 'argh2')
    s['Animation'] = g('argh2', 'end')
    s['total'] = s['end']
    return s


# ================================================================ report

def table(name, runs, key, fn, lag_floor=None):
    """floor (min), fastest 10% (p10), median, p90 over 5-player timed runs, old vs all."""
    out = []
    for label, sel in (('old', [r for r in runs if r.old]), ('all', runs)):
        v = [fn(r).get(key) if fn(r) else None for r in sel]
        v = [a for a in v if a is not None]
        out.append('  %-14s %-4s %s' % (name, label, stats(v)))
    print('\n'.join(out))


def section_data(runs, excluded, info, xs):
    old = [r for r in runs if r.old]
    print('== data')
    print('recordings: %d (old %d, new %d)' % (len(xs), sum(1 for i in xs if info[i]['uploadedAt'] <= OLD_UNTIL_[0]),
                                                sum(1 for i in xs if info[i]['uploadedAt'] > OLD_UNTIL_[0])))
    print('runs (groups) kept: %d (old %d, new %d); excluded as alpha: %d' % (len(runs), len(old), len(runs) - len(old), len(excluded)))
    t5 = [r for r in runs if r.timed and r.party == 5]
    print('timed 5-player runs: %d (old %d, new %d)' % (len(t5), sum(r.old for r in t5), sum(not r.old for r in t5)))
    up = sorted(info[i]['uploadedAt'] for i in xs)
    print('uploads %s .. %s' % (up[0], up[-1]))


def section_alpha(runs, excluded, info, xs):
    print('== alpha check (Storm departure - lightning line; "Ah, we meet again" line)')
    for g, (bad, sig, al) in sorted(excluded.items()):
        new = all(info[i]['uploadedAt'] > OLD_UNTIL_[0] for i in bad)
        print('  excluded %s %s sig=%s alpha_line=%s %s' % (g, bad, sig, al, 'NEW' if new else 'old'))
    d = []
    for r in runs:
        for i in r.ids:
            s = alpha_signature(xs[i])
            if s is not None:
                d.append(s)
    print('  departure - lightning over kept recordings:', stats(d), '|', hist(d))


def five(runs):
    return [r for r in runs if r.timed and r.party == 5]


def section_watcher(runs):
    print('== Watcher (server ticks after "The BLOOD DOOR has been opened!" = D)')
    R5 = five(runs)
    S = {r.id: watcher_splits(r) for r in R5}
    have = [r for r in R5 if S[r.id] and S[r.id]['P'] is not None]
    print('5-player timed runs reaching "proven": %d (old %d)' % (len(have), sum(r.old for r in have)))
    for k in ('Dialogue', 'Wait', 'move_abs', 'Camp', 'Clear', 'total'):
        table(k, have, k, lambda r: S[r.id])
    print('  coverage: H %d, move %d, last spawn fresh %d / seen-only %d, of %d' % (
        sum(S[r.id]['H'] is not None for r in have), sum(S[r.id]['move'] is not None for r in have),
        sum(S[r.id]['lastkind'] in ('fresh', 'fresh17') for r in have), sum(S[r.id]['lastkind'] == 'seen' for r in have), len(have)))
    print('  last spawn: all 19 fresh %d, 17 regulars fresh %d' % (sum(S[r.id]['lastkind'] == 'fresh' for r in have), sum(S[r.id]['lastkind'] == 'fresh17' for r in have)))
    mv = [S[r.id]['move'] for r in have if S[r.id]['move'] is not None]
    print('  move tick mod 40:', hist([m % 40 for m in mv]))
    print('  move values:', hist(mv, 20))
    print('  move - H minimum:', min([S[r.id]['Wait'] for r in have if S[r.id]['Wait'] is not None] or [None]))
    print('  Clear by kind (fresh):', stats([S[r.id]['Clear'] for r in have if S[r.id]['lastkind'] == 'fresh']))
    print('  Camp fresh only:', stats([S[r.id]['Camp'] for r in have if S[r.id]['lastkind'] == 'fresh']))
    fast = sorted(have, key=lambda r: S[r.id]['total'])[:5]
    for r in fast:
        s = S[r.id]
        print('   fastest %s total %s H %s move %s last %s(%s) P %s' % (r.id, s['total'], s['H'], s['move'], s['last'], s['lastkind'], s['P']))
    return S


def section_maxor(runs):
    print('== Maxor (server ticks after his first line = s0)')
    R5 = five(runs)
    S = {r.id: maxor_splits(r) for r in R5}
    have = [r for r in R5 if S[r.id] and S[r.id]['storm'] is not None]
    print('5-player timed runs reaching Storm: %d (old %d)' % (len(have), sum(r.old for r in have)))
    for k in ('Crystals', 'Lure', 'hit1', 'Cooldown', 'Cooldown_s', 'Kill', 'Animation', 'total'):
        table(k, have, k, lambda r: S[r.id])
    print('  hit sources: hit1 %s; hit2 %s' % (collections.Counter(S[r.id]['src'][0] for r in have),
                                               collections.Counter(S[r.id]['src'][1] for r in have)))
    print('  kill (bedrock) seen: %d of %d' % (sum(S[r.id]['kill'] is not None for r in have), len(have)))
    lo = [r for r in have if S[r.id]['Kill'] is not None and S[r.id]['Kill'] < 0]
    print('  kill before the 2nd hit as detected (silent hit 2): %d' % len(lo))
    an = [S[r.id]['Animation'] for r in have if S[r.id]['Animation'] is not None]
    print('  Animation hist:', hist(an))
    cd = [S[r.id]['Cooldown'] for r in have if S[r.id]['Cooldown'] is not None]
    print('  Cooldown hist:', hist(cd, 20))
    print('  Crystals hist:', hist([S[r.id]['Crystals'] for r in have if S[r.id]['Crystals'] is not None], 15))
    print('  Lure hist:', hist([S[r.id]['Lure'] for r in have if S[r.id]['Lure'] is not None], 15))
    return S


def section_storm(runs):
    print('== Storm (server ticks after "Pathetic Maxor, just like expected." = t0)')
    R5 = five(runs)
    S = {r.id: storm_splits(r) for r in R5}
    have = [r for r in R5 if S[r.id] and S[r.id]['goldor'] is not None]
    print('5-player timed runs reaching Goldor: %d (old %d)' % (len(have), sum(r.old for r in have)))
    print('  crush 1 pillar:', collections.Counter(S[r.id]['pillar1'] for r in have))
    for k in ('Opening', 'Opening_pos', 'Crush1', 'Crush1_pos', 'Pin', 'Flight', 'Crush2', 'FlightCrush2', 'Kill', 'Animation', 'total'):
        table(k, have, k, lambda r: S[r.id])
    print('  Opening source:', collections.Counter(S[r.id]['Opening_src'] for r in have))
    dl = [S[r.id]['dep'] - S[r.id]['lightning'] for r in have if S[r.id]['dep'] is not None and S[r.id]['lightning'] is not None]
    print('  departure - lightning:', stats(dl), '|', hist(dl))
    print('  departure:', stats([S[r.id]['dep'] for r in have]), '|', hist([S[r.id]['dep'] for r in have if S[r.id]['dep'] is not None]))
    print('  lightning:', stats([S[r.id]['lightning'] for r in have]))
    print('  crush 1 check:', hist([S[r.id]['check1'] for r in have if S[r.id]['check1'] is not None]))
    print('  crush 2 source:', collections.Counter(S[r.id]['crush2_src'] for r in have))
    print('  arrival seen: %d of %d with an enrage' % (sum(S[r.id]['arr'] is not None for r in have), sum(S[r.id]['enrage'] is not None for r in have)))
    print('  Flight hist:', hist([S[r.id]['Flight'] for r in have if S[r.id]['Flight'] is not None], 20))
    print('  Crush2 (arrival -> crush 2) hist:', hist([S[r.id]['Crush2'] for r in have if S[r.id]['Crush2'] is not None], 20))
    return S


def section_necron(runs):
    print('== Necron (server ticks after his first line = n0)')
    R5 = five(runs)
    S = {r.id: necron_splits(r) for r in R5}
    have = [r for r in R5 if S[r.id] and S[r.id]['end'] is not None]
    print('5-player timed runs reaching "All this, for nothing...": %d (old %d)' % (len(have), sum(r.old for r in have)))
    for k in ('Intro', 'Trip1', 'B1', 'Lock1', 'argh1', 'Space', 'L2', 'Trip2', 'B2', 'Lock2', 'argh2', 'Animation', 'total'):
        table(k, have, k, lambda r: S[r.id])
    print('  coverage: L1 %d B1 %d L2 %d B2 %d of %d' % (tuple(sum(S[r.id][k] is not None for r in have) for k in ('L1', 'B1', 'L2', 'B2')) + (len(have),)))
    print('  total hist:', hist([S[r.id]['total'] for r in have], 20))
    print('  argh1 hist:', hist([S[r.id]['argh1'] for r in have if S[r.id]['argh1'] is not None]))
    print('  argh2 hist:', hist([S[r.id]['argh2'] for r in have if S[r.id]['argh2'] is not None]))
    # Lock = first grid tick (5 mod 20) >= B + 141
    ok = bad = 0
    for r in have:
        s = S[r.id]
        for b, a in ((s['B1'], s['argh1']), (s['B2'], s['argh2'])):
            if b is None or a is None:
                continue
            g = b + 141
            g += (5 - g) % 20
            if b is s['B1']:
                g = max(g, 330 - 5) if False else g
            pred = max(g, 330) if a == s['argh1'] else g
            if abs(a - pred) <= 2:
                ok += 1
            else:
                bad += 1
    print('  ARGH = first grid tick >= B + 141 (ARGH 1 >= 330), +-2: %d ok, %d off' % (ok, bad))
    return S


def section_fixes(runs, WS, MS, SS):
    print('== fixes')
    R5 = five(runs)
    allr = [r for r in runs if r.timed]
    # 1. Storm taunts before Storm's death
    for label, sel in (('5-player', R5), ('all timed', allr)):
        n_alive = n_taunt = 0
        for r in sel:
            s = storm_splits(r)
            if not s or s['dead'] is None:
                continue
            n_alive += 1
            if any(t < s['dead'] for t in s['taunts_adv']):
                n_taunt += 1
        print('1. %s: runs with one of the four taunts before Storm\'s death: %d of %d' % (label, n_taunt, n_alive))
    # 2. I'M TOO YOUNG vs the bedrock kill
    a = b = c = 0
    d = []
    for r in R5:
        s = maxor_splits(r)
        if not s or s['kill'] is None:
            continue
        a += 1
        if s['too_young'] is not None:
            b += 1
            d.append(s['too_young'] - s['kill'])
        else:
            c += 1
    print('2. 5-player runs with the bedrock kill: %d; "TOO YOUNG" said in %d (line - kill: %s), not said in %d' % (a, b, stats(d), c))
    st2 = []
    for r in R5:
        s = maxor_splits(r)
        if s and s['too_young'] is not None and s['hit2'] is not None and s['src'][1] in ('line', 'line-only'):
            st2.append(s['too_young'] - s['hit2'])
    print('   TOO YOUNG - 2nd stun line:', stats(st2), '|', hist(st2))
    # 3. lightning + 688 vs departure
    e = []
    for r in R5:
        s = storm_splits(r)
        if s and s['lightning'] is not None and s['dep'] is not None:
            e.append(s['dep'] - s['lightning'])
    print('3. departure - lightning line:', stats(e))
    # 4. "Slowing me down..." is not the break-free: where is it against the enrage?
    d, n_en = [], 0
    for r in allr:
        s = storm_splits(r)
        if not s or s['crush1'] is None or s['enrage'] is None:
            continue
        n_en += 1
        slow = pick(r.ev['storm'], None, lambda e: e.get('slow'))
        if slow is not None:
            d.append(slow - s['enrage'])
    print('4. runs with crush 1 and "Storm is enraged!": %d; "Slowing me down" said in %d, line - enrage: %s' % (n_en, len(d), stats(d)))
    # 5. no second crush line
    nl = []
    for r in allr:
        s = storm_splits(r)
        if not s or s['dead'] is None or s['crush1'] is None:
            continue
        nl.append(s['crush2_src'])
    print('5. runs with Storm\'s death and a crush 1: crush 2 from', collections.Counter(nl))


def section_goldor(runs):
    print('== Terminals and Goldor (from "Who dares trespass into my domain?" = g0)')
    R5 = five(runs)
    S = {r.id: goldor_splits(r) for r in R5}
    have = [r for r in R5 if S[r.id] and S[r.id]['core'] is not None]
    print('5-player timed runs with the core opening: %d (old %d)' % (len(have), sum(r.old for r in have)))
    for k in ('S1', 'S1_s', 'S2', 'S2_s', 'S3', 'S3_s', 'S4', 'S4_s', 'core', 'Leaps', 'Kill'):
        table(k, have, k, lambda r: S[r.id])
    print('  door source:', collections.Counter(tuple(S[r.id]['door_src']) for r in have))
    print('  coverage: doors %d, everyone in %d, Necron line %d of %d' % (
        sum(all(S[r.id][k] is not None for k in ('door1', 'door2', 'door3')) for r in have),
        sum(S[r.id]['in'] is not None for r in have), sum(S[r.id]['necron'] is not None for r in have), len(have)))
    return S


PCTS = (0, 5, 10, 25, 50, 75, 90, 100)


def band_row(name, v, unit):
    v = sorted(a for a in v if a is not None)
    if not v:
        return '| %s | %s | 0 |' % (name, unit) + ' - |' * len(PCTS)
    f = (lambda a: '%.2f' % a) if unit == 's' else (lambda a: '%d' % round(a))
    cells = [f(pct(v, q)) for q in PCTS]
    spread = pct(v, 90) - pct(v, 10)
    fixed = spread <= (0.25 if unit == 's' else 4)
    return '| %s | %s | %d | %s | %s |' % (name, unit, len(v), ' | '.join(cells), 'fixed (p10-p90 %s)' % f(spread) if fixed else '')


def grid_hist(v, base, step, first_said=None):
    out = collections.Counter()
    for a in v:
        if a is None:
            continue
        out[base + step * round((a - base) / step)] += 1
    return ', '.join('%s%s: %d' % (g, ' (said %d)' % first_said[1] if first_said and g == first_said[0] else '', out[g]) for g in sorted(out))


def section_bands(runs):
    """Percentiles of every sub split over all 5-player timed runs (alpha left out)."""
    R5 = five(runs)
    W = {r.id: watcher_splits(r) for r in R5}
    Mx = {r.id: maxor_splits(r) for r in R5}
    St = {r.id: storm_splits(r) for r in R5}
    Go = {r.id: goldor_splits(r) for r in R5}
    Ne = {r.id: necron_splits(r) for r in R5}

    def col(D, k):
        return [D[r.id].get(k) if D[r.id] else None for r in R5]
    rows = [('Watcher Dialogue (D -> handle)', col(W, 'Dialogue'), 't'),
            ('Watcher Wait', col(W, 'Wait'), 't'),
            ('Watcher Camp', col(W, 'Camp'), 't'),
            ('Watcher Clear', col(W, 'Clear'), 't'),
            ('Maxor Crystals', col(Mx, 'Crystals'), 't'),
            ('Maxor Lure', col(Mx, 'Lure'), 't'),
            ('Maxor Cooldown', col(Mx, 'Cooldown_s'), 's'),
            ('Maxor Cooldown (ticks)', col(Mx, 'Cooldown'), 't'),
            ('Maxor Kill', [a if a is None or a >= 0 else None for a in col(Mx, 'Kill')], 't'),
            ('Maxor Animation', col(Mx, 'Animation'), 't'),
            ('Storm Opening', col(St, 'Opening'), 't'),
            ('Storm Crush 1', col(St, 'Crush1'), 't'),
            ('Storm Pin', col(St, 'Pin'), 't'),
            ('Storm Flight', col(St, 'Flight'), 't'),
            ('Storm Crush 2', col(St, 'Crush2'), 't'),
            ('Storm Flight + Crush 2', col(St, 'FlightCrush2'), 't'),
            ('Storm Kill', col(St, 'Kill'), 't'),
            ('Storm Animation', col(St, 'Animation'), 't'),
            ('Terminals S1', col(Go, 'S1_s'), 's'),
            ('Terminals S2', col(Go, 'S2_s'), 's'),
            ('Terminals S3', col(Go, 'S3_s'), 's'),
            ('Terminals S4', col(Go, 'S4_s'), 's'),
            ('Goldor Leaps', col(Go, 'Leaps'), 't'),
            ('Goldor Kill', col(Go, 'Kill'), 't'),
            ('Necron Intro', col(Ne, 'Intro'), 't'),
            ('Necron Trip 1', col(Ne, 'Trip1'), 't'),
            ('Necron Lock 1', col(Ne, 'Lock1'), 't'),
            ('Necron Space', col(Ne, 'Space'), 't'),
            ('Necron Trip 2', col(Ne, 'Trip2'), 't'),
            ('Necron Lock 2', col(Ne, 'Lock2'), 't'),
            ('Necron Animation', col(Ne, 'Animation'), 't')]
    print('== bands: 5-player timed runs, all data (t = server ticks, s = real seconds)')
    print('| split | unit | n | min | p5 | p10 | p25 | p50 | p75 | p90 | max | spread |')
    print('|---|---|---|---|---|---|---|---|---|---|---|---|')
    for name, v, unit in rows:
        print(band_row(name, v, unit))
    print('grid steps reached:')
    print('  Storm crush 1 check (t after the first line):', grid_hist(col(St, 'crush1'), 699, 20))
    print('  Storm crush 2 check:', grid_hist(col(St, 'crush2'), 699, 20))
    print('  Storm crush 2 - crush 1:', grid_hist([St[r.id]['crush2'] - St[r.id]['crush1'] if St[r.id] and St[r.id]['crush2'] is not None and St[r.id]['crush1'] is not None else None for r in R5], 0, 20))
    print('  Necron ARGH 1 slot:', grid_hist(col(Ne, 'argh1'), 5, 20, (325, 330)))
    print('  Necron ARGH 2 slot:', grid_hist(col(Ne, 'argh2'), 5, 20))
    print('  Watcher move (D+, 20-tick steps):', grid_hist(col(W, 'move_abs'), 0, 20))


OLD_UNTIL_ = [OLD_UNTIL]


def main():
    args = sys.argv[1:]
    cache, jobs = None, max(1, (os.cpu_count() or 2))
    if '--cache' in args:
        i = args.index('--cache'); cache = args[i + 1]; del args[i:i + 2]
    if '--jobs' in args:
        i = args.index('--jobs'); jobs = int(args[i + 1]); del args[i:i + 2]
    if '--old-until' in args:
        i = args.index('--old-until'); OLD_UNTIL_[0] = args[i + 1]; del args[i:i + 2]
    if not args:
        sys.exit(__doc__)
    data_dir, sections = args[0], args[1:] or ['data', 'alpha', 'watcher', 'maxor', 'storm', 'goldor', 'necron', 'fixes', 'bands']
    info, xs = load(data_dir, cache, jobs)
    runs, excluded = build_runs(info, xs, OLD_UNTIL_[0])
    WS = MS = SS = None
    if 'data' in sections:
        section_data(runs, excluded, info, xs)
    if 'alpha' in sections:
        section_alpha(runs, excluded, info, xs)
    if 'watcher' in sections:
        WS = section_watcher(runs)
    if 'maxor' in sections:
        MS = section_maxor(runs)
    if 'storm' in sections:
        SS = section_storm(runs)
    if 'goldor' in sections:
        section_goldor(runs)
    if 'necron' in sections:
        section_necron(runs)
    if 'fixes' in sections:
        section_fixes(runs, WS, MS, SS)
    if 'bands' in sections:
        section_bands(runs)


if __name__ == '__main__':
    main()
