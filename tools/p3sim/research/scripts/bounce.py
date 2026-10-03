"""Lava bounce analysis. python3 bounce.py self.jsonl lava.json
lava.json: [[x,y,z],...] lava cells of the built arena (static; P4 rising lava not included).
For every velocity packet on yourself, prints the last sent positions, whether those touch lava, hp around it."""
import json, sys, collections, math

lava = set(tuple(p) for p in json.load(open(sys.argv[2])))
ev = collections.defaultdict(list)
for line in open(sys.argv[1]):
    o = json.loads(line)
    ev[o["f"]].append(o)


def lava_contact(x, y, z):
    """which lava cells the player box (0.6 wide, 1.8 tall) overlaps; returns min dy of feet into the cell"""
    hits = []
    for bx in range(math.floor(x - 0.3), math.floor(x + 0.3) + 1):
        for bz in range(math.floor(z - 0.3), math.floor(z + 0.3) + 1):
            for by in range(math.floor(y) - 1, math.floor(y + 1.8) + 1):
                if (bx, by, bz) in lava:
                    hits.append((bx, by, bz))
    return hits


summary = collections.Counter()
rows = []
for f, L in ev.items():
    pos = None
    hist = []  # (n, x, y, z, onGround)
    hp = None
    last_bounce = None
    for i, o in enumerate(L):
        k = o["k"]
        if k == "me" and o["a"][0] is not None:
            pos = o["a"]
            hist.append((o["n"], pos[0], pos[1], pos[2], pos[5]))
        elif k == "tp":
            a = o["a"]
            hist.append((o["n"], a[0], a[1], a[2], "TP"))
        elif k == "hp":
            hp = o["a"][0]
        elif k == "v":
            vx, vy, vz = o["a"][1:]
            if vy < 2.0:
                continue
            n = o["n"]
            recent = [h for h in hist if n - 12 <= h[0] <= n]
            contact = [(h[0] - n, round(h[2], 3), h[4], len(lava_contact(h[1], h[2], h[3]))) for h in recent]
            # first tick in recent with lava contact
            first = next((h for h in recent if lava_contact(h[1], h[2], h[3])), None)
            # hp after: next hp packets within 10 ticks
            hps = [(p["n"] - n, round(p["a"][0], 2)) for p in L[max(0, i - 40):i + 80] if p["k"] == "hp" and -10 <= p["n"] - n <= 20]
            chats = [(p["n"] - n, p["m"]) for p in L[max(0, i - 60):i + 120] if p["k"] == "chat" and -5 <= p["n"] - n <= 10]
            gap = n - last_bounce if last_bounce else None
            last_bounce = n
            y = recent[-1][2] if recent else None
            rows.append(dict(f=f[:19], n=n, v=(round(vx, 3), round(vy, 3), round(vz, 3)), y=y,
                             xz=(round(recent[-1][1], 1), round(recent[-1][3], 1)) if recent else None,
                             firstLavaDt=(first[0] - n) if first else None, gap=gap, contact=contact[-6:], hps=hps, chats=chats))
for r in rows:
    print(json.dumps(r))
