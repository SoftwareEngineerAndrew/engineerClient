"""Goldor's 'What do you think you are doing there!' kills: ticks since P3 start, who died within 5 ticks,
own y trace before own deaths. python3 goldor_kill.py self.jsonl"""
import json, sys, collections, re

ev = collections.defaultdict(list)
for line in open(sys.argv[1]):
    o = json.loads(line)
    ev[o["f"]].append(o)
tot = 0
deaths_near = collections.Counter()
for f, L in ev.items():
    p3 = None
    chats = [(o["n"], o["m"]) for o in L if o["k"] == "chat"]
    me = [(o["n"], o["a"]) for o in L if o["k"] == "me" and o["a"][0] is not None]
    for n, m in chats:
        if "Who dares trespass" in m:
            p3 = n
        if "What do you think you are doing there" in m:
            tot += 1
            near = [mm.strip() for nn, mm in chats if -6 <= nn - n <= 2 and "became a ghost" in mm]
            deaths_near[len(near)] += 1
            print(f[:19], "P3+%s" % (n - p3 if p3 else "?"), near[:3])
        if re.match(r"^ ☠ You died", m):
            tr = [(t - n, round(a[0], 1), round(a[1], 1), round(a[2], 1), a[5]) for t, a in me if -100 <= t - n <= 0][::6]
            print("   own death trace (dt,x,y,z,onGround):", tr[-12:])
print("lines:", tot, "deaths within -6..+2 ticks:", deaths_near)
