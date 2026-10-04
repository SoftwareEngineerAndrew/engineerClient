"""For every shot of one bow ($TERROR_BOW, default MOSQUITO_BOW): every arrow spawned within -1..+8 ticks of it within 3 blocks of the
main arrow's spawn point (whatever its owner), with its spawn, every server velocity / position packet
for its first 10 ticks, its entity data, and when it was removed. Enough to rebuild each arrow's flight:
when it launches, from where, at what speed and direction. -> out/trajectories.jsonl
"""
import gzip, json, math, os, re, sys, bisect, collections
from concurrent.futures import ProcessPoolExecutor

from tlib import R, OUT  # noqa: E402
BOW = os.environ.get('TERROR_BOW', 'MOSQUITO_BOW')
STRIP = re.compile(r'§.')
WANT = (b'"k":"inv"', b'"k":"me"', b'"k":"meta"', b'"k":"world"', b'use_item', b'"minecraft:swing"', b'move_player',
        b'minecraft:cooldown', b'"overlay":true', b'"minecraft:arrow"', b'set_entity_motion', b'entity_position_sync',
        b'teleport_entity', b'set_entity_data', b'remove_entities', b'move_entity')


def one(rec):
    d = os.path.join(R, rec)
    lines = []
    for part in sorted(f for f in os.listdir(d) if f.endswith('.jsonl.gz')):
        try:
            with gzip.open(os.path.join(d, part), 'rb') as f:
                for line in f:
                    if any(w in line for w in WANT):
                        try: lines.append(json.loads(line))
                        except ValueError: pass
        except (EOFError, OSError):
            pass
    lines.sort(key=lambda o: o.get('seq', 0))
    self_id = None; slots = {}; sel = 0; look = None; me = None
    srv = {'pos': None, 'rot': None, 'shift': False, 'ground': None, 'n': None}   # the server's view of p3wr: last sent position / rotation (sneak: use 'me'.crouch, player_input shift stays false)
    arrows = {}; order = []; triggers = []; cools = []; bars = []; rots = []
    for o in lines:
        k = o['k']; t = o.get('t'); seq = o.get('seq', 0)
        if k in ('meta', 'world'): self_id = o.get('selfId', self_id)
        elif k == 'inv':
            sel = o.get('sel', sel)
            for s, item in o.get('s', []): slots[s] = (item or {}).get('sb')
        elif k == 'me':
            sel = o.get('slot', sel)
            me = {'pos': o.get('pos'), 'crouch': o.get('crouch'), 'vel': o.get('vel')}
        elif k == 'out':
            p = o.get('p', ''); f = o.get('f', {})
            if p.startswith('minecraft:move_player'):
                if f.get('hasPos'): srv = dict(srv, pos=[f['x'], f['y'], f['z']], n=o.get('n'))
                if f.get('hasRot'): srv = dict(srv, rot=[f['yRot'], f['xRot']])
                srv = dict(srv, ground=f.get('onGround'))
            if p == 'minecraft:player_input':
                srv = dict(srv, shift=((f.get('input') or {}).get('shift')))
            if p.startswith('minecraft:move_player') and f.get('yRot') is not None and 'Rot' in f.get('@c', ''):
                look = [f['yRot'], f['xRot']]; rots.append((o.get('n'), seq, look))
            elif p == 'minecraft:use_item':
                rots.append((o.get('n'), seq, [f.get('yRot'), f.get('xRot')]))
                look = [f.get('yRot'), f.get('xRot')]; triggers.append((t, seq, 'use_item', look, o.get('n')))
            elif p == 'minecraft:swing':
                triggers.append((t, seq, 'swing', look, o.get('n')))
        elif k == 'in':
            p = o.get('p', ''); f = o.get('f', {})
            eid = f.get('id', f.get('entityId'))
            if p == 'minecraft:add_entity' and f.get('type') == 'minecraft:arrow':
                arrows[f['id']] = {'id': f['id'], 't': t, 'n': o.get('n'), 'seq': seq, 'owner': f.get('data'), 'spawn': [f['x'], f['y'], f['z']],
                                   'v0': f.get('movement'), 'look': look, 'me': me, 'srv': srv, 'held': slots.get(sel),
                                   'worn': sorted(slots.get(i) for i in (36, 37, 38, 39) if 'TERROR' in (slots.get(i) or '')), 'upd': [], 'data': None}
                order.append(f['id'])
            elif eid in arrows and t - arrows[eid]['t'] <= 10:
                a = arrows[eid]
                if p == 'minecraft:set_entity_motion': a['upd'].append([t - a['t'], 'v', f.get('movement'), o.get('n') - a['n']])
                elif p == 'minecraft:entity_position_sync': a['upd'].append([t - a['t'], 'sync', o.get('abs'), o.get('n') - a['n']])
                elif p == 'minecraft:teleport_entity': a['upd'].append([t - a['t'], 'tp', o.get('abs')])
                elif p.startswith('minecraft:move_entity'): a['upd'].append([t - a['t'], 'move', o.get('abs')])
                elif p == 'minecraft:set_entity_data':
                    vals = {str(x[0]): x[2] for x in f.get('packedItems', [])}
                    if a['data'] is None: a['data'] = vals
                    else: a['upd'].append([t - a['t'], 'data', vals])
            elif p == 'minecraft:remove_entities':
                for i in f.get('entityIds', []):
                    if i in arrows and 'gone' not in arrows[i]: arrows[i]['gone'] = t - arrows[i]['t']
            elif p == 'minecraft:cooldown':
                cools.append((t, seq, f.get('duration')))
            elif p == 'minecraft:system_chat' and f.get('overlay'):
                m = re.search(r'(\d+)⁑', STRIP.sub('', (f.get('content') or {}).get('t', '')))
                bars.append((seq, int(m.group(1)) if m else 0))
    if self_id is None:
        return rec, []
    bar_seq = [b[0] for b in bars]
    out = []
    for aid in order:
        a = arrows[aid]
        if a['owner'] != self_id or a['held'] != BOW:
            continue
        near = [arrows[x] for x in order if x != aid and -1 <= arrows[x]['t'] - a['t'] <= 8 and math.dist(arrows[x]['spawn'], a['spawn']) <= 3]
        bi = bisect.bisect_left(bar_seq, a['seq'])
        trig = next(((tt, kind, rot, tn) for tt, ts, kind, rot, tn in reversed(triggers) if ts < a['seq'] and a['t'] - tt <= 5), None)

        def rec_(x):
            return {'dt': x['t'] - a['t'], 'dn': x['n'] - a['n'], 'own': 'self' if x['owner'] == self_id else ('own-id' if x['owner'] == x['id'] else x['owner']),
                    'id': x['id'], 'spawn': x['spawn'], 'v0': x['v0'], 'data': x['data'], 'upd': x['upd'], 'gone': x.get('gone'), 'look': x['look'], 'me': x['me'], 'srv': x['srv']}
        out.append({'rec': rec, 't': a['t'], 'n': a['n'], 'stacks': bars[bi - 1][1] if bi > 0 else None, 'worn': a['worn'],
                    'trigger': None if trig is None else {'kind': trig[1], 'dt': a['t'] - trig[0], 'dn': a['n'] - trig[3], 'rot': trig[2]},
                    'clicks': [[tn, kind, rot] for tt, ts, kind, rot, tn in triggers if ts < a['seq'] and 0 <= a['n'] - tn <= 10],
                    'rots': [[rn, rr] for rn, rs, rr in rots if rs < a['seq'] and 0 <= a['n'] - rn <= 10],
                    'cooldown': [c[2] for c in cools if 0 <= c[1] - a['seq'] and c[0] - a['t'] <= 2],
                    'main': rec_(a), 'near': [rec_(x) for x in near]})
    return rec, out


if __name__ == '__main__':
    recs = sorted({v['rec'] for v in (json.loads(l) for l in open(os.path.join(OUT, 'volleys.jsonl'))) if v['held'] == BOW})
    n = 0
    name = 'trajectories.jsonl' if BOW == 'MOSQUITO_BOW' else 'trajectories_%s.jsonl' % BOW
    with open(os.path.join(OUT, name), 'w') as f, ProcessPoolExecutor(12) as ex:
        for rec, rows in ex.map(one, recs, chunksize=1):
            for r in rows: f.write(json.dumps(r) + '\n'); n += 1
    print(BOW, 'shots with trajectories:', n)
