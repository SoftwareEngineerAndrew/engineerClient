#!/usr/bin/env python3
"""Step 1: condense each recording to its Goldor window (F7 phase 3 + the core).

usage: extract.py DATA_DIR OUT_DIR [--jobs N]

DATA_DIR holds runs.json, ids.txt (optional) and runs/<id>.gz (gzip or xz). For every recording
with Goldor's first line, writes OUT_DIR/extract/<id>.json with:
  window   [t0, t1]  client ticks: 300 before "Who dares trespass" to 100 after Necron's first line
                     (or the end of the recording)
  st       [[t, n], ...] server tick count (Odin's ping) when it changed, plus the last before t0
  chat     [[t, m], ...]
  blocks   [[t, x, y, z, state], ...] every block change in the window
  ui       raw gui / guiclose / slots / slotclick / click / use / tp / sw / frame lines (dicts)
  players  {name: [[t, x, y, z, yaw, pitch, held], ...]} party members (last row before t0 too)
  pgone    [[t, name], ...]
  ents     {id: {type, name, names: [[t, name], ...], ev: [[t, kind, x, y, z, yaw, headYaw], ...]}}
           withers: every event of the whole recording (so de-lerping starts at the spawn);
           everything else except arrows/bats/items/fireworks/bobbers: window events only
           ('s' spawn, 'e' move, 'g' gone)
  tags     [[t, stand_id, mob_id or None, dx, dy, dz]] name tags riding a mob
"""
import json
import os
import sys
from multiprocessing import Pool

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import glib as G  # noqa: E402

SKIP_TYPES = {'minecraft:arrow', 'minecraft:bat', 'minecraft:item', 'minecraft:firework_rocket',
              'minecraft:fishing_bobber', 'minecraft:experience_orb', 'minecraft:snowball',
              'minecraft:ender_pearl', 'minecraft:potion', 'minecraft:small_fireball'}
UI_KINDS = {'gui', 'guiclose', 'slots', 'slotclick', 'click', 'use', 'tp', 'sw', 'frame'}


def extract(args):
    data_dir, out_dir, rid, meta_row = args
    dst = os.path.join(out_dir, 'extract', rid + '.json')
    src = G.run_path(data_dir, rid)
    if not os.path.exists(src):
        return rid, 'missing'
    if os.path.exists(dst) and os.path.getmtime(dst) >= os.path.getmtime(src):
        return rid, 'cached'
    try:
        L = G.read_lines(src)
    except Exception as ex:  # truncated download etc.
        return rid, 'unreadable: %s' % ex
    meta = L[0]
    g = nec = None
    for l in L:
        if l['k'] != 'chat':
            continue
        if g is None and l['m'] == G.GOLDOR_START:
            g = l['t']
        elif g is not None and l['m'].startswith('[BOSS] Necron'):
            nec = l['t']
            break
    if g is None:
        json.dump({'id': rid, 'skip': 'no Goldor line'}, open(dst, 'w'))
        return rid, 'no goldor'
    t0 = g - 300
    t1 = nec + 100 if nec is not None else max(l.get('t', 0) for l in L)
    party, pal = [], {}
    st, chat, blocks, ui, players, pgone, ents, tags = [], [], [], [], {}, [], {}, []
    last_st, last_p = None, {}
    for l in L:
        k = l['k']
        if k == 'pal':
            pal[l['i']] = l['s']
            continue
        if k == 'party':
            party = [m[0] for m in l['m']]
        t = l.get('t')
        if t is None:
            continue
        if t > t1:
            break
        inw = t >= t0
        if k == 'st':
            if inw:
                st.append([t, l['n']])
            else:
                last_st = [t, l['n']]
        elif k == 'spawn':
            ty = l['type']
            if ty == 'minecraft:wither' or (inw and ty not in SKIP_TYPES):
                # an entity coming back into view is spawned again: keep its history
                e = ents.setdefault(str(l['id']), {'type': ty, 'name': l.get('name', ''), 'names': [], 'ev': []})
                e['names'].append([t, l.get('name', '')])
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
                e['names'].append([t, l.get('name', '')])
                e['name'] = l.get('name', '')
        elif k == 'p':
            for d in l['d']:
                if d[0] in party:
                    row = [t, d[1], d[2], d[3], d[4], d[5], d[6] if len(d) > 6 else None]
                    if inw:
                        players.setdefault(d[0], []).append(row)
                    else:
                        last_p[d[0]] = row
        elif not inw:
            continue
        elif k == 'chat':
            chat.append([t, l['m']])
        elif k == 'block':
            blocks.append([t, l['x'], l['y'], l['z'], pal.get(l['s'], '?')])
        elif k in UI_KINDS:
            ui.append(l)
        elif k == 'pgone':
            pgone.append([t, l['name']])
        elif k == 'tag':
            tags.append([t, l['id'], l.get('of'), l.get('dx'), l.get('dy'), l.get('dz')])
    if last_st:
        st.insert(0, last_st)
    for name, row in last_p.items():
        players.setdefault(name, []).insert(0, row)
    # entities that never showed up in the window (withers of earlier phases) are dropped
    ents = {k: v for k, v in ents.items() if any(ev[0] >= t0 for ev in v['ev'])}
    out = {
        'id': rid, 'self': meta.get('self'), 'mod': meta.get('mod'),
        'group': meta_row.get('group', rid) if meta_row else rid,
        'cleared': meta_row.get('cleared') if meta_row else None,
        'party': party, 'window': [t0, t1], 'goldor': g, 'necron': nec,
        'st': st, 'chat': chat, 'blocks': blocks, 'ui': ui, 'players': players,
        'pgone': pgone, 'ents': ents, 'tags': tags,
    }
    tmp = dst + '.tmp'
    json.dump(out, open(tmp, 'w'), separators=(',', ':'))
    os.replace(tmp, dst)
    return rid, 'ok'


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    data_dir, out_dir = sys.argv[1], sys.argv[2]
    jobs = int(sys.argv[sys.argv.index('--jobs') + 1]) if '--jobs' in sys.argv else os.cpu_count() or 2
    os.makedirs(os.path.join(out_dir, 'extract'), exist_ok=True)
    rows = {r['id']: r for r in G.run_list(data_dir)}
    work = [(data_dir, out_dir, rid, rows.get(rid)) for rid in G.wanted_ids(data_dir)]
    counts = {}
    with Pool(jobs) as pool:
        for rid, s in pool.imap_unordered(extract, work):
            key = s.split(':')[0]
            counts[key] = counts.get(key, 0) + 1
    print('extract:', counts)


if __name__ == '__main__':
    main()
