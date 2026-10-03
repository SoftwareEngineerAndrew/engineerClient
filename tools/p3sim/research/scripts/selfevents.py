"""Dump the recorder's own-player stream (velocity, hp, dmg/tp/ev/hurt on self, me, ex, all chat) as a
flat timeline: python3 selfevents.py OUT.jsonl FILE [FILE...]
Each output line: {"f": file, "n": servertick, "t": clienttick, "k": kind, "a": [...]} or chat {"m": ...}"""
import gzip, json, sys, os

KINDS = {"v", "hp", "dmg", "tp", "me", "ex", "ev", "hurt", "sy", "d"}
SELF_ID = {"v", "dmg", "tp", "ev", "hurt", "sy", "d"}
out = open(sys.argv[1], "w")
for fn in sys.argv[2:]:
    me = None
    base = os.path.basename(fn)
    for line in gzip.open(fn, "rt"):
        o = json.loads(line)
        k = o["k"]
        if k == "meta":
            me = o["selfId"]
        elif k == "chat":
            out.write(json.dumps({"f": base, "n": o["n"], "t": o["t"], "k": "chat", "m": o["m"]}) + "\n")
        elif k == "net":
            for e in o["d"]:
                n, kind = e[0], e[1]
                if kind not in KINDS:
                    continue
                if kind in SELF_ID and e[2] != me:
                    continue
                out.write(json.dumps({"f": base, "n": n, "t": o["t"], "k": kind, "a": e[2:]}) + "\n")
