import { load, graph } from './layout.mjs';

/**
 * What each floor's maps follow (F and M alike; floors.mjs checks it): the main grid (tile x, z from the
 * dungeon's north-west corner), whether there's a trap room, and how many walk steps the fairy is before
 * blood (the wither path is 7-8 steps on F1-F4, 9-10 on F5-F7). On F4-F6 the column east of the main grid
 * (x = w) holds only 1x1 dead ends, each behind one door from a normal room west of it: the puzzle column,
 * which tops the map's dead ends (not entrance or blood) up to [target].
 */
export const FLOORS = {
  1: { w: 4, h: 5, fairy: 3 },
  2: { w: 5, h: 5, fairy: 3 },
  3: { w: 5, h: 5, trap: true, fairy: 3 },
  4: { w: 5, h: 5, trap: true, fairy: 3, column: true, target: 6 },
  5: { w: 5, h: 6, trap: true, fairy: 4, column: true, target: 5 },
  6: { w: 5, h: 6, trap: true, fairy: 4, column: true, target: 5 },
  7: { w: 6, h: 6, trap: true, fairy: 4 },
};
/** "5,6" (env FLOORS; default 7) -> ['F5', 'M5', 'F6', 'M6']. */
export const floorList = (s = process.env.FLOORS || '7') => String(s).split(',').flatMap(n => ['F' + n, 'M' + n]);

export function complete(file = process.env.LAYOUTS || 'layouts.jsonl', floors = floorList()) {
  const seen = new Set();
  return load(file).filter(L => {
    if (floors && !floors.includes(L.floor)) return false;
    const F = FLOORS[L.floor[1]]; if (!F) return false;
    // the main grid full, nothing outside it but the puzzle column's rows
    for (let z = 0; z < 6; z++) for (let x = 0; x < 6; x++) {
      const main = x < F.w && z < F.h, column = F.column && x === F.w && z < F.h;
      if (main && L.grid[z][x] < 0 || !main && !column && L.grid[z][x] >= 0) return false;
    }
    if (!L.rooms.every(r => r.name && ({ OneByOne: 1, TwoByOne: 2, ThreeByOne: 3, FourByOne: 4, TwoByTwo: 4, L: 3 })[r.shape] === r.tiles.length)) return false;
    let spots = 0; for (const d of L.doors) if (d.bx < F.w && d.bz < F.h) spots++;
    if (spots < (F.w - 1) * F.h + F.w * (F.h - 1)) return false;
    const g = graph(L); let e = 0; for (const [, a] of g) e += a.length;
    if (e / 2 !== L.rooms.length - 1) return false; // a tree, connected: as many doors as that, and every room reached
    const seenR = new Set([0]), q = [0];
    while (q.length) for (const x of g.get(q.pop())) if (!seenR.has(x.to)) { seenR.add(x.to); q.push(x.to); }
    if (seenR.size !== L.rooms.length) return false;
    // the same dungeon recorded twice (two groups): keep one
    const key = L.rooms.map(r => r.name + r.tiles.map(t => t.x + '' + t.z).join('')).sort().join('|');
    if (seen.has(key)) return false; seen.add(key);
    L.w = F.w + (F.column ? 1 : 0); L.h = F.h; L.rules = F; L.g = g;
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
