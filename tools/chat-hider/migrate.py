"""Carry saved Show/Block decisions across a rebuild: ids that still exist keep their value;
blocked templates that were merged away are re-applied by matching their old regex against
the new templates' examples. Reads db/chat/filters.json, writes db/new_filters.json."""
import json, re, time, sys
raw=json.load(open("db/chat/filters.json")); cur=raw.get("data", raw)
d=json.load(open("dataset.json")); rules=d["rules"]+d["long_tail"]
ids={r["id"] for r in rules}
old=cur.get("decisions",{}); custom=cur.get("custom",[])
decisions={k:v for k,v in old.items() if k in ids}
gone=[b for b in cur.get("export",{}).get("block",[]) if b["id"] not in ids]
gone_rx=[re.compile(b["regex"]) for b in gone]
force_block=sys.argv[1:]  # substrings of templates to block outright
for r in rules:
    if r["id"] in decisions: continue
    if any(rx.match(e) for rx in gone_rx for e in r["examples"]) or any(f in r["template"] for f in force_block):
        decisions[r["id"]]="block"
cc=[]
for c in custom:
    try: cc.append((re.compile(c["regex"]),c["action"]))
    except: pass
def overridden(r): return any(any(rx.search(e) for e in r["examples"]) for rx,_ in cc)
def eff(r):
    for rx,a in cc:
        if any(rx.search(e) for e in r["examples"]): return a
    return decisions.get(r["id"],"show")
block=[{"id":r["id"],"family":r["family"],"template":r["template"],"regex":r["regex"],"count":r["count"]} for r in rules if eff(r)=="block" and not overridden(r)]
doc={"decisions":decisions,"custom":custom,"export":{"version":1,"updatedAt":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),"custom":custom,"block":block},"updatedAt":int(time.time()*1000)}
json.dump(doc,open("db/new_filters.json","w"))
print(f"version {raw.get('version')}: kept {len(decisions)} decisions ({len(old)} before, {len(gone)} merged away), {len(block)} blocked types, {100*sum(b['count'] for b in block)/d['total']:.1f}% of lines")
