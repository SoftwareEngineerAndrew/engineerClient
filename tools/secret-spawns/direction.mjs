// Is it a sphere? Fit R per site separately for players approaching from each side (the player nearest at first sight).
import { loadPack, recordings } from './common.mjs';
import { events } from './events.mjs';
import { fit } from './fit.mjs';
const pack = loadPack(process.argv[2]);
const TRACK = { item: 28, bat: 40 };
const d3 = (p, s) => Math.hypot(p[0] - s[0], p[1] - s[1], p[2] - s[2]);
const by = new Map();
for (const rec of recordings(process.argv[3], +process.argv[4] || Infinity)) for (const e of events(pack, rec)) {
  if (!e.sid || !e.fresh) continue;
  if (e.kind !== 'skull') { let ok = true; for (let k = 1; k <= 10 && ok; k++) { const me = e.pos(k).find(([n]) => n === e.self); if (!me || Math.max(Math.abs(me[1][0] - e.x), Math.abs(me[1][2] - e.z)) > TRACK[e.kind]) ok = false; } if (!ok) continue; }
  const ref = e.kind === 'item' ? [Math.floor(e.x) + 0.5, Math.floor(e.y), Math.floor(e.z) + 0.5] : e.kind === 'bat' ? [e.x, e.y, e.z] : [e.x, e.y - 0.5, e.z];
  const inRoom = q => e.pl.owns(Math.floor(q[0]), Math.floor(q[2]));
  const me0 = e.pos(0).find(([n]) => n === e.self)?.[1];
  const recBox = me0 ? Math.max(Math.abs(me0[0] - ref[0]), Math.abs(me0[2] - ref[2])) : Infinity;
  let lb = Infinity, q0 = null; for (const [, q] of e.pos(0)) if (inRoom(q)) { const d = d3(q, ref); if (d < lb) { lb = d; q0 = q; } }
  // the side the spawn came from: where the nearest in-room player was 12 ticks before
  let ub = Infinity, q12 = null; for (const [, q] of e.pos(12)) if (inRoom(q)) { const d = d3(q, ref); if (d < ub) { ub = d; q12 = q; } }
  const from = q12 || q0; if (!from) continue;
  const dy = from[1] - ref[1];
  const side = Math.abs(dy) > Math.hypot(from[0] - ref[0], from[2] - ref[2]) ? (dy > 0 ? 'above' : 'below') : ['E', 'S', 'W', 'N'][Math.round(Math.atan2(from[2] - ref[2], from[0] - ref[0]) / (Math.PI / 2) + 4) % 4];
  const k = e.kind + ' ' + e.design + ' ' + e.sid; if (!by.has(k)) by.set(k, {});
  (by.get(k)[side] ||= []).push({ lb: { m: Number.isFinite(lb) && recBox + lb <= 44 ? lb : null }, ub: { m: ub } });
}
const diffs = { item: [], bat: [], skull: [] };
for (const [k, sides] of by) {
  const fits = Object.entries(sides).filter(([, v]) => v.length >= 25).map(([s, v]) => { const f = fit(v, 'm'); return [s, f.r, v.length, f.nlb]; }).filter(f => f[3] >= 10 && f[1] > 0.5);
  if (fits.length < 2) continue;
  const rs = fits.map(f => f[1]);
  const spread = Math.max(...rs) - Math.min(...rs);
  diffs[k.split(' ')[0]].push(spread);
  if (spread > 3) console.log(k.padEnd(30), fits.map(([s, r, n]) => `${s}:${r.toFixed(1)}(${n})`).join(' '));
}
const q = (a, p) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(p * (s.length - 1))]?.toFixed(2); };
for (const [k, a] of Object.entries(diffs)) console.log(k, a.length, 'sites; spread of R between sides: median', q(a, .5), 'q75', q(a, .75), 'q90', q(a, .9));
