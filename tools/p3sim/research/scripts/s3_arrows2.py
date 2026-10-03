"""S3 Arrow Align, second pass: best Odin layout (arrow frames + extra frames), rotations first seen well before
P3 (the spawn state), rotation changes before P3, frame removal/re-add, and frames around completion."""
import collections
import devlib
from s3_arrows import SOL  # noqa: E402  (runs the first pass too)

print('\n-- second pass')
pre = collections.Counter(); pre_upd = collections.Counter(); idsets = collections.Counter(); best = collections.Counter()
extra_cells = collections.Counter(); per_run_ok = collections.Counter()
for f in devlib.files():
    meta, chat, blocks, ents = devlib.load(f)
    p3 = next((n for n, m in chat if 'Who dares trespass' in m), None)
    pos = {}
    for n, k, fl in ents:
        if k == 'a' and 'item_frame' in fl[1] and round(fl[2]) == -2 and 120 <= fl[3] <= 124 and 75 <= fl[4] <= 79:
            pos[fl[0]] = (round(fl[3]) - 120) + (round(fl[4]) - 75) * 5
    if not pos or p3 is None:
        continue
    idx = set(pos.values())
    idsets[len(pos) == len(idx)] += 1
    cands = [(len({j for j in range(25) if s[j] >= 0}), i) for i, s in enumerate(SOL) if {j for j in range(25) if s[j] >= 0} <= idx]
    if not cands:
        best['none'] += 1; continue
    li = max(cands)[1]; s = SOL[li]
    ex = tuple(sorted(idx - {j for j in range(25) if s[j] >= 0}))
    best[(li, len(ex))] += 1; extra_cells[(li, ex)] += 1
    seen = {}; ok = 0; tot = 0
    for n, k, fl in ents:
        if k == 'd' and fl[0] in pos and s[pos[fl[0]]] >= 0:
            for i, v in fl[1]:
                if i != 10:
                    continue
                if fl[0] not in seen:
                    seen[fl[0]] = v
                    if n < p3 - 200:
                        pre[(s[pos[fl[0]]] - v) % 8] += 1; tot += 1; ok += (s[pos[fl[0]]] == v)
                elif n < p3 and v != seen[fl[0]]:
                    pre_upd[f.split('/')[-1][:16]] += 1
    if tot: per_run_ok[f'{ok}/{tot}'] += 1
print('one entity per cell (no respawn)', dict(idsets)); print('best layout (index, n extra frames)', dict(best))
print('extra frames by layout', dict(extra_cells))
print('clicks needed at first sight, >200 ticks before P3', sorted(pre.items()))
print('frames right at first sight per run', dict(per_run_ok))
print('rotation changes before P3 starts, by file', dict(pre_upd))
