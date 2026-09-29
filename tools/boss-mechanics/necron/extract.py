"""Step 1: cut each recording's Necron window into OUT_DIR/extract/<id>.json.

    python3 extract.py DATA_DIR OUT_DIR [--jobs N]

The window runs from 300 client ticks before Necron's first line to 400 after "All this, for
nothing..." (or the end of the file). Every line in it gets `n`, the server tick of the last `st`
line before it in file order (None when the recording has no `st` lines). Kept:
  st      [[t, n]]
  chat    [[t, n, m]]            every chat line
  ents    {id: {type, name, ev: [[t, n, kind, x, y, z, yaw, headYaw]]}}  kind s=spawn e=move g=gone
          (players' own entries are in `players`; armor stands keep spawn/name only)
  names   [[t, n, id, name]]     name changes
  blocks  [[t, n, x, y, z, state]]  except the sea-lantern/iron-block light show (counted per tick in `lights`)
  players {name: [[t, n, x, y, z, yaw, pitch, held]]}
  tp      [[t, n, x, y, z]]      the recorder's own teleports
  sw      [[t, n, name]]         arm swings
"""
import json
import os
import sys
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import necronlib as N  # noqa: E402

PRE, POST = 300, 400
LIGHTS = ('minecraft:sea_lantern', 'minecraft:iron_block')


def extract(args):
    data_dir, out_dir, rid = args
    src = N.R.run_path(data_dir, rid)
    dst = os.path.join(out_dir, 'extract', rid + '.json')
    if os.path.exists(dst) and os.path.getmtime(dst) > os.path.getmtime(src):
        return rid, 'cached'
    L = N.R.read_lines(src)
    start = end = None
    for l in L:
        if l['k'] == 'chat':
            if start is None and l['m'] in N.START:
                start = l['t']
            elif start is not None and l['m'] == N.DEAD and end is None:
                end = l['t']
    meta = L[0]
    info = {r['id']: r for r in N.R.run_list(data_dir)}.get(rid, {})
    x = {'id': rid, 'group': info.get('group', rid), 'self': meta.get('self'), 'party': info.get('party'),
         'start_t': start, 'end_t': end}
    if start is None:
        x['skip'] = 'no Necron'
        json.dump(x, open(dst, 'w'))
        return rid, 'skip'
    t0, t1 = start - PRE, (end if end is not None else 10 ** 9) + POST
    pal = {}
    n = None
    has_st = any(l['k'] == 'st' for l in L)
    st, chat, names, blocks, tp, sw = [], [], [], [], [], []
    ents, players, lights = {}, {}, {}
    for l in L:
        k = l['k']
        if k == 'pal':
            pal[l['i']] = l['s']
            continue
        if k == 'st':
            n = l['n']
        t = l.get('t')
        if t is None or t < t0 or t > t1:
            continue
        nn = n if has_st else None
        if k == 'st':
            st.append([t, l['n']])
        elif k == 'chat':
            chat.append([t, nn, l['m']])
        elif k == 'spawn':
            e = ents.setdefault(str(l['id']), {'type': l['type'], 'name': l.get('name', ''), 'ev': []})
            e['type'] = l['type']
            if l.get('name'):
                e['name'] = l['name']
            e['ev'].append([t, nn, 's', l['x'], l['y'], l['z'], l.get('yaw'), l.get('headYaw')])
        elif k == 'e':
            for d in l['d']:
                e = ents.get(str(d[0]))
                if e is None or e['type'] == 'minecraft:armor_stand':
                    continue
                e['ev'].append([t, nn, 'e', d[1], d[2], d[3], d[4], d[5] if len(d) > 5 else None])
        elif k == 'gone':
            e = ents.get(str(l['id']))
            if e is not None:
                e['ev'].append([t, nn, 'g', None, None, None, None, None])
        elif k == 'name':
            names.append([t, nn, l['id'], l['name']])
            e = ents.get(str(l['id']))
            if e is not None and l['name']:
                e['name'] = l['name']
        elif k == 'block':
            s = pal.get(l['s'], '?')
            if s.split('[')[0] in LIGHTS:
                lights[t] = lights.get(t, 0) + 1
            else:
                blocks.append([t, nn, l['x'], l['y'], l['z'], s])
        elif k == 'p':
            for d in l['d']:
                players.setdefault(d[0], []).append([t, nn, d[1], d[2], d[3], d[4], d[5], d[6] if len(d) > 6 else None])
        elif k == 'tp':
            tp.append([t, nn, l['x'], l['y'], l['z']])
        elif k == 'sw':
            for name in l['d']:
                sw.append([t, nn, name])
    x.update(has_st=has_st, st=st, chat=chat, ents=ents, names=names, blocks=blocks,
             lights=sorted(lights.items()), players=players, tp=tp, sw=sw)
    json.dump(x, open(dst, 'w'), separators=(',', ':'))
    return rid, 'ok'


def main():
    args = sys.argv[1:]
    jobs = 4
    if '--jobs' in args:
        i = args.index('--jobs')
        jobs = int(args[i + 1])
        del args[i:i + 2]
    data_dir, out_dir = args[0], args[1]
    os.makedirs(os.path.join(out_dir, 'extract'), exist_ok=True)
    ids = [i for i in N.R.wanted_ids(data_dir) if os.path.exists(N.R.run_path(data_dir, i))]
    with Pool(jobs) as p:
        res = p.map(extract, [(data_dir, out_dir, i) for i in ids], chunksize=1)
    c = {}
    for _, s in res:
        c[s] = c.get(s, 0) + 1
    print(len(res), 'recordings:', c)


if __name__ == '__main__':
    main()
