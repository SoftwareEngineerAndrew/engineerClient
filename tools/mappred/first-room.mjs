// The first rooms out from the entrance: the room behind it (shape, doors), the rooms off it, and the cells
// beside the entrance. Real maps and generator.mjs DUMPs (GEN).
const T0 = './';
const { complete } = await import(T0 + 'common.mjs');
const { readFileSync } = await import('node:fs');
const W = 6, N = 36;
const NB = t => { const x = t % W, z = (t / W) | 0, o = []; if (x + 1 < W) o.push(t + 1); if (x > 0) o.push(t - 1); if (z + 1 < W) o.push(t + W); if (z > 0) o.push(t - W); return o; };
/** maps as { owner[], shape[] (room 0 the entrance), doors: [[a, b] tiles] } */
const sets = {};
sets.real = complete().filter(L => L.rooms.filter(r => r.shape !== 'OneByOne').length === 7).map(L => {
  const E = L.rooms.find(r => r.type === 'ENTRANCE'); const idx = new Map([[E.i, 0]]); let k = 1; for (const r of L.rooms) if (r !== E) idx.set(r.i, k++);
  const owner = new Array(N); const shape = []; for (const r of L.rooms) { shape[idx.get(r.i)] = r.shape; for (const t of r.tiles) owner[t.z * W + t.x] = idx.get(r.i); }
  const doors = L.doors.filter(d => d.what !== '-' && d.a >= 0 && d.b >= 0 && d.a !== d.b).map(d => [d.az * W + d.ax, d.bz * W + d.bx]);
  return { owner, shape, doors, ent: E.tiles[0].z * W + E.tiles[0].x };
});
if (process.env.GEN) for (const [v, list] of Object.entries(JSON.parse(readFileSync(process.env.GEN, 'utf8')))) sets[v] = list.map(({ owner, shape, parent, ent }) => {
  const doors = []; for (let t = 0; t < N; t++) if (parent[t] >= 0 && owner[parent[t]] !== owner[t]) doors.push([t, parent[t]]);
  return { owner, shape, doors, ent };
});
const SH = { TwoByOne: '1x2', ThreeByOne: '1x3', FourByOne: '1x4', TwoByTwo: '2x2', L: 'L', OneByOne: '1x1' };
for (const [name, maps] of Object.entries(sets)) {
  const c = {}; const inc = (k, v = 1) => c[k] = (c[k] || 0) + v;
  for (const M of maps) {
    const deg = new Map(); const nbr = new Map();
    for (const [a, b] of M.doors) { const ra = M.owner[a], rb = M.owner[b]; deg.set(ra, (deg.get(ra) || 0) + 1); deg.set(rb, (deg.get(rb) || 0) + 1); (nbr.get(ra) || nbr.set(ra, []).get(ra)).push(rb); (nbr.get(rb) || nbr.set(rb, []).get(rb)).push(ra); }
    const first = nbr.get(0)[0];
    const fs = SH[M.shape[first]];
    inc(`behind entrance ${fs === '1x1' ? '1x1' : 'big'}: doors ${Math.min(deg.get(first), 4)}`);
    inc(`behind entrance ${fs === '1x1' ? '1x1' : 'big'}`);
    // rooms off the first room that are 1x1 dead ends
    const off = nbr.get(first).filter(r => r !== 0);
    inc('rooms off the first room', off.length); inc('of them 1x1 dead ends', off.filter(r => M.shape[r] === 'OneByOne' && deg.get(r) === 1).length);
    inc('of them big', off.filter(r => M.shape[r] !== 'OneByOne').length);
  }
  const n = maps.length;
  console.log(`\n${name} (${n} maps)`);
  for (const k of Object.keys(c).sort()) { const m = /^(behind entrance (1x1|big)): doors/.exec(k); console.log(`  ${k.padEnd(36)} ${(m ? c[k] / c[m[1]] : c[k] / n).toFixed(3)}`); }
}
