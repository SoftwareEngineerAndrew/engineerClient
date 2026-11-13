"""Every Water Board room's board as built (before any lever): rows, rotation, gates closed."""
import gzip, glob, json, os, subprocess, sys
from harness import *
HERE = os.path.dirname(os.path.abspath(__file__))
rec, out = sys.argv[1], sys.argv[2]
room = None; first_lever = None
lines = []
for p in sorted(glob.glob(rec + '/part*.jsonl.gz')):
    try:
        for line in gzip.open(p, 'rt'):
            if line.startswith('{"k":"room"') and '"Water Board"' in line:
                o = json.loads(line)
                if o.get('rot') and o.get('clay') and not (room and room.get('rot')): room = o
                elif room is None: room = o
    except (EOFError, OSError): pass
if not room or not room.get('rot'): print('skip', 'no rotation' if room else 'no board'); sys.exit(0)
tx, tz = room['tiles'][0]; X0, Z0 = -200 + 32 * tx, -200 + 32 * tz
cx_, _, cz_ = room['clay']; rot = room['rot']
def to_room(x, y, z):
    dx, dz = x - cx_, z - cz_
    return {'NORTH': (-dx, y, -dz), 'WEST': (dz, y, -dx), 'SOUTH': (dx, y, dz), 'EAST': (-dz, y, dx)}[rot]
# first lever packet in the room: the board as built is just before it
for p in sorted(glob.glob(rec + '/part*.jsonl.gz')):
    try:
        for line in gzip.open(p, 'rt'):
            if line.startswith('{"k":"in"') and ('block_update' in line or 'section_blocks' in line) and 'lever' in line:
                o = json.loads(line); f = o['f']
                ch = [[*f['pos'], f['blockState']]] if 'pos' in f else f.get('changes', [])
                for x, y, z, s in ch:
                    if X0 <= x < X0 + 31 and Z0 <= z < Z0 + 31 and 'lever' in s and to_room(x, y, z) in [tuple(q) for q in LEVER_POS]:
                        first_lever = first_lever or o['seq']
    except (EOFError, OSError): pass
seq = first_lever or 10 ** 12
r = subprocess.run([sys.executable, os.path.join(HERE, 'rawchunks.py'), rec, str(X0), str(Z0), str(seq), out + '.w.json'], capture_output=True, text=True)
if r.returncode != 0: print('skip rawchunks', r.stderr[-200:]); sys.exit(0)
wb = json.load(open(out + '.w.json')); os.remove(out + '.w.json')
B = {','.join(map(str, to_room(*map(int, k.split(','))))): v for k, v in wb.items()}
rows = rows_from(B)
closed = sum(1 << o for o, z in enumerate(GATE_Z) if not B.get(f'15,56,{z}', 'minecraft:air').endswith(':air'))
moving = any('moving_piston' in B.get(f'{x},{y},{z}', '') for x in range(5, 26) for y in range(57, 81) for z in (26, 27))
water = sum(1 for x in range(5, 26) for y in range(57, 81) if 'water' in B.get(f'{x},{y},26', ''))
json.dump({'rec': os.path.basename(rec.rstrip('/')), 'rot': rot, 'rows': rows, 'closed': closed, 'moving': moving, 'water': water, 'levered': first_lever is not None}, open(out, 'w'))
print('ok', rot, bin(closed), 'moving' if moving else '', water)
