// Who stood at Simon Says: every player within 2.5 blocks of the standing spot, per run, P3 start to +40 s.
import fs from 'fs';
const out = {};
for (const f of fs.readdirSync('runs').filter(f => f.endsWith('.jsonl'))) {
  const text = fs.readFileSync('runs/' + f, 'utf8');
  const i = text.indexOf('Goldor: Who dares'); if (i < 0) continue;
  let p3 = null; const near = {};
  for (const l of text.split('\n')) {
    if (l.startsWith('{"k":"chat"') && p3 == null && l.includes('Goldor: Who dares')) p3 = JSON.parse(l).t;
    if (p3 == null || !l.startsWith('{"k":"p"')) continue;
    const o = JSON.parse(l); if (o.t < p3 || o.t > p3 + 800) continue;
    for (const q of o.d) if (Math.abs(q[1] - 108.3) < 2.5 && Math.abs(q[3] - 93.8) < 2.5 && q[2] >= 119.5 && q[2] <= 122) near[q[0]] = (near[q[0]] || 0) + 1;
  }
  out[f.replace('.jsonl', '')] = near;
}
fs.writeFileSync('near.json', JSON.stringify(out));
console.log(Object.keys(out).length);
