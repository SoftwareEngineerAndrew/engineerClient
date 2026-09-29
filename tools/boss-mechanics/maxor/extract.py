#!/usr/bin/env python3
"""Step 1: condense each recording to its Maxor window (F7 P1).

usage: extract.py DATA_DIR OUT_DIR [--jobs N]

DATA_DIR holds runs.json, ids.txt (optional) and runs/<id>.gz (see ../../boss-movement/README.md).
Writes OUT_DIR/extract/<id>.json for every recording with Maxor's first line, from 200 client
ticks before it to 100 after Storm's first line (or the end of the recording):

  st       [[t, n]] server tick count (Odin's ping) when it changed, plus the last one before
  chat     [[t, m]] every chat line
  players  {name: [[t, x, y, z, yaw, pitch, heldItemId]]} party members (last entry holds)
  pgone    [[t, name]]; tp [[t, x, y, z]] the recorder's own teleports; use [[t, x, y, z]]
  ents     {id: {type, name, ev: [[t, kind, x, y, z, yaw, headYaw]]}} withers, end crystals and
           the named armor stands of the phase (Maxor's tag, "Energy Crystal", "CLICK HERE",
           "Energy Crystal Missing" / "Crystal Active"); kind 's' spawn, 'e' move, 'g' gone,
           'n' name change (the new name in the x slot)
  dmg      [[t, name, colour name, x, y, z]] damage-number armor stands (name all digits/commas,
           possibly with crit decorations)
  blocks   [[t, x, y, z, state]] block changes in Maxor's arena (x 35-110, y 215-240, z 25-85),
           without the conveyor-belt animation (coal/player heads at y 225-226, z 72-74, x != 73)
Recordings from the alpha server (boss-movement/recording.ALPHA_RUNS) are skipped.
"""
import json
import os
import re
import sys
from multiprocessing import Pool

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', '..', 'boss-movement'))
import recording as R  # noqa: E402

MAXOR_START = R.MAXOR_START
STORM_START = R.STORM_START
STAND_NAMES = ('Crystal', 'CLICK HERE', 'Maxor')
DMG = re.compile(r'^[^0-9a-zA-Z]*[0-9][0-9,]*[^0-9a-zA-Z]*$')


def in_arena(x, y, z):
    return 35 <= x <= 110 and 215 <= y <= 240 and 25 <= z <= 85


def conveyor(x, y, z, state):
    return 225 <= y <= 226 and 72 <= z <= 74 and x != 73 and state.split('[')[0] in (
        'minecraft:coal_block', 'minecraft:air', 'minecraft:player_head')


def extract(args):
    data_dir, out_dir, rid, row = args
    dst = os.path.join(out_dir, 'extract', rid + '.json')
    src = R.run_path(data_dir, rid)
    if not os.path.exists(src):
        return rid, 'missing'
    if os.path.exists(dst) and os.path.getmtime(dst) >= os.path.getmtime(src):
        return rid, 'cached'
    if rid in R.ALPHA_RUNS:
        json.dump({'id': rid, 'skip': 'alpha server'}, open(dst, 'w'))
        return rid, 'alpha'
    try:
        L = R.read_lines(src)
    except Exception as ex:
        return rid, 'unreadable: %s' % ex
    meta = L[0]
    t0 = t1 = None
    for l in L:
        if l['k'] == 'chat':
            if t0 is None and l['m'] == MAXOR_START:
                t0 = l['t'] - 200
            elif t0 is not None and l['m'] == STORM_START:
                t1 = l['t'] + 100
                break
    if t0 is None:
        json.dump({'id': rid, 'skip': 'no Maxor line'}, open(dst, 'w'))
        return rid, 'no maxor'
    if t1 is None:
        t1 = max(l.get('t', 0) for l in L)
    pal, party, classes = {}, [], []
    st, chat, players, pgone, tp, use, ents, dmg, blocks = [], [], {}, [], [], [], {}, [], []
    last_st = None
    for l in L:
        k = l['k']
        if k == 'pal':
            pal[l['i']] = l['s']
            continue
        t = l.get('t')
        if k == 'party' and t is not None and t <= t1:
            party = [m[0] for m in l['m']]
            classes = l['m']
        if t is None:
            continue
        if k == 'st':
            if t < t0:
                last_st = [t, l['n']]
            elif t <= t1:
                st.append([t, l['n']])
            continue
        if t < t0 or t > t1:
            continue
        if k == 'chat':
            chat.append([t, l['m']])
        elif k == 'p':
            for d in l['d']:
                if d[0] in party:
                    players.setdefault(d[0], []).append([t, d[1], d[2], d[3], d[4], d[5], d[6] if len(d) > 6 else None])
        elif k == 'pgone':
            pgone.append([t, l['name']])
        elif k == 'tp':
            tp.append([t, l['x'], l['y'], l['z']])
        elif k == 'use':
            use.append([t, l['x'], l['y'], l['z']])
        elif k == 'spawn':
            name = l.get('name', '') or ''
            typ = l['type']
            keep = typ in ('minecraft:wither', 'minecraft:end_crystal') or (
                typ == 'minecraft:armor_stand' and any(s in name for s in STAND_NAMES))
            if typ == 'minecraft:armor_stand' and name and DMG.match(name):
                dmg.append([t, name, l.get('c', ''), l['x'], l['y'], l['z']])
            elif keep:
                e = ents.setdefault(str(l['id']), {'type': typ, 'name': name, 'ev': []})
                e['ev'].append([t, 's', l['x'], l['y'], l['z'], l.get('yaw'), l.get('headYaw')])
        elif k == 'e':
            for d in l['d']:
                e = ents.get(str(d[0]))
                if e is not None:
                    e['ev'].append([t, 'e', d[1], d[2], d[3], d[4], d[5] if len(d) > 5 else None])
        elif k == 'gone':
            e = ents.get(str(l['id']))
            if e is not None:
                e['ev'].append([t, 'g', None, None, None, None, None])
        elif k == 'name':
            e = ents.get(str(l['id']))
            if e is not None:
                e['ev'].append([t, 'n', l.get('name', ''), None, None, None, None])
        elif k == 'block':
            s = pal.get(l['s'], '?')
            if in_arena(l['x'], l['y'], l['z']) and not conveyor(l['x'], l['y'], l['z'], s):
                blocks.append([t, l['x'], l['y'], l['z'], s])
    if last_st:
        st.insert(0, last_st)
    out = {'id': rid, 'self': meta.get('self'), 'mod': meta.get('mod'),
           'group': (row or {}).get('group', rid), 'party': party, 'classes': classes,
           'window': [t0, t1], 'st': st, 'chat': chat, 'players': players, 'pgone': pgone,
           'tp': tp, 'use': use, 'ents': ents, 'dmg': dmg, 'blocks': blocks}
    tmp = dst + '.tmp'
    json.dump(out, open(tmp, 'w'), separators=(',', ':'))
    os.replace(tmp, dst)
    return rid, 'ok'


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    data_dir, out_dir = sys.argv[1], sys.argv[2]
    jobs = int(sys.argv[sys.argv.index('--jobs') + 1]) if '--jobs' in sys.argv else (os.cpu_count() or 2)
    os.makedirs(os.path.join(out_dir, 'extract'), exist_ok=True)
    rows = {r['id']: r for r in R.run_list(data_dir)}
    work = [(data_dir, out_dir, rid, rows.get(rid)) for rid in R.wanted_ids(data_dir)]
    status = {}
    with Pool(jobs) as pool:
        for rid, s in pool.imap_unordered(extract, work):
            status[rid] = s
    c = {}
    for s in status.values():
        c[s.split(':')[0]] = c.get(s.split(':')[0], 0) + 1
    print('extract:', c)


if __name__ == '__main__':
    main()
