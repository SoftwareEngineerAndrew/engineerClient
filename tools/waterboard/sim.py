"""Line-for-line port of src/waterboard/Geometry.kt + WaterSim.kt, instrumented for validation."""
AIR, SOLID, DYN, NONE = -1, -2, 16, None
DRIED_FLOWS = False   # vanilla 1.8 drops a level-7 block below a block that just dried up; Hypixel doesn't
X0, X1, Y0, Y1 = 4, 26, 56, 83
BX0, BX1, BY0, BY1 = 5, 25, 57, 80
OUTLET_X = [24, 20, 15, 10, 6]
COLORS = ['purple', 'orange', 'blue', 'green', 'red']
GATE_Z = [19, 18, 17, 16, 15]
LEVERS = ['quartz', 'gold', 'coal', 'diamond', 'emerald', 'clay', 'water']
LEVER_POS = [(20, 61, 20), (20, 61, 15), (20, 61, 10), (10, 61, 20), (10, 61, 15), (10, 61, 10), (15, 60, 5)]
SLOT_CHARS = 'QGCDET'
W = X1 - X0 + 1
H = Y1 - Y0 + 1


def cell(x, y): return (y - Y0) * W + (x - X0)
def cx(c): return c % W + X0
def cy(c): return c // W + Y0


class Geometry:
    def __init__(self, rotation, open_, slot_cell, slot_lever):
        self.rotation, self.open, self.slot_cell, self.slot_lever = rotation, open_, slot_cell, slot_lever
        self.w, self.h = W, H
        self.source = cell(15, 82)
        self.outlet_cell = [cell(x, 58) for x in OUTLET_X]
        plus = {'NORTH': 'W', 'WEST': 'S', 'SOUTH': 'E', 'EAST': 'N'}[rotation]
        minus = {'N': 'S', 'S': 'N', 'E': 'W', 'W': 'E'}[plus]
        rel = lambda f: 1 if f == plus else (-1 if f == minus else None)
        self.horizontal = [r for r in map(rel, 'NESW') if r is not None]
        self.set_order = [r for r in map(rel, 'NSWE') if r is not None]
        self.notify_order = [(-W if f == 'D' else W if f == 'U' else rel(f)) for f in 'WEDUNS']
        self.notify_order = [d for d in self.notify_order if d is not None]

    @staticmethod
    def parse(rows, rotation):
        assert len(rows) == BY1 - BY0 + 1 and all(len(r) == BX1 - BX0 + 1 for r in rows)
        open_, slots, levers, inn = [], [], [], []
        for i, row in enumerate(rows):
            y = BY1 - i
            for j, ch in enumerate(row):
                x = BX0 + j
                if ch == '.':
                    if y <= 78 or x == 15: open_.append(cell(x, y))
                elif ch.upper() in SLOT_CHARS:
                    slots.append(cell(x, y)); levers.append(SLOT_CHARS.index(ch.upper())); inn.append(ch.isupper())
        for y in (79, 80, 81):
            if cell(15, y) not in open_: open_.append(cell(15, y))
        return Geometry(rotation, open_, slots, levers), inn


class WaterSim:
    RATE, LAND = 5, 2
    WATER_LEVER = 6

    def __init__(self, geo, slot_in, closed=0, time=0):
        self.geo = geo
        n = W * H
        self.cells = [SOLID] * n
        for c in geo.open: self.cells[c] = AIR
        for k, c in enumerate(geo.slot_cell): self.cells[c] = SOLID if slot_in[k] else AIR
        self.cells[geo.source] = SOLID
        self.due = [NONE] * n
        self.queue = [[] for _ in range(8)]
        self.slot_in = list(slot_in)
        self.land_at = [NONE] * len(geo.slot_cell)
        self.time = time; self.source_on = False; self.closed = closed; self.wet = 0; self.wrong_hits = 0
        self.arrivals = []; self.phase = 0; self.adj = 0
        self.on_write = None; self.on_send = None
        self.log = []      # (time, phase, cell, view)
        self.random_ticks = {}   # tick -> [cell]

    def step(self, flips=0):
        self.time += 1
        if flips & (1 << 6):
            self.time -= 1; self.phase = 1; self.set_source(not self.source_on); self.time += 1
        self.phase = 0
        self.run_due()
        # random ticks (WorldServer.updateBlocks, after the scheduled ticks): flowing water only
        for c in self.random_ticks.get(self.time, ()):
            v = self.cells[c]
            if v >= 0 and v & DYN: self.update(c)
        self.outlets()
        if self.on_send: self.on_send()
        self.phase = 1
        if flips & 63:
            for k, c in enumerate(self.geo.slot_cell):
                if flips & (1 << self.geo.slot_lever[k]): self.toggle(k)
        for k in range(len(self.land_at)):
            if self.land_at[k] == self.time:
                self.land_at[k] = NONE; self.set(self.geo.slot_cell[k], SOLID, True)

    def set_source(self, on):
        self.source_on = on
        self.set(self.geo.source, DYN if on else SOLID, True)

    def toggle(self, k):
        c = self.geo.slot_cell[k]
        if self.land_at[k] is not NONE: self.land_at[k] = NONE; self.set(c, SOLID, True)
        if self.slot_in[k]:
            self.set(c, AIR, True)
        else:
            if self.cells[c] >= 0: self.set(c, AIR, True)
            self.write(c, SOLID, send=False)      # Blocks.piston_extension, flag 4: not sent to clients
            self.land_at[k] = self.time + self.LAND
        self.slot_in[k] = not self.slot_in[k]

    def outlets(self):
        now = 0
        for o, c in enumerate(self.geo.outlet_cell):
            if self.cells[c] >= 0: now |= 1 << o
        arrived = now & ~self.wet
        self.wet = now
        for o in range(5):
            if not arrived & (1 << o): continue
            if not self.closed & (1 << o): self.wrong_hits += 1
            self.closed ^= 1 << o
            self.arrivals.append((self.time, o))

    def write(self, c, v, send=True):
        self.cells[c] = v
        self.last_send_flag = send
        self.log.append((self.time, self.phase, c, v & 15 if v >= 0 else v))
        if self.on_write: self.on_write(c, self.phase)

    def set(self, c, v, notify):
        before = self.cells[c]
        self.write(c, v)
        if v >= 0 and v & DYN and not (before >= 0 and before & DYN): self.schedule(c)
        if notify: self.notify(c)

    def schedule(self, c):
        if self.due[c] is not NONE: return
        t = self.time + self.RATE
        self.due[c] = t
        self.queue[t & 7].append(c)

    def notify(self, c):
        for d in self.geo.notify_order:
            q = c + d
            v = self.cells[q]
            if v >= 0 and not v & DYN: self.write(q, v | DYN); self.schedule(q)

    def run_due(self):
        b = self.time & 7
        lst = self.queue[b]
        if not lst: return
        m = []
        for c in lst:
            if self.due[c] == self.time: self.due[c] = NONE; m.append(c)
        self.queue[b] = []
        for c in m:
            v = self.cells[c]
            if v >= 0 and v & DYN: self.update(c)

    def level(self, c):
        v = self.cells[c]
        return v & 15 if v >= 0 else -1

    def blocked(self, c): return self.cells[c] == SOLID

    def check_adjacent(self, q, cur):
        i = self.level(q)
        if i < 0: return cur
        if i == 0: self.adj += 1
        if i >= 8: i = 0
        return cur if (cur >= 0 and i >= cur) else i

    def update(self, c):
        w = W
        i = self.cells[c] & 15
        if i > 0:
            l = -100
            self.adj = 0
            for d in self.geo.horizontal: l = self.check_adjacent(c + d, l)
            i1 = l + 1
            if i1 >= 8 or l < 0: i1 = -1
            up = self.level(c + w)
            if up >= 0: i1 = up if up >= 8 else up + 8
            if self.adj >= 2:
                below = c - w
                if self.cells[below] == SOLID or self.level(below) == 0: i1 = 0
            if i1 == i:
                self.write(c, i)
            else:
                i = i1
                if i1 < 0:
                    self.set(c, AIR, True)
                else:
                    self.set(c, i1 | DYN, False)
                    self.schedule(c)
                    self.notify(c)
        else:
            self.write(c, i)
        below = c - w
        if i < 0 and not DRIED_FLOWS: return
        if self.cells[below] == AIR:
            self.try_flow(below, i if i >= 8 else i + 8)
        elif i >= 0 and (i == 0 or self.blocked(below)):
            dirs = self.flow_directions(c)
            k1 = 1 if i >= 8 else i + 1
            if k1 >= 8: return
            for d in self.geo.set_order:
                if dirs & (1 if d > 0 else 2): self.try_flow(c + d, k1)

    def try_flow(self, q, lvl):
        if self.cells[q] == AIR: self.set(q, lvl | DYN, True)

    def flow_directions(self, c):
        best, s = 1000, 0
        for d in self.geo.horizontal:
            q = c + d
            if self.blocked(q): continue
            v = self.cells[q]
            if v >= 0 and v & 15 == 0: continue
            j = self.flow_cost(q, 1, -d) if self.blocked(q - W) else 0
            if j < best: s = 0
            if j <= best: s |= (1 if d > 0 else 2); best = j
        return s

    def flow_cost(self, c, dist, came):
        best = 1000
        for d in self.geo.horizontal:
            if d == came: continue
            q = c + d
            if self.blocked(q): continue
            v = self.cells[q]
            if v >= 0 and v & 15 == 0: continue
            if not self.blocked(q - W): return dist
            if dist < 4:
                j = self.flow_cost(q, dist + 1, -d)
                if j < best: best = j
        return best
