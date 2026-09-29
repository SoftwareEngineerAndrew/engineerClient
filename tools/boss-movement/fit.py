#!/usr/bin/env python3
"""Step 4: fit movement models to Storm's chase/transfer/later phases and Maxor's pursuit.

usage: fit.py OUT_DIR [storm|maxor|all]

Only runs whose every recording has server ticks are used, and only one recording's packets per
run (the one with the most), so arrival jitter between clients does not enter. For each phase it
prints the constant-velocity baseline and the best model of each family (grid search, then a
coordinate-descent refinement) with its rms prediction error 4/8/12/16 server ticks ahead.
Writes OUT_DIR/fit_results.json.

Targets: the 3D-closest living player (Storm's chase and later phases, Maxor), or the fixed point
over Yellow's middle block (46, y, 65) for Storm's flight to the next pillar after a Purple crush.
"""
import collections
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bosslib as B  # noqa: E402
import dynamics as D  # noqa: E402
import recording as R  # noqa: E402

YELLOW = (46.0, 170.0, 65.0)
HORIZON = 16


def load(out_dir):
    X = B.load_extracts(out_dir)
    T = {}
    for f in sorted(os.listdir(os.path.join(out_dir, 'tracks'))):
        tr = json.load(open(os.path.join(out_dir, 'tracks', f)))
        if all(len(X[r]['st']) > 10 for r in tr['recs']):
            T[f[:-5]] = tr
    return T


def best_packets(tr, boss):
    pk = tr['bosses'][boss]['pkt']
    if not pk:
        return []
    rec = collections.Counter(p[4] for p in pk).most_common(1)[0][0]
    return [p for p in pk if p[4] == rec]


def closest_player_fn(tr):
    ghosts = B.ghost_spans(tr, None)
    rows = {k: v for k, v in tr['players'].items() if v}

    def at(n, pos):
        best = None
        for name, r in rows.items():
            if not B.alive(ghosts, name, n):
                continue
            q = B.player_at(r, n)
            if q is None:
                continue
            d = B.dist(q, pos)
            if best is None or d < best[0]:
                best = (d, q)
        return None if best is None else tuple(best[1])
    return at


def cases_for(pk, n_from, n_to, target_fn, stride=2, min_speed=0.05, min_start_speed=0.15):
    """A case starts at every `stride`-th packet in [n_from, n_to - 4]: the observed position,
    the velocity from the packet 2-6 ticks before, the target for each of the next HORIZON ticks
    (chosen against the observed position at that tick), and the packets that follow."""
    out = []
    for i in range(1, len(pk)):
        n0 = pk[i][0]
        if not (n_from <= n0 <= n_to - 4) or i % stride:
            continue
        j = i - 1
        while j >= 0 and n0 - pk[j][0] < 2:
            j -= 1
        if j < 0 or n0 - pk[j][0] > 6:
            continue
        dn = n0 - pk[j][0]
        vel = tuple((pk[i][k] - pk[j][k]) / dn for k in (1, 2, 3))
        if math.sqrt(sum(v * v for v in vel)) < min_start_speed:
            continue   # starting from rest (pinned, parked, stunned): not what these models describe
        obs = [(p[0], tuple(p[1:4])) for p in pk[i + 1:i + 12] if n0 < p[0] <= min(n_to, n0 + HORIZON)]
        if len(obs) < 2:
            continue
        if B.dist(obs[-1][1], pk[i][1:4]) / max(1, obs[-1][0] - n0) < min_speed:
            continue
        targets = []
        for h in range(HORIZON):
            p = B.pos_at(pk, n0 + h, max_gap=HORIZON) or tuple(pk[i][1:4])
            targets.append(target_fn(n0 + h, p))
        out.append((tuple(pk[i][1:4]), vel, n0, targets, obs))
    return out


def storm_cases(T):
    phases = {'chase (after the lightning, to crush 1)': [], 'transfer (Purple crush -> Yellow)': [],
              'later (near Yellow / after the transfer)': []}
    names = list(phases)
    for g, tr in T.items():
        if 'storm' not in tr['bosses']:
            continue
        ev = tr['events']
        if R.STORM_START not in ev:
            continue
        lis = [v for m in R.STORM_LIGHTNING for v in ev.get(m, [])]
        if not lis:
            continue
        li = min(lis)
        cr = sorted(v for m in R.STORM_CRUSHED for v in ev.get(m, []))
        dead = ev.get(R.STORM_DEAD, [10 ** 9])[0]
        pk = best_packets(tr, 'storm')
        near = closest_player_fn(tr)
        c1 = cr[0] if cr else dead
        phases[names[0]] += cases_for(pk, li + 130, c1 - 2, near)
        if not cr:
            continue
        p1 = B.pos_at(pk, cr[0] + 4, max_gap=40)
        if not p1 or B.pillar_of(p1[0], p1[2]) != 'purple':
            continue
        end = cr[1] - 2 if len(cr) > 1 else dead
        arrive = next((p[0] for p in pk if p[0] > cr[0] and math.hypot(p[1] - YELLOW[0], p[3] - YELLOW[2]) < 8), end)
        phases[names[1]] += [c for c in cases_for(pk, cr[0] + 4, min(end, arrive), lambda n, pos: YELLOW)
                             if B.pillar_of(c[0][0], c[0][2]) != 'purple']
        phases[names[2]] += cases_for(pk, arrive, end, near)
    return phases


def maxor_cases(T):
    cases = []
    for g, tr in T.items():
        if 'maxor' not in tr['bosses']:
            continue
        ev = tr['events']
        intro = ev.get(R.MAXOR_INTRO_END, [None])[0]
        ss = ev.get(R.STORM_START, [None])[0]
        if intro is None or ss is None:
            continue
        stuns = sorted(v for m in R.MAXOR_STUN for v in ev.get(m, []))
        enr = sorted(ev.get(R.MAXOR_ENRAGED, []))
        pk = best_packets(tr, 'maxor')
        near = closest_player_fn(tr)
        # free movement: from intro end + 46 to the first stun line, and from each enrage line
        # (the end of a stun) to the next stun line
        starts = [intro + 46] + [e for e in enr]
        for a in starts:
            b = min([s for s in stuns if s > a + 2] + [ss])
            cases += cases_for(pk, a + 2, b - 2, near)
    return {'pursuit (intro end + 46 to a stun, enrage to the next stun)': cases}


FAMILIES = [
    ('constant speed + stop radius', D.Pursuit,
     {'vmax': [0.4, 0.6, 0.72, 0.8, 0.9, 1.0], 'k': [1e9], 'stop': [0, 3, 6], 'dy': [0, 2, 4]},
     {'vmax': 0.05, 'stop': 0.5, 'dy': 0.5}),
    ('speed = min(vmax, c + k d)', D.Pursuit,
     {'vmax': [0.72, 0.9, 1.0], 'k': [0.01, 0.02, 0.03, 0.05, 0.08], 'c': [0, 0.1, 0.2], 'dy': [0, 2, 4]},
     {'vmax': 0.05, 'k': 0.005, 'c': 0.05, 'dy': 0.5}),
    ('inertia: vel += (pursuit - vel) a', D.Inertia,
     {'vmax': [0.72, 0.9], 'k': [0.02, 0.05, 1e9], 'c': [0, 0.2], 'a': [0.1, 0.3, 0.6], 'dy': [0, 3]},
     {'vmax': 0.05, 'k': 0.005, 'c': 0.05, 'a': 0.05, 'dy': 0.5}),
    ('vanilla 1.8 wither, vanilla constants', D.VanillaWither,
     {'pull': [0.5], 'lerp': [0.6], 'friction': [0.91], 'gravity': [0.08], 'stop': [3.0], 'hover': [0.0, 5.0]},
     {}),
    ('vanilla 1.8 wither, constants fitted', D.VanillaWither,
     {'pull': [0.4, 0.5, 0.6, 0.8], 'lerp': [0.3, 0.6], 'friction': [0.91], 'gravity': [0.08], 'stop': [3.0, 6.0], 'hover': [0.0, 2.5, 5.0]},
     {'pull': 0.05, 'lerp': 0.05, 'stop': 0.5, 'hover': 0.5}),
]


def fit_phase(label, cases, results):
    base, n = D.prediction_error(D.ConstVel(), cases)
    fmt = lambda e: ' '.join('h%d %.2f' % (h, v) for h, v in sorted(e.items()) if v is not None)
    print('\n== %s: %d start points, %d compared positions ==' % (label, len(cases), n))
    print('   %-36s %s' % ('constant velocity (baseline)', fmt(base)))
    results[label] = {'starts': len(cases), 'compared': n, 'baseline': base, 'fits': []}
    for fname, cls, grid, steps in FAMILIES:
        s, m, errs = D.grid_search(cls, grid, cases)[0]
        if steps:
            s, m, errs = D.refine(cls, m.p, cases, {k: v for k, v in steps.items() if k in m.p and m.p[k] < 1e8})
        print('   %-36s %s   %r' % (fname, fmt(errs), m))
        results[label]['fits'].append({'family': fname, 'model': repr(m), 'params': m.p, 'errs': errs, 'score': s})


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    out_dir = sys.argv[1]
    which = sys.argv[2] if len(sys.argv) > 2 else 'all'
    T = load(out_dir)
    results = {}
    todo = []
    if which in ('storm', 'all'):
        todo += [('Storm ' + k, v) for k, v in storm_cases(T).items()]
    if which in ('maxor', 'all'):
        todo += [('Maxor ' + k, v) for k, v in maxor_cases(T).items()]
    for label, cases in todo:
        fit_phase(label, cases, results)
    json.dump(results, open(os.path.join(out_dir, 'fit_results.json'), 'w'), indent=1, default=str)


if __name__ == '__main__':
    main()
