"""terror-mosquito.md §5: Hydra Strike stacks. What grants one (shots or hits), when one is lost (a once-a-second check:
every loss in a recording on one phase mod 20, gains on any), loss spacing by Terror pieces, and whether a hit at 10
stacks refreshes the timer (10-stack holds longer than one loss interval).    python3 stacks.py   (needs out/stacktl.jsonl)
"""
import collections
from tlib import jsonl

TL = jsonl('stacktl.jsonl')
PH = [('Maxor', 'P1'), ('Storm: Pathetic', 'P2'), ('Goldor: Who dares', 'P3'), ('Necron', 'P4+')]

gain_by_phase = collections.defaultdict(collections.Counter); spacing = collections.defaultdict(collections.Counter); holds = []
print('loss tick mod 20 by chain of losses, and gain tick mod 20, per recording (action bar every 10 ticks):')
for row in TL:
    ev = row['events']
    phase = 'clear'; ks = []
    for e in ev:
        if e['e'] == 'chat':
            for key, name in PH:
                if key in e['text'] and (name != 'P1' or phase == 'clear'): phase = name
        if e['e'] == 'k' and abs(e['s'] - round(e['s'])) < 0.2 and 0 <= round(e['s']) <= 10: ks.append((e['n'], round(e['s']), phase))
    for (n1, s1, p1), (n2, s2, p2) in zip(ks, ks[1:]):
        if n2 - n1 <= 12 and p1 == p2: gain_by_phase[p1]['at 10' if s1 == 10 else s2 - s1] += 1
    bars = [e for e in ev if e['e'] == 'bar']
    worn = [(e['n'], e['worn']) for e in ev if e['e'] == 'shot']
    def worn_at(n):
        w = [x for x in worn if x[0] <= n]
        return w[-1][1] if w else None
    chains = []; gains = []; prev = None
    for a, b in zip(bars, bars[1:]):
        if b['s'] == a['s'] - 1:
            if prev != 'loss': chains.append([])
            elif chains[-1]: spacing[worn_at(b['n'])][b['n'] - chains[-1][-1]] += 1
            chains[-1].append(b['n']); prev = 'loss'
        else:
            prev = 'gain'
            if b['s'] > a['s']: gains.append(b['n'])
        if a['s'] == 10:
            shots = [e['n'] for e in ev if e['e'] == 'shot' and a['n'] <= e['n'] <= b['n']]
            holds.append((b['n'] - a['n'], worn_at(b['n']), len(shots), (b['n'] - shots[-1]) if shots else None))
    if chains:
        print('  %s losses %s  gains %s' % (row['rec'][11:19], [sorted(collections.Counter(n % 20 for n in c).items()) for c in chains],
                                           sorted(collections.Counter(n % 20 for n in gains).items())))
print('\nstack change between consecutive shots (<= 12 ticks apart), by fight phase:')
for p, c in gain_by_phase.items(): print('  %-5s %s' % (p, dict(sorted(c.items(), key=str))))
print('\nloss-to-loss ticks (action-bar ticks, +-1 jitter) by Terror pieces worn:')
for w, c in spacing.items(): print('  %s pieces: %s' % (w, sorted(c.items())))
print('\n10-stack holds longer than 165 ticks: (length, pieces, shots during, ticks from the last shot to the loss)')
for h in sorted(holds, reverse=True):
    if h[0] > 165: print('  ', h)
