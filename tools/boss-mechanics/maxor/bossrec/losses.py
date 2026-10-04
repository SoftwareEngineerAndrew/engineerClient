"""Alpha: each run against the floor, step by step, and each carrier's trip (pick -> leap -> placed)."""
import os, json, glob, math, statistics, collections
from cycle_table import row, hits_of
from placerule import interp

PYL = {'W': (52.5, 224.0, 41.5), 'E': (94.5, 224.0, 41.5)}


def trips(r, h1):
    """per pick: (cycle, who, pick, arrive-at-pylon (within 3 blocks, on the platform), placed, side)"""
    ev = r['events']
    picks = [(s, v) for s, k, v in ev if k == 'pick']
    places = [(s, v) for s, k, v in ev if k == 'crystal+' and v in ('W', 'E')]
    used = set(); out = []
    for ps, who in picks:
        tr = r['players'].get(who)
        if not tr:
            continue
        got = None
        for s, side in places:
            if s <= ps or (s, side) in used:
                continue
            p, age = interp(tr, s)
            if p and math.hypot(p[0] - PYL[side][0], p[2] - PYL[side][2]) < 5:
                got = (s, side); used.add(got); break
        arrive = None; leap = None
        if got:
            prev = None
            for row_ in tr:
                if row_[0] < ps or row_[0] > got[0]:
                    prev = row_ if row_[0] < ps else prev
                    continue
                d = math.dist(row_[1:], PYL[got[1]])
                if arrive is None and d <= 3.0:
                    arrive = row_[0]
                if prev and leap is None and math.dist(prev[1:], row_[1:]) > 8 and row_[0] - prev[0] <= 3:
                    leap = row_[0]
                prev = row_
        cyc = 1 if h1 is None or ps < h1 else 2
        out.append(dict(cyc=cyc, who=who, pick=ps, leap=leap, arrive=arrive, placed=got[0] if got else None,
                        side=got[1] if got else None))
    return out


if __name__ == '__main__':
    W = []
    for p in sorted(glob.glob(os.path.join(os.environ.get('MAXOR_OUT', 'out'), '*.json'))):
        r = json.load(open(p))
        if 'events' not in r or r['server'] != 'alpha':
            continue
        x = row(r)
        if x['kill'] is None or x['hit1'] is None:
            continue
        b, h1, h2, k, st = x['beacon'], x['hit1'], x['hit2'], x['kill'], x['storm']
        armed1 = (x['charge1'] or 0)
        loss_h1 = h1 - b
        loss_h2 = (h2 - h1 - 80) if h2 else None
        loss_k = (k - h2) if h2 else None
        tr = trips(r, h1)
        print('%s beacon %d | hit1 +%-3d (charge1 %s) | hit2-hit1-80 = %-4s | kill-hit2 %-3s | storm %d (floor-ish %d)' % (
            x['run'][11:], b, loss_h1, armed1 - b, loss_h2, loss_k, st, b + 80 + 2 + 62))
        for t in tr:
            base = (b - 40) if t['cyc'] == 1 else h1
            rel = lambda v: None if v is None else v - base
            print('     cyc%d %-10s %s  pick %-4s leap %-4s at-pylon %-4s placed %-4s' % (
                t['cyc'], t['who'], t['side'], rel(t['pick']), rel(t['leap']), rel(t['arrive']), rel(t['placed'])))
            W.append(dict(t, base=base, run=x['run']))
    print()
    for cyc in (1, 2):
        for who in sorted({w['who'] for w in W}):
            xs = [w for w in W if w['cyc'] == cyc and w['who'] == who and w['placed'] is not None]
            if len(xs) < 3:
                continue
            f = lambda key: sorted(w[key] - w['base'] for w in xs if w[key] is not None)
            print('cycle %d %-10s n=%2d  pick %s\n                      arrive %s\n                      placed %s' % (cyc, who, len(xs), f('pick'), f('arrive'), f('placed')))
