"""Own movement in the boss: ground speed per tick (from `me`), jump rise, vy of 3.038 bounces by file,
attribute-ish synced data. python3 movement.py self.jsonl"""
import json, sys, collections, statistics as st

ev = collections.defaultdict(list)
for line in open(sys.argv[1]):
    o = json.loads(line)
    ev[o["f"]].append(o)

byfile = collections.defaultdict(collections.Counter)
speeds = collections.Counter()
perfile_p95 = {}
jumps = []
for f, L in ev.items():
    for o in L:
        if o["k"] == "v" and o["a"][2] >= 2:
            byfile[f[:19]][round(o["a"][2], 3)] += 1
    me = [(o["n"], o["a"]) for o in L if o["k"] == "me" and o["a"][0] is not None]
    tps = set(o["n"] for o in L if o["k"] in ("tp", "v"))
    fs = []
    for (t1, a1), (t2, a2) in zip(me, me[1:]):
        if t2 - t1 != 1 or t1 in tps or t2 in tps:
            continue
        if a1[5] and a2[5] and abs(a2[1] - a1[1]) < 0.01:  # both on ground, flat
            s = ((a2[0] - a1[0]) ** 2 + (a2[2] - a1[2]) ** 2) ** 0.5
            if s > 0.05:
                speeds[round(s, 2)] += 1
                fs.append(s)
    if fs:
        perfile_p95[f[:19]] = round(st.quantiles(fs, n=20)[-1], 3)
    # jumps: onGround -> airborne with first dy > 0.3 and no velocity packet near
    for i in range(1, len(me) - 1):
        (t0, a0), (t1, a1) = me[i - 1], me[i]
        if a0[5] and not a1[5] and t1 - t0 == 1 and not any(t in tps for t in range(t0 - 1, t0 + 15)):
            dy = a1[1] - a0[1]
            if 0.3 < dy < 0.5:
                ys = [a[1] for t, a in me[i:i + 15]]
                jumps.append((round(dy, 4), round(max(ys) - a0[1], 3)))
print("3.038 vs 2.25 per file:", {f: dict(c) for f, c in byfile.items() if 3.038 in c})
print("files with only 2.25:", sum(1 for c in byfile.values() if 3.038 not in c))
print("flat ground speeds (blocks/tick) top:", speeds.most_common(25))
print("per-file 95th percentile ground speed:", perfile_p95)
print("jumps: first-tick dy and rise:", collections.Counter(jumps).most_common(10))
