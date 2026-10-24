// What decides whether a room the tree reaches starts a big room? Every room entry (its entry tile, the walk
// it's at) with the state then - the rooms entered at a shallower walk placed, the rest free - and a logistic
// fit of "big" on that state. Real maps, and generator.mjs DUMPs (GEN) whose rule is known (grow: chance
// c * shapes left / free cells), so what the reconstruction does by itself shows there too.
//   LAYOUTS=layouts.jsonl [GEN=dump.json] node startrule.mjs
import { readFileSync } from 'node:fs';
import { complete } from './common.mjs';
import { walkFrom } from './walk.mjs';
const W = 6, N = 36;
const NB = Array.from({ length: N }, (_, t) => { const x = t % W, z = (t / W) | 0, o = []; if (x + 1 < W) o.push(t + 1); if (x > 0) o.push(t - 1); if (z + 1 < W) o.push(t + W); if (z > 0) o.push(t - W); return o; });
const ORIENT = {
  TwoByOne: [[[0, 0], [1, 0]], [[0, 0], [0, 1]]],
  ThreeByOne: [[[0, 0], [1, 0], [2, 0]], [[0, 0], [0, 1], [0, 2]]],
  FourByOne: [[[0, 0], [1, 0], [2, 0], [3, 0]], [[0, 0], [0, 1], [0, 2], [0, 3]]],
  TwoByTwo: [[[0, 0], [1, 0], [0, 1], [1, 1]]],
  L: [[[0, 0], [1, 0], [0, 1]], [[0, 0], [1, 0], [1, 1]], [[0, 0], [0, 1], [1, 1]]],
};
/** maps as { rooms: [{ d, entry, tiles: [t], shape }] } without the entrance */
const sets = {};
sets.real = complete().filter(L => L.rooms.filter(r => r.shape !== 'OneByOne').length === 7).map(L => {
  const E = L.rooms.find(r => r.type === 'ENTRANCE'); const wf = walkFrom(L, E.i);
  return L.rooms.filter(r => r !== E).map(r => { const { dist, at } = wf.get(r.i); return { d: dist, entry: at[1] * W + at[0], tiles: r.tiles.map(t => t.z * W + t.x), shape: r.shape }; });
});
if (process.env.GEN) for (const [v, list] of Object.entries(JSON.parse(readFileSync(process.env.GEN, 'utf8')))) sets[v] = list.map(({ owner, shape, depth }) => {
  const by = new Map(); for (let t = 0; t < N; t++) { const r = owner[t]; if (r === 0) continue; const o = by.get(r) || { d: 99, entry: -1, tiles: [], shape: shape[r] }; o.tiles.push(t); if (depth[t] < o.d) { o.d = depth[t]; o.entry = t; } by.set(r, o); }
  return [...by.values()];
});
const FEATS = ['log shapes left', 'log free cells', 'log fits', 'walk', 'entry on the edge', 'log open doors'];
function events(rooms) {
  const out = [];
  for (const r of rooms) {
    const earlier = rooms.filter(o => o.d < r.d), left = 7 - earlier.filter(o => o.shape !== 'OneByOne').length;
    const avail = new Uint8Array(N); for (const o of rooms) if (o.d >= r.d) for (const t of o.tiles) avail[t] = 1;
    let free = 0; for (let t = 0; t < N; t++) free += avail[t];
    // the big shapes left: the map's big rooms entered at this walk or later
    const shapesLeft = [...new Set(rooms.filter(o => o.d >= r.d && o.shape !== 'OneByOne').map(o => o.shape))];
    let fits = 0;
    for (const s of shapesLeft) for (const o of ORIENT[s]) for (let a = 0; a < N; a++) { const x0 = a % W, z0 = (a / W) | 0; let ok = true, has = false;
      for (const [dx, dz] of o) { const x = x0 + dx, z = z0 + dz; if (x >= W || z >= W || !avail[z * W + x]) { ok = false; break; } if (z * W + x === r.entry) has = true; }
      if (ok && has) fits++; }
    if (left <= 0 || fits === 0) continue;   // a 1x1 for sure: nothing to decide
    const x = r.entry % W, z = (r.entry / W) | 0;
    const open = rooms.filter(o => o.d === r.d).length;
    out.push([r.shape !== 'OneByOne' ? 1 : 0, [Math.log(left), Math.log(free), Math.log(fits), r.d, +(x === 0 || z === 0 || x === 5 || z === 5), Math.log(open)]]);
  }
  return out;
}
function logistic(ev) {
  const K = FEATS.length + 1; const b = new Float64Array(K);
  const mu = new Float64Array(K - 1), sd = new Float64Array(K - 1);
  for (const [, f] of ev) f.forEach((v, k) => { mu[k] += v / ev.length; });
  for (const [, f] of ev) f.forEach((v, k) => { sd[k] += (v - mu[k]) ** 2 / ev.length; });
  for (let k = 0; k < K - 1; k++) sd[k] = Math.sqrt(sd[k]) || 1;
  const X = ev.map(([y, f]) => [y, [1, ...f.map((v, k) => (v - mu[k]) / sd[k])]]);
  for (let it = 0; it < 400; it++) {
    const g = new Float64Array(K);
    for (const [y, x] of X) { const p = 1 / (1 + Math.exp(-x.reduce((a, v, k) => a + v * b[k], 0))); x.forEach((v, k) => { g[k] += (y - p) * v; }); }
    for (let k = 0; k < K; k++) b[k] += 2 * g[k] / X.length;
  }
  // back in raw units (per unit of each feature) for reading
  return [...b].slice(1).map((v, k) => v / sd[k]);
}
console.log('logistic fit of "starts a big room", per unit of each feature (a log feature: per e-fold)');
const fits = Object.fromEntries(Object.entries(sets).map(([k, maps]) => { const ev = maps.flatMap(events); return [k, [logistic(ev), ev.length]]; }));
console.log(''.padEnd(22) + Object.keys(fits).map(k => k.slice(0, 18).padStart(19)).join(''));
FEATS.forEach((f, k) => console.log(f.padEnd(22) + Object.values(fits).map(([b]) => b[k].toFixed(3).padStart(19)).join('')));
console.log('events'.padEnd(22) + Object.values(fits).map(([, n]) => String(n).padStart(19)).join(''));
