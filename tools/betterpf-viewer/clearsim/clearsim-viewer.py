#!/usr/bin/env python3
"""
Build the Clear Sim Viewport page from the simulator's own replay dump.

WHY THIS IS A SCRIPT AND NOT A MANUAL STEP
  Andrew, 2026-08-20: the viewer "should be simply a viewer for your simulations, updating
  automatically". A published Artifact cannot reach this machine — the runtime capabilities a
  page can hold are self-publishing, file downloads, and the viewer's own claude.ai connectors,
  none of which read a local file. So the page cannot pull new runs.

  What it can be is a PRODUCT OF THE RUN. `./gradlew clearSim` writes
  build/clearsim-replays.json; this turns that into the page. Nothing is hand-assembled, so the
  page cannot drift from the run it claims to show — which is the failure mode that matters more
  than the convenience.

CURATION, and why it is not "ship everything"
  A full dump is ~35 MB across 300+ runs and an Artifact must render under 16 MB. So this picks
  the rooms where the strategies actually DISAGREE — the ones worth watching — keeps every
  strategy for each so they can be compared side by side, and decimates long runs. The selection
  rule is stated in the output so a reader knows what was left out; a silently truncated corpus
  is the thing this project treats as worse than a smaller honest one.

Usage:  python3 scripts/clearsim-viewer.py [--rooms N] [--frames N] [--out FILE]
"""
import json, argparse, os, sys
from collections import defaultdict

ap = argparse.ArgumentParser()
ap.add_argument('--replays', default='build/clearsim-replays.json')
ap.add_argument('--rooms', type=int, default=10)
ap.add_argument('--frames', type=int, default=320)
ap.add_argument('--out', default='build/clearsim-viewer.html')
ap.add_argument('--assets', default='scripts/clearsim-viewer-assets')
a = ap.parse_args()

runs = json.load(open(a.replays))
by = defaultdict(list)
for r in runs:
    by[r['design']].append(r)

# Interesting = the strategies disagree most on how long the room took. A room every strategy
# finishes in the same time teaches nothing; a room one clears and another cannot is the point.
def spread(rs):
    lens = [len(r['frames']) for r in rs]
    return (max(lens) - min(lens)) * len(rs)

picked = sorted(by.items(), key=lambda kv: -spread(kv[1]))[:a.rooms]

def slim(r, maxf):
    fr, al = r['frames'], r['alive']
    if len(fr) > maxf:
        step = max(1, len(fr) // maxf)
        fr, al = fr[::step], al[::step]
    return dict(design=r['design'], strategy=r['strategy'], floor=r['floor'], walls=r['walls'],
                stars=r['stars'], frames=fr, alive=al, events=r.get('events', []))

out = []
for design, rs in picked:
    for r in rs:
        out.append(slim(r, a.frames))

data = json.dumps(out, separators=(',', ':'))
head = open(os.path.join(a.assets, 'head.html')).read()
body = open(os.path.join(a.assets, 'body.html')).read()
page = head + '\n<script>window.__REPLAYS__=' + data + ';</script>\n' + body
os.makedirs(os.path.dirname(a.out), exist_ok=True)
open(a.out, 'w').write(page)

print(f"[viewer] {len(runs)} runs over {len(by)} designs in the dump")
print(f"[viewer] embedded {len(out)} runs over {len(picked)} designs, "
      f"chosen by widest disagreement between strategies")
print(f"[viewer] frames capped at {a.frames} per run (longer runs decimated)")
print(f"[viewer] LEFT OUT: {len(by) - len(picked)} designs, {len(runs) - len(out)} runs")
print(f"[viewer] wrote {a.out}  ({os.path.getsize(a.out)/1e6:.2f} MB)")
