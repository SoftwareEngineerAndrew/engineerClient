"""hp drops/rises per boss phase, drop intervals, regen steps; positions at instant deaths.
Phases from chat: P1 'Maxor: WELL! WELL!', P2 'Storm: Pathetic Maxor', P3 'Goldor: Who dares trespass',
P4 'Necron: You went further than any human', end 'Defeated'. python3 hp_phases.py self.jsonl"""
import json, sys, collections, re

PH = [("P1", "Maxor: WELL! WELL!"), ("P2", "Storm: Pathetic Maxor"), ("P3", "Goldor: Who dares trespass"),
      ("P4", "Necron: You went further"), ("END", "Defeated Maxor")]
ev = collections.defaultdict(list)
for line in open(sys.argv[1]):
    o = json.loads(line)
    ev[o["f"]].append(o)
drops = collections.defaultdict(list)
rises = collections.defaultdict(list)
intervals = collections.defaultdict(collections.Counter)
dropchat = collections.defaultdict(collections.Counter)
deathpos = []
hpsamples = collections.defaultdict(int)
for f, L in ev.items():
    phase = "pre"
    hps = []
    last_me = None
    for o in L:
        if o["k"] == "chat":
            for p, s in PH:
                if s in o["m"]:
                    phase = p
            if re.match(r"^ ☠ You (died|were killed)", o["m"]):
                deathpos.append((f[:19], phase, o["m"].strip()[:50], last_me))
        elif o["k"] == "me" and o["a"][0] is not None:
            last_me = [round(v, 1) for v in o["a"][:3]]
        elif o["k"] == "hp":
            hps.append((o["n"], o["a"][0], phase))
            hpsamples[phase] += 1
    chats = [(o["n"], o["m"]) for o in L if o["k"] == "chat"]
    lastdrop = {}
    for (n0, h0, p0), (n1, h1, p1) in zip(hps, hps[1:]):
        if h1 < h0 - 0.05:
            drops[p1].append(round(h0 - h1, 2))
            if p1 in lastdrop:
                intervals[p1][min(n1 - lastdrop[p1], 100)] += 1
            lastdrop[p1] = n1
            near = [re.sub(r"[\d,.]+", "#", m)[:50] for cn, m in chats if abs(cn - n1) <= 1 and ("hit you" in m or "struck you" in m or "damage" in m)]
            dropchat[p1][near[0] if near else "(no damage line)"] += 1
        elif h1 > h0 + 0.05 and h1 < 40:
            rises[p1].append((n1 - n0, round(h1 - h0, 2)))
for p in ["P1", "P2", "P3", "P4"]:
    d = drops[p]
    print(f"== {p}: hp samples {hpsamples[p]}, drops {len(d)}, median drop {sorted(d)[len(d)//2] if d else None}")
    print("   drop intervals:", sorted(intervals[p].items())[:25])
    print("   drop chat:", dropchat[p].most_common(8))
    r = collections.Counter(rises[p])
    print("   rises (dt, +hp):", r.most_common(10))
print("deaths:")
for d in deathpos:
    print("  ", d)
