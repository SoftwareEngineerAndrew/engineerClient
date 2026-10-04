"""What makes a crystal place: every check a carrier holds a crystal near an open pylon, placed or not.

Carriers' positions are the server's own (other players' move packets), on the same clock as the
placement; p3wr's are what his client sent, ~one round trip early, so they are kept apart.
"""
import os, json, glob, math, collections, sys
from carriers import at

PYL = {'W': (52.5, 224.0, 41.5), 'E': (94.5, 224.0, 41.5)}


def interp(track, s):
    prev = None
    for row in track:
        if row[0] >= s:
            if prev is None:
                return row[1:], row[0] - s
            if row[0] == s:
                return row[1:], 0
            a = (s - prev[0]) / (row[0] - prev[0])
            return tuple(p + (q - p) * a for p, q in zip(prev[1:], row[1:])), min(s - prev[0], row[0] - s)
        prev = row
    return (prev[1:], s - prev[0]) if prev else (None, None)


def hits_of(ev):
    hits = []; last = None
    for s, k, v in ev:
        if k == 'mhp':
            if v == 1000.0 and last != 1000.0:
                hits.append(s)
            last = v
    return hits


def samples(r):
    ev = r['events']
    beacon = next((s for s, k, v in ev if k == 'col221' and v == 'beacon'), None)
    if beacon is None:
        return []
    spans = r.get('clean_spans', [])
    def clean(x):
        return any(a < x <= b for a, b in spans)
    marks = [s for s, k, v in ev if k in ('col224', 'col223', 'col222') and clean(s)]
    if beacon is not None and clean(beacon): marks.append(beacon)
    if len(marks) < 2:
        return []
    ph = collections.Counter(m % 10 for m in marks).most_common(1)[0][0]
    occ = {'W': [], 'E': []}
    cur = {}
    for s, k, v in ev:
        if k == 'crystal+' and v in ('W', 'E'): cur[v] = s
        if k == 'crystal-' and v in ('W', 'E') and v in cur: occ[v].append((cur.pop(v), s))
    for v, a in cur.items(): occ[v].append((a, 10**6))
    def occupied(side, c):
        return any(a <= c < b for a, b in occ[side])
    hits = hits_of(ev)
    picks = [(s, v) for s, k, v in ev if k == 'pick']
    places = [(s, v) for s, k, v in ev if k == 'crystal+' and v in ('W', 'E')]
    out = []
    used = set()
    for ps, who in picks:
        tr = r['players'].get(who)
        if not tr:
            continue
        # placement it led to: first unused placement after the pick on a pylon the carrier is within 5 of
        got = None
        for s, side in places:
            if s <= ps or (s, side) in used:
                continue
            p, age = interp(tr, s)
            if p and math.hypot(p[0] - PYL[side][0], p[2] - PYL[side][2]) < 5:
                got = (s, side); used.add(got); break
        # the end of the carrying: placement, or 400 ticks
        end = got[0] if got else ps + 400
        prevhit = max([h for h in hits if h < ps] or [None], key=lambda x: -1 if x is None else x)
        open_at = (beacon - 40) if prevhit is None else prevhit + 41
        c = ps + ((ph - ps) % 10)
        while c <= end:
            if c >= open_at:
                for side in ('W', 'E'):
                    p, age = interp(tr, c)
                    if p is None or age > 2 or not clean(c) or occupied(side, c):
                        continue
                    d3 = math.dist(p, PYL[side]); h = math.hypot(p[0] - PYL[side][0], p[2] - PYL[side][2])
                    if d3 > 6:
                        continue
                    placed = bool(got and got[1] == side and got[0] - c in (0, 1))
                    out.append(dict(run=r['run'][:19], srv=r['server'], who=who, self=(who == r['self']), c=c,
                                    side=side, h=round(h, 2), dy=round(p[1] - 224, 2), d3=round(d3, 2),
                                    age=age, placed=placed, placed_at=got[0] if got else None, cyc=0 if prevhit is None else 1))
            c += 10
        if got and (got[0] - ph) % 10 not in (0, 1) and clean(got[0]):
            p, age = interp(tr, got[0])
            out.append(dict(run=r['run'][:19], srv=r['server'], who=who, self=(who == r['self']), c=got[0], side=got[1],
                            h=round(math.hypot(p[0] - PYL[got[1]][0], p[2] - PYL[got[1]][2]), 2), dy=round(p[1] - 224, 2),
                            d3=round(math.dist(p, PYL[got[1]]), 2), age=age, placed=True, offgrid=(got[0] - ph) % 10,
                            placed_at=got[0], cyc=0 if prevhit is None else 1))
    return out


if __name__ == '__main__':
    sel = sys.argv[1] if len(sys.argv) > 1 else 'alpha'
    allS = []
    for p in sorted(glob.glob(os.path.join(os.environ.get('MAXOR_OUT', 'out'), '*.json'))):
        r = json.load(open(p))
        if 'events' not in r or r['server'] != sel:
            continue
        allS += samples(r)
    others = [s for s in allS if not s['self']]
    print('samples', len(allS), 'others', len(others))
    # table: by 3D distance bucket, placed / total (others, on-grid checks)
    for key in ('d3', 'h'):
        print('by', key)
        b = collections.defaultdict(lambda: [0, 0])
        for s in others:
            if 'offgrid' in s:
                continue
            k = min(int(s[key] * 4) / 4, 6)
            b[k][1] += 1; b[k][0] += s['placed']
        for k in sorted(b):
            print('  %4.2f-%4.2f  %3d / %3d' % (k, k + 0.25, b[k][0], b[k][1]))
    print('misses within 3.25 (3D):')
    for s in others:
        if not s['placed'] and s['d3'] <= 3.25 and 'offgrid' not in s:
            print('  ', s)
    print('off-grid placements:')
    for s in allS:
        if 'offgrid' in s:
            print('  ', s)
