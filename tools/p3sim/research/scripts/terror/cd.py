"""Mosquito Shortbow clicks, cooldowns and Nasty Bite, in packet order.

Per recording with Mosquito shots: the stream (by seq) of p3wr's clicks while holding it (use_item / swing),
bow-group cooldown packets, his main arrows (add_entity, owner him), and the action bar's vitality and
mana; plus Nasty Bite's particles (heart / dripping_lava near him) so a Nasty Bite shot can be told apart.
-> out/cd.jsonl (one row per recording: the event list)
"""
import gzip, json, os, re
from concurrent.futures import ProcessPoolExecutor

from tlib import R, OUT  # noqa: E402
STRIP = re.compile(r'§.')
WANT = (b'"k":"inv"', b'"k":"me"', b'"k":"meta"', b'"k":"world"', b'use_item', b'"minecraft:swing"', b'minecraft:cooldown',
        b'"overlay":true', b'"minecraft:arrow"', b'level_particles', b'"minecraft:sound"', b'set_carried_item')


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
    self_id = None; slots = {}; sel = 0; me = None; ev = []; mosq = False
    for o in lines:
        k = o['k']; n = o.get('n'); seq = o.get('seq')
        if k in ('meta', 'world'): self_id = o.get('selfId', self_id)
        elif k == 'inv':
            sel = o.get('sel', sel)
            for s, item in o.get('s', []): slots[s] = (item or {}).get('sb')
        elif k == 'me':
            sel = o.get('slot', sel); me = o.get('pos')
        elif k == 'out':
            p = o.get('p', '')
            if p in ('minecraft:use_item', 'minecraft:swing') and slots.get(sel) == 'MOSQUITO_BOW':
                ev.append({'n': n, 'seq': seq, 'e': 'use' if p == 'minecraft:use_item' else 'swing'})
            elif p == 'minecraft:set_carried_item':
                ev.append({'n': n, 'seq': seq, 'e': 'slot', 'held': slots.get((o.get('f') or {}).get('slot'))})
        elif k == 'in':
            p = o.get('p', ''); f = o.get('f', {})
            if p == 'minecraft:cooldown' and 'bow' in str(f.get('cooldownGroup')):
                ev.append({'n': n, 'seq': seq, 'e': 'cd', 'd': f.get('duration')})
            elif p == 'minecraft:add_entity' and f.get('type') == 'minecraft:arrow' and f.get('data') == self_id and slots.get(sel) == 'MOSQUITO_BOW':
                mosq = True
                ev.append({'n': n, 'seq': seq, 'e': 'shot', 'id': f['id']})
            elif p == 'minecraft:system_chat' and f.get('overlay'):
                t = STRIP.sub('', (f.get('content') or {}).get('t', '') or '')
                vit = re.search(r'([\d,]+)/([\d,]+)', t); mana = re.search(r'([\d,]+)/([\d,]+)', t)
                ev.append({'n': n, 'seq': seq, 'e': 'bar', 'vit': int(vit.group(1).replace(',', '')) if vit else None,
                           'mana': int(mana.group(1).replace(',', '')) if mana else None})
            elif p == 'minecraft:level_particles' and me:
                part = f.get('particle'); typ = part.get('type') if isinstance(part, dict) else str(part)
                if typ and ('heart' in typ or 'dripping_lava' in typ or 'lava' in typ):
                    at = [f.get('x'), f.get('y'), f.get('z')]
                    if None not in at and sum((at[i] - me[i]) ** 2 for i in range(3)) < 64:
                        ev.append({'n': n, 'seq': seq, 'e': 'nb_particle', 'type': typ, 'count': f.get('count')})
            elif p == 'minecraft:sound' and me:
                snd = str(f.get('sound'))
                at = [f.get('x'), f.get('y'), f.get('z')]
                if None not in at:
                    at = [c / 8 for c in at]   # the sound packet carries x/y/z x8 as ints
                    if sum((at[i] - me[i]) ** 2 for i in range(3)) < 64:
                        ev.append({'n': n, 'seq': seq, 'e': 'sound', 's': snd[-40:], 'pitch': f.get('pitch'), 'vol': f.get('volume')})
    return rec, ({'rec': rec, 'events': ev} if mosq else None)


if __name__ == '__main__':
    recs = sorted(x for x in os.listdir(R) if os.path.isdir(os.path.join(R, x)) and not x.startswith('.'))
    n = 0
    with open(os.path.join(OUT, 'cd.jsonl'), 'w') as f, ProcessPoolExecutor(12) as ex:
        for rec, row in ex.map(one, recs, chunksize=1):
            if row: f.write(json.dumps(row) + '\n'); n += 1
    print('recordings with Mosquito shots:', n)
