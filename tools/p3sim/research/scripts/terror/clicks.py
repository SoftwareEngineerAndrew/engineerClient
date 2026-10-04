"""terror-mosquito.md §6: Mosquito clicks and cooldowns. Each shot's kind from the bow cooldown packet sent right after it
(5 = a normal shot, 10 = Nasty Bite, 7 = a normal shot without Terror's attack speed); which click came before it;
the gaps between kinds; a click made during the cooldown firing when it ends; Nasty Bite's vitality cost.
    python3 clicks.py   (needs out/cd.jsonl)
"""
import collections
from tlib import jsonl

R = jsonl('cd.jsonl')
kinds = collections.Counter(); trig = collections.Counter(); gap = collections.defaultdict(collections.Counter)
same = collections.defaultdict(collections.Counter); fresh = collections.Counter(); buffered = collections.Counter()
vit = collections.Counter(); regen = collections.Counter()
for row in R:
    ev = row['events']; shots = []
    for i, e in enumerate(ev):
        if e['e'] != 'shot': continue
        cd = next((x for x in ev[i + 1:i + 6] if x['e'] == 'cd' and x['n'] - e['n'] <= 1 and x['d'] > 0), None)
        kind = {10: 'NB', 5: 'N', 7: 'N7'}.get(cd['d'] if cd else None, 'cd?')
        clicks = [x for x in ev[max(0, i - 40):i] if x['e'] in ('use', 'swing') and e['n'] - x['n'] <= 6]
        shots.append({'n': e['n'], 'seq': e['seq'], 'kind': kind})
        kinds[kind] += 1; trig[(kind, 'swing' in [c['e'] for c in clicks], 'use' in [c['e'] for c in clicks])] += 1
    for a, b in zip(shots, shots[1:]): gap[(a['kind'], b['kind'])][min(b['n'] - a['n'], 15)] += 1
    for kd in ('N', 'NB'):
        ns = [s['n'] for s in shots if s['kind'] == kd]
        for x, y in zip(ns, ns[1:]): same[kd][min(y - x, 20)] += 1
    clicks = [e for e in ev if e['e'] in ('use', 'swing')]
    normal = [s for s in shots if s['kind'] == 'N']
    for j, s in enumerate(normal):
        prev = normal[j - 1]['n'] if j else -10 ** 9
        cl = [c for c in clicks if c['seq'] < s['seq'] and c['n'] > prev - 3 and c['e'] == 'use']
        if not cl: continue
        if s['n'] - prev > 20: fresh[s['n'] - cl[-1]['n']] += 1
        elif s['n'] - prev == 5: buffered[s['n'] - cl[-1]['n']] += 1
    bars = [e for e in ev if e['e'] == 'bar' and e['vit'] is not None]
    nbs = [s['n'] for s in shots if s['kind'] == 'NB']
    for a, b in zip(bars, bars[1:]):
        if b['n'] - a['n'] > 12: continue
        k = sum(1 for n in nbs if a['n'] < n <= b['n'])
        (vit if k == 1 else regen if k == 0 else collections.Counter())[b['vit'] - a['vit']] += 1
print('shot kinds:', dict(kinds))
print('(kind, a swing in the 6 ticks before, a use in the 6 ticks before):', dict(trig))
for k in sorted(gap): print('  gap %-12s %s' % (k, sorted(gap[k].items())))
print('normal to normal (Nasty Bites skipped):', sorted(same['N'].items()))
print('Nasty Bite to Nasty Bite:', sorted(same['NB'].items()))
print('fresh normal shot (>20 ticks after the last): ticks from the last right click:', sorted(fresh.items()))
print('normal shot exactly 5 after the last: ticks from the last right click (>1 = it waited out the cooldown):', sorted(buffered.items()))
print('vitality, bar to bar with one Nasty Bite:', vit.most_common(6), '  with none:', regen.most_common(6))
