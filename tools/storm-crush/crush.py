#!/usr/bin/env python3
"""Storm's crush check, measured from Better PF recordings (see docs/storm-crush.md).

    python3 crush.py fetch   DATA   # download every F7 run long enough to reach Storm into DATA
    python3 crush.py analyze DATA   # extract Storm's phase from each and print the tables

DATA gets runs.json (the site's run list) and runs/<id>.gz (each recording as the site serves it:
gzip or xz, told apart by their first bytes). Standard library only. The extraction is cached in
DATA/storm.pkl; delete it after adding runs.
"""
import bisect, collections, gzip, json, lzma, math, os, pickle, subprocess, sys
from multiprocessing import Pool

SITE = 'https://undonecoffee.com/betterpf/api'
START = '[BOSS] Storm: Pathetic Maxor, just like expected.'
LIGHTNING = {'[BOSS] Storm: ENERGY HEED MY CALL!', '[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!'}
CRUSHED = {'[BOSS] Storm: Oof', '[BOSS] Storm: Ouch, that hurt!'}
DEAD = '[BOSS] Storm: I should have known that I stood no chance.'
GOLDOR = '[BOSS] Goldor: Who dares trespass into my domain?'
HEAD = 2.975                      # a wither's eye height
# Each crusher's 7x7 square, by its -x/-z corner. Red never moved in the recorded runs.
PILLARS = {'Purple': (97, 62), 'Yellow': (43, 62), 'Green': (43, 38), 'Red': (97, 38)}
SOLID = ('minecraft:polished_diorite', 'minecraft:moving_piston', 'minecraft:piston')
# Recorded on Hypixel's alpha server (Storm leaves ~40 ticks early after his lightning): not the
# live game, so left out of the analysis.
ALPHA_RUNS = {'20260927-170801-b120f4c1', '20260928-214732-33c77ab5', '20260929-024353-1f5e9ba7', '20260929-025227-24f19367'}


# ------------------------------------------------------------------ fetch

def curl(url, out=None):
    args = ['curl', '-sS', '--retry', '3', '--retry-delay', '5', url] + (['-o', out] if out else [])
    return subprocess.run(args, check=True, capture_output=out is None).stdout

def fetch(data):
    os.makedirs(os.path.join(data, 'runs'), exist_ok=True)
    runs = json.loads(curl(SITE + '/runs'))
    json.dump(runs, open(os.path.join(data, 'runs.json'), 'w'))
    want = [r['id'] for r in runs if r['floor'] in ('F7', 'M7') and (r['ticks'] or 0) >= 3000 and r['id'] not in ALPHA_RUNS]
    todo = [i for i in want if not os.path.exists(os.path.join(data, 'runs', i + '.gz'))]
    print(len(want), 'runs reach the boss,', len(todo), 'to download')
    with Pool(4) as p:   # the site limits requests per address
        p.starmap(curl, [(f'{SITE}/runs/{i}', os.path.join(data, 'runs', i + '.gz')) for i in todo])


# ------------------------------------------------------------------ extract

def lines(path):
    raw = open(path, 'rb').read()
    text = (lzma.decompress(raw) if raw[:3] == b'\xfd7z' else gzip.decompress(raw)).decode('utf-8')
    return [json.loads(l) for l in text.splitlines() if l.strip()]

def extract(path):
    """Storm's phase in one recording: chat, server ticks, arena block changes, withers."""
    L = lines(path)
    pal = {o['i']: o['s'] for o in L if o['k'] == 'pal'}
    start = next((o['t'] for o in L if o['k'] == 'chat' and o['m'] == START), None)
    if start is None: return None
    end = next((o['t'] for o in L if o['k'] == 'chat' and o['t'] >= start and o['m'] == GOLDOR), start + 4000)
    out = {'self': L[0].get('self'), 'chat': [], 'st': [], 'blocks': [], 'withers': {}}
    n = None   # the server tick count as of each line (the last "st" before it)
    for o in L:
        k, t = o['k'], o.get('t')
        if k == 'st': out['st'].append((t, o['n'])); n = o['n']; continue
        if t is None or t < start - 60 or t > end + 20: continue
        if k == 'chat' and o['m'].startswith('[BOSS]'): out['chat'].append((t, o['m'], n))
        elif k == 'block' and 169 <= o['y'] <= 205 and 25 <= o['x'] <= 120 and 20 <= o['z'] <= 110:
            out['blocks'].append((t, o['x'], o['y'], o['z'], pal.get(o['s'], '?'), n))
        elif k == 'spawn' and o['type'] == 'minecraft:wither':
            out['withers'].setdefault(o['id'], []).append((t, o['x'], o['y'], o['z'], n))
        elif k == 'e':
            for d in o['d']:
                if d[0] in out['withers']: out['withers'][d[0]].append((t, d[1], d[2], d[3], n))
    return out

def safe_extract(path):
    try: return os.path.basename(path)[:-3], extract(path)
    except Exception as e: print('skipped', path, e); return None, None

def load(data):
    cache = os.path.join(data, 'storm.pkl')
    if os.path.exists(cache): return pickle.load(open(cache, 'rb'))
    paths = sorted(os.path.join(data, 'runs', f) for f in os.listdir(os.path.join(data, 'runs')) if f.endswith('.gz') and f[:-3] not in ALPHA_RUNS)
    with Pool(3) as p: res = {i: r for i, r in p.map(safe_extract, paths) if r}
    pickle.dump(res, open(cache, 'wb'))
    return res


# ------------------------------------------------------------------ measure

def storm_track(r):
    """The Storm wither's samples [(t, x, y, z, n)]: the arena wither seen most."""
    tracks = [[s for s in tr if s[2] > 150] for tr in r['withers'].values()]
    return max(tracks, key=len, default=[])

def pillar_steps(r):
    """{pillar: [(t, n, bottom, stepped)]} from its piston column (the square's centre)."""
    state = {p: {} for p in PILLARS}
    out = {p: [] for p in PILLARS}
    by_tick = collections.defaultdict(list)
    for b in r['blocks']: by_tick[b[0]].append(b)
    for t in sorted(by_tick):
        touched = {}
        for (_, x, y, z, s, n) in by_tick[t]:
            for p, (px, pz) in PILLARS.items():
                if (x, z) == (px + 3, pz + 3):
                    state[p][y] = s.split('[')[0] in SOLID; touched[p] = n
        for p, n in touched.items():
            solid = [y for y, v in state[p].items() if v]
            bottom = min(solid) if solid else None
            prev = out[p][-1][2] if out[p] else None
            out[p].append((t, n, bottom, prev is not None and bottom is not None and bottom < prev))
    return out

def at(track, t):
    i = bisect.bisect_right([s[0] for s in track], t) - 1
    return track[i] if i >= 0 else None

def inset(p, x, z):
    px, pz = PILLARS[p]
    return min(x - px, px + 6 - x, z - pz, pz + 6 - z)

def analyze(data):
    R = {k: v for k, v in load(data).items() if k not in ALPHA_RUNS}
    runs = {r['id']: r for r in json.load(open(os.path.join(data, 'runs.json')))}
    print(len(R), 'recordings reach Storm')

    # 1. When: each crush line's server tick against the phase's first line.
    phase = collections.Counter(); gaps = collections.Counter()
    for r in R.values():
        s = next((c for c in r['chat'] if c[1] == START and c[2] is not None), None)
        cr = [c for c in r['chat'] if c[1] in CRUSHED and c[2] is not None]
        if s: phase.update((c[2] - s[2]) % 20 for c in cr)
        gaps.update((b[2] - a[2]) % 20 for a, b in zip(cr, cr[1:]))
    print('\ncrush line, server ticks after "Pathetic Maxor" (mod 20):', sorted(phase.items()))
    print('between two crushes of one run (mod 20):', sorted(gaps.items()))

    # 2. Where: Storm's position when a crush pinned him (he holds still from then on; the client
    #    gets there 3 ticks after the line, its interpolation), against the pillars.
    stuns = {}
    for rid, r in R.items():
        tr = storm_track(r); steps = pillar_steps(r)
        times = [s[0] for s in tr]
        for (tc, m, nc) in (c for c in r['chat'] if c[1] in CRUSHED):
            i = bisect.bisect_left(times, tc - 3)
            while i < len(tr) - 1 and tr[i][0] <= tc + 8:
                j = i
                while j + 1 < len(tr) and tr[j + 1][1:4] == tr[i][1:4]: j += 1
                if tr[j][0] - tr[i][0] >= 4: break
                i += 1
            else: continue
            if not 2 <= tr[i][0] - tc <= 5: continue
            _, x, y, z, _ = tr[i]
            p = min(PILLARS, key=lambda q: -inset(q, x, z))
            before = [e for e in steps[p] if e[0] <= tc]
            if not before or before[-1][2] is None: continue
            last = [e[0] for e in steps[p] if e[3] and e[0] <= tc]
            key = (runs.get(rid, {}).get('group', rid), p, round(x, 1), round(z, 1))
            stuns.setdefault(key, dict(p=p, x=x, y=y, z=z, bottom=before[-1][2], since=tc - last[-1] if last else None))
    S = list(stuns.values())
    print('\n%d crushes with Storm pinned in view' % len(S), collections.Counter(s['p'] for s in S))
    print('sideways inset into the 6x6 zone: min %.3f (all >= 0: %s)' % (min(inset(s['p'], s['x'], s['z']) for s in S),
          all(inset(s['p'], s['x'], s['z']) >= 0 for s in S)))
    print('in the square\'s rounded corners (no pillar block over him):',
          sum(1 for s in S if not in_circle(s['p'], s['x'], s['z'])))
    print('head above the pillar bottom: min %.3f (feet %.3f below it)' % (min(s['y'] + HEAD - s['bottom'] for s in S),
          max(s['bottom'] - s['y'] for s in S)))
    print('ticks since the pillar last stepped:', sorted(collections.Counter(s['since'] for s in S).items()))

    # 3. Every other check after the lightning (20 server ticks apart, counted from each crush
    #    line), with Storm's position then (the client's, 3 ticks on) - the ones he wasn't crushed
    #    on bound the rule from outside. Only checks with him holding still are trusted: moving,
    #    the client's position can be half a block off the server's.
    table = collections.defaultdict(lambda: [0, 0]); misses = []; counted = set()
    for rid, r in R.items():
        tr = storm_track(r); steps = pillar_steps(r)
        st_n = [s[1] for s in r['st']]; st_t = [s[0] for s in r['st']]
        light = next((c[0] for c in r['chat'] if c[1] in LIGHTNING), None)
        cr = [c for c in r['chat'] if c[1] in CRUSHED and c[2] is not None]
        if light is None or not cr or len(tr) < 50: continue
        done = set()
        for a in cr:
            for k in range(-60, 1):
                i = bisect.bisect_left(st_n, a[2] + 20 * k)
                if i >= len(st_t): continue
                T = st_t[i]
                # Not the lightning's own second, and not while a crush has him pinned.
                if T < light + 20 or T in done or any(0 < T - c[0] <= 100 for c in cr): continue
                done.add(T)
                s, s0, s1 = at(tr, T + 3), at(tr, T + 1), at(tr, T + 5)
                if not (s and s0 and s1) or T + 3 - s[0] > 6 or math.dist(s0[1:4], s1[1:4]) > 1.0: continue
                crushed = any(abs(c[0] - T) <= 2 for c in cr)
                for p in PILLARS:
                    before = [e for e in steps[p] if e[0] <= T]
                    last = [e[0] for e in steps[p] if e[3] and e[0] <= T]
                    if not before or before[-1][2] is None or not last: continue
                    head = s[2] + HEAD - before[-1][2]
                    if inset(p, s[1], s[3]) < 0.2 or head < 0.2: continue
                    # A run recorded by several players counts once.
                    key = (runs.get(rid, {}).get('group', rid), p, round((T - cr[0][0]) / 20))
                    if key in counted: continue
                    counted.add(key)
                    table[(T - last[-1]) // 10 * 10][0 if crushed else 1] += 1
                    if not crushed: misses.append((rid, T, p, round(s[1], 2), round(s[3], 2), round(head, 2), T - last[-1]))
    print('\nchecks with Storm still, inside a zone with his head in the pillar, by ticks since its last step:')
    for b in sorted(table): print('  %3d-%3d: crushed %3d, not %3d' % (b, b + 9, *table[b]))
    for m in misses: print('  not crushed:', m)

def in_circle(p, x, z):
    """Whether (x, z) is under one of the pillar's 37 blocks (rows of 3, 5, 7, 7, 7, 5, 3)."""
    px, pz = PILLARS[p]
    dx, dz = math.floor(x) - px, math.floor(z) - pz
    cut = {0: 2, 1: 1, 5: 1, 6: 2}.get(dz, 0)
    return 0 <= dz <= 6 and cut <= dx <= 6 - cut


if __name__ == '__main__':
    if len(sys.argv) != 3 or sys.argv[1] not in ('fetch', 'analyze'): sys.exit(__doc__)
    {'fetch': fetch, 'analyze': analyze}[sys.argv[1]](sys.argv[2])
