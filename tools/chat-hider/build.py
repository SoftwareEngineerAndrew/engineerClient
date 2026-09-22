import json, re, collections, hashlib
S="/home/andrew/Cluade/engineerClient/tools/chat-hider"
msgs=[m for f,m in json.load(open(S+"/all.json"))]
# compact-chat repeat counter "(3)" at the end (not "(1/7)")
cc=re.compile(r"\s\((\d+)\)$")
raw=[]
for m in msgs:
    mm=cc.sub("",m)
    raw.append(mm)
# IGNs: harvest from well-known slots
ign=set()
for m in raw:
    for r in [r"^Party > (?:\[[A-Z+]+\] )?(\w{1,16}):", r"^(\w{1,16}) (?:activated|completed) ", r"^(\w{1,16}) is now ready!",
              r"^(?:\[[A-Z+]+\] )?(\w{1,16}) has obtained ", r"^Party Finder > (?:\[[A-Z+]+\] )?(\w{1,16}) joined", r"^Friend > (\w{1,16}) (?:joined|left)", r"^Teleported you to (\w{1,16})!", r"^RARE REWARD! (\w{1,16}) found", r"^(\w{1,16}) (?:has left|has joined|was removed|has been removed|disconnected|invited|joined)",
              r"^(?:\[[A-Z+]+\] )?(\w{1,16}) (?:joined the lobby|has invited you)", r"You have teleported to (\w{1,16})!", r"Leaped to (\w{1,16})",
              r"^(?:\[[A-Z+]+\] )?(\w{1,16}) (?:found|picked up|healed|has died|died|used|was killed|is dead|has revived|revived|opened|entered)"]:
        g=re.search(r,m)
        if g: ign.add(g.group(1))
ign={i for i in ign if not re.fullmatch(r"\d+",i) and i.lower() not in {"you","the","a","an","your","party","guild"}}
ign.add("p3wr")
WORD=re.compile(r"(?<![\w])[A-Za-z0-9_]{1,16}(?![\w])")
def ign_sub(m):
    w=m.group(0)
    return NM if w in ign else w
ign_re=True
RANK=re.compile(r"\[(?:VIP|VIP\+|MVP|MVP\+|MVP\+\+|ADMIN|GM|YOUTUBE|MOD|HELPER|OWNER)\]")
NUM=re.compile(r"(?<![A-Za-z_\d])\d[\d,.]*")
N,NM,RK,UU,SV="\U000F0000","\U000F0001","\U000F0002","\U000F0004","\U000F0005"
UUID=re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
SERVER=re.compile(r"\b(?:mini|mega|sim|dynamic|lobby)\d+[A-Z]*\b")
PR,BL,TR,MS,TM="\U000F0006","\U000F0007","\U000F0008","\U000F0009","\U000F000A"
MILESTONE=re.compile(r"^(Healer|Mage|Berserk|Archer|Tank) Milestone [\u2776-\u277e]: (.+ so far!) (.+)$")
TIME=re.compile(r"(?<![\w.])(?:\d+h )?(?:\d+m )?\d+(?:\.\d+)?s(?![\w])")
BLESS=re.compile(r"(Blessing of )(Power|Life|Wisdom|Stone|Time)( [IVX]+)?\b")
PROFILE=re.compile(r"^(You are playing on profile: |Your profile was changed to: |Switching to profile |You have switched to profile ).+$")
# whole-message rewrites: (needle, display, regex) — an announcement whose every line varies
WHOLE=[("FIRE SALE", "<fire sale announcement>", r"^[\s\S]*FIRE SALE[\s\S]*$")]
WH="\U000F000B"
def template(m):
    for needle,_,_ in WHOLE:
        if needle in m: return WH+needle
    t=PROFILE.sub(lambda g: g.group(1)+PR, m)
    t=BLESS.sub(lambda g: g.group(1)+BL+(" "+TR if g.group(3) else ""), t)
    t=MILESTONE.sub(lambda g: MS+" "+g.group(2)+" "+TM, t)
    t=TIME.sub(TM, t)
    t=UUID.sub(UU,t)
    t=SERVER.sub(SV,t)
    t=RANK.sub(RK,t)
    t=WORD.sub(ign_sub,t)
    t=NUM.sub(N,t)
    return t
def display(t):
    if t.startswith(WH): return next(d for n,d,_ in WHOLE if n==t[1:])
    return t.replace(N,"#").replace(NM,"<name>").replace(RK,"[RANK]").replace(UU,"<uuid>").replace(SV,"<server>").replace(PR,"<profile>").replace(BL,"<blessing>").replace(TR,"<tier>").replace(MS,"<class> Milestone <n>:").replace(TM,"<time>")
def regex(t):
    if t.startswith(WH): return next(r for n,_,r in WHOLE if n==t[1:])
    r=re.escape(t.replace("\n","\U000F0003"))
    r=r.replace(re.escape(RK+" "),r"(?:\[[A-Z+]+\] )?").replace(RK,r"(?:\[[A-Z+]+\])?").replace(NM,r"\w{1,16}").replace(N,r"\d[\d,.]*").replace(UU,r"[0-9a-f-]{36}").replace(PR,r".+").replace(BL,r"(?:Power|Life|Wisdom|Stone|Time)").replace(TR,r"[IVX]+").replace(MS,r"(?:Healer|Mage|Berserk|Archer|Tank) Milestone [\u2776-\u277e]:").replace(TM,r"(?:\d+h )?(?:\d+m )?\d+(?:\.\d+)?s").replace(SV,r"(?:mini|mega|sim|dynamic|lobby)\d+[A-Z]*")
    r=r.replace("\U000F0003",r"\n")
    return "^"+r+"$"
FAMILIES=[
 ("Party chat", r"^Party > "), ("Friend list", r"^Friend > "), ("Bazaar / AH", r"^\[Bazaar\]|^\[Auction\]"), ("Guild chat", r"^Guild > "), ("Co-op chat", r"^Co-op > "), ("Private messages", r"^(From|To) "),
 ("Party Finder", r"^Party Finder > "), ("Party management", r"^(?:\[RANK\] )?<name> (has (left|joined|been removed|disbanded)|was removed|invited|joined the (party|dungeon))|^You (have joined|left the party|are now|were removed)|^The party (was|has)|^Party (Leader|Moderator|Members|Finder)|^Party ?[-=]{3,}|^-{20,}$"),
 ("Terminals / devices / levers", r"^<name> (activated|completed) (a )?(terminal|device|lever)|^The gate has been destroyed|^The Core entrance is opening|^This Terminal doesn't|^Question #|^<name> is now ready!|^The Core will|^Please wait|^#/# Energy"),
 ("Boss dialogue", r"^\[BOSS\] "), ("NPC dialogue", r"^\[NPC\] "),
 ("Ability damage", r"^Your [\w ]+ hit # enem"), ("Ability status", r"is (now available|ready to use)|^Your Ultimate is|^Used |^Command Failed"),
 ("Dungeon buffs / blessings", r"^DUNGEON BUFF!|^A Blessing of|^Granted you|^Also granted you"),
 ("Keys / doors", r"has obtained (Wither|Blood) Key|WITHER door|BLOOD DOOR|^The BLOOD DOOR|Superboom TNT"),
 ("Secrets / items pickup", r"^You found|^\[Sacks\]|^>>> CLICK HERE|^You have # items stashed|^RARE DROP|Autopet"),
 ("Run summary / splits", r"^Blood Clear|^Portal #s|^Maxor #s|^Storm #s|^Goldor #s|^Necron #s|^Boss Entry|^Team Score|^Score:|^Time Elapsed|^Secrets Found|^Deaths:|^Crypts:|^Puzzles:|^Rooms:|^Speed|^Total Damage|^Enemies Killed|^Damage Taken|^Bonus|^Skill|^Explore|^Class Rewards|^Dungeon Chest|^Experience|^\s*(Healer|Mage|Berserk|Archer|Tank)|^You obtained|^\+#|^Ability Damage|^Healing Done|^Deaths|died at:|^The Catacombs - |^[^ ]+ ran into|^Run "),
 ("Odin", r"^Odin »"), ("Mod messages", r"^\[EC\]|^\[BRW\]|^Skytils|^\[SkyHanni\]|^\[NEU\]|^\[Devonian\]|^\[Blade"),
 ("Watcher / blood", r"^\[BOSS\] The Watcher|^A shiver runs|^The Watcher|^Odin » Watcher|blood"),
 ("Mob / boss mechanics", r"enraged|^The Energy Laser|^A Crypt Wither Skull|^You cannot hit the silverfish|^A mystical force|^There are blocks in the way|^Starting in #|^Sending to|^Already connecting|^Kicked whilst|^You are playing on profile|^Profile ID|^You equipped Loadout|^Thunderstorm|^Ragnarok"),
 ("Deaths / revives", r"died|dead|revive|was killed"),
 ("Lobby / hub spam", r"joined the lobby|^You haven't claimed|^Talk to the|Bestiary Milestones|^>>> CLICK HERE to claim|^Welcome to|^Kicked|^Summer|Rewards"),
]
FAM=[(n,re.compile(r)) for n,r in FAMILIES]
def family(t):
    for n,r in FAM:
        if r.search(t): return n
    return "Other"
cnt=collections.Counter(); ex=collections.defaultdict(list)
for m in raw:
    t=template(m); cnt[t]+=1
    if len(ex[t])<3 and m not in ex[t]: ex[t].append(m)
total=len(raw)
rules=[]
bad=0
for t,n in cnt.most_common():
    r=regex(t); rx=re.compile(r)
    for e in ex[t]:
        if not rx.match(e): bad+=1; print("NOMATCH",r,"|",e); break
    rules.append({"id":"h"+hashlib.sha1(display(t).encode()).hexdigest()[:10],"family":family(display(t)),"template":display(t),"regex":r,"count":n,"examples":ex[t]})
print("templates",len(rules),"bad",bad,"total",total,"igns",len(ign))
fam=collections.Counter()
for r in rules: fam[r["family"]]+=r["count"]
for f,n in fam.most_common(): print(f"{n:7d} {100*n/total:5.1f}%  {f}  ({sum(1 for r in rules if r['family']==f)} templates)")
keep=[r for r in rules if r["count"]>=2]
print("kept (>=2):",len(keep),"covering",sum(r["count"] for r in keep)/total)
json.dump({"total":total,"rules":keep,"long_tail":[r for r in rules if r["count"]<2][:400]},open(S+"/dataset.json","w"))
print("Other top:"); 
for r in rules:
    if r["family"]=="Other" and r["count"]>=15: print(f"{r['count']:6d}  {r['template'][:120]}")
