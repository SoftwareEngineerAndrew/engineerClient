// Why do big rooms sit where they do? For each big room, the rest of its map held fixed, every spot its shape
// could have taken instead; a conditional logit says which features make the chosen spot likelier than those.
// Fitted on the real maps and on layouts from the placement rules (uniform, held cells), so what holding the
// rest fixed gives by itself shows up in the uniform layouts too.   LAYOUTS=... node placefit.mjs
const T0 = './';
const { complete } = await import(T0 + 'common.mjs');
const { makeRng } = await import(T0 + 'mlp.mjs');
const W = 6, N = 36;
const rnd = makeRng(7);
const shuffle = a => { for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };
const ORIENT = {
  TwoByOne: [[[0, 0], [1, 0]], [[0, 0], [0, 1]]],
  ThreeByOne: [[[0, 0], [1, 0], [2, 0]], [[0, 0], [0, 1], [0, 2]]],
  FourByOne: [[[0, 0], [1, 0], [2, 0], [3, 0]], [[0, 0], [0, 1], [0, 2], [0, 3]]],
  TwoByTwo: [[[0, 0], [1, 0], [0, 1], [1, 1]]],
  L: [[[0, 0], [1, 0], [0, 1]], [[0, 0], [1, 0], [1, 1]], [[0, 0], [0, 1], [1, 1]]],
};
const NB = Array.from({ length: N }, (_, t) => { const x = t % W, z = (t / W) | 0, o = []; if (x + 1 < W) o.push(t + 1); if (x > 0) o.push(t - 1); if (z + 1 < W) o.push(t + W); if (z > 0) o.push(t - W); return o; });
function spots(owner, shape, mine) {
  const out = [];
  for (const o of ORIENT[shape]) for (let t = 0; t < N; t++) { const x0 = t % W, z0 = (t / W) | 0; const ts = []; let ok = true;
    for (const [dx, dz] of o) { const x = x0 + dx, z = z0 + dz; if (x >= W || z >= W) { ok = false; break; } const u = z * W + x; if (owner[u] !== -1 && owner[u] !== mine) { ok = false; break; } ts.push(u); }
    if (ok) out.push(ts); }
  return out;
}
const FEATS = ['border sides', 'sides on other big rooms', 'touches entrance', 'corner tiles', 'lone 1x1s left', '2x2 blocks of 1x1s left', 'tiles from entrance'];
/** Features of putting room `mine` on tiles ts, everything else as owner has it (-1 = 1x1, -2 = entrance). */
function feats(owner, ts, mine, ent) {
  const ow = Int32Array.from(owner); for (let t = 0; t < N; t++) if (ow[t] === mine) ow[t] = -1; for (const t of ts) ow[t] = mine;
  const set = new Set(ts); let border = 0, big = 0, entT = 0, corner = 0;
  for (const t of ts) { const x = t % W, z = (t / W) | 0; border += 4 - NB[t].length; if ((x === 0 || x === 5) && (z === 0 || z === 5)) corner++;
    for (const u of NB[t]) { if (set.has(u)) continue; if (ow[u] >= 0) big++; if (ow[u] === -2) entT = 1; } }
  const one = t => ow[t] < 0;
  let lone = 0; for (let t = 0; t < N; t++) if (ow[t] === -1 && NB[t].every(u => !one(u))) lone++;
  let blocks = 0; for (let z = 0; z < 5; z++) for (let x = 0; x < 5; x++) { const t = z * W + x; if (one(t) && one(t + 1) && one(t + W) && one(t + W + 1)) blocks++; }
  const cx = ts.reduce((a, t) => a + t % W, 0) / ts.length, cz = ts.reduce((a, t) => a + ((t / W) | 0), 0) / ts.length;
  const dist = Math.abs(cx - ent % W) + Math.abs(cz - ((ent / W) | 0));
  return [border, big, entT, corner, lone, blocks, dist];
}
/** Choice sets from one layout: [chosen index, feature rows]. */
function choices(owner, ent) {
  const out = []; const ids = [...new Set([...owner].filter(v => v >= 0))];
  for (const id of ids) {
    const ts0 = []; for (let t = 0; t < N; t++) if (owner[t] === id) ts0.push(t);
    const shape = owner.shapeOf[id]; const alts = spots(owner, shape, id); if (alts.length < 2) continue;
    const key = ts => [...ts].sort((a, b) => a - b).join(','); const k0 = key(ts0);
    const ci = alts.findIndex(ts => key(ts) === k0); if (ci < 0) continue;
    out.push([ci, alts.map(ts => feats(owner, ts, id, ent))]);
  }
  return out;
}
function fit(sets) {
  const K = FEATS.length; const b = new Float64Array(K);
  // standardise the features over all alternatives
  const mu = new Float64Array(K), sd = new Float64Array(K); let n = 0;
  for (const [, rows] of sets) for (const r of rows) { n++; for (let k = 0; k < K; k++) mu[k] += r[k]; }
  for (let k = 0; k < K; k++) mu[k] /= n;
  for (const [, rows] of sets) for (const r of rows) for (let k = 0; k < K; k++) sd[k] += (r[k] - mu[k]) ** 2;
  for (let k = 0; k < K; k++) sd[k] = Math.sqrt(sd[k] / n) || 1;
  const X = sets.map(([c, rows]) => [c, rows.map(r => r.map((v, k) => (v - mu[k]) / sd[k]))]);
  for (let it = 0; it < 300; it++) {
    const g = new Float64Array(K);
    for (const [c, rows] of X) {
      const s = rows.map(r => r.reduce((a, v, k) => a + v * b[k], 0)); const m = Math.max(...s); const e = s.map(v => Math.exp(v - m)); const Z = e.reduce((a, v) => a + v, 0);
      for (let k = 0; k < K; k++) { let ex = 0; rows.forEach((r, i) => { ex += r[k] * e[i] / Z; }); g[k] += rows[c][k] - ex; }
    }
    for (let k = 0; k < K; k++) b[k] += 0.5 * g[k] / X.length;
  }
  return b;
}

// layouts: owner (-2 entrance, -1 1x1, room id), with owner.shapeOf
function realLayout(L) {
  const ow = new Int32Array(N).fill(-1); ow.shapeOf = {};
  for (const r of L.rooms) for (const t of r.tiles) ow[t.z * W + t.x] = r.type === 'ENTRANCE' ? -2 : r.shape === 'OneByOne' ? -1 : r.i;
  for (const r of L.rooms) if (r.shape !== 'OneByOne') ow.shapeOf[r.i] = r.shape;
  return ow;
}
function genLayout(ent, shapes, held) {
  for (let attempt = 0; attempt < 300; attempt++) {
    const ow = new Int32Array(N).fill(-1); ow.shapeOf = {}; ow[ent] = -2;
    const hs = shuffle([...Array(N).keys()].filter(t => t !== ent)).slice(0, held); for (const t of hs) ow[t] = -3;
    let ok = true;
    for (const [i, s] of shuffle([...shapes]).entries()) { const c = spots(ow, s, -99); if (!c.length) { ok = false; break; } for (const t of c[Math.floor(rnd() * c.length)]) ow[t] = 100 + i; ow.shapeOf[100 + i] = s; }
    if (!ok) continue;
    for (let t = 0; t < N; t++) if (ow[t] === -3) ow[t] = -1;
    return ow;
  }
  return null;
}
/** Local features of a spot when it's placed (the rooms so far): edge sides, sides on big rooms, touches entrance, tiles from entrance. */
function localFeats(ow, ts, ent) {
  const set = new Set(ts); let border = 0, big = 0, entT = 0;
  for (const t of ts) { border += 4 - NB[t].length; for (const u of NB[t]) { if (set.has(u)) continue; if (ow[u] >= 0) big++; if (ow[u] === -2) entT = 1; } }
  const cx = ts.reduce((a, t) => a + t % W, 0) / ts.length, cz = ts.reduce((a, t) => a + ((t / W) | 0), 0) / ts.length;
  return [border, big, entT, Math.abs(cx - ent % W) + Math.abs(cz - ((ent / W) | 0))];
}
function weightedLayout(ent, shapes, beta) {
  for (let attempt = 0; attempt < 300; attempt++) {
    const ow = new Int32Array(N).fill(-1); ow.shapeOf = {}; ow[ent] = -2; let ok = true;
    for (const [i, s] of shuffle([...shapes]).entries()) {
      const c = spots(ow, s, -99); if (!c.length) { ok = false; break; }
      const w = c.map(ts => Math.exp(localFeats(ow, ts, ent).reduce((a, v, k) => a + v * beta[k], 0)));
      let r = rnd() * w.reduce((a, b) => a + b, 0), k = 0; while (r > w[k] && k < w.length - 1) r -= w[k++];
      for (const t of c[k]) ow[t] = 100 + i; ow.shapeOf[100 + i] = s;
    }
    if (ok) return ow;
  }
  return null;
}
const Ls = complete().filter(L => L.rooms.filter(r => r.shape !== 'OneByOne').length === 7).slice(0, +(process.env.MAPS || 1500));
const sets = { real: [], uniform: [], 'held 5': [], 'held 7': [] };
for (const L of Ls) {
  const e = L.rooms.find(r => r.type === 'ENTRANCE').tiles[0], ent = e.z * W + e.x;
  sets.real.push(...choices(realLayout(L), ent));
  const shapes = L.rooms.filter(r => r.shape !== 'OneByOne').map(r => r.shape);
  for (const [name, h] of [['uniform', 0], ['held 5', 5], ['held 7', 7]]) { const ow = genLayout(ent, shapes, h); if (ow) sets[name].push(...choices(ow, ent)); }
}
if (process.env.CALIB) {
  // match the weighted generator's fit to the real one, feature by feature (edge sides, big sides, entrance, distance)
  const fk = [0, 1, 2, 6]; const realB = fit(sets.real); let beta = [0, 0, 0, 0];
  for (let it = 0; it < +(process.env.CALIB); it++) {
    const gen = []; for (const L of Ls) { const e = L.rooms.find(r => r.type === 'ENTRANCE').tiles[0], ent = e.z * W + e.x; const shapes = L.rooms.filter(r => r.shape !== 'OneByOne').map(r => r.shape); const ow = weightedLayout(ent, shapes, beta); if (ow) gen.push(...choices(ow, ent)); }
    const gb = fit(gen);
    const gap = fk.map(k => realB[k] - gb[k]);
    console.log(`iteration ${it}: beta [${beta.map(v => v.toFixed(3)).join(', ')}], fit gap [${gap.map(v => v.toFixed(3)).join(', ')}], other features: lone ${gb[4].toFixed(3)} (real ${realB[4].toFixed(3)}), 2x2 blocks ${gb[5].toFixed(3)} (real ${realB[5].toFixed(3)}), corners ${gb[3].toFixed(3)} (real ${realB[3].toFixed(3)})`);
    // the fit is per standard deviation of each feature over the alternatives: step in raw units
    const sd = [1.3, 1.8, 0.4, 1.9];
    beta = beta.map((b, i) => b + 0.8 * gap[i] / sd[i]);
  }
  process.exit(0);
}
const fits = Object.fromEntries(Object.entries(sets).map(([k, v]) => [k, fit(v)]));
console.log(`${Ls.length} maps; weight per standard deviation of each feature (0 = no pull; + = the chosen spot has more of it)`);
console.log(''.padEnd(28) + Object.keys(fits).map(k => k.padStart(10)).join(''));
FEATS.forEach((f, k) => console.log(f.padEnd(28) + Object.values(fits).map(b => b[k].toFixed(3).padStart(10)).join('')));
console.log('choice sets: ' + Object.entries(sets).map(([k, v]) => `${k} ${v.length}`).join(', '));
