"""Scoreboard recorder logs -> line types for the Scoreboard Lines page. Every recorded line must
land in exactly one type; anything unclassified is printed so a type can be added."""
import sys, re, json, glob, os, collections
LOGS = sorted(glob.glob(os.path.expanduser("~/.local/share/PrismLauncher/instances/26.1.2 BRW/minecraft/logs/engineerclient/scoreboard-*.log")))
N = r"\d[\d,.]*"
# (id, label, where, regex over the plain line, currently hidden?, note)
TYPES = [
 ("title",     "Title (SKYBLOCK)",              "everywhere", None, True,  "The bold header. Not a line; hidden through its own hook."),
 ("blank",     "Blank spacer",                  "everywhere", r"^$", True, "Empty lines Hypixel uses to space the sidebar out."),
 ("date",      "Date and server id",            "everywhere", r"^\d{2}/\d{2}/\d{2}\s+\S+.*$", True, "Real-world date, then the server (m33CJ); in dungeons also the instance, e.g. 1-2."),
 ("season",    "Skyblock season and day",       "everywhere", r"^(?:Early |Late )?(?:Spring|Summer|Autumn|Winter) \d{1,2}(?:st|nd|rd|th)$", True, ""),
 ("clock",     "Skyblock clock",                "everywhere", r"^\d{1,2}:\d{2}(?:am|pm)(?: [☀☽])?$", True, "With the sun/moon glyph outside dungeons."),
 ("loc_cata",  "Location: The Catacombs (floor)","dungeon",   r"^ The Catacombs \((?:[FM]\d|E)\)$", True, ""),
 ("loc_other", "Location: anywhere else",       "outside",    r"^ (?!The Catacombs).+$", False, "Dungeon Hub, Village, Your Island, None…"),
 ("purse",     "Purse",                         "outside",    rf"^(?:Purse|Piggy): {N}(?: \(\+{N}\))?$", False, ""),
 ("bits",      "Bits",                          "outside",    rf"^Bits: {N}(?: \(\+{N}\))?$", False, ""),
 ("obj_head",  "Objective (header)",            "outside",    r"^Objective(?: \S)?$", False, "The word Objective, sometimes with a direction arrow."),
 ("obj_task",  "Objective task (line under it)", "outside",   None, False, "Free text like 'Talk to Enid' or 'Paint the Canvas'; matched as the line right after Objective."),
 ("hype",      "Hype (Prototype Lobby)",        "outside",    rf"^Hype: {N}/{N}$", False, ""),
 ("ptl",       "Prototype Lobby notice",        "outside",    r"^(?:Games in this lobby are|under heavy development!|Report bugs and leave|feedback at|hypixel\.net/ptl)$", False, "Five lines of 'under development' text."),
 ("footer",    "www.hypixel.net",               "everywhere", r"^www\.hypixel\.net$", False, "The footer on every sidebar."),
 ("starting",  "Starting in / Auto-closing in", "dungeon",    r"^(?:Starting in|Auto-closing in): .+$", False, "Pre-start countdown lines."),
 ("mate_lobby","Teammate (pre-start, with level)","dungeon",  r"^\[[MHABT]\] \w{1,16} \[Lv\d*\]?$", False, "Before the run all five are listed, you included: [T] name [Lv50]."),
 ("mate_run",  "Teammate (in run, with health)", "dungeon",   rf"^\[[MHABT]\] \w{{1,16}} {N}❤?$", False, "During the run only your four teammates, name cut short to fit health."),
 ("solo",      "Solo",                          "dungeon",    r"^Solo$", False, "Where teammates would be, on a solo run."),
 ("elapsed",   "Time Elapsed",                  "dungeon",    r"^Time Elapsed: .+$", False, ""),
 ("keys",      "Keys",                          "dungeon",    r"^Keys: .+$", True, "Wither and blood key status."),
 ("cleared",   "Cleared %",                     "dungeon",    rf"^Cleared: {N}% \({N}\)$", True, ""),
]
row = re.compile(r"^\s*(\d+)\s+(-?\d+)\s+(\S+)\s+\| (.*?) \| (.*?) \| owner=(.*)$")
counts = collections.Counter(); examples = collections.defaultdict(list); unknown = collections.Counter(); raw_examples = collections.defaultdict(list)
compiled = [(t[0], re.compile(t[3])) for t in TYPES if t[3]]
for path in LOGS:
    for s in open(path).read().split("\n=== "):
        L = s.split("\n")
        if len(L) < 5: continue
        rows = [m for m in (row.match(l) for l in L[5:]) if m]
        prev = None
        for m in rows:
            if m.group(3) in ("holder", "past15"): continue
            plain = m.group(5).strip()
            hit = [tid for tid, rx in compiled if rx.match(plain)]
            if prev == "obj_head" and not hit: hit = ["obj_task"]
            if len(hit) != 1:
                unknown[(plain, tuple(hit))] += 1; prev = None; continue
            tid = hit[0]; counts[tid] += 1
            if plain not in examples[tid] and len(examples[tid]) < 4: examples[tid].append(plain); raw_examples[tid].append(m.group(4))
            prev = tid
title_n = sum(1 for p in LOGS for s in open(p).read().split("\n=== ") if "\ntitle: " in "\n"+s or s.startswith("title: "))
out = [{"id": t[0], "label": t[1], "where": t[2], "regex": t[3], "hiddenNow": t[4], "note": t[5],
        "count": title_n if t[0] == "title" else counts[t[0]], "examples": ["SKYBLOCK"] if t[0] == "title" else examples[t[0]],
        "raw": ["&e&lSKYBLOCK"] if t[0] == "title" else raw_examples[t[0]]} for t in TYPES]
json.dump({"types": out, "logs": [os.path.basename(p) for p in LOGS]}, open("types.json", "w"), ensure_ascii=False)
for t in out: print(f'{t["count"]:5}  {t["id"]:10} {t["examples"][:2]}')
print("UNCLASSIFIED / AMBIGUOUS:"); [print("  ", n, k) for k, n in unknown.most_common()]

# --- three real sidebars for the live preview -------------------------------------------------
def snapshot_rows(s):
    L = s.split("\n")
    rows = [m for m in (row.match(l) for l in L[5:]) if m and m.group(3) not in ("holder", "past15")]
    out, prev = [], None
    for m in rows:
        plain = m.group(5).strip()
        hit = [tid for tid, rx in compiled if rx.match(plain)]
        if prev == "obj_head" and not hit: hit = ["obj_task"]
        tid = hit[0] if len(hit) == 1 else None
        out.append({"type": tid, "raw": m.group(4)}); prev = tid
    return out
def pick(pred):
    best = None
    for path in LOGS:
        for s in open(path).read().split("\n=== "):
            if len(s.split("\n")) > 5 and pred(s): best = s
    return best
pre = pick(lambda s: "Auto-closing in" in s and s.count("[Lv") >= 5)
run = pick(lambda s: "Time Elapsed" in s and s.count("❤") + s.count("[B] ") >= 4 and "party: p3wr, " in s)
hub = pick(lambda s: "Purse:" in s and "Objective" in s and "Talk to" in s)
previews = [{"name": n, "title": "&e&lSKYBLOCK", "rows": snapshot_rows(s)} for n, s in (("Dungeon, before the run", pre), ("Dungeon, in the run", run), ("Hub (Village)", hub)) if s]
d = json.load(open("types.json")); d["previews"] = previews; json.dump(d, open("types.json", "w"), ensure_ascii=False)
print("previews:", [(p["name"], len(p["rows"]), sum(1 for r in p["rows"] if r["type"] is None)) for p in previews])
