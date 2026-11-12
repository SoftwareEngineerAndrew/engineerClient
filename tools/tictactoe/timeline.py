import gzip, glob, json, sys
rec, tx, tz = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
X0, Z0 = -200 + 32 * tx, -200 + 32 * tz
inb = lambda x, z: X0 - 1 <= x < X0 + 32 and Z0 - 1 <= z < Z0 + 32
frames = {}; t0 = None
def out(ms, s):
    global t0
    t0 = t0 or ms
    print(f'{(ms - t0)/1000:8.3f} {s}')
for p in sorted(glob.glob(rec + '/part*.jsonl.gz')):
    for line in gzip.open(p, 'rt'):
        if not (line.startswith('{"k":"in"') or line.startswith('{"k":"out"')): continue
        if not any(k in line for k in ('add_entity', 'set_entity_data', 'map_item_data', 'block_update', 'section_blocks', 'use_item_on', 'interact', 'remove_entities', 'swing', 'player_action')): continue
        o = json.loads(line); f = o.get('f') or {}; pk = o.get('p', '')
        if pk.endswith('add_entity') and 'item_frame' in line and inb(f['x'], f['z']):
            frames[f['id']] = (f['x'], f['y'], f['z']); out(o['ms'], f"spawn frame {f['id']} at {f['x']},{f['y']},{f['z']} facing {f.get('facing')}")
        elif pk.endswith('set_entity_data') and f.get('id') in frames:
            out(o['ms'], f"data frame {f['id']} {json.dumps(f.get('packedItems', f))[:300]}")
        elif pk.endswith('remove_entities') and any(i in frames for i in (f.get('entityIds') or f.get('ids') or [])):
            out(o['ms'], f"remove {[i for i in (f.get('entityIds') or f.get('ids')) if i in frames]}")
        elif pk.endswith('map_item_data'):
            mid = f['mapId']['id']
            if mid == 1024: continue
            cp = f.get('colorPatch') or {}
            keys = [k for k in cp if k not in ('x', 'y', 'w', 'h', 'full', 'len')]
            out(o['ms'], f"map {mid} patch keys {keys} {json.dumps({k: (cp[k] if len(str(cp[k])) < 120 else str(cp[k])[:120]) for k in keys})}")
        elif ('block_update' in pk or 'section_blocks' in pk):
            ch = [[*f['pos'], f['blockState']]] if 'pos' in f else f.get('changes', [])
            for x, y, z, s in ch:
                if inb(x, z): out(o['ms'], f"block {x},{y},{z} {s}")
        elif o['k'] == 'out' and ('use_item_on' in pk or 'interact' in pk or 'player_action' in pk):
            bp = (f.get('blockHit') or {}).get('blockPos') or f.get('pos')
            tid = f.get('entityId') or f.get('id')
            if (bp and inb(bp[0], bp[2])) or tid in frames: out(o['ms'], f"OUT {pk} {json.dumps(f)[:250]}")
