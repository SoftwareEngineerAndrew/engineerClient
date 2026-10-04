"""i4 board timeline per attempt (main server), with every arrow traced into the 3x3 target grid.

From $I4_OUT/rec/*.json (extract.py). An attempt: from Goldor's first line (or a reset) to "<name> completed a device!".
Lights (blue -> emerald) and hits (emerald -> blue) at the 9 cells, the completion line, the device stands' names.
Every arrow with an exact position packet (its relaunch `sync` + velocity) is flown on with vanilla physics
(pos += v; v *= 0.99; v.y -= 0.05) to the board plane z = 50: the cell it enters and the server tick. Each cell that
was never lit in an attempt (a hidden completion) gets the arrows that reached it, with the tick and the gap to
the previous light / hit.

    I4_OUT=~/Cluade/maxor-work/i4/out python3 board.py [--all]
"""
import glob, json, os, sys, collections

OUT = os.path.expanduser(os.environ.get('I4_OUT', '~/Cluade/maxor-work/i4/out'))
CELLS = [(x, y) for y in (126, 128, 130) for x in (64, 66, 68)]
VERBOSE = '--all' in sys.argv


def cell_of(x, y):
    for cx, cy in CELLS:
        if cx <= x < cx + 1 and cy <= y < cy + 1: return (cx, cy)
    return None


def trace(a):
    """(tick, (x, y), cell or None) where the arrow crosses z = 50 going +z, from its first exact sync."""
    sync = next((e for e in a['ev'] if e[2] == 'sync'), None)
    vel = None
    if sync is None: return None
    # the velocity sent with (just before or after) that sync
    vs = [e for e in a['ev'] if e[2] == 'v' and abs(e[0] - sync[0]) <= 0 and any(e[3:6])]
    if not vs: return None
    v = list(vs[-1][3:6]); p = list(sync[3:6]); n = sync[0]
    for k in range(1, 60):
        q = [p[0] + v[0], p[1] + v[1], p[2] + v[2]]
        if p[2] < 50 <= q[2] and v[2] > 0:
            f = (50 - p[2]) / (q[2] - p[2])
            x = p[0] + f * v[0]; y = p[1] + f * v[1]
            return n + k, (round(x, 3), round(y, 3)), cell_of(x, y)
        p = q; v = [v[0] * 0.99, v[1] * 0.99 - 0.05, v[2] * 0.99]
        if p[1] < 100: break
    return None


def attempts(d):
    """Split the board's light/hit events into attempts ending at a device-completion line or a reset."""
    ev = []
    for n, seq, x, y, z, st, kind, i in d['board']:
        if z != 50 or (x, y) not in CELLS: continue
        if 'emerald' in st: ev.append((n, seq, 'light', (x, y)))
        elif 'blue_terracotta' in st: ev.append((n, seq, 'blue', (x, y)))
    for n, seq, plain, leg in d['chat']:
        if plain and plain.endswith('/7)') and 'completed a device!' in plain: ev.append((n, seq, 'done', plain.split(' ')[0]))
        if plain == '[BOSS] Goldor: Who dares trespass into my domain?': ev.append((n, seq, 'p3', None))
    for n, seq, x, y, z, st, kind, i in d['board']:
        if (x, y, z) == (63, 127, 35): ev.append((n, seq, 'plate', st.split('power=')[-1].rstrip(']')))
    ev.sort(key=lambda e: e[1])
    return ev


def main():
    stats = collections.Counter(); gaps = collections.Counter(); hidden_rows = []; lat = collections.Counter(); light_gap = collections.Counter()
    same_tick = collections.Counter(); first_light = collections.Counter(); stand_lag = collections.Counter()
    for f in sorted(glob.glob(os.path.join(OUT, 'rec', '*.json'))):
        d = json.load(open(f))
        if 'stuck' not in (d.get('server') or '') and 'hypixel.net' != d.get('server'): continue
        ev = attempts(d)
        if not any(e[2] == 'light' for e in ev): continue
        arrows = []
        for aid, a in d['arrows'].items():
            t = trace(a)
            if t: arrows.append((t[0], t[2], t[1], a['own'], aid))
        stands = [(nm[0], nm[2]) for s in d['stands'].values() for nm in s['names'] if nm[2] in ('Active', 'Device', 'Inactive')]
        # walk attempts
        shown = set(); hits = []; lights = []; p3 = None; plate = '0'
        for e in ev:
            n, seq, kind, val = e
            if kind == 'p3': p3 = n; shown = set(); hits = []; lights = []
            elif kind == 'plate': plate = val
            elif kind == 'light':
                if lights or hits:
                    pass
                if not lights and p3 is not None: first_light[n - p3] += 1
                if hits: light_gap[n - hits[-1][0]] += 1
                lights.append((n, val)); shown.add(val)
                if any(h[0] == n for h in hits): same_tick['light in a hit tick'] += 1
            elif kind == 'blue':
                if lights and lights[-1][1] == val and (not hits or hits[-1][1] != val):
                    hits.append((n, val))
                    # latency: the arrow into that cell nearest before
                    cand = [a for a in arrows if a[1] == val and a[0] <= n + 1 and a[0] >= lights[-1][0] - 1]
                    if cand: lat[n - max(c[0] for c in cand)] += 1
                elif not lights or lights[-1][1] != val:
                    stats['blue on a cell not lit last'] += 1
            elif kind == 'done' and val == d.get('self'):
                stats['attempts'] += 1
                missing = [c for c in CELLS if c not in shown]
                stats['lights %d' % len(shown)] += 1
                lag = [s[0] - n for s in stands if s[1] == 'Active' and 0 <= s[0] - n <= 40]
                if lag: stand_lag[min(lag)] += 1
                lo = lights[0][0] if lights else n - 200
                for c in missing:
                    reach = sorted(a for a in arrows if a[1] == c and lo - 2 <= a[0] <= n + 2)
                    # timeline context for each arrow that reached the hidden cell
                    ctx = []
                    for a in reach:
                        prev_hit = max([h[0] for h in hits if h[0] <= a[0]], default=None)
                        prev_light = max([l[0] for l in lights if l[0] <= a[0]], default=None)
                        next_light = min([l[0] for l in lights if l[0] > a[0]], default=None)
                        ctx.append({'tick': a[0], 'own': a[3], 'since_hit': None if prev_hit is None else a[0] - prev_hit,
                                    'since_light': None if prev_light is None else a[0] - prev_light,
                                    'to_next_light': None if next_light is None else next_light - a[0],
                                    'cur_lit': next((l[1] for l in reversed(lights) if l[0] <= a[0]), None)})
                    hidden_rows.append((os.path.basename(f)[11:19], c, ctx))
                    if ctx:
                        first = ctx[0]
                        if first['since_hit'] is not None: gaps[first['since_hit']] += 1
                if VERBOSE:
                    print(os.path.basename(f)[11:19], 'lights', [(l[0] - (p3 or 0), l[1]) for l in lights], 'hits', [(h[0] - (p3 or 0), h[1]) for h in hits], 'done', n - (p3 or 0), 'missing', missing)
                shown = set(); hits = []; lights = []
    print('attempts:', dict(stats))
    print('first light, ticks after Goldor\'s line:', sorted(first_light.items()))
    print('hit -> next light (ticks):', sorted(light_gap.items()), ' ', dict(same_tick))
    print('arrow reaches the lit cell -> its block goes blue (ticks):', sorted(lat.items()))
    print('completion line -> stand "Active" (ticks):', sorted(stand_lag.items()))
    print('hidden cells: first arrow into them, ticks after the previous counted hit:', sorted(gaps.items()))
    for rec, c, ctx in hidden_rows:
        print('  %s hidden %s: %s' % (rec, c, ctx[:4] if ctx else 'no traced arrow reached it'))


if __name__ == '__main__':
    main()
