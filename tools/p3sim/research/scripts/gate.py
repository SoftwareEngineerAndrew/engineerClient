"""Superboom TNT at the P3 gates: swing -> 'The gate has been destroyed!' delay and block changes (Better PF),
and explosions / sounds / blocks at that server tick (Boss Recorder).

    python3 gate.py
"""
import collections, glob, gzip, json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'boss-mechanics'))
from netlog import NetLog  # noqa
BPF = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/betterpf/runs/*.gz'
BR = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/bossrecorder/*.jsonl.gz'
dly = collections.Counter(); blk = collections.Counter(); pos_ex = []; holders = collections.Counter(); nsw = collections.Counter()
for f in sorted(glob.glob(BPF)):
    L = [json.loads(x) for x in gzip.open(f, 'rt')]
    self = L[0].get('self'); held = None; sws = []; pal = {}; me = None
    for i, l in enumerate(L):
        k = l['k']
        if k == 'pal': pal[l['i']] = l['s']
        elif k == 'p':
            for d in l['d']:
                if d[0] == self: held = d[6] if len(d) > 6 else None; me = d[1:4]
        elif k == 'sw' and self in l['d'] and held == 'SUPERBOOM_TNT': sws.append((l['t'], me))
        elif k == 'chat' and l['m'] == 'The gate has been destroyed!':
            holders[held] += 1
            prior = [s for s in sws if 0 <= l['t'] - s[0] <= 60]
            nsw[len(prior)] += 1
            if prior: dly[l['t'] - prior[-1][0]] += 1
            for m in L[max(0, i - 400):i + 400]:
                if m['k'] == 'block' and abs(m['t'] - l['t']) <= 3: blk[(m['t'] - l['t'], pal.get(m['s'], '?').split('[')[0])] += 1
            if len(pos_ex) < 8: pos_ex.append((f[-30:-9], l['t'], me))
print('gate destroyed while holding:', holders.most_common(5))
print('own superboom swings within 60 ticks before:', sorted(nsw.items()))
print('last swing -> chat client ticks:', sorted(dly.items()))
print('block changes within +-3 ticks (dt, new state):', blk.most_common(12))
print('player pos at message:', pos_ex)
exs = collections.Counter(); snds = collections.Counter(); bl = collections.Counter(); ents = collections.Counter(); chat_dt = collections.Counter()
for f in sorted(glob.glob(BR)):
    L = [json.loads(x) for x in gzip.open(f, 'rt')]
    log = NetLog(L)
    for t, n, m in log.chat:
        if m != 'The gate has been destroyed!' or n is None: continue
        for e in log.kind('ex'):
            if -40 <= e[0] - n <= 5: exs[(e[0] - n, round(e[4], 1), e[5])] += 1
        for e in log.kind('snd'):
            if -3 <= e[0] - n <= 3: snds[(e[0] - n, e[1], round(e[6], 2), round(e[7], 2))] += 1
        for e in log.kind('b'):
            if -3 <= e[0] - n <= 3: bl[(e[0] - n, e[4])] += 1
        for e in log.kind('a'):
            if -40 <= e[0] - n <= 3 and e[2] in ('minecraft:tnt', 'minecraft:falling_block', 'minecraft:item_display', 'minecraft:block_display'): ents[(e[0] - n, e[2])] += 1
        for t2, n2, m2 in log.chat:
            if n2 is not None and -40 <= n2 - n <= 10 and ('gate' in m2.lower() or 'TNT' in m2 or 'Goldor' in m2): chat_dt[(n2 - n, m2[:70])] += 1
print('BR explosions (dt, radius, blocks):', exs.most_common(10))
print('BR sounds:', snds.most_common(12))
print('BR block changes (dt, palette):', bl.most_common(10))
print('BR entities added:', ents.most_common(10))
print('BR chat around:', chat_dt.most_common(12))
