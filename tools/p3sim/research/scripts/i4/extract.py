"""i4 (F7 P3 4th device, sharpshooter target) raw extraction from Dungeon Recorder recordings.

Per recording -> $I4_OUT/rec/<rec>.json with everything near the plate / board, server ticks (`n`) on every item:
  board   : every block change (block_update / section_blocks_update) in the board box and at the plate,
            [n, seq, x, y, z, state, packet, i (index in the packet's change list)]
  chat    : every non-overlay system_chat [n, seq, plain, legacy] and title/subtitle texts
  stands  : armor stands spawned near the i4 device stand (63.5,126,34.5) or ever named Device/Active/Inactive:
            {id: {spawn: [n, x, y, z], names: [[n, seq, plain, legacy, visible]], gone: n}}
  arrows  : every arrow spawned in the S4 area: {id: {spawn: [n, seq, x, y, z, vx, vy, vz], owner, held, ev: [[n, seq, kind, ...]], gone}}
  sounds  : sounds in the plate/board area [n, seq, sound, source, x, y, z, vol, pitch]
  parts   : particles in the board box [n, seq, type, x, y, z, count]
  me      : p3wr near the plate every tick [n, t, x, y, z, yaw, pitch, crouch]
  clicks  : p3wr's use_item / swing [n, seq, kind, yaw, pitch, held]

    I4_OUT=~/Cluade/maxor-work/i4/out python3 extract.py [rec-prefix ...]
"""
import gzip, json, os, sys
from concurrent.futures import ProcessPoolExecutor

R = os.path.expanduser(os.environ.get('I4_RECORDINGS', '~/.local/share/PrismLauncher/instances/26.1.2 BRW/minecraft/engineerclient-recordings'))
OUT = os.path.expanduser(os.environ.get('I4_OUT', '~/Cluade/maxor-work/i4/out'))

WANT = (b'"k":"meta"', b'"k":"world"', b'"k":"inv"', b'"k":"me"', b'block_update', b'section_blocks_update', b'system_chat',
        b'title_text', b'add_entity', b'set_entity_data', b'set_entity_motion', b'entity_position_sync', b'teleport_entity',
        b'move_entity', b'remove_entities', b'minecraft:sound"', b'level_particles', b'use_item"', b'minecraft:swing')
COLORS = {'black': '0', 'dark_blue': '1', 'dark_green': '2', 'dark_aqua': '3', 'dark_red': '4', 'dark_purple': '5', 'gold': '6',
          'gray': '7', 'dark_gray': '8', 'blue': '9', 'green': 'a', 'aqua': 'b', 'red': 'c', 'light_purple': 'd', 'yellow': 'e', 'white': 'f'}
FMT = (('obfuscated', 'k'), ('bold', 'l'), ('strikethrough', 'm'), ('underlined', 'n'), ('italic', 'o'))


def legacy(j, inh=None):
    """A text component (the recorder's `j`, or a plain string) as the legacy §-coded string 1.8 servers send."""
    if j is None: return None
    if isinstance(j, str): return j
    st = dict(inh or {})
    for k in ('color',) + tuple(a for a, _ in FMT):
        if k in j: st[k] = j[k]
    s = ''
    text = j.get('text', '')
    if text:
        code = ''
        if st.get('color') in COLORS: code += '§' + COLORS[st['color']]
        for a, c in FMT:
            if st.get(a) and a != 'italic': code += '§' + c
        s += code + text
    for e in j.get('extra', []) or []:
        s += legacy(e, st)
    return s


def inbox(p, lo, hi): return all(lo[i] <= p[i] <= hi[i] for i in range(3))


BOARD = ((58, 120, 44), (74, 136, 56))
PLATE_AREA = ((56, 120, 28), (72, 134, 40))
STAND_AREA = ((58, 120, 30), (69, 132, 39))
ARROW_AREA = ((40, 100, 20), (90, 150, 60))
SOUND_AREA = ((50, 110, 25), (80, 145, 58))
PLATE = (63, 127, 35)


def one(rec):
    d = os.path.join(R, rec)
    try: man = json.load(open(os.path.join(d, 'manifest.json')))
    except (OSError, ValueError): man = {}
    lines = []
    for part in sorted(f for f in os.listdir(d) if f.endswith('.jsonl.gz')):
        try:
            with gzip.open(os.path.join(d, part), 'rb') as f:
                for line in f:
                    if any(w in line for w in WANT):
                        try: lines.append(json.loads(line))
                        except ValueError: pass
        except (EOFError, OSError):
            pass
    lines.sort(key=lambda o: o.get('seq', 0))
    self_id = man.get('selfId'); slots = {}; sel = 0
    board, chat, sounds, parts, me, clicks = [], [], [], [], [], []
    stands, arrows, etype = {}, {}, {}
    for o in lines:
        k = o['k']; n = o.get('n'); seq = o.get('seq', 0)
        if k in ('meta', 'world'):
            self_id = o.get('selfId', self_id)
        elif k == 'inv':
            sel = o.get('sel', sel)
            for s, item in o.get('s', []): slots[s] = (item or {}).get('sb')
        elif k == 'me':
            sel = o.get('slot', sel)
            pos = o.get('pos')
            if pos and inbox(pos, *PLATE_AREA):
                rot = o.get('rot') or [None, None]
                me.append([n, o.get('t'), pos[0], pos[1], pos[2], rot[0], rot[1], o.get('crouch')])
        elif k == 'out':
            p = o.get('p', ''); f = o.get('f', {})
            if p == 'minecraft:use_item':
                clicks.append([n, seq, 'use', f.get('yRot'), f.get('xRot'), slots.get(sel)])
            elif p == 'minecraft:swing':
                clicks.append([n, seq, 'swing', None, None, slots.get(sel)])
        elif k == 'in':
            p = o.get('p', ''); f = o.get('f', {})
            if p == 'minecraft:block_update':
                pos = f.get('pos')
                if pos and (inbox(pos, *BOARD) or inbox(pos, *PLATE_AREA)):
                    board.append([n, seq] + list(pos) + [f.get('blockState'), 'bu', 0])
            elif p == 'minecraft:section_blocks_update':
                for i, c in enumerate(f.get('changes') or []):
                    if inbox(c[:3], *BOARD) or inbox(c[:3], *PLATE_AREA):
                        board.append([n, seq] + list(c[:3]) + [c[3], 'sbu', i])
            elif p == 'minecraft:system_chat':
                if not f.get('overlay'):
                    c = f.get('content') or {}
                    chat.append([n, seq, c.get('t'), legacy(c.get('j', c.get('t')))])
            elif p in ('minecraft:set_title_text', 'minecraft:set_subtitle_text'):
                c = f.get('text') or {}
                chat.append([n, seq, '#' + p.split(':')[1] + ' ' + str(c.get('t')), legacy(c.get('j', c.get('t')))])
            elif p == 'minecraft:add_entity':
                ty = f.get('type'); eid = f.get('id'); etype[eid] = ty
                pos = [f.get('x'), f.get('y'), f.get('z')]
                if ty == 'minecraft:armor_stand' and inbox(pos, *STAND_AREA):
                    st = stands.setdefault(eid, {'spawn': [n] + pos, 'spawns': [], 'names': [], 'gone': None, 'gones': [], 'moves': []})
                    st['spawns'].append([n, seq] + pos)
                elif ty == 'minecraft:arrow' and inbox(pos, *ARROW_AREA):
                    owner = f.get('data')
                    arrows[eid] = {'spawn': [n, seq] + pos + list(f.get('movement') or [0, 0, 0]),
                                   'own': 'self' if owner == self_id else ('none' if owner == eid else owner),
                                   'held': slots.get(sel) if owner == self_id else None, 'ev': [], 'gone': None}
            elif p == 'minecraft:set_entity_data':
                eid = f.get('id')
                items = {x[0]: x[2] for x in f.get('packedItems', [])}
                if eid in stands or (etype.get(eid) == 'minecraft:armor_stand' and 2 in items):
                    if 2 in items or 3 in items:
                        nm = items.get(2)
                        plain = nm.get('t') if isinstance(nm, dict) else nm
                        if eid not in stands:
                            if not (plain and any(w in plain for w in ('Device', 'Active', 'Inactive'))):
                                continue
                            stands[eid] = {'spawn': None, 'spawns': [], 'names': [], 'gone': None, 'gones': [], 'moves': []}
                        stands[eid]['names'].append([n, seq, plain, legacy(nm.get('j', nm.get('t'))) if isinstance(nm, dict) else nm, items.get(3)])
                elif eid in arrows:
                    arrows[eid]['ev'].append([n, seq, 'data', {str(a): b for a, b in items.items()}])
            elif p == 'minecraft:set_entity_motion':
                eid = f.get('id')
                if eid in arrows: arrows[eid]['ev'].append([n, seq, 'v'] + list(f.get('movement')))
            elif p == 'minecraft:entity_position_sync':
                eid = f.get('id')
                if eid in arrows:
                    vals = f.get('values') or {}
                    arrows[eid]['ev'].append([n, seq, 'sync'] + list(o.get('abs') or vals.get('position')) + [vals.get('deltaMovement'), f.get('onGround')])
                elif eid in stands:
                    stands[eid]['moves'].append([n] + list(o.get('abs') or []))
            elif p == 'minecraft:teleport_entity' or p.startswith('minecraft:move_entity'):
                eid = f.get('id', f.get('entityId'))
                if eid in arrows:
                    arrows[eid]['ev'].append([n, seq, 'tp' if 'teleport' in p else 'mv'] + list(o.get('abs') or [None] * 3))
                elif eid in stands:
                    stands[eid]['moves'].append([n] + list(o.get('abs') or []))
            elif p == 'minecraft:remove_entities':
                for i in f.get('entityIds', []):
                    if i in arrows and arrows[i]['gone'] is None: arrows[i]['gone'] = [n, seq]
                    if i in stands:
                        stands[i]['gones'].append([n, seq])
                        if stands[i]['gone'] is None: stands[i]['gone'] = n
            elif p == 'minecraft:sound':
                pos = [f.get('x', 0) / 8, f.get('y', 0) / 8, f.get('z', 0) / 8]
                if inbox(pos, *SOUND_AREA):
                    sounds.append([n, seq, f.get('sound'), f.get('source'), pos[0], pos[1], pos[2], f.get('volume'), f.get('pitch')])
            elif p == 'minecraft:level_particles':
                pos = [f.get('x'), f.get('y'), f.get('z')]
                if None not in pos and inbox(pos, *BOARD):
                    pt = f.get('particle')
                    parts.append([n, seq, pt.get('type') if isinstance(pt, dict) else str(pt), pos[0], pos[1], pos[2], f.get('count')])
    # only keep arrows that matter: spawned near the plate, or that ever came near the board
    keep = {}
    for eid, a in arrows.items():
        sp = a['spawn'][2:5]
        near_plate = abs(sp[0] - 63.5) <= 8 and abs(sp[1] - 128) <= 8 and abs(sp[2] - 35.5) <= 8
        near_board = any(e[2] in ('sync', 'tp', 'mv') and e[3] is not None and inbox(e[3:6], *BOARD) for e in a['ev'])
        if near_plate or near_board: keep[eid] = a
    out = {'rec': rec, 'server': man.get('server'), 'self': man.get('self'), 'selfId': self_id, 'board': board, 'chat': chat,
           'stands': stands, 'arrows': keep, 'sounds': sounds, 'parts': parts, 'me': me, 'clicks': clicks}
    os.makedirs(os.path.join(OUT, 'rec'), exist_ok=True)
    with open(os.path.join(OUT, 'rec', rec + '.json'), 'w') as fo:
        json.dump(out, fo)
    tb = sum(1 for b in board if b[4] == 50 and 'emerald' in b[5])
    return rec, man.get('server'), tb, len(keep)


if __name__ == '__main__':
    pre = sys.argv[1:]
    recs = sorted(x for x in os.listdir(R) if os.path.isdir(os.path.join(R, x)) and not x.startswith('.')
                  and (not pre or any(x.startswith(p) for p in pre)))
    with ProcessPoolExecutor(10) as ex:
        for rec, srv, tb, na in ex.map(one, recs, chunksize=1):
            print(rec, srv, 'lights', tb, 'arrows', na, flush=True)
