"""Sounds within 0..3 server ticks of a lava bounce (velocity on self with vy >= 2), near the player,
vs a baseline count over all ticks. python3 bounce_sounds.py FILE.jsonl.gz [...]"""
import gzip, json, sys, collections

near, total_b = collections.Counter(), 0
for fn in sys.argv[1:]:
    me = None
    snds = []  # (n, name, x, y, z)
    bounces = []
    pos = None
    for line in gzip.open(fn, "rt"):
        o = json.loads(line)
        if o["k"] == "meta":
            me = o["selfId"]
        if o["k"] != "net":
            continue
        for e in o["d"]:
            if e[1] == "me" and e[2] is not None:
                pos = e[2:5]
            elif e[1] == "snd":
                snds.append((e[0], e[2], e[4], e[5], e[6]))
            elif e[1] == "sde" and e[3] == me:
                snds.append((e[0], "self:" + e[2], None, None, None))
            elif e[1] == "v" and e[2] == me and e[4] >= 2 and pos:
                bounces.append((e[0], pos))
    for n, p in bounces:
        total_b += 1
        seen = set()
        for sn, name, x, y, z in snds:
            if 0 <= sn - n <= 3 and (x is None or abs(x - p[0]) + abs(y - p[1]) + abs(z - p[2]) < 6):
                seen.add(name)
        for s in seen:
            near[s] += 1
print("bounces", total_b)
for s, c in near.most_common(15):
    print(c, s)
