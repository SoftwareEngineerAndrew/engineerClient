"""Median own y trajectory (rise above the last sent y) for each client move after a lava bounce, per vy, using
only bounces with a sent position on every tick for 30 ticks. python3 bounce_traj.py self.jsonl"""
import json, sys, collections, statistics as st

ev = collections.defaultdict(list)
for line in open(sys.argv[1]):
    o = json.loads(line)
    ev[o["f"]].append(o)
traj = collections.defaultdict(list)
for f, L in ev.items():
    me = [(o["t"], o["n"], o["a"]) for o in L if o["k"] == "me" and o["a"][1] is not None]
    for o in L:
        if o["k"] == "v" and o["a"][2] >= 2:
            # moves sent after the client tick the packet was handled on
            pre = [a[1] for t, n, a in me if t <= o["t"]]
            post = [a[1] for t, n, a in me if t > o["t"]][:30]
            if pre and len(post) == 30:
                traj[round(o["a"][2], 3)].append([p - pre[-1] for p in post])
for vy, T in traj.items():
    med = [round(st.median(T[i][k] for i in range(len(T))), 3) for k in range(30)]
    print(vy, "n=%d" % len(T), med)
# vanilla model: first tick still in lava (vel*0.5 or *0.8 vertical, -0.02), then air (v-0.08)*0.98
for vy in (2.25, 3.038):
    for name, first in (("air-only", None), ("lava x0.5", 0.5), ("lava x0.8", 0.8)):
        y, v, out = 0, vy, []
        for k in range(30):
            y += v
            out.append(round(y, 2))
            v = (v * first - 0.02) if (k == 0 and first) else (v - 0.08) * 0.98
        print("model", vy, name, out[:12], "apex", max(out))
