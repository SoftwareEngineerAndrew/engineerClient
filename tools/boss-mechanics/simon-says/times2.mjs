import fs from 'fs';
const runs = JSON.parse(fs.readFileSync('ss.json')); const near = JSON.parse(fs.readFileSync('near.json'));
const rows = [];
for (const r of runs) {
  const ev = r.ev.filter(e => e[0] >= r.p3 - 20 && e[0] <= r.p3 + 1200);
  const ons = ev.filter(e => e[1] === 'on'); if (!ons.length) continue;
  // shows: clusters of lights
  const shows = []; let prev = -99;
  for (const e of ons) { if (e[0] - prev > 12) shows.push({ t: e[0], n: 0 }); shows[shows.length - 1].n++; shows[shows.length - 1].end = e[0]; prev = e[0]; }
  const last = shows[shows.length - 1]; if (last.n !== 5) continue;
  // completion: buttons gone (>=10 air at one tick) after the last show's buttons came up
  const cnt = new Map(); for (const e of ev) if (e[1] === 'air' || e[1] === 'up') cnt.set(e[0] + e[1], (cnt.get(e[0] + e[1]) || 0) + 1);
  const up = ev.find(e => e[1] === 'up' && e[0] > last.end && cnt.get(e[0] + 'up') >= 10);
  const gone = up && ev.find(e => e[1] === 'air' && e[0] > up[0] && cnt.get(e[0] + 'air') >= 10);
  if (!gone) continue;
  const n = near[r.id] || {}; const who = Object.entries(n).sort((a, b) => b[1] - a[1])[0]?.[0] || '?';
  const cls = (r.party || []).find(p => p[0] === who)?.[1] || '?';
  rows.push({ id: r.id, who, cls, fromP3: (gone[0] - r.p3) / 20, fromLight: (gone[0] - ons[0][0]) / 20, press: (gone[0] - up[0]) / 20, shows: shows.map(s => s.n).join(','), day: r.id.slice(0, 8) });
}
fs.writeFileSync('times.json', JSON.stringify(rows));
const med = a => { const s = [...a].sort((p, q) => p - q); return s[s.length >> 1]; };
const clean = x => x.shows === '3,3,4,5';
console.log('SS completions:', rows.length, 'clean 3,3,4,5:', rows.filter(clean).length);
const by = {}; for (const x of rows) (by[x.who] ||= []).push(x);
console.log('player'.padEnd(18), '  n clean  P3->done(med clean)  best  first-light->done  last-round-press  class');
for (const [who, a] of Object.entries(by).sort((p, q) => q[1].length - p[1].length)) {
  const c = a.filter(clean); if (a.length < 2) continue;
  console.log(who.padEnd(18), String(a.length).padStart(3), String(c.length).padStart(5), (c.length ? med(c.map(x => x.fromP3)).toFixed(2) : '-').padStart(10), (c.length ? Math.min(...c.map(x => x.fromP3)).toFixed(2) : '-').padStart(12), (c.length ? med(c.map(x => x.fromLight)).toFixed(2) : '-').padStart(10), (c.length ? med(c.map(x => x.press)).toFixed(2) : '-').padStart(14), '  ', [...new Set(a.map(x => x.cls))].join('/'));
}
