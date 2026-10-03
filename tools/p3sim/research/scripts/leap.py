"""Spirit Leap menu and timing from own Better PF runs.

For every 'Spirit Leap' GUI: its slots (first few printed in full), the slot click, the following
teleport, the chat around it; delays in client ticks (t) and server ticks (st/n).

    python3 leap.py [N_FULL_DUMPS]
"""
import bisect, collections, glob, gzip, json, sys
RUNS = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/betterpf/runs/*.gz'
NFULL = int(sys.argv[1]) if len(sys.argv) > 1 else 3
dumped = 0
click_tp_t = collections.Counter(); click_tp_n = collections.Counter()
open_close = collections.Counter(); layouts = collections.Counter(); names = collections.Counter()
chat_after = collections.Counter(); held = collections.Counter(); noTp = 0
lore_ex = {}
for f in sorted(glob.glob(RUNS)):
    L = [json.loads(x) for x in gzip.open(f, 'rt')]
    st_t, st_n = [], []
    for l in L:
        if l['k'] == 'st': st_t.append(l['t']); st_n.append(l['n'])
        if l['k'] == 'held' and l.get('name'): held[l['name']] += 1
    def n_at(t):
        i = bisect.bisect_right(st_t, t) - 1
        return st_n[i] if i >= 0 else None
    for i, l in enumerate(L):
        if l['k'] != 'gui' or l['title'] != 'Spirit Leap': continue
        slots = None; click = None; tp = None; close = None; chats = []
        for m in L[i + 1:i + 400]:
            if m['k'] == 'slots' and slots is None: slots = m['s']
            elif m['k'] == 'slotclick' and click is None: click = m
            elif m['k'] == 'guiclose' and close is None: close = m
            elif m['k'] == 'tp' and click is not None and tp is None: tp = m
            elif m['k'] == 'chat' and click is not None and m.get('t',0) - click['t'] <= 40: chats.append((m['t'] - click['t'], m['m']))
            if m['k'] == 'gui': break
            if tp and m.get('t', 0) - tp['t'] > 40: break
        filled = [s for s in (slots or []) if s[1]]
        layouts[(l.get('menu'), tuple(s[0] for s in filled if 'head' in s[1]))] += 1
        for s in filled: names[(s[1], s[4] if len(s) > 4 else '')] += 0
        if dumped < NFULL and filled:
            dumped += 1
            print('==', f.split('/')[-1], 't', l['t'], l.get('menu'), 'filled slots:')
            for s in filled: print('   ', s)
        if click:
            if tp:
                click_tp_t[tp['t'] - click['t']] += 1
                a, b = n_at(click['t']), n_at(tp['t'])
                if a is not None and b is not None: click_tp_n[b - a] += 1
            else: noTp += 1
            for dt, m in chats:
                if 'Leap' in m or 'leap' in m or 'You have teleported' in m: chat_after[m.split(':')[0][:10] + '|' + m] += 0; chat_after[m] += 1
            if close: open_close[close['t'] - click['t']] += 1
print('held head items:', held.most_common(10))
print('layouts (menu, head slots):', layouts.most_common(10))
print('slotclick -> tp client ticks:', sorted(click_tp_t.items()))
print('slotclick -> tp server ticks (st):', sorted(click_tp_n.items()))
print('slotclick -> guiclose client ticks:', sorted(open_close.items()))
print('clicks without tp:', noTp)
print('chat after click:'); [print('  ', c, m) for m, c in chat_after.most_common(25) if c]
