#!/usr/bin/env python3
"""Step 4: P3 / Goldor split timings on the server-tick clock, one recording per run.

usage: splits.py OUT_DIR

Section ends come from Hypixel's "(n/n)" completion line when the recording has it, else from
Goldor's section-complete line: one of three, said in the same tick as the last completion of
S1-S3 (checked here against the recordings that have both). S4 ends at "The Core entrance is
opening!". Goldor's death is his "...." when he is killed on the way in, else Necron's first
line - 82 (the gap measured when "...." is the death line). Prints distributions and the fastest
runs; writes OUT_DIR/splits.json.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glib as G  # noqa: E402

SECTION_LINES = ("[BOSS] Goldor: I will replace that gate with a stronger one!",
                 "[BOSS] Goldor: YOUR END IS NEAR!!",
                 "[BOSS] Goldor: The little ants have a brain it seems.")


def run_splits(x, sec):
    c = G.Clock(x['st'])
    g = x['goldor']
    n0 = c.n(g)
    said = [c.n(t) - n0 for t, m in x['chat'] if t >= g and m in SECTION_LINES]
    ends, check = {}, []
    for k in (1, 2, 3):
        d = sec['done'].get(str(k))
        s = said[k - 1] if len(said) >= k else None
        if d is not None and s is not None:
            check.append(s - d)
        ends[k] = d if d is not None else s
    ends[4] = sec['core']
    e = sec['ends']
    if e['dots'] is not None and (e['doneit'] is None or e['dots'] < e['doneit']):
        death, how = e['dots'], 'dots'
    elif e['necron'] is not None:
        death, how = e['necron'] - 82, 'necron-82'
    else:
        death, how = None, None
    return {'ends': ends, 'gates': {int(k): v for k, v in sec['gate'].items()}, 'death': death,
            'death_how': how, 'necron': e['necron'], 'check': check,
            'doneit': e['doneit'], 'n_said': len(said)}


def main():
    out_dir = sys.argv[1]
    X = G.load_extracts(out_dir, need_st=True)
    T = json.load(open(os.path.join(out_dir, 'sections.json')))
    per = {rid: run_splits(x, T[rid]) for rid, x in X.items()}
    # one recording per run: the one with the most known ends
    best = {}
    for rid, r in per.items():
        grp = X[rid]['group']
        score = sum(v is not None for v in r['ends'].values()) + (r['death'] is not None)
        if grp not in best or score > best[grp][0]:
            best[grp] = (score, rid)
    runs = {grp: dict(per[rid], id=rid) for grp, (_, rid) in best.items()}
    json.dump(runs, open(os.path.join(out_dir, 'splits.json'), 'w'))

    chk = [d for r in per.values() for d in r['check']]
    print('section-complete Goldor line minus the (n/n) completion line, server ticks:',
          sorted(set(chk)), 'n=%d' % len(chk))
    rows = [r for r in runs.values() if all(r['ends'][k] is not None for k in (1, 2, 3, 4))]
    print('runs with all four section ends: %d (of %d runs with server ticks)' % (len(rows), len(runs)))
    for k in (1, 2, 3, 4):
        v = [r['ends'][k] - (r['ends'][k - 1] if k > 1 else 0) for r in rows]
        print('  S%d  ' % k, G.summary(v, 0))
    print('  P3   ', G.summary([r['ends'][4] for r in runs.values() if r['ends'][4] is not None], 0))
    dd = [r for r in runs.values() if r['ends'][4] is not None and r['death'] is not None]
    print('  core -> Goldor dead', G.summary([r['death'] - r['ends'][4] for r in dd], 0))
    print('  core -> Necron line', G.summary([r['necron'] - r['ends'][4] for r in dd if r['necron']], 0))
    print('  start -> Necron line', G.summary([r['necron'] for r in dd if r['necron']], 0))
    print('fastest runs (start -> Necron line):')
    for r in sorted((r for r in dd if r['necron']), key=lambda r: r['necron'])[:10]:
        e = r['ends']
        spl = [e[1], e[2] - e[1] if e[1] is not None and e[2] is not None else None,
               e[3] - e[2] if e[2] is not None and e[3] is not None else None,
               e[4] - e[3] if e[3] is not None else None]
        print('  %s  P3 %d  S1-S4 %s  core->dead %d (%s)  dead->Necron %d  total %d' % (
            r['id'], e[4], spl, r['death'] - e[4], r['death_how'], r['necron'] - r['death'], r['necron']))


if __name__ == '__main__':
    main()
