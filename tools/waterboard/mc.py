import json, random, collections, sys
from harness import *
import sim as S

def mc(path, ft, rate, n, horizon):
    d = json.load(open(path)); geo, inn = Geometry.parse(rows_from(d['blocks']), d['rot'])
    gates = sum(1 << o for o, z in enumerate(GATE_Z) if not d['blocks'].get(f'15,56,{z}', 'minecraft:air').endswith(':air'))
    def one(rng):
        s = WaterSim(geo, inn, gates)
        orig = s.run_due
        def run_due():
            orig()
            if rng:
                for c in range(len(s.cells)):
                    v = s.cells[c]
                    if v >= 0 and v & DYN and rng.random() < rate: s.random_ticks.setdefault(s.time, []).append(c)
        s.run_due = run_due
        for _ in range(horizon): s.step(ft.get(s.time + 1, 0))
        return tuple(s.arrivals), s.closed
    base = one(None)
    rng = random.Random(1)
    res = collections.Counter(); shifts = collections.Counter(); outcome = collections.Counter()
    for i in range(n):
        arr, closed = one(rng)
        same_order = [o for _, o in arr] == [o for _, o in base[0]]
        outcome[(same_order, closed == base[1])] += 1
        if same_order:
            for (t, o), (t0, _) in zip(arr, base[0]): shifts[t - t0] += 1
    print(path, 'deterministic arrivals', [(t, COLORS[o]) for t, o in base[0]], 'final closed', [COLORS[o] for o in range(5) if base[1] >> o & 1])
    print('  (same outlets in same order, same final gates):', dict(outcome))
    print('  arrival tick shift vs deterministic:', sorted(shifts.items()))

rate = float(sys.argv[1]) if len(sys.argv) > 1 else 1 / 350
mc('a18b44.json', {1: 64}, rate, 300, 400)
mc('a88e9b.json', {1: 64, 20: 16, 37: 1, 94: 4, 147: 2, 170: 4}, rate, 300, 700)
