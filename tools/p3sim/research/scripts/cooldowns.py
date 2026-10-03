"""'This ability is on cooldown for Ns.': ticks since the item's last use, by held item (own Better PF runs).

Last use: leap = last own 'tp' after a Spirit Leap slot click; AOTV / Hyperion = last own 'tp';
Wither Cloak = last 'Creeper Veil Activated!' and last 'De-activated'.  Also the cloak's on-time.

    python3 cooldowns.py
"""
import collections, glob, gzip, json, re
BPF = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/betterpf/runs/*.gz'
since = collections.defaultdict(list); ontime = collections.Counter(); reon = []
for f in sorted(glob.glob(BPF)):
    L = [json.loads(x) for x in gzip.open(f, 'rt')]
    self = L[0].get('self'); held = None; last = {}; leap_click = None; on = None; off = None
    for l in L:
        k = l['k']; t = l.get('t')
        if k == 'p':
            for d in l['d']:
                if d[0] == self: held = d[6] if len(d) > 6 else None
        elif k == 'gui' and l.get('title') == 'Spirit Leap': leap_click = 'open'
        elif k == 'slotclick' and leap_click == 'open': leap_click = t
        elif k == 'guiclose' and leap_click == 'open': leap_click = None
        elif k == 'tp':
            if isinstance(leap_click, int) and t - leap_click <= 10: last['INFINITE_SPIRIT_LEAP'] = t; leap_click = None
            else: last[held] = t
        elif k == 'chat':
            m = l['m']
            if m == 'Creeper Veil Activated!':
                if off is not None: reon.append(t - off)
                on = t
            elif m.startswith(('Creeper Veil De-activated', 'Not enough vitality')):
                if on is not None: ontime[(m, (t - on) // 10 * 10)] += 1
                off = t; last['WITHER_CLOAK_OFF'] = t
            c = re.match(r'This ability is on cooldown for (\d+)s\.', m)
            if c:
                ref = 'WITHER_CLOAK_OFF' if held == 'WITHER_CLOAK' else held
                if ref in last: since[(held, int(c.group(1)))].append(t - last[ref])
for k in sorted(since, key=str):
    v = sorted(since[k]); print(k, 'n', len(v), 'ticks since last use: min', v[0], 'median', v[len(v) // 2], 'max', v[-1])
print('cloak on-time (10-tick bins):', sorted(ontime.items()))
print('cloak re-activation after off (ticks):', sorted(reon)[:20])
