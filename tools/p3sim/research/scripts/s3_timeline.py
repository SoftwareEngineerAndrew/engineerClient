"""One run's Arrow Align frames over time: `python3 s3_timeline.py <file-substring>`.
Prints the board (clicks still needed per arrow frame, '.' no frame, '*' extra frame) whenever it changes,
with boss lines, device lines and rotate sounds."""
import sys
import devlib
from s3_arrows2 import SOL  # noqa

f = next(p for p in devlib.files() if sys.argv[1] in p)
meta, chat, blocks, ents = devlib.load(f)
pos = {}
for n, k, fl in ents:
    if k == 'a' and 'item_frame' in fl[1] and round(fl[2]) == -2 and 120 <= fl[3] <= 124 and 75 <= fl[4] <= 79:
        pos[fl[0]] = (round(fl[3]) - 120) + (round(fl[4]) - 75) * 5
idx = set(pos.values())
li = max((len({j for j in range(25) if s[j] >= 0}), i) for i, s in enumerate(SOL) if {j for j in range(25) if s[j] >= 0} <= idx)[1]
s = SOL[li]
print(f, 'layout', li)
state = {}
ev = []
for n, k, fl in ents:
    if k == 'd' and fl[0] in pos:
        for i, v in fl[1]:
            if i == 10: ev.append((n, 0, pos[fl[0]], v))
    elif k == 'snd' and 'rotate_item' in fl[0]: ev.append((n, 1, None, None))
    elif k == 'r' and any(e in pos for e in fl[0]): ev.append((n, 2, None, None))
for n, m in chat:
    if 'Storm:' in m or 'Goldor:' in m or 'device' in m or 'Maxor:' in m: ev.append((n, 3, m, None))
ev.sort(key=lambda e: (e[0], e[1]))
last = None; snd = 0
for n, kind, a, b in ev:
    if kind == 0:
        state[a] = b
        def cell(i):
            if i in idx and s[i] < 0: return '*'
            if i not in state or s[i] < 0: return '.'
            return str((s[i] - state[i]) % 8)
        board = ' '.join(''.join(cell(y + z * 5) for y in range(5)) for z in range(5))
        if board != last: print(f'{n:6} {board}  snd={snd}'); last = board; snd = 0
    elif kind == 1: snd += 1
    elif kind == 2: print(f'{n:6} frames removed (out of range)')
    else: print(f'{n:6}   {a}')
