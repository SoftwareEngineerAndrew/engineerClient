// The splits: each door from the discovered rooms into unknown tiles leads to its own subtree.
import { prep } from './sim.mjs';
import { edgeId } from './features.mjs';
const DIRS = [[1, 0], [-1, 0], [0, 1], [0, -1]];
/** Entry doors of an observation: { x, z (unknown tile behind it), kind, from (discovered room) }. */
export function entries(obs) {
  const out = [];
  const { W, H, tile, edge } = obs;
  for (let z = 0; z < H; z++) for (let x = 0; x < W; x++) {
    if (tile[z][x] >= 0) continue;
    for (const [dx, dz] of DIRS) {
      const nx = x + dx, nz = z + dz;
      if (nx < 0 || nz < 0 || nx >= W || nz >= H || tile[nz][nx] < 0) continue;
      const k = edge.get(edgeId(x, z, dx, dz));
      if (k === 'n' || k === 'w' || k === 'b') out.push({ x, z, kind: k, from: tile[nz][nx] });
    }
  }
  return out;
}
/** Truth: the room indices of each entry's subtree in the full layout. */
export function subtrees(L, known, ents) {
  return ents.map(e => {
    const root = L.grid[e.z][e.x];
    const seen = new Set([root]); const q = [root];
    while (q.length) { const u = q.pop(); for (const ed of L.g.get(u)) if (!known.has(ed.to) && !seen.has(ed.to)) { seen.add(ed.to); q.push(ed.to); } }
    return seen;
  });
}
/** Geodesic (through unknown tiles) distance from each entry's tile; membership weights per tile by softmin. */
export function membership(obs, ents, A, beta = 1.5) {
  const { W, H, tile } = obs;
  const dists = ents.map(e => {
    const d = Array.from({ length: H }, () => Array(W).fill(Infinity));
    d[e.z][e.x] = 0; const q = [[e.x, e.z]];
    for (let i = 0; i < q.length; i++) { const [x, z] = q[i]; for (const [dx, dz] of DIRS) { const nx = x + dx, nz = z + dz; if (nx < 0 || nz < 0 || nx >= W || nz >= H || tile[nz][nx] >= 0 || d[nz][nx] <= d[z][x] + 1) continue; d[nz][nx] = d[z][x] + 1; q.push([nx, nz]); } }
    return d;
  });
  return A.unknown.map(([x, z]) => {
    const w = dists.map(d => d[z][x] === Infinity ? 0 : Math.exp(-beta * d[z][x]));
    // the entry tile itself belongs to its entry
    ents.forEach((e, j) => { if (e.x === x && e.z === z) { w.fill(0); w[j] = 1; } });
    const s = w.reduce((a, b) => a + b, 0);
    return s ? w.map(v => v / s) : w;
  });
}
