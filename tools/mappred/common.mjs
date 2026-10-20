import { load, extent, graph } from './layout.mjs';
export function complete(file = process.env.LAYOUTS || 'layouts.jsonl', floors = ['F7', 'M7']) {
  const seen = new Set();
  return load(file).filter(L => {
    if (floors && !floors.includes(L.floor)) return false;
    const [w, h] = extent(L);
    const W = { F1: 4, F2: 4, F3: 5, F4: 5, M1: 4, M2: 4, M3: 5, M4: 5 }[L.floor] ?? (L.floor.endsWith('5') ? 5 : 6);
    if (w < W || h < 6 && !['F1','F2','F3','M1','M2','M3'].includes(L.floor)) return false;
    for (let z = 0; z < h; z++) for (let x = 0; x < w; x++) if (L.grid[z][x] < 0) return false;
    if (!L.rooms.every(r => r.name && ({ OneByOne: 1, TwoByOne: 2, ThreeByOne: 3, FourByOne: 4, TwoByTwo: 4, L: 3 })[r.shape] === r.tiles.length)) return false;
    let spots = 0; for (const d of L.doors) if (d.bx < w && d.bz < h) spots++;
    if (spots < (w - 1) * h + w * (h - 1)) return false;
    const g = graph(L); let e = 0; for (const [, a] of g) e += a.length;
    if (e / 2 !== L.rooms.length - 1) return false; // a tree, connected
    // the same dungeon recorded twice (two groups): keep one
    const key = L.rooms.map(r => r.name + r.tiles.map(t => t.x + '' + t.z).join('')).sort().join('|');
    if (seen.has(key)) return false; seen.add(key);
    L.w = w; L.h = h; L.g = g;
    return true;
  });
}
export const inc = (o, k, n = 1) => { o[k] = (o[k] || 0) + n; };
export function heat(cells, w = 6, h = 6) { // cells: count grid
  const out = [];
  for (let z = 0; z < h; z++) out.push(Array.from({ length: w }, (_, x) => String(cells[z * w + x] || 0).padStart(5)).join(''));
  return out.join('\n');
}
export function pathBetween(L, a, b) { // room indices; returns list of edges
  const prev = new Map([[a, null]]); const q = [a];
  while (q.length) { const u = q.shift(); if (u === b) break; for (const e of L.g.get(u)) if (!prev.has(e.to)) { prev.set(e.to, [u, e]); q.push(e.to); } }
  const path = []; let c = b; while (prev.get(c)) { const [u, e] = prev.get(c); path.unshift({ from: u, to: c, what: e.what }); c = u; }
  return path;
}
// The 8 symmetries of the 6x6 grid as tile maps.
export const SYM = [
  (x, z) => [x, z], (x, z) => [5 - z, x], (x, z) => [5 - x, 5 - z], (x, z) => [z, 5 - x],
  (x, z) => [5 - x, z], (x, z) => [z, x], (x, z) => [x, 5 - z], (x, z) => [5 - z, 5 - x],
];
/** The symmetry that puts the entrance on the top row, in the left half. */
export function normFor(L) {
  const e = L.rooms.find(r => r.type === 'ENTRANCE').tiles[0];
  for (const s of SYM) { const [x, z] = s(e.x, e.z); if (z === 0 && x <= 2) return s; }
  return null;
}
