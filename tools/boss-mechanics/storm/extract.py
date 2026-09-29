#!/usr/bin/env python3
"""Step 1: each recording's Storm window, with what tools/boss-movement's extract leaves out.

usage: extract.py DATA_DIR OUT_DIR [--jobs N]

DATA_DIR is laid out as for tools/boss-movement (runs.json, ids.txt, runs/<id>.gz). Writes
OUT_DIR/storm-mech/<id>.pkl for every recording with Storm's first line (alpha-server runs are
skipped), covering 100 client ticks before that line to 100 after Goldor's first line:
  st      [(t, n)]                   server tick count (Odin's ping), plus the last one before
  chat    [(t, message)]             every chat line
  party   [(t, [[name, class]...])]  party lines (the whole recording)
  p       [(t, name, x, y, z, yaw, pitch, heldItemId)]   player entries
  sw      [(t, (name, ...))]         arm swings
  blocks  [(t, x, y, z, state)]      block changes in the arena (x 30-120, y 150-210, z 5-100)
Cached: a recording whose .pkl exists is not read again.
"""
import os
import pickle
import sys
from multiprocessing import Pool

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'boss-movement'))
import recording as R  # noqa: E402


def one(args):
    data_dir, out, rid = args
    dst = os.path.join(out, rid + '.pkl')
    if os.path.exists(dst):
        return 'cached'
    try:
        L = R.read_lines(R.run_path(data_dir, rid))
    except Exception:
        return 'unreadable'
    t0 = t1 = None
    for l in L:
        if l['k'] == 'chat':
            if l['m'] == R.STORM_START and t0 is None:
                t0 = l['t']
            if l['m'] == R.GOLDOR_START and t0 is not None and t1 is None:
                t1 = l['t']
    if t0 is None:
        pickle.dump({'id': rid, 'skip': 'no Storm line'}, open(dst, 'wb'))
        return 'no Storm'
    lo, hi = t0 - 100, (t1 + 100) if t1 else 10 ** 9
    pal = {}
    o = {'id': rid, 'self': L[0].get('self'), 'mod': L[0].get('mod'), 'st': [], 'chat': [], 'party': [],
         'p': [], 'sw': [], 'blocks': []}
    last_st = None
    for l in L:
        k = l['k']
        if k == 'pal':
            pal[l['i']] = l['s']
            continue
        t = l.get('t')
        if k == 'party':
            o['party'].append((t, l['m']))
            continue
        if t is None:
            continue
        if k == 'st':
            if t < lo:
                last_st = (t, l['n'])
            elif t <= hi:
                o['st'].append((t, l['n']))
            continue
        if t < lo or t > hi:
            continue
        if k == 'chat':
            o['chat'].append((t, l['m']))
        elif k == 'p':
            for d in l['d']:
                o['p'].append((t, d[0], d[1], d[2], d[3], d[4], d[5], d[6] if len(d) > 6 else None))
        elif k == 'sw':
            o['sw'].append((t, tuple(l['d'])))
        elif k == 'block' and 150 <= l['y'] <= 210 and 30 <= l['x'] <= 120 and 5 <= l['z'] <= 100:
            o['blocks'].append((t, l['x'], l['y'], l['z'], pal.get(l['s'], '?')))
    if last_st:
        o['st'].insert(0, last_st)
    tmp = dst + '.tmp'
    pickle.dump(o, open(tmp, 'wb'))
    os.replace(tmp, dst)
    return 'ok'


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    data_dir, out_dir = sys.argv[1], sys.argv[2]
    jobs = int(sys.argv[sys.argv.index('--jobs') + 1]) if '--jobs' in sys.argv else os.cpu_count() or 2
    out = os.path.join(out_dir, 'storm-mech')
    os.makedirs(out, exist_ok=True)
    ids = [i for i in R.wanted_ids(data_dir) if i not in R.ALPHA_RUNS]
    counts = {}
    with Pool(jobs) as p:
        for s in p.imap_unordered(one, [(data_dir, out, i) for i in ids]):
            counts[s] = counts.get(s, 0) + 1
    print('storm-mech extract:', counts)


if __name__ == '__main__':
    main()
