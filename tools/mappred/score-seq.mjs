// Scores MapSequenceCheck's results (<prefix>.<variant>.pts.tsv and .views.tsv): the per-tile log-likelihood of what
// the worker would have shown at each budget point of each view, by the number of rooms known (BINS, default
// "2-3,4-5,6-8,9-11,12-15,16-40"), for each variant, the first one the reference: paired differences to it, their
// standard error over maps. Then what the pool did at the reveals (the newest samples' share of weight that fitted, the
// samples and batches' worth at once, import and view CPU time).
//   node score-seq.mjs <prefix> <variant> [<variant> ...]
import { readFileSync } from 'node:fs';
const [prefix, ...variants] = process.argv.slice(2);
if (!prefix || !variants.length) { console.error('node score-seq.mjs <prefix> <variant>...'); process.exit(1); }
const BINS = (process.env.BINS || '2-3,4-5,6-8,9-11,12-15,16-40').split(',').map(s => s.split('-').map(Number));
const binOf = k => BINS.findIndex(([a, b]) => k >= a && k <= b);
const tsv = f => { const [h, ...rows] = readFileSync(f, 'utf8').trim().split('\n'); const cols = h.split('\t'); return rows.map(r => Object.fromEntries(r.split('\t').map((v, i) => [cols[i], isNaN(+v) ? v : +v]))); };
const data = variants.map(v => ({ v, pts: tsv(`${prefix}.${v}.pts.tsv`), views: tsv(`${prefix}.${v}.views.tsv`) }));
const points = [...new Set(data[0].pts.map(r => r.point))];
const key = r => `${r.map}|${r.k}|${r.point}`;
const ref = new Map(data[0].pts.map(r => [key(r), r]));
const label = i => BINS[i].join('-');

function table(pointSel) {
  console.log(`\n== log-likelihood per tile at ${pointSel} particles (paired difference to ${variants[0]}, s.e. over maps)`);
  console.log(['rooms'.padEnd(7), 'views'.padStart(6), ...variants.map((v, i) => (i ? v + ' (diff ± se)' : v).padStart(i ? 26 : 10))].join(' '));
  const rows = [...BINS.map((_, i) => i), -1];
  for (const b of rows) {
    const line = [];
    let nViews = 0;
    for (const [vi, d] of data.entries()) {
      const by = new Map(); let ll = 0, tiles = 0;
      for (const r of d.pts) {
        if (r.point !== pointSel || (b >= 0 && binOf(r.k) !== b)) continue;
        const q = ref.get(key(r)); if (!q) continue;
        ll += r.ll; tiles += r.tiles; if (vi === 0) nViews++;
        const m = by.get(r.map) || { d: 0, t: 0, ll: 0 }; m.d += r.ll - q.ll; m.t += r.tiles; m.ll += r.ll; by.set(r.map, m);
      }
      if (!tiles) { line.push('-'.padStart(vi ? 26 : 10)); continue; }
      const mean = ll / tiles;
      if (vi === 0) line.push(mean.toFixed(4).padStart(10));
      else {
        const diffs = [...by.values()].map(m => m.d / m.t); const n = diffs.length;
        const mu = diffs.reduce((a, c) => a + c, 0) / n;
        const se = n > 1 ? Math.sqrt(diffs.reduce((a, c) => a + (c - mu) ** 2, 0) / (n - 1) / n) : NaN;
        line.push(`${mean.toFixed(4)} (${(mu >= 0 ? '+' : '') + mu.toFixed(4)} ± ${se.toFixed(4)})`.padStart(26));
      }
    }
    console.log([(b >= 0 ? label(b) : 'all').padEnd(7), String(nViews / (variants.length ? 1 : 1)).padStart(6), ...line].join(' '));
  }
}
for (const p of points) table(p);

console.log('\n== at the reveal (per variant): the share of the previous view\'s samples that fit, by rooms known');
for (const d of data) {
  console.log(`-- ${d.v}`);
  console.log(['rooms'.padEnd(7), 'views'.padStart(6), 'carried'.padStart(8), 'samples'.padStart(8), 'fit'.padStart(6), 'fit%'.padStart(7), 'wshare%'.padStart(8), 'B before'.padStart(9), 'B after'.padStart(8), 'inst B'.padStart(7), 'inst kept'.padStart(9), 'shown%'.padStart(7), 'import ms'.padStart(10), 'view ms'.padStart(8), 'tries'.padStart(8), 'kept'.padStart(7)].join(' '));
  const shownAt0 = new Map(d.pts.filter(r => r.point === 0).map(r => [`${r.map}|${r.k}`, r.shown]));
  for (const b of [...BINS.map((_, i) => i), -1]) {
    const rs = d.views.filter(r => b < 0 || binOf(r.k) === b);
    if (!rs.length) continue;
    const mean = f => rs.reduce((a, r) => a + f(r), 0) / rs.length;
    const withPool = rs.filter(r => r.carried && r.lastSeen > 0);
    const m2 = f => withPool.length ? withPool.reduce((a, r) => a + f(r), 0) / withPool.length : NaN;
    console.log([(b >= 0 ? label(b) : 'all').padEnd(7), String(rs.length).padStart(6), (100 * mean(r => r.carried)).toFixed(0).padStart(7) + '%', m2(r => r.lastSeen).toFixed(0).padStart(8), m2(r => r.lastFit).toFixed(0).padStart(6),
      (100 * m2(r => r.lastFit / r.lastSeen)).toFixed(1).padStart(7), (100 * m2(r => r.lastShare)).toFixed(2).padStart(8), m2(r => r.lastB0).toFixed(1).padStart(9), m2(r => r.lastB1).toFixed(1).padStart(8),
      mean(r => r.instantBatches).toFixed(1).padStart(7), mean(r => r.instantKept).toFixed(0).padStart(9), (100 * mean(r => shownAt0.get(`${r.map}|${r.k}`) || 0)).toFixed(0).padStart(6) + '%',
      mean(r => r.importMs).toFixed(1).padStart(10), mean(r => r.totalMs).toFixed(0).padStart(8), mean(r => r.tries).toFixed(0).padStart(8), mean(r => r.kept).toFixed(0).padStart(7)].join(' '));
  }
}

// What the player sees over a whole stay in a room: the prediction at each point holds until the next one, averaged
// over the dwell (the views' "dwell" column: particles; "end" is the state when the next room opens).
console.log('\n== log-likelihood per tile averaged over the dwell (each point\'s prediction held until the next; paired difference to ' + variants[0] + ')');
console.log(['rooms'.padEnd(7), ...variants.map((v, i) => (i ? v + ' (diff ± se)' : v).padStart(i ? 26 : 10))].join(' '));
const avgs = data.map(d => {
  const dwell = new Map(d.views.map(r => [`${r.map}|${r.k}`, r.dwell > 0 ? r.dwell : r.tries]));
  const byView = new Map();
  for (const r of d.pts) { const id = `${r.map}|${r.k}`; (byView.get(id) || byView.set(id, []).get(id)).push(r); }
  const out = new Map();
  for (const [id, rows] of byView) {
    const D = dwell.get(id) ?? 0;
    const pts = rows.filter(r => r.point !== 'end').map(r => ({ t: +r.point, ll: r.ll, tiles: r.tiles })).sort((a, b) => a.t - b.t);
    const endRow = rows.find(r => r.point === 'end');
    let ll = 0;
    for (let i = 0; i < pts.length; i++) {
      const t0 = pts[i].t; if (t0 >= D && i > 0) break;
      const t1 = i + 1 < pts.length && pts[i + 1].t < D ? pts[i + 1].t : D;
      ll += pts[i].ll * (t1 - t0) / D;
    }
    if (!(D > 0)) ll = endRow ? endRow.ll : pts[0].ll;
    out.set(id, { map: rows[0].map, k: rows[0].k, ll, tiles: rows[0].tiles });
  }
  return out;
});
for (const b of [...BINS.map((_, i) => i), -1]) {
  const line = [];
  for (const [vi, m] of avgs.entries()) {
    const by = new Map(); let ll = 0, tiles = 0;
    for (const [id, r] of m) {
      if (b >= 0 && binOf(r.k) !== b) continue;
      const q = avgs[0].get(id); if (!q) continue;
      ll += r.ll; tiles += r.tiles;
      const e = by.get(r.map) || { d: 0, t: 0 }; e.d += r.ll - q.ll; e.t += r.tiles; by.set(r.map, e);
    }
    if (!tiles) { line.push('-'.padStart(vi ? 26 : 10)); continue; }
    if (vi === 0) line.push((ll / tiles).toFixed(4).padStart(10));
    else {
      const diffs = [...by.values()].map(e => e.d / e.t); const n = diffs.length;
      const mu = diffs.reduce((a, c) => a + c, 0) / n;
      const se = n > 1 ? Math.sqrt(diffs.reduce((a, c) => a + (c - mu) ** 2, 0) / (n - 1) / n) : NaN;
      line.push(`${(ll / tiles).toFixed(4)} (${(mu >= 0 ? '+' : '') + mu.toFixed(4)} ± ${se.toFixed(4)})`.padStart(26));
    }
  }
  console.log([(b >= 0 ? label(b) : 'all').padEnd(7), ...line].join(' '));
}
