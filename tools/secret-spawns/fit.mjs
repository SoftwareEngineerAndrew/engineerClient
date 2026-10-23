// Per-site spawn radius: Hypixel checks every 5 ticks and spawns at the first check with a player within R.
// Each valid event gives LB (nearest player at the check that spawned it: R >= LB) and UB (nearest at the check
// before: R < UB). R per site is where the fewest events disagree.
//   node fit.mjs <pack> <ex dir> [limit] [metric]
import { loadPack, recordings } from './common.mjs';
import { events } from './events.mjs';

export const METRICS = {
  d3feet: (p, s) => Math.hypot(p[0] - s[0], p[1] - s[1], p[2] - s[2]),
  d3eye: (p, s) => Math.hypot(p[0] - s[0], p[1] + 1.62 - s[1], p[2] - s[2]),
  d3mid: (p, s) => Math.hypot(p[0] - s[0], p[1] + 0.9 - s[1], p[2] - s[2]),
  h: (p, s) => Math.hypot(p[0] - s[0], p[2] - s[2]),
  box: (p, s) => Math.max(Math.abs(p[0] - s[0]), Math.abs(p[1] - s[1]), Math.abs(p[2] - s[2])),
};
const TRACK = { item: 28, bat: 40 };

/** Valid events with lb/ub per metric. */
export function bounded(pack, rec, metrics = Object.keys(METRICS), K = { lb: 1, ub: 6 }) {
  const out = [];
  for (const e of events(pack, rec)) {
    if (!e.sid) continue;
    if (e.kind === 'skull' && !e.fresh) continue;
    if (e.kind === 'item' && !e.fresh) continue;
    // the entity would have been seen had it been there already: the recorder near enough, the 10 ticks before
    if (e.kind !== 'skull') {
      let ok = true;
      for (let k = 1; k <= 10 && ok; k++) {
        const me = e.pos(k).find(([n]) => n === e.self);
        if (!me || Math.max(Math.abs(me[1][0] - e.x), Math.abs(me[1][2] - e.z)) > TRACK[e.kind]) ok = false;
      }
      if (!ok) continue;
    }
    const ref = e.kind === 'item' ? [Math.floor(e.x) + 0.5, Math.floor(e.y), Math.floor(e.z) + 0.5] : e.kind === 'bat' ? [e.x, e.y, e.z] : [e.x, e.y - 0.5, e.z];
    const pl = e.pos(K.lb), pu = e.pos(K.ub);
    if (!pl.length) continue;
    const me = pl.find(([n]) => n === e.self)?.[1];
    const recBox = me ? Math.max(Math.abs(me[0] - ref[0]), Math.abs(me[2] - ref[2])) : Infinity;
    const r = { kind: e.kind, key: e.design + ' ' + e.sid, design: e.design, sid: e.sid, cell: e.cell, group: e.group, lb: {}, ub: {}, who: null };
    for (const m of metrics) {
      const f = METRICS[m];
      let lb = Infinity, who = null; for (const [n, q] of pl) { const d = f(q, ref); if (d < lb) { lb = d; who = n; } }
      let ub = Infinity; for (const [, q] of pu) ub = Math.min(ub, f(q, ref));
      // a player out of the recorder's sight (48 blocks) could have been nearer: LB only counts when nobody that near could hide
      r.lb[m] = recBox + lb <= 44 ? lb : null;
      r.ub[m] = ub;
      if (m === metrics[0]) r.who = who === e.self ? 'self' : 'mate';
    }
    out.push(r);
  }
  return out;
}

/** The R with the fewest disagreeing events: { r, lo, hi, bad, n }. lo..hi is the range of equally good R. */
export function fit(evs, m) {
  const lbs = evs.map(e => e.lb[m]).filter(v => v != null && Number.isFinite(v));
  const ubs = evs.map(e => e.ub[m]).filter(Number.isFinite);
  // bad(R) = #(LB > R) + #(UB <= R), constant between breakpoints: a sweep over [b_k, b_k+1)
  const pts = [...lbs.map(v => [v, -1]), ...ubs.map(v => [v, +1])].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  let bad = lbs.length, start = 0, best = { bad: Infinity, lo: 0, hi: 0 };
  const segs = [];
  for (const [v, d] of pts) { if (v > start) segs.push([start, v, bad]); bad += d; start = v; }
  segs.push([start, Infinity, bad]);
  const min = Math.min(...segs.map(s => s[2]));
  // merge touching best segments, keep the longest
  let cur = null;
  for (const [a, b, c] of segs) {
    if (c === min) { if (cur && Math.abs(cur[1] - a) < 1e-9) cur[1] = b; else { cur = [a, b]; if (!best.cur || b - a > best.cur[1] - best.cur[0]) best.cur = cur; } if (cur[1] - cur[0] >= best.cur[1] - best.cur[0]) best.cur = cur; }
    else cur = null;
  }
  const [lo, hi] = best.cur;
  return { lo, hi, r: Number.isFinite(hi) ? (lo + hi) / 2 : lo, bad: min, n: evs.length, nlb: lbs.length };
}

if (process.argv[1].endsWith('fit.mjs')) {
  const pack = loadPack(process.argv[2]);
  const lim = +(process.argv[4] || Infinity);
  const by = new Map();
  for (const rec of recordings(process.argv[3], lim)) for (const r of bounded(pack, rec)) { if (!by.has(r.key)) by.set(r.key, []); by.get(r.key).push(r); }
  const tot = {};
  for (const m of Object.keys(METRICS)) {
    let bad = 0, n = 0, width = 0, w = 0;
    for (const [k, evs] of by) { if (evs.length < 10) continue; const f = fit(evs, m); bad += f.bad; n += f.n; if (Number.isFinite(f.hi)) { width += f.hi - f.lo; w++; } }
    tot[m] = { badPct: (bad / n * 100).toFixed(2), meanWidth: (width / w).toFixed(2), n };
  }
  console.log(tot);
}
