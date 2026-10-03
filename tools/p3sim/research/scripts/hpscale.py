"""Implied Skyblock max HP from 'hit you for N damage' lines paired with the vanilla hp drop on the same/next
tick (vanilla health = 40 * hp / maxHp if the bar is scaled to 20 hearts). python3 hpscale.py self.jsonl"""
import json, sys, collections, re, statistics as st

ev = collections.defaultdict(list)
for line in open(sys.argv[1]):
    o = json.loads(line)
    ev[o["f"]].append(o)
rows = collections.defaultdict(list)
for f, L in ev.items():
    hps = [(o["n"], o["a"][0]) for o in L if o["k"] == "hp"]
    for o in L:
        if o["k"] != "chat":
            continue
        m = re.search(r"^(.*?) (?:hit you|hitting you|struck you) for ([\d,.]+) (true )?damage", o["m"])
        if not m:
            continue
        dmg = float(m.group(2).replace(",", ""))
        n = o["n"]
        before = [h for t, h in hps if n - 6 <= t < n]
        after = [h for t, h in hps if n <= t <= n + 1]
        if before and after and before[-1] - after[0] > 0.05:
            drop = before[-1] - after[0]
            rows[f[:19]].append((re.sub(r"[\d,.]+", "#", m.group(1))[:30], dmg, round(drop, 3), round(dmg * 40 / drop)))
for f, r in rows.items():
    imp = [x[3] for x in r]
    print(f, "n=%d implied maxHP median %d" % (len(r), st.median(imp)), r[:3])
