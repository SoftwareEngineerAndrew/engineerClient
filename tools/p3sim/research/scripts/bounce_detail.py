"""Bounce details: horizontal motion before/after, apex height, 3.038 vs 2.25 context, on-fire flag timing.
python3 bounce_detail.py self.jsonl"""
import json, sys, collections, statistics as st

ev = collections.defaultdict(list)
for line in open(sys.argv[1]):
    o = json.loads(line)
    ev[o["f"]].append(o)

apex = collections.defaultdict(list)
hspeed = collections.defaultdict(list)
ctx = collections.defaultdict(collections.Counter)
fire_after = []
fire_len = []
for f, L in ev.items():
    me = [(o["n"], o["a"]) for o in L if o["k"] == "me" and o["a"][0] is not None]
    bn = [o for o in L if o["k"] == "v" and o["a"][2] >= 2]
    fireflag = [(o["n"], v & 1) for o in L if o["k"] == "d" for i, v in o["a"][1] if i == 0 and isinstance(v, int)]
    prevb = None
    for b in bn:
        n, vy = b["n"], round(b["a"][2], 3)
        pre = [a for t, a in me if n - 3 <= t <= n]
        post = [(t, a) for t, a in me if n < t <= n + 40]
        if pre and post:
            y0 = pre[-1][1]
            # apex before next bounce/landing
            top = max(a[1] for t, a in post)
            apex[vy].append(round(top - y0, 2))
            # horizontal speed per tick in the 3 ticks before and the 3 after
            def hs(seq):
                out = []
                for (t1, a1), (t2, a2) in zip(seq, seq[1:]):
                    if t2 > t1:
                        out.append(((a2[0] - a1[0]) ** 2 + (a2[2] - a1[2]) ** 2) ** 0.5 / (t2 - t1))
                return round(st.mean(out), 3) if out else None
            before = [(t, a) for t, a in me if n - 6 <= t <= n]
            hspeed[vy].append((hs(before), hs(post[:4])))
        gap = n - prevb if prevb else 999
        prevb = n
        ctx[vy]["gap<20" if gap < 20 else "gap<45" if gap < 45 else "gap>=45"] += 1
        # previous me y velocity (falling speed into lava)
        if len(pre) >= 2:
            dy = pre[-1][1] - pre[-2][1]
            ctx[vy]["falling>1" if dy < -1 else "falling0.4-1" if dy < -0.4 else "slow/ground"] += 1
        on = [t for t, v in fireflag if v and n - 10 <= t <= n + 10]
        if on:
            fire_after.append(on[0] - n)
    # fire episode lengths
    st_on = None
    for t, v in fireflag:
        if v and st_on is None:
            st_on = t
        elif not v and st_on is not None:
            fire_len.append(t - st_on)
            st_on = None
for vy in apex:
    print(vy, "apex rise: median", st.median(apex[vy]), "quartiles", st.quantiles(apex[vy], n=4))
    hb = [a for a, b in hspeed[vy] if a is not None]
    ha = [b for a, b in hspeed[vy] if b is not None]
    print("   horiz speed before median", round(st.median(hb), 3), "after median", round(st.median(ha), 3))
    print("   context", dict(ctx[vy]))
print("fire flag set relative to bounce (ticks):", sorted(collections.Counter(fire_after).items()))
print("fire episode lengths:", sorted(collections.Counter(fire_len).items())[:40])
