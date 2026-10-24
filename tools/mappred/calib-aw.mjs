// Fits sampler2's AW (F7): each big shape's own weight per orientation and top-left tile, in the map's own
// directions, so that the big rooms sampled from the first rooms of training dungeons sit where the real
// ones do. BW (the shared per-tile weights, entrance frame) stays as START has it. BUNCH (env) as the
// predictor's FloorRules.bunch; SAMPLES (env, default 20) per snapshot and iteration.
//   START=tables.json node calib-aw.mjs <maps> <iterations> <out.json>
import { readFileSync, writeFileSync } from 'node:fs';
import { split, snapshots } from './evalcore.mjs';
import { sample, combosFor, defaultTables, PLACEMENTS, SHAPES } from './sampler2.mjs';
import { makeRng } from './mlp.mjs';
const [nMaps = 600, iters = 8, out = 'tables-aw.json'] = process.argv.slice(2);
const { train } = split();
const combos = combosFor(train);
const snaps = snapshots(train.slice(0, +nMaps), 5, 1, [2, 4]);
console.log(snaps.length, 'snapshots');
const T = process.env.START ? JSON.parse(readFileSync(process.env.START, 'utf8')) : defaultTables();
T.AW = Object.fromEntries(SHAPES.map(s => [s, PLACEMENTS[s].map(() => new Array(36).fill(1))]));
const W = 6;
/** A room's orientation (index into PLACEMENTS[shape]) and top-left tile. */
function placementOf(shape, tiles) {
  const xs = tiles.map(t => t % W), zs = tiles.map(t => (t / W) | 0), x0 = Math.min(...xs), z0 = Math.min(...zs);
  const set = new Set(tiles);
  const oi = PLACEMENTS[shape].findIndex(c => c.length === tiles.length && c.every(([dx, dz]) => set.has((z0 + dz) * W + x0 + dx)));
  return [oi, z0 * W + x0];
}
const zero = () => Object.fromEntries(SHAPES.map(s => [s, PLACEMENTS[s].map(() => new Float64Array(36))]));
const real = zero();
for (const s of snaps) for (const r of s.L.rooms) {
  if (r.shape === 'OneByOne' || s.known.has(r.i)) continue;
  const [oi, a] = placementOf(r.shape, r.tiles.map(t => t.z * W + t.x));
  if (oi >= 0) real[r.shape][oi][a]++;
}
const rnd = makeRng(13);
const bunch = +(process.env.BUNCH || 0);
for (let it = 0; it < +iters; it++) {
  const sim = zero();
  for (const s of snaps) {
    const got = [];
    sample(s.obs, combos['7'], { samples: +(process.env.SAMPLES || 20), rnd, tables: T, bunch, onSample: ({ rooms, weight }) => got.push([rooms, weight]) });
    const tot = got.reduce((a, g) => a + g[1], 0); if (!tot) continue;
    for (const [rooms, w] of got) for (const r of rooms) if (r.shape !== 'OneByOne' && r.orient !== undefined) sim[r.shape][r.orient][r.anchor] += w / tot;
  }
  let err = 0, n = 0;
  for (const s of SHAPES) {
    PLACEMENTS[s].forEach((_, oi) => {
      for (let t = 0; t < 36; t++) {
        const r = real[s][oi][t], m = sim[s][oi][t];
        if (r + m === 0) continue;
        err += Math.abs(r - m); n += r;
        T.AW[s][oi][t] *= Math.pow((r + 2) / (m + 2), 0.6);
      }
    });
    // only the shape's weights next to each other count: its largest is 1
    const mx = Math.max(...T.AW[s].flat()); T.AW[s] = T.AW[s].map(a => a.map(v => +(v / mx).toFixed(4)));
  }
  console.log(`iteration ${it}: placements off by ${(err / n * 100).toFixed(1)}% of the real ones`);
}
writeFileSync(out, JSON.stringify(T));
