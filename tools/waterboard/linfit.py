"""Clock-fit check: server tick = a*ms + b (fitted robustly on exact matches); flips placed by the clock; each batch must
equal the sends of one tick, or of 2-3 consecutive ticks (delivered together), within +-2 of the clock."""
import json, sys, glob, os, align
from harness import *

def run(path, R=None, show=0):
    align.RANDOM = R or {}
    d = json.load(open(path)); geo, inn = Geometry.parse(rows_from(d['blocks']), d['rot'])
    gates = sum(1 << o for o, z in enumerate(GATE_Z) if not d['blocks'].get(f'15,56,{z}', 'minecraft:air').endswith(':air'))
    b = align.batches_of(d)
    def evaluate(a, c):
        ft = {}
        for ms, it, lv in b:
            for l in lv: t = round(a * ms + c); ft[t] = ft.get(t, 0) ^ (1 << l)
        s, sends = align.simulate2(geo, inn, gates, ft, round(a * b[-1][0] + c) + 60)
        res = []
        for ms, it, lv in b:
            if not it: continue
            g = a * ms + c; best = None
            for t in range(round(g) - 2, round(g) + 3):
                for span in (1, 2, 3):
                    u = frozenset().union(*[sends.get(k, frozenset()) for k in range(t - span + 1, t + 1)])
                    sc = (u == it, len(it & u) - len(it ^ u), -abs(t - g), -span)
                    if best is None or sc > best[0]: best = (sc, t, u)
            res.append((ms, best[1], it, best[2]))
        return res, s
    best = None
    for c in [x / 4 for x in range(-4, 24)]:
        res, s = evaluate(0.02, c)
        sc = sum(len(it & u) for ms, t, it, u in res)
        if best is None or sc > best[0]: best = (sc, 0.02, c)
    a, c = best[1], best[2]
    for it in range(4):
        res, s = evaluate(a, c)
        pts = [(ms, t) for ms, t, x, u in res if x == u]
        if len(pts) < 3: break
        n = len(pts); mx = sum(p[0] for p in pts) / n; my = sum(p[1] for p in pts) / n
        sxx = sum((p[0] - mx) ** 2 for p in pts)
        a2 = sum((p[0] - mx) * (p[1] - my) for p in pts) / sxx if sxx else a
        c2 = my - a2 * mx
        if abs(a2 - a) < 1e-6 and abs(c2 - c) < 1e-3: break
        a, c = a2, c2
    res, s = evaluate(a, c)
    ok = sum(len(it & u) for _, _, it, u in res); tot = sum(len(it) for _, _, it, _ in res)
    ex = sum(it == u for _, _, it, u in res)
    print(f'{d["rec"]} {d["rot"]}: writes {ok}/{tot}, batches {ex}/{len(res)}  (ms per tick {1/a:.2f})')
    f = lambda q: f'{cx(q[0])},{cy(q[0])}={q[1]}'
    bad = [(ms, t, it - u, u - it) for ms, t, it, u in res if it != u]
    for ms, t, x, y in bad[:show]: print(f'   ms {ms} T{t}: server-only {sorted(map(f, x))} sim-only {sorted(map(f, y))}')
    return bad

if __name__ == '__main__':
    for p in sorted(glob.glob(sys.argv[1] + '/*.json')):
        if p.endswith('.blocks.json') or p.endswith('report.json'): continue
        run(p, show=int(sys.argv[2]) if len(sys.argv) > 2 else 0)
