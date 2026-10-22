// Every public ADR run in the list (list-all.json): the latest list, then two days at a time going back
// until a run of empty windows.   node fetch-list.mjs
import { writeFileSync } from 'node:fs';
const SITE = 'https://undonecoffee.com/adr/api/runs';
const have = new Map((await (await fetch(SITE)).json()).map(r => [r.id, r]));
let until = Math.min(...[...have.values()].map(r => r.startMs)) + 3600_000;
const DAY = 86400_000;
let empty = 0;
while (empty < 6) {
  const since = until - 2 * DAY;
  let n = 0;
  for (const view of ['main', 'other']) {
    const res = await fetch(`${SITE}?view=${view}&since=${since}&until=${until}`);
    if (!res.ok) { console.error(view, res.status); continue; }
    for (const r of await res.json()) if (!have.has(r.id)) { have.set(r.id, r); n++; }
    await new Promise(r => setTimeout(r, 400));
  }
  console.error(new Date(since).toISOString().slice(0, 10), n, have.size);
  empty = n ? 0 : empty + 1;
  until = since;
}
writeFileSync('list-all.json', JSON.stringify([...have.values()]));
