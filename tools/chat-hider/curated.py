"""Hand-written generalisations of the blocked templates. Each rule is one idea with a reason;
the script only checks it: which blocked templates it replaces, what it newly hides, and
whether it is already inside an existing custom rule or another proposal."""
import json, re, hashlib
N=r"\d[\d,.]*"; NAME=r"\w{1,16}"; RANK=r"(?:\[[A-Z+]+\] )?"
RULES=[
 ("Ability hits on enemies", rf"^Your [\w ]+ hit {N} enem(?:y|ies)(?: for {N} damage\.)?!?", "Implosion, Guided Sheep, Witherborn, Spirit Pet, Fireball — any ability's hit summary, whatever the ability."),
 ("Damage you take from traps and mechanics", rf"(?:hit|hitting) you for {N} (?:true )?damage[.!]$", "Extends the 'hit you for # damage.' rule to traps ('!' ending), true damage and 'exploded, hitting you'."),
 ("Ability ready / used / on cooldown", rf"^(?:[\w ]+ is (?:now available|ready to use)!|Your [\w ]+ ULTIMATE [\w ]+ is now available!|Used [\w ]+!|Your [\w ]+ is currently on cooldown for {N} more seconds?\.|This ability is on cooldown for .+|You are already channeling this ability!)", "Every class ability's ready/used/cooldown line — the hotbar already shows this."),
 ("Blessings and their stat grants", rf"^(?:DUNGEON BUFF! |A Blessing of \w+ was picked up!|(?:Also )?[Gg]ranted you \+)", "All blessing announcements and the 'Granted you +…' lines that follow, including the Intelligence one with the stat glyph."),
 ("Potion buffs", r"^BUFF! You have gained ", "'BUFF! You have gained Speed IV!' and the like."),
 ("Someone obtained an item", rf"^{RANK}{NAME} has obtained ", "Keys, Superboom, Revive Stone, Blessings, Beating Heart — you accepted this one; kept as-is."),
 ("Wither / Blood door prompts", r"WITHER door|BLOOD DOOR", "Door key prompts and 'opened a WITHER door' / 'The BLOOD DOOR has been opened!'."),
 ("'A mystical force' restrictions", r"^A mystical force ", "Five variants of the same can't-do-that message."),
 ("'You cannot …' restrictions", r"^You cannot (?!invite|message|say)", "You accepted 'You cannot …'. Invite, message and say refusals are excluded: those are replies to something you did on purpose."),
 ("Sack summaries", r"^\[Sacks\] ", "The periodic '[Sacks] +# items, -# items' tallies, every combination."),
 ("Items moved from sacks", rf"^Moved {N} .+ from your Sacks to your inventory\.$", "Any item, any amount, pulled from your sacks to your inventory."),
 ("Item stash", r"stashed away!|item stash|^>>> CLICK HERE to pick them up! <<<$|^\(This totals .+ stashed!\)$", "Stash reminders and the click-to-pick-up line."),
 ("Essence pickups", r"^ESSENCE! |found a Wither Essence! Everyone gains an extra essence!$", "Every essence type, you or a teammate."),
 ("Chest rewards", r"^RARE REWARD! |^[A-Z]+ CHEST REWARDS$", "Rare reward broadcasts and the reward chest headers."),
 ("Rare drops", r"^RARE DROP! ", "Every rare drop, not only the three you blocked."),
 ("Charms", r"^CHARM! ", "Shard charms on any mob."),
 ("Class orbs", r"^◕ ", "Orb pickups in both directions."),
 ("Class stat boosts at run start", r"^\[(?:Mage|Berserk|Archer|Healer|Tank)\] .+ -> |^Your (?:Mage|Berserk|Archer|Healer|Tank) stats are doubled", "The per-class '[Mage] Intelligence # -> #' block and its header, for every class."),
 ("Class milestones", r"^(?:Healer|Mage|Berserk|Archer|Tank) Milestone [❶-❾]: ", "All four milestone wordings, not only Total Damage."),
 ("Experience gains", r"^\+\d[\d,.]* \w+ Experience", "Catacombs and class XP lines after a run."),
 ("Tethers", r"formed a tether with", "Healer tether both ways."),
 ("Mort's intro", r"^\[NPC\] Mort: ", "All of Mort's lines at run start."),
 ("Oruo quiz dialogue", r"^\[STATUE\] Oruo the Omniscient: (?!.*(?:answered|questions? left|One more question))", "Oruo's speeches. Quiz progress ('answered Question #1 correctly', 'questions left', 'One more question') stays visible."),
 ("Wither Skull hints", r"^\[SKULL\] ", "You accepted '[SKULL] …'."),
 ("Fairy soul counts", r"^[ⓐ-ⓩ] \d[\d,.]* Fairy Souls$", "The ⓐ/ⓑ/ⓒ fairy soul lines."),
 ("Server moves", rf"^(?:Sending to (?:server )?\S+\.\.\.|Request join for .+\.\.\.|Warping\.\.\.|Already connecting to this server!|Kicked whilst connecting to .+|Queu(?:e)?ing .+|I'm already sending you to SkyBlock!|Attempting to add you to the party\.\.\.)$", "Every 'sending / warping / queueing' line, whichever server."),
 ("Hypixel notices", r"^(?:\[WATCHDOG ANNOUNCEMENT\]|Watchdog has banned .+|Staff have banned .+|Blacklisted modifications are a bannable offense!|Link looks suspicious\? - Don't click it!|Clicking sketchy links can result in your account|being stolen!|Latest update: SkyBlock .+)$", "Watchdog stats and the link-safety block."),
 ("GEXP", r"^You earned .+ GEXP", "Guild XP notices, with or without event XP."),
 ("Separator bars", r"^(?:-{10,}|▬{10,})$", "Plain dash and bar separators on their own line."),
 ("Mod kill announcements in party", rf"^Party > {RANK}{NAME}: (?:(?:Mimic|Bat|Prince) Killed!?|\[Skyblocker\] (?:Crypts: {N}/{N}|We only have {N} crypts out of {N} we need more!|{N} Score Reached!))$", "Only fixed-wording kill/score/crypt announcements from mods. Leap announcements and anything a player typed never match."),
 ("Devonian leaderboard switch", r"^\[Devonian\] Switched leaderboard category", "Every floor, not only F7/M7."),
 ("Bazaar executing", r"^\[Bazaar\] Executing instant (?:buy|sell)\.\.\.$", "The 'executing' step only; the result line stays."),
]
d=json.load(open("dataset.json")); total=d["total"]; rules=d["rules"]+d["long_tail"]
cur=json.load(open("db/chat/filters.json")); cur=cur.get("data",cur)
blocked={k for k,v in cur["decisions"].items() if v=="block"}
custom=[re.compile(c["regex"]) for c in cur.get("custom",[])]
def hit(rx,r): return any(rx.search(e) for e in r["examples"])
covered_by_custom={r["id"] for r in rules if any(hit(c,r) for c in custom)}
out=[]
for name,regex,why in RULES:
    rx=re.compile(regex)
    B=[r for r in rules if hit(rx,r) and r["id"] in blocked and r["id"] not in covered_by_custom]
    U=[r for r in rules if hit(rx,r) and r["id"] not in blocked and r["id"] not in covered_by_custom]
    out.append({"id":"c"+hashlib.sha1(regex.encode()).hexdigest()[:10],"kind":"curated","label":name,"why":why,"regex":regex,
      "blocked":[b["id"] for b in B],"blockedTemplates":[b["template"] for b in sorted(B,key=lambda b:-b["count"])],"blockedLines":sum(b["count"] for b in B),
      "adds":[{"id":u["id"],"template":u["template"],"count":u["count"],"example":u["examples"][0]} for u in sorted(U,key=lambda u:-u["count"])],"addsLines":sum(u["count"] for u in U)})
# overlap check between proposals
for a in out:
    sa=set(a["blocked"])|{x["id"] for x in a["adds"]}
    a["overlaps"]=[b["label"] for b in out if b is not a and sa and sa & (set(b["blocked"])|{x["id"] for x in b["adds"]})]
json.dump({"total":total,"blockedTypes":len(blocked),"candidates":out},open("general.json","w"),ensure_ascii=False)
for c in out: print(f'{len(c["blocked"]):3} blk {c["blockedLines"]:6} | +{len(c["adds"]):3} types +{c["addsLines"]:5} | {c["label"]}' + (f'   OVERLAP {c["overlaps"]}' if c["overlaps"] else ""))
left=[b for b in cur["export"]["block"] if not any(b["id"] in c["blocked"] for c in out)]
print("\nblocked templates no proposal covers:",len(left))
