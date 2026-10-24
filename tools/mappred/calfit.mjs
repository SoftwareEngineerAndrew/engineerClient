// A calibration layer on the sampler's per-tile chances: q_c ∝ p_c^a_c * e^(b_c + d_c * (rooms known - 6)),
// fitted on one dump (training maps) and scored on another (held-out), from evalbig's DUMPP rows
// [rooms known, true class, chance of each class (sampler2 OUT order)].
//   node calfit.mjs cal-train.json cal-test.json [out.json]
import { readFileSync, writeFileSync } from 'node:fs';
const [trainFile, testFile, out] = process.argv.slice(2);
const load = f => JSON.parse(readFileSync(f, 'utf8'));
const tr = load(trainFile), te = load(testFile);
const C = tr[0].length - 2, EPS = 1e-3;
const OUT = ['n', 'o', 'P', 'T', 'Y', 'B', 'F', 'R', 'x'].slice(0, C);
function apply(row, a, b, d) {
  const k = (row[0] - 6) / 3; const s = new Float64Array(C);
  for (let c = 0; c < C; c++) s[c] = a[c] * Math.log(row[2 + c] + EPS) + b[c] + d[c] * k;
  const m = Math.max(...s); let Z = 0; for (let c = 0; c < C; c++) { s[c] = Math.exp(s[c] - m); Z += s[c]; }
  for (let c = 0; c < C; c++) s[c] /= Z; return s;
}
function score(rows, a, b, d) {
  let ll = 0, big = 0;
  for (const r of rows) { const q = apply(r, a, b, d); ll += Math.log(Math.max(q[r[1]], 1e-4)); const pb = Math.min(Math.max(q[0], 1e-3), 1 - 1e-3); big += r[1] === 0 ? Math.log(pb) : Math.log(1 - pb); }
  return [ll / rows.length, big / rows.length];
}
// the sampler's own chances as they are: a = 1, b = d = 0 (with the same small floor)
let a = new Float64Array(C).fill(1), b = new Float64Array(C), d = new Float64Array(C);
const raw = [score(tr, a, b, d), score(te, a, b, d)];
// gradient ascent on the training rows' log-likelihood
for (let it = 0; it < 300; it++) {
  const ga = new Float64Array(C), gb = new Float64Array(C), gd = new Float64Array(C);
  for (const r of tr) { const q = apply(r, a, b, d), k = (r[0] - 6) / 3;
    for (let c = 0; c < C; c++) { const e = (r[1] === c ? 1 : 0) - q[c]; const lp = Math.log(r[2 + c] + EPS); ga[c] += e * lp; gb[c] += e; gd[c] += e * k; } }
  const lr = 0.5 / tr.length;
  for (let c = 0; c < C; c++) { a[c] += lr * ga[c] * 0.2; b[c] += lr * gb[c]; d[c] += lr * gd[c]; }
}
const fit = [score(tr, a, b, d), score(te, a, b, d)];
console.log(`${tr.length} training tiles, ${te.length} held-out tiles`);
console.log(`raw:        train ll ${raw[0][0].toFixed(4)} (big ${raw[0][1].toFixed(4)})   held-out ll ${raw[1][0].toFixed(4)} (big ${raw[1][1].toFixed(4)})`);
console.log(`calibrated: train ll ${fit[0][0].toFixed(4)} (big ${fit[0][1].toFixed(4)})   held-out ll ${fit[1][0].toFixed(4)} (big ${fit[1][1].toFixed(4)})`);
console.log('per class  a (sharpness)  b  d (per 3 rooms known)');
for (let c = 0; c < C; c++) console.log(`  ${OUT[c]}  ${a[c].toFixed(3).padStart(7)} ${b[c].toFixed(3).padStart(7)} ${d[c].toFixed(3).padStart(7)}`);
// how sure-and-right before and after: tiles at 70%+ for a big room
for (const [lab, A, B, D] of [['raw', new Float64Array(C).fill(1), new Float64Array(C), new Float64Array(C)], ['calibrated', a, b, d]]) {
  let n = 0, right = 0; for (const r of te) { const q = apply(r, A, B, D); if (q[0] >= 0.7) { n++; if (r[1] === 0) right++; } }
  console.log(`${lab}: held-out tiles 70%+ big ${n}, right ${(right / Math.max(n, 1) * 100).toFixed(1)}%`);
}
if (out) writeFileSync(out, JSON.stringify({ a: [...a], b: [...b], d: [...d] }));
