// Every public ADR run, one day at a time from tomorrow back to [from] (both list views), into list-all.json.
import { writeFileSync } from 'node:fs';
const SITE = 'https://undonecoffee.com/adr/api/runs';
const DAY = 86400_000, from = Date.parse(process.argv[2] || '2026-08-01');
const have = new Map((await (await fetch(SITE)).json()).map(r => [r.id, r]));
for (let until = Date.now() + DAY; until > from; until -= DAY) {
  const since = until - DAY; let n = 0;
  for (const view of ['main', 'other']) {
    const res = await fetch(`${SITE}?view=${view}&since=${since}&until=${until}`);
    if (!res.ok) { console.error(view, res.status); continue; }
    const rows = await res.json();
    if (rows.length >= 5000) console.error('window full', view, since);
    for (const r of rows) if (!have.has(r.id)) { have.set(r.id, r); n++; }
    await new Promise(r => setTimeout(r, 300));
  }
  console.error(new Date(since).toISOString().slice(0, 10), n, have.size);
}
writeFileSync('list-all.json', JSON.stringify([...have.values()]));
