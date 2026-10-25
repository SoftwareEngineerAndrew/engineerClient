// What rule picks the big rooms? Each candidate gives a chance to every room mix (summed over the orders the
// shapes could be drawn in); scored on the real mixes, the shape weights fitted for each rule.
//   LAYOUTS=layouts.jsonl node mixrule.mjs
// until22: random shapes (at most two of one) added until the big tiles reach 22; more than 7 rooms and the
//          draw starts again (so 6 rooms only when 6 make 22, 7 rooms 22-24 tiles, never 8)
// seven:   7 random shapes, drawn again unless they make 22 tiles or more
// seven22: 7 random shapes, drawn again unless exactly 22 (no 23s: shown for scale)
// until22nocap: as until22 without the 7-room cap (8 rooms when 7 fall short)
// skip:    random shapes added until 22 tiles, a shape that would go past 22 put back and another drawn, except
//          with chance p it's kept (and when nothing else is left); no room cap - 22 exactly can only be 6 or 7
//          rooms with at most two of a shape, so 7 rooms come out by themselves
// Each shape's weight w (second copy s times as likely) is fitted by coordinate search. ZMIX=file (placelik
// MIXRATE's): each mix's chance also times how often it gets placed with a door tree kept, for the model
// ZMODEL (the whole map made again on a failure, mix and all).
import { complete } from './common.mjs';
import { readFileSync } from 'node:fs';
const SH = ['TwoByOne', 'ThreeByOne', 'L', 'FourByOne', 'TwoByTwo'], SZ = [2, 3, 3, 4, 4];
const real = new Map(); let n = 0;
for (const L of complete()) { const c = [0, 0, 0, 0, 0]; for (const r of L.rooms) if (r.shape !== 'OneByOne') c[SH.indexOf(r.shape)]++; const k = c.join(''); real.set(k, (real.get(k) || 0) + 1); n++; }
// placelik's shape order is 1x2 1x3 1x4 2x2 L
const ZM = process.env.ZMIX ? JSON.parse(readFileSync(process.env.ZMIX, 'utf8')) : null;
const zOf = k => { if (!ZM) return 1; const c = k.split('').map(Number); return ZM[`${process.env.ZMODEL}|${[c[0], c[1], c[3], c[4], c[2]].join('')}`] ?? 0; };
/** chance of each mix (counts as a key) under a rule, shape weights w and second-copy factor s */
function dist(rule, w, s, p = 0) {
  const out = new Map();
  (function rec(c, p) {
    const rooms = c.reduce((a, b) => a + b, 0), tiles = c.reduce((a, b, i) => a + b * SZ[i], 0);
    const stop = rule.startsWith('until22') || rule === 'skip' ? tiles >= 22 : rooms === 7;
    if (stop) { if (rule === 'until22' && rooms > 7) return; if (rule === 'seven' && tiles < 22) return; if (rule === 'seven22' && tiles !== 22) return; const k = c.join(''); out.set(k, (out.get(k) || 0) + p); return; }
    if (rooms >= (rule === 'until22nocap' || rule === 'skip' ? 10 : 7)) return;
    const ws = c.map((v, i) => v >= 2 ? 0 : w[i] * (v ? s : 1));
    if (rule === 'skip' && ws.some((x, i) => x && tiles + SZ[i] <= 22)) ws.forEach((x, i) => { if (tiles + SZ[i] > 22) ws[i] = x * p; });
    const W = ws.reduce((a, b) => a + b, 0);
    ws.forEach((x, i) => { if (x) { c[i]++; rec(c, p * x / W); c[i]--; } });
  })([0, 0, 0, 0, 0], 1);
  for (const [k, v] of out) out.set(k, v * zOf(k));
  const Z = [...out.values()].reduce((a, b) => a + b, 0); for (const [k, v] of out) out.set(k, v / Z);
  return out;
}
let P = 0;
const ll = (rule, w, s) => { const d = dist(rule, w, s, P); let t = 0; for (const [k, v] of real) t += v * Math.log(Math.max(d.get(k) || 0, 1e-9)); return t / n; };
{ let t = 0; for (const [, v] of real) t += v * Math.log(v / n); console.log(`the best any rule could do (each mix at its own share): ${(t / n).toFixed(4)} per map`); }
for (const rule of (process.env.RULES || 'until22,until22nocap,seven,seven22').split(',')) {
  let w = [1, 1, 1, 1, 1], s = 1; const flat = ll(rule, w, s);
  for (let it = 0; it < 6; it++) {
    for (let i = 1; i < 5; i++) { let best = w[i]; for (const f of [0.5, 0.7, 0.85, 0.95, 1.05, 1.2, 1.4, 2]) { const w2 = [...w]; w2[i] *= f; if (ll(rule, w2, s) > ll(rule, [...w.slice(0, i), best, ...w.slice(i + 1)], s)) best = w2[i]; } w[i] = best; }
    { let best = s; for (const f of [0.5, 0.7, 0.85, 0.95, 1.05, 1.2, 1.4]) if (ll(rule, w, s * f) > ll(rule, w, best)) best = s * f; s = best; }
    if (rule === 'skip') { let best = P || 0.1; for (const f of [0.3, 0.6, 0.85, 1.2, 1.6, 3]) { const p0 = P; P = Math.min(1, (p0 || 0.1) * f); const a = ll(rule, w, s); P = best; if (a > ll(rule, w, s)) best = Math.min(1, (p0 || 0.1) * f); P = p0; } P = best; }
  }
  const d = dist(rule, w, s, P); const by = {}; for (const [k, v] of d) { const c = k.split('').map(Number); const key = `${c.reduce((a, b) => a + b, 0)} rooms ${c.reduce((a, b, i) => a + b * SZ[i], 0)} tiles`; by[key] = (by[key] || 0) + v; }
  const rb = {}; for (const [k, v] of real) { const c = k.split('').map(Number); const key = `${c.reduce((a, b) => a + b, 0)} rooms ${c.reduce((a, b, i) => a + b * SZ[i], 0)} tiles`; rb[key] = (rb[key] || 0) + v / n; }
  console.log(`\n${rule}: log-lik per map, equal weights ${flat.toFixed(4)}, fitted ${ll(rule, w, s).toFixed(4)}${rule === 'skip' ? ` (overshoot kept ${P.toFixed(3)})` : ''} (weights 1x2 1x3 L 1x4 2x2: ${w.map(x => x.toFixed(2)).join(' ')}, second copy x${s.toFixed(2)})`);
  for (const k of [...new Set([...Object.keys(by), ...Object.keys(rb)])].sort()) console.log(`   ${k.padEnd(18)} ${((by[k] || 0) * 100).toFixed(1).padStart(6)}%   real ${((rb[k] || 0) * 100).toFixed(1).padStart(6)}%`);
}
