// Partial-map observations from full layouts, and the features the predictor sees for each unknown tile.
// A layout here is a complete() one from common.mjs (6x6 F7/M7 unless said otherwise).

export const CLASSES = ['n', 'o', 'P', 'T', 'Y', 'B', 'F', 'R'];
export const CLS = Object.fromEntries(CLASSES.map((c, i) => [c, i]));
export function classOf(r) {
  switch (r.type) {
    case 'NORMAL': return r.shape === 'OneByOne' ? 'o' : 'n';
    case 'PUZZLE': return 'P'; case 'TRAP': return 'T'; case 'CHAMPION': return 'Y';
    case 'BLOOD': return 'B'; case 'FAIRY': return 'F'; case 'RARE': return 'R'; case 'ENTRANCE': return 'E';
  }
  return 'n';
}

// Door between tiles: the layout's door list keyed by the lower tile and direction.
function doorKinds(L) {
  // edge id: horizontal (x,z)-(x+1,z) = 'h'+x+','+z ; vertical (x,z)-(x,z+1) = 'v'+x+','+z
  const m = new Map();
  for (const d of L.doors) {
    const id = (d.bx === d.ax + 1 ? 'h' : 'v') + d.ax + ',' + d.az;
    let k = d.what;
    if (d.a === d.b) k = 'same';
    else if (k === '-') k = 'none';
    else if (k === 'e' || k === 'f') k = 'n';
    m.set(id, k);
  }
  return m;
}

/** Room-level tree: path from entrance to blood (room indices), each room's door kinds. */
export function prep(L) {
  if (L._prep) return L._prep;
  const E = L.rooms.find(r => r.type === 'ENTRANCE'), B = L.rooms.find(r => r.type === 'BLOOD');
  const prev = new Map([[E.i, -1]]); const q = [E.i];
  while (q.length) { const u = q.shift(); for (const e of L.g.get(u)) if (!prev.has(e.to)) { prev.set(e.to, u); q.push(e.to); } }
  const path = []; for (let c = B.i; c !== -1; c = prev.get(c)) path.unshift(c);
  const onPath = new Set(path);
  // a wither door is any door on the entrance->blood path except the first (entrance) and last (blood)
  const kinds = doorKinds(L);
  for (const [id, k] of kinds) {
    if (k !== 'n' && k !== 'w') continue;
    const [a, b] = edgeTiles(id).map(([x, z]) => L.grid[z][x]);
    const pa = path.indexOf(a), pb = path.indexOf(b);
    const onP = pa >= 0 && pb >= 0 && Math.abs(pa - pb) === 1;
    if (onP) {
      const lo = Math.min(pa, pb);
      kinds.set(id, lo === 0 ? 'n' : lo === path.length - 2 ? 'b' : 'w');
    }
  }
  L._prep = { E, B, path, onPath, kinds, parent: prev };
  return L._prep;
}

export function edgeTiles(id) {
  const [x, z] = id.slice(1).split(',').map(Number);
  return id[0] === 'h' ? [[x, z], [x + 1, z]] : [[x, z], [x, z + 1]];
}

/** Rooms discovered in the order a party might: entrance and the room behind it, then growth through doors, path rooms favoured. */
export function discoveryOrder(L, rnd, pathWeight = 2.5) {
  const { E, onPath } = prep(L);
  const got = new Set([E.i]); const order = [E.i];
  const first = L.g.get(E.i)[0].to; got.add(first); order.push(first);
  while (order.length < L.rooms.length) {
    const front = [];
    for (const i of got) for (const e of L.g.get(i)) if (!got.has(e.to)) front.push(e.to);
    const w = front.map(i => onPath.has(i) ? pathWeight : 1);
    let s = rnd() * w.reduce((a, b) => a + b, 0), k = 0;
    while (s > w[k] && k < front.length - 1) s -= w[k++];
    got.add(front[k]); order.push(front[k]);
  }
  return order;
}

/**
 * What the map shows with [known] rooms discovered: per tile the room index or -1, per edge its kind
 * when a side is discovered ('none','n','w','b','same') or 'unk'.
 */
export function observe(L, known) {
  const W = L.w, H = L.h;
  const tile = Array.from({ length: H }, (_, z) => Array.from({ length: W }, (_, x) => known.has(L.grid[z][x]) ? L.grid[z][x] : -1));
  const { kinds } = prep(L);
  const edge = new Map();
  for (const [id, k] of kinds) {
    const [[ax, az], [bx, bz]] = edgeTiles(id);
    if (ax >= W || bx >= W || az >= H || bz >= H) continue;
    edge.set(id, tile[az][ax] >= 0 || tile[bz][bx] >= 0 ? k : 'unk');
  }
  const rooms = new Map();
  for (const i of known) { const r = L.rooms[i]; rooms.set(i, { type: r.type, cls: classOf(r), shape: r.shape, tiles: r.tiles }); }
  return { W, H, tile, edge, rooms, puzzles: L.rooms.filter(r => r.type === 'PUZZLE').length };
}
