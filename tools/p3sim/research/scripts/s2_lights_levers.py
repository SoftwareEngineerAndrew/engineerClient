"""S2 Lights (levers x58-62 y133-136 z142, lamps z143) and the eight section levers: states, toggles, completion,
sounds and chat lines, across all Boss Recorder files."""
import collections
import re
import devlib

LEVERS = {(106, 124, 113), (94, 124, 113), (27, 124, 127), (23, 132, 138), (14, 122, 55), (2, 122, 55), (86, 128, 46), (84, 121, 34)}
kinds = collections.Counter(); first_on = collections.Counter(); lamp_vs_lever = collections.Counter(); final = collections.Counter()
done_gap = collections.Counter(); sounds = collections.Counter(); lever_snd = collections.Counter(); lchat = collections.Counter()
lever_states = collections.Counter(); lever_chat_gap = collections.Counter(); lamp_lag = collections.Counter(); oncount = collections.Counter()
pattern_at_first = collections.Counter()
for f in devlib.files():
    meta, chat, blocks, ents = devlib.load(f)
    lb = [r for r in blocks if 58 <= r[1] <= 62 and 133 <= r[2] <= 136 and r[3] in (142, 143)]
    for r in lb: kinds[(r[3], r[4].split('[')[0])] += 1
    lev = sorted((n, x, y, 'powered=true' in s) for n, x, y, z, s in lb if z == 142 and 'lever' in s)
    lamp = sorted((n, x, y, 'lit=true' in s) for n, x, y, z, s in lb if z == 143 and 'lamp' in s)
    # lamp follows its own lever? for each lever change, lamp changes at same (x,y) within 3 ticks
    for n, x, y, p in lev:
        same = [m for m, a, b, l in lamp if a == x and b == y and n - 1 <= m <= n + 5 and l == p]
        lamp_vs_lever['own lamp follows' if same else 'own lamp not'] += 1
        if same: lamp_lag[same[0] - n] += 1
        others = {(a, b) for m, a, b, l in lamp if (a, b) != (x, y) and m == n}
        lamp_vs_lever[f'other lamps same tick: {len(others)}'] += 1
    st = {}
    for n, x, y, p in lev: st[(x, y)] = p
    final[tuple(sorted(k for k, v in st.items() if v))] += 1
    devs = [n for n, m in chat if 'completed a device!' in m]
    for n, x, y, p in lev:
        d = [m for m in devs if n - 2 <= m <= n + 5]
        if d: done_gap[d[0] - n] += 1
    for n, k, fl in ents:
        if k == 'snd' and 57 <= fl[2] <= 63 and 132 <= fl[3] <= 137 and 141 <= fl[4] <= 144:
            sounds[(fl[0], round(fl[5], 2), round(fl[6], 2))] += 1
        if k == 'snd' and any(abs(fl[2] - (x + .5)) < 1 and abs(fl[3] - (y + .5)) < 1 and abs(fl[4] - (z + .5)) < 1 for x, y, z in LEVERS):
            lever_snd[(fl[0], fl[1], round(fl[5], 2), round(fl[6], 2))] += 1
    for n, x, y, z, s in blocks:
        if (x, y, z) in LEVERS: lever_states[s.split('[', 1)[1] if '[' in s else s] += 1
    for n, m in chat:
        if 'lever' in m.lower(): lchat[re.sub(r'^\w+ ', '<name> ', re.sub(r'\(\d/\d\)', '(k/N)', m))] += 1
    # lever chat line vs block change
    for n, m in chat:
        if 'activated a lever!' in m:
            c = [b[0] for b in blocks if (b[1], b[2], b[3]) in LEVERS and 'powered=true' in b[4] and n - 10 <= b[0] <= n + 10]
            lever_chat_gap[c[0] - n if c else None] += 1
print('block kinds (z, block)', dict(kinds))
print('lamp vs lever', dict(lamp_vs_lever)); print('own lamp lag', dict(lamp_lag))
print('final ON lever sets', final.most_common(6))
print('device line - lever change', sorted(done_gap.items()))
print('sounds at lights', dict(sounds)); print('sounds at the 8 levers', dict(lever_snd))
print('lever block states', lever_states.most_common(8)); print('lever chat', dict(lchat))
print('lever chat - block powered', sorted(lever_chat_gap.items(), key=str))
