"""Replay a recorded Water Board against the sim.
- board rows from the decoded blocks (as WaterBoardSolver.readBoard does)
- lever flips from the lever packets (room writes), at their arrival ms
- the server's board-cell writes (z=26, x 5..25, y 57..81) grouped into arrival batches
Server ticks are inferred: batch i -> tick, monotone, using the 5-tick water cadence (fit ms -> tick by least squares
on matched writes, iterated)."""
import json, sys
from sim import *

BOARD_Y_TOP = 81


def rows_from(B):
    def st(x, y, z): return B.get(f'{x},{y},{z}', 'minecraft:air').replace('minecraft:', '')
    slot = {'quartz_block': 'Q', 'gold_block': 'G', 'coal_block': 'C', 'diamond_block': 'D', 'emerald_block': 'E', 'terracotta': 'T'}
    rows = []
    for y in range(BY1, BY0 - 1, -1):
        r = ''
        for x in range(BX0, BX1 + 1):
            a, b = st(x, y, 26), st(x, y, 27); pist = st(x, y, 28).startswith('sticky_piston')
            ab, bb = a.split('[')[0], b.split('[')[0]
            ta, tb = slot.get(ab), slot.get(bb)
            if pist and ta: r += ta
            elif pist and tb and ab in ('air', 'water'): r += tb.lower()
            elif ab in ('air', 'water'): r += '.'
            else: r += '#'
        rows.append(r)
    return rows


def view_of(s):
    b = s.replace('minecraft:', '')
    if b.startswith('water'): return int(b.split('level=')[1].rstrip(']'))
    if b == 'air': return AIR
    return SOLID


def load(path):
    d = json.load(open(path))
    rows = rows_from(d['blocks'])
    geo, inn = Geometry.parse(rows, d['rot'])
    gates = 0
    for o, z in enumerate(GATE_Z):
        if not d['blocks'].get(f'15,56,{z}', 'minecraft:air').endswith(':air'): gates |= 1 << o
    t0 = d['writes'][0][0]
    flips, obs = [], []
    lever_state = {}
    for ms, seq, x, y, z, s in d['writes']:
        for i, p in enumerate(LEVER_POS):
            if (x, y, z) == p and 'lever' in s:
                pw = 'powered=true' in s
                if lever_state.get(i) != pw: flips.append((ms - t0, seq, i))
                lever_state[i] = pw
        if z == 26 and BX0 <= x <= BX1 and BY0 <= y <= BOARD_Y_TOP and (y <= 80 or x == 15):
            obs.append((ms - t0, seq, cell(x, y), view_of(s)))
    return d, rows, geo, inn, gates, flips, obs


def simulate(geo, inn, gates, flip_ticks, n):
    """flip_ticks: {tick: bits}; returns per tick the sent list [(cell, view)] in first-dirty order (1.8 PlayerChunkMap):
    a position is sent when its block state really changed (flowing/still count as different blocks) during the tick
    window that ends at the send; its state at send time is what goes out."""
    s = WaterSim(geo, inn, gates)
    sends = {}
    dirty = {}            # cell -> None (ordered)
    prev = list(s.cells)

    def on_write(c, ph):
        pass
    s.on_write = on_write
    state = {'last': list(s.cells)}

    # track real changes via log diff: easier to compare full cells before/after each write
    orig_write = s.write

    def write(c, v):
        old = s.cells[c]
        orig_write(c, v)
        if old != v and c not in dirty: dirty[c] = None
    s.write = write

    def on_send():
        out = []
        for c in dirty:
            v = s.cells[c]
            out.append((c, v & 15 if v >= 0 else v))
        sends[s.time] = out
        dirty.clear()
    s.on_send = on_send
    for t in range(n):
        s.step(flip_ticks.get(s.time + 1, 0))
    return s, sends
