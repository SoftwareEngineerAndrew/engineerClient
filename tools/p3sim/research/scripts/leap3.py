"""Spirit Leap menu: head slot layout by head count, head order vs party/class, 'Unknown' heads, ghosts,
and what the 'On cooldown!' / 'Teleport to Player' GUIs were.

    python3 leap3.py
"""
import collections, glob, gzip, json, re
RUNS = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/betterpf/runs/*.gz'
lay = collections.Counter(); order = collections.Counter(); unk = []; other = []
for f in sorted(glob.glob(RUNS)):
    L = [json.loads(x) for x in gzip.open(f, 'rt')]
    party = []; ghosts = set(); self = L[0].get('self')
    for i, l in enumerate(L):
        k = l['k']
        if k == 'party': party = l['m']
        elif k == 'chat':
            m = re.match(r' ☠ (\S+) .*became a ghost', l['m'])
            if m: ghosts.add(self if m.group(1) == 'You' else m.group(1))
            m = re.match(r' ❣ (\S+) was revived', l['m'])
            if m: ghosts.discard(m.group(1))
        elif k == 'gui' and l.get('title') in ('On cooldown!', 'Teleport to Player'):
            sl = {'s': [s for m in L[i + 1:i + 40] if m['k'] == 'slots' for s in m['s']]}
            other.append((f.split('/')[-1], l['t'], l['title'], l.get('menu'), [(s[0], s[1], s[4]) for s in (sl or {}).get('s', []) if s[0] < 36 and s[1] and 'glass' not in s[1]][:12]))
        elif k == 'gui' and l.get('title') == 'Spirit Leap':
            cont = {}
            for m in L[i + 1:i + 60]:
                if m['k'] in ('slotclick', 'guiclose', 'gui'): break
                if m['k'] == 'slots':
                    for s in m['s']:
                        if s[0] < 36: cont[s[0]] = s
            heads = [(s[0], s[4]) for s in sorted(cont.values()) if 'head' in s[1]]
            if not heads: continue
            lay[tuple(h[0] for h in heads)] += 1
            names = [h[1] for h in heads]
            others = [p for p in party if p[0] != self]
            cls = [p[1] for n in names for p in party if p[0] == n.replace('Unknown ', '')]
            order[('party-order' if [p[0] for p in others] == names else 'alpha' if names == sorted(names, key=str.lower) else 'class:' + ','.join(cls))] += 1
            if any('Unknown' in n for n in names) or ghosts & set(p[0] for p in others):
                unk.append((f.split('/')[-1], l['t'], names, sorted(ghosts), [p[0] for p in others]))
print('head slot layouts:', lay.most_common())
print('order:', order.most_common(15))
print('menus with Unknown heads or ghosts in party:'); [print('  ', u) for u in unk[:12]]
print('other GUIs:'); [print('  ', o) for o in other]
