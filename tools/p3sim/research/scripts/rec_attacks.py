"""Boss Recorder: projectile/attack entities added during the F7 boss, per phase.

Phase marks from chat: P1 '[BOSS] Maxor: WELL! WELL!', P2 'Pathetic Maxor', P3 'Who dares trespass',
P4 'You went further', END 'All this, for nothing'.
Per (phase, type): count, runs, owner (which phase's wither, if the owner id is a wither), speed / y /
tick-in-phase quartiles. Also explosions, sounds and damage types to you, per phase.

    python3 rec_attacks.py                 # summary
    python3 rec_attacks.py --detail TYPE   # every spawn of a type (suffix match) in the first 2 runs
"""
import collections
import glob
import gzip
import json
import math
import sys

D = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/bossrecorder/'
MARKS = [('P1', "[BOSS] Maxor: WELL! WELL!"), ('P2', '[BOSS] Storm: Pathetic Maxor'),
         ('P3', '[BOSS] Goldor: Who dares'), ('P4', '[BOSS] Necron: You went further'),
         ('END', '[BOSS] Necron: All this, for nothing')]
SKIP = {'minecraft:armor_stand', 'minecraft:player', 'minecraft:item', 'minecraft:experience_orb',
        'minecraft:zombie', 'minecraft:skeleton', 'minecraft:wither_skeleton', 'minecraft:item_frame',
        'minecraft:interaction', 'minecraft:text_display', 'minecraft:item_display', 'minecraft:block_display',
        'minecraft:bat', 'minecraft:giant', 'minecraft:end_crystal', 'minecraft:guardian', 'minecraft:wither'}
detail = sys.argv[sys.argv.index('--detail') + 1] if '--detail' in sys.argv else None


def q(a):
    a = sorted(x for x in a if x is not None)
    return [round(a[int(len(a) * p)], 3) for p in (0, .25, .5, .75)] + [round(a[-1], 3)] if a else []


stats = collections.defaultdict(lambda: {'n': 0, 'runs': set(), 'own': collections.Counter(), 'sp': [], 'y': [], 'dt': []})
other = collections.Counter()
exs = collections.defaultdict(list)
snds = collections.defaultdict(collections.Counter)
dmgc = collections.defaultdict(collections.Counter)
nfiles = 0
shown = set()
for f in sorted(glob.glob(D + '*.jsonl.gz')):
    try:
        L = [json.loads(l) for l in gzip.open(f, 'rt')]
    except Exception:
        continue
    marks = {}
    for l in L:
        if l['k'] == 'chat' and l.get('n') is not None:
            for p, s in MARKS:
                if l['m'].startswith(s) and p not in marks:
                    marks[p] = l['n']
    if 'P1' not in marks:
        continue
    nfiles += 1

    def phase(n):
        cur = None
        for p, _ in MARKS:
            if p in marks and n >= marks[p]:
                cur = p
        return cur

    selfid = L[0].get('selfId')
    withers = {}
    ents = []
    for l in L:
        if l['k'] != 'net':
            continue
        for e in l['d']:
            n, k = e[0], e[1]
            ph = phase(n)
            if k == 'a':
                i, typ = e[2], e[3]
                if typ == 'minecraft:wither':
                    withers[i] = ph
                    continue
                if ph is None or ph == 'END':
                    continue
                if typ in SKIP:
                    other[(ph, typ)] += 1
                    continue
                ents.append((n, ph, i, typ, e[4], e[5], e[6], e[7], e[8], e[9], e[13] if len(e) > 13 else None))
            elif ph is None or ph == 'END':
                continue
            elif k == 'ex':
                exs[ph].append((e[2], e[3], e[4], e[5]))
            elif k == 'snd':
                snds[ph][e[2]] += 1
            elif k == 'dmg' and e[2] == selfid:
                dmgc[ph][e[3]] += 1
    for (n, ph, i, typ, x, y, z, vx, vy, vz, data) in ents:
        s = stats[(ph, typ)]
        s['n'] += 1
        s['runs'].add(f)
        own = ('wither@' + str(withers[data])) if data in withers else ('self' if data == selfid else ('none' if not data else 'other'))
        s['own'][own] += 1
        if vx is not None:
            s['sp'].append(math.sqrt(vx * vx + vy * vy + vz * vz))
        s['y'].append(y)
        s['dt'].append(n - marks[ph])
        if detail and typ.endswith(detail) and (f in shown or len(shown) < 2):
            shown.add(f)
            print(f[-25:], ph, n - marks[ph], i, typ, round(x, 2), round(y, 2), round(z, 2), vx, vy, vz, 'owner', data, own)
print('files with boss:', nfiles)
for (ph, typ), s in sorted(stats.items()):
    print(f"{ph} {typ}: n={s['n']} runs={len(s['runs'])} owner={dict(s['own'])}\n   speed q={q(s['sp'])} y q={q(s['y'])} t-in-phase q={q(s['dt'])}")
print('explosions per phase:', {p: len(v) for p, v in exs.items()})
print('  radius:', {p: collections.Counter(round(r[3], 1) for r in v).most_common(4) for p, v in exs.items()})
for p in snds:
    print('sounds', p, snds[p].most_common(30))
print('dmg types to you', {p: dict(c) for p, c in dmgc.items()})
print('other added (skipped types)', sorted(other.items()))
