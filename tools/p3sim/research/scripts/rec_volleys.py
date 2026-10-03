"""Boss Recorder: the boss attacks' entities in detail.

For each (phase, type) of attack entity: who owns it (owner entity's type; withers named by where they
spawned: y>200 Maxor, 150-200 Storm, 100-150 Goldor/Necron-P3, <100 Necron-P4), how many spawn on the same
server tick ("volley"), gaps between volleys, spawn offset from the owner, aim (pitch of the velocity), and
the horizontal distance from you at spawn. Then own-health drops matched to the damage chat line on the
same/next tick.

    python3 rec_volleys.py
"""
import collections
import glob
import gzip
import json
import math
import re

D = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/bossrecorder/'
MARKS = [('P1', "[BOSS] Maxor: WELL! WELL!"), ('P2', '[BOSS] Storm: Pathetic Maxor'),
         ('P3', '[BOSS] Goldor: Who dares'), ('P4', '[BOSS] Necron: You went further'),
         ('END', '[BOSS] Necron: All this, for nothing')]
ATTACK = {'minecraft:wither_skull', 'minecraft:fireball', 'minecraft:small_fireball', 'minecraft:tnt',
          'minecraft:lightning_bolt'}
DMG = re.compile(r"^(Maxor's|Storm's|Goldor's|Necron's|A Crypt Wither Skull|The Arrow Trap)(.*?)(?:hit you for|hitting you for) ([\d,.]+)")


def wname(y):
    return 'Maxor' if y > 200 else 'Storm' if y > 150 else 'Goldor/NecronP3' if y > 100 else 'NecronP4'


def q(a):
    a = sorted(a)
    return [round(a[int(len(a) * p)], 2) for p in (0, .25, .5, .75)] + [round(a[-1], 2)] if a else []


S = collections.defaultdict(lambda: collections.defaultdict(list))
owners = collections.defaultdict(collections.Counter)
hpm = collections.defaultdict(list)
unmatched = collections.Counter()
for f in sorted(glob.glob(D + '*.jsonl.gz')):
    try:
        L = [json.loads(l) for l in gzip.open(f, 'rt')]
    except Exception:
        continue
    marks = {}
    chat = []
    for l in L:
        if l['k'] == 'chat' and l.get('n') is not None:
            chat.append((l['n'], l['m']))
            for p, s in MARKS:
                if l['m'].startswith(s) and p not in marks:
                    marks[p] = l['n']
    if 'P1' not in marks:
        continue

    def phase(n):
        cur = None
        for p, _ in MARKS:
            if p in marks and n >= marks[p]:
                cur = p
        return cur

    types, pos = {}, {}
    me = None
    hp = None
    drops = []
    vol = collections.defaultdict(list)  # (phase,type,owner) -> [(n, ...)]
    for l in L:
        if l['k'] != 'net':
            continue
        for e in l['d']:
            n, k = e[0], e[1]
            if k == 'a':
                types[e[2]] = (e[3], e[5])
                pos[e[2]] = (e[4], e[5], e[6])
                ph = phase(n)
                if e[3] in ATTACK and ph not in (None, 'END'):
                    o = e[13] if len(e) > 13 else 0
                    ot = types.get(o)
                    on = 'none' if not o else (wname(ot[1]) if ot and ot[0] == 'minecraft:wither' else (ot[0].split(':')[1] if ot else 'unknown'))
                    owners[(ph, e[3])][on] += 1
                    vx, vy, vz = e[7], e[8], e[9]
                    h = math.hypot(vx, vz)
                    pitch = math.degrees(math.atan2(-vy, h)) if (h or vy) else None
                    op = pos.get(o)
                    off = math.dist(op, (e[4], e[5], e[6])) if op else None
                    dme = math.hypot(e[4] - me[0], e[6] - me[2]) if me else None
                    vol[(ph, e[3], on)].append((n, pitch, off, dme, e[5]))
            elif k in ('m', 'tp', 'sy') and e[2] in pos and e[3] is not None:
                pos[e[2]] = (e[3], e[4], e[5])
            elif k == 'me' and e[2] is not None:
                me = (e[2], e[3], e[4])
            elif k == 'hp':
                if hp is not None and e[2] < hp - 0.01 and phase(n) not in (None, 'END'):
                    drops.append((n, hp - e[2], hp))
                hp = e[2]
    for key, v in vol.items():
        d = S[key]
        byt = collections.Counter(x[0] for x in v)
        d['per_tick'] += list(byt.values())
        ts = sorted(byt)
        d['gap'] += [b - a for a, b in zip(ts, ts[1:])]
        d['pitch'] += [x[1] for x in v if x[1] is not None]
        d['off'] += [x[2] for x in v if x[2] is not None]
        d['dme'] += [x[3] for x in v if x[3] is not None]
        d['tin'] += [x[0] - marks[key[0]] for x in v]
        d['runs'].append(1)
    # match own health drops to damage chat within +-1 tick
    for n, amt, before in drops:
        lines = [m for cn, m in chat if abs(cn - n) <= 1 and DMG.match(m)]
        if lines:
            mm = DMG.match(lines[0])
            hpm[(mm.group(1) + mm.group(2)).strip()].append((round(amt, 1), before))
        else:
            unmatched[phase(n)] += 1
for key in sorted(S):
    d = S[key]
    print(f"{key[0]} {key[1]} owner={key[2]}: runs={len(d['runs'])} n={len(d['tin'])}")
    print(f"   per-tick q={q(d['per_tick'])} gap q={q(d['gap'])} t-in-phase q={q(d['tin'])}")
    print(f"   pitch q={q(d['pitch'])} offset-from-owner q={q(d['off'])} horiz-dist-from-you q={q(d['dme'])}")
print()
for k, c in sorted(owners.items()):
    print('owners', k, dict(c))
print()
for k, v in hpm.items():
    c = collections.Counter(a for a, _ in v)
    print('hp drop with', repr(k), 'n=', len(v), 'drop (hearts of 40-max hp) top:', c.most_common(5))
print('hp drops with no damage line, by phase:', dict(unmatched))
