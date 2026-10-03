"""Shared loader for the device research scripts: Boss Recorder files -> chat, block changes, entities."""
import glob, gzip, json, os
DIR = os.path.expanduser('~/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/bossrecorder')

def files():
    return sorted(glob.glob(os.path.join(DIR, '*.jsonl.gz')))

def load(path):
    pal, chat, blocks, ents, meta = {}, [], [], [], {}
    with gzip.open(path, 'rt') as f:
        for line in f:
            try: l = json.loads(line)
            except Exception: continue
            k = l.get('k')
            if k == 'pal': pal[l['i']] = l['s']
            elif k == 'meta': meta = l
            elif k == 'chat': chat.append((l.get('n'), l['m']))
            elif k == 'net':
                for e in l['d']:
                    if e[1] == 'b': blocks.append((e[0], e[2], e[3], e[4], e[5]))
                    else: ents.append((e[0], e[1], e[2:]))
    blocks = [(n, x, y, z, pal.get(p, p)) for n, x, y, z, p in blocks]
    return meta, chat, blocks, ents

def inbox(b, x0, x1, y0, y1, z0, z1):
    return [r for r in b if x0 <= r[1] <= x1 and y0 <= r[2] <= y1 and z0 <= r[3] <= z1]
