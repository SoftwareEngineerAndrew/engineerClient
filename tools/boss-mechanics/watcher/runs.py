"""Step 2: one timeline per run (its recordings merged) on server ticks -> OUT_DIR/runs.json.

    python3 runs.py DATA_DIR OUT_DIR

Recordings of one run share `group` in DATA_DIR/runs.json. The reference recording is the one
with the most Watcher lines; each sibling is shifted onto its server-tick timeline by the median
difference of the Watcher/door chat lines both recorded (the spread is kept as a check).
Merged per run:
  chat     [[n, t, line]]      Watcher lines ("[BOSS] The Watcher: " stripped), the door line, Maxor's first line
  mobs     [{name, sn, pos, gn, ...}]   blood mobs first seen falling in the middle of the room (sn: server
           tick first seen = spawn; gn: removal, which is death + 20); merged by name, earliest sighting wins
  skulls   {entity id: {slot, skin, pk, gone}}   the wall skulls (armor stands wearing a head in the wall
           niches) with their de-lerped move packets; entity ids are the server's, so siblings merge by id
  watcher  [{id, pk}]          the Watcher's de-lerped packets from every recording
Positions are relative to the room's middle (x - cx, y, z - cz); see wlib.center.
"""
import collections
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wlib as L  # noqa: E402


def mobs_of(x, clk, off):
    cx, cz = L.center(x)
    pg = collections.defaultdict(list)
    for t, nm in x['pgone']:
        pg[nm].append(t)
    out = {}
    for nm, pm in x['pmobs'].items():
        tr = pm['tr']
        if not tr:
            continue
        t0, xx, yy, zz = tr[0][:4]
        # A fresh spawn appears in the air over the middle of the room; one first seen on the floor
        # or elsewhere came into view late and has no usable spawn time.
        if not (abs(xx - cx) < 7 and abs(zz - cz) < 7 and yy > 70.5):
            continue
        base = nm.split('#')[0].strip()
        g = [t for t in pg.get(nm, []) if t > t0]
        out[base] = {'name': base, 'sn': clk.n(t0) + off, 'pos': [round(xx - cx, 3), round(yy, 3), round(zz - cz, 3)],
                     'held': tr[0][4], 'gn': clk.n(g[0]) + off if g else None}
    for i, e in x['ents'].items():
        # The Giant is a real giant; a second, upside-down one ("Dinnerbone") is its sword prop.
        if e['type'] != 'minecraft:giant' or e['name'] == 'Dinnerbone' or e.get('pre'):
            continue
        s = e['ev'][0]
        if not (abs(s[2] - cx) < 7 and abs(s[4] - cz) < 7 and s[3] > 70.5):
            continue
        g = [ev[0] for ev in e['ev'] if ev[1] == 'g']
        out['Giant#' + i] = {'name': 'Giant', 'sn': clk.n(s[0]) + off, 'pos': [round(s[2] - cx, 3), round(s[3], 3), round(s[4] - cz, 3)],
                             'held': '', 'gn': clk.n(g[0]) + off if g else None}
    return out


def skulls_of(x, clk, off, door_n):
    cx, cz = L.center(x)
    dec = L.decimals(x)
    out = {}
    for i, e in x['ents'].items():
        if e['type'] != 'minecraft:armor_stand' or e['name'] or not any(q[1][1] == 'minecraft:player_head' for q in e['eq']):
            continue
        p = e['ev'][0]
        lx, lz = p[2] - cx, p[4] - cz
        if not (p[3] in (71.75, 75.75, 79.75) and 8 < max(abs(lx), abs(lz)) < 13.5):
            continue
        pk = L.delerp(e['ev'], dec)
        g = [ev[0] for ev in e['ev'] if ev[1] == 'g']
        sk = [q[2] for q in e['eq'] if q[2]]
        out[i] = {'slot': [round(lx, 2), p[3], round(lz, 2)], 'skin': sk[0][:12] if sk else '',
                  'pk': [[clk.n(t) + off, round(a - cx, 3), round(b, 3), round(c - cz, 3)] for t, a, b, c, k in pk if k == 'p'],
                  'gone': clk.n(g[0]) + off if g else None, 'src': x['id']}
    return out


def main(data_dir, out_dir):
    info = {r['id']: r for r in L.run_list(data_dir)}
    groups = collections.defaultdict(list)
    for rid in L.extract_ids(out_dir):
        if rid in L.ALPHA_RUNS or rid in L.SUSPECT_ALPHA:
            continue
        x = L.load_extract(out_dir, rid)
        if 'skip' in x:
            continue
        groups[info.get(rid, {}).get('group', rid)].append(x)
    res = []
    for gid, xs in sorted(groups.items()):
        xs.sort(key=lambda x: (-sum(1 for t, m in x['chat'] if m.startswith(L.W)), -len(x['pmobs']), x['id']))
        ref = xs[0]
        cr = L.Clock(ref['st'])
        refl, k = {}, collections.Counter()
        for t, m in ref['chat']:
            if m.startswith(L.W) or m == L.DOOR:
                refl[(m, k[m])] = cr.n(t); k[m] += 1
        door_n = refl.get((L.DOOR, 0))
        recs, mobs, skulls, wat = [], {}, {}, []
        for x in xs:
            clk = L.Clock(x['st'])
            off, spread = 0, [0, 0]
            if x is not ref:
                k2, d = collections.Counter(), []
                for t, m in x['chat']:
                    if m.startswith(L.W) or m == L.DOOR:
                        if (m, k2[m]) in refl:
                            d.append(refl[(m, k2[m])] - clk.n(t))
                        k2[m] += 1
                if not d:
                    continue
                off = L.median(d)
                spread = [min(v - off for v in d), max(v - off for v in d)]
            recs.append({'id': x['id'], 'st': clk.ok, 'off': off, 'spread': spread, 'self': x['meta']['self']})
            for key, m in mobs_of(x, clk, off).items():
                if key.startswith('Giant#'):
                    same = [kk for kk, mm in mobs.items() if mm['name'] == 'Giant' and abs(mm['sn'] - m['sn']) <= 3]
                    key = same[0] if same else key
                o = mobs.get(key)
                if o is None:
                    m['src'] = x['id']; mobs[key] = m
                else:
                    if m['sn'] < o['sn']:
                        o.update(sn=m['sn'], pos=m['pos'], held=m['held'], src=x['id'])
                    if m['gn'] is not None and (o['gn'] is None or m['gn'] < o['gn']):
                        o['gn'] = m['gn']
            for i, s in skulls_of(x, clk, off, door_n).items():
                if i not in skulls or len(s['pk']) > len(skulls[i]['pk']):
                    skulls[i] = s
            cx, cz = L.center(x)
            dec = L.decimals(x)
            for wid in L.watcher_ids(x):
                pk = L.delerp(x['ents'][wid]['ev'], dec)
                wat.append({'id': x['id'], 'pk': [[clk.n(t) + off, round(a - cx, 3), round(b, 3), round(c - cz, 3), kk] for t, a, b, c, kk in pk]})
        chat = [[cr.n(t), t, m.replace(L.W, '')] for t, m in ref['chat'] if m.startswith(L.W) or m in (L.DOOR, L.MAXOR)]
        res.append({'group': gid, 'party': len(info.get(ref['id'], {}).get('party', [])), 'recs': recs,
                    'all_st': all(r['st'] for r in recs), 'rot': ref['blood'][4], 'chat': chat,
                    'mobs': sorted(mobs.values(), key=lambda m: m['sn']), 'skulls': skulls, 'watcher': wat})
    json.dump(res, open(os.path.join(out_dir, 'runs.json'), 'w'))
    print(len(res), 'runs,', sum(1 for r in res if r['all_st']), 'with server ticks in every recording')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
