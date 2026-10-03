"""Bounce vy split and hp around bounces; plus hp drop events and the chat lines next to them.
python3 bounce_hp.py self.jsonl"""
import json, sys, collections, re

ev = collections.defaultdict(list)
for line in open(sys.argv[1]):
    o = json.loads(line)
    ev[o["f"]].append(o)

split = collections.defaultdict(collections.Counter)
hpdelta = collections.defaultdict(list)
drops = collections.Counter()
maxhp = collections.Counter()
fire = collections.Counter()
for f, L in ev.items():
    hps = [(o["n"], o["a"][0]) for o in L if o["k"] == "hp"]
    for _, h in hps:
        maxhp[round(h)] += 1
    chats = [(o["n"], o["m"]) for o in L if o["k"] == "chat"]
    ys = [(o["n"], o["a"][1], o["a"][5]) for o in L if o["k"] == "me" and o["a"][0] is not None]
    for o in L:
        if o["k"] == "v" and o["a"][2] >= 2:
            n, vy = o["n"], round(o["a"][2], 3)
            bp = any(abs(cn - n) <= 2 and "bone plating" in m for cn, m in chats)
            before = [h for hn, h in hps if n - 20 <= hn < n]
            after = [h for hn, h in hps if n <= hn <= n + 10]
            prev_vy = None
            yb = [y for yn, y, g in ys if n - 3 <= yn <= n]
            split[vy]["boneplating" if bp else "no-bp"] += 1
            split[vy]["P4" if yb and min(yb) < 80 else "P3" if yb and min(yb) < 140 else "P2+"] += 1
            if before and after:
                hpdelta[vy].append(round(min(after) - before[-1], 2))
            # was the previous bounce recent (still airborne)?
            # fire: entity data flag 0 bit 0 on self within 10 ticks after
    for o in L:
        if o["k"] == "d":
            for idx, val in o["a"][1]:
                if idx == 0 and isinstance(val, int):
                    fire["onfire" if val & 1 else "not"] += 1
    for (n0, h0), (n1, h1) in zip(hps, hps[1:]):
        if h1 < h0 - 0.5:
            near = [m for cn, m in chats if 0 <= cn - n1 <= 1 or 0 <= n1 - cn <= 1]
            key = "; ".join(re.sub(r"[\d,.]+", "#", m)[:70] for m in near[:2]) or "(none)"
            drops[key] += 1
for vy, c in split.items():
    d = hpdelta[vy]
    print(vy, dict(c), "hp delta (min after - before):", collections.Counter(d).most_common(8))
print("max-ish hp values:", sorted(maxhp.items())[-6:])
print("self flag0 on-fire bit in entity data:", fire)
print("hp drop chat neighbours:")
for k, c in drops.most_common(30):
    print(" ", c, k)
