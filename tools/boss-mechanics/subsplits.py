#!/usr/bin/env python3
"""The proposed sub splits of docs/mechanics/sub-splits.md, measured on Better PF recordings.

    python3 subsplits.py DATA_DIR [--cache DIR] [--jobs N] [--old-until ISO] [section ...]

DATA_DIR is laid out like the site's API (see tools/boss-movement/README.md): runs.json,
optional ids.txt, runs/<id>.gz (gzip or xz). Sections: data alpha watcher maxor storm necron
fixes (default: all). --cache DIR keeps one small extract per recording (re-used while it is
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
KEEP = ('[BOSS]', DOOR, '⚠', CHARGING)

# ---------------------------------------------------------------- places
MAXOR_SPAWN = (73.0, 226.0, 53.0)
STORM_SPAWN = (103.0, 188.0, 53.0)
STORM_PARK = (102.375, 183.0, 52.375)
YELLOW_POINT = (46.0, 65.0)
NECRON_MID = (54.0, 66.0, 76.0)
BEACON = (73, 221, 73)
PYLONS = ((52.5, 41.5), (94.5, 41.5))
PILLARS = B.PILLARS


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
                if (len(d) > 7 and d[7] == 2) or '#' in d[0]:
                    base = d[0].split('#')[0].strip()
                    if base in REGULAR or base in MINI:
                        if d[0] not in mobs:
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
            'beacon': beacon, 'resets': resets, 'alpha_line': alpha_line,
            'st_first': st[0] if st else None}


def extract_cached(args):
    path, cache = args
    if cache:
        c = os.path.join(cache, os.path.basename(path)[:-3] + '.pkl')
        if os.path.exists(c) and os.path.getmtime(c) > os.path.getmtime(path):
            return pickle.load(open(c, 'rb'))
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
    pk, _ = B.delerp([list(v) for v in ev], 3)
    out = []
    for t, x, y, z, kind in pk:
        out.append((nmap.get(t, None), x, y, z, kind, t))
    # n for ticks that are not exactly an ev tick (shouldn't happen: packets sit on ev ticks)
    return [(n, x, y, z, kind) for n, x, y, z, kind, t in out if n is not None]


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
    # the Watcher's move: the first packet after "handle this" that takes him > 0.5 blocks
    # (horizontally) from the middle, once he is back in the middle
    if x['blood'] and H is not None:
        cx, cz = x['blood']
        mid = (cx - 0.5, cz - 0.5)
        best = None
        for i, e in x['ents'].items():
            if e['type'] != 'minecraft:zombie':
                continue
            pk = packets(e)
            home = False
            for n, a, b, c, kind in pk:
                if n < H - 80:
                    continue
                d = math.hypot(a - mid[0], c - mid[1])
                if d < 0.5:
                    home = True
                elif home and d > 0.5 and n >= H:
                    if best is None or n < best:
                        best = n
                    break
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
    # placed crystals (on the pylons, y 224.375) vanishing: hit + 42 (or the kill)
    van = []
    for i, e in x['ents'].items():
        if e['type'] != 'minecraft:end_crystal':
            continue
        sp = e['ev'][0]
        if sp[2] != 's' or abs(sp[4] - 224.375) > 0.1 or not any(abs(sp[3] - px) < 0.6 and abs(sp[5] - pz) < 0.6 for px, pz in PYLONS):
            continue
        g = [v[0] for v in e['ev'] if v[2] == 'g']
        if g and g[0] >= s0:
            van.append(g[0])
    ev['vanish'] = sorted(van)
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
    ev['resets'] = [(n, p) for n, t, p in x['resets'] if n >= t0]
    # Storm's wither: the one seen at his spawn or parking spot
    ids = withers_near(x, STORM_SPAWN, 1.5, t0 - 5, t0 + 30) + withers_near(x, STORM_PARK, 0.6, t0 + 400, t0 + 700)
    dep = arr = None
    if ids:
        pk = []
        for i in set(ids):
            pk += packets(x['ents'][i])
        pk.sort()
        parked = False
        for n, a, b, c, kind in pk:
            if n < t0 + 400:
                continue
            d = math.dist((a, b, c), STORM_PARK)
            if d < 0.3:
                parked = True
            elif parked and d > 0.3:
                dep = n
                break
        if ev['enrage'] is not None:
            for n, a, b, c, kind in pk:
                if n > ev['enrage'] and math.hypot(a - YELLOW_POINT[0], c - YELLOW_POINT[1]) <= 2.4:
                    arr = n
                    break
            ev['near'] = min([math.hypot(a - YELLOW_POINT[0], c - YELLOW_POINT[1]) for n, a, b, c, k in pk
                              if ev['enrage'] < n < ev['enrage'] + 300] or [None]) if pk else None
        ev['seen'] = [(pk[0][0], pk[-1][0])] if pk else []
        ev['pk_after_enrage'] = len([1 for p in pk if ev['enrage'] is not None and ev['enrage'] < p[0] < ev['enrage'] + 120])
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
        pk = []
        for i in set(ids):
            pk += packets(x['ents'][i])
        pk.sort()
        cur = None
        for n, a, b, c, kind in pk:
            if n < n0:
                continue
            d = math.dist((a, b, c), NECRON_MID)
            if cur is None and d > 0.2:
                cur = [n, None]
            elif cur is not None and d < 0.05:
                cur[1] = n
                trips.append(cur)
                cur = None
        if cur:
            trips.append(cur)
        ev['seen'] = (pk[0][0], pk[-1][0]) if pk else None
    ev['trips'] = trips
    return ev


BOSSES = (('watcher', watcher_events, 'D'), ('maxor', maxor_events, 's0'), ('storm', storm_events, 't0'),
          ('necron', necron_events, 'n0'))


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
        if k in ('times', 'stun_ms', 'near', 'pk_after_enrage'):
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
        if any(s is not None and s < 125 for s in sig):
            bad += [i for i, s in zip(ids, sig) if s is not None and s < 125]
        if bad:
            excluded[g] = (sorted(set(bad)), sig, [xs[i]['alpha_line'] for i in ids])
            continue
        for boss, fn, anchor in BOSSES:
            evs = []
            for i in r.ids:
                e = fn(xs[i])
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
    elif len([k for k in seen if k in REGULAR]) == 17 and 'Giant' in seen and any(k in MINI for k in seen):
        last, lastkind = max(seen.values()), 'seen'
    s['H'], s['P'], s['move'], s['last'], s['lastkind'] = H, P, move, last, lastkind
    s['Dialogue'] = H
    s['Wait'] = move - H if (move is not None and H is not None) else None
    s['move_abs'] = move
    s['Camp'] = last - move if (last is not None and move is not None) else None
    s['Clear'] = P - last if (P is not None and last is not None) else None
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
    # hits: each stun line, unless a pylon crystal vanished > 3 ticks earlier than line - 42 allows
    lines = pick(evs, None, lambda e: tuple(e['stun_lines'][:2]) if e['stun_lines'] else None)
    lines = list(lines) if lines else []
    van = pick(evs, None, lambda e: tuple(e['vanish']) if e['vanish'] else None)
    van = list(van) if van else []
    kill = s['kill']
    # a vanish at the kill (within 2 ticks) is the kill, not a hit
    hitv = sorted(set(v - 42 for v in van if kill is None or abs(v - kill) > 2))
    hits, src = [], []
    for j in range(2):
        ln = lines[j] if j < len(lines) else None
        cand = [h for h in hitv if (not hits or h > hits[-1] + 20)]
        hv = cand[0] if cand else None
        if ln is not None and (hits and ln <= hits[-1] + 20):
            ln = None
        if hv is not None and (ln is None or hv < ln - 3):
            if ln is not None and ln - hv > 120:      # vanish belongs to an earlier, unseen hit? keep the line
                hits.append(ln); src.append('line')
            else:
                hits.append(hv); src.append('vanish')
        elif ln is not None:
            hits.append(ln); src.append('line')
        else:
            hits.append(None); src.append(None)
    # second hit silent and the kill came first: the stun line is late; the hit is before the kill
    if hits[1] is not None and kill is not None and hits[1] > kill:
        src[1] = 'line-after-kill'
    s['hit1'], s['hit2'], s['src'] = hits[0], hits[1], src
    s['Crystals'] = s['charging']
    s['Lure'] = hits[0] - s['charging'] if (hits[0] is not None and s['charging'] is not None) else None
    s['Cooldown'] = hits[1] - hits[0] if (hits[0] is not None and hits[1] is not None) else None
    s['Kill'] = kill - hits[1] if (kill is not None and hits[1] is not None) else None
    s['Animation'] = s['storm'] - kill if (s['storm'] is not None and kill is not None) else None
    s['total'] = s['storm']
    # real seconds between the two hits, from the recorder's clock
    sec = []
    for e in evs:
        if not e['times'] or hits[0] is None or hits[1] is None:
            continue
        sec.append(None)
    s['Cooldown_s'] = None
    for e in evs:
        if hits[0] is None or hits[1] is None or not e['times']:
            continue
        # client tick of a server tick: invert this recording's chat (n, t) pairs is not stored;
        # use the stun lines' own real times when both hits are lines
        if src[0] == 'line' and src[1] == 'line' and len(e['stun_ms']) >= 2 and all(e['stun_ms'][:2]):
            s['Cooldown_s'] = (e['stun_ms'][1] - e['stun_ms'][0]) / 1000
            break
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
    s['Opening'] = s['dep'] if s['dep'] is not None else s['opening_line']
    s['Opening_src'] = 'position' if s['dep'] is not None else ('lightning' if s['lightning'] is not None else None)
    s['Crush1'] = s['crush1'] - s['Opening'] if (s['crush1'] is not None and s['Opening'] is not None) else None
    s['check1'] = 699 + 20 * round((s['crush1'] - 699) / 20) if s['crush1'] is not None else None
    s['Pin'] = s['enrage'] - s['crush1'] if (s['enrage'] is not None and s['crush1'] is not None and s['enrage'] >= s['crush1'] - 2) else None
    s['Flight'] = s['arr'] - s['enrage'] if (s['arr'] is not None and s['enrage'] is not None) else None
    s['Crush2'] = s['crush2'] - s['arr'] if (s['crush2'] is not None and s['arr'] is not None) else None
    s['FlightCrush2'] = s['crush2'] - s['enrage'] if (s['crush2'] is not None and s['enrage'] is not None) else None
    s['Kill'] = s['dead'] - s['crush2'] if (s['dead'] is not None and s['crush2'] is not None) else None
    s['Animation'] = s['goldor'] - s['dead'] if (s['goldor'] is not None and s['dead'] is not None) else None
    s['total'] = s['goldor']
    s['taunts_adv'] = sorted(set(pick(evs, None, lambda e: tuple(e['taunts_adv'])) or ()))
    s['pillar1'] = None
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

    def trip(j, k):
        return pick(evs, None, lambda e: e['trips'][j][k] if len(e['trips']) > j else None)
    s['L1'], s['B1'], s['L2'], s['B2'] = trip(0, 0), trip(0, 1), trip(1, 0), trip(1, 1)
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
        sum(S[r.id]['lastkind'] == 'fresh' for r in have), sum(S[r.id]['lastkind'] == 'seen' for r in have), len(have)))
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
    for k in ('Opening', 'Crush1', 'Pin', 'Flight', 'Crush2', 'FlightCrush2', 'Kill', 'Animation', 'total'):
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
    print('  coverage: L1 %d B1 %d L2 %d B2 %d of %d' % tuple(sum(S[r.id][k] is not None for r in have) for k in ('L1', 'B1', 'L2', 'B2')) + (len(have),))
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
        if s and s['too_young'] is not None and s['hit2'] is not None and s['src'][1] in ('line', 'line-after-kill'):
            st2.append(s['too_young'] - s['hit2'])
    print('   TOO YOUNG - 2nd stun line:', stats(st2), '|', hist(st2))
    # 3. lightning + 688 vs departure
    e = []
    for r in R5:
        s = storm_splits(r)
        if s and s['lightning'] is not None and s['dep'] is not None:
            e.append(s['dep'] - s['lightning'])
    print('3. departure - lightning line:', stats(e))
    # 4. "Slowing me down" vs enrage
    sl = []
    for r in allr:
        for ev in r.ev.get('storm', [])[:1]:
            pass
    for r in allr:
        s = storm_splits(r)
        if not s:
            continue
        evs = r.ev['storm']
    n_sl = n_en = 0
    for r in allr:
        s = storm_splits(r)
        if not s or s['crush1'] is None:
            continue
        n_en += s['enrage'] is not None
    print('4. timed runs with a crush 1 and an enrage line: %d' % n_en)
    # 5. no second crush line
    nl = []
    for r in allr:
        s = storm_splits(r)
        if not s or s['dead'] is None or s['crush1'] is None:
            continue
        nl.append(s['crush2_src'])
    print('5. runs with Storm\'s death and a crush 1: crush 2 from', collections.Counter(nl))


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
    data_dir, sections = args[0], args[1:] or ['data', 'alpha', 'watcher', 'maxor', 'storm', 'necron', 'fixes']
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
    if 'necron' in sections:
        section_necron(runs)
    if 'fixes' in sections:
        section_fixes(runs, WS, MS, SS)


if __name__ == '__main__':
    main()
