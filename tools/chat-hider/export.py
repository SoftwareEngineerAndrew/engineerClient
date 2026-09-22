"""db/chat/filters.json (the Dungeon Chat Hider page's saved decisions) -> the rule file the
mod ships: src/main/resources/chat/hidden.json. Custom rules first (first match wins, may be
show or block), then the blocked templates. Examples are NOT exported (party chat)."""
import json, time, re, sys
raw=json.load(open("db/chat/filters.json")); cur=raw.get("data", raw)
exp=cur.get("export",{})
rules=[{"regex":b["regex"],"template":b["template"],"family":b["family"]} for b in exp.get("block",[])]
custom=[{"regex":c["regex"],"action":c["action"]} for c in cur.get("custom",[]) if c.get("regex")]
for r in rules+custom: re.compile(r["regex"])   # python-side sanity; the JUnit test does Java
out={"version":1,"updatedAt":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),"source":"Dungeon Chat Hider artifact, db doc chat/filters v%s"%(raw.get("version") or cur.get("updatedAt","?")),"custom":custom,"block":rules}
json.dump(out,open("../../src/main/resources/chat/hidden.json","w"),indent=1,ensure_ascii=False)
print(f"exported {len(rules)} block rules + {len(custom)} custom rules")
