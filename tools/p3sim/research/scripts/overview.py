"""Overview of self events: dmg types, velocity distribution, hp values, death-ish chat. python3 overview.py self.jsonl"""
import json, sys, collections, re

C = collections.Counter
dmg, vel, hpv, maxhp, chat, food = C(), C(), C(), C(), C(), C()
for line in open(sys.argv[1]):
    o = json.loads(line)
    k = o["k"]
    if k == "dmg":
        dmg[o["a"][1]] += 1
    elif k == "v":
        _, vx, vy, vz = o["a"]
        vel[(round(vx, 2) if abs(vx) > 0.001 else 0, round(vy, 2), round(vz, 2) if abs(vz) > 0.001 else 0)] += 1
    elif k == "hp":
        hpv[o["a"][0]] += 1
        food[o["a"][1]] += 1
    elif k == "chat":
        m = o["m"]
        if re.search(r"died|dead|killed|revive|ghost|burn|lava|void|fell|☠|respawn|Wish|heal", m, re.I):
            chat[re.sub(r"\d+", "#", m)] += 1
print("DMG types on self:", dmg.most_common())
print("\nVelocity packets on self (top 60):")
for k, c in vel.most_common(60):
    print(" ", c, k)
print("\nHP values:", sorted(hpv.items())[:80])
print("food:", food.most_common(10))
print("\nchat:")
for k, c in chat.most_common(80):
    print(" ", c, k)
