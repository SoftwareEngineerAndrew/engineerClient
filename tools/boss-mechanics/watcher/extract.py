"""Step 1: cut each recording's blood-room window into OUT_DIR/extract/<id>.json.

    python3 extract.py DATA_DIR OUT_DIR [--jobs N] [--force]

Window: 600 client ticks before the Watcher's first line to 40 after Maxor's first line (or 800
after "You have proven yourself", or 2000 after his last line, or the end of the file).
Kept (all client ticks t; the analysis converts to server ticks with `st`):
  st       [[t, n]]                     the whole file's server-tick lines
  chat     [[t, message]]               every chat line in the window
  blood    [x0, z0, x1, z1, rotation]   the Blood room's tiles (Odin's `rooms` line)
  ents     {id: {type, name, ev: [[t, 's'|'e'|'g', x, y, z]], names, eq, tags}}
           non-player entities that were in or next to the blood room (arrows left out). An entity
           that spawned before the window and moves in it is included with its original spawn.
  pmobs    {name: {tr: [[t, x, y, z, held]], eq}}   player-shaped mobs (uuid version 2) in the room
  pgone    [[t, name]]                  player entities leaving
  players  {name: [[t, x, y, z]]}       real players
  blocks   [[t, x, y, z, state]]        block changes in the room and any nether portal block
"""
import base64
import json
import os
import re
import sys
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wlib as L  # noqa: E402

MARGIN = 6  # blocks around the room's tiles that still count as "in the room"


def skin(tex):
    try:
        m = re.findall(r'texture/([0-9a-f]+)', base64.b64decode(tex).decode('utf-8', 'replace'))
        return m[0] if m else ''
    except Exception:
        return ''


def extract(args):
    data_dir, out_dir, rid, force = args
    out = os.path.join(out_dir, 'extract', rid + '.json')
    src = L.run_path(data_dir, rid)
    if not force and os.path.exists(out) and os.path.getmtime(out) > os.path.getmtime(src):
        return rid, 'cached'
    lines = L.read_lines(src)
    meta = lines[0]
    wl = [l['t'] for l in lines if l['k'] == 'chat' and l['m'].startswith(L.W)]
    if not wl:
        json.dump({'id': rid, 'skip': 'no Watcher line'}, open(out, 'w'))
        return rid, 'skip'
    tmax = max(l.get('t', 0) for l in lines)
    mx = [l['t'] for l in lines if l['k'] == 'chat' and l['m'] == L.MAXOR and l['t'] > wl[0]]
    pv = [l['t'] for l in lines if l['k'] == 'chat' and l['m'] == L.PROVEN]
    tA = wl[0] - 600
    tB = min([tmax] + [m + 40 for m in mx[:1]] + [p + 800 for p in pv[:1]] + [wl[-1] + 2000])
    blood = None
    for l in lines:
        if l['k'] == 'rooms':
            for r in l['r']:
                if r[1] == 'BLOOD':
                    tiles = r[5]
                    blood = [-200 + 32 * min(a for a, b in tiles), -200 + 32 * min(b for a, b in tiles),
                             -200 + 32 * max(a for a, b in tiles) + 31, -200 + 32 * max(b for a, b in tiles) + 31, r[3]]

    def inroom(x, z):
        return blood is None or (blood[0] - MARGIN <= x <= blood[2] + 1 + MARGIN and blood[1] - MARGIN <= z <= blood[3] + 1 + MARGIN)

    st, chat, blocks, players, pmobs, pgone = [], [], [], {}, {}, []
    ents, spawn_all, eqs, pal = {}, {}, {}, {}

    def ent_from_spawn(s):
        return {'type': s['type'], 'name': s['name'], 'ev': [[s['t'], 's', s['x'], s['y'], s['z']]], 'names': [], 'eq': []}

    for l in lines:
        k = l['k']
        if k == 'pal':
            pal[l['i']] = l['s']
            continue
        t = l.get('t')
        if k == 'st':
            st.append([t, l['n']])
            continue
        if k == 'spawn':
            spawn_all[l['id']] = l
            if t < tA:
                continue
        if k == 'eq' and 'id' in l:
            eqs.setdefault(l['id'], []).append([t, l['eq'], skin(l['headTex']) if l.get('headTex') else ''])
        if k == 'eq' and 'name' in l and tA <= t <= tB and l['name'] in pmobs:
            pmobs[l['name']]['eq'].append([t, l['eq'], skin(l['headTex']) if l.get('headTex') else ''])
        if t is None or t < tA or t > tB:
            continue
        if k == 'chat':
            chat.append([t, l['m']])
        elif k == 'block':
            s = pal.get(l['s'], str(l['s']))
            if inroom(l['x'], l['z']) or 'portal' in s:
                blocks.append([t, l['x'], l['y'], l['z'], s])
        elif k == 'p':
            for d in l['d']:
                # Hypixel's player-shaped mobs have uuid version 2; newer recorders also key them "name#id".
                if (len(d) > 7 and d[7] == 2) or '#' in d[0]:
                    pmobs.setdefault(d[0], {'tr': [], 'eq': []})['tr'].append([t, d[1], d[2], d[3], d[6]])
                else:
                    players.setdefault(d[0], []).append([t, d[1], d[2], d[3]])
        elif k == 'pgone':
            pgone.append([t, l['name']])
        elif k == 'spawn':
            ents[l['id']] = ent_from_spawn(l)
        elif k in ('e', 'name', 'gone', 'tag'):
            items = [(d[0], d) for d in l['d']] if k == 'e' else [(l['id'], None)]
            for i, d in items:
                e = ents.get(i)
                if e is None and i in spawn_all and k in ('e', 'name'):
                    e = ents[i] = ent_from_spawn(spawn_all[i])
                    e['pre'] = 1
                if e is None:
                    continue
                if k == 'e':
                    e['ev'].append([t, 'e', d[1], d[2], d[3]])
                elif k == 'gone':
                    e['ev'].append([t, 'g'])
                elif k == 'name':
                    e['names'].append([t, l['name']])
                else:
                    e.setdefault('tags', []).append([t, l.get('of')])
    keep = {}
    for i, e in ents.items():
        if e['type'] in ('minecraft:arrow', 'minecraft:experience_orb'):
            continue
        if any(inroom(ev[2], ev[4]) for ev in e['ev'] if len(ev) > 2):
            e['eq'] = eqs.get(i, [])
            keep[i] = e
    pmobs = {n: p for n, p in pmobs.items() if any(inroom(r[1], r[3]) for r in p['tr'])}
    x = {'id': rid, 'meta': {k: meta.get(k) for k in ('self', 'mod', 'startMs', 'farHalf')}, 'tA': tA, 'tB': tB,
         'tmax': tmax, 'blood': blood, 'st': st, 'chat': chat, 'blocks': blocks, 'players': players,
         'pmobs': pmobs, 'pgone': pgone, 'ents': keep}
    json.dump(x, open(out, 'w'), separators=(',', ':'))
    return rid, 'ok'


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    jobs = 4
    if '--jobs' in sys.argv:
        jobs = int(sys.argv[sys.argv.index('--jobs') + 1])
        args = [a for a in args if a != str(jobs)]
    data_dir, out_dir = args[0], args[1]
    os.makedirs(os.path.join(out_dir, 'extract'), exist_ok=True)
    ids = L.recording_ids(data_dir)
    with Pool(jobs) as p:
        res = p.map(extract, [(data_dir, out_dir, i, '--force' in sys.argv) for i in ids], chunksize=1)
    c = {}
    for _, s in res:
        c[s] = c.get(s, 0) + 1
    print(len(res), 'recordings:', c)


if __name__ == '__main__':
    main()
