"""Per-run Maxor event timelines (relative to s0 on the corrected server clock) -> out/<run>.json"""
import json, os, sys, math
from concurrent.futures import ProcessPoolExecutor
import mlib as M

OUT = os.environ.get('MAXOR_OUT', 'out')

INTRO = {"[BOSS] Maxor: I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU.": 'intro2',
         "[BOSS] Maxor: DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.": 'intro3',
         "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!": 'tooyoung'}


def near(a, b, r=1.0):
    return abs(a[0] - b[0]) <= r and abs(a[2] - b[2]) <= r


def one(path):
    r = M.Run(path)
    res = {'run': r.name, 'server': M.server_of(r.name), 'party': r.party, 'self': r.self_name,
           'clean': [r.clean_seconds, r.seconds]}
    if r.maxor() is None:
        res['skip'] = 'no maxor'
        return res
    ev = []
    for s, m in r.window_chat():
        if m in INTRO:
            ev.append((s, INTRO[m], None))
        elif m in M.STUN:
            ev.append((s, 'stun', m[14:20]))
        elif m == M.ENRAGE:
            ev.append((s, 'enrage', None))
        elif m == M.CHARGING:
            ev.append((s, 'charging', None))
        elif m == M.STORM:
            ev.append((s, 'storm', None))
        elif M.PICK.match(m):
            ev.append((s, 'pick', M.PICK.match(m).group(1)))
        elif M.PLACED.match(m):
            ev.append((s, 'placedline', int(M.PLACED.match(m).group(1))))
        elif m.startswith('[BOSS] Maxor:'):
            ev.append((s, 'maxorline', m[14:60]))
    types = {}; names = {}; maxor = None
    for n_, t_, ms_, k_, f_ in r.stream:
        if k_ == 'a' and f_[1] == 'minecraft:player' and len(f_) > 12:
            names[f_[0]] = f_[12]
    pos = {}   # entity id -> last (x, y, z)
    track = {}  # name -> [(s, x, y, z)]
    mtrack = []
    for s, t, ms, k, f in r.window_net(lo=-150):
        if k == 'a':
            eid, typ = f[0], f[1]
            types[eid] = typ
            p = (f[2], f[3], f[4])
            pos[eid] = p
            if typ == 'minecraft:wither' and maxor is None:
                maxor = eid
                ev.append((s, 'wither', p))
            if typ == 'minecraft:end_crystal':
                where = 'W' if near(p, M.WEST) else 'E' if near(p, M.EAST) else \
                    ('topW' if near(p, (64.5, 0, 50.5)) else 'topE' if near(p, (82.5, 0, 50.5)) else 'other')
                ev.append((s, 'crystal+', where))
                types[eid] = 'crystal:' + where
        elif k == 'r':
            for eid in f[0]:
                if str(types.get(eid, '')).startswith('crystal:'):
                    ev.append((s, 'crystal-', types[eid][8:]))
        elif k == 'b':
            x, y, z, pi = f
            st = r.block(pi) or ''
            if (x, z) in ((52, 41), (94, 41)) and y == 224:
                ev.append((s, 'plate', ('W' if x == 52 else 'E', 'powered=true' in st)))
            elif (x, z) == (73, 73) and 221 <= y <= 224:
                ev.append((s, 'col%d' % y, st.replace('minecraft:', '').split('[')[0]))
        elif k == 'bb':
            if f[0] in ('add', 'progress') and len(f) > 2:
                prog = f[3] if f[0] == 'add' else f[2]
                if isinstance(prog, (int, float)):
                    ev.append((s, 'bar', prog))
        elif k == 'd' and maxor is not None and f[0] == maxor:
            for idx, v in f[1]:
                if idx == 9:
                    ev.append((s, 'mhp', v))
        if k in ('m', 'tp', 'sy') and f[1] is not None:
            eid = f[0]
            p = (f[1], f[2], f[3])
            pos[eid] = p
            if eid == maxor:
                mtrack.append((s, round(p[0], 3), round(p[1], 3), round(p[2], 3)))
            elif eid in names:
                track.setdefault(names[eid], []).append((s, round(p[0], 3), round(p[1], 3), round(p[2], 3)))
        elif k == 'me' and f[0] is not None:
            track.setdefault(r.self_name, []).append((s, round(f[0], 3), round(f[1], 3), round(f[2], 3)))
        elif k == 'msw':
            ev.append((s, 'selfswing', None))
    ev.sort(key=lambda e: e[0])
    res.update({'events': ev, 'maxor': mtrack, 'players': track, 's0n': r.s0n, 's0': r.s0,
                'clean_spans': [(a - r.s0, b - r.s0) for a, b in r.clean_spans if b - r.s0 > -200 and a - r.s0 < 1200]})
    return res


def main():
    os.makedirs(OUT, exist_ok=True)
    paths = M.runs(sys.argv[1] if len(sys.argv) > 1 else '2026-10-02')
    with ProcessPoolExecutor(8) as ex:
        for res in ex.map(one, paths):
            json.dump(res, open(os.path.join(OUT, res['run'].replace('.jsonl.gz', '.json')), 'w'))
            ev = res.get('events', [])
            print('%-34s %-6s clean %s  %s' % (res['run'], res['server'], res['clean'], res.get('skip') or len(ev)))


if __name__ == '__main__':
    main()
