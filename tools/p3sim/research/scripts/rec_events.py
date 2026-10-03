"""Boss Recorder: (1) sounds on the same server tick (+0..+1) as key chat lines, compared to a baseline;
(2) where P1/P3/P4 TNT and P2/P4 fireballs spawn (rounded positions, most common) and the P3 TNT volleys'
timing against the (n/7) progress lines; (3) your own position at the P3 TNT volleys (were you in it?).

    python3 rec_events.py
"""
import collections
import glob
import gzip
import json

D = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/bossrecorder/'
EVENTS = {
    'terminal': ' activated a terminal! (', 'lever': ' activated a lever! (', 'device': ' completed a device! (',
    'gate': 'The gate has been destroyed!', 'core': 'The Core entrance is opening!', 'gate5': 'The gate will open in 5',
    'crystal_active': 'Energy Crystals are now active!', 'crystal_pick': 'picked up an Energy Crystal',
    'laser': 'The Energy Laser is charging up!', 'maxor_enraged': 'Maxor is enraged', 'storm_enraged': 'Storm is enraged',
    'boss_line': '[BOSS] ', 'mask': 'saved your life', 'phoenix': 'saved you from certain death', 'died': 'became a ghost',
    'goldor_frenzy': "Goldor's Frenzy", 'necron_frenzy': "Necron's Nuclear Frenzy", 'tnt_trap': "Goldor's TNT Trap",
    'giga': "Storm's Giga Lightning", 'defeated': 'Defeated Maxor, Storm',
}
snd_at = {k: collections.Counter() for k in EVENTS}
ev_n = collections.Counter()
base = collections.Counter()
base_ticks = 0
tntpos = collections.defaultdict(collections.Counter)
fbpos = collections.defaultdict(collections.Counter)
p3tnt = []
for f in sorted(glob.glob(D + '*.jsonl.gz')):
    try:
        L = [json.loads(l) for l in gzip.open(f, 'rt')]
    except Exception:
        continue
    chat = [(l['n'], l['m']) for l in L if l['k'] == 'chat' and l.get('n') is not None]
    start = next((n for n, m in chat if m.startswith('[BOSS] Maxor: WELL!')), None)
    if start is None:
        continue
    p3 = next((n for n, m in chat if m.startswith('[BOSS] Goldor: Who dares')), None)
    p4 = next((n for n, m in chat if m.startswith('[BOSS] Necron: You went further')), None)
    sounds = collections.defaultdict(list)
    me = None
    tnt_ticks = collections.defaultdict(list)
    for l in L:
        if l['k'] != 'net':
            continue
        for e in l['d']:
            n, k = e[0], e[1]
            if k == 'snd' and n >= start:
                sounds[n].append(e[2] + ' v%.2f p%.2f' % (e[7], e[8]))
                base[e[2]] += 1
            elif k == 'me' and e[2] is not None:
                me = (e[2], e[3], e[4])
            elif k == 'a' and n >= start and e[3] in ('minecraft:tnt', 'minecraft:fireball'):
                ph = 'P1' if (p3 is None or n < p3) else ('P3' if (p4 is None or n < p4) else 'P4')
                key = (round(e[4]), round(e[5]), round(e[6]))
                if e[3] == 'minecraft:tnt':
                    tntpos[ph][key] += 1
                    if ph == 'P3':
                        tnt_ticks[n].append((e[4], e[5], e[6], me))
                else:
                    fbpos[ph][key] += 1
    if chat:
        base_ticks += max(n for n, _ in chat) - start
    for n, m in chat:
        if n < start:
            continue
        for k, s in EVENTS.items():
            if s in m:
                ev_n[k] += 1
                seen = set()
                for t in (n - 1, n, n + 1):
                    for x in sounds.get(t, []):
                        if x not in seen:
                            seen.add(x)
                            snd_at[k][x] += 1
    for n, v in sorted(tnt_ticks.items()):
        xs = [a[0] for a in v]; ys = [a[1] for a in v]; zs = [a[2] for a in v]
        near = [m for cn, m in chat if 0 <= n - cn <= 40 and ('activated' in m or 'completed' in m or 'gate' in m.lower())]
        p3tnt.append((f[-25:-9], n - p3, len(v), (round(min(xs), 1), round(max(xs), 1)), (round(min(ys), 1), round(max(ys), 1)),
                      (round(min(zs), 1), round(max(zs), 1)), tuple(round(c, 1) for c in v[0][3]) if v[0][3] else None, near[-1:] if near else []))
print('== sounds within +-1 tick of chat events (count / events) ==')
for k in EVENTS:
    if ev_n[k]:
        print(k, ev_n[k], [(s, c) for s, c in snd_at[k].most_common(8) if c >= max(2, ev_n[k] * 0.2)])
print('\n== TNT spawn positions (top) ==')
for ph, c in tntpos.items():
    print(ph, sum(c.values()), c.most_common(12))
print('\n== fireball spawn positions (top) ==')
for ph, c in fbpos.items():
    print(ph, sum(c.values()), c.most_common(8))
print('\n== P3 TNT volleys: run, tick-in-P3, count, x/y/z range, your pos, last progress line within 40t ==')
for r in p3tnt[:40]:
    print(r)
