"""Scan Boss Recorder files for velocity packets on yourself and their context.

usage: python3 kb_scan.py [summary|events] [files...]
Writes nothing; prints. Each self-`v` event gets: preceding self-owned entity spawns (`a` with data==selfId)
in the last 40 server ticks, explosions (`ex`) and sounds (`snd`) within +-3 ticks, own last `me` position.
"""
import gzip, json, sys, glob, math
from collections import Counter, defaultdict

DIR = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/bossrecorder/'


def load(f):
    meta = None
    rows = []  # (n, ms, entry)
    try:
        with gzip.open(f, 'rt') as fh:
            for l in fh:
                try:
                    o = json.loads(l)
                except Exception:
                    break
                k = o['k']
                if k == 'meta':
                    meta = o
                elif k == 'net':
                    for d in o['d']:
                        rows.append((d[0], o.get('ms'), d))
                elif k == 'chat':
                    rows.append((o['n'], None, [o['n'], 'chat', o['m']]))
    except EOFError:
        pass
    return meta, rows


def events(f):
    meta, rows = load(f)
    if not meta:
        return meta, []
    me = meta['selfId']
    owned = {}  # id -> spawn entry
    out = []
    lastme = [None, None, None, None, None]
    recent_snd = []
    for idx, (n, ms, d) in enumerate(rows):
        kind = d[1]
        if kind == 'me':
            for i in range(5):
                if d[2 + i] is not None:
                    lastme[i] = d[2 + i]
        elif kind == 'a' and d[13] == me:
            owned[d[2]] = d
        elif kind == 'v' and d[2] == me:
            ctx = {'n': n, 'ms': ms, 'v': d[3:6], 'pos': list(lastme), 'file': f.split('/')[-1]}
            ctx['spawns'] = [(o[0] - n, o[3], o[2]) for o in owned.values() if n - 40 <= o[0] <= n]
            ctx['ex'] = [(r[2][0] - n, r[2][2:]) for r in rows[max(0, idx - 400): idx + 400]
                         if r[2][1] == 'ex' and abs(r[2][0] - n) <= 3]
            ctx['snd'] = [(r[2][0] - n, r[2][2], r[2][8]) for r in rows[max(0, idx - 400): idx + 400]
                          if r[2][1] == 'snd' and abs(r[2][0] - n) <= 2 and lastme[0] is not None
                          and abs(r[2][4] - lastme[0]) + abs(r[2][6] - lastme[2]) < 12]
            out.append(ctx)
    return meta, out


if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else 'summary'
    files = sys.argv[2:] or sorted(glob.glob(DIR + '*.jsonl.gz'))
    if mode == 'summary':
        types = Counter(); snds = Counter(); nv = 0
        for f in files:
            meta, ev = events(f)
            for e in ev:
                nv += 1
                for s in e['spawns']:
                    types[s[1]] += 1
                for s in e['snd']:
                    snds[s[1]] += 1
        print('self v', nv); print(types.most_common(20)); print(snds.most_common(40))
    else:
        for f in files:
            meta, ev = events(f)
            for e in ev:
                print(json.dumps(e))
