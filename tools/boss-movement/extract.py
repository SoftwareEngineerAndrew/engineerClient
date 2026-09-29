#!/usr/bin/env python3
"""Step 1: condense each recording to its Maxor/Storm window.

usage: extract.py DATA_DIR OUT_DIR [--jobs N]

DATA_DIR holds runs.json, ids.txt (optional) and runs/<id>.gz. For every recording that has a
Maxor or Storm line, writes OUT_DIR/extract/<id>.json with:
  window [t0, t1]    client ticks, from 60 before Maxor's first line to 60 after Goldor's first
  st                 [[t, n], ...] server tick count when it changed (plus the last one before t0)
  chat               [[t, m], ...] every chat line in the window
  players            {name: [[t, x, y, z, yaw, pitch], ...]} party members only (a player keeps
                     their last entry until the next one)
  pgone              [[t, name], ...]
  tp                 [[t, x, y, z], ...] the recorder's own teleports
  ents               {id: {type, name, ev: [[t, kind, x, y, z, yaw, headYaw], ...]}} withers,
                     the boss name tags, lightning bolts, end crystals ('s' spawn, 'e' move, 'g' gone)
  tags               [[t, stand_id, mob_id or None, dx, dy, dz]] name tag attachments
  blocks             [[t, x, y, z, state], ...] block changes with y 150-200 (Storm's arena)
"""
import json
import os
import sys
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import recording as R  # noqa: E402

KEEP_TYPES = {"minecraft:wither", "minecraft:lightning_bolt", "minecraft:end_crystal"}


def extract(args):
    data_dir, out_dir, rid, meta_row = args
    dst = os.path.join(out_dir, 'extract', rid + '.json')
    src = R.run_path(data_dir, rid)
    if not os.path.exists(src):
        return rid, 'missing'
    if os.path.exists(dst) and os.path.getmtime(dst) >= os.path.getmtime(src):
        return rid, 'cached'
    try:
        L = R.read_lines(src)
    except Exception as ex:  # truncated download etc.
        return rid, 'unreadable: %s' % ex
    meta = L[0]
    t0 = t1 = None
    for l in L:
        if l['k'] != 'chat':
            continue
        m = l['m']
        if t0 is None and (m.startswith('[BOSS] Maxor') or m.startswith('[BOSS] Storm')):
            t0 = l['t'] - 60
        if m == R.GOLDOR_START and t0 is not None and t1 is None:
            t1 = l['t'] + 60
    if t0 is None:
        json.dump({'id': rid, 'skip': 'no Maxor/Storm line'}, open(dst, 'w'))
        return rid, 'no boss'
    if t1 is None:
        t1 = max(l.get('t', 0) for l in L)
    party = []
    pal = {}
    st, chat, players, pgone, tp, ents, tags, blocks = [], [], {}, [], [], {}, [], []
    last_st = None
    boss_stands = set()
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
                    players.setdefault(d[0], []).append([t, d[1], d[2], d[3], d[4], d[5]])
        elif k == 'pgone':
            if l['name'] in party:
                pgone.append([t, l['name']])
        elif k == 'tp':
            tp.append([t, l['x'], l['y'], l['z']])
        elif k == 'spawn':
            name = l.get('name', '')
            is_boss_tag = l['type'] == 'minecraft:armor_stand' and ('Maxor' in name or 'Storm' in name) and '﴾' in name
            if l['type'] in KEEP_TYPES or is_boss_tag:
                if is_boss_tag:
                    boss_stands.add(l['id'])
                e = ents.setdefault(str(l['id']), {'type': l['type'], 'name': name, 'ev': []})
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
        elif k == 'tag':
            if l['id'] in boss_stands:
                tags.append([t, l['id'], l.get('of'), l.get('dx'), l.get('dy'), l.get('dz')])
        elif k == 'block':
            if 150 <= l['y'] <= 200:
                blocks.append([t, l['x'], l['y'], l['z'], pal.get(l['s'], '?')])
    if last_st:
        st.insert(0, last_st)
    out = {
        'id': rid, 'self': meta.get('self'), 'mod': meta.get('mod'), 'farHalf': meta.get('farHalf'),
        'group': meta_row.get('group', rid) if meta_row else rid,
        'cleared': meta_row.get('cleared') if meta_row else None,
        'party': party, 'window': [t0, t1], 'st': st, 'chat': chat, 'players': players,
        'pgone': pgone, 'tp': tp, 'ents': ents, 'tags': tags, 'blocks': blocks,
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
    rows = {r['id']: r for r in R.run_list(data_dir)}
    ids = R.wanted_ids(data_dir)
    work = [(data_dir, out_dir, rid, rows.get(rid)) for rid in ids]
    status = {}
    with Pool(jobs) as pool:
        for rid, s in pool.imap_unordered(extract, work):
            status[rid] = s
    counts = {}
    for s in status.values():
        key = s.split(':')[0]
        counts[key] = counts.get(key, 0) + 1
    print('extract:', counts)
    missing = [r for r, s in status.items() if s == 'missing']
    if missing:
        print('missing recordings (%d): %s' % (len(missing), ' '.join(missing[:10]) + (' ...' if len(missing) > 10 else '')))


if __name__ == '__main__':
    main()
