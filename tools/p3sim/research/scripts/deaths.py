"""Own deaths and revives: everything on yourself from 60 ticks before to 60 after each
'You died'/'You were killed' / 'revived you' line; also min y per file. python3 deaths.py self.jsonl"""
import json, sys, collections, re

ev = collections.defaultdict(list)
for line in open(sys.argv[1]):
    o = json.loads(line)
    ev[o["f"]].append(o)
for f, L in ev.items():
    ys = [o["a"][1] for o in L if o["k"] == "me" and o["a"][1] is not None]
    marks = [o for o in L if o["k"] == "chat" and re.search(r"^ ☠ You |You were killed|revived you|johnswizzlechang was revived|You fell|void", o["m"])]
    if not marks:
        continue
    print("=====", f, "min y", round(min(ys), 1) if ys else None)
    for mk in marks:
        n0 = mk["n"]
        print("  --", mk["m"].strip(), "@", n0)
        last = None
        for o in L:
            dt = o["n"] - n0
            if -60 <= dt <= 60 and o["k"] in ("hp", "tp", "v", "ev", "hurt", "chat", "d", "sy"):
                if o["k"] == "d" and not any(i in (0, 9, 15, 16, 17) for i, v in o["a"][1]):
                    continue
                s = f"{dt:+4d} {o['k']} {o.get('m') or o['a']}"[:150]
                if s != last:
                    print("    ", s)
                last = s
