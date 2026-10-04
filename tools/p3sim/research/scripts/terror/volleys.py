"""Every bow shot p3wr fired in the Dungeon Recorder recordings, arrow by arrow, for an exact replica of
the Mosquito Shortbow and Terror armor's Hydra Strike in P3 Sim. -> out/volleys.jsonl

How Hypixel sends a shot: see terror-mosquito.md. In short, the main arrow is spawned owned by p3wr; the extra
arrows (Hydra Strike, Terminator side arrows) are spawned with no owner (the packet's owner field is their own id)
and no velocity, and launched on the next tick with a velocity and an exact position packet. Duplex arrows come
3-5 ticks later (trajectories.py keeps those). For each arrow this keeps: spawn point and velocity, entity data,
and every motion / position-sync / teleport / data packet for its first 6 ticks.

Per shot: the bow held, Terror pieces worn, the packet that fired it (use_item with the rotation it
carried, or a swing with the last rotation sent), p3wr's position and eye, Hydra Strike stacks, mana and
vitality from the action bar before and after, the bow cooldown, and every sound and particle packet
within 2 blocks of him on the shot tick and the next.
"""
import gzip, json, math, os, re, sys, bisect, collections
from concurrent.futures import ProcessPoolExecutor


from tlib import R, OUT  # noqa: E402
BOWS = ('MOSQUITO_BOW', 'TERMINATOR', 'JUJU_SHORTBOW', 'ITEM_SPIRIT_BOW', 'ARTISANAL_SHORTBOW')
STRIP = re.compile(r'§.')
WANT = (b'"k":"inv"', b'"k":"me"', b'"k":"meta"', b'"k":"world"', b'use_item', b'"minecraft:swing"', b'move_player',
        b'minecraft:cooldown', b'"overlay":true', b'minecraft:sound', b'level_particles',
        b'"minecraft:arrow"', b'set_entity_motion', b'entity_position_sync', b'teleport_entity', b'set_entity_data',
        b'remove_entities', b'move_entity')


def bar(text):
    t = STRIP.sub('', text)
    out = {}
    m = re.search(r'(\d+)⁑', t); out['stacks'] = int(m.group(1)) if m else 0
    m = re.search(r'([\d,]+)/([\d,]+)', t)
    if m: out['mana'] = [int(m.group(1).replace(',', '')), int(m.group(2).replace(',', ''))]
    m = re.search(r'([\d,]+)/([\d,]+)', t)
    if m: out['vit'] = [int(m.group(1).replace(',', '')), int(m.group(2).replace(',', ''))]
    m = re.search(r'-(\d+) Mana \(([^)]*)\)', t)
    if m: out['spent'] = [int(m.group(1)), m.group(2)]
    return out


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
    self_id = None; slots = {}; sel = 0
    look = None; me = None
    arrows = {}       # id -> arrow record
    order = []        # arrow ids in spawn order
    triggers, cools, bars, sounds, parts = [], [], [], [], []
    for o in lines:
        k = o['k']; t = o.get('t'); seq = o.get('seq', 0)
        if k in ('meta', 'world'):
            self_id = o.get('selfId', self_id)
        elif k == 'inv':
            sel = o.get('sel', sel)
            for s, item in o.get('s', []): slots[s] = (item or {}).get('sb')
        elif k == 'me':
            sel = o.get('slot', sel)
            me = {'pos': o.get('pos'), 'crouch': o.get('crouch'), 'pose': o.get('pose'), 'rot': (o.get('rot') or [0, 0])[:2], 'vel': o.get('vel'), 'ground': o.get('ground')}
        elif k == 'out':
            p = o.get('p', ''); f = o.get('f', {})
            if p.startswith('minecraft:move_player') and f.get('yRot') is not None and 'Rot' in f.get('@c', ''):
                look = [f['yRot'], f['xRot']]
            elif p == 'minecraft:use_item':
                look = [f.get('yRot'), f.get('xRot')]
                triggers.append({'t': t, 'seq': seq, 'kind': 'use_item', 'rot': look, 'held': slots.get(sel)})
            elif p == 'minecraft:swing':
                triggers.append({'t': t, 'seq': seq, 'kind': 'swing', 'rot': look, 'held': slots.get(sel)})
        elif k == 'in':
            p = o.get('p', ''); f = o.get('f', {})
            eid = f.get('id', f.get('entityId'))
            if p == 'minecraft:add_entity' and f.get('type') == 'minecraft:arrow':
                arrows[f['id']] = {'id': f['id'], 't': t, 'n': o.get('n'), 'seq': seq, 'owner': f.get('data'),
                                   'spawn': [f['x'], f['y'], f['z']], 'v0': f.get('movement'), 'rot0': [f.get('yRot'), f.get('xRot')],
                                   'deg': f.get('deg'), 'look': look, 'me': me, 'held': slots.get(sel),
                                   'worn': sorted(slots.get(i) for i in (36, 37, 38, 39) if 'TERROR' in (slots.get(i) or '')),
                                   'upd': [], 'data': None}
                order.append(f['id'])
            elif eid in arrows and t - arrows[eid]['t'] <= 6:
                a = arrows[eid]
                if p == 'minecraft:set_entity_motion':
                    a['upd'].append([t - a['t'], 'v', f.get('movement')])
                elif p == 'minecraft:entity_position_sync':
                    a['upd'].append([t - a['t'], 'sync', o.get('abs') or (f.get('values') or {}).get('position')])
                elif p == 'minecraft:teleport_entity':
                    a['upd'].append([t - a['t'], 'tp', o.get('abs') or f])
                elif p.startswith('minecraft:move_entity'):
                    a['upd'].append([t - a['t'], 'move', o.get('abs')])
                elif p == 'minecraft:set_entity_data':
                    vals = {str(x[0]): x[2] for x in f.get('packedItems', [])}
                    if a['data'] is None: a['data'] = vals
                    else: a['upd'].append([t - a['t'], 'data', vals])
            elif p == 'minecraft:remove_entities':
                for i in f.get('entityIds', []):
                    if i in arrows and 'gone' not in arrows[i]: arrows[i]['gone'] = t - arrows[i]['t']
            elif p == 'minecraft:cooldown':
                cools.append({'t': t, 'seq': seq, 'group': f.get('cooldownGroup'), 'ticks': f.get('duration')})
            elif p == 'minecraft:system_chat' and f.get('overlay'):
                bars.append((seq, t, bar((f.get('content') or {}).get('t', ''))))
            elif p == 'minecraft:sound':
                sounds.append({'t': t, 'seq': seq, 'sound': f.get('sound'), 'pitch': f.get('pitch'), 'volume': f.get('volume'), 'at': [f.get('x'), f.get('y'), f.get('z')]})
            elif p == 'minecraft:level_particles':
                parts.append({'t': t, 'seq': seq, 'type': (f.get('particle') or {}).get('type') if isinstance(f.get('particle'), dict) else str(f.get('particle'))[:40],
                              'at': [f.get('x'), f.get('y'), f.get('z')], 'count': f.get('count'), 'speed': f.get('maxSpeed'), 'spread': [f.get('xDist'), f.get('yDist'), f.get('zDist')]})
    if self_id is None:
        return rec, []
    bar_seq = [b[0] for b in bars]

    def eye(me_):
        if not me_ or not me_.get('pos'): return None
        h = 1.27 if me_.get('crouch') else 1.62
        return [me_['pos'][0], me_['pos'][1] + h, me_['pos'][2]]

    # a shot: arrows spawned on one tick at (nearly) one point, near p3wr's eye, with at least one owned by him
    used = set(); shots = []
    for aid in order:
        a = arrows[aid]
        if aid in used or a['owner'] != self_id or a['held'] not in BOWS:
            continue
        e = eye(a['me'])
        group = [x for x in order if x not in used and abs(arrows[x]['t'] - a['t']) <= 0 and math.dist(arrows[x]['spawn'], a['spawn']) < 0.3
                 and (arrows[x]['owner'] == self_id or arrows[x]['owner'] == x)]
        used.update(group)
        bi = bisect.bisect_left(bar_seq, a['seq'])
        after = next((b[2] for b in bars[bi:] if b[1] - a['t'] >= 2), None)
        trig = next((tr for tr in reversed(triggers) if tr['seq'] < a['seq'] and a['t'] - tr['t'] <= 5), None)
        near = lambda at: e is not None and at[0] is not None and math.dist(at, e) <= 3
        shots.append({
            'rec': rec, 't': a['t'], 'n': a['n'], 'held': a['held'], 'worn': a['worn'],
            'trigger': None if trig is None else {'kind': trig['kind'], 'dt': a['t'] - trig['t'], 'rot': trig['rot']},
            'look_at_spawn': a['look'], 'me': a['me'], 'eye': e,
            'bar_before': bars[bi - 1][2] if bi > 0 else None, 'bar_after': after,
            'cooldown': [c for c in cools if 0 <= c['t'] - a['t'] <= 2],
            'sounds': [dict(s, dt=s['t'] - a['t']) for s in sounds if 0 <= s['t'] - a['t'] <= 1 and near(s['at'])],
            'particles': [dict(q, dt=q['t'] - a['t']) for q in parts if 0 <= q['t'] - a['t'] <= 1 and near(q['at'])],
            'arrows': [{'role': 'main' if arrows[x]['owner'] == self_id else 'extra', 'id': x, 'spawn': arrows[x]['spawn'], 'v0': arrows[x]['v0'],
                        'rot0': arrows[x]['rot0'], 'data': arrows[x]['data'], 'upd': arrows[x]['upd'], 'gone': arrows[x].get('gone')} for x in group],
        })
    return rec, shots


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    recs = sorted(x for x in os.listdir(R) if os.path.isdir(os.path.join(R, x)) and not x.startswith('.'))
    n = collections.Counter()
    with open(os.path.join(OUT, 'volleys.jsonl'), 'w') as f, ProcessPoolExecutor(12) as ex:
        for rec, shots in ex.map(one, recs, chunksize=1):
            for s in shots:
                f.write(json.dumps(s) + '\n'); n[s['held']] += 1
    print(dict(n))
