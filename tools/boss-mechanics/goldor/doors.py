#!/usr/bin/env python3
"""Step 1b: where the section doors are -> OUT_DIR/doors.json.

usage: doors.py DATA_DIR OUT_DIR

Each of S1-S3 ends in a door: a wall of barrier blocks (plus some stairs/iron) behind a cobblestone
portcullis, next to the section's gate. The barrier blocks turn to air in the tick the section's
last completion lands, in every recording (block updates reach the whole arena), so they time a
section's end even when a chat cleaner hid Hypixel's "(n/n)" line. This reads the barrier
positions from the first recording that captured the boss arena (`lib` lines with key
`Boss|F7|cx,cz`, see tools/betterpf-viewer/FORMAT.md).
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glib as G  # noqa: E402

# search boxes (x0, x1, y0, y1, z0, z1) around each door
DOOR_BOXES = {'1': (90, 112, 110, 140, 118, 126), '2': (12, 22, 105, 140, 122, 142), '3': (0, 16, 110, 140, 45, 54)}


def block_at(vols, x, y, z):
    for v in vols:
        if v['x0'] <= x < v['x0'] + v['w'] and v['y0'] <= y < v['y0'] + v['h'] and v['z0'] <= z < v['z0'] + v['d']:
            idx = ((y - v['y0']) * v['d'] + (z - v['z0'])) * v['w'] + (x - v['x0'])
            r = v['rle']
            i = 0
            for j in range(0, len(r), 2):
                if idx < i + r[j]:
                    return v['pal'][r[j + 1]] if r[j + 1] else None
                i += r[j]
    return None


def main():
    data_dir, out_dir = sys.argv[1], sys.argv[2]
    for rid in G.wanted_ids(data_dir):
        p = G.run_path(data_dir, rid)
        if not os.path.exists(p):
            continue
        vols = [l for l in G.read_lines(p) if l['k'] == 'lib' and l['key'].startswith('Boss|F7')]
        if len(vols) < 20:
            continue
        doors = {}
        for s, (x0, x1, y0, y1, z0, z1) in DOOR_BOXES.items():
            doors[s] = [(x, y, z) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1) for z in range(z0, z1 + 1)
                        if (block_at(vols, x, y, z) or '').startswith('minecraft:barrier')]
        if all(len(v) > 100 for v in doors.values()):
            json.dump({'from': rid, 'doors': doors}, open(os.path.join(out_dir, 'doors.json'), 'w'))
            print('doors.json from %s: %s barrier blocks' % (rid, {k: len(v) for k, v in doors.items()}))
            return
    print('no recording with the boss arena captured')


if __name__ == '__main__':
    main()
