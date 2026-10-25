"""Party Progress's skill table, from ADR runs: how long each player takes on each terminal type and on Simon Says.

    python -I build.py <adr-runs folder> <out.json> [workers]

<adr-runs folder> is what adr-fetch.mjs (or adr-fetch.py) fills: runs.json and runs/<id>.jsonl.gz (xz inside). Only F7/M7
runs that reached terminals are read. What a recording gives:
  - its own player's terminals: the GUI's title (the terminal's type) at open, then their own "<name> activated a
    terminal!" while it is open: a solve time, open to done, and the clicks it took;
  - its own player's Simon Says: the first start-button press after P3 starts, to their own "<name> completed a device!"
    in section 1 (SS is section 1's device);
  - every party member's Simon Says finish, from P3's start (Goldor's first line): who completed section 1's device, and
    when (one count per run group, so a run recorded by several party members counts once).
Simon Says only from runs since the October 2026 update (4 rounds since). Each player keeps their newest 80 of each.
"""
import json, lzma, os, re, sys, statistics
from concurrent.futures import ProcessPoolExecutor

TITLES = [
    (re.compile(r"^Click in order!$"), "NUMBERS"),
    (re.compile(r"^Correct all the panes!$"), "PANES"),
    (re.compile(r"^Change all to same color!$"), "RUBIX"),
    (re.compile(r"^What starts with: '[A-Za-z]'\?$"), "STARTS_WITH"),
    (re.compile(r"^Select all the [\w ]+ items!$"), "SELECT"),
    (re.compile(r"^Click the button on time!$"), "MELODY"),
]
TERM = re.compile(r"^(\w{1,16}) activated a terminal! \((\d)/(\d)\)$")
DEVICE = re.compile(r"^(\w{1,16}) completed a device! \((\d)/(\d)\)$")
COUNT = re.compile(r"^(\w{1,16}) (?:activated a terminal|activated a lever|completed a device)! \((\d)/(\d)\)$")
P3_START = "[BOSS] Goldor: Who dares trespass into my domain?"
SS_SINCE = "20261006"
KEEP = 80


def title_type(title):
    for r, t in TITLES:
        if r.match(title):
            return t
    return None


def read(path):
    """One run: (self, [(type, ms, clicks)], ss_ms or None, (ss player, ms after P3) or None)."""
    me = None
    p3 = None
    section = 1
    open_type = None
    open_t = 0
    clicks = 0
    closed = None
    terms = []
    ss_press = None
    ss_mine = None
    ss_at = None
    with lzma.open(path, "rt", encoding="utf-8") as fh:
        for line in fh:
            if line.startswith('{"k":"meta"'):
                me = json.loads(line).get("self", "")
                continue
            if line.startswith('{"k":"gui"'):
                o = json.loads(line)
                open_type = title_type(o.get("title", ""))
                open_t = o["t"]
                clicks = 0
                continue
            if line.startswith('{"k":"guiclose"'):
                # Hypixel closes a solved terminal just before its "activated" line, in the same tick.
                if open_type is not None:
                    closed = (open_type, open_t, clicks, json.loads(line)["t"])
                open_type = None
                continue
            if line.startswith('{"k":"slotclick"'):
                clicks += 1
                continue
            if line.startswith('{"k":"ssclick"'):
                o = json.loads(line)
                # The start button (110, 121, 91), pressed while P3 runs and SS isn't done.
                if p3 is not None and section == 1 and ss_press is None and o.get("x") == 110 and o.get("y") == 121 and o.get("z") == 91:
                    ss_press = o["t"]
                continue
            if not line.startswith('{"k":"chat"'):
                continue
            o = json.loads(line)
            m = o.get("m", "")
            t = o["t"]
            if m == P3_START:
                if p3 is None:
                    p3 = t
                continue
            if p3 is None:
                continue
            c = COUNT.match(m)
            if not c:
                continue
            name, n, of = c.group(1), int(c.group(2)), int(c.group(3))
            tm = TERM.match(m)
            if tm and me and name.lower() == me.lower():
                if open_type is not None:
                    terms.append((open_type, (t - open_t) * 50, clicks))
                    open_type = None
                elif closed is not None and t - closed[3] <= 1:
                    terms.append((closed[0], (t - closed[1]) * 50, closed[2]))
                closed = None
            dm = DEVICE.match(m)
            if dm and section == 1:
                ss_at = (name, (t - p3) * 50)
                if me and name.lower() == me.lower() and ss_press is not None:
                    ss_mine = (t - ss_press) * 50
            if n == of:
                section += 1
    return me, terms, ss_mine, ss_at


def work(args):
    path, rid, grp = args
    try:
        return rid, grp, read(path)
    except Exception as e:  # a cut-short recording
        return rid, grp, None


def median(xs):
    return int(statistics.median(xs)) if xs else None


def main():
    d, out = sys.argv[1], sys.argv[2]
    workers = int(sys.argv[3]) if len(sys.argv) > 3 else max(1, (os.cpu_count() or 2) - 2)
    rows = json.load(open(os.path.join(d, "runs.json"), encoding="utf-8"))
    runs = [r for r in rows if r.get("floor") in ("F7", "M7") and r.get("terms_ms")]
    runs.sort(key=lambda r: r["id"])
    jobs = [(os.path.join(d, "runs", r["id"] + ".jsonl.gz"), r["id"], r.get("grp") or r["id"]) for r in runs]
    jobs = [j for j in jobs if os.path.exists(j[0])]
    print(f"{len(jobs)} F7/M7 runs to read with {workers} workers", file=sys.stderr, flush=True)

    term = {}      # player -> type -> [ms]
    clicks = {}    # type -> [ms per click] (all players), for terminals that took clicks
    ss = {}        # player -> [ms, start to done]
    ss_at = {}     # player -> [ms after P3 start]
    groups = set()
    done = 0
    with ProcessPoolExecutor(workers) as ex:
        for rid, grp, res in ex.map(work, jobs, chunksize=8):
            done += 1
            if done % 1000 == 0:
                print(f"{done} / {len(jobs)}", file=sys.stderr, flush=True)
            if not res:
                continue
            me, terms, ss_mine, at = res
            key = (me or "").lower()
            for ty, ms, n in terms:
                if 0 < ms < 30_000:
                    term.setdefault(key, {}).setdefault(ty, []).append(ms)
                    if n > 0 and ty != "MELODY":
                        clicks.setdefault(ty, []).append(ms / n)
            recent = rid[:8] >= SS_SINCE
            if recent and ss_mine is not None and 0 < ss_mine < 60_000:
                ss.setdefault(key, []).append(ss_mine)
            if recent and at is not None and grp not in groups and 0 < at[1] < 90_000:
                groups.add(grp)
                ss_at.setdefault(at[0].lower(), []).append(at[1])

    def stat(xs):
        xs = xs[-KEEP:]
        return [median(xs), len(xs)]

    players = {}
    for p, by in term.items():
        players.setdefault(p, {})["t"] = {ty: stat(xs) for ty, xs in by.items()}
    for p, xs in ss.items():
        players.setdefault(p, {})["ss"] = stat(xs)
    for p, xs in ss_at.items():
        players.setdefault(p, {})["ssAt"] = stat(xs)
    every = lambda key: [x for by in term.values() for x in by.get(key, [])]
    table = {
        "format": 1,
        "runs": len(jobs),
        "built": max(r["id"] for r in runs)[:15] if runs else "",
        "types": {ty: [median(every(ty)), len(every(ty))] for _, ty in TITLES},
        "msPerClick": {ty: [median(xs), len(xs)] for ty, xs in clicks.items()},
        "ss": [median([x for xs in ss.values() for x in xs]), sum(len(xs) for xs in ss.values())],
        "ssAt": [median([x for xs in ss_at.values() for x in xs]), sum(len(xs) for xs in ss_at.values())],
        "players": dict(sorted(players.items())),
    }
    tmp = out + ".part"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(table, f, separators=(",", ":"))
    os.replace(tmp, out)
    print(f"{len(players)} players; types {table['types']}; ss {table['ss']}; ssAt {table['ssAt']}", file=sys.stderr)


if __name__ == "__main__":
    main()
