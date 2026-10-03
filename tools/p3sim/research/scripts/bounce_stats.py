"""Lava bounce statistics. python3 bounce_stats.py self.jsonl lava.json
Contact model: player box (+-0.3, 1.8 tall) overlapping a lava cell's fluid (source height 8/9, or full if lava above).
Reports, per bounce (velocity on self with vy >= 2): vy, horizontal, delay from first contact tick to the packet,
the contact situation, chat within 3 ticks; and every contact episode that was NOT followed by a bounce."""
import json, sys, collections, math

lava = set(tuple(p) for p in json.load(open(sys.argv[2])))


def surface(bx, by, bz):
    return by + (1.0 if (bx, by + 1, bz) in lava else 8 / 9)


def depth(x, y, z):
    """how far feet are below the highest touched lava surface (>0 = touching), plus touched-cell count"""
    best, cnt = -9, 0
    for bx in range(math.floor(x - 0.3), math.floor(x + 0.3) + 1):
        for bz in range(math.floor(z - 0.3), math.floor(z + 0.3) + 1):
            for by in range(math.floor(y) - 1, math.floor(y + 1.8) + 1):
                if (bx, by, bz) in lava and y < surface(bx, by, bz) and y + 1.8 > by:
                    best = max(best, surface(bx, by, bz) - y)
                    cnt += 1
    return best, cnt


ev = collections.defaultdict(list)
for line in open(sys.argv[1]):
    o = json.loads(line)
    ev[o["f"]].append(o)

vy_c, delay_c, hz, region, contact_noBounce, chat_c = (collections.Counter() for _ in range(6))
bounce_after = collections.Counter()
gaps = []
for f, L in ev.items():
    pos_by_n = {}
    bounces = []
    for i, o in enumerate(L):
        if o["k"] == "me" and o["a"][0] is not None:
            pos_by_n.setdefault(o["n"], []).append(o["a"])
        elif o["k"] == "v" and o["a"][2] >= 2:
            bounces.append((o["n"], o["a"], i))
    # contact ticks
    contact_ticks = sorted(n for n, ps in pos_by_n.items() if any(depth(p[0], p[1], p[2])[1] for p in ps))
    cset = set(contact_ticks)
    bset = [b[0] for b in bounces]
    for n, a, i in bounces:
        vy_c[round(a[2], 3)] += 1
        hz["zero" if abs(a[1]) + abs(a[3]) < 0.05 else "nonzero"] += 1
        # first contact tick of this episode: walk back from n while contact in [n-15, n]
        firsts = [t for t in range(n - 15, n + 1) if t in cset]
        if firsts:
            delay_c[n - firsts[0]] += 1
        else:
            delay_c["none"] += 1
        ys = [p[1] for t in range(n - 3, n + 1) for p in pos_by_n.get(t, [])]
        y = min(ys) if ys else None
        region[("P4 y55-58" if y and y < 80 else "P3 y106" if y and y < 140 else "P2 y162+" if y and y < 200 else "P1/other")] += 1
        for o in L[max(0, i - 50):i + 80]:
            if o["k"] == "chat" and abs(o["n"] - n) <= 3:
                chat_c[o["m"][:60]] += 1
    for a, b in zip(bset, bset[1:]):
        gaps.append(b - a)
    # contact episodes without a bounce within 15 ticks after
    ep = None
    for t in contact_ticks:
        if ep is None or t - ep[-1] > 3:
            ep = [t]
            if not any(0 <= bn - t <= 15 for bn in bset):
                contact_noBounce[f[:19]] += 1
        else:
            ep.append(t)

print("bounces:", sum(vy_c.values()))
print("vy:", vy_c.most_common())
print("horizontal:", hz)
print("delay first-contact -> packet (server ticks):", sorted(delay_c.items(), key=lambda kv: str(kv[0])))
print("region:", region)
print("chat within 3 ticks:", chat_c.most_common(12))
print("gap histogram (consecutive bounces <=60):", sorted(collections.Counter(g for g in gaps if g <= 60).items()))
print("contact episodes with no bounce:", sum(contact_noBounce.values()), contact_noBounce.most_common(5))
