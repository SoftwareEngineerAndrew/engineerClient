"""Item mechanics from Boss Recorder files (server packets per server tick).

    python3 bossrec_items.py [section ...]   sections: tp arrows procs cloak tnt

tp      every teleport of you: the chat that came with it (+-3 ticks), sounds at your landing (+-1 tick), rotation
arrows  arrows the server added with you as owner: per-tick volleys, speed, spread, gravity from their moves
procs   chat/sounds/hp around mask / phoenix procs and deaths
cloak   Creeper Veil on/off spacing
tnt     chat / block changes / explosions around Superboom-like events
"""
import collections, glob, gzip, json, math, os, re, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'boss-mechanics'))
from netlog import NetLog  # noqa
DIR = '/home/cam/.local/share/PrismLauncher/instances/f7/minecraft/config/engineerclient/bossrecorder/*.jsonl.gz'
SECT = set(sys.argv[1:]) or {'tp', 'arrows', 'procs', 'cloak', 'tnt'}


def load(f):
    lines = []
    with gzip.open(f, 'rt') as fh:
        for x in fh:
            try: lines.append(json.loads(x))
            except Exception: pass
    return lines


tpcat = collections.Counter(); tpsnd = collections.defaultdict(collections.Counter); tprot = collections.Counter()
leapoff = collections.Counter(); chat_tp_dt = collections.defaultdict(collections.Counter)
vol = collections.Counter(); speeds = []; spread = collections.Counter(); grav = []; drag = []; arrow_sounds = collections.Counter()
procs = []; cloak = []; tnt = collections.Counter(); tntex = []
for f in sorted(glob.glob(DIR)):
    L = load(f)
    if not L or L[0].get('k') != 'meta': continue
    me_id = L[0]['selfId']; log = NetLog(L)
    chat = [(n, m) for t, n, m in log.chat if n is not None]
    snd = log.kind('snd'); sde = log.kind('sde')
    mes = [e for e in log.kind('me')]
    def last_me(n):
        r = None
        for e in mes:
            if e[0] > n: break
            if e[4] is not None: r = e
        return r
    if 'tp' in SECT:
        # Boss Recorder 0.6.15 keeps no 'tp' for you: a teleport shows as a jump between consecutive 'me'
        # positions (the client confirms the server's teleport with a move packet the same tick).
        own_tps = []; pos = None; rot = None
        for e in mes:  # (n, x, y, z, yaw, pitch, onGround)
            if e[4] is not None: prot = rot; rot = (e[4], e[5])
            if e[1] is None: continue
            if pos and (e[1] - pos[0]) ** 2 + (e[2] - pos[1]) ** 2 + (e[3] - pos[2]) ** 2 > 16:
                own_tps.append((e[0], me_id, e[1], e[2], e[3], rot[0], rot[1], (None, None, None, None, *(prot if e[4] is not None else rot))))
            pos = (e[1], e[2], e[3])
        for e in own_tps:
            n = e[0]; x, y, z, yaw, pitch = e[2:7]
            near = [(c[0] - n, c[1]) for c in chat if abs(c[0] - n) <= 3]
            cat = 'other'
            for dt, m in near:
                if m.startswith('You have teleported to'): cat = 'leap'
                elif m.startswith('Your Implosion'): cat = cat if cat == 'leap' else 'hype'
            tpcat[cat] += 1
            for dt, m in near:
                chat_tp_dt[cat][(dt, re.sub(r'\d[\d,.]*', '#', m)[:60])] += 1
            for s in snd:
                if abs(s[0] - n) <= 1 and abs(s[3] - x) < 3 and abs(s[5] - z) < 3:
                    tpsnd[cat][(s[0] - n, s[1], round(s[6], 2), round(s[7], 3))] += 1
            pm = e[7]
            if pm and yaw is not None:
                tprot[(cat, 'same' if abs((yaw - pm[4] + 180) % 360 - 180) < 0.5 and abs(pitch - pm[5]) < 0.5 else 'changed')] += 1
            if cat == 'leap':
                tgt = next((m.split('to ')[1].rstrip('!') for dt, m in near if m.startswith('You have teleported to')), None)
                # target entity: player entity whose last pos is within 0.01 of landing
                best = None
                for eid in set(a[1] for a in log.kind('a') if a[2] == 'minecraft:player') - {me_id}:
                    mv = [mm for mm in log.moves(eid) if mm[0] <= n - 1]
                    if mv:
                        d = (x - mv[-1][1], y - mv[-1][2], z - mv[-1][3])
                        if best is None or sum(v * v for v in d) < sum(v * v for v in best): best = d
                if best: leapoff[tuple(round(v, 2) for v in best)] += 1
    if 'arrows' in SECT:
        ar = [a for a in log.kind('a') if a[2] in ('minecraft:arrow', 'minecraft:spectral_arrow') and a[-1] == me_id or (a[2] == 'minecraft:arrow' and len(a) > 13 and a[13] == me_id)]
        byn = collections.defaultdict(list)
        for a in ar: byn[a[0]].append(a)
        for n, aa in byn.items():
            vol[len(aa)] += 1
            yaws = []
            for a in aa:
                vx, vy, vz = a[6], a[7], a[8]
                sp = math.sqrt(vx * vx + vy * vy + vz * vz); speeds.append(round(sp, 2))
                yaws.append(math.degrees(math.atan2(-vx, vz)))
            if len(aa) > 1:
                yaws.sort(); spread[tuple(round(((y - yaws[len(yaws) // 2]) + 180) % 360 - 180) for y in yaws)] += 1
            for s in snd:
                if s[0] == n and abs(s[3] - aa[0][3]) < 3: arrow_sounds[(s[1], round(s[6], 2), round(s[7], 2))] += 1
            for a in aa[:1]:
                v = [e for e in log.kind('v') if e[1] == a[1]]
                if len(v) >= 2 and len(grav) < 400:
                    for v0, v1 in zip(v, v[1:]):
                        if v1[0] - v0[0] == 1 and abs(v0[3]) > 0.01:
                            grav.append(round(v1[3] - v0[3] * 0.99, 4)); drag.append(round(v1[2] / v0[2], 4) if v0[2] else None)
    if 'procs' in SECT or 'cloak' in SECT or 'tnt' in SECT:
        hp = log.kind('hp')
        for n, m in chat:
            if re.search(r"saved your life|Phoenix Pet saved|You died|became a ghost|revived you|Second Wind", m) and 'procs' in SECT:
                h = [(e[0] - n, round(e[2], 1)) for e in hp if -3 <= e[0] - n <= 3]
                s = sorted(set((x[0] - n, x[1], round(x[7], 2)) for x in snd if -2 <= x[0] - n <= 2 and abs(x[3] - 0) >= 0)
                           , key=lambda q: q[0])[:6]
                ch = [(c[0] - n, c[1][:70]) for c in chat if -2 <= c[0] - n <= 60 and c[1] != m and re.search(r'Procced|saved|ghost|died|Phoenix|Mask|revive', c[1])]
                procs.append((os.path.basename(f)[:19], n, m, h, s, ch[:4]))
            if 'Creeper Veil' in m and 'cloak' in SECT: cloak.append((os.path.basename(f)[:19], n, m))
            if re.search(r'TNT|Superboom|crypt|Crypt|blew|explo', m) and 'tnt' in SECT: tnt[re.sub(r'\d[\d,.]*', '#', m)[:90]] += 1
        if 'tnt' in SECT:
            for e in log.kind('ex'): tntex.append((os.path.basename(f)[:19], e[0], e[1:6]))

if 'tp' in SECT:
    print('self tp by category:', tpcat)
    for c in chat_tp_dt: print(' chat dt vs tp', c, chat_tp_dt[c].most_common(12))
    for c in tpsnd: print(' sounds at landing', c, tpsnd[c].most_common(10))
    print(' rotation in tp vs last sent:', tprot)
    print(' leap landing - nearest player server pos:', leapoff.most_common(10))
if 'arrows' in SECT:
    print('arrow volleys (arrows per tick):', vol.most_common())
    print('speeds:', collections.Counter(speeds).most_common(10))
    print('yaw spread around middle arrow:', spread.most_common(8))
    print('gravity dvy (after 0.99 drag) :', collections.Counter(grav).most_common(6), 'h ratio', collections.Counter(drag).most_common(4))
    print('sounds on volley tick:', arrow_sounds.most_common(6))
if 'procs' in SECT:
    for p in procs[:40]: print(p)
if 'cloak' in SECT:
    for c in cloak[:40]: print(c)
if 'tnt' in SECT:
    print(tnt.most_common(20)); print('explosions:', len(tntex), tntex[:10])
