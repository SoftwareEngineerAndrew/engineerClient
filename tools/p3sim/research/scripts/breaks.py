"""What Dungeonbreaker / Superboom TNT break in the boss (own Better PF runs).

A broken block = a block line turning a non-air state into air within 6 blocks of your eye, 0..3 ticks after
your own swing while holding the item. Prints prior states, delay, which boss phase (last [BOSS] speaker),
swing spacing, and every chat line seen while holding the item.

    python3 breaks.py
"""
import bisect, collections, glob, gzip, json, re
RUNS = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/betterpf/runs/*.gz'
prior = collections.defaultdict(collections.Counter); dts = collections.defaultdict(collections.Counter)
phase_c = collections.defaultdict(collections.Counter); per_swing = collections.defaultdict(collections.Counter)
gap = collections.defaultdict(collections.Counter); chats = collections.defaultdict(collections.Counter); ex = []
for f in sorted(glob.glob(RUNS)):
    L = [json.loads(x) for x in gzip.open(f, 'rt')]
    self = L[0].get('self'); pal = {}; state = {}; held = None; pos = None; phase = 'clear'; pending = []; last_sw = {}
    for l in L:
        k = l['k']; t = l.get('t')
        if k == 'pal': pal[l['i']] = l['s']
        elif k == 'p':
            for d in l['d']:
                if d[0] == self: pos = d[1:4]; held = d[6] if len(d) > 6 else None
        elif k == 'chat':
            m = re.match(r'\[BOSS\] (\w+)', l['m'])
            if m and m.group(1) in ('Maxor', 'Storm', 'Goldor', 'Necron'): phase = m.group(1)
            if held in ('DUNGEONBREAKER', 'SUPERBOOM_TNT') and not l['m'].startswith(('Party >', '[BOSS]')) and 'obtained' not in l['m']:
                chats[held][re.sub(r'\d[\d,.]*', '#', l['m'])[:80]] += 1
        elif k == 'sw' and self in l['d'] and held in ('DUNGEONBREAKER', 'SUPERBOOM_TNT') and pos:
            key = (held, phase)
            if key in last_sw: gap[key][min(t - last_sw[key], 30)] += 1
            last_sw[key] = t
            pending.append([t, key, pos, 0])
        elif k == 'block':
            p = (l['x'], l['y'], l['z']); old = state.get(p); new = pal.get(l['s'], '?'); state[p] = new
            if 'air' in new and old and 'air' not in old:
                for sw in pending:
                    if 0 <= t - sw[0] <= 3 and (l['x'] + .5 - sw[2][0]) ** 2 + (l['y'] + .5 - sw[2][1] - 1.6) ** 2 + (l['z'] + .5 - sw[2][2]) ** 2 < 36:
                        prior[sw[1]][old.split('[')[0]] += 1; dts[sw[1]][t - sw[0]] += 1; sw[3] += 1
                        if len(ex) < 6 and sw[1][1] != 'clear': ex.append((f[-30:], t, sw[1], p, old))
                        break
        if t is not None:
            keep = []
            for sw in pending:
                if t - sw[0] > 3: per_swing[sw[1]][sw[3]] += 1
                else: keep.append(sw)
            pending = keep
for key in sorted(prior, key=str):
    print(key, 'broken per swing', sorted(per_swing[key].items())[:8])
    print('   prior states', prior[key].most_common(8)); print('   delay', sorted(dts[key].items()))
    print('   swing spacing (ticks, 30=30+)', sorted(gap[key].items())[:12])
for h in chats: print('chat holding', h, chats[h].most_common(10))
print('examples', ex)
