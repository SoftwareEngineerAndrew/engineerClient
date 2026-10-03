"""Own Better PF runs: chat lines by held Skyblock item, and blocks broken by Dungeonbreaker / Superboom TNT.

    python3 bpf_items.py

Held item = your own 'p' entry's heldItemId at the tick. 'In boss' = after the first '[BOSS] Maxor' chat.
Broken block = a 'block' line whose state is air within 7 blocks of you, 0..20 ticks after your swing ('sw').
"""
import bisect, collections, glob, gzip, json, re
RUNS = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/betterpf/runs/*.gz'
INTEREST = re.compile(r'cooldown|blocks in the way|Creeper Veil|mana|Mana|Implosion|Warping|Superboom|vitality')
bychat = collections.Counter(); brk = collections.defaultdict(collections.Counter); delays = collections.defaultdict(collections.Counter)
brkstates = collections.defaultdict(collections.Counter); swings = collections.Counter(); cd_vals = collections.defaultdict(collections.Counter)
for f in sorted(glob.glob(RUNS)):
    L = [json.loads(x) for x in gzip.open(f, 'rt')]
    self = L[0].get('self'); pal = {}; held = []; boss_t = None; pos = []
    for l in L:
        k = l['k']
        if k == 'pal': pal[l['i']] = l['s']
        elif k == 'p':
            for d in l['d']:
                if d[0] == self: held.append((l['t'], d[6] if len(d) > 6 else None)); pos.append((l['t'], d[1], d[2], d[3]))
        elif k == 'chat' and boss_t is None and l['m'].startswith('[BOSS] Maxor'): boss_t = l['t']
    ht = [h[0] for h in held]; pt = [p[0] for p in pos]
    def held_at(t):
        i = bisect.bisect_right(ht, t) - 1
        return held[i][1] if i >= 0 else None
    def pos_at(t):
        i = bisect.bisect_right(pt, t) - 1
        return pos[i][1:] if i >= 0 else None
    blocks = [l for l in L if l['k'] == 'block']; bt = [b['t'] for b in blocks]
    for l in L:
        k = l['k']
        if k == 'chat' and INTEREST.search(l['m']):
            m = re.sub(r'\d[\d,.]*', '#', l['m'])[:70]
            bychat[(m, held_at(l['t']))] += 1
            c = re.match(r'This ability is on cooldown for (\d+)s', l['m'])
            if c: cd_vals[held_at(l['t'])][int(c.group(1))] += 1
        elif k == 'sw' and self in l['d']:
            item = held_at(l['t'])
            if item not in ('DUNGEONBREAKER', 'SUPERBOOM_TNT'): continue
            phase = 'boss' if boss_t and l['t'] >= boss_t else 'clear'
            swings[(item, phase)] += 1
            p = pos_at(l['t'])
            if not p: continue
            i = bisect.bisect_left(bt, l['t']); n = 0
            while i < len(blocks) and blocks[i]['t'] <= l['t'] + 20:
                b = blocks[i]; i += 1
                st = pal.get(b['s'], '?')
                if 'air' in st and (b['x'] + .5 - p[0]) ** 2 + (b['y'] + .5 - p[1] - 1.6) ** 2 + (b['z'] + .5 - p[2]) ** 2 < 49:
                    n += 1; delays[(item, phase)][b['t'] - l['t']] += 1
            brk[(item, phase)][n] += 1
print('chat by held item:'); [print('  ', c, k) for k, c in bychat.most_common(40)]
print('cooldown seconds by item:', {k: dict(v) for k, v in cd_vals.items()})
print('swings:', swings)
for k in brk: print('air blocks within 7 after swing', k, sorted(brk[k].items())[:12], 'delays', sorted(delays[k].items())[:12])
