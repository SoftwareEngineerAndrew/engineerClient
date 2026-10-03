"""Track the projectiles you fire (entities spawned within 2.5 blocks of your eye) tick by tick.

usage: python3 kb_proj.py [files...] > /tmp/kb_proj.jsonl
Per projectile: type, spawn tick n, spawn pos, your eye/yaw/pitch (last `me` before the spawn, and the
`me` sent on the spawn tick), held item (Better PF, by ms), every position packet until removal, any
entity spawned within 1.5 blocks of its last position on its removal tick (+-1), the `v` on you in the
6 ticks after its removal, sounds at its spawn and at its removal.
"""
import json, glob, sys, os, math, bisect
sys.path.insert(0, os.path.dirname(__file__))
from kb_scan import load, DIR
from kb_join import bpf_runs, held_timeline, held_at

TYPES = None  # all types


def main(files):
    runs = bpf_runs()
    tlcache = {}
    for f in files:
        meta, rows = load(f)
        if not meta:
            continue
        me = meta['selfId']; name = meta['self']
        if name not in tlcache:
            tl = []
            for s, nm, p in runs:
                if nm == name:
                    tl += held_timeline(p, name)
            tl.sort(); tlcache[name] = tl
        tl = tlcache[name]
        lastme = [None] * 5
        mehist = []  # (n, x,y,z,yaw,pitch)
        live = {}
        done = []
        for idx, (n, ms, d) in enumerate(rows):
            k = d[1]
            if k == 'me':
                for i in range(5):
                    if d[2 + i] is not None:
                        lastme[i] = d[2 + i]
                mehist.append((n, *lastme))
            elif k == 'a':
                if lastme[0] is not None and d[3] != 'minecraft:player':
                    ex, ey, ez = lastme[0], lastme[1] + 1.62, lastme[2]
                    if math.dist((d[4], d[5], d[6]), (ex, ey, ez)) < 2.5:
                        h = held_at(tl, ms) if ms and tl else None
                        live[d[2]] = {'file': f.split('/')[-1], 'id': d[2], 'type': d[3], 'n': n, 'ms': ms,
                                      'spawn': d[4:10], 'data': d[13], 'me': list(lastme),
                                      'held': h[1] if h else None, 'moves': [], 'idx': idx}
                for p in live.values():
                    if 'rem' in p and abs(n - p['rem']) <= 1 and p.get('pos') and math.dist(d[4:7], p['pos']) < 1.5:
                        p.setdefault('burst', []).append((n - p['rem'], d[3], d[4:7]))
            elif k in ('m', 'tp', 'sy') and d[2] in live and d[3] is not None:
                p = live[d[2]]
                p['moves'].append((n - p['n'], k, d[3:6])); p['pos'] = d[3:6]
            elif k == 'v' and d[2] in live:
                live[d[2]]['moves'].append((n - live[d[2]]['n'], 'v', d[3:6]))
            elif k == 'r':
                for i in d[2]:
                    if i in live and 'rem' not in live[i]:
                        live[i]['rem'] = n
                        live[i].setdefault('pos', live[i]['spawn'][:3])
            elif k == 'v' and d[2] == me:
                for p in live.values():
                    if n >= p['n'] and ('rem' not in p or n - p['rem'] <= 6):
                        p.setdefault('selfv', []).append((n - p['n'], d[3:6], list(lastme)))
            # retire
            for i in [i for i, p in live.items() if 'rem' in p and n - p['rem'] > 6]:
                p = live.pop(i)
                p['mesince'] = [m for m in mehist if p['n'] - 1 <= m[0] <= p['rem'] + 3]
                done.append(p)
        for p in done:
            p.pop('idx', None)
            print(json.dumps(p))


main(sys.argv[1:] or sorted(glob.glob(DIR + '*.jsonl.gz')))
