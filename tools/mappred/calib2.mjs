// Fits sampler2's MW (blood distance from the entrance) and FM (fairy distance from the entrance), and on F7
// BW (big rooms per tile), so that samples drawn from the first [stage] rooms of training dungeons match the
// real distributions. One table for all the FLOORS (env, default 7): FLOORS=2,3,4 fits the 5x5 floors; SAMPLES
// (env, default 30) per snapshot and iteration.
import { writeFileSync } from 'node:fs';
import { split, snapshots } from './evalcore.mjs';
import { sample, combosFor, defaultTables } from './sampler2.mjs';
import { prep } from './sim.mjs';
import { makeRng } from './mlp.mjs';
import { frameOf } from './features.mjs';
import { CLS } from './sim.mjs';
const [nMaps = 250, iters = 6, out = 'tables2.json'] = process.argv.slice(2);
const { train } = split();
const combos = combosFor(train);
const snaps = snapshots(train.slice(0, +nMaps), 5, 1, [2]);
const rules = snaps[0].L.rules, six = rules.w === 6 && rules.h === 6;
console.log(snaps.length, 'snapshots of', [...new Set(snaps.map(s => s.L.floor))].join(' '));
const rnd = makeRng(9);
const T = process.env.START ? JSON.parse((await import('node:fs')).readFileSync(process.env.START, 'utf8')) : defaultTables(rules);
const real = { MW: new Float64Array(16), FM: new Float64Array(16) };
const realBig = new Float64Array(36), seenTile = new Float64Array(36);
if (six) for (const s of snaps) { const fr = frameOf(s.obs); s.A.unknown.forEach(([x, z], i) => { const [a, b] = fr(x, z); seenTile[b * 6 + a]++; if (s.Y[i] === CLS.n) realBig[b * 6 + a]++; }); }
const md = (t, e) => Math.abs(t.x - e.x) + Math.abs(t.z - e.z);
for (const s of snaps) { const { E, B } = prep(s.L); const F = s.L.rooms.find(r => r.type === 'FAIRY'); real.MW[md(B.tiles[0], E.tiles[0])]++; real.FM[md(F.tiles[0], E.tiles[0])]++; }
for (const k in real) { const t = real[k].reduce((a, b) => a + b, 0); real[k] = real[k].map(v => v / t); }
for (let it = 0; it < +iters; it++) {
  const sim = { MW: new Float64Array(16), FM: new Float64Array(16) };
  const simBig = new Float64Array(36);
  for (const s of snaps) {
    const W = s.obs.W;
    const fr = frameOf(s.obs);
    const res = sample(s.obs, combos[s.L.floor[1]], { samples: +(process.env.SAMPLES || 30), rnd, tables: T, onSample: ({ rooms, type, ent, weight }) => {
      const b = rooms[type.indexOf('B')].tiles[0], f = rooms[type.indexOf('F')].tiles[0];
      sim.MW[Math.abs(b % W - ent[0]) + Math.abs(((b / W) | 0) - ent[1])] += weight;
      sim.FM[Math.abs(f % W - ent[0]) + Math.abs(((f / W) | 0) - ent[1])] += weight;
    } });
    if (six) res.unknown.forEach((t, i) => { const p = res.probs[i]; if (!p) return; const [a, b] = fr(t % 6, (t / 6) | 0); simBig[b * 6 + a] += p[CLS.n]; });
  }
  if (six) {
    let errB = 0;
    for (let k = 0; k < 36; k++) { if (!seenTile[k]) continue; const r = realBig[k] / seenTile[k], p = simBig[k] / seenTile[k]; errB += Math.abs(r - p) / 36; if (p > 0) T.BW[k] *= Math.pow(Math.max(r, 0.01) / p, 0.6); }
    { const mx = Math.max(...T.BW); T.BW = T.BW.map(v => v / mx); }
    console.log('big tile mean abs error', errB.toFixed(3));
  }
  let err = 0;
  for (const k in sim) {
    const t = sim[k].reduce((a, b) => a + b, 0);
    for (let i = 0; i < 16; i++) { const p = sim[k][i] / t; err += Math.abs(p - real[k][i]); if (p > 0 && T[k][i] > 0) T[k][i] *= Math.pow(Math.max(real[k][i], 1e-4) / p, 0.8); }
    const mx = Math.max(...T[k]); T[k] = T[k].map(v => v / mx);
  }
  console.log('iteration', it, 'abs error', err.toFixed(3), '');
}
console.log('real MW', [...real.MW].map(v => v.toFixed(3)).join(' '));
console.log('real FM', [...real.FM].map(v => v.toFixed(3)).join(' '));
writeFileSync(out, JSON.stringify(T));
console.log(JSON.stringify(T, (k, v) => typeof v === 'number' ? +v.toFixed(3) : v));
