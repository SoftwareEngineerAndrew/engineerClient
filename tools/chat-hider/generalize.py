"""Propose broader rules from the templates the page marked Block, and show what each would
additionally hide in the rest of the dataset. Reads dataset.json + db/chat/filters.json,
writes general.json for the review page."""
import json, re, collections, hashlib
d=json.load(open("dataset.json")); rules=d["rules"]+d["long_tail"]; total=d["total"]
cur=json.load(open("db/chat/filters.json")); cur=cur.get("data",cur)
blocked={k for k,v in cur.get("decisions",{}).items() if v=="block"}
byid={r["id"]:r for r in rules}
PH={"#":r"\d[\d,.]*","<name>":r"\w{1,16}","[RANK]":r"(?:\[[A-Z+]+\])?","<uuid>":r"[0-9a-f-]{36}","<server>":r"(?:mini|mega|sim|dynamic|lobby)\d+[A-Z]*",
    "<profile>":r".+","<blessing>":r"(?:Power|Life|Wisdom|Stone|Time)","<tier>":r"[IVX]+","<time>":r"(?:\d+h )?(?:\d+m )?\d+(?:\.\d+)?s","<n>":r"[❶-❾]","<class>":r"(?:Healer|Mage|Berserk|Archer|Tank)"}
TOK=re.compile(r"<[a-z]+>|\[RANK\]|#|[^\s#<\[]+|\[")
def tok_regex(tok):
    if tok in PH: return PH[tok]
    # a token may embed a placeholder, e.g. "(#/#)" or "#s"
    out=""; i=0
    while i<len(tok):
        m=None
        for ph in sorted(PH,key=len,reverse=True):
            if tok.startswith(ph,i): m=ph; break
        if m: out+=PH[m]; i+=len(m)
        else: out+=re.escape(tok[i]); i+=1
    return out
def words(t): return t.replace("\n"," ⏎ ").split(" ")
def prefix_regex(ws): return "^"+r"\ ".join(tok_regex(w) if w!="⏎" else r"\n" for w in ws)+r"(?:\s[\s\S]*)?$"
def suffix_regex(ws): return r"^(?:[\s\S]*\s)?"+r"\ ".join(tok_regex(w) if w!="⏎" else r"\n" for w in ws)+"$"
STOP={"you","your","a","an","the","this","it","i","is","are","has","have","to","of","in","on","and","for","was","with","that","be","as"}
def meaningful(ws):
    """A rule needs at least one real word (not a placeholder, not a stopword, 3+ letters), and a one-word rule needs a word of 4+ letters."""
    real=[w for w in ws if w not in PH and w!="⏎" and w.lower().strip("[]:!.,()") not in STOP and len(re.sub(r"[^A-Za-z]","",w))>=3]
    if not real: return False
    if len(ws)==1 and len(re.sub(r"[^A-Za-z]","",ws[0]))<4: return False
    return True
cands={}
def add(kind, key, regex, label):
    if key in cands: return
    rx=re.compile(regex)
    B=[]; U=[]
    for r in rules:
        if any(rx.match(e) for e in r["examples"]):
            (B if r["id"] in blocked else U).append(r)
    if len(B)<2: return
    cands[key]={"id":"g"+hashlib.sha1(regex.encode()).hexdigest()[:10],"kind":kind,"label":label,"regex":regex,
                "blocked":[b["id"] for b in B],"blockedLines":sum(b["count"] for b in B),
                "adds":[{"id":u["id"],"template":u["template"],"count":u["count"],"example":u["examples"][0]} for u in sorted(U,key=lambda u:-u["count"])],
                "addsLines":sum(u["count"] for u in U)}
btemps=[byid[i] for i in blocked if i in byid]
# prefix candidates: every word-prefix (1..8 words) shared by >=2 blocked templates
pref=collections.defaultdict(set)
for r in btemps:
    ws=words(r["template"])
    for k in range(1,min(8,len(ws))+1):
        if k==len(ws): continue   # the whole template is not a generalisation
        pref[" ".join(ws[:k])].add(r["id"])
for p,ids in pref.items():
    if len(ids)<2: continue
    ws=p.split(" ")
    if not meaningful(ws): continue
    add("prefix", "P:"+p, prefix_regex(ws), p+" …")
# suffix candidates (3..6 words)
suf=collections.defaultdict(set)
for r in btemps:
    ws=words(r["template"])
    for k in range(3,min(6,len(ws)-1)+1):
        suf[" ".join(ws[-k:])].add(r["id"])
for p,ids in suf.items():
    if len(ids)<2: continue
    ws=p.split(" ")
    if not meaningful(ws): continue
    add("suffix", "S:"+p, suffix_regex(ws), "… "+p)
# single-slot candidates: same length, one differing word
bykey=collections.defaultdict(list)
for r in btemps:
    ws=words(r["template"])
    for i in range(len(ws)):
        bykey[(len(ws),i,tuple(ws[:i]+["*"]+ws[i+1:]))].append(r["id"])
for (n,i,pat),ids in bykey.items():
    if len(set(ids))<2: continue
    ws=list(pat)
    if not meaningful([w for w in ws if w!="*"]): continue
    regex="^"+r"\ ".join(r"\S+" if w=="*" else (tok_regex(w) if w!="⏎" else r"\n") for w in ws)+"$"
    add("slot","L:"+" ".join(ws), regex, " ".join("<any>" if w=="*" else w for w in ws))
# prune: among candidates with the same blocked set keep the one adding the least; drop candidates that add nothing (no wider than what is already blocked)
best={}
for k,c in cands.items():
    key=tuple(sorted(c["blocked"]))
    if key not in best or (len(c["adds"]),len(c["regex"]))<(len(best[key]["adds"]),len(best[key]["regex"])): best[key]=c
out=[c for c in best.values() if c["adds"]]
out.sort(key=lambda c:(-c["blockedLines"],-len(c["blocked"])))
fam={}
for c in out:
    for i in c["blocked"]:
        fam.setdefault(c["id"],collections.Counter())[byid[i]["family"]]+=1
for c in out: c["family"]=fam[c["id"]].most_common(1)[0][0]; c["blockedTemplates"]=[byid[i]["template"] for i in c["blocked"]]
json.dump({"total":total,"blockedTypes":len(blocked),"candidates":out},open("general.json","w"),ensure_ascii=False)
print(len(cands),"candidates,",len(out),"after pruning; adds lines total distinct:",len({a["id"] for c in out for a in c["adds"]}))
for c in out[:25]: print(f'{c["kind"]:6} covers {len(c["blocked"]):3} blocked ({c["blockedLines"]:6}) adds {len(c["adds"]):3} types ({c["addsLines"]:6})  {c["label"][:70]}')
