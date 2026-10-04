"""Export the per-tick movement track of every leg in legs.csv to tracks.jsonl.gz.

One JSON line per leg, in legs.csv order:
  {"leg": {<all legs.csv columns>}, "pad": PAD,
   "track": [[t, x, y, z, yaw, pitch, heldSbId, inputs, sneak, sprint], ...]}
The track covers start_t-PAD .. end_t+PAD (client ticks, same tick axis as start_t/end_t).
Recorder legs: inputs = 'WSADJ' with '.' for keys not held; sneak = shift or crouch; sprint = 0/1.
Better PF legs: inputs = '?????' (not recorded); sneak = recorded crouch flag; sprint = null.
Only the local player's own samples are read (recorder 'me' lines, Better PF 'p' rows for meta.self).

Env vars (all optional):
  P3_LEGS      merged legs.csv            (default ../../docs/mechanics/p3-movement/legs.csv)
  P3_EXTRACTS  dir of <6hex>.jsonl recorder extracts (default ./poitimes)
  BPF_RUNS     Better PF runs dir (see bpflegs.py)
  P3_OUT       output path                (default ../../docs/mechanics/p3-movement/tracks.jsonl.gz)
  P3_PAD       padding ticks              (default 10)
"""
import bisect, csv, gzip, json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import bpflegs
from legs import parse

DOCS = os.path.join(HERE, '../../docs/mechanics/p3-movement')
LEGS = os.environ.get('P3_LEGS', os.path.join(DOCS, 'legs.csv'))
EXTRACTS = os.environ.get('P3_EXTRACTS', 'poitimes')
OUT = os.environ.get('P3_OUT', os.path.join(DOCS, 'tracks.jsonl.gz'))
PAD = int(os.environ.get('P3_PAD', '10'))


def rec_run(run):
    me, acts, leaps, inv = parse(os.path.join(EXTRACTS, run))
    hb = {}; invt = []
    for d in sorted(inv, key=lambda d: d['seq']):
        for sl, it in d['s']:
            if sl < 9: hb[sl] = (it.get('sb') or it.get('id')) if it else None
        invt.append((d['seq'], dict(hb)))
    return me, invt, [x[0] for x in invt], [m['t'] for m in me]


def rec_track(c, r):
    me, invt, iseq, T = c
    t0 = int(r['start_t']) - PAD; t1 = int(r['end_t']) + PAD
    tr = []
    for m in me[bisect.bisect_left(T, t0):bisect.bisect_right(T, t1)]:
        j = bisect.bisect_right(iseq, m['seq']) - 1
        h = invt[j][1].get(m['slot']) if j >= 0 else None
        i = m['in']; ins = ''.join(ch if i[k] else '.' for k, ch in enumerate('WSADJ'))
        tr.append([m['t'], round(m['pos'][0], 3), round(m['pos'][1], 3), round(m['pos'][2], 3),
                   round(m['rot'][0], 2), round(m['rot'][1], 2), h, ins,
                   int(bool(m['shift'] or m['crouch'])), int(bool(m['sprint']))])
    return tr


def bpf_run(stamp):
    f = stamp + '_F7.jsonl.gz'
    if not os.path.exists(bpflegs.D + f):
        f = [x for x in os.listdir(bpflegs.D) if x.startswith(stamp)][0]
    info, _, _ = bpflegs.process(f)
    return info['_tr']


def bpf_track(c, r):
    pos, rot, hl, cr = c
    t0 = max(0, int(r['start_t']) - PAD); t1 = min(len(pos) - 1, int(r['end_t']) + PAD)
    return [[t, round(pos[t][0], 3), round(pos[t][1], 3), round(pos[t][2], 3),
             round(rot[t][0], 2), round(rot[t][1], 2), hl[t] or None, '?????', int(cr[t] == 1), None]
            for t in range(t0, t1 + 1)]


def main():
    rows = list(csv.DictReader(open(LEGS)))
    key = None; cache = None; n = 0; samples = 0
    with gzip.open(OUT, 'wt', compresslevel=9) as out:
        for r in rows:
            k = (r['source'], r['run'])
            if k != key:  # legs.csv is grouped by run; keep one run's data in memory at a time
                cache = rec_run(r['run']) if r['source'] == 'recorder' else bpf_run(r['run'])
                key = k
            tr = rec_track(cache, r) if r['source'] == 'recorder' else bpf_track(cache, r)
            out.write(json.dumps(dict(leg=r, pad=PAD, track=tr), separators=(',', ':')) + '\n')
            n += 1; samples += len(tr)
    print(n, 'legs', samples, 'samples', round(os.path.getsize(OUT) / 1e6, 2), 'MB ->', OUT)


if __name__ == '__main__':
    main()
