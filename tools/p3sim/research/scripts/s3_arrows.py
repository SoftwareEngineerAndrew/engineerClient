"""S3 Arrow Align: item frames at x=-2 (index (y-120) + (z-75)*5, as Odin), rotations (synced data 10),
spawn layouts vs Odin's nine solutions, clicks, and completion timing, across all Boss Recorder files."""
import collections
import re
import devlib

SOL = [
    [7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, -1, -1, 7, 1],
    [-1, -1, 7, 7, 5, -1, 7, 1, -1, 5, -1, -1, -1, -1, -1, -1, 7, 5, -1, 1, -1, -1, 7, 7, 1],
    [7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, -1, 7, 5, -1, -1, -1, -1, 5, -1, -1, -1, 3, 3],
    [5, 3, 3, 3, -1, 5, -1, -1, -1, -1, 7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, -1],
    [5, 3, 3, 3, 3, 5, -1, -1, -1, 1, 7, 7, -1, -1, 1, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1],
    [7, 7, 7, 7, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1],
    [-1, -1, -1, -1, -1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1],
    [-1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, 7, 7, 7, 7, 1, -1, -1, -1, -1, -1],
    [-1, -1, -1, -1, -1, -1, 1, -1, 1, -1, 7, 1, 7, 1, 3, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1],
]
extras = collections.Counter(); layouts = collections.Counter(); nframes = collections.Counter(); initial_ok = collections.Counter()
init_off = collections.Counter(); done_gap = collections.Counter(); solved_match = collections.Counter()
step = collections.Counter(); clickgap = collections.Counter(); sound = collections.Counter(); facing = collections.Counter()
for f in devlib.files():
    meta, chat, blocks, ents = devlib.load(f)
    pos = {}; rot = collections.defaultdict(list)
    for n, k, fl in ents:
        if k == 'a' and 'item_frame' in fl[1] and round(fl[2]) == -2 and 120 <= fl[3] <= 124 and 75 <= fl[4] <= 79:
            pos[fl[0]] = (round(fl[3]) - 120) + (round(fl[4]) - 75) * 5; facing[(fl[8], fl[11])] += 1
    for n, k, fl in ents:
        if k == 'd' and fl[0] in pos:
            for i, v in fl[1]:
                if i == 10: rot[fl[0]].append((n, v))
        if k == 'snd' and 'item_frame' in fl[0] and abs(fl[2] + 2) < 2: sound[(fl[0], fl[1], round(fl[5], 2), round(fl[6], 2))] += 1
    if not pos: continue
    idx = set(pos.values())
    nframes[len(idx)] += 1
    lay = [i for i, s in enumerate(SOL) if {j for j in range(25) if s[j] >= 0} <= idx and len(idx - {j for j in range(25) if s[j] >= 0}) == 2]
    if lay: extras[tuple(sorted(idx - {j for j in range(25) if SOL[lay[0]][j] >= 0}))] += 1
    pos = {e: i for e, i in pos.items() if not lay or SOL[lay[0]][i] >= 0}
    layouts[lay[0] if lay else 'none:' + ','.join(map(str, sorted(idx)))] += 1
    # S3 window: third section's progress lines
    ends = sorted({n for n, m in chat if (mm := re.search(r'\((\d+)/(\d+)\)$', m)) and mm.group(1) == mm.group(2)})
    devs = [n for n, m in chat if 'completed a device!' in m]
    if lay:
        s = SOL[lay[0]]
        first = {e: rot[e][0][1] for e in pos if rot[e]}
        off = [(s[pos[e]] - first[e]) % 8 for e in first]
        for o in off: init_off[o] += 1
        initial_ok[sum(o == 0 for o in off)] += 1
        # completion: the tick every frame matches; the device line after it
        state = dict(first); allrot = sorted((n, e, v) for e in pos for n, v in rot[e][1:])
        prev = {}
        for n, e, v in allrot:
            step[(v - state[e]) % 8] += 1
            if e in prev: clickgap[min(n - prev[e], 30)] += 1
            prev[e] = n; state[e] = v
            if all(state[x] == s[pos[x]] for x in state):
                d = [m for m in devs if m >= n - 2]
                done_gap[d[0] - n if d else None] += 1
                solved_match['solved'] += 1
                break
print("extra (non-arrow) frames", dict(extras)); print("frames per layout", dict(nframes)); print('layout (Odin index)', dict(layouts))
print('frame facing (yaw, data)', dict(facing))
print('initial clicks needed per frame', sorted(init_off.items())); print('frames already right at spawn', sorted(initial_ok.items()))
print('rotation step per update', dict(step)); print('ticks between updates of one frame', sorted(clickgap.items()))
print('device line - solving update', sorted(done_gap.items(), key=str)); print('sounds', dict(sound))
