// How the big rooms start as the tree grows out: by the walk depth a room is entered at, the share of the
// rooms entered there that are big, and the c that share implies in "chance = c * shapes left / free cells"
// (shapes left and free cells counting what's entered deeper). Real maps, and generator.mjs DUMPs (GEN).
const T0 = './';
const { complete } = await import(T0 + 'common.mjs');
const { walkFrom } = await import(T0 + 'walk.mjs');
const { readFileSync } = await import('node:fs');
/** maps as [{rooms: [{d, size, big, shape}]}] */
const sets = {};
sets.real = complete().filter(L => L.rooms.filter(r => r.shape !== 'OneByOne').length === 7).map(L => {
  const E = L.rooms.find(r => r.type === 'ENTRANCE'); const wf = walkFrom(L, E.i);
  return L.rooms.filter(r => r !== E).map(r => ({ d: wf.get(r.i).dist, size: r.tiles.length, big: r.shape !== 'OneByOne', shape: r.shape }));
});
if (process.env.GEN) for (const [v, list] of Object.entries(JSON.parse(readFileSync(process.env.GEN, 'utf8')))) sets[v] = list.map(({ owner, shape, depth }) => {
  const by = new Map(); for (let t = 0; t < 36; t++) { const r = owner[t]; if (r === 0) continue; const o = by.get(r) || { d: 99, size: 0, big: shape[r] !== 'OneByOne', shape: shape[r] }; o.d = Math.min(o.d, depth[t]); o.size++; by.set(r, o); }
  return [...by.values()];
});
const D = 12;
for (const [name, maps] of Object.entries(sets)) {
  const R = new Float64Array(D), B = new Float64Array(D), exp = new Float64Array(D);
  for (const rooms of maps) for (let d = 1; d < D; d++) {
    const here = rooms.filter(r => Math.min(r.d, D - 1) === d); if (!here.length) continue;
    const deeper = rooms.filter(r => r.d >= d);
    const left = 7 - rooms.filter(r => r.big && r.d < d).length;
    const free = deeper.reduce((a, r) => a + r.size, 0);
    R[d] += here.length; B[d] += here.filter(r => r.big).length; exp[d] += here.length * left / Math.max(free, 1);
  }
  console.log(`\n${name} (${maps.length} maps)\n depth  rooms/map  big share   implied c`);
  for (let d = 1; d < D; d++) if (R[d]) console.log(`${String(d).padStart(5)}${(R[d] / maps.length).toFixed(2).padStart(10)}${(B[d] / R[d]).toFixed(3).padStart(11)}${(B[d] / exp[d]).toFixed(2).padStart(12)}`);
}
