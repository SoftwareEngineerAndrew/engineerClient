"""Hydra Strike stack timeline per recording, to find what grants a stack and when one is lost.

Per recording (each with Terror worn or a Mosquito Shortbow shot): every action-bar stack count change
(server tick n), every bow shot p3wr fired (n, bow, Terror pieces worn, stacks implied by the arrow's speed-up
k when it has one), every arrow of his removed (n, age, main or extra) with the hurt_animation packets on
that tick (entities hurt), the boss lines (phase markers), and the cooldown packets. -> out/stacktl.jsonl
"""
import gzip, json, math, os, re, collections
from concurrent.futures import ProcessPoolExecutor

from tlib import R, OUT  # noqa: E402
STRIP = re.compile(r'§.')
WANT = (b'"k":"inv"', b'"k":"me"', b'"k":"meta"', b'"k":"world"', b'use_item', b'"minecraft:swing"', b'minecraft:cooldown',
        b'"overlay":true', b'"minecraft:arrow"', b'set_entity_motion', b'remove_entities', b'hurt_animation', b'[BOSS]', b'system_chat')
BOWS = ('MOSQUITO_BOW', 'TERMINATOR', 'JUJU_SHORTBOW', 'ITEM_SPIRIT_BOW', 'ARTISANAL_SHORTBOW')


def spd(v): return math.sqrt(sum(c * c for c in v))


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
    ev = []; arrows = {}; last_stacks = None; terror_seen = False; mosq = False
    for o in lines:
        k = o['k']; n = o.get('n'); seq = o.get('seq', 0)
        if k in ('meta', 'world'): self_id = o.get('selfId', self_id)
        elif k == 'inv':
            sel = o.get('sel', sel)
            for s, item in o.get('s', []): slots[s] = (item or {}).get('sb')
        elif k == 'me': sel = o.get('slot', sel)
        elif k == 'out':
            p = o.get('p', '')
            if p in ('minecraft:use_item', 'minecraft:swing'):
                ev.append({'n': n, 'e': 'click', 'kind': 'use' if p == 'minecraft:use_item' else 'swing', 'held': slots.get(sel)})
        elif k == 'in':
            p = o.get('p', ''); f = o.get('f', {})
            if p == 'minecraft:add_entity' and f.get('type') == 'minecraft:arrow':
                own = f.get('data')
                worn = sum(1 for i in (36, 37, 38, 39) if 'TERROR' in (slots.get(i) or ''))
                terror_seen |= worn > 0
                a = {'id': f['id'], 'n': n, 'owner': own, 'v0': f.get('movement'), 'held': slots.get(sel), 'worn': worn, 'pos': [f['x'], f['y'], f['z']], 'k': None}
                arrows[f['id']] = a
                if own == self_id and a['held'] in BOWS:
                    mosq |= a['held'] == 'MOSQUITO_BOW'
                    a['main'] = True
                    ev.append({'n': n, 'e': 'shot', 'id': f['id'], 'held': a['held'], 'worn': worn})
            elif p == 'minecraft:set_entity_motion':
                a = arrows.get(f.get('id'))
                if a and a.get('main') and a['k'] is None and a['v0'] and spd(a['v0']) > 1e-9 and f.get('movement') and f['movement'] != a['v0']:
                    v0, v1 = a['v0'], f['movement']
                    a['k'] = spd([v1[0], v1[1] + 0.05, v1[2]]) / spd([0.99 * c for c in v0])
                    ev.append({'n': n, 'e': 'k', 'id': a['id'], 's': round((a['k'] - 1) * 100, 2)})
            elif p == 'minecraft:remove_entities':
                for i in f.get('entityIds', []):
                    a = arrows.pop(i, None)
                    if a and (a['owner'] == self_id or a['owner'] == i):
                        ev.append({'n': n, 'e': 'gone', 'id': i, 'main': bool(a.get('main')), 'age': n - a['n']})
            elif p == 'minecraft:hurt_animation':
                ev.append({'n': n, 'e': 'hurt', 'id': f.get('id')})
            elif p == 'minecraft:cooldown':
                ev.append({'n': n, 'e': 'cd', 'g': f.get('cooldownGroup'), 'd': f.get('duration')})
            elif p == 'minecraft:system_chat':
                text = STRIP.sub('', (f.get('content') or {}).get('t', '') or '')
                if f.get('overlay'):
                    m = re.search(r'(\d+)⁑', text); st = int(m.group(1)) if m else 0
                    if st != last_stacks:
                        ev.append({'n': n, 'e': 'bar', 's': st}); last_stacks = st
                elif text.startswith('[BOSS]') or 'Hydra' in text:
                    ev.append({'n': n, 'e': 'chat', 'text': text[:90]})
    if not (terror_seen or mosq):
        return rec, None
    return rec, {'rec': rec, 'self': self_id, 'events': ev}


if __name__ == '__main__':
    recs = sorted(x for x in os.listdir(R) if os.path.isdir(os.path.join(R, x)) and not x.startswith('.'))
    n = 0
    with open(os.path.join(OUT, 'stacktl.jsonl'), 'w') as f, ProcessPoolExecutor(12) as ex:
        for rec, row in ex.map(one, recs, chunksize=1):
            if row: f.write(json.dumps(row) + '\n'); n += 1
    print('recordings with Terror or Mosquito:', n)
