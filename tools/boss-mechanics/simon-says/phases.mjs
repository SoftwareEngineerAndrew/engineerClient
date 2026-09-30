import fs from 'fs';
const runs = JSON.parse(fs.readFileSync('ss.json')); const times = JSON.parse(fs.readFileSync('times.json'));
const whoOf = Object.fromEntries(times.map(t => [t.id, t]));
const by = {};
for (const r of runs) {
  const t = whoOf[r.id]; if (!t || t.shows !== '3,3,4,5') continue;
  const ev = r.ev.filter(e => e[0] >= r.p3 - 20 && e[0] <= r.p3 + 1200);
  const cnt = new Map(); for (const e of ev) if (e[1] === 'air' || e[1] === 'up') cnt.set(e[0] + e[1], (cnt.get(e[0] + e[1]) || 0) + 1);
  const mass = ev.filter(e => (e[1] === 'air' || e[1] === 'up') && cnt.get(e[0] + e[1]) >= 10).map(e => [e[0], e[1]]).filter((x, i, a) => !i || a[i - 1][0] !== x[0] || a[i - 1][1] !== x[1]);
  const ups = mass.filter(m => m[1] === 'up').map(m => m[0]), gones = mass.filter(m => m[1] === 'air').map(m => m[0]);
  const first = ev.find(e => e[1] === 'on')[0];
  if (ups.length < 4) continue;
  const row = { start: (first - r.p3) / 20 };
  ['r2', 'r3', 'r4', 'r5'].forEach((k, i) => { const g = gones.find(x => x > ups[i]); row[k] = g ? (g - ups[i]) / 20 : NaN; });
  row.total = t.fromP3;
  (by[t.who] ||= []).push(row);
}
const med = a => { const s = a.filter(x => !isNaN(x)).sort((p, q) => p - q); return s[s.length >> 1]; };
console.log('player'.padEnd(14), 'n', 'start ', ' 1st(2)', ' r3   ', ' r4   ', ' r5   ', ' total');
const rows = Object.entries(by).filter(([, a]) => a.length >= 2).sort((p, q) => med(p[1].map(x => x.total)) - med(q[1].map(x => x.total)));
for (const [who, a] of rows) console.log(who.padEnd(14), String(a.length).padStart(2), ...['start', 'r2', 'r3', 'r4', 'r5', 'total'].map(k => med(a.map(x => x[k])).toFixed(2).padStart(6)));
const me = by.TheBadOne; console.log('\nyour last 10 (start, first show, r3, r4, r5, total):'); for (const x of me.slice(-10)) console.log(['start', 'r2', 'r3', 'r4', 'r5', 'total'].map(k => x[k].toFixed(2)).join('  '));
