"""Fall damage and the void: landings after falls of >= 4 blocks (hp change within 5 ticks),
and what happens when your y goes below 52 (P4 lava floor is y55-58). python3 falls.py self.jsonl"""
import json, sys, collections

ev = collections.defaultdict(list)
for line in open(sys.argv[1]):
    o = json.loads(line)
    ev[o["f"]].append(o)
land = collections.Counter()
lowcases = []
for f, L in ev.items():
    me = [(o["n"], o["a"]) for o in L if o["k"] == "me" and o["a"][0] is not None]
    hps = [(o["n"], o["a"][0]) for o in L if o["k"] == "hp"]
    peak = None
    for (t0, a0), (t1, a1) in zip(me, me[1:]):
        if not a0[5]:
            peak = max(peak or a0[1], a0[1])
        if a1[5] and not a0[5] and peak is not None:
            fall = peak - a1[1]
            if fall >= 4:
                before = [h for n, h in hps if t1 - 10 <= n < t1]
                after = [h for n, h in hps if t1 <= n <= t1 + 5]
                if before and after:
                    land[(min(int(fall // 4) * 4, 40), "drop" if min(after) < before[-1] - 0.05 else "none")] += 1
            peak = None
        if a1[5]:
            peak = None
    low = [(t, a) for t, a in me if a[1] < 52]
    if low:
        t, a = low[0]
        ctx = [(o["n"] - t, o["k"], (o.get("m") or str(o.get("a")))[:70]) for o in L if -5 <= o["n"] - t <= 60 and o["k"] in ("tp", "chat", "hp", "v")]
        lowcases.append((f[:19], round(a[1], 1), [round(a[0], 1), round(a[2], 1)], ctx[:12]))
print("landings (fall bucket, hp drop?):", sorted(land.items()))
for c in lowcases:
    print(c)
