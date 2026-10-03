"""Spirit Leap: menu contents (container slots only), landing vs target, rotation, spacing.

    python3 leap2.py [N_DUMPS]
"""
import bisect, collections, glob, gzip, json, math, re, sys
RUNS = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/betterpf/runs/*.gz'
ND = int(sys.argv[1]) if len(sys.argv) > 1 else 4
dumps = 0
menu_names = collections.Counter(); slot_pos = collections.Counter(); size = collections.Counter()
off = []; rot = collections.Counter(); gaps = []; nohit = collections.Counter(); heldid = collections.Counter()
for f in sorted(glob.glob(RUNS)):
    L = [json.loads(x) for x in gzip.open(f, 'rt')]
    self = L[0].get('self')
    pos = {}  # name -> last p entry
    hist = collections.defaultdict(list)  # name -> [(t, x,y,z,yaw,pitch)]
    for l in L:
        if l['k'] == 'p':
            for d in l['d']: hist[d[0]].append((l['t'], *d[1:6], d[6] if len(d) > 6 else None))
    def at(name, t):
        h = hist.get(name, []); i = bisect.bisect_right([e[0] for e in h], t) - 1
        return h[i] if i >= 0 else None
    last_tp_t = None
    for i, l in enumerate(L):
        if l['k'] != 'gui' or l.get('title') != 'Spirit Leap': continue
        cont = {}; click = None; tp = None; tgt = None; chats = []
        for m in L[i + 1:i + 300]:
            k = m['k']
            if k == 'slots' and click is None:
                for s in m['s']:
                    if s[0] < 36: cont[s[0]] = s
            elif k == 'slotclick' and click is None: click = m
            elif k == 'tp' and click and tp is None: tp = m
            elif k == 'chat' and click:
                chats.append((m['t'] - click['t'], m['m']))
                mm = re.match(r'You have teleported to (\S+)!', m['m'])
                if mm: tgt = mm.group(1)
            if k == 'gui' or (click and m.get('t', 0) - click['t'] > 60): break
        me = at(self, l['t'])
        if me: heldid[me[6]] += 1
        filled = {k: v for k, v in cont.items() if v[1]}
        size[len(cont)] += 1
        for k, v in filled.items(): slot_pos[(k, v[1].split('@')[0])] += 1
        if dumps < ND and filled:
            dumps += 1
            print('==', f.split('/')[-1], l['t'], l.get('menu'))
            for k in sorted(filled): v = filled[k]; print('   ', k, v[1], v[2], repr(v[4] if len(v) > 4 else ''), 'tex' if v[3] else '', v[5] if len(v) > 5 else '')
            if click: print('    click', click, 'chats', chats[:5])
        for v in filled.values():
            if len(v) > 4: menu_names[re.sub(r'[A-Za-z0-9_]{3,16}$', '<name>', v[4]) if 'head' in v[1] else v[4]] += 1
        if click and not tp: nohit[tuple(c[1] for c in chats[:3])] += 1
        if tp and tgt:
            tg = at(tgt, tp['t'] - 1); prev = at(self, click['t'])
            if tg:
                off.append((round(tp['x'] - tg[1], 3), round(tp['y'] - tg[2], 3), round(tp['z'] - tg[3], 3)))
                if prev: rot[(round(tp['yaw'] - prev[4]) % 360 if tp['yaw'] is not None else None, 'yaw=target' if tp['yaw'] is not None and abs((tp['yaw'] - tg[4] + 180) % 360 - 180) < 1 else '')] += 1
            if last_tp_t is not None: gaps.append(tp['t'] - last_tp_t)
            last_tp_t = tp['t']
print('own held skyblock id at menu open:', heldid.most_common(5))
print('container slot counts (menu size):', size.most_common(5))
print('filled slot -> item:', sorted(slot_pos.items())[:40])
print('item names:', menu_names.most_common(20))
print('n landings measured', len(off))
c = collections.Counter((round(a, 1), round(b, 1), round(cc, 1)) for a, b, cc in off)
print('landing - target(p at tp-1) rounded:', c.most_common(12))
print('rotation after leap (delta yaw from own, target-yaw match):', rot.most_common(8))
print('min gaps between consecutive leap tps (client ticks):', sorted(gaps)[:15])
print('clicks without tp -> first chats:', nohit.most_common(10))
