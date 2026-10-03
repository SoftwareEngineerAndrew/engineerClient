"""Classify every `v` on you (kb_join.py output) by signature.

usage: python3 kb_classify.py /tmp/kb_events.jsonl
Signature = (vy, |v_xz| rounded, held item, sounds near you within +-2 ticks, explosion/firework nearby, damage).
"""
import json, math, sys
from collections import Counter, defaultdict
sig = Counter(); ex = defaultdict(list)
for l in open(sys.argv[1]):
    e = json.loads(l)
    v = e['v']; h = round(math.hypot(v[0], v[2]), 2)
    fw = any(s[1] == 'minecraft:firework_rocket' for s in e['spawns'])
    snd = sorted(set(s[1].split(':', 1)[-1].split('.', 1)[-1] for s in e['snd'] if s[0] >= -1))
    hp = [x[1] for x in e['hp']]
    if v[1] == 0.5 and h == 1.5: c = 'bonzo'
    elif v[1] == 0.6: c = 'jerry'
    elif v == [0, 0, 0] or v == [0.0, 0.0, 0.0]: c = 'zero'
    else: c = 'vy=%s h=%s' % (v[1], h)
    key = (c, e['held'] if c not in ('bonzo', 'jerry') else '', 'fw' if fw else '', ','.join(x for x in snd if x in (
        'player.hurt', 'generic.explode', 'item.break', 'enderman.teleport', 'wither.shoot', 'lightning_bolt.thunder', 'ghast.ambient', 'villager.yes', 'arrow.hit', 'player.attack.weak')),
        'ex' if e['ex'] else '', 'dmg:' + ','.join(sorted(set(d[1][0] for d in e['dmg']))) if e['dmg'] else '')
    sig[key] += 1
    if len(ex[key]) < 2: ex[key].append((e['file'][:16], e['n'], v, [round(x, 1) for x in e['pos'][:3]] if e['pos'][0] is not None else None))
for k, c in sig.most_common():
    print(c, k, ex[k])
print('--- by class')
cls = Counter()
for k, c in sig.items():
    cls[k[0] if not k[0].startswith(('vy=2.25', 'vy=3.0375')) else k[0][:9]] += c
print(cls.most_common())
print('--- other')
for k, c in sig.most_common():
    if not (k[0] in ('bonzo', 'jerry', 'zero') or k[0].startswith(('vy=2.25', 'vy=3.0375'))):
        print(c, k, ex[k])
