"""Replays I4Complete's triggers over the recorded main-server i4 attempts (server ticks = the recorder's n, the
pings Odin's TickEvent.Server counts): when each would fire relative to the board's completion line - whoever's
"completed a device!" line after which the board never lights again - and any fire before it (a false alarm). The
plate condition is left out, so step-off resets show up as their own case.
    I4_OUT=~/Cluade/maxor-work/i4/out python3 title_replay.py
"""
import glob, json, os, collections
from board import attempts, OUT

emer = collections.Counter(); tag = collections.Counter(); early = collections.Counter()
for f in sorted(glob.glob(os.path.join(OUT, 'rec', '*.json'))):
    d = json.load(open(f))
    if 'stuck' not in (d.get('server') or ''): continue
    ev = attempts(d)
    stands = sorted((nm[1], (nm[0], nm[1], 'stand', nm[2])) for s in d['stands'].values()
                    if s.get('spawn') and abs(s['spawn'][1] - 63.5) < 0.1 and abs(s['spawn'][3] - 34.5) < 0.1
                    for nm in s['names'] if nm[2] in ('Active', 'Device', 'Inactive'))
    allev = sorted([(e[1], e) for e in ev] + stands)
    # the board's completion lines: per P3, the first device line after which no light follows
    p3s = [i for i, (_, e) in enumerate(allev) if e[2] == 'p3'] + [len(allev)]
    for a, b in zip(p3s, p3s[1:]):
        seg = [e for _, e in allev[a:b]]
        lights = [e[0] for e in seg if e[2] == 'light']
        if not lights: continue
        done = next((e[0] for e in seg if e[2] == 'done' and not e[3].startswith('#') and e[0] >= lights[-1]), None)
        if done is None: continue
        grid = -1; lit = None; hit_at = -1; fired = None; tag_was = None; tag_fired = None; last_n = seg[0][0]; plate = '1'
        for n, seq, kind, val in seg:
            for t in range(last_n + 1, n + 1):
                if fired is None and hit_at >= 0 and lit is None and grid >= 0 and t >= hit_at + 1 + (grid - (hit_at + 1)) % 10 + 1:
                    fired = (t, plate); hit_at = -1
            last_n = n
            if kind == 'light': lit = val; grid = n % 10
            elif kind == 'blue' and val == lit: lit = None; hit_at = n
            elif kind == 'plate': plate = val
            elif kind == 'stand':
                active = val == 'Active'
                if active and tag_was is False and tag_fired is None: tag_fired = n
                if val != 'Inactive': tag_was = active
        if fired is None: emer['not by the line +20'] += 1
        elif fired[0] < done: early['off the plate' if fired[1] == '0' else 'ON the plate'] += 1
        else: emer[fired[0] - done] += 1
        if tag_fired is not None: tag[tag_fired - done] += 1
print('emeralds-stopped trigger, ticks after the completion line:', sorted(emer.items(), key=str))
print('  fired before the line:', dict(early))
print('device-tag trigger, ticks after the line:', sorted(tag.items()))
