// Scores on real discovery order: the order rooms showed on the recorder's map in real party runs.
import { readFileSync } from 'node:fs';
import { split, score } from './evalcore.mjs';
import { loadTimelines } from './tl.mjs';
import { complete } from './common.mjs';
import { CLASSES, CLS, classOf, observe } from './sim.mjs';
import { analyse } from './features.mjs';
import { entries, subtrees } from './branch.mjs';
import { sample, SHAPES } from './sampler2.mjs';
import { makeRng } from './mlp.mjs';
const T = JSON.parse(readFileSync(process.argv[2] || 'tables3.json', 'utf8'));
const { train } = split();
const combo = new Map();
for (const L of train) { const c = SHAPES.map(s => L.rooms.filter(r => r.shape === s).length); if (c.reduce((a, b) => a + b) !== 7) continue; const k = c.join(''); combo.set(k, (combo.get(k) || 0) + 1); }
const combos = [...combo].map(([k, w]) => ({ counts: [...k].map(Number), w }));
const full = new Map(complete().map(L => [L.id, L]));
const tls = loadTimelines().filter(t => full.has(t.id) && t.start !== null);
console.log(tls.length, 'real timelines with complete layouts');
const snaps = [];
for (const t of tls) {
  const L = full.get(t.id);
  const order = [...t.seen.entries()].sort((a, b) => a[1] - b[1]).map(([i]) => i);
  for (const k of [2, 3, 4, 5, 6, 8, 10, 12, 15]) {
    if (k >= order.length) continue;
    const known = new Set(order.slice(0, k));
    // real order must be tree-connected for the observation to make sense
    const obs = observe(L, known); const A = analyse(obs);
    const Y = A.unknown.map(([x, z]) => CLS[classOf(L.rooms[L.grid[z][x]])]);
    const ents = entries(obs);
    const truth = subtrees(L, known, ents).map(tr => { const e = new Float64Array(CLASSES.length + 2); for (const r of tr) { e[CLS[classOf(L.rooms[r])]]++; e[CLASSES.length] += L.rooms[r].tiles.length; e[CLASSES.length + 1]++; } return e; });
    snaps.push({ L, k, known, obs, A, Y, ents, truth, sec: (t.seen.get(order[k - 1]) - t.start) / 20 });
  }
}
const secs = {}; for (const s of snaps) (secs[s.k] ??= []).push(s.sec);
console.log('seconds into the run at each stage (median):', Object.entries(secs).map(([k, v]) => k + ':' + v.sort((a, b) => a - b)[v.length >> 1].toFixed(0) + 's').join(' '));
const rnd = makeRng(5);
score('sampler2 on real discovery order', snaps, s => {
  const r = sample(s.obs, combos, { samples: 400, rnd, tables: T });
  const probs = r.probs.map(p => p || [1, 0, 0, 0, 0, 0, 0, 0]);
  const B = s.ents.map(e => { const j = r.entries.findIndex(q => q.t === e.z * s.obs.W + e.x); return j >= 0 ? r.branch[j] : { c: new Array(8).fill(0), tiles: 0 }; });
  return { probs, branch: B };
});
