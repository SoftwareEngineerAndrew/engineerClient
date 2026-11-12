"""Every Tic Tac Toe game in a recording: moves in order (cell, mark, ms), resets, the solve, and the recorder's own clicks.
Cells are (row, col) as the player sees the board: row 0 the top, col 0 the left. Marks: the map picture each frame holds."""
import base64, gzip, glob, json, sys, os

rec = sys.argv[1]
room = None


def lines():
    for p in sorted(glob.glob(rec + '/part*.jsonl.gz')):
        try:
            for line in gzip.open(p, 'rt'): yield line
        except (EOFError, OSError): return


for line in lines():
    if line.startswith('{"k":"room"') and '"Tic Tac Toe"' in line:
        room = json.loads(line); break
if not room: sys.exit(0)
tx, tz = room['tiles'][0]; X0, Z0 = -200 + 32 * tx, -200 + 32 * tz
inb = lambda x, z: X0 - 1 <= x < X0 + 32 and Z0 - 1 <= z < Z0 + 32

frames = {}        # entity id -> {pos, facing, map}
maps = {}          # map id -> pixels (bytes) when sent whole
buttons = {}       # (x,y,z) -> ms removed (latest)
events = []        # (ms, kind, data)
facing = None
for line in lines():
    k = line[6:12]
    if line.startswith('{"k":"blk"'):
        o = json.loads(line); x, y, z = o['p']
        if inb(x, z) and 'stone_button' in o['old'] and o['new'].endswith('air'):
            events.append((o['ms'], 'button_gone', (x, y, z)))
        elif inb(x, z) and o['new'].endswith(':stone_button') or (inb(x, z) and 'stone_button' in o['new'] and 'stone_button' not in o['old']):
            events.append((o['ms'], 'button_back', (x, y, z)))
        elif inb(x, z) and 'iron_block' in o['old'] and 'barrier' in o['new']:
            events.append((o['ms'], 'solved', (x, y, z)))
        continue
    if not (line.startswith('{"k":"in"') or line.startswith('{"k":"out"')): continue
    if not any(s in line for s in ('add_entity', 'set_entity_data', 'map_item_data', 'remove_entities', 'use_item_on')): continue
    o = json.loads(line); f = o.get('f') or {}; pk = o.get('p', '')
    if pk.endswith('add_entity') and 'item_frame' in line and inb(f['x'], f['z']):
        fr = frames.setdefault(f['id'], {})
        fr['pos'] = (f['x'], f['y'], f['z']); fr['facing'] = f.get('facing'); facing = facing or f.get('facing')
        fr.setdefault('first', o['ms'])
    elif pk.endswith('set_entity_data') and f.get('id') in frames:
        for it in f.get('packedItems') or []:
            if isinstance(it[2], dict) and it[2].get('id') == 'minecraft:filled_map':
                frames[f['id']]['map'] = it[2]['full']['components'].get('minecraft:map_id')
    elif pk.endswith('remove_entities'):
        ids = [i for i in (f.get('entityIds') or f.get('ids') or []) if i in frames]
        if ids: events.append((o['ms'], 'removed', ids))
    elif pk.endswith('map_item_data'):
        mid = f['mapId']['id']; cp = f.get('colorPatch') or {}
        if mid != 1024 and cp.get('full') and 'b64' in cp: maps[mid] = base64.b64decode(cp['b64'])
    elif o['k'] == 'out' and pk.endswith('use_item_on'):
        bp = (f.get('blockHit') or {}).get('blockPos')
        if bp and inb(bp[0], bp[2]): events.append((o['ms'], 'click', tuple(bp)))

if not frames: sys.exit(0)
# the board: the 3x3 of stone buttons (every one that ever disappears or is clicked), cells as the player sees them
fac = facing or 'south'
bpos = {tuple(p) for _, k, p in events if k in ('button_gone', 'button_back')}
def along(x, z):
    return {'south': x, 'north': -x, 'east': -z, 'west': z}[fac]
if bpos:
    cols = sorted({along(x, z) for x, y, z in bpos}); rows = sorted({y for x, y, z in bpos}, reverse=True)
else:
    cols, rows = [], []
def cell(x, y, z):
    if not cols: return None
    a = along(x, z); r = min(range(len(rows)), key=lambda i: abs(rows[i] - y)); c = min(range(len(cols)), key=lambda i: abs(cols[i] - a))
    if abs(rows[r] - y) > 0.8 or abs(cols[c] - a) > 0.8: return None
    return r, c
raw = {}
for fid, fr in frames.items():
    x, y, z = fr['pos']
    # a frame hangs in front of its button: same column and row, half a block off the wall
    raw[fid] = cell(x - 0.5 if fac in ('south', 'north') else x, y - 0.5, z - 0.5 if fac in ('east', 'west') else z)
for ms, k, p in events:
    if k in ('button_gone', 'click', 'button_back'): raw[(k, ms, p)] = cell(*p)
norm = lambda rc: rc

def picture(mid):
    """The map's picture, very coarse: which of 'X' and 'O' it looks like (centre pixel set vs not)."""
    px = maps.get(mid)
    if not px: return None
    bg = max(set(px), key=px.count)
    on = lambda x, y: px[y * 128 + x] != bg
    centre = sum(on(x, y) for x in range(58, 70) for y in range(58, 70))
    return 'X' if centre > 40 else 'O'


marks = {mid: picture(mid) for mid in {fr.get('map') for fr in frames.values()} if mid is not None}
out = {'rec': os.path.basename(rec.rstrip('/')), 'rot': room.get('rot'), 'facing': fac,
       'frames': sorted([[fid, list(raw[fid]) if raw.get(fid) else None, fr.get('map'), marks.get(fr.get('map')), fr['first']] for fid, fr in frames.items()]),
       'grid': {'rows': rows, 'cols': cols},
       'events': [[ms, k, (list(raw[(k, ms, p)]) if raw[(k, ms, p)] else None) if isinstance(p, tuple) and (k, ms, p) in raw else p] for ms, k, p in sorted(events, key=lambda e: e[0])],
       'maps': {str(m): marks[m] for m in marks}}
print(json.dumps(out))
